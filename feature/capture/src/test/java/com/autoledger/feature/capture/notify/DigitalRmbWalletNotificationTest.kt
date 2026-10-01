package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 回归：数字人民币 / 钱包支付通知里的 **`¥`** 金额必须能被解析（用户实报的 4 条通知中的 [3][4]）。
 *
 * ## 真正的根因（已由 team-lead 核实纠正）
 * 不是「金额正则不认识 `¥`」——`bank_generic_out.amountPatterns` 第 2 条
 * `[¥￥]\s?(\d+...)` **本来就认 `¥`**。真正的缺口是这两条文本**没有命中任何规则**
 * （`ruleId = —`）：它们含「支付」却**不含**「支付成功」，而 `bank_generic_out.bodyMustContainAny`
 * 里只有「支付成功」这一种「支付」措辞 ⇒ 整条被 `NotificationParser` 判为「不是一笔账」而丢弃。
 *
 * ## 修法
 * 在 `bank_generic_out.bodyMustContainAny` 里补**组合词**（「支付给 / 钱包支付 / 数字人民币支付 / 数字钱包」），
 * **刻意不加裸「支付」** —— 后者会把「还款提醒 / 账单到期 / 营销」这类短信一并吸进来
 * （见 [BankCardNotificationTest.bank marketing sms mentioning 消费 is not parsed as spending] 的同源教训）。
 *
 * 本用例即钉住：真实原文 [3][4] 现在**既能命中规则、又能解析出金额**；且裸「支付」仍**不会**被误吸。
 */
class DigitalRmbWalletNotificationTest {

    private val parser = NotificationParser()

    @Test
    fun `icbc wallet 数字钱包-支付给 notification is parsed with the yuan amount`() {
        // 真实原文 [3]（工行数字钱包动账通知）：含「数字钱包」「钱包支付」「支付给」三个组合词。
        val r = parser.parse(
            "com.icbc.wallet", "动账通知",
            "您尾号为4793的数字钱包支付给中电联京东共管钱包（0098）¥17.45",
        )
        assertNotNull(r, "「数字钱包支付给…¥17.45」必须命中支出规则，不能落「未解析出金额」")
        assertEquals("bank_generic_out", r.ruleId, "应命中银行支出通用规则")
        assertEquals(-1_745L, r.amountMinor, "金额必须解析出 17.45 元（走 `[¥￥]` 那条正则）")
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `dcep 数字人民币钱包 pay notification is parsed with the yuan amount`() {
        // 真实原文 [4]（数币 App 付款通知）：命中组合词「数字人民币钱包」。
        // ⚠️「数字人民币钱包」不含子串「数字钱包」，故这条只认「数字人民币钱包」——
        // 若只加「数字钱包」，本条会继续落「未解析出金额」（实测踩过）。
        val r = parser.parse(
            "cn.gov.pboc.dcep", "付款通知",
            "您的我的钱包数字人民币钱包在京东平台支付¥17.45",
        )
        assertNotNull(r, "「…数字人民币钱包在京东平台支付¥17.45」必须能命中规则")
        assertEquals("bank_generic_out", r.ruleId)
        assertEquals(-1_745L, r.amountMinor, "金额必须解析出 17.45 元")
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `a bare 支付 verb is still NOT enough to be recorded as spending`() {
        // 反向护栏：本轮扩展触发词时**没有**加裸「支付」。
        // 「支付密码」既不含任何组合词、也不含「消费/支出/扣款/交易/还款」，故整条不得被认成一笔账。
        assertNull(
            parser.parse("com.icbc", "动账通知", "您的支付密码已于10月1日重置，请妥善保管。"),
            "仅含裸「支付」的运营类短信不得被记为消费（否则会虚增一笔支出）",
        )
    }
}
