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
        val recorded = parser.parse(alipay, "交易提醒", "你有一笔1,280.00元的支出，请于10日还款")
        assertNotNull(recorded, "含触发词时应能记账（作为对照前提）")
        assertEquals(-128_000L, recorded.amountMinor)

        val stripped = "你有一笔1,280.00元的支出，请于10日还款".replace("元的支出", "")
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

    // -------------------- 「X元的支出」的白名单收窄：只有「你有一笔X元的支出」才认

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
    fun `real device sample is not rejected by the template guard`() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔9.90元的支出，领2元小荷包支付红包。")
        assertNotNull(r, "真机样本就是『你有一笔X元的支出』句式，不得被白名单拒绝误拒")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `legacy pay wording is not rejected by the template guard`() {
        val r = parser.parse(alipay, "支付宝", "成功付款 ￥9.90")
        assertNotNull(r, "旧文案不含『X元的支出』⇒ 拒绝条件根本不触发")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun `bare repayment word never rejects a real payment`() {
        // team-lead 指定的第 5 条探针：`信用卡还款成功，￥500.00`
        // 改动前 null / 改动后仍 null —— 两次都因为它压根不含任何触发词，
        // **不是**被拒绝式命中（拒绝式还要求出现「数字+元的支出」）。
        assertNull(parser.parse(alipay, "支付宝", "信用卡还款成功，￥500.00"))

        // 对照组：摘掉 bodyRejectPatterns（即本轮改动前的状态），结果必须完全一致
        // ⇒ 证明该探针的 null 与白名单拒绝无关，改动对它零影响。
        val withoutGuard = NotificationParser(
            DefaultNotificationRules.PACK.map {
                if (it.id == "alipay_pay") it.copy(bodyRejectPatterns = emptyList()) else it
            },
        )
        assertNull(
            withoutGuard.parse(alipay, "支付宝", "信用卡还款成功，￥500.00"),
            "改动前同样为 null —— 第 5 条探针的结果与白名单拒绝无关",
        )

        // 鉴别探针：让它真的命中 alipay_pay，再单独带上语境词。
        // 本文本没有「元的支出」⇒ 拒绝条件不触发 ⇒ 必须照常记账。
        // 若把裸词「还款」塞进 bodyRejectAny，这条就会被整条拒掉
        // （本项目「支付给」裸词翻车的同类教训）。
        val r = parser.parse(alipay, "支付宝", "成功付款 ￥500.00，请于10日前还款")
        assertNotNull(r, "含『还款』但不含『X元的支出』⇒ 拒绝不触发，真实还款付款不得被拒")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-50_000L, r.amountMinor)
    }

    @Test
    fun `real transaction with a repayment clause is still recorded`() {
        // 与「黑名单」方案的**决定性差异**：这是真实的消费通知（花呗消费 + 还款提示），
        // 白名单只看句式「你有一笔X元的支出」，不会因为带「还款」就整条丢掉。
        val r = parser.parse(alipay, "交易提醒", "你有一笔1,280.00元的支出，请于10日还款")
        assertNotNull(r, "带还款从句的真实交易必须照样记账 —— 黑名单方案会在这里误杀")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-128_000L, r.amountMinor)
    }

    @Test
    fun `spaced real transaction wording is not rejected`() {
        // 证明白名单句式里的 `\s?` 生效：带空格的真句式不会被判成「非一笔句式」而拒绝。
        val r = parser.parse(alipay, "交易提醒", "你有一笔 9.90 元的支出")
        assertNotNull(r)
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun `real transaction with a currency symbol is not rejected`() {
        // 「一笔」与金额之间的币符必须容得下 —— 否则白名单会把真交易误判成账单文案整条拒绝。
        // 口径与本规则首条金额正则 `一笔\s?([¥￥]?\s?\d+…` 保持一致。
        listOf("￥", "¥").forEach { symbol ->
            val r = parser.parse(alipay, "交易提醒", "你有一笔${symbol}9.90元的支出")
            assertNotNull(r, "金额带币符的真机句式不得被白名单拒绝：$symbol")
            assertEquals("alipay_pay", r.ruleId)
            assertEquals(-990L, r.amountMinor, "金额必须取支出额 9.90（$symbol）")
        }
    }

    @Test
    fun `real payment with a monthly aggregate clause is still recorded`() {
        // QA-A1 回归：正文靠老触发词「成功付款」命中，只是**附带**了一句「…元的支出」汇总。
        // 白名单收窄是给新触发词用的，不得顺手取消既有付款动词的资格（作用域放大）。
        val r = parser.parse(alipay, "交易提醒", "成功付款9.90元。本月累计1,280.00元的支出。")
        assertNotNull(r, "既有付款动词命中的真交易不得被白名单整条拒绝（QA-A1）")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor, "金额必须取真实付款额 9.90，而不是累计句的 1,280.00")
    }

    @Test
    fun `bill texts contain none of the legacy pay verbs so the narrowing still applies`() {
        // 修法成立的前提：上述收窄之所以仍能挡住账单文案，是因为这些文案**不含**任何既有付款动词。
        // 一旦将来有人在账单文案里加入这些动词，本用例会失败并迫使重新审视修法 —— 故意钉死前提，而不是假设。
        val legacyVerbs = listOf("成功付款", "付款成功", "已付款", "支付成功", "即时到账交易")
        val billTexts = listOf(
            "本月1,280.00元的支出，请于10日还款",
            "你的花呗账单：1,280.00元的支出，请于10日还款",
            "账单：你本月累计9.90元的支出",
        )
        billTexts.forEach { body ->
            val hits = legacyVerbs.filter { body.contains(it) }
            assertTrue(hits.isEmpty(), "前提：账单文案不得含既有付款动词，实际命中 $hits：$body")
            assertNull(parser.parse(alipay, "支付宝", body), "账单文案仍必须被拒：$body")
        }
    }

    @Test
    fun `accumulated bill figure is not recorded`() {
        assertNull(
            parser.parse(alipay, "支付宝", "账单：你本月累计9.90元的支出"),
            "账单口径的累计「X元的支出」不是一笔交易",
        )
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

    // ------------------------------------------------------------ 判据总表（表驱动）

    /**
     * 把整张判据表钉成**可执行**用例 —— 一次性人工对照守不住将来（QA 建议）。
     *
     * 每条的期望值都是当前**已确认**的行为；任何一条发生变化，都必须是有意为之，
     * 并在此处同步改期望值 + 写明原因。**禁止**为了让断言变绿而改这里。
     */
    @Test
    fun `alipay wording decision matrix as a whole`() {
        data class Case(
            val title: String,
            val body: String,
            /** null = 不得记账 */
            val amountMinor: Long?,
            val note: String,
        )

        val cases = listOf(
            // ---- 真实交易：必须记账 ----
            Case("交易提醒", "你有一笔9.90元的支出，领2元小荷包支付红包。", -990L, "真机样本"),
            Case("交易提醒", "你有一笔9.90元的支出", -990L, "最简真句式"),
            Case("交易提醒", "你有一笔 9.90 元的支出", -990L, "带空格变体"),
            Case("交易提醒", "你有一笔1,234.56元的支出", -123_456L, "千分位"),
            Case("交易提醒", "你有一笔2元的支出，领9.90元红包", -200L, "抢位反例：取支出额而非尾缀"),
            Case("交易提醒", "你有一笔￥9.90元的支出", -990L, "全角币符"),
            Case("交易提醒", "你有一笔¥9.90元的支出", -990L, "半角币符"),
            Case("交易提醒", "你有一笔1,280.00元的支出，请于10日还款", -128_000L, "真交易带还款从句"),
            Case("支付宝", "成功付款 ￥9.90", -990L, "旧文案（老触发词）"),
            Case("交易提醒", "支付成功，你有一笔9.90元的支出", -990L, "旧动词 + 新句式"),
            Case("支付宝", "成功付款 ￥500.00，请于10日前还款", -50_000L, "裸词『还款』不得误伤"),
            Case("交易提醒", "本月已支出500元，你有一笔9.90元的支出", -990L, "更早的『元』不得抢位"),
            Case("交易提醒", "成功付款9.90元。本月累计1,280.00元的支出。", -990L, "QA-A1 回归：老动词不得被取消资格"),
            // ---- 账单 / 汇总 / 营销：不得记账 ----
            Case("支付宝", "本月1,280.00元的支出，请于10日还款", null, "账单汇总"),
            Case("支付宝", "你的花呗账单：1,280.00元的支出，请于10日还款", null, "花呗账单"),
            Case("账单", "账单：你本月累计9.90元的支出", null, "累计口径"),
            Case("支付宝", "你的花呗本月账单：本月支出1,280.00元，请于10日还款", null, "账单汇总（无四字短语）"),
            Case("交易提醒", "成功付款，本月累计1,280.00元的支出。", null, "QA-A3：动词后无金额 ⇒ 不得放行"),
            Case("交易提醒", "付款成功。你本月累计9.90元的支出", null, "QA-A3b：同上"),
            Case("交易提醒", "本次交易产生9.90元的支出，商户：肯德基", null, "QA-A2：不含『一笔』，已知未覆盖句式"),
            // ---- 已知边角（错值但本期接受，QA-A4；谁修它谁改这里） ----
            Case("交易提醒", "成功付款 10月账单：1,280.00元的支出", -1_000L, "★KNOWN EDGE QA-A4：金额误取月份 10，本期接受"),
        )

        cases.forEach { c ->
            val r = parser.parse(alipay, c.title, c.body)
            if (c.amountMinor == null) {
                assertNull(r, "应不记账（${c.note}）：${c.body}")
            } else {
                assertNotNull(r, "应记账（${c.note}）：${c.body}")
                assertEquals(c.amountMinor, r.amountMinor, "金额不符（${c.note}）：${c.body}")
            }
        }
    }
}
