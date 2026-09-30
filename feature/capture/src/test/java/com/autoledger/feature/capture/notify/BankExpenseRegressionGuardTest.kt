package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA 独立回归护栏（针对 commit d854d8c 的 P0 复查）。
 *
 * 背景：`bank_generic_out.bodyRejectAny` 现在 = 17 个入账词 + 19 个营销词 + 「退款」，
 * 而 `sms_bank_in.bodyMustContainAny` = **完全相同的** 17 个入账词。
 * 这两个集合相同意味着：**任何被支出规则拒绝的文本，按定义都会被收入规则认领**。
 * 于是「支出正文里夹了一个入账词」的后果不是「静默丢弃」，而是更糟的**方向反转**
 * —— 一笔 500 元的消费被记成 +500 元的收入。
 *
 * 本文件断言的是业务事实：**真实消费必须仍然是 OUT，既不能被丢掉，更不能变成 IN**。
 *
 * 分两类（报告时请分开看）：
 *  - `本次新增`  = 只有 d854d8c 新加进 reject 的 14 个词才会触发，旧代码不受影响；
 *  - `既有缺陷`  = 旧代码同样会触发（营销词 reject / 「存入」等老词），非本次引入。
 */
class BankExpenseRegressionGuardTest {

    private val parser = NotificationParser()

    private fun describe(r: NotificationParser.ParseResult?): String =
        if (r == null) "null（整条被丢弃）"
        else "rule=${r.ruleId} dir=${r.direction} amount=${r.amountMinor} cp=${r.counterparty}"

    /** 断言「真实消费 = OUT 支出」，并给出足够定位信息的失败消息。 */
    private fun assertExpense(tag: String, body: String, expectedMinor: Long, pkg: String = "sms:inbox") {
        val title = if (pkg == "sms:inbox") "95588" else "动账通知"
        val r = parser.parse(pkg, title, body)
        assertNotNull(r, "$tag：真实消费不得被静默丢弃\n  正文=$body\n  实际=${describe(r)}")
        assertEquals(
            "bank_generic_out", r.ruleId,
            "$tag：真实消费必须命中支出规则\n  正文=$body\n  实际=${describe(r)}",
        )
        assertEquals(
            Direction.OUT, r.direction,
            "$tag：真实消费不得被判成收入（方向反转比丢弃更严重）\n  正文=$body\n  实际=${describe(r)}",
        )
        assertEquals(
            expectedMinor, r.amountMinor,
            "$tag：支出金额必须为负\n  正文=$body\n  实际=${describe(r)}",
        )
    }

    /**
     * 断言「**不自动记账**」—— 营销尾缀的消费短信按产品决策不落库。
     *
     * 产品取舍（lead 定）：用户此前明确报告过「银行营销短信被误记为消费」，
     * 偏好是**宁可漏记，也不虚增支出** —— 虚增会污染净支出 / 结余 / 时间成本这些核心指标，
     * 而漏记只影响完整性。所以这类文本允许返回 null（不入库）。
     *
     * ⚠️ 但**绝不允许方向反转成收入**：信用卡「消费5,000元，将于25日入账」若变成
     * +5,000 收入就是大面积错账，比漏记严重得多（见 A 组护栏）。
     */
    private fun assertNotAutoBooked(tag: String, body: String, pkg: String = "sms:inbox") {
        val title = if (pkg == "sms:inbox") "95588" else "动账通知"
        val r = parser.parse(pkg, title, body)
        if (r != null) {
            assertEquals(
                Direction.OUT, r.direction,
                "$tag：可以漏记，但绝不能方向反转成收入\n  正文=$body\n  实际=${describe(r)}",
            )
        }
    }

    // ==================================================================
    // A. 本次新增的 14 个入账词 → 真实消费被误伤（本次提交引入的回归）
    //    新增词：存入?/ 报销 / 入账 / 到账 / 汇入 / 结息 / 代发 / 补贴 /
    //            奖金 / 返现 / 退还 / 收款成功 / 收款到账 / 已收款
    //    （「工资 / 转入 / 收入 / 存入」旧代码已有，见 C 区）
    // ==================================================================

    @Test
    fun `A1 - 消费短信尾部带「入账」仍是支出`() {
        assertExpense(
            "A1 入账",
            "您尾号1234的卡9月30日07:16消费500元，该笔交易将于次日入账。",
            -50_000L,
        )
    }

    @Test
    fun `A2 - 消费短信带「已收款」仍是支出`() {
        assertExpense(
            "A2 已收款",
            "您尾号1234的卡9月30日消费500元，商户已收款。",
            -50_000L,
        )
    }

    @Test
    fun `A3 - 消费短信带「退还」仍是支出`() {
        assertExpense(
            "A3 退还",
            "您尾号1234的卡9月30日消费500元，手续费将于次月退还。",
            -50_000L,
        )
    }

    @Test
    fun `A4 - 消费短信带「补贴」仍是支出`() {
        assertExpense(
            "A4 补贴",
            "您尾号1234的卡9月30日消费500元，其中政府补贴100元，实付400元。",
            -50_000L,
        )
    }

