package com.autoledger.core.model

/**
 * AI 判定模式。
 *
 * - [FALLBACK]（**默认**）：仅当本地规则**判不出**时（金额缺失、本地无结论）才问 AI；
 * - [ALWAYS]：每笔都问 AI，AI 的结论在达到采纳阈值时覆盖本地判断。
 *
 * 无论哪种模式，AI 都**不得**覆盖采集端已确定的类型（`explicitType != null`），
 * 也不得产出 [com.autoledger.core.model.TxnType.TRANSFER] / `REFUND` —— 那两类由本地规则负责。
 */
enum class AiMode {
    FALLBACK,
    ALWAYS,
}

/**
 * 应用级设置（时薪参数 + 自由基金目标 + 行为开关 + AI 判定配置）。
 * 作为一个整体往返于 Room 与备份档案，避免设置散落在多处存储。
 *
 * ⚠️ **AI 的 API 密钥刻意不在这里**（它不在任何 `AppSettings` 里，也就不在任何备份档案里）：
 * 密钥走 `AiKeyVault`（Keystore 包裹后存 SharedPreferences），绝不可能被导出带走。
 * 这里只放**可导出**的四项：开关 / 模式 / 接口地址 / 模型名。
 */
data class AppSettings(
    val wage: WageProfile = WageProfile(),
    val goal: FreedomGoal = FreedomGoal(),
    val autoMerge: Boolean = true,
    /** AI 判定总开关。默认关 ⇒ 升级后行为与本字段引入前**完全一致**（全本地处理，通知原文不出设备）。 */
    val aiEnabled: Boolean = false,
    val aiMode: AiMode = AiMode.FALLBACK,
    /** 用户自填的接口地址。默认留空，不预填任何服务商 URL。 */
    val aiEndpoint: String = "",
    /** 用户自填的模型名。为空时 AI 链路直接回落本地规则，不发请求。 */
    val aiModel: String = "",
)
