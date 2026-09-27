package com.autoledger.feature.dedup

import com.autoledger.core.model.Account
import com.autoledger.core.model.AccountKind
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferKind
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 内部划转 / 退款识别 —— 纯 JVM 单元测试。
 *
 * 「把银行卡充值微信算成消费，月度支出就凭空翻倍」是自动记账最伤信任的错误，
 * 这里的每一条规则都必须有对应用例。
 */
class TransferRulesTest {

    private val anchor = 1_700_000_000_000L

    private fun ctx(
        counterparty: String = "",
        note: String? = null,
        amountMinor: Long = -1_000L,
        accounts: List<Account> = emptyList(),
    ) = TransferContext(
        counterparty = counterparty,
        note = note,
        amountMinor = amountMinor,
        occurredAtMillis = anchor,
        sourceId = "notify",
        accounts = accounts,
    )

    // ------------------------------------------------------------ 关键词命中

    @Test
    fun `top up keywords map to TOPUP`() {
        assertEquals(TransferKind.TOPUP, TransferRulePack.matchKindOf(ctx("微信零钱充值"))!!.first)
    }

    @Test
    fun `withdraw keywords map to WITHDRAW`() {
        assertEquals(TransferKind.WITHDRAW, TransferRulePack.matchKindOf(ctx("余额提现"))!!.first)
    }

    @Test
    fun `repayment keywords map to CREDIT_REPAYMENT`() {
        assertEquals(TransferKind.CREDIT_REPAYMENT, TransferRulePack.matchKindOf(ctx("信用卡还款"))!!.first)
    }

    @Test
    fun `self transfer keywords map to SELF_TRANSFER`() {
        assertEquals(TransferKind.SELF_TRANSFER, TransferRulePack.matchKindOf(ctx("账户互转"))!!.first)
    }

    @Test
    fun `refund keywords map to REFUND`() {
        assertEquals(TransferKind.REFUND, TransferRulePack.matchKindOf(ctx("已退款到银行卡"))!!.first)
        assertEquals(TransferKind.REFUND, TransferRulePack.matchKindOf(ctx("原路退回"))!!.first)
    }

    @Test
    fun `refund wins over top up when both keywords appear`() {
        // 「零钱充值」被撤销 -> 必须判 REFUND 而不是 TOPUP（优先级见 TransferRules.kt:33）
        val verdict = TransferRulePack.matchKindOf(ctx(counterparty = "零钱充值", note = "已退款"))
        assertEquals(TransferKind.REFUND, verdict!!.first)
        assertTrue(verdict.second.contains("退款"))
    }

    @Test
    fun `wallet merchant hints map to SELF_TRANSFER`() {
        assertEquals(TransferKind.SELF_TRANSFER, TransferRulePack.matchKindOf(ctx("余额宝"))!!.first)
        assertEquals(TransferKind.SELF_TRANSFER, TransferRulePack.matchKindOf(ctx("微信零钱"))!!.first)
    }

    @Test
    fun `ordinary merchant returns no verdict`() {
        assertNull(TransferRulePack.matchKindOf(ctx("星巴克")))
        assertNull(TransferRulePack.matchKindOf(ctx(counterparty = "", note = null)))
    }

    @Test
    fun `note participates in matching`() {
        assertNotNull(TransferRulePack.matchKindOf(ctx(counterparty = "财付通", note = "零钱充值成功")))
    }

    // ------------------------------------------------------------ 自有账户识别

    private val cmb = Account(
        id = "acc_cmb",
        name = "招商银行储蓄卡",
        kind = AccountKind.BANK_CARD,
        identifierHints = listOf("6688", "13800138000"),
    )

    @Test
    fun `own account matched by last four digits`() {
        assertEquals("acc_cmb", TransferRulePack.ownAccount(ctx("尾号6688 消费", accounts = listOf(cmb)))?.id)
    }

    @Test
    fun `own account matched by phone hint in note`() {
        assertEquals(
            "acc_cmb",
            TransferRulePack.ownAccount(ctx(counterparty = "转账", note = "13800138000", accounts = listOf(cmb)))?.id,
        )
    }

