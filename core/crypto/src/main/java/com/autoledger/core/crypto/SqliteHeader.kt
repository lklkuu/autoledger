package com.autoledger.core.crypto

/**
 * SQLite 文件头判定（**纯函数，可 JVM 单测**）。
 *
 * 未加密的 SQLite 数据库文件，开头 16 字节恒为 `SQLite format 3\0`；
 * 被 SQLCipher 加密后的文件则是随机字节。
 *
 * 这是「防回归护栏」的核心判据：用来在运行期验证**数据库是否真的被加密了**，
 * 而不是"以为用了 SQLCipher、实际悄悄退化成明文"。
 * 这类问题编译期发现不了，只有真机（或本判据）能抓住。
 */
object SqliteHeader {

    /** SQLite 未加密文件头的固定内容（16 字节，末位为 \0）。 */
    val PLAINTEXT_SIGNATURE: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * @param prefix 待判定字节（通常取文件前 16 字节）
     * @return true 表示该数据库**没有加密**
     */
    fun isPlaintext(prefix: ByteArray): Boolean =
        prefix.size >= PLAINTEXT_SIGNATURE.size &&
            prefix.copyOf(PLAINTEXT_SIGNATURE.size).contentEquals(PLAINTEXT_SIGNATURE)
}
