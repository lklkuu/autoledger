package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType

/**
 * 通知文本解析器：按规则包把「一串系统通知文本」翻译成结构化的金额 + 商户 + 方向。
 *
 * 两个刻意的设计：
 * 1. **宁可解析失败也不瞎猜**：没命中任何规则就返回 null，绝不入库，避免污染账本。
 * 2. **命中规则但金额提取失败**时仍然返回，只是 amount 为 null —— 交给「待确认」队列让人补，
 *    而不是悄悄扔掉一笔真实支出。
 */
class NotificationParser(
    private val rules: List<NotificationRule> = DefaultNotificationRules.PACK,
) {

    data class ParseResult(
        val ruleId: String,
        val ruleLabel: String,
        val amountMinor: Long?,
        val counterparty: String?,
        val direction: Direction,
        /**
         * 命中规则已确定的账本类型（如退款），会原样透传给 [com.autoledger.core.model.RawEnvelope.explicitType]。
         * null = 未指定，交由流水线按金额正负推断。
         */
        val explicitType: TxnType?,
    )

    fun parse(packageName: String, title: String, body: String): ParseResult? {
        if (body.isBlank() && title.isBlank()) return null
        val candidates = rules.filter { rule ->
            val pkgOk = rule.packageNames?.contains(packageName) ?: true
            if (!pkgOk) return@filter false
            when (packageName) {
                DefaultNotificationRules.PKG_WECHAT, DefaultNotificationRules.PKG_ALIPAY ->
                    rule.packageNames?.contains(packageName) == true
                else -> rule.packageNames == null
            }
        }
        // haystack（title + 正文）：只用于**关键词命中与拒绝判定**。
        val haystack = "$title\n$body"
        val hit = candidates.firstOrNull { rule ->
            val bodyOk = rule.bodyMustContainAny.isEmpty() || rule.bodyMustContainAny.any { haystack.contains(it) }
            val titleOk = rule.titleMustContainAny.isEmpty() || rule.titleMustContainAny.any { title.contains(it) }
            val notRejected = rule.bodyRejectAny.none { haystack.contains(it) } &&
                rule.bodyRejectPatterns.none { Regex(it).containsMatchIn(haystack) }
            bodyOk && titleOk && notRejected
        } ?: return null

        // 金额**只从正文提取**，不看 title：
        // 短信渠道传进来的 title 其实是**发件号码**（95555 / 95588 / 1069xxx），
        // 一旦把 title 混入金额搜索范围，发件号码就会被当成金额（收入虚增约 19 倍）。
        // 与之配套，各规则的金额正则也必须带明确上下文（见 DefaultNotificationRules 的注释）。
        // 取不到金额时宁可为 null 进「待确认」，也绝不瞎猜 —— 符合本解析器的设计原则 1。
        val amount = extractFirst(hit.amountPatterns, body)?.toMinor()
        val signed = when (hit.direction) {
            Direction.OUT -> amount?.let { -kotlin.math.abs(it) }
            Direction.IN -> amount?.let { kotlin.math.abs(it) }
        }
        val counterparty = extractFirst(hit.counterpartyPatterns, haystack)?.trim()
        return ParseResult(hit.id, hit.label, signed, counterparty, hit.direction, hit.ledgerType)
    }

    private fun extractFirst(patterns: List<String>, text: String): String? {
        for (p in patterns) {
            val m = Regex(p).find(text) ?: continue
            val value = m.groupValues.getOrNull(1)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** "12.30" / "1,234.5" -> 分 */
    private fun String.toMinor(): Long? {
        val cleaned = replace(",", "").replace("¥", "").replace("￥", "").trim()
        val yuan = cleaned.toBigDecimalOrNull() ?: return null
        return (yuan * java.math.BigDecimal(100)).toLong()
    }
}