    @Test
    fun `own account matched by account name`() {
        assertEquals(
            "acc_cmb",
            TransferRulePack.ownAccount(ctx(counterparty = "招商银行储蓄卡扣款", accounts = listOf(cmb)))?.id,
        )
    }

    @Test
    fun `short generic account name does not trigger own account match`() {
        // 2 字短名（现金/微信/钱包）不该靠"包含"命中，否则整渠道被误判成内部划转。
        val generic = Account(
            id = "acc_cash", name = "现金", kind = AccountKind.CASH, identifierHints = emptyList(),
        )
        assertNull(TransferRulePack.ownAccount(ctx("现金充值", accounts = listOf(generic))))
    }

    @Test
    fun `hints shorter than four chars are ignored`() {
        val weak = cmb.copy(identifierHints = listOf("668"))
        assertNull(TransferRulePack.ownAccount(ctx("尾号668", accounts = listOf(weak))))
    }

    @Test
    fun `no account list means no own account`() {
        assertNull(TransferRulePack.ownAccount(ctx("尾号6688", accounts = emptyList())))
    }

    // ------------------------------------------------------------ DefaultTransferDetector

    @Test
    fun `detector trusts own account evidence with 0_95`() = runBlocking {
        val v = DefaultTransferDetector().detect(ctx(counterparty = "尾号6688", accounts = listOf(cmb)))
        assertEquals(TransferKind.SELF_TRANSFER, v.kind)
        assertEquals(0.95f, v.confidence)
        assertEquals("acc_cmb", v.matchedAccountId)
    }

    @Test
    fun `detector falls back to keywords with 0_85`() = runBlocking {
        val v = DefaultTransferDetector().detect(ctx(counterparty = "零钱充值"))
        assertEquals(TransferKind.TOPUP, v.kind)
        assertEquals(0.85f, v.confidence)
        assertNull(v.matchedAccountId)
    }

    @Test
    fun `detector returns none for ordinary spend`() = runBlocking {
        val v = DefaultTransferDetector().detect(ctx(counterparty = "星巴克"))
        assertEquals(TransferKind.NONE, v.kind)
        assertEquals(0f, v.confidence)
    }

    // ------------------------------------------------------------ 成对配账

    private fun t(
        id: String,
        amountMinor: Long,
        at: Long = anchor,
        type: TxnType = TxnType.EXPENSE,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = at,
        type = type,
        counterparty = "",
        sourceId = "notify",
        sourceRef = id,
    )

    @Test
    fun `pairs opposite amounts within window`() {
        val out = t("out", -100_000)
        val inn = t("in", 100_000, type = TxnType.INCOME)
        val result = TransferPairMatcher.match(out, listOf(inn))
        assertNotNull(result)
        assertEquals("out", result.outId)
        assertEquals("in", result.inId)
        assertTrue(result.groupId.startsWith("tr-"))
    }

    @Test
    fun `pairing works from the inflow side too`() {
        val out = t("out", -100_000)
        val inn = t("in", 100_000, type = TxnType.INCOME)
        val result = TransferPairMatcher.match(inn, listOf(out))
        assertNotNull(result)
        assertEquals("out", result.outId)
        assertEquals("in", result.inId)
    }

    @Test
    fun `no pairing when amount does not mirror`() {
        val out = t("out", -100_000)
        assertNull(TransferPairMatcher.match(out, listOf(t("other", 99_900, type = TxnType.INCOME))))
    }

    @Test
    fun `no pairing outside ten minute window`() {
        val out = t("out", -100_000)
        val late = t("in", 100_000, at = anchor + 11 * 60 * 1000L, type = TxnType.INCOME)
        assertNull(TransferPairMatcher.match(out, listOf(late)))
    }

    @Test
    fun `never pairs a transaction with itself`() {
        val out = t("out", -100_000)
        assertNull(TransferPairMatcher.match(out, listOf(out)))
    }

    @Test
    fun `pairing skips expense candidates`() {
        // 另一笔支出即便金额相反也不应配对（TransferRules.kt:95 要求 other.type != EXPENSE）
        val out = t("out", -100_000)
        assertNull(TransferPairMatcher.match(out, listOf(t("x", 100_000, type = TxnType.EXPENSE))))
    }
}
