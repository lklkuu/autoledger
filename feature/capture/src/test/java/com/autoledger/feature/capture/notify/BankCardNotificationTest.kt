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
}
