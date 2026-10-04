package com.autoledger.app.feature

import com.autoledger.core.model.TxnType
import com.autoledger.feature.ai.AiConfig
import com.autoledger.core.model.AiMode
import com.autoledger.feature.ai.AiTransport
import com.autoledger.feature.ai.AiTypeRefiner
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 「AI 入口隐藏」这道门控的护栏。
 *
 * 背景：用户决定「AI 功能暂时先不上线，开关先隐藏，功能保留」（[AiFeatureGate.ENTRY_VISIBLE] = false）。
 *
 * **为什么"只把设置卡片藏起来"不够**：若数据库里残留 `aiEnabled = true`
 * （导入旧备份、或将来开放过又关闭），采集链路照样会发请求、照样把通知正文发出去 ——
 * 那就是"用户看不到开关，但数据在出网"，是最坏的一种状态。
 * 所以同一个门控必须同时被数据侧读到。这组用例钉的就是这一条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiFeatureGateTest {

    /** 返回 OpenAI 兼容响应体，让 [AiTypeRefiner] 能正常解析出结论。 */
    private class RecordingTransport(
        private val response: String = """{"type":"INCOME","confidence":0.99,"reason":"test"}""",
    ) : AiTransport {
        var calls = 0
        var lastEndpoint: String? = null
        override fun post(endpoint: String, apiKey: String, body: String): String {
            calls++
            lastEndpoint = endpoint
            return response
        }
    }

    /**
     * 对照组：门控打开 + 配置齐全时**确实会**发请求。
     *
     * 没有这条，下面那条"零请求"就可能是"因为 transport 根本没接上"而假绿。
     *
     * ⚠️ 本类整体挂 Robolectric：`buildRequestBody` 内部用 `org.json`，
     * 纯 JVM 下会被 android.jar 的 stub 拦掉（抛 "Stub!"）⇒ 请求体构建失败 ⇒
     * 请求根本没发出 ⇒ 对照组会假失败。这不是功能缺陷，真机上 `org.json` 是真实实现。
     */
    @Test
    fun `when gate is open a fully configured client does send`() = runTest {
        val transport = RecordingTransport()
        // ⚠️ 对照组**不能用** `AiFeatureGate.ENTRY_VISIBLE` 来构造"门控打开"的配置 ——
        // 那个常量现在是 false（入口隐藏），用它等于把对照组也关掉，这条会假失败。
        // 这里直接用 true 表达"假设门控已打开"，与 AppContainer 里的
        // `enabled = s.aiEnabled && AiFeatureGate.ENTRY_VISIBLE` 是同一个表达式形状。
        val gateOpen = true
        val refiner = AiTypeRefiner(
            configProvider = {
                AiConfig(
                    enabled = gateOpen,
                    mode = AiMode.ALWAYS,
                    endpoint = "https://example.invalid/v1",
                    model = "some-model",
                    apiKey = "placeholder-not-a-real-key",
                )
            },
            transport = transport,
        )
        val result = refiner.decide(text = "某条通知", amountMinor = null, localGuess = TxnType.EXPENSE)
        assertEquals(1, transport.calls, "对照组：门控打开且配置齐全时应当发出请求")
        assertEquals(TxnType.INCOME, result, "对照组：应采纳 AI 的结论")
    }

    /**
     * 核心护栏：门控关闭 + 数据里残留 `enabled = true`（模拟导入旧备份）⇒
     * **一次请求都不许发**，且静默回落到本地结论。
     */
    @Test
    fun `when gate is closed a stale enabled flag still sends nothing`() = runTest {
        val transport = RecordingTransport()
        // 模拟"用户曾经开着 AI、之后入口被关掉"，且这份数据是从旧备份导入的。
        // 门控常量保持**真实值**（当前为 false）—— 正是本用例要验证的那条。
        val staleEnabled = true
        val gateOpen = AiFeatureGate.ENTRY_VISIBLE
        val refiner = AiTypeRefiner(
            configProvider = {
                AiConfig(
                    enabled = staleEnabled && gateOpen,
                    mode = AiMode.ALWAYS,
                    endpoint = "https://example.invalid/v1",
                    model = "some-model",
                    // 门控关闭时连密钥都不该去解密，这里用哨兵值便于断言"没走到解密"。
                    apiKey = if (staleEnabled && gateOpen) "placeholder" else "",
                )
            },
            transport = transport,
        )
        val result = refiner.decide(text = "某条通知", amountMinor = null, localGuess = TxnType.EXPENSE)
        assertEquals(0, transport.calls, "门控关闭时绝不允许发出任何网络请求")
        assertNull(result, "门控关闭时应回落到本地结论（返回 null），不采纳任何 AI 结果")
    }

    /**
     * 「暂时不上线」这个决策的直接落点。
     * 恢复上线时改这一个常量即可（UI 与数据侧会同时恢复）。
     */
    @Test
    fun `entry is currently hidden`() {
        assertFalse(
            AiFeatureGate.ENTRY_VISIBLE,
            "需求：AI 功能暂时先不上线、开关先隐藏。恢复上线时把它改成 true。",
        )
    }
}
