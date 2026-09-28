package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 用户反馈问题 1 的**独立反例验证**（QA 新增，不复跑工程师用例）。
 *
 * 问题 1 的修复：给 `bank_generic_out` 追加了 23 个营销词到 `bodyRejectAny`
 * （活动/抽奖/立减金/红包/优惠/领取/参与方式/公众号/退订/达标/有机会/礼品/积分/权益/福利/办理/尽享/尊享/敬请），
 * 并把金额首正则改成「数字紧跟元」以修复商户名里的数字被误当金额。
 *
 * 本测试用**真实短信样张**回答两个问题：
 *  - A1 修复是否误杀真实流水？（构造真实消费/收入/退款短信，断言金额与方向）
 *  - A2 出现「活动/办理」等词时会不会误伤？（构造正常交易短信，给明确结论）
 */
class NotificationParserMisfireTest {

    private val parser = NotificationParser()

    // ------------------------------------------------------------------ A1 真实样张：必须能正确入账

    @Test
    fun `counterexample 1 - credit card purchase SMS is recorded with the right amount and direction`() {
        // 银行信用卡消费短信：金额在"人民币"后、"元"前。
        val r = parser.parse("sms:inbox", "95555", "您尾号1234信用卡消费人民币200.00元")
        assertNotNull(r, "真实消费短信必须能解析，不能被新增的 reject 词误杀")
        assertEquals("bank_generic_out", r.ruleId)
        assertEquals(-20_000L, r.amountMinor, "200.00 元应为 -20000 分（流出）")
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `counterexample 2 - merchant name containing digits must not be taken as the amount`() {
        // 商户名里带数字（2zero首饰屋）：旧逻辑会把 "-2" 当成金额，得到 -2 分。
        // 修复后首正则是「数字紧跟元」，必须稳定取到 39.80。
        val body = "您尾号6602卡9月27日23:52支出(消费财付通-2zero首饰屋)39.80元"
        val r = parser.parse("sms:inbox", "95555", body)
        assertNotNull(r)
        assertEquals("bank_generic_out", r.ruleId)
        assertEquals(-3_980L, r.amountMinor, "必须取 39.80（-3980 分），而不是商户名里的 2")
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `counterexample 3 - salary inflow is booked as positive income`() {
        val r = parser.parse("sms:inbox", "95555", "工资入账 5000元")
        assertNotNull(r, "工资短信是真实收入，不能被拒绝")
        assertEquals("sms_bank_in", r.ruleId)
        assertEquals(500_000L, r.amountMinor, "5000 元应为 +500000 分（流入）")
        assertEquals(Direction.IN, r.direction)
        // 收入走 sms_bank_in，不下发 explicitType（否则会被误判成退款）；类型交给流水线按正负推断为 INCOME。
        assertNull(r.explicitType)
    }

    @Test
    fun `counterexample 4 - refund arrival is booked as REFUND not INCOME`() {
        val r = parser.parse("sms:inbox", "95555", "退款到账 99元")
        assertNotNull(r, "退款到账必须能解析")
        assertEquals("refund_generic", r.ruleId)
        assertEquals(9_900L, r.amountMinor)
        assertEquals(Direction.IN, r.direction)
        assertEquals(TxnType.REFUND, r.explicitType, "退款金额为正，必须显式下发 REFUND 类型")
    }

    // ------------------------------------------------------------------ A1 修复初衷：营销短信不得虚增支出

    @Test
    fun `marketing SMS that merely mentions 消费 is rejected (the point of the fix)`() {
        // 修复前：含"消费"即命中 bank_generic_out，会把 500 记成一笔真实支出（假阳性）。
        val r = parser.parse("sms:inbox", "95533", "您本月消费累计金额达标，有机会抽取500元立减金")
        assertNull(r, "营销短信必须被整条拒绝，不得虚增一笔支出")
    }

    // ------------------------------------------------------------------ A2 反例：含营销词的真实交易会被漏记

    @Test
    fun `counterexample A2a - real purchase SMS carrying an activity suffix is dropped`() {
        // 真实银行短信常附带营销/服务尾缀（"参加活动请回复Y"）。
        // 这条短信本身是**一笔真实消费**（200.00 元），但正文含"活动"→ 命中 reject → 整条拒绝 → 漏记。
        val r = parser.parse("sms:inbox", "95533", "您尾号1234储蓄卡9月27日消费支出人民币200.00元，参加活动请回复Y")
        assertNull(
            r,
            "结论：含'活动'的真实消费短信会被静默漏记（假阴性）——这是 Fix 1 的已知取舍，不是解析崩溃",
        )
    }

    @Test
    fun `counterexample A2b - real purchase SMS carrying an installment offer is dropped`() {
        // 另一真实场景：消费短信后附"办理分期"推荐，含"办理"→ 整条拒绝 → 漏记。
        val r = parser.parse("sms:inbox", "95533", "您尾号1234信用卡消费200.00元，如需办理分期请回复1")
        assertNull(r, "结论：含'办理'的真实消费短信同样会被漏记")
    }

    @Test
    fun `control - 申请 is NOT in the reject list so an unrelated service suffix does not kill the record`() {
        // 对照组：'申请'未列入 reject，说明拒绝面并非无限扩大；同样带服务尾缀的短信可正常入账。
        val r = parser.parse("sms:inbox", "95533", "您尾号1234储蓄卡9月27日消费支出人民币200.00元，如有疑问请申请客服")
        assertNotNull(r)
        assertEquals("bank_generic_out", r.ruleId)
        assertEquals(-20_000L, r.amountMinor)
    }
}
