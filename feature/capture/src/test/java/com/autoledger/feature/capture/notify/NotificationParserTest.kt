package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通知解析规则 —— 纯 JVM 单测。
 *
 * 重点覆盖「退款自动记录」：退款通知必须能被解析出来（此前规则里根本没有退款规则，
 * 而且付款规则还主动排除退款文案，导致退款通知压根进不了账本）。
 */
class NotificationParserTest {

    private val parser = NotificationParser()

    // ------------------------------------------------------------ 退款（自动记录）

    @Test
    fun `wechat refund notification is parsed as an inflow`() {
        val r = parser.parse(
            DefaultNotificationRules.PKG_WECHAT,
            "微信支付",
            "退款到账通知\n已退款 ¥12.30\n商户名称：星巴克",
        )
        assertNotNull(r, "退款通知必须能解析")
        assertEquals("refund_wechat", r.ruleId)
        assertEquals(1_230L, r.amountMinor, "退款金额应为正数（资金流入）")
        assertEquals(Direction.IN, r.direction)
        assertEquals("星巴克", r.counterparty)
        // 显式下发 REFUND：退款金额是正数，若只靠金额正负会被误判成 INCOME。
        assertEquals(TxnType.REFUND, r.explicitType)
    }

    @Test
    fun `alipay refund notification is parsed`() {
        val r = parser.parse(
            DefaultNotificationRules.PKG_ALIPAY,
            "支付宝",
            "退款成功：￥58.00 已原路退回",
        )
        assertNotNull(r)
        assertEquals("refund_alipay", r.ruleId)
        assertEquals(5_800L, r.amountMinor)
        assertEquals(Direction.IN, r.direction)
        assertEquals(TxnType.REFUND, r.explicitType)
    }

    @Test
    fun `bank sms refund is parsed by the generic refund rule`() {
        val r = parser.parse("sms:inbox", "95555", "您尾号1234账户退款入账人民币1,234.50元")
        assertNotNull(r, "银行退款短信也必须能解析")
        assertEquals("refund_generic", r.ruleId)
        assertEquals(123_450L, r.amountMinor, "千分位应被正确解析")
        assertEquals(Direction.IN, r.direction)
        assertEquals(TxnType.REFUND, r.explicitType)
    }

    @Test
    fun `refund in progress is not recorded as received`() {
        // 申请中/失败/处理中的文案不能记成"退款到账"
        assertNull(parser.parse(DefaultNotificationRules.PKG_WECHAT, "微信支付", "退款申请已提交，商家正在退款中"))
        assertNull(parser.parse(DefaultNotificationRules.PKG_WECHAT, "微信支付", "退款失败，请联系商家"))
    }

    // ------------------------------------------------------------ 付款（回归：不能被退款规则抢走）

