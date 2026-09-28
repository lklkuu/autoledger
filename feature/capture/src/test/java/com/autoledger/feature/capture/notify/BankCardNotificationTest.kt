package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 问题 4 回归：信用卡消费通知（抖音刷信用卡等）必须能被自动抓取。
 *
 * 根因：`bank_generic_out` 的 `bodyMustContainAny` 此前只含「消费/支出/扣款/支付成功」，
 * 而银行信用卡 App 常用「交易成功 / 交易提醒 / 交易支出」，导致通知被静默丢弃。
 */
class BankCardNotificationTest {

    private val parser = NotificationParser()

    @Test
    fun `cmb credit card 消费 notification is parsed`() {
        val r = parser.parse("cmb.pb", "招商银行", "您尾号1234信用卡于09月27日18:32消费人民币200.00元，商户：抖音")
        assertNotNull(r)
        assertEquals(-20_000L, r.amountMinor)
        assertEquals(Direction.OUT, r.direction)
        assertEquals("抖音", r.counterparty)
    }

    @Test
    fun `bank credit card 交易成功 notification is parsed`() {
        val r = parser.parse("com.icbc", "工商银行", "您尾号6666的信用卡于18:32交易成功，交易金额200.00元")
        assertNotNull(r, "「交易成功」措辞的信用卡消费必须能解析")
        assertEquals(-20_000L, r.amountMinor)
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `bank credit card 交易 notification is parsed`() {
        val r = parser.parse("com.bankcomm", "交通银行", "您尾号9999信用卡交易人民币200.00元")
        assertNotNull(r, "「交易」措辞的信用卡消费必须能解析")
        assertEquals(-20_000L, r.amountMinor)
    }

    @Test
    fun `bank credit card with merchant is parsed with counterparty`() {
        val r = parser.parse("com.pingan", "平安银行", "您尾号1111信用卡于18:32消费200.00元，商户：字节跳动")
        assertNotNull(r)
        assertEquals(-20_000L, r.amountMinor)
        assertEquals("字节跳动", r.counterparty)
    }

    @Test
    fun `credit card 消费抖音支付 notification is parsed`() {
        // 用户实报：银行端文案为「消费抖音支付XXX」——"消费"与金额之间隔着"抖音支付"，
        // 旧正则没有 [^\d]{0,6} 间隔，导致提取不到金额而漏记。
        val r = parser.parse("cmb.pb", "招商银行", "您尾号1234信用卡消费抖音支付200.00元")
        assertNotNull(r, "「消费抖音支付200元」必须能解析")
        assertEquals(-20_000L, r.amountMinor)
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `icbc 动账通知 merchant-with-digits is parsed with correct amount`() {
        // 用户实报（截图）：工行动账通知——商户名"2zero首饰屋"含数字，不能干扰金额提取
        val r = parser.parse(
            "com.icbc", "动账通知",
            "尾号6602卡9月27日23:52支出(消费财付通-2zero首饰屋)39.80元。请点击查看详情。",
        )
        assertNotNull(r, "工行动账通知必须能解析")
        assertEquals(-3_980L, r.amountMinor, "金额必须是 39.80 元（不能被商户名里的数字 2 干扰）")
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `credit card available limit reminder is not parsed as spending`() {
        assertNull(parser.parse("cmb.pb", "招商银行", "您尾号1234信用卡可用额度为3000.00元"))
    }

    @Test
    fun `bank marketing sms mentioning 消费 is not parsed as spending`() {
        // 用户实报（截图）：工行营销短信含"消费"二字却非真实消费，
        // 旧规则 bodyRejectAny 没有营销词，命中 bank_generic_out 并提取了"500元"。
        val body = "【工商银行】工享月月花，好礼月月拿！即日起至9月30日，绑定工行信用卡通过微信快捷支付，" +
            "消费累计金额达标，最高有机会抽取500元微信立减金。参与方式：关注\"工行福建\"微信公众号…回复TDYX退订。"
        assertNull(
            parser.parse("sms:inbox", "95588", body),
            "营销短信不得被记为消费",
        )
    }

    // ------------------------------------------------------------ 商户名抽取（含银行短信兜底）

    @Test
    fun `bank sms merchant right after the verb is extracted without a prefix`() {
        // 用户实报：工行短信「…支出(消费财付通-2zero首饰屋)39.80元。」
        // 没有「商户」前缀，商户紧跟在交易动词之后 —— 兜底正则应抽出它，并剔除通道词「财付通」。
        val r = parser.parse("sms:inbox", "95588", "您尾号6602卡9月27日23:52支出(消费财付通-2zero首饰屋)39.80元")
        assertNotNull(r)
        assertEquals(-3_980L, r.amountMinor)
        assertEquals("2zero首饰屋", r.counterparty, "应剔除支付通道词，只保留商户")
    }

    @Test
    fun `explicit merchant prefix still wins over the fallback`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号1234卡消费 398.00元，商户：星巴克")
        assertNotNull(r)
        assertEquals("星巴克", r.counterparty)
    }

    @Test
    fun `plain merchant after the verb is extracted`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号1234卡消费 星巴克 398.00元")
        assertNotNull(r)
        assertEquals("星巴克", r.counterparty)
    }

    @Test
    fun `amount-only statement does not produce a bogus merchant`() {
        // 噪声护栏：只有金额、没有商户的账单明细，不得把「1,280.00元」当成商户名。
        val r = parser.parse("sms:inbox", "95555", "您尾号1234卡消费 1,280.00元，余额 8,000元")
        assertNotNull(r)
        assertEquals(-128_000L, r.amountMinor)
        assertNull(r.counterparty, "抽不到商户就返回空，绝不塞噪声")
    }
}
