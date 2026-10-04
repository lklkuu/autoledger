package com.autoledger.core.backup

import com.autoledger.core.model.AiMode
import com.autoledger.core.model.AppSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AI 配置在**备份档案**里的两条安全护栏。
 *
 * 必须跑 Robolectric：`org.json` 在纯 JVM 的 android.jar 里是 stub，
 * 不跑真实实现的话 `put` / `optBoolean` 全是空操作，测试通过但什么都没验证。
 *
 * 两条护栏缺一不可：
 *  1. **密钥绝不外泄** —— 断言导出 JSON 里没有任何密钥痕迹（这是本任务最重要的一条）；
 *  2. **配置确实外带** —— 反向锁住开关/模式/地址/模型名真的进了档案、且往返不失真
 *     （否则「保护」很容易被实现成「直接不导出」，换机就得重填）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsJsonAiTest {

    private val withAi = AppSettings(
        aiEnabled = true,
        aiMode = AiMode.ALWAYS,
        aiEndpoint = "https://ai.example.com/v1/chat/completions",
        aiModel = "gpt-4o-mini",
    )

    @Test
    fun `ai api key is NEVER present in exported settings json`() {
        val json = withAi.toSettingsJson().toString()

        // 明文里不许出现任何「密钥」字段名，也不许出现典型密钥前缀。
        for (forbidden in listOf("apiKey", "api_key", "apikey", "token", "secret", "password", "sk-")) {
            assertFalse(json.contains(forbidden, ignoreCase = true), "导出档案里出现了密钥痕迹：$forbidden")
        }

        // 键名层面也钉死：顶层与嵌套都不得有密钥键。
        assertFalse(jsonObjectHasKeyLike(jsonObjectOf(json), "key"), "导出档案里出现了 key 相关字段")
    }

    @Test
    fun `ai enabled mode endpoint survive a backup round trip`() {
        val restored = JSONObject(withAi.toSettingsJson().toString()).parseAppSettings()

        assertEquals(true, restored.aiEnabled, "AI 开关必须往返不失真")
        assertEquals(AiMode.ALWAYS, restored.aiMode, "AI 模式必须往返不失真")
        assertEquals("https://ai.example.com/v1/chat/completions", restored.aiEndpoint, "接口地址必须往返不失真")
        assertEquals("gpt-4o-mini", restored.aiModel, "模型名必须往返不失真")
    }

    @Test
    fun `legacy archive without ai fields still imports with safe defaults`() {
        // v1.1.6 及更早的档案没有这四个键：必须照旧能导入，且拿到「AI 关」的默认值。
        val legacy = JSONObject(
            """
            {"wage":{"monthlyNetSalaryMinor":1200000},"goal":{"targetMinor":12000000},"autoMerge":false}
            """.trimIndent(),
        )

        val restored = legacy.parseAppSettings()

        assertEquals(false, restored.aiEnabled, "老档案导入后 AI 必须默认关闭（升级不改变行为）")
        assertEquals(AiMode.FALLBACK, restored.aiMode)
        assertEquals("", restored.aiEndpoint)
        assertEquals("", restored.aiModel)
        assertEquals(false, restored.autoMerge, "既有字段也不该受影响")
    }

    @Test
    fun `unknown mode name falls back to FALLBACK instead of throwing`() {
        val dirty = JSONObject().apply {
            put("aiEnabled", true)
            put("aiMode", "SOMETHING_FROM_THE_FUTURE")
            put("aiEndpoint", "https://ai.example.com")
            put("aiModel", "m")
        }

        val restored = dirty.parseAppSettings()

        assertEquals(AiMode.FALLBACK, restored.aiMode, "未知枚举名必须回落 FALLBACK")
        assertEquals(true, restored.aiEnabled, "回落模式不影响其他字段")
    }

    // ------------------------------------------------------------------ 小工具

    private fun jsonObjectOf(raw: String): JSONObject = JSONObject(raw)

    /** 递归找出名字里含 "key"（忽略大小写）的键。 */
    private fun jsonObjectHasKeyLike(obj: JSONObject, needle: String): Boolean {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.contains(needle, ignoreCase = true)) return true
            val v = obj.opt(k)
            if (v is JSONObject && jsonObjectHasKeyLike(v, needle)) return true
        }
        return false
    }
}
