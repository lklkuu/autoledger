package com.autoledger.feature.ai

import com.autoledger.core.model.AiMode
import com.autoledger.core.model.TxnType

/**
 * AI 判定的运行期配置快照。
 *
 * 只含**非敏感**的四项 + 运行时才注入的密钥；密钥不进 `AppSettings`、不进 Room、不进备份
 * （见 core:crypto 的 AiKeyVault 与 core:backup 的 SettingsJsonAiTest 护栏）。
 */
data class AiConfig(
    /** 总开关。false ⇒ 通知原文绝不出设备。 */
    val enabled: Boolean = false,
    val mode: AiMode = AiMode.FALLBACK,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
) {
    /** 配置是否齐全到「可以发一次请求」。缺任何一项都静默走本地规则。 */
    val isReady: Boolean
        get() = enabled && endpoint.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

/**
 * 是否值得为这一笔发请求。
 *
 * - [AiMode.FALLBACK]（默认，兜底）：**只在本地判不出时**才问 —— 本地"判不出"的可观测信号
 *   就是**金额缺失**（`resolveInitialType` 在有金额时永远能按正负给出 EXPENSE/INCOME）。
 *   因此有金额就不问，避免把每笔通知都发出去。
 * - [AiMode.ALWAYS]（全覆盖）：每笔都问。
 *
 * 纯函数，独立单测。
 */
internal fun shouldAskAi(mode: AiMode, amountMinor: Long?): Boolean = when (mode) {
    AiMode.FALLBACK -> amountMinor == null
    AiMode.ALWAYS -> true
}

/** AI 可以被允许产出的类型：**只有支出与收入**。划转/退款由本地规则负责，AI 不许选。 */
internal val AI_CANDIDATES: List<TxnType> = listOf(TxnType.EXPENSE, TxnType.INCOME)
