package com.autoledger.core.crypto

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM 加解密封装。
 *
 * 密文布局: `IV(12B) || ciphertext+tag`。
 *
 * **IV 由 provider 生成，不自建**：Android Keystore 的密钥在 ENCRYPT 模式下
 * **禁止调用方提供 IV**（会抛 `InvalidAlgorithmParameterException: Caller-provided IV not permitted`，
 * 在 Android 14 上必现）。因此加密时用 `init(ENCRYPT_MODE, key)` 让 provider 生成 IV，
 * 再从 `cipher.iv` 取回并写入密文头部 —— 布局不变，`open()` 仍按原方式解密，且对普通
 * `SecretKeySpec`（如 PBKDF2 派生的备份密钥）同样适用。
 */
class CryptoBox(private val key: SecretKey) {

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val body = cipher.doFinal(plain)
        return cipher.iv + body
    }

    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "密文长度不足" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)))
        }
        return cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size))
    }

    fun sealString(plain: String): String =
        Base64.encodeToString(seal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun openString(sealedB64: String): String = try {
        open(Base64.decode(sealedB64, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        throw CryptoException("解密失败，密钥或数据已损坏", e)
    } catch (e: IllegalArgumentException) {
        throw CryptoException("密文格式非法", e)
    }

    /** 生成随机 256bit 数据库口令 */
    fun randomPassphrase(): ByteArray = ByteArray(32).also { SECURE_RANDOM.nextBytes(it) }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        private val SECURE_RANDOM = SecureRandom()
    }
}

class CryptoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