    @Test
    fun `A5 - 消费短信带「代发」账户标识仍是支出`() {
        assertExpense(
            "A5 代发",
            "您尾号1234的卡（代发账户）9月30日消费500元。",
            -50_000L,
        )
    }

    @Test
    fun `A6 - 消费短信带「到账」仍是支出`() {
        assertExpense(
            "A6 到账",
            "您尾号1234的卡9月30日消费500元，资金将于T+1日到账商户。",
            -50_000L,
        )
    }

    @Test
    fun `A7 - 信用卡账单短信带「入账」仍是支出`() {
        assertExpense(
            "A7 入账（信用卡）",
            "您尾号1234信用卡本期消费5,000元，将于10月25日入账。",
            -500_000L,
        )
    }

    @Test
    fun `A8 - 消费短信带「结息」仍是支出`() {
        assertExpense(
            "A8 结息",
            "您尾号1234的卡9月30日消费500元，本期结息日为10月20日。",
            -50_000L,
        )
    }

    // ==================================================================
    // B. 团队指定的三条 P0 样本（营销词路径 —— 既有缺陷，但同样必须修）
    //    「积分 / 达标 / 有机会 / 活动 / 公众号」在 d854d8c **之前** 就在
    //    bank_generic_out.bodyRejectAny 里，所以这三条旧代码也会丢弃；
    //    但它们同时含新增收入词（到账 / 奖金 / 返现），属于两条缺陷叠加。
    // ==================================================================

    @Test
    fun `B1 - 消费 + 积分到账 不自动记账（宁漏不虚）`() {
        // 放开「积分」拒绝词会让「消费500元可兑换500积分」被记成 -500 元假支出，
        // 用假阳性换假阴性不划算 —— 维持不自动记账。
        assertNotAutoBooked("B1 积分/到账", "您尾号1234的卡9月30日07:16消费500元，积分到账100分。")
    }

    @Test
    fun `B2 - 消费 + 达标有机会获得奖金 不自动记账（宁漏不虚）`() {
        assertNotAutoBooked("B2 达标/有机会/奖金", "您尾号1234的卡消费2,000元，本月累计消费达标，有机会获得奖金。")
    }

    @Test
    fun `B3 - 消费 + 返现活动公众号 不自动记账（宁漏不虚）`() {
        assertNotAutoBooked("B3 返现/活动/公众号", "您尾号1234的卡消费88元，返现活动详见公众号。")
    }

    // ==================================================================
    // C. 既有缺陷对照：d854d8c 之前就在 reject 里的词（工资 / 转入 / 收入 / 存入）
    //    用来证明「reject 集合 = must-contain 集合 ⇒ 方向反转」是**结构性缺陷**，
    //    不是这次才出现的，但这次把暴露面从 4 个词扩大到了 17 个。
    // ==================================================================

    @Test
    fun `C1 - 消费 + 请于还款日前存入 仍是支出（旧代码已有缺陷）`() {
        assertExpense(
            "C1 存入（旧词）",
            "您尾号1234的卡9月30日消费500元，请于还款日前存入足额款项。",
            -50_000L,
        )
    }

    // ==================================================================
    // D. 控制组：这些本就应该通过，用来证明上面不是「测试写错」
    // ==================================================================

    @Test
    fun `D1 - plain expense sms without any income keyword still works`() {
        assertExpense(
            "D1 控制组",
            "您尾号1234的卡9月30日07:16消费500元，余额1,234.56元。",
            -50_000L,
        )
    }

    @Test
    fun `D2 - plain credit card repayment reminder still works`() {
        assertExpense(
            "D2 控制组（信用卡还款）",
            "您尾号1234信用卡本期应还款5,000元，到期还款日10月25日，请及时还款。",
            -500_000L,
        )
    }

    // ==================================================================
    // E. 硬不变量：任何一条真实消费样本都不得变成 IN（方向反转）
    //    这一条分开写，即使上面逐条断言太严，方向反转本身也绝不能接受。
    // ==================================================================

    @Test
    fun `E1 - invariant - no real expense sample is ever booked as income`() {
        val samples = listOf(
            "您尾号1234的卡9月30日07:16消费500元，该笔交易将于次日入账。",
            "您尾号1234的卡9月30日消费500元，商户已收款。",
            "您尾号1234的卡9月30日消费500元，手续费将于次月退还。",
            "您尾号1234的卡9月30日消费500元，其中政府补贴100元，实付400元。",
            "您尾号1234的卡（代发账户）9月30日消费500元。",
            "您尾号1234的卡9月30日消费500元，资金将于T+1日到账商户。",
            "您尾号1234信用卡本期消费5,000元，将于10月25日入账。",
            "您尾号1234的卡9月30日消费500元，本期结息日为10月20日。",
            "您尾号1234的卡9月30日消费500元，请于还款日前存入足额款项。",
        )
        val inverted = samples.mapNotNull { body ->
            val r = parser.parse("sms:inbox", "95588", body)
            if (r != null && r.direction == Direction.IN) "「$body」 → ${describe(r)}" else null
        }
        assertTrue(
            inverted.isEmpty(),
            "以下真实消费被记成了收入（方向反转）：\n  ${inverted.joinToString("\n  ")}",
        )
    }
}
