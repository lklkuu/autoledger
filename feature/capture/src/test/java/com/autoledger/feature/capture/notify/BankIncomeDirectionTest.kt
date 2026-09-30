package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用户实测 Bug 1：**银行「收入」被记成支出（EXPENSE −5055）**。
 *
 * 同一笔工行定期到期入账，两个渠道各发一条：
 * - 银行短信（发件号 95588）：
 *   `[3条]尾号9783卡9月30日07:16工商银行收入(整整到期)5,055元，余额6,056.05元。【工商银行】`
 * - 工行 App 动账通知（title=「动账通知」）：
 *   `尾号9783卡9月30日07:16工商银行收入(整整到期)5,055元。请点击查看详情。`
 *
 * 本文件先于修复编写，用来**锁定现状**：修复前 `bank_generic_out` 会抢走那些
 * 「含交易动词 + 用了未被识别的入账词」的银行收入文本，落成 OUT。
 *
 * 修复原则（团队约定）：
 *  1. 正文含「收入」语义的银行文本**绝不能落成 OUT**；
 *  2. 金额正则必须覆盖「关键词(注记)金额」形态（如 `收入(整整到期)5,055元`）。
 */
class BankIncomeDirectionTest {

    private val parser = NotificationParser()

    // ------------------------------------------------------------------ 用户截图原文

    /** 银行短信：发件号码 95588 —— title 是号码，正文才是账单。 */
    private val smsBody =
        "[3条]尾号9783卡9月30日07:16工商银行收入(整整到期)5,055元，余额6,056.05元。【工商银行】"

    /** 工行 App 动账通知：title 是「动账通知」。 */
    private val appBody =
        "尾号9783卡9月30日07:16工商银行收入(整整到期)5,055元。请点击查看详情。"

    @Test
    fun `screenshot 1 - icbc bank sms income is booked as positive income`() {
        val r = parser.parse("sms:inbox", "95588", smsBody)
        assertNotNull(r, "银行收入短信必须能解析")
        assertEquals("sms_bank_in", r.ruleId, "必须命中收入规则，实际=${r?.ruleId}")
        assertEquals(Direction.IN, r.direction, "收入短信不得落成 OUT")
        assertEquals(505_500L, r.amountMinor, "5,055 元 = +505500 分")
    }

    @Test
    fun `screenshot 2 - icbc app 动账通知 income is booked as positive income`() {
        val r = parser.parse("com.icbc", "动账通知", appBody)
        assertNotNull(r, "工行动账通知必须能解析")
        assertEquals("sms_bank_in", r.ruleId, "必须命中收入规则，实际=${r?.ruleId}")
        assertEquals(Direction.IN, r.direction, "动账通知里的「收入」不得落成 OUT")
        assertEquals(505_500L, r.amountMinor, "5,055 元 = +505500 分")
    }

    @Test
    fun `screenshot 3 - both channels extract the bank name as the counterparty`() {
        // Bug 2 的前置条件：两条跨渠道记录必须抽到**同一个**商户/银行名，
        // 否则指纹走「blank」分支（含 sourceId + sourceRef）永远不可能相等。
        val sms = assertNotNull(parser.parse("sms:inbox", "95588", smsBody))
        val app = assertNotNull(parser.parse("com.icbc", "动账通知", appBody))
        assertEquals("工商银行", sms.counterparty, "短信侧应抽出银行名")
        assertEquals("工商银行", app.counterparty, "通知侧应抽出同一个银行名")
        assertEquals(sms.counterparty, app.counterparty, "跨渠道必须归一化到同一个实体")
    }

    // ------------------------------------------------------------------ 未被识别的入账词（Bug 1 根因）

    @Test
    fun `入账 with 交易 must not fall into the outflow rule`() {
        // 根因：`bank_generic_out` 的 bodyMustContainAny 含极泛的「交易」，
        // 而它的 bodyRejectAny 只认 4 个入账词（工资/转入/收入/退款），
        // 「入账」不在其中 → 收入被判成支出 −505500。
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡9月30日07:16交易入账5,055元")
        assertNotNull(r)
        assertEquals("sms_bank_in", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.IN, r.direction, "「入账」是资金流入")
        assertEquals(505_500L, r.amountMinor)
    }

