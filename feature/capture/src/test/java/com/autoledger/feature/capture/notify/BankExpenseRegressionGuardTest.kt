package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * ⚠️ **v1.1.14 修正的错误契约**：本用例原先叫
     * `D2 - plain credit card repayment reminder still works`，断言
     * 「信用卡还款提醒 = 支出 −5,000」。那是把 bug 写成了期望行为：
     * 「本期应还款5,000元」是**账单口径的应付金额**，不是任何一笔已发生的交易。
     * 记成 −5,000 会让净支出凭空虚增，用户当月真实消费被**再算一遍**。
     *
     * 修法：`BANK_BILL_NOTICE_REJECT` 按上下文（账单关键词 + 金额 + 无流出动词）
     * 剔掉账单汇总；`bodyMustContainAny` 里的「还款」保持不动 ——
     * 真实还款交易（见 D5）仍要靠它命中，再由 `TransferRules.REPAYMENT` 改判内部划转。
     */
    @Test
    fun `D2 - 信用卡账单提醒不入账（v1_1_14 修正的错误契约）`() {
        assertNull(
            parser.parse("sms:inbox", "95588", "您尾号1234信用卡本期应还款5,000元，到期还款日10月25日，请及时还款。"),
            "账单提醒不是交易，不得入账（原先被记成 -500000 分）",
        )
    }

    /**
     * v1.1.14 用户报障的**逐字**样本（工行 95588，尾号 6602）。
     *
     * 原行为：`bank_generic_out` 命中裸词「还款」（应**还款**额 / 到期**还款**日），
     * 首条金额正则取走左起第一处「1,451.50元」⇒ 记成 −145150 分。
     *
     * 两个包名都断言：`sms:inbox`（短信通道）与 `com.icbc`（App 通知）走的是**同一批**
     * 通用规则 —— `NotificationParser` 对非微信 / 支付宝包名只认 `packageNames == null`。
     * 这也是**不能**给 `com.icbc` 加专属规则的原因：一旦加了，该包名下的通用规则会
     * 集体退出候选集，工行真实消费 / 收入通知被整片丢弃。
     */
    @Test
    fun `D3 - ICBC 信用卡账单提醒（应还款额）不入账`() {
        val body = "短信账单：您尾号6602的信用卡本期账单（9月24日—10月23日）：人民币账户应还款额1,451.50元，" +
            "到期还款日为11月11日。其他币种账户余额均为0。登录工银e生活App查询账单明细。【工商银行】"

        assertNull(parser.parse("sms:inbox", "95588", body), "短信通道：账单提醒不得入账（原 −145150 分）")
        assertNull(parser.parse("com.icbc", "动账通知", body), "com.icbc 共用通用规则，同样不得入账")
    }

    /**
     * 反向样本 ①：真实消费 + 还款日尾缀，**必须仍是支出**。
     *
     * 这条是 `BANK_BILL_NOTICE_REJECT` 的负向边界。若把「到期还款日」做成裸拒绝词，
     * 这条真支出会被整条丢掉（漏记）；若拒绝正则漏了 `\A` 锚定，`containsMatchIn` 会从
     * 「消费」之后重新起匹配，负向断言 `(?!.*消费)` 失效，这条同样会被误杀（见 D6）。
     */
    @Test
    fun `D4 - 消费短信带「到期还款日」尾缀仍是支出（账单拒绝不得过宽）`() {
        assertExpense(
            "D4 还款日尾缀",
            "您尾号1234卡消费500元，到期还款日为10月25日。",
            -50_000L,
        )
    }

    /**
     * 反向样本 ②：**真实还款交易**必须仍被解析（不能因账单拒绝而误杀）。
     *
     * 它不含任何账单汇总关键词（应还款额 / 到期还款日 / 最低还款额 / 本期账单应还），
     * 所以拒绝不触发；「还款」这个触发词仍在 `bodyMustContainAny` 里。
     * 方向按 `bank_generic_out` 是 OUT；随后由 `TransferRules.REPAYMENT` 改判内部划转，
     * 那是流水线另一层的事，不在本文件的断言范围。
     */
    @Test
    fun `D5 - 真实信用卡还款交易仍解析（账单拒绝不得吃掉还款）`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号1234信用卡还款1,000元，感谢使用。")
        assertNotNull(r, "真实还款交易不得因新增的账单拒绝被丢弃\n  实际=${describe(r)}")
        assertEquals("bank_generic_out", r.ruleId, "实际=${describe(r)}")
        assertEquals(Direction.OUT, r.direction, "实际=${describe(r)}")
        assertEquals(-100_000L, r.amountMinor, "实际=${describe(r)}")
    }

    /**
     * 证伪：`BANK_BILL_NOTICE_REJECT` 的 `\A` 锚定**不是装饰**，去掉就会误杀 D4。
     *
     * `NotificationParser` 用 `Regex.containsMatchIn`；无锚定时引擎会在第 0 位失败后
     * 逐个起始位置重试，而负向断言只看**该位置之后**的文本 —— 前文的「消费」不再可见
     * ⇒ 真实消费被判成账单短信。这条用例把「必须有 `\A`」变成可执行的约束，
     * 防止后来者「顺手简化正则」时静默引入漏记。
     */
    @Test
    fun `D6 - 账单拒绝正则必须锚定串首（去掉锚定会误杀真实消费）`() {
        // 与生产常量唯一的差别：开头没有 (?s)\A 里的 \A
        val unanchored = """(?s)(?=.*(?:应还款额|应还金额|到期还款日|最低还款额|本期账单应还))(?=.*\d[\d.,]*\s?元)(?!.*(?:消费|支出|扣款|取款|支付成功|交易成功|已消费))"""
        val body = "您尾号1234卡消费500元，到期还款日为10月25日。"
        assertTrue(
            Regex(unanchored).containsMatchIn(body),
            "前提不成立：去掉串首锚定后本应误命中「消费500元，到期还款日…」，实际没命中 —— " +
                "说明正则已改，请重新审视本用例",
        )
        // 生产规则必须不命中它
        assertExpense("D6 锚定必要性", body, -50_000L)
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
