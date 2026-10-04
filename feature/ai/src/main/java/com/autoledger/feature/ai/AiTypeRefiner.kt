package com.autoledger.feature.ai

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * AI 收支类型判定器。
 *
 * 定位：**可选增强，永远不是单点故障**。任何一步不满足都返回 `null`，调用方继续用本地规则结论。
 *
 * 四道闸门（顺序即短路顺序）：
 * 1. 开关关闭 / 兜底模式下本地已有结论 / 配置不齐 ⇒ 根本不发请求；
 * 2. `withTimeoutOrNull(2s)` 硬超时（AI 在采集闸门内串行执行，超过 2 秒对收支二分类没有价值）；
 * 3. `runCatching` 吞掉一切异常；
 * 4. 响应 `type` 必须落在 [AI_CANDIDATES] 内且 `confidence >= [ADOPT_CONFIDENCE]`。
 *
 * 另有 [shouldAskAi] 决定「这一笔值不值得问」。
 *
 * 本类**不认识** `feature:capture` 的任何类型（端口在调用方），因此 feature 之间零横向依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiTypeRefiner(
    private val configProvider: suspend () -> AiConfig,
    private val transport: AiTransport = HttpAiTransport(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    private val log: AiDecisionLog = AndroidAiDecisionLog,
) {

    /**
     * @return 采纳的类型；`null` = 不采纳（调用方用本地结论）。
     */
    suspend fun decide(text: String, amountMinor: Long?, localGuess: TxnType, directionHint: Direction?): TxnType? {
        val config = configProvider()

        if (!config.enabled) {
            log.onSkipped(AiDecisionLog.SkippedReason.DISABLED, 0, localGuess.name)
            return null
        }
        if (!shouldAskAi(config.mode, amountMinor, directionHint)) {
            log.onSkipped(AiDecisionLog.SkippedReason.LOCAL_DECIDED, 0, localGuess.name)
            return null
        }
        if (!config.isReady) {
            // 未填密钥 / 地址 / 模型：宁可不用 AI，也不发一个必然失败的请求。
            log.onSkipped(AiDecisionLog.SkippedReason.NOT_CONFIGURED, 0, localGuess.name)
            return null
        }

        val startedAt = System.nanoTime()
        val body = runCatching { buildRequestBody(config.model, text, amountMinor, localGuess) }.getOrNull()
        if (body == null) {
            log.onSkipped(AiDecisionLog.SkippedReason.BAD_RESPONSE, 0, localGuess.name)
            return null
        }
        val raw = withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) {
                runCatching {
                    // ⚠️ runInterruptible 不能省：`HttpURLConnection` 是**阻塞**调用，
                    // 光靠 withTimeoutOrNull 取消不了它（超时只在阻塞返回后才被观察到），
                    // 那就等于没有硬超时。runInterruptible 会在取消时 interrupt 线程，
                    // 阻塞读随即抛异常被下面的 runCatching 接住 ⇒ 预算才真正生效。
                    runInterruptible { transport.post(config.endpoint, config.apiKey, body) }                }.getOrNull()
            }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        if (raw == null) {
            // 超时与传输失败在这一步无法区分（都被 runCatching / withTimeout 抹平），
            // 统一归为 TIMEOUT：对我们而言二者的处置完全一样——静默回落。
            log.onSkipped(AiDecisionLog.SkippedReason.TIMEOUT, elapsedMs, localGuess.name)
            return null
        }

        val parsed = runCatching { parseResponse(raw) }.getOrNull()
        if (parsed == null || parsed.first !in AI_CANDIDATES) {
            log.onSkipped(AiDecisionLog.SkippedReason.BAD_RESPONSE, elapsedMs, localGuess.name)
            return null
        }
        val (type, confidence) = parsed
        if (confidence < ADOPT_CONFIDENCE) {
            log.onSkipped(AiDecisionLog.SkippedReason.LOW_CONFIDENCE, elapsedMs, localGuess.name)
            return null
        }

        log.onDecided(hitAi = true, elapsedMs = elapsedMs, localType = localGuess.name, aiType = type.name, confidence = confidence)
        return type
    }

    /**
     * OpenAI 兼容请求体：`{ model, messages, temperature }`。
     *
     * ⚠️ `candidates` 只给 EXPENSE / INCOME（写进 system 提示词）——
     * 划转与退款由本地规则负责，不许 AI 选，否则会出现「AI 判了一笔划转成收入、
     * 而划转识别又把它改回去」这种来回抖动。
     */
    internal fun buildRequestBody(
        model: String,
        text: String,
        amountMinor: Long?,
        localGuess: TxnType,
    ): String {
        val amountText = amountMinor?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" } ?: "未知"
        val system = "你是记账助手，判断下面这条通知是支出还是收入。" +
            "只允许回答 ${AI_CANDIDATES.joinToString("/")} 之一。" +
            "请只输出 JSON：{\"type\":\"EXPENSE|INCOME\",\"confidence\":0到1的小数,\"reason\":\"不超过30字\"}。"
        val user = "通知原文：$text\n金额：$amountText\n本地初判：${localGuess.name}"
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", system)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", user)
                })
            })
        }.toString()
    }

    /** 解析 `{ type, confidence, reason }`；任一字段缺失/类型不对都返回 null。 */
    internal fun parseResponse(raw: String): Pair<TxnType, Double>? {
        val obj = JSONObject(raw)
        val typeName = obj.optString("type").takeIf { it.isNotBlank() } ?: return null
        val type = runCatching { TxnType.valueOf(typeName) }.getOrNull() ?: return null
        if (!obj.has("confidence")) return null
        val confidence = obj.optDouble("confidence", Double.NaN)
        if (confidence.isNaN()) return null
        return type to confidence
    }

    companion object {
        /** 硬超时。AI 在采集闸门内串行执行，超过 2 秒对收支二分类没有价值。 */
        const val DEFAULT_TIMEOUT_MS = 2_000L

        /** 采纳阈值：低于此置信度一律回落本地规则。 */
        const val ADOPT_CONFIDENCE = 0.8
    }
}