    @Test
    fun `normal wechat payment still matches the pay rule`() {
        val r = parser.parse(
            DefaultNotificationRules.PKG_WECHAT,
            "微信支付",
            "微信支付凭证\n付款金额 ¥25.00\n商户名称：瑞幸咖啡",
        )
        assertNotNull(r)
        assertEquals("wechat_pay", r.ruleId)
        assertEquals(-2_500L, r.amountMinor, "付款应为负数")
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `normal alipay payment still matches the pay rule`() {
        val r = parser.parse(DefaultNotificationRules.PKG_ALIPAY, "支付宝", "成功付款 ￥9.90")
        assertNotNull(r)
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun `refund rules take precedence over the pay rule for refund text`() {
        // 同时含"支付成功"与"退款"的文案：必须判为退款，不能被付款规则吃掉
        val r = parser.parse(
            DefaultNotificationRules.PKG_WECHAT,
            "微信支付",
            "支付成功\n已退款到账 ¥30.00",
        )
        assertNotNull(r)
        assertTrue(r.ruleId.startsWith("refund_"), "退款优先，实际=${r.ruleId}")
        assertEquals(3_000L, r.amountMinor)
    }

    @Test
    fun `non-refund rules never mark an explicit refund type`() {
        // 只有退款规则才下发 explicitType=REFUND；付款/收款必须留 null，否则会被误记成退款。
        val wechatPay = parser.parse(
            DefaultNotificationRules.PKG_WECHAT,
            "微信支付",
            "微信支付凭证\n付款金额 ¥25.00",
        )
        assertNotNull(wechatPay)
        assertNull(wechatPay.explicitType, "微信付款不得下发退款类型")

        val alipayPay = parser.parse(DefaultNotificationRules.PKG_ALIPAY, "支付宝", "成功付款 ￥9.90")
        assertNotNull(alipayPay)
        assertNull(alipayPay.explicitType, "支付宝付款不得下发退款类型")

        // 收款是 IN 方向，但类型应是 INCOME（无 explicitType），绝不能靠 direction 推成 REFUND。
        val wechatReceive = parser.parse(
            DefaultNotificationRules.PKG_WECHAT,
            "微信支付",
            "收款成功\n收款金额 ¥88.00",
        )
        assertNotNull(wechatReceive)
        assertEquals("wechat_receive", wechatReceive.ruleId)
        assertNull(wechatReceive.explicitType, "微信收款不得下发退款类型")
    }

    @Test
    fun `payment text mentioning 退回 is not treated as a refund`() {
        // T4 护栏：付款通知常带"如未收到可申请退回"，不能被退款规则抢走。
        val r = parser.parse(DefaultNotificationRules.PKG_WECHAT, "微信支付", "支付成功｜如未收到可申请退回")
        assertNotNull(r, "该文案应由付款规则命中，而非退款规则")
        assertFalse(r.ruleId.startsWith("refund_"), "不得判为退款到账，实际=${r.ruleId}")
        assertNull(r.explicitType, "付款文案不得下发退款类型")
    }

    @Test
    fun `unrelated notification is not parsed`() {
        assertNull(parser.parse(DefaultNotificationRules.PKG_WECHAT, "微信", "你有一条新消息"))
    }

    // ------------------------------------------------------------ 短信：金额不得取自发件号码 / 尾号

    @Test
    fun `salary sms amount comes from the body not the sender number`() {
        // P0：短信渠道的 title 其实是发件号码（95555）。若把 title 混入金额搜索范围，
        // 金额会被记成 95555.00（收入虚增约 19 倍）。
        val r = parser.parse("sms:inbox", "95555", "工资入账 5000元")
        assertNotNull(r)
        assertEquals("sms_bank_in", r.ruleId)
        assertEquals(500_000L, r.amountMinor, "必须取正文的 5000 元，而不是发件号码 95555")
        assertEquals(Direction.IN, r.direction)
    }

    @Test
    fun `salary sms with another sender and decimals is parsed correctly`() {
        val r = parser.parse("sms:inbox", "95588", "工资入账5000.00元")
        assertNotNull(r)
        assertEquals("sms_bank_in", r.ruleId)
        assertEquals(500_000L, r.amountMinor, "工行发件号码 95588 同样不得被当成金额")
        assertEquals(Direction.IN, r.direction)
    }

    @Test
    fun `sms card tail digits are not taken as the amount`() {
        // 尾号 / 卡号数字极常见：金额正则必须带明确上下文（元 / 币符 / 收入关键词）。
        val r = parser.parse("sms:inbox", "95555", "您尾号1234账户工资入账5000元")
        assertNotNull(r)
        assertEquals("sms_bank_in", r.ruleId)
        assertEquals(500_000L, r.amountMinor, "必须取 5000，而不是尾号 1234")
    }

    @Test
    fun `real expense sms still parses after the amount scope change`() {
        val r = parser.parse("sms:inbox", "95555", "您尾号6602卡9月27日23:52支出(消费财付通-2zero首饰屋)39.80元")
        assertNotNull(r, "真实消费短信不得被误伤")
        assertEquals("bank_generic_out", r.ruleId)
        assertEquals(-3_980L, r.amountMinor)
    }

    @Test
    fun `real refund sms still parses after the amount scope change`() {
        val r = parser.parse("sms:inbox", "95555", "退款到账 99元")
        assertNotNull(r)
        assertEquals("refund_generic", r.ruleId)
        assertEquals(9_900L, r.amountMinor)
        assertEquals(TxnType.REFUND, r.explicitType)
    }
}
