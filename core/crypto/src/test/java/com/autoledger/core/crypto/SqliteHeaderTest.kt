package com.autoledger.core.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 防回归护栏：确保「明文 / 已加密」的判定永远可靠。
 *
 * 历史教训：曾经以为在用 SQLCipher，实际静默降级成明文（只有真机能发现）。
 * 这条测试把判据钉死，防止再次出现"报了加密、实为明文"。
 */
class SqliteHeaderTest {

    @Test
    fun `unencrypted sqlite header is detected as plaintext`() {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertTrue(SqliteHeader.isPlaintext(header), "未加密的 SQLite 头必须判为明文")
    }

    @Test
    fun `extra bytes after the header are ignored`() {
        val bytes = ("SQLite format 3\u0000" + "rest of the file").toByteArray(Charsets.US_ASCII)
        assertTrue(SqliteHeader.isPlaintext(bytes))
    }

    @Test
    fun `encrypted random bytes are not plaintext`() {
        val encrypted = byteArrayOf(0xc5.toByte(), 0x7f.toByte(), 0xa3.toByte(), 0x81.toByte(),
            0x18.toByte(), 0x16.toByte(), 0x81.toByte(), 0x31.toByte(),
            0x2e.toByte(), 0x5d.toByte(), 0xad.toByte(), 0x8a.toByte(),
            0xdf.toByte(), 0xf1.toByte(), 0xe1.toByte(), 0xbe.toByte())
        assertFalse(SqliteHeader.isPlaintext(encrypted), "加密后的随机字节不能判为明文")
    }

    @Test
    fun `short or empty input is not treated as plaintext`() {
        assertFalse(SqliteHeader.isPlaintext(ByteArray(0)))
        assertFalse(SqliteHeader.isPlaintext("SQLite".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `corrupted header that differs by one byte is not plaintext`() {
        val almost = "SQLite format 4\u0000".toByteArray(Charsets.US_ASCII)
        assertFalse(SqliteHeader.isPlaintext(almost))
    }
}
