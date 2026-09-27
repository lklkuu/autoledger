package com.autoledger.feature.transfer

import java.security.MessageDigest
import java.util.BitSet

/**
 * 传输分块编解码 + 完整性校验。
 *
 * 把迁移包切成固定大小的块、逐块带序号传输；新机按序号拼接，最后用 SHA-256 整体校验。
 * 断点续传靠「已收块位图」：连接中断重连后只拉缺块。
 */
object TransferCodec {

    /** 分块大小：64 KB，兼顾传输效率与断点续传粒度。 */
    const val BLOCK_SIZE = 64 * 1024

    /** 切块：返回按序号排列的块列表。 */
    fun chunk(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val count = (data.size + BLOCK_SIZE - 1) / BLOCK_SIZE
        return (0 until count).map { i ->
            val from = i * BLOCK_SIZE
            val to = minOf(data.size, from + BLOCK_SIZE)
            data.copyOfRange(from, to)
        }
    }

    /** 组装：按序号拼接（调用方保证按序传入）。 */
    fun assemble(blocks: List<ByteArray>): ByteArray {
        val total = blocks.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (b in blocks) {
            b.copyInto(out, offset)
            offset += b.size
        }
        return out
    }

    /** 整体 SHA-256（十六进制小写），供双端比对。 */
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /** 已收块位图 → 字节（用于断点续传的进度持久化）。 */
    fun encodeBitmap(received: Set<Int>): ByteArray {
        val bits = BitSet()
        received.forEach { bits.set(it) }
        return bits.toByteArray()
    }

    /** 字节 → 已收块位图。 */
    fun decodeBitmap(bytes: ByteArray): Set<Int> {
        val bits = BitSet.valueOf(bytes)
        val out = mutableSetOf<Int>()
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            out.add(i)
            i = bits.nextSetBit(i + 1)
        }
        return out
    }
}
