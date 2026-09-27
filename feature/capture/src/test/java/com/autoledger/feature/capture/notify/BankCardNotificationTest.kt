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
    fun `credit card available limit reminder is not parsed as spending`() {
        assertNull(parser.parse("cmb.pb", "招商银行", "您尾号1234信用卡可用额度为3000.00元"))
    }
}
