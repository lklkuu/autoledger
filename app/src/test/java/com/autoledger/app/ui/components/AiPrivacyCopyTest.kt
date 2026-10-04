package com.autoledger.app.ui.components

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 「隐私与安全」文案的条件化护栏。
 *
 * 这段文案是产品对用户的**承诺**：默认状态下「不发任何数据上云」必须原样保留；
 * 只有当用户**自己开启** AI 且**自己填了接口地址**时，才改成告知「正文会发到哪里」。
 *
 * 抽成纯函数就是为了能脱离 Compose 单测 —— 这类承诺不该只靠肉眼看预览。
 */
class AiPrivacyCopyTest {

    @Test
    fun `ai off keeps the original no-egress wording`() {
        val line = aiPrivacyLine(enabled = false, endpoint = "")

        assertEquals("不发任何数据上云；云同步仅有接口，当前是无操作的占位实现", line)
        assertFalse(line.contains("接口地址"), "AI 关闭时不得出现任何「发到某地址」的表述")
    }

    @Test
    fun `ai on but endpoint blank still keeps the original wording`() {
        // 开关开了但没填地址 ⇒ 实际上不会有任何请求发生，不能提前恐吓用户。
        assertEquals(
            "不发任何数据上云；云同步仅有接口，当前是无操作的占位实现",
            aiPrivacyLine(enabled = true, endpoint = "   "),
        )
    }

    @Test
    fun `ai on with endpoint discloses exactly where the text goes`() {
        val endpoint = "https://ai.example.com/v1/chat/completions"

        val line = aiPrivacyLine(enabled = true, endpoint = endpoint)

        assertTrue(line.contains(endpoint), "必须原样写出用户自己填的地址")
        assertTrue(line.contains("通知正文会发送到你配置的接口地址"))
        assertTrue(line.contains("除此之外不发任何数据上云"), "必须保留「除此之外」的边界声明")
    }

    @Test
    fun `api key never appears in the privacy copy`() {
        val line = aiPrivacyLine(enabled = true, endpoint = "https://ai.example.com")

        assertFalse(line.contains("sk-"), "隐私文案绝不能出现密钥形态的字符串")
        assertFalse(line.lowercase().contains("apikey"), "隐私文案绝不能提到密钥字段")
    }
}
