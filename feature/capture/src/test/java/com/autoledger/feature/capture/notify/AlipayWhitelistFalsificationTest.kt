package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 独立验证（QA）：支付宝「X元的支出」**白名单句式**改动（commit ba58b1b + 52e690a）。
 *
 * 立场：**证伪**。本文件不复用 `AlipayExpenseWordingAuditTest` 的任何断言，
 * 全部由本 QA 重新推导期望值；并且额外做一件工程师没做的事 ——
 * **把白名单与两个历史方案（无收窄 / 合取式 9e4f476）放在同一批文本上做三方对照**，
 * 目的是找出「白名单比旧方案更差」的文本，而不是确认它更好。
 *
 * 三方对照的结论见文件末尾的对照分组。
 */
class AlipayWhitelistFalsificationTest {

    private val parser = NotificationParser()
    private val alipay = DefaultNotificationRules.PKG_ALIPAY
    private val wechat = DefaultNotificationRules.PKG_WECHAT

    private val alipayRule = DefaultNotificationRules.PACK.first { it.id == "alipay_pay" }

    /** 被否决的 commit 9e4f476 的合取式拒绝（逐字照抄其 diff）。 */
    private val conjunctiveReject =
        """(?s)(?=.*(?:账单|还款|应还|待还))(?=.*\d[\d.,]*\s?元的支出)"""

    /** 三方对照的三个解析器：**都不改生产代码**，只复制规则列表改字段。 */
    private val noNarrowingParser = NotificationParser(
        DefaultNotificationRules.PACK.map {
            if (it.id == "alipay_pay") it.copy(bodyRejectPatterns = emptyList()) else it
        },
    )

    private val conjunctiveParser = NotificationParser(
        DefaultNotificationRules.PACK.map {
            if (it.id == "alipay_pay") it.copy(bodyRejectPatterns = listOf(conjunctiveReject)) else it
        },
    )

    private fun rule(): NotificationRule = alipayRule

    // ================================================================= ① 规则形状：先看它写成了什么

    @Test
    fun whitelistRejectPatternIsTheOnlyRejectPattern() {
        assertEquals(
            1, rule().bodyRejectPatterns.size,
            "alipay_pay 现在应只靠一条白名单正则做收窄（多余条件会让下游难以推理）",
        )
        val p = rule().bodyRejectPatterns.single()
        assertTrueMsg(
            p.startsWith("(?s)\\A(?!.*(?:成功付款|付款成功|已付款|支付成功|即时到账交易)"),
            "第一道闸门必须是「负向前瞻：未命中既有付款动词」，否则白名单会取消老触发词的资格（QA-A1 回归）⇒ 实际：$p",
        )
        assertTrueMsg(
            p.contains("即时到账交易)\\s?[¥￥]?\\s?\\d"),
            "豁免必须要求「动词后紧邻金额」，不能退化成『出现动词即可』——" +
                "否则累计句会被兜底正则捞成一笔不存在的支出（QA-A3 护栏）⇒ 实际：$p",
        )
        assertTrueMsg(
            p.contains("(?!.*一笔\\s?[¥￥]?\\s?\\d"),
            "第二道闸门仍是白名单本体「负向前瞻：全串不存在『一笔+金额+元的支出』」⇒ 实际：$p",
        )
    }

    @Test
    fun currencySymbolVariantIsAllowedInsideTheWhitelistLookahead() {
        // commit 52e690a 声称补的是负向前瞻里的 [¥￥]?。若这个可选组不在，
        // 「一笔￥9.90元的支出」匹配不上白名单句式 ⇒ 被当成账单文案整条拒绝。
        assertTrueMsg(
            rule().bodyRejectPatterns.single().contains("一笔\\s?[¥￥]?\\s?\\d"),
            "负向前瞻必须容忍「一笔」后的币符，否则 ￥/¥ 变体整条漏记",
        )
        assertTrueMsg(
            rule().amountPatterns.first().contains("一笔\\s?([¥￥]?\\s?\\d"),
            "首条金额正则必须与负向前瞻口径一致（同为「一笔 + 可选币符 + 数字」）",
        )
    }

