package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 独立验证：支付宝新版「你有一笔X元的支出」句式（commit 1777697）。
 *
 * ⚠️ 本文件由 QA 独立编写，**不复用**工程师的断言，目的是**证伪**而非盖章。
 * 每一条都直接驱动 [NotificationParser] + [DefaultNotificationRules.PKG_ALIPAY]，
 * 从行为（ruleId / amountMinor / direction / explicitType）反推修复是否真的生效。
 *
 * 被测真机样本：标题「交易提醒」/ 正文「你有一笔9.90元的支出，领2元小荷包支付红包。」
 */
class AlipayExpenseWordingAuditTest {

    private val parser = NotificationParser()
    private val alipay = DefaultNotificationRules.PKG_ALIPAY
    private val wechat = DefaultNotificationRules.PKG_WECHAT

    // ------------------------------------------------------------ 正例：必须命中

    @Test
    fun `real device sample hits alipay_pay with the expense amount`() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔9.90元的支出，领2元小荷包支付红包。")
        assertNotNull(r, "真机样本必须命中，否则修复无效")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor, "金额必须是支出额 9.90，不能被尾缀的 2 元红包抢走")
        assertEquals(Direction.OUT, r.direction)
        assertNull(r.explicitType, "普通支出不应带账本类型")
    }

    @Test
    fun `spaced variant still extracts 990`() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔 9.90 元的支出")
        assertNotNull(r, "带空格的变体也应命中")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun `thousands separator variant extracts 123456`() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔1,234.56元的支出")
        assertNotNull(r)
        assertEquals(-123_456L, r.amountMinor, "千分位必须被正确解析")
    }

    @Test
    fun `amount stealing reverse case prefers the expense amount -200`() {
        // 营销尾缀里的 9.90 出现在「元的支出」之后：只有「锚定在『的支出』前面那个数」的新正则
        // （或顺序正确的回退）才能取到 2，而不是 9.90。
        val r = parser.parse(alipay, "交易提醒", "你有一笔2元的支出，领9.90元红包")
        assertNotNull(r)
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-200L, r.amountMinor, "必须取支出额 2；若得 -990 说明金额被尾缀抢走 ⇒ 修复无效")
    }

    @Test
    fun `simplest form without marketing tail extracts 990`() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔9.90元的支出")
        assertNotNull(r)
        assertEquals(-990L, r.amountMinor)
    }

    // ------------------------------------------------------------ 负例：必须不命中（防误伤）

    @Test
    fun `huabei bill summary is not recorded`() {
        val r = parser.parse(alipay, "支付宝", "你的花呗本月账单：本月支出1,280.00元，请于10日还款")
        assertNull(r, "账单汇总文案不得记成一笔支出")
    }

    @Test
    fun `bill summary contains the bare word but not the literal trigger`() {
        // 证伪「是裸『支出』在起作用」：账单文案里确实有裸「支出」，但因为没有「元的支出」四字短语，
        // 仍然不命中。
        val body = "你的花呗本月账单：本月支出1,280.00元，请于10日还款"
        assertTrue(body.contains("支出"), "前提：该文案确实含裸『支出』")
        assertTrue(!body.contains("元的支出"), "前提：该文案不含『元的支出』")
        assertNull(parser.parse(alipay, "支付宝", body))
    }

    @Test
    fun `deleting the literal trigger phrase leaves the text unrecordable`() {
        // 「删掉『元的支出』触发词后仍为 null」：同一条会被记账的文本，抹掉四字短语即失效。
        //
        // ⚠️ 夹具原先是「你有一笔1,280.00元的支出，请于10日还款」，随 alipay_pay 新增
        // 「账单/还款语境 + X元的支出」的合取式拒绝后，它会被判为还款提醒 ⇒ 不再可记账，
        // 与本用例「作为对照前提必须可记」相冲突。去掉末尾还款从句后意图**完全不变**
        // （仍在验证「四字短语而非裸『支出』起关键作用」），断言值也未改动。
        val recorded = parser.parse(alipay, "交易提醒", "你有一笔1,280.00元的支出")
        assertNotNull(recorded, "含触发词时应能记账（作为对照前提）")
        assertEquals(-128_000L, recorded.amountMinor)

        val stripped = "你有一笔1,280.00元的支出".replace("元的支出", "")
        assertTrue(!stripped.contains("元的支出"))
        assertNull(
            parser.parse(alipay, "交易提醒", stripped),
            "抹掉『元的支出』后必须不再命中 —— 证明是四字短语而非裸『支出』在起作用",
        )
    }

    @Test
    fun `marketing and activity wordings are not recorded`() {
        val samples = listOf(
            "支付宝红包活动：领取2元立减金，先到先得",
            "你有一笔红包待领取，快去参与活动",
            "恭喜获得积分奖励，有机会参与抽奖",
        )
        samples.forEach { body ->
            assertNull(
                parser.parse(alipay, "支付宝", body),
                "营销/活动文案不得记账：$body",
            )
        }
    }

    // ------------------------------ 账单 / 还款语境：合取式上下文拒绝（不得记成一笔支出）

    @Test
    fun `bill summary with the literal phrase is not recorded`() {
        assertNull(
            parser.parse(alipay, "支付宝", "本月1,280.00元的支出，请于10日还款"),
            "账单汇总里的「X元的支出」不是一笔交易，不得记账",
        )
    }

    @Test
    fun `huabei bill summary with the literal phrase is not recorded`() {
        assertNull(
            parser.parse(alipay, "支付宝", "你的花呗账单：1,280.00元的支出，请于10日还款"),
            "花呗账单汇总不得记成一笔支出",
        )
    }

    @Test
    fun `real device sample is not rejected by the bill context guard`() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔9.90元的支出，领2元小荷包支付红包。")
        assertNotNull(r, "真机样本不含账单/还款语境，不得被上下文拒绝误拒")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `legacy pay wording is not rejected by the bill context guard`() {
        val r = parser.parse(alipay, "支付宝", "成功付款 ￥9.90")
        assertNotNull(r, "旧文案既无语境词也无『X元的支出』，不得被上下文拒绝误拒")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun `bare repayment word never rejects a real payment`() {
        // team-lead 指定的第 5 条探针：`信用卡还款成功，￥500.00`
        // 改动前 null / 改动后仍 null —— 两次都因为它压根不含任何触发词，
        // **不是**被合取拒绝（合取还要求同时出现「X元的支出」）。
        assertNull(parser.parse(alipay, "支付宝", "信用卡还款成功，￥500.00"))

        // 对照组：摘掉 bodyRejectPatterns（即本轮改动前的状态），结果必须完全一致
        // ⇒ 证明该探针的 null 与合取拒绝无关，改动对它零影响。
        val withoutGuard = NotificationParser(
            DefaultNotificationRules.PACK.map {
                if (it.id == "alipay_pay") it.copy(bodyRejectPatterns = emptyList()) else it
            },
        )
        assertNull(
            withoutGuard.parse(alipay, "支付宝", "信用卡还款成功，￥500.00"),
            "改动前同样为 null —— 第 5 条探针的结果与合取拒绝无关",
        )

        // 更强的鉴别探针：让它真的命中 alipay_pay，再单独带上语境词。
        // 合取不成立 ⇒ 必须照常记账。若把裸词「还款」塞进 bodyRejectAny，这条就会被整条拒掉
        // （本项目「支付给」裸词翻车的同类教训）。
        val r = parser.parse(alipay, "支付宝", "成功付款 ￥500.00，请于10日前还款")
        assertNotNull(r, "含『还款』但不含『X元的支出』⇒ 合取不成立，真实还款付款不得被拒")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-50_000L, r.amountMinor)
    }

    @Test
    fun `each half of the conjunction alone does not reject`() {
        // 拆开合取的两个条件逐条验证：只有**两者同时成立**才拒绝。
        val keywordOnly = "成功付款 ￥500.00，你的本月账单已出" // 只有语境词，没有「X元的支出」
        val phraseOnly = "成功付款，你有一笔500.00元的支出" // 只有「X元的支出」，没有语境词
        listOf(keywordOnly, phraseOnly).forEach { body ->
            val r = parser.parse(alipay, "支付宝", body)
            assertNotNull(r, "只满足合取的一半不得拒绝：$body")
            assertEquals("alipay_pay", r.ruleId)
            assertEquals(-50_000L, r.amountMinor)
        }
    }

    // ------------------------------------------------------------ 跨规则副作用

    @Test
    fun `same body under wechat package is not claimed by alipay_pay`() {
        val r = parser.parse(wechat, "微信", "你有一笔9.90元的支出，领2元小荷包支付红包。")
        assertNull(r, "微信包名不得被 alipay_pay 认领（其 packageNames 只含支付宝）")
    }

    @Test
    fun `alipay refund still wins over the new expense trigger`() {
        val r = parser.parse(alipay, "支付宝", "退款成功，￥9.90 已原路退回")
        assertNotNull(r)
        assertEquals("refund_alipay", r.ruleId, "退款规则排在前，不得被付款规则的触发词抢走")
        assertEquals(Direction.IN, r.direction)
    }

    @Test
    fun `legacy alipay pay wording still works`() {
        val r = parser.parse(alipay, "支付宝", "成功付款 ￥9.90")
        assertNotNull(r)
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor, "新正则排在最前，不得抢错或抢不到现有文案")
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `blank body and title yields null`() {
        assertNull(parser.parse(alipay, "", ""))
    }

    @Test
    fun `title only yields null`() {
        assertNull(parser.parse(alipay, "交易提醒", ""))
    }

    @Test
    fun `combined old verb plus new phrase extracts the expense amount`() {
        val r = parser.parse(alipay, "交易提醒", "支付成功，你有一笔9.90元的支出")
        assertNotNull(r)
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor, "新正则优先，取到的仍是支出额 9.90")
    }

    // ------------------------------------------------------------ 正则硬约定：每条金额正则含且仅含一个捕获组

    @Test
    fun `alipay amount patterns expose exactly one capture group each`() {
        val alipayRule = DefaultNotificationRules.PACK.first { it.id == "alipay_pay" }
        alipayRule.amountPatterns.forEachIndexed { i, p ->
            val groups = Regex(p).toPattern().matcher("").groupCount()
            assertEquals(1, groups, "amountPatterns[$i] 必须含且仅含一个捕获组：$p")
        }
    }

    @Test
    fun `the newly inserted pattern is the first amount pattern`() {
        val alipayRule = DefaultNotificationRules.PACK.first { it.id == "alipay_pay" }
        assertTrue(
            alipayRule.amountPatterns.first().contains("元的支出"),
            "新正则必须排在 amountPatterns 最前，否则营销尾缀会抢位",
        )
    }

    // ------------------------------------------------------------ 独立复现「修复前」的 bug

    /** 修复前的 alipay_pay（从 commit 1777697^ 的规则逐字重建，仅用于对照，不动生产代码）。 */
    private val oldAlipayPay = NotificationRule(
        id = "alipay_pay",
        label = "支付宝付款",
        packageNames = setOf(DefaultNotificationRules.PKG_ALIPAY),
        titleMustContainAny = listOf("支付宝", "交易提醒", "账单"),
        bodyMustContainAny = listOf("成功付款", "付款成功", "已付款", "支付成功", "即时到账交易"),
        bodyRejectAny = listOf("收款成功", "退款成功"),
        amountPatterns = listOf(
            """(?:成功付款|付款成功|已付款|支付成功)([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
            """[¥￥]\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
            """(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元""",
        ),
        counterpartyPatterns = listOf(
            """(?:收款方|商家|商户)[^\S\n]{0,4}[:：]?\s?([^\s,，|]{2,24})""",
        ),
        direction = Direction.OUT,
    )

    @Test
    fun `the new anchor pattern is load-bearing when an earlier yuan amount precedes it`() {
        // 工程师称新正则是「加固而非必需」。验证：在真机样本里它确实非必需（第一条金额就是支出额），
        // 但一旦正文更靠前处出现别的「数字+元」，它就成了必需品。
        val body = "本月已支出500元，你有一笔9.90元的支出"
        val r = parser.parse(alipay, "交易提醒", body)
        assertNotNull(r)
        assertEquals(-990L, r.amountMinor, "新正则把金额锚定在『的支出』前的数，必须取 9.90 而非更早的 500")

        // 对照：临时去掉新正则（只改本地规则副本，不动生产代码）后，同一文本会被更早的 500 抢走。
        val noAnchor = NotificationParser(
            DefaultNotificationRules.PACK.map { rule ->
                if (rule.id == "alipay_pay") rule.copy(amountPatterns = rule.amountPatterns.drop(1)) else rule
            },
        )
        assertEquals(
            -50_000L, // 500 元 = 50000 分
            noAnchor.parse(alipay, "交易提醒", body)?.amountMinor,
            "去掉锚定正则后金额被更早的 500 元（50000 分）抢走 —— 反证新正则在真实变体里确有作用",
        )
    }

    @Test
    fun `pre-fix rule truly returned null for the real sample`() {
        val oldParser = NotificationParser(
            DefaultNotificationRules.PACK.map { if (it.id == "alipay_pay") oldAlipayPay else it },
        )
        assertNull(
            oldParser.parse(alipay, "交易提醒", "你有一笔9.90元的支出，领2元小荷包支付红包。"),
            "修复前该样本必须返回 null（未命中规则）—— 独立复现原始 bug",
        )
    }
}