    @Test
    fun `到账 with 交易提醒 must not fall into the outflow rule`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡9月30日07:16交易提醒：到账5,055元")
        assertNotNull(r)
        assertEquals("sms_bank_in", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.IN, r.direction)
        assertEquals(505_500L, r.amountMinor)
    }

    @Test
    fun `存入 with 交易成功 must not fall into the outflow rule`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡9月30日07:16交易成功，存入5,055元")
        assertNotNull(r)
        assertEquals("sms_bank_in", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.IN, r.direction)
        assertEquals(505_500L, r.amountMinor)
    }

    @Test
    fun `汇入 with 交易 must not fall into the outflow rule`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡9月30日07:16交易汇入5,055元")
        assertNotNull(r)
        assertEquals("sms_bank_in", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.IN, r.direction)
        assertEquals(505_500L, r.amountMinor)
    }

    @Test
    fun `结息 sms is recognized as income instead of being dropped`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡9月30日结息5,055元")
        assertNotNull(r, "季度结息是真实收入，此前因关键词缺失被静默丢弃")
        assertEquals("sms_bank_in", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.IN, r.direction)
        assertEquals(505_500L, r.amountMinor)
    }

    // ------------------------------------------------------------------ 「关键词(注记)金额」形态

    @Test
    fun `income keyword followed by a bracket annotation and no 元 unit`() {
        // 「收入(整整到期)5,055」——没有「元」时，第一条正则（数字+元）失效，
        // 第三条必须能覆盖这种「关键词 + 可选注记 + 金额」形态。
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡9月30日07:16工商银行收入(整整到期)5,055")
        assertNotNull(r)
        assertEquals(Direction.IN, r.direction)
        assertEquals(505_500L, r.amountMinor, "关键词后的金额必须被提取，而不是落到 null → EXPENSE")
    }

    @Test
    fun `income keyword followed by 入账 connector and no 元 unit`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡9月30日07:16工资入账5,055")
        assertNotNull(r)
        assertEquals(Direction.IN, r.direction)
        assertEquals(505_500L, r.amountMinor)
    }

    // ------------------------------------------------------------------ 护栏：不得把尾号/日期当金额

    @Test
    fun `card tail digits after an income keyword are never taken as the amount`() {
        // 护栏：放开「关键词 + 间隔 + 数字」后，绝不能把「尾号 9783」当成 9783 元收入。
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡工资入账，尾号9783")
        assertNotNull(r, "仍应命中收入规则，只是金额取不到")
        assertEquals(Direction.IN, r.direction)
        assertNull(r.amountMinor, "取不到金额应进待确认，绝不拿尾号当金额")
    }

    @Test
    fun `date digits after an income keyword are never taken as the amount`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号9783卡工资入账9月30日")
        assertNotNull(r)
        assertNull(r.amountMinor, "「9月」不得被当成 9 元收入")
    }

    // ------------------------------------------------------------------ 护栏：真实支出不得被误伤

    @Test
    fun `real expense 动账通知 with 收款方 stays an outflow`() {
        // 取舍记录：裸「收款」未列入收入词（支出短信常写「收款方：XX」），
        // 因此这类真实支出必须仍然是 OUT —— 防止后来者把「收款」加进收入词导致方向翻转。
        val r = parser.parse(
            "com.icbc", "动账通知",
            "尾号6602卡9月27日23:52支出(消费财付通-2zero首饰屋)39.80元。请点击查看详情。",
        )
        assertNotNull(r)
        assertEquals("bank_generic_out", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.OUT, r.direction)
        assertEquals(-3_980L, r.amountMinor)
    }

    @Test
    fun `real expense sms with 交易成功 and 收款方 stays an outflow`() {
        val r = parser.parse(
            "sms:inbox", "95588",
            "您尾号1234卡9月27日交易成功200.00元，收款方：抖音",
        )
        assertNotNull(r, "真实消费短信不得被收入词误杀")
        assertEquals("bank_generic_out", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.OUT, r.direction)
        assertEquals(-20_000L, r.amountMinor)
    }

    @Test
    fun `marketing sms abusing the word 工资 is not booked as income`() {
        // 收入规则同样要挡营销词：否则「工资理财，有机会领取红包」会被记成一笔收入。
        val r = parser.parse(
            "sms:inbox", "95588",
            "【工商银行】工资代发专享，活动期间有机会领取500元红包，回复TD退订",
        )
        assertNull(r, "营销短信不得被记为收入")
    }

    // ------------------------------------------------------------------ 反向护栏：真实支出绝不能变成收入
    //
    // 这一组是 d854d8c 引入、由 QA 独立护栏（BankExpenseRegressionGuardTest）抓出来的**方向反转**回归：
    // 若把入账词整表塞进 bank_generic_out.bodyRejectAny，支出规则让位后收入规则会顺势认领，
    // 「消费5000元，将于10月25日入账」会被记成 +5000 的收入 —— 比漏记严重得多。
    // 修法是让位条件必须带上下文（入账词要**主导**本次金额），见 INCOME_GOVERNS_AMOUNT。

    @Test
    fun `credit card purchase sms ending with 入账 stays an outflow`() {
        // 信用卡账单短信最常见的尾缀：钱先花掉、次月才入账。
        val r = parser.parse("sms:inbox", "95588", "您尾号1234信用卡本期消费5,000元，将于10月25日入账。")
        assertNotNull(r, "真实消费不得被丢弃")
        assertEquals("bank_generic_out", r.ruleId, "实际=${r.ruleId}")
        assertEquals(Direction.OUT, r.direction, "消费短信绝不能被判成收入（方向反转）")
        assertEquals(-500_000L, r.amountMinor)
    }

    @Test
    fun `purchase sms whose 入账 is a trailing clause stays an outflow`() {
        val r = parser.parse("sms:inbox", "95588", "您尾号1234的卡9月30日07:16消费500元，该笔交易将于次日入账。")
        assertNotNull(r)
        assertEquals(Direction.OUT, r.direction)
        assertEquals(-50_000L, r.amountMinor)
    }

    @Test
    fun `purchase sms with a subsidy sub amount stays an outflow of the real amount`() {
        // 「补贴100元」是子金额，不是本次交易的金额 —— 支出规则不得让位，金额也必须取 500。
        val r = parser.parse("sms:inbox", "95588", "您尾号1234的卡9月30日消费500元，其中政府补贴100元，实付400元。")
        assertNotNull(r)
        assertEquals(Direction.OUT, r.direction, "实际=${r.ruleId}")
        assertEquals(-50_000L, r.amountMinor, "必须取 500 元，而不是补贴的 100 元")
    }

    @Test
    fun `invariant - expense samples carrying an income word are never booked as income`() {
        val samples = listOf(
            "您尾号1234的卡9月30日07:16消费500元，该笔交易将于次日入账。",
            "您尾号1234信用卡本期消费5,000元，将于10月25日入账。",
            "您尾号1234的卡9月30日消费500元，其中政府补贴100元，实付400元。",
            "您尾号1234的卡（代发账户）9月30日消费500元。",
            "您尾号1234的卡9月30日消费500元，资金将于T+1日到账商户。",
            "您尾号1234的卡9月30日消费500元，本期结息日为10月20日。",
            "您尾号1234的卡9月30日消费500元，商户已收款。",
            "您尾号1234的卡9月30日消费500元，手续费将于次月退还。",
            "您尾号1234的卡9月30日消费500元，请于还款日前存入足额款项。",
        )
        val inverted = samples.mapNotNull { body ->
            val r = parser.parse("sms:inbox", "95588", body)
            if (r != null && r.direction == Direction.IN) "「$body」→ ${r.ruleId}/${r.amountMinor}" else null
        }
        assertTrue(
            inverted.isEmpty(),
            "以下真实消费被记成了收入（方向反转，比漏记更严重）：\n  ${inverted.joinToString("\n  ")}",
        )
    }

    // ------------------------------------------------------------------ 不变量：含收入语义的文本永不落 OUT

    @Test
    fun `invariant - no bank inflow sample is ever classified as an outflow`() {
        val samples = listOf(
            "sms:inbox" to "95588" to smsBody,
            "com.icbc" to "动账通知" to appBody,
            "sms:inbox" to "95588" to "您尾号9783卡9月30日07:16交易入账5,055元",
            "sms:inbox" to "95588" to "您尾号9783卡9月30日交易提醒：到账5,055元",
            "sms:inbox" to "95588" to "您尾号9783卡9月30日交易成功，存入5,055元",
            "sms:inbox" to "95588" to "您尾号9783卡9月30日交易汇入5,055元",
            "sms:inbox" to "95588" to "您尾号9783卡9月30日结息5,055元",
            "sms:inbox" to "95588" to "您尾号9783卡9月30日返现5,055元",
            "sms:inbox" to "95588" to "您尾号9783卡9月30日代发工资5,055元",
            "sms:inbox" to "95588" to "您尾号9783卡9月30日转入5,055元",
        )
        for ((pkgTitle, body) in samples) {
            val pkg = pkgTitle.first
            val title = pkgTitle.second
            val r = parser.parse(pkg, title, body)
            if (r == null) continue // 漏记可接受（宁可漏记也不记反），但方向绝不能反
            assertTrue(
                r.direction != Direction.OUT,
                "收入文本被判成支出：rule=${r.ruleId} dir=${r.direction} body=$body",
            )
        }
    }
}