    @Test
    fun triggerWordForTheNewTemplateIsPresent() {
        assertTrueMsg(
            rule().bodyMustContainAny.contains("元的支出"),
            "触发词「元的支出」必须仍在准入表里，否则新句式根本进不到规则",
        )
    }

    // ================================================================= ② 必达正例（我自己的期望值）

    @Test
    fun realDeviceSampleIsRecorded() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔9.90元的支出，领2元小荷包支付红包。")
        assertNotNull(r, "真机样本必须命中")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor, "必须取 9.90，不能被尾缀「2元红包」抢走")
        assertEquals(Direction.OUT, r.direction)
        assertNull(r.explicitType, "普通支出不得带账本类型")
    }

    @Test
    fun fullWidthYuanSymbolVariantIsRecorded() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔￥9.90元的支出")
        assertNotNull(r, "全角币符变体不得被拒")
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun halfWidthYenSymbolVariantIsRecorded() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔¥9.90元的支出")
        assertNotNull(r, "半角币符变体不得被拒")
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun realConsumptionWithRepaymentClauseIsRecorded() {
        // 这是与「黑名单/合取式方案」的分水岭：NotificationRule.kt 的注释主张它是真消费。
        val r = parser.parse(alipay, "交易提醒", "你有一笔1,280.00元的支出，请于10日还款")
        assertNotNull(r, "真交易带还款从句必须记上")
        assertEquals(-128_000L, r.amountMinor)
    }

    @Test
    fun spacedVariantIsRecorded() {
        val r = parser.parse(alipay, "交易提醒", "你有一笔 9.90 元的支出")
        assertNotNull(r, "带空格变体不得被拒")
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun oldSuccessPaymentWordingStillWorks() {
        val r = parser.parse(alipay, "支付宝", "成功付款 ￥9.90")
        assertNotNull(r, "旧文案不得回退")
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor)
    }

    @Test
    fun refundStillWinsOverPayment() {
        val r = parser.parse(alipay, "交易提醒", "退款成功，￥9.90 已原路退回")
        assertNotNull(r, "退款必须命中退款规则")
        assertEquals("refund_alipay", r.ruleId, "退款规则排在付款规则之前，不得被付款规则抢走")
        assertEquals(990L, r.amountMinor, "退款是进账：+990")
        assertEquals(Direction.IN, r.direction)
        assertEquals(TxnType.REFUND, r.explicitType, "退款必须带 REFUND 类型，否则金额为正会被推断成 INCOME")
    }

    @Test
    fun wechatPackageDoesNotClaimAlipayWording() {
        assertNull(
            parser.parse(wechat, "交易提醒", "你有一笔9.90元的支出，领2元小荷包支付红包。"),
            "同一条文本走微信包名必须不认领（不得跨包名）",
        )
    }

    // ================================================================= ③ 必须拒绝的账单汇总文案

    @Test
    fun monthlyAggregateWithoutYiBiIsNotRecorded() {
        assertNull(parser.parse(alipay, "支付宝", "本月1,280.00元的支出，请于10日还款"))
    }

    @Test
    fun huabeiBillStatementWithoutYiBiIsNotRecorded() {
        assertNull(parser.parse(alipay, "支付宝", "你的花呗账单：1,280.00元的支出，请于10日还款"))
    }

    @Test
    fun cumulativeBillWordingIsNotRecorded() {
        assertNull(parser.parse(alipay, "账单", "账单：你本月累计9.90元的支出"))
    }

    // ================================================================= ④ 关键挑战题：白名单漏记真交易

    /**
     * **回归护栏**（原 QA-A1，已修复）：同一条通知里既有真实付款句、又有月度累计句。
     *
     * 「成功付款9.90元」是货真价实的单笔交易。白名单只要发现正文里存在任何一个
     * 「数字+元的支出」且不含「一笔…元的支出」就会整条拒绝 —— 若没有第一道负向前瞻
     * （既有付款动词）这道闸门，连本该继续生效的老触发词「成功付款」都会被一起拒掉 ⇒ 漏记真交易。
     *
     * 该缺陷在 v1.1.10 发版前修复：白名单收窄只是给新触发词「元的支出」用的，
     * **不得取消既有付款动词的资格**（`bodyRejectPatterns` 是整条规则级生效的 —— 作用域放大）。
     *
     * **修复后应然：记上 -990**，与无收窄方案、以及被否决的合取式方案（9e4f476）一致。
     */
    @Test
    fun `regression guard existing pay verbs are not disqualified by the whitelist narrowing`() {
        val body = "成功付款9.90元。本月累计1,280.00元的支出。"
        val r = parser.parse(alipay, "交易提醒", body)
        assertNotNull(
            r,
            "既有付款动词「成功付款」不得被白名单收窄取消资格（QA-A1 回归）：$body",
        )
        assertEquals("alipay_pay", r.ruleId)
        assertEquals(-990L, r.amountMinor, "金额必须取真实付款额 9.90，而不是累计句的 1,280.00")
    }

    /**
     * 【已确认漏记 QA-A2】只用「产生/发生了 X 元的支出」描述的单笔交易，没有「一笔」。
     *
     * **正确行为应为记上 -990**。本用例按**现状**（null）钉住。
     */
    @Test
    fun `KNOWN DEFECT QA-A2 transaction described without yi-bi is dropped`() {
        assertNull(
            parser.parse(alipay, "交易提醒", "本次交易产生9.90元的支出，商户：肯德基"),
            "★ 已知缺陷 QA-A2（现状=漏记 -990）：不含『一笔』的真实交易句式被白名单拒掉。" +
                "若本断言不再成立，说明缺陷已被修复 —— 请把本用例改回正例断言。",
        )
    }

    /**
     * 【回归护栏 QA-A4，已修】动词与金额之间的容差若不含**日期护栏** ⇒ 日期也能充当"金额"。
     *
     * `成功付款 10月账单：…` 里，「10」是月份而不是金额，却曾触发「老动词 + 紧邻数字」的豁免闸门，
     * 随后被 `amountPatterns[1]` 当成金额取走 ⇒ **凭空记 -1000**
     * （与 QA-A3 同属「记出一笔不存在的钱」，比漏记更糟）。
     *
     * ⚠️ 这条边缘 **QA 原提的收紧方案同样中招**（不是实现方的疏漏）：
     * 「动词 + 可选币符 + 可选空白 + 数字」这个判据本身没有排除"数字其实是日期"。
     *
     * **已修**：豁免闸门改为 `…\d+(?!\d)(?!\s?[月日年号])`（复用本仓库 `AMOUNT_BODY` 的日期护栏，
     * `(?!\d)` 不可省，否则 `\d+` 会回溯成「1」而让护栏失效）。
     * 现状该文本**不得记账**（漏记）—— 相对「凭空记一笔 -1000」，这是方向正确的一侧。
     */
    @Test
    fun `regression guard a date following the payment verb is not mistaken for the amount`() {
        assertNull(
            parser.parse(alipay, "交易提醒", "成功付款 10月账单：1,280.00元的支出"),
            "月份「10」不得被当成金额 ⇒ 整条不记（QA-A4 已修）",
        )
    }

    // 上面两条漏记的三方对照证据见下一分组的同名用例。

    /**
     * 【修法护栏 QA-A3】修复 QA-A1 时**最容易踩的坑**：把豁免条件放宽成
     * 「正文含任一老动词（成功付款 / 付款成功…）就不拒绝」是不够的 ——
     * 那样下面两条**账单汇总文案**会因为沾了一个动词短语而被放行，
     * 金额再被兜底正则 `(\d+…)\s?元` 从「累计…元的支出」里捞出来 ⇒ **凭空记出一笔不存在的支出**。
     *
     * 正确的豁免条件必须是「**老动词 + 紧邻金额**」（与 amountPatterns[1] 同口径），
     * 而不是「出现动词即可」。这两条就是二者的分水岭。
     */
    @Test
    fun `FIX GUARD QA-A3 payment verb without an anchored amount must stay rejected`() {
        // 「成功付款」后面紧跟的是逗号、没有金额 ⇒ 没有任何证据说明这里有一笔已成交的交易。
        assertNull(
            parser.parse(alipay, "交易提醒", "成功付款，本月累计1,280.00元的支出。"),
            "★ 修法护栏：动词后无紧邻金额时不得豁免拒绝，" +
                "否则会把累计额 1,280.00 当成一笔真支出（仿真实测 -128000）",
        )
    }

    @Test
    fun `FIX GUARD QA-A3b payment sentence without amount stays rejected`() {
        assertNull(
            parser.parse(alipay, "交易提醒", "付款成功。你本月累计9.90元的支出"),
            "★ 修法护栏：同上，累计额 9.90 不得被兜底正则捞 -990）",
        )
    }

    /**
     * 挑战 3：口径存疑的一处 —— 「你有2笔共计45.00元的支出」。
     * 这类「合并提醒」按产品口径本就难说是不是一笔账，这里只做**现状记录**，不假装它必须是某一种结果。
     */
    @Test
    fun challenge_surveyOfAggregatedBearWordingRecordsBehaviour() {
        val body = "你有2笔共计45.00元的支出"
        val current = parser.parse(alipay, "交易提醒", body)
        // 现状记录：合并提醒不计账，与白名单收窄的口径一致，属可接受取舍。
        assertNull(current, "合并/批量提醒不计账是可接受的取舍（现状）")
    }

    // ================================================================= ⑤ 三方对照：白名单 vs 无收窄 vs 合取式

    /**
     * 「白名单改得更好」不等于「白名单没有代价」：只要存在「两个旧方案都记、唯独白名单不记」的文本，
     * 白名单就是有净损失的。这类文本必须显式列出来，而不是消失在平均值里。
     */
    @Test
    fun whitelistIsStrictlyWorseThanBothHistoricSchemesForSomeReformulations() {
        // ⚠️ 原用 QA-A1 的文本（「成功付款9.90元。本月累计…元的支出。」）。
        //   修复 QA-A1 后该文本三方已一致（-990），**不再是**「白名单净损失」的证据。
        //   改用 QA-A2 的文本：它同样满足「两个历史方案都记、唯独白名单不记」，
        //   且至今**未修复**（已知未覆盖句式，等真机样本），因此本用例的结论仍然成立、未被削弱。
        val body = "本次交易产生9.90元的支出，商户：肯德基"
        val baseline = noNarrowingParser.parse(alipay, "交易提醒", body)
        val conjunctive = conjunctiveParser.parse(alipay, "交易提醒", body)
        val current = parser.parse(alipay, "交易提醒", body)

        assertNotNull(baseline, "对照前提：无收窄方案记下了这笔真实交易")
        assertEquals(-990L, baseline.amountMinor)
        assertNotNull(conjunctive, "对照前提：被否决的合取式方案（9e4f476）也记下了它（正文无 账单/还款 词）")
        assertNull(
            current,
            "★ 唯独现行白名单把它丢了 —— 这是白名单方案相对两个历史方案的净损失（残余风险 QA-A2），" +
                "应作为已知取舍显式接受，而不是当成没发生过",
        )
    }

    /** 反向对照：白名单必须**确实优于**合取式方案，否则它就没有存在理由。 */
    @Test
    fun whitelistBeatsTheConjunctiveSchemeOnTheHeadlineRepaymentCase() {
        val body = "你有一笔1,280.00元的支出，请于10日还款"
        assertNull(
            conjunctiveParser.parse(alipay, "交易提醒", body),
            "对照前提：合取式会因为「还款」+「元的支出」同时出现而拒绝 —— 这正是它被否决的原因",
        )
        assertNotNull(
            parser.parse(alipay, "交易提醒", body),
            "白名单必须在这个主样本上胜出，否则改动白做了",
        )
    }

    /** 三方都能记的文本：说明三家共性，用于排除「白名单把一切都拒了」的过度怀疑。 */
    @Test
    fun allThreeSchemesAgreeOnTheRealDeviceSample() {
        val body = "你有一笔9.90元的支出，领2元小荷包支付红包。"
        listOf(noNarrowingParser, conjunctiveParser, parser).forEach { p ->
            val r = p.parse(alipay, "交易提醒", body)
            assertNotNull(r, "三个方案都必须记下真机样本")
            assertEquals(-990L, r.amountMinor)
        }
    }

    // ================================================================= 工具

    private fun assertTrueMsg(condition: Boolean, message: String) {
        kotlin.test.assertTrue(condition, message)
    }
}
