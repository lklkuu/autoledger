package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 「解析结果 → RawEnvelope」映射的护栏（T8）。
 *
 * 覆盖 T1 修复的**本体那一行** `explicitType = parsed.explicitType`：
 * 删掉它，本测试必须变红 —— 此前该行埋在 Listener / SmsSource 的内联构造里、零覆盖，
 * 可以被随意删除而全量测试仍绿（退款会悄悄退化回「靠关键词二次命中」）。
 */
class CaptureEnvelopeFactoryTest {

    private val parser = NotificationParser()

    @Test
    fun `wechat refund envelope carries explicit refund type`() {
        val parsed = assertNotNull(
            parser.parse(DefaultNotificationRules.PKG_WECHAT, "微信支付", "已退款 ¥12.30"),
            "退款文案应命中退款规则",
        )
        val env = toRawEnvelope(
            sourceId = "notify",
            sourceRef = "notify:ref-1",
            occurredAtMillis = 1_000L,
            rawText = "微信支付\n已退款 ¥12.30",
            packageName = DefaultNotificationRules.PKG_WECHAT,
            parsed = parsed,
        )
        assertEquals(TxnType.REFUND, env.explicitType, "退款信封必须显式下发 REFUND")
        assertEquals(1_230L, env.amountHint)
    }

    @Test
    fun `wechat payment envelope carries no explicit type`() {
        val parsed = assertNotNull(
            parser.parse(DefaultNotificationRules.PKG_WECHAT, "微信支付", "微信支付凭证\n付款金额 ¥25.00"),
        )
        val env = toRawEnvelope(
            sourceId = "notify",
            sourceRef = "notify:pay-1",
            occurredAtMillis = 2_000L,
            rawText = "微信支付\n微信支付凭证\n付款金额 ¥25.00",
            packageName = DefaultNotificationRules.PKG_WECHAT,
            parsed = parsed,
        )
        assertNull(env.explicitType, "付款信封不得下发退款类型")
        assertEquals(-2_500L, env.amountHint)
    }

    @Test
    fun `bank sms refund envelope carries refund type and parsed amount`() {
        val parsed = assertNotNull(
            parser.parse("sms:inbox", "95555", "您尾号1234账户退款入账人民币1,234.50元"),
        )
        val env = toRawEnvelope(
            sourceId = "sms",
            sourceRef = "sms:42",
            occurredAtMillis = 3_000L,
            rawText = "95555\n您尾号1234账户退款入账人民币1,234.50元",
            packageName = "95555",
            parsed = parsed,
        )
        assertEquals(TxnType.REFUND, env.explicitType)
        assertEquals(123_450L, env.amountHint, "千分位应被正确解析并透传")
    }

    @Test
    fun `metadata is passed through verbatim`() {
        val parsed = NotificationParser.ParseResult(
            ruleId = "r",
            ruleLabel = "l",
            amountMinor = -1_234L,
            counterparty = "某商户",
            direction = Direction.OUT,
            explicitType = null,
        )
        val env = toRawEnvelope(
            sourceId = "src",
            sourceRef = "ref",
            occurredAtMillis = 42L,
            rawText = "raw",
            packageName = "pkg",
            parsed = parsed,
        )
        assertEquals("src", env.sourceId)
        assertEquals("ref", env.sourceRef)
        assertEquals(42L, env.occurredAtMillis)
        assertEquals("raw", env.rawText)
        assertEquals("pkg", env.packageName)
        assertEquals("某商户", env.counterpartyHint)
        assertEquals(-1_234L, env.amountHint)
        assertNull(env.explicitType)
    }

    // ---------------- 方向提示透传（本次修复：金额缺失时的方向不再丢失） ----------------

    /** B1：方向为流入、金额缺失 ⇒ `directionHint == IN` 且 `amountHint == null`（方向成为唯一信号）。 */
    @Test
    fun `direction hint survives even when the amount is missing`() {
        val parsed = NotificationParser.ParseResult(
            ruleId = "sms_bank_in",
            ruleLabel = "银行短信（收入）",
            amountMinor = null,
            counterparty = "工商银行",
            direction = Direction.IN,
            explicitType = null,
        )
        val env = toRawEnvelope(
            sourceId = "sms",
            sourceRef = "sms:1",
            occurredAtMillis = 7_000L,
            rawText = "【工商银行】您尾号1234账户工资已转入。",
            packageName = "95588",
            parsed = parsed,
        )
        assertEquals(Direction.IN, env.directionHint, "金额缺失时方向必须被透传（否则下游只能兜底成支出）")
        assertNull(env.amountHint)
    }

    /** B2：金额与方向**并存、互不覆盖**。 */
    @Test
    fun `direction hint and amount coexist without overwriting each other`() {
        val parsed = NotificationParser.ParseResult(
            ruleId = "sms_bank_in",
            ruleLabel = "银行短信（收入）",
            amountMinor = 1_230L,
            counterparty = "工商银行",
            direction = Direction.IN,
            explicitType = null,
        )
        val env = toRawEnvelope(
            sourceId = "sms",
            sourceRef = "sms:2",
            occurredAtMillis = 8_000L,
            rawText = "【工商银行】您尾号1234账户收入人民币12.30元。",
            packageName = "95588",
            parsed = parsed,
        )
        assertEquals(Direction.IN, env.directionHint)
        assertEquals(1_230L, env.amountHint)
    }
}
