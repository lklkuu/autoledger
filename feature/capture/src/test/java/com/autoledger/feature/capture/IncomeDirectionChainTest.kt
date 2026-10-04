package com.autoledger.feature.capture

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType
import com.autoledger.feature.capture.notify.DefaultNotificationRules
import com.autoledger.feature.capture.notify.NotificationParser
import com.autoledger.feature.capture.notify.toRawEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * 「命中规则但金额取不到」这条此前**无人覆盖**的收入路径，从解析一路串到类型判定。
 *
 * 修复前：方向（`ParseResult.direction`）到不了信封（`RawEnvelope` 无该字段），
 * 金额又缺失 ⇒ `resolveInitialType` 只能兜底成 EXPENSE ⇒ **一笔真实收入被静默记成支出**。
 *
 * 本文件把 `NotificationParser` + `toRawEnvelope` + `resolveInitialTypeWithRefiner`
 * 三段真实组件串起来（`typeRefiner = null` ⇒ 不接线、纯本地），逐段钉死方向不再丢失。
 *
 * 为什么放在 `feature:capture` 而非走完整 `IngestPipeline`：`IngestPipeline` 需要一个
 * `DuplicateResolver`，而它在 `feature:dedup` —— `feature:capture` 连测试期都没有该依赖
 * （`build.gradle.kts` 只依赖 `core:model` + `core:crypto`）。完整管线路径见 app 模块的
 * `IncomeDirectionIngestTest`。
 */
class IncomeDirectionChainTest {

    private val parser = NotificationParser()

    /** 把「解析 → 信封 → 判定链」串起来跑一遍，返回最终账本类型。 */
    private fun chain(packageName: String, title: String, body: String): Pair<NotificationParser.ParseResult, TxnType> {
        val parsed = assertNotNull(parser.parse(packageName, title, body), "文案应命中某条规则")
        val env = toRawEnvelope(
            sourceId = if (packageName.startsWith("sms")) "sms" else "notify",
            sourceRef = "ref:test",
            occurredAtMillis = 1_000L,
            rawText = "$title\n$body",
            packageName = packageName,
            parsed = parsed,
        )
        val type = runBlocking {
            resolveInitialTypeWithRefiner(
                explicitType = env.explicitType,
                amount = env.amountHint,
                directionHint = env.directionHint,
                rawText = env.rawText,
                typeRefiner = null,
            )
        }
        return parsed to type
    }

    // ---------------- C1'：短信收入规则、金额取不到 ⇒ 收入（修复前是支出） ----------------

    @Test
    fun `a bank income sms whose amount cannot be parsed is still recorded as income`() {
        val (parsed, type) = chain("sms:inbox", "95588", "【工商银行】您尾号1234账户工资已转入。")

        // 前置条件（实测确认）：命中收入规则、金额取不到、方向为流入。
        assertEquals("sms_bank_in", parsed.ruleId, "应命中银行收入短信规则")
        assertNull(parsed.amountMinor, "该文案三条金额正则都取不到数字 ⇒ amountMinor 应为 null")
        assertEquals(Direction.IN, parsed.direction, "规则声明的方向应为流入")

        // 链路结论：修复后为收入（修复前因方向丢失被兜底成 EXPENSE）。
        assertEquals(TxnType.INCOME, type, "★ 核心：金额缺失但方向已知为流入 ⇒ 必须是收入")
    }

    // ---------------- C3：微信收款规则、金额取不到 ⇒ 收入 ----------------

    @Test
    fun `a wechat receive notification whose amount cannot be parsed is income`() {
        val (parsed, type) = chain(DefaultNotificationRules.PKG_WECHAT, "服务通知", "收款到账")

        assertEquals("wechat_receive", parsed.ruleId, "「收款到账」应被 wechat_receive 认领（wechat_pay 会拒绝它）")
        assertNull(parsed.amountMinor)
        assertEquals(Direction.IN, parsed.direction)

        assertEquals(TxnType.INCOME, type, "微信收款金额缺失时仍应是收入")
    }

    // ---------------- C4：退款规则在金额取不到时仍为 REFUND（explicitType 通道未被破坏） ----------------

    @Test
    fun `a wechat refund without amount stays REFUND via the explicit type channel`() {
        val (parsed, type) = chain(DefaultNotificationRules.PKG_WECHAT, "微信支付", "已退款")

        assertEquals("refund_wechat", parsed.ruleId)
        assertNull(parsed.amountMinor, "「已退款」后无数字 ⇒ 金额缺失")
        assertEquals(TxnType.REFUND, parsed.explicitType, "退款规则必须显式下发 REFUND")

        // 方向虽为流入，但 explicitType 优先级最高 ⇒ 仍是 REFUND，绝不被方向推导成收入。
        assertEquals(Direction.IN, parsed.direction)
        assertEquals(TxnType.REFUND, type, "显式类型最高优先，方向不得把退款推成收入")
    }

    @Test
    fun `a generic refund without amount stays REFUND`() {
        val (parsed, type) = chain("sms:inbox", "95588", "原路退回")

        assertEquals("refund_generic", parsed.ruleId)
        assertNull(parsed.amountMinor)
        assertEquals(TxnType.REFUND, parsed.explicitType)
        assertEquals(TxnType.REFUND, type)
    }

    // ---------------- C5：真实样本回归（1.1.5 修过的形态，必须不退化） ----------------

    @Test
    fun `the real sample with a parenthesised note keeps a correct income and amount`() {
        val (parsed, type) = chain("sms:inbox", "95588", "工商银行收入(整整到期)5,055元")

        assertEquals("sms_bank_in", parsed.ruleId, "「收入…5,055元」应命中收入规则")
        assertEquals(505_500L, parsed.amountMinor, "千分位 5,055 应解析为 505500 分")
        assertEquals(Direction.IN, parsed.direction)
        assertEquals(TxnType.INCOME, type, "金额存在时按金额符号判成收入（本次修复不得让该样本退化）")
    }
}
