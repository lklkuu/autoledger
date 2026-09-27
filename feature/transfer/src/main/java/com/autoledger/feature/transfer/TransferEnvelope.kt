package com.autoledger.feature.transfer

/**
 * 迁移包中 rawText（通知/短信原文）的处理策略。
 */
enum class RawTextPolicy {
    /** 原文已在旧机解密为明文，导入时必须用新机主密钥重加密（策略 A，保原文）。 */
    REWRAPPED,

    /** 原文密文原样保留。⚠️ 不适用于跨设备迁移——新机主密钥解不开旧机密文。 */
    PRESERVED,

    /** 原文已丢弃（策略 B 降级），只保留金额/商户/分类等账目字段。 */
    DROPPED,
}

/**
 * 迁移包信封：握手时由服务端（旧机）发给客户端（新机）的元数据。
 *
 * 各字段均不含 `|` 字符（版本号/设备名/枚举/数字/hex 摘要）。
 */
data class TransferEnvelope(
    val appVersion: String,
    val device: String,
    val rawTextPolicy: RawTextPolicy,
    val blockCount: Int,
    val totalBytes: Long,
    val sha256: String,
) {
    fun encode(): String = listOf(
        appVersion, device, rawTextPolicy.name, blockCount.toString(), totalBytes.toString(), sha256,
    ).joinToString(SEP)

    companion object {
        private const val SEP = "|"

        fun decode(raw: String): TransferEnvelope? {
            val p = raw.split(SEP)
            if (p.size != 6) return null
            val policy = runCatching { RawTextPolicy.valueOf(p[2]) }.getOrNull() ?: return null
            val blockCount = p[3].toIntOrNull() ?: return null
            val totalBytes = p[4].toLongOrNull() ?: return null
            if (blockCount < 0 || totalBytes < 0) return null
            return TransferEnvelope(p[0], p[1], policy, blockCount, totalBytes, p[5])
        }
    }
}
