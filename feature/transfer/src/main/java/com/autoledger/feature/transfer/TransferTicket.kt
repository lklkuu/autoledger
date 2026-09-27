package com.autoledger.feature.transfer

/**
 * 迁移配对的「一次性票据」：旧机把这些信息编进二维码，新机扫码即完成发现 + 配对。
 *
 * 约定：各字段**不含 `|` 字符**（SSID / 口令为常规字符串，token 建议 base64/hex，port 为数字）。
 * token 一次性、30 秒有效，作为连接鉴权，防重放。
 */
data class TransferTicket(
    val ssid: String,
    val password: String,
    val token: String,
    val port: Int,
) {
    fun encode(): String = listOf(ssid, password, token, port.toString()).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"

        fun decode(raw: String): TransferTicket? {
            val parts = raw.split(SEPARATOR)
            if (parts.size != 4) return null
            val port = parts[3].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return TransferTicket(parts[0], parts[1], parts[2], port)
        }
    }
}
