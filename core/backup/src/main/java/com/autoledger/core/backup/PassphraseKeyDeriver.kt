package com.autoledger.core.backup

import android.util.Base64
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 从用户口令派生导出密钥（PBKDF2 + HMAC-SHA256）。
 *
 * 默认本机不需要口令 —— 数据库本身是 SQLCipher 加密的。
 * 只有在用户要「把备份文件存到网盘 / 发给别人」时才启用口令，
 * 那时即便文件被拿到，没有口令也只是乱码。
 */
object PassphraseKeyDeriver {

    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 210_000
    const val KEY_BITS = 256
    const val SALT_BYTES = 16

    fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKey {
        val spec: KeySpec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val skf = SecretKeyFactory.getInstance(KDF)
        return SecretKeySpec(skf.generateSecret(spec).encoded, "AES")
    }

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** 擦掉 char[] 里的明文，降低内存 dump 命中率 */
    fun wipe(passphrase: CharArray) = passphrase.fill('\u0000')
}
