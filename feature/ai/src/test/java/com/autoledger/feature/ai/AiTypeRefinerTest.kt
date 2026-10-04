package com.autoledger.feature.ai

import com.autoledger.core.model.AiMode
import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AiTypeRefiner] 的判定逻辑护栏。
 *
 * 核心不变式：**AI 是可选增强，永不成为单点故障**。
 * 任何一步不满足（关掉 / 没配置 / 本地已有结论 / 超时 / 异常 / 低置信 / 非法类型）
 * 都必须返回 `null`（= 用本地规则结论），且**绝不能把异常抛给调用方**。
 *
 * 必须跑 Robolectric：请求体与响应解析都用 `org.json`，纯 JVM 的 android.jar 里它是 stub。
 * 注意这里**不打真实网络** —— 传输层是假的 [FakeTransport]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiTypeRefinerTest {

    private class FakeTransport(
        private val response: String = """{"type":"INCOME","confidence":0.95,"reason":"测试"}""",
        private val delayMs: Long = 0,
        private val error: Throwable? = null,
    ) : AiTransport {
        var calls = 0
        var lastBody: String? = null
        var lastEndpoint: String? = null
        var lastApiKey: String? = null

        override fun post(endpoint: String, apiKey: String, body: String): String {
            calls++
            lastEndpoint = endpoint
            lastApiKey = apiKey
            lastBody = body
            if (delayMs > 0) Thread.sleep(delayMs)
            error?.let { throw it }
            return response
        }
    }

    private val readyConfig = AiConfig(
        enabled = true,
        mode = AiMode.ALWAYS,
        endpoint = "https://ai.example.com/v1/chat/completions",
        model = "gpt-4o-mini",
        apiKey = "sk-test",
    )

    private fun refiner(
        config: AiConfig = readyConfig,
        transport: FakeTransport = FakeTransport(),
        timeoutMs: Long = 2_000L,
        log: RecordingAiDecisionLog = RecordingAiDecisionLog(),
    ) = Triple(AiTypeRefiner({ config }, transport, timeoutMs, log), transport, log)

    // ------------------------------------------------------------------ 门控：不该发就不发

    @Test
    fun `disabled never sends a request`() = runBlocking {
        val (ai, transport, log) = refiner(config = readyConfig.copy(enabled = false))

        assertNull(ai.decide("通知原文", 1_470L, TxnType.EXPENSE, directionHint = null))
        assertEquals(0, transport.calls, "开关关闭时一个请求都不该发")
        assertEquals(listOf(AiDecisionLog.SkippedReason.DISABLED), log.skips)
    }

    @Test
    fun `fallback mode stays local when amount is already known`() = runBlocking {
        val (ai, transport, log) = refiner(config = readyConfig.copy(mode = AiMode.FALLBACK))

        assertNull(ai.decide("某店消费 14.70", 1_470L, TxnType.EXPENSE, directionHint = null))
        assertEquals(0, transport.calls, "兜底模式 + 本地已有结论 ⇒ 不该发请求")
        assertEquals(listOf(AiDecisionLog.SkippedReason.LOCAL_DECIDED), log.skips)
    }

    @Test
    fun `fallback mode asks when local has no amount`() = runBlocking {
        val (ai, transport, _) = refiner(config = readyConfig.copy(mode = AiMode.FALLBACK))

        val decided = ai.decide("某笔扣款", null, TxnType.EXPENSE, directionHint = null)

        assertEquals(TxnType.INCOME, decided, "兜底模式问到 AI 且置信度足够 ⇒ 采纳 AI 的结论")
        assertEquals(1, transport.calls, "本地判不出（金额缺失）⇒ 必须问一次")
    }

    @Test
    fun `always mode asks even when amount is known`() = runBlocking {
        val (ai, transport, _) = refiner(config = readyConfig.copy(mode = AiMode.ALWAYS))

        ai.decide("某店消费 14.70", 1_470L, TxnType.EXPENSE, directionHint = null)

        assertEquals(1, transport.calls, "全覆盖模式 ⇒ 每笔都问")
    }

    @Test
    fun `incomplete configuration never sends a request`() = runBlocking {
        for (broken in listOf(
            readyConfig.copy(apiKey = ""),
            readyConfig.copy(endpoint = "  "),
            readyConfig.copy(model = ""),
        )) {
            val (ai, transport, log) = refiner(config = broken)
            assertNull(ai.decide("原文", null, TxnType.EXPENSE, directionHint = null))
            assertEquals(0, transport.calls, "配置不齐 [$broken] 时不该发请求")
            assertEquals(listOf(AiDecisionLog.SkippedReason.NOT_CONFIGURED), log.skips)
        }
    }

    // ------------------------------------------------------------------ 采纳

    @Test
    fun `a confident answer within candidates is adopted`() = runBlocking {
        val (ai, transport, log) = refiner()

        val decided = ai.decide("退款到账 50.00", 5_000L, TxnType.EXPENSE, directionHint = null)

        assertEquals(TxnType.INCOME, decided)
        assertEquals(1, transport.calls)
        assertEquals("https://ai.example.com/v1/chat/completions", transport.lastEndpoint)
        assertEquals("sk-test", transport.lastApiKey)
        assertTrue(transport.lastBody!!.contains("gpt-4o-mini"), "请求体必须带上用户配置的模型名")
        assertEquals(1, log.decisions)
    }

    @Test
    fun `only expense and income are ever accepted as candidates`() = runBlocking {
        // AI 若（或被诱导）返回划转/退款，一律不采纳。
        for (illegal in listOf("TRANSFER", "REFUND", "NOT_A_TYPE")) {
            val (ai, _, log) = refiner(transport = FakeTransport(response = """{"type":"$illegal","confidence":0.99}"""))
            assertNull(ai.decide("原文", null, TxnType.EXPENSE, directionHint = null), "AI 返回 $illegal 必须被拒绝")
            assertTrue(log.skips.contains(AiDecisionLog.SkippedReason.BAD_RESPONSE))
        }
    }

    // ------------------------------------------------------------------ 回落

    @Test
    fun `low confidence falls back to local`() = runBlocking {
        val (ai, _, log) = refiner(transport = FakeTransport(response = """{"type":"EXPENSE","confidence":0.79}"""))

        assertNull(ai.decide("原文", null, TxnType.EXPENSE, directionHint = null), "0.79 < 阈值 0.8 ⇒ 不采纳")
        assertTrue(log.skips.contains(AiDecisionLog.SkippedReason.LOW_CONFIDENCE))
    }

    @Test
    fun `malformed response falls back to local`() = runBlocking {
        for (bad in listOf("not json at all", """{"type":"EXPENSE"}""", """{"confidence":0.9}""", "[]")) {
            val (ai, _, log) = refiner(transport = FakeTransport(response = bad))
            assertNull(ai.decide("原文", null, TxnType.EXPENSE, directionHint = null), "坏响应 [$bad] 必须回落")
            assertTrue(log.skips.contains(AiDecisionLog.SkippedReason.BAD_RESPONSE))
        }
    }

    @Test
    fun `transport exception falls back to local without throwing`() = runBlocking {
        val (ai, _, log) = refiner(transport = FakeTransport(error = java.io.IOException("connect refused")))

        assertNull(ai.decide("原文", null, TxnType.EXPENSE, directionHint = null), "传输失败必须静默回落")
        assertTrue(log.skips.contains(AiDecisionLog.SkippedReason.TIMEOUT))
    }

    @Test
    fun `over-budget latency falls back to local`() = runBlocking {
        // 预算 50ms、假传输睡 300ms。
        // ⚠️ 这条同时钉住了「必须用 runInterruptible」：HttpURLConnection 是阻塞调用，
        // 光靠 withTimeoutOrNull 取消不了它（超时只在阻塞返回后才被观察到），
        // 那样这条就会返回 AI 的结论而不是回落 —— 硬超时形同虚设。
        val (ai, _, log) = refiner(transport = FakeTransport(delayMs = 300), timeoutMs = 50)

        assertNull(ai.decide("原文", null, TxnType.EXPENSE, directionHint = null), "超预算必须回落")
        assertTrue(log.skips.contains(AiDecisionLog.SkippedReason.TIMEOUT))
    }

    @Test
    fun `request body carries model and the notification text but never the api key`() = runBlocking {
        val (ai, transport, _) = refiner()

        ai.decide("郑思强麻辣烫 14.70", 1_470L, TxnType.EXPENSE, directionHint = null)

        val body = transport.lastBody!!
        assertTrue(body.contains("郑思强麻辣烫"), "通知原文必须随请求发出（AI 开启的前提）")
        assertTrue(body.contains("14.70"), "金额必须随请求发出")
        assertTrue(!body.contains("sk-test"), "⚠️ 密钥绝不能出现在请求体里")
    }

    @Test
    fun `shouldAskAi gate is a pure function of mode, amount and direction`() {
        // 原有 5 条断言（补 directionHint = null，保持原语义逐位不变）
        assertTrue(shouldAskAi(AiMode.FALLBACK, null, directionHint = null))
        assertTrue(!shouldAskAi(AiMode.FALLBACK, 0L, directionHint = null))
        assertTrue(!shouldAskAi(AiMode.FALLBACK, 1_470L, directionHint = null))
        assertTrue(shouldAskAi(AiMode.ALWAYS, 1_470L, directionHint = null))
        assertTrue(shouldAskAi(AiMode.ALWAYS, null, directionHint = null))

        // D1：金额缺失但方向已知 ⇒ 本地已判出 ⇒ 不问（修复前是 true，即「已判出却仍出网」）
        assertTrue(!shouldAskAi(AiMode.FALLBACK, null, Direction.IN))
        // D2：真·判不出（金额与方向都缺）⇒ 仍要问
        assertTrue(shouldAskAi(AiMode.FALLBACK, null, null))
        // D3：全覆盖模式下不受影响
        assertTrue(shouldAskAi(AiMode.ALWAYS, null, Direction.IN))
    }

    /**
     * D1b（最强的一条）：完整 `decide` 层面的「不出网」证据（隐私口径）。
     *
     * 修复后：金额缺失但方向已知 ⇒ 本地已判出 ⇒ FALLBACK 模式下**一个网络请求都不发**。
     * 为一件本地已解决的事把通知原文发往外部服务，正是本用例要堵死的回归。
     */
    @Test
    fun `fallback mode sends nothing when the direction is already known`() = runBlocking {
        val (ai, transport, log) = refiner(config = readyConfig.copy(mode = AiMode.FALLBACK))

        assertNull(ai.decide("工资已转入", null, TxnType.INCOME, Direction.IN))
        assertEquals(0, transport.calls, "本地已判出方向 ⇒ 不得发出任何网络请求（隐私口径）")
        assertEquals(listOf(AiDecisionLog.SkippedReason.LOCAL_DECIDED), log.skips)
    }

    /** 反向对照：金额与方向都缺失 ⇒ 真·判不出 ⇒ 必须发一次请求。 */
    @Test
    fun `fallback mode sends exactly one request when both amount and direction are unknown`() = runBlocking {
        val (ai, transport, _) = refiner(config = readyConfig.copy(mode = AiMode.FALLBACK))

        ai.decide("某笔扣款", null, TxnType.EXPENSE, directionHint = null)

        assertEquals(1, transport.calls, "金额与方向都缺失 ⇒ 真·判不出 ⇒ 必须问一次")
    }
}
