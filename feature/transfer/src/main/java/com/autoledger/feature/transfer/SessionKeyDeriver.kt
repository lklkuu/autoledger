package com.autoledger.feature.transfer

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 从扫码 token 派生传输会话密钥（HKDF-SHA256，RFC 5869）。
 *
 * 会话密钥**只用于加密迁移包在链路上的传输**，用完即弃、不落盘。
 * token 是一次性、短时效（30 秒），本身有足够熵，因此无需 PBKDF2 那样的大迭代。
 */
object SessionKeyDeriver {

    const val KEY_BITS = 256
    private const val HMAC = "HmacSHA256"

    /** 派生 AES-256 会话密钥。 */
    fun derive(token: String, salt: ByteArray, info: String = "autoledger-transfer"): SecretKeySpec {
        val prk = extract(salt.takeIf { it.isNotEmpty() }, token.toByteArray(Charsets.UTF_8))
        val okm = expand(prk, info.toByteArray(Charsets.UTF_8), KEY_BITS / 8)
        return SecretKeySpec(okm, "AES")
    }

    /** 生成随机盐（16 字节）。 */
    fun randomSalt(): ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    // HKDF-Extract：PRK = HMAC(salt, IKM)。salt 为空时按 RFC 5869 用零串。
    private fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt ?: ByteArray(HMAC_SHA256_LEN), HMAC))
        return mac.doFinal(ikm)
    }

    // HKDF-Expand：OKM = T(1) || T(2) || ...，T(i) = HMAC(PRK, T(i-1) || info || i)
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(prk, HMAC))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }

    private const val HMAC_SHA256_LEN = 32
}
