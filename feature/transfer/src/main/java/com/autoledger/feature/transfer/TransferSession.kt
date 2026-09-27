package com.autoledger.feature.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket

/** 服务端构造信封所需的元数据（appVersion / device / rawTextPolicy）。 */
data class TransferMeta(
    val appVersion: String,
    val device: String,
    val rawTextPolicy: RawTextPolicy,
)

/** 传输会话异常。 */
class TransferException(message: String) : RuntimeException(message)

/**
 * 传输会话：执行 [TransferProtocol] 的握手 + 分块收发 + 断点续传。
 *
 * 只用 `java.net.Socket`（不依赖 Android），可纯 JVM 单测（localhost 回环）。
 * Android 的「拉起热点 / 连 wifi」是外层薄壳，负责拿到一个已连接的 [Socket] 后交给本类。
 */
class TransferSession(private val socket: Socket) {

    /**
     * 服务端（旧机）：验证握手 token → 发送整个 payload（分块）→ 响应断点续传请求。
     */
    fun serve(
        payload: ByteArray,
        expectedToken: String,
        meta: TransferMeta,
        onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> },
    ): TransferEnvelope {
        val blocks = TransferCodec.chunk(payload)
        val envelope = TransferEnvelope(
            appVersion = meta.appVersion,
            device = meta.device,
            rawTextPolicy = meta.rawTextPolicy,
            blockCount = blocks.size,
            totalBytes = payload.size.toLong(),
            sha256 = TransferCodec.sha256Hex(payload),
        )
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())

            val hello = readLine(input) ?: throw TransferException("连接在握手前关闭")
            if (!hello.startsWith("HELLO ") || hello.removePrefix("HELLO ") != expectedToken) {
                throw TransferException("token 不匹配")
            }
            out.write(TransferProtocol.helloOk(envelope))
            out.flush()

            sendAll(out, blocks, onProgress)
            // 断点续传：客户端会发 RESEND <bitmap>，直到收不到为止
            while (true) {
                val line = readLine(input) ?: break
                val missing = TransferProtocol.parseResend(line) ?: break
                missing.filter { it in blocks.indices }
                    .forEach { out.write(TransferProtocol.block(it, blocks[it])) }
                out.write(TransferProtocol.end)
                out.flush()
            }
        }
        return envelope
    }

    /**
     * 客户端（新机）：握手 → 接收分块 → 缺块时请求重发 → SHA-256 校验。
     */
    fun receive(
        token: String,
        onProgress: (received: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<ByteArray, TransferEnvelope> {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())

            out.write(TransferProtocol.hello(token))
            out.flush()

            val ok = readLine(input) ?: throw TransferException("连接在握手后关闭")
            val envelope = TransferProtocol.parseOk(ok) ?: throw TransferException("协议错误：期望 OK")

            val blocks = arrayOfNulls<ByteArray>(envelope.blockCount)
            receiveBlocks(input, blocks, onProgress)

            // 缺块 → 请求重发一次
            val missing = blocks.indices.filter { blocks[it] == null }
            if (missing.isNotEmpty()) {
                out.write(TransferProtocol.resend(missing.toSet()))
                out.flush()
                receiveBlocks(input, blocks, onProgress)
            }
            if (blocks.any { it == null }) throw TransferException("仍有缺块，传输不完整")

            val payload = TransferCodec.assemble(blocks.filterNotNull())
            if (TransferCodec.sha256Hex(payload) != envelope.sha256) {
                throw TransferException("SHA-256 校验失败")
            }
            return payload to envelope
        }
    }

    private fun sendAll(out: BufferedOutputStream, blocks: List<ByteArray>, onProgress: (Int, Int) -> Unit) {
        blocks.forEachIndexed { i, b ->
            out.write(TransferProtocol.block(i, b))
            onProgress(i + 1, blocks.size)
        }
        out.write(TransferProtocol.end)
        out.flush()
    }

    private fun receiveBlocks(input: BufferedInputStream, blocks: Array<ByteArray?>, onProgress: (Int, Int) -> Unit) {
        while (true) {
            val line = readLine(input) ?: break
            if (line == "END") break
            val (idx, len) = TransferProtocol.parseBlockHeader(line)
                ?: throw TransferException("协议错误：期望 BLOCK，收到 $line")
            val data = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(data, off, len - off)
                if (n < 0) throw TransferException("块数据被截断")
                off += n
            }
            if (idx in blocks.indices) {
                blocks[idx] = data
                onProgress(blocks.count { it != null }, blocks.size)
            }
        }
    }

    /** 按行读（字节级，避免与二进制块数据混用 BufferedReader）。 */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
