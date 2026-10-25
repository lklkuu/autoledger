package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 独立复核（QA「3fix」）：**信用卡账单提醒不得入账**，而真实消费 / 真实还款必须仍被解析。
 *
 * ## 为什么另开一个文件
 * 工程师的 `BankExpenseRegressionGuardTest` D 组用的是**他自己挑的样本**与他自己写的断言；
 * 只跑它无法排除「样本恰好躲过了漏洞」这种可能。本文件刻意用**另外一批银行文案**
 * （招行 / 建行 / 交行 / 工行，措辞各不相同）做同样的正反两个方向，
 * 并额外钉三条工程师没覆盖的东西：
 *   ① 拒绝判据不能因为「有流水动词」以外的**任何**理由放行（即 B2 的反向样本不止一条）；
 *   ② 规则包里**不存在** `packageNames = setOf("com.icbc")` 这类专属规则
 *      （那会让该包名的通用规则集体退出候选集，是本次修法必须避开的坑）；
 *   ③ 真实还款交易（`还款` 触发词）不会被账单拒绝误杀 —— 否则「转移内部划转」能力倒退。
 *
 * ## 判据本身（生产实现）
 * `BANK_BILL_NOTICE_REJECT`：
 * ```
 * (?s)\A(?=.*(?:应还款额|应还金额|到期还款日|最低还款额|本期账单应还))
 *      (?=.*\d[\d.,]*\s?元)
 *      (?!.*(?:消费|支出|扣款|取款|支付成功|交易成功|已消费))
 * ```
 * 语义 =「**有账单口径关键词** + **确实带金额** + **没有任何流出动词**」⇒ 整条拒绝。
 */
class BankBillNoticeIndependentAuditTest {

    private val parser = NotificationParser()

    private fun describe(r: NotificationParser.ParseResult?): String =
        if (r == null) "null（整条被丢弃）"
        else "rule=${r.ruleId} dir=${r.direction} amount=${r.amountMinor} cp=${r.counterparty}"

    /** 短信通道（发件号码当 title）与 App 通道（任意 title）都断言为 null。 */
    private fun assertNotBooked(tag: String, body: String) {
        assertNull(
            parser.parse("sms:inbox", "95588", body),
            "$tag：账单提醒不是交易，短信通道不得入账\n  正文=$body\n  实际=${describe(parser.parse("sms:inbox", "95588", body))}",
        )
        assertNull(
            parser.parse("com.icbc", "动账通知", body),
            "$tag：账单提醒不是交易，App 通道不得入账\n  正文=$body\n  实际=${describe(parser.parse("com.icbc", "动账通知", body))}",
        )
    }

    private fun assertExpense(tag: String, body: String, expectedMinor: Long, pkg: String = "sms:inbox") {
        val title = if (pkg == "sms:inbox") "95588" else "动账通知"
        val r = parser.parse(pkg, title, body)
        assertNotNull(r, "$tag：真实交易不得被静默丢弃\n  正文=$body\n  实际=${describe(r)}")
        assertEquals("bank_generic_out", r.ruleId, "$tag：应命中银行支出规则\n  正文=$body\n  实际=${describe(r)}")
        assertEquals(Direction.OUT, r.direction, "$tag：真实流出的方向不得反转\n  正文=$body\n  实际=${describe(r)}")
        assertEquals(expectedMinor, r.amountMinor, "$tag：金额必须为负\n  正文=$body\n  实际=${describe(r)}")
    }

    // ==================================================================
    // B1. 正向：四家银行、四种措辞的账单提醒都必须**不入账**
    //     （关键词命中面覆盖 应还金额 / 最低还款额 / 应还款额 / 到期还款日）
    // ==================================================================

    @Test
    fun `B1a - 招行 本期应还金额 … 到期还款日 不入账`() {
        assertNotBooked(
            "B1a 招行",
            "【招商银行】您尾号1234的信用卡本期应还金额5,236.80元，到期还款日10月25日，" +
                "请于到期还款日前存入足额款项。",
        )
    }

    @Test
    fun `B1b - 建行 本期账单金额 … 最低还款额 … 到期还款日 不入账`() {
        assertNotBooked(
            "B1b 建行",
            "【建设银行】您尾号5678的龙卡信用卡本期账单金额3,280.00元，最低还款额328.00元，" +
                "到期还款日为11月5日，请按时还款。",
        )
    }

