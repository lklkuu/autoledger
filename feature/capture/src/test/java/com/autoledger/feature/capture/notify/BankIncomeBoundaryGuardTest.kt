package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA 独立 P2 边界护栏（针对 commit d854d8c）。
 *
 * 刻意**不复用**工程师用例里的文本 —— 换一批银行、换一批措辞，
 * 用来判断修复是「真的通用」还是「只对工行那两条文本过拟合」。
 *
 * 覆盖：
 *  - 「关键词(注记)金额」且没有「元」的形态必须取到金额（否则 IngestPipeline 兜底成 EXPENSE）
 *  - 尾号 / 卡号 / 日期数字绝不能被当成金额
 *  - 真实支出（含「收款方：XX」）绝不能被判成收入
 *  - 营销短信绝不能被记为收入
 *  - 跨渠道两条文本必须归一化出**同一个**银行名（Bug 2 的前置条件）
 */
class BankIncomeBoundaryGuardTest {

    private val parser = NotificationParser()

    private fun describe(r: NotificationParser.ParseResult?): String =
        if (r == null) "null（整条被丢弃）"
        else "rule=${r.ruleId} dir=${r.direction} amount=${r.amountMinor} cp=${r.counterparty}"

    // ------------------------------------------------------------------ 金额：无「元」形态

    @Test
    fun `P2-1 - ccb salary credit without the 元 unit still yields the amount`() {
        val r = parser.parse(
            "sms:inbox", "95533",
            "您尾号5678卡10月1日12:00中国建设银行入账(工资代发)12,345.67",
        )
        assertNotNull(r, "建行入账短信必须能解析\n  实际=${describe(r)}")
        assertEquals("sms_bank_in", r.ruleId, "实际=${describe(r)}")
        assertEquals(Direction.IN, r.direction, "实际=${describe(r)}")
        assertEquals(1_234_567L, r.amountMinor, "12,345.67 元 = +1234567 分，实际=${describe(r)}")
        assertEquals("中国建设银行", r.counterparty, "实际=${describe(r)}")
    }

    @Test
    fun `P2-2 - abc transfer credit without the 元 unit still yields the amount`() {
        val r = parser.parse(
            "sms:inbox", "95599",
            "您尾号4321的账户10月2日汇入（跨行转账）800",
        )
        assertNotNull(r, "实际=${describe(r)}")
        assertEquals(Direction.IN, r.direction, "实际=${describe(r)}")
        assertEquals(80_000L, r.amountMinor, "800 元 = +80000 分，实际=${describe(r)}")
    }

    // ------------------------------------------------------------------ 护栏：尾号 / 日期不是金额

    @Test
    fun `P2-3 - card tail digits are never taken as the amount`() {
        val r = parser.parse(
            "sms:inbox", "95533",
            "您尾号5678的卡于10月1日结息，尾号5678",
        )
        assertNotNull(r, "仍应命中收入规则，只是金额取不到\n  实际=${describe(r)}")
        assertEquals(Direction.IN, r.direction, "实际=${describe(r)}")
        assertNull(r.amountMinor, "尾号绝不能被当成金额，实际=${describe(r)}")
    }

    @Test
    fun `P2-4 - date digits are never taken as the amount`() {
        val r = parser.parse(
            "sms:inbox", "95533",
            "您尾号5678的卡工资到账10月1日",
        )
        assertNotNull(r, "实际=${describe(r)}")
        assertNull(r.amountMinor, "「10月」不得被当成 10 元，实际=${describe(r)}")
    }

    @Test
    fun `P2-5 - balance digits do not shadow the real amount`() {
        // 「余额」在后面，金额正则必须取**第一处**而不是被余额覆盖
        val r = parser.parse(
            "sms:inbox", "95533",
            "您尾号5678卡10月1日中国建设银行代发工资8,000元，余额20,000.50元。",
        )
        assertNotNull(r, "实际=${describe(r)}")
        assertEquals(800_000L, r.amountMinor, "必须取 8,000 而不是余额 20,000.50，实际=${describe(r)}")
    }

    // ------------------------------------------------------------------ 反向护栏：真实支出不得变收入

    @Test
    fun `P2-6 - expense with 收款方 stays an outflow`() {
        val r = parser.parse(
            "sms:inbox", "95533",
            "您尾号1234卡10月1日消费1,299元，收款方：京东商城",
        )
        assertNotNull(r, "真实消费不得被丢弃\n  实际=${describe(r)}")
        assertEquals("bank_generic_out", r.ruleId, "实际=${describe(r)}")
        assertEquals(Direction.OUT, r.direction, "实际=${describe(r)}")
        assertEquals(-129_900L, r.amountMinor, "实际=${describe(r)}")
    }

    @Test
    fun `P2-7 - marketing sms is never booked as income`() {
        val r = parser.parse(
            "sms:inbox", "95555",
            "【招商银行】尊敬的客户，本月消费满额有机会抽取1,000元红包，回复TD退订",
        )
        assertNull(r, "营销短信不得记为收入，实际=${describe(r)}")
    }

    // ------------------------------------------------------------------ Bug 2 前置：跨渠道归一化到同一实体

    @Test
    fun `P2-8 - ccb sms and ccb app notification normalize to the same bank name`() {
        val sms = parser.parse(
            "sms:inbox", "95533",
            "【中国建设银行】您尾号5678卡10月1日工资代发12,345.67元，余额20,000元。",
        )
        val app = parser.parse(
            "com.ccb", "动账通知",
            "【中国建设银行】您尾号5678卡10月1日工资代发12,345.67元。请点击查看详情。",
        )
        assertNotNull(sms, "实际=${describe(sms)}")
        assertNotNull(app, "实际=${describe(app)}")
        assertEquals("中国建设银行", sms.counterparty, "实际=${describe(sms)}")
        assertEquals(sms.counterparty, app.counterparty, "跨渠道必须归一化到同一个实体，否则指纹不等、无法合并")
        assertEquals(sms.amountMinor, app.amountMinor, "金额必须一致，实际 sms=${describe(sms)} app=${describe(app)}")
        assertTrue(sms.amountMinor!! > 0, "收入必须为正")
    }
}
