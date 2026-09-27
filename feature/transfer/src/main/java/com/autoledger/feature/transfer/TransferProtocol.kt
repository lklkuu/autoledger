package com.autoledger.feature.transfer

import java.util.Base64

/**
 * 传输线协议（纯 JVM 可测，只负责消息编解码，不含任何 Socket 读写）。
 *
 * 文本头 + 二进制块的混合协议：
 * ```
 * 客户端 → 服务端：HELLO <token>\n                          （握手，token 鉴权）
 * 服务端 → 客户端：OK <envelope>\n                          （envelope = TransferEnvelope.encode()）
 * 服务端 → 客户端：BLOCK <index> <length>\n + <length 字节>    （逐块数据）
 * 服务端 → 客户端：END\n
 * 客户端 → 服务端：RESEND <bitmapBase64>\n                   （断点续传，只重发缺块）
 * ```
 */
object TransferProtocol {

    fun hello(token: String): ByteArray = "HELLO $token\n".toByteArray(Charsets.UTF_8)

    fun helloOk(envelope: TransferEnvelope): ByteArray = "OK ${envelope.encode()}\n".toByteArray(Charsets.UTF_8)

    fun block(index: Int, data: ByteArray): ByteArray {
        val header = "BLOCK $index ${data.size}\n".toByteArray(Charsets.UTF_8)
        return header + data
    }

    val end: ByteArray = "END\n".toByteArray(Charsets.UTF_8)

    fun resend(missing: Set<Int>): ByteArray {
        val b64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(TransferCodec.encodeBitmap(missing))
        return "RESEND $b64\n".toByteArray(Charsets.UTF_8)
    }

    /** 解析 `OK <envelope>` 行。 */
    fun parseOk(line: String): TransferEnvelope? {
        if (!line.startsWith("OK ")) return null
        return TransferEnvelope.decode(line.removePrefix("OK ").trim())
    }

    /** 解析 `BLOCK <index> <length>` 行。 */
    fun parseBlockHeader(line: String): Pair<Int, Int>? {
        if (!line.startsWith("BLOCK ")) return null
        val parts = line.split(" ")
        if (parts.size != 3) return null
        val index = parts[1].toIntOrNull() ?: return null
        val length = parts[2].toIntOrNull() ?: return null
        if (index < 0 || length < 0) return null
        return index to length
    }

    /** 解析 `RESEND <bitmapBase64>` 行。 */
    fun parseResend(line: String): Set<Int>? {
        if (!line.startsWith("RESEND ")) return null
        val b64 = line.removePrefix("RESEND ").trim()
        return runCatching {
            TransferCodec.decodeBitmap(Base64.getUrlDecoder().decode(b64))
        }.getOrNull()
    }
}