    @Test
    fun `B1c - 交行 应还款额 … 最后还款日 不入账`() {
        assertNotBooked(
            "B1c 交行",
            "【交通银行】您尾号9012信用卡人民币账户应还款额2,100.00元，最后还款日10月28日。" +
                "请登录买单吧App查询账单明细。",
        )
    }

    @Test
    fun `B1d - 用户原话的工行样本（逐字）不入账`() {
        assertNotBooked(
            "B1d 工行逐字",
            "短信账单：您尾号6602的信用卡本期账单（9月24日—10月23日）：人民币账户应还款额1,451.50元，" +
                "到期还款日为11月11日。其他币种账户余额均为0。登录工银e生活App查询账单明细。【工商银行】",
        )
    }

    // ==================================================================
    // B2. 反向：带账单短语但**有消费动词** ⇒ 必须仍然解析为支出
    //     （若拒绝判据过宽，这些真实消费会被整条丢掉 —— 漏记。）
    // ==================================================================

    @Test
    fun `B2a - 消费 + 到期还款日尾缀仍是支出`() {
        assertExpense("B2a 还款日尾缀", "您尾号1234卡消费500元，到期还款日为10月25日。", -50_000L)
    }

    @Test
    fun `B2b - 消费 + 本期账单应还0元 仍是支出（有消费动词就不算账单汇总）`() {
        assertExpense(
            "B2b 本期账单应还",
            "您尾号1234卡消费1,000元，本期账单应还0元。",
            -100_000L,
        )
    }

    @Test
    fun `B2c - 消费 + 最低还款额提示 仍是支出`() {
        assertExpense(
            "B2c 最低还款额尾缀",
            "您尾号1234的信用卡于10月8日消费328.00元，本月最低还款额328.00元（已全额占用）。",
            -32_800L,
        )
    }

    @Test
    fun `B2d - 普通商户消费通知仍是支出`() {
        assertExpense("B2d 普通消费", "您尾号1234卡在美团消费38.50元", -3_850L)
    }

    // ==================================================================
    // B3. 真实还款交易：`还款` 触发词与账单拒绝必须能共存
    // ==================================================================

    @Test
    fun `B3 - 真实信用卡还款交易仍解析（账单拒绝不得误杀还款）`() {
        assertExpense("B3 还款交易", "您尾号1234信用卡还款1,000元", -100_000L)
    }

    // ==================================================================
    // B4. 规则结构：不得为某个银行包名加专属规则
    //     候选集语义见 NotificationParser：非微信/支付宝包名只认 packageNames == null。
    // ==================================================================

    @Test
    fun `B4 - no bank-specific rule may hijack the generic candidate set`() {
        val bankScoped = DefaultNotificationRules.PACK.filter { rule ->
            rule.packageNames?.any { !it.startsWith("com.tencent.mm") && it != DefaultNotificationRules.PKG_ALIPAY } == true
        }
        assertTrue(
            bankScoped.isEmpty(),
            "不得新增银行包名专属规则：一旦出现 packageNames = setOf(\"com.icbc\") 这类规则，" +
                "该包名下所有通用规则会同时退出候选集（工行真实消费/收入被整片丢弃）。" +
                "违规规则=${bankScoped.map { it.id }}",
        )
        // 正向前置：工行样本能命中 bank_generic_out，说明它确实在候选集里走过（只是被拒绝）
        val r = parser.parse("com.icbc", "95588", "您尾号1234卡在美团消费38.50元")
        assertNotNull(r, "com.icbc 必须仍能命中通用支出规则（否则上面的结构性断言没有意义）")
        assertEquals("bank_generic_out", r.ruleId, "实际=${describe(r)}")
    }

    // ==================================================================
    // B5. 锚定：`\A` 是必要条件（可执行约束，而不是注释）
    // ==================================================================

    @Test
    fun `B5 - the A anchor is what keeps the negative lookahead covering the whole body`() {
        val unanchored =
            """(?s)(?=.*(?:应还款额|应还金额|到期还款日|最低还款额|本期账单应还))""" +
                """(?=.*\d[\d.,]*\s?元)(?!.*(?:消费|支出|扣款|取款|支付成功|交易成功|已消费))"""
        val realExpense = "您尾号1234卡消费500元，到期还款日为10月25日。"
        assertTrue(
            Regex(unanchored).containsMatchIn(realExpense),
            "前置不成立：去掉 \\A 后本应在「消费」之后重新起匹配并误命中 —— " +
                "说明正则形状已改，请重新评估本用例",
        )
        // 有 \A 的生产规则不得命中它
        assertExpense("B5 锚定必要性", realExpense, -50_000L)
    }
}
