package com.autoledger.feature.ai

import android.util.Log

/**
 * AI 判定的**隐私安全日志**。
 *
 * 落盘内容被刻意限死为：时间戳 / 是否命中 AI / 耗时毫秒 / 失败原因分类 / 本地与 AI 的**枚举值**。
 *
 * ⚠️ **绝不落**：通知原文、商户名、金额、模型返回全文、接口地址、API 密钥。
 * 这不是洁癖 —— 原文与金额进了日志，就等于绕过「AI 关 = 数据不出设备」的用户承诺：
 * 用户关了 AI 之后，日志里仍留着他的通知内容。
 *
 * 日志只进 logcat，**不进 Room**（`listAllForBackup` 会把进库的东西带进备份档案）。
 */
interface AiDecisionLog {
    fun onDecided(hitAi: Boolean, elapsedMs: Long, localType: String?, aiType: String?, confidence: Double)
    fun onSkipped(reason: SkippedReason, elapsedMs: Long, localType: String)

    /** 回落原因分类（**不含任何业务内容**）。 */
    enum class SkippedReason {
        /** AI 总开关关闭。 */
        DISABLED,

        /** 兜底模式下本地已有结论，没必要问。 */
        LOCAL_DECIDED,

        /** 缺 endpoint / model / 密钥任一项。 */
        NOT_CONFIGURED,

        /** 超过整体时间预算。 */
        TIMEOUT,

        /** 传输层失败：连接被拒、非 2xx、读取异常。 */
        TRANSPORT_ERROR,

        /** 响应解析失败，或 type 不在候选内（TRANSFER/REFUND 一律不采纳）。 */
        BAD_RESPONSE,

        /** confidence 低于采纳阈值。 */
        LOW_CONFIDENCE,
    }
}

/** logcat 实现。用 [Log.d] 而不是 `println`：logcat 有级别过滤与 tag 便于排查。 */
object AndroidAiDecisionLog : AiDecisionLog {

    private const val TAG = "AutoLedgerAi"

    override fun onDecided(hitAi: Boolean, elapsedMs: Long, localType: String?, aiType: String?, confidence: Double) {
        // 注意这里只打枚举名（EXPENSE/INCOME/…），不打任何原文与金额。
        Log.d(TAG, "decide hitAi=$hitAi elapsedMs=$elapsedMs local=$localType ai=$aiType confidence=$confidence")
    }

    override fun onSkipped(reason: AiDecisionLog.SkippedReason, elapsedMs: Long, localType: String) {
        Log.d(TAG, "skip reason=$reason elapsedMs=$elapsedMs local=$localType")
    }
}

/** 测试用：只记录，不打印任何东西。 */
class RecordingAiDecisionLog : AiDecisionLog {
    val skips = mutableListOf<AiDecisionLog.SkippedReason>()
    var decisions = 0
    var lastConfidence: Double? = null

    override fun onDecided(hitAi: Boolean, elapsedMs: Long, localType: String?, aiType: String?, confidence: Double) {
        decisions++
        lastConfidence = confidence
    }

    override fun onSkipped(reason: AiDecisionLog.SkippedReason, elapsedMs: Long, localType: String) {
        skips += reason
    }
}
