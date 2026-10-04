package com.autoledger.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额值对象。
 *
 * 一律使用**最小货币单位**（人民币为「分」）整数存储，杜绝浮点误差。
 * 符号约定：负数 = 资金流出（支出 / 转出），正数 = 资金流入（收入 / 转入 / 退款）。
 */
data class Money(
    val minor: Long,
    val currency: String = DEFAULT_CURRENCY,
) {
    val isOutflow: Boolean get() = minor < 0
    val isInflow: Boolean get() = minor > 0
    fun abs(): Money = copy(minor = if (minor < 0) -minor else minor)
    fun negate(): Money = copy(minor = -minor)

    companion object {
        const val DEFAULT_CURRENCY = "CNY"
        private val YUAN_PATTERN = Regex("""-?\d{1,12}(,\d{3})*(\.\d{1,2})?""")
        private val YUAN_WITH_CONTEXT_PATTERN = Regex(
            """(?:[¥￥]\s*(-?\d{1,12}(?:,\d{3})*(?:\.\d{1,2})?)|(-?\d{1,12}(?:,\d{3})*(?:\.\d{1,2})?)\s*元)"""
        )

        /** 从任意文本中解析出金额，例如 "¥12.30" / "1,234.5" / "-32.00"。解析失败返回 null。 */
        fun fromYuan(text: String): Money? {
            // 优先使用货币符号或“元”附近的数字，避免把订单号误当成金额。
            val contextualMatch = YUAN_WITH_CONTEXT_PATTERN.find(text)
            val raw = contextualMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
                ?: YUAN_PATTERN.find(text.replace(" ", ""))?.value
                ?: return null
            val normalized = raw.replace(",", "").trim()
            val value = normalized.toBigDecimalOrNull() ?: return null
            return Money((value * BigDecimal("100")).toLong())
        }

        /** Double 先转十进制再四舍五入，避免 0.29 变成 28 分。 */
        fun fromYuanDouble(value: Double): Money = Money(
            BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        )
    }
}

/**
 * 以元为单位的可读格式，例如 1234 -> "12.34"；负值**始终**带负号。
 *
 * 这里曾经有个 `withSign: Boolean = true` 参数，但两个分支返回同一个空串
 * （`if (withSign) "" else ""`），从未生效过 —— 调用方写 `withSign = false` 以为能去掉负号，
 * 实际拿到的是带负号的字符串，是一处**静默失效的调用契约**。
 *
 * 现在把参数删掉，等于把「账本格式化必须始终可见符号」从"默认真相"固化成"唯一选项"。
 * **刻意不实现"去掉负号"这一支**：`feature:stats` 的「折合 ¥…」这类次要文案一旦吞掉负号，
 * 负结余会显示成正数，属于把亏损读成盈利级别的观感事故。需要绝对值请显式 `.abs()`。
 */
fun Money.formatYuan(): String {
    val negative = minor < 0
    val v = kotlin.math.abs(minor)
    val yuan = v / 100
    val fen = v % 100
    val body = if (fen == 0L) "$yuan" else "$yuan.${fen.toString().padStart(2, '0')}"
    return (if (negative) "-" else "") + body
}
