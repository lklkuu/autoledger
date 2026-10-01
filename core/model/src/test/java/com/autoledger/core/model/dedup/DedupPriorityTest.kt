package com.autoledger.core.model.dedup

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformPriority
import com.autoledger.core.model.platform.priorityOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DedupPriority.choosePrimary] 的裁决护栏。
 *
 * 这条规则决定「合并后账单里留下哪条」——写错就是把用户的记录悄悄换掉，
 * 或者在用户手动指定过平台之后被自动流程覆盖（[com.autoledger.core.model.platform.PlatformSource.USER]
 * 存在的全部意义就是防这件事）。因此每条规则都配一个**反例**。
 */
class DedupPriorityTest {

    private val order = PlatformPriority.ORDER.rank
    private val payment = PlatformPriority.PAYMENT.rank
    private val bank = PlatformPriority.BANK.rank
    private val none = PlatformPriority.NONE.rank

    @Test
    fun `a higher ranked incoming record becomes the primary`() {
        // 需求场景：先到的是银行短信(BANK)，后到的是美团通知(ORDER) ⇒ 美团当主记录
        val choice = DedupPriority.choosePrimary(
            incomingId = "meituan-txn", incomingRank = order, incomingIsUser = false,
            existingId = "bank-txn", existingRank = bank, existingIsUser = false,
        )
        assertEquals("meituan-txn", choice.primaryId)
        assertEquals("bank-txn", choice.mergedId)
    }

    @Test
    fun `a higher ranked existing record keeps the primary`() {
        // 反过来：美团先到、银行后到 ⇒ 仍然美团留下（不能因为"后来者"就易主）
        val choice = DedupPriority.choosePrimary(
            incomingId = "bank-txn", incomingRank = bank, incomingIsUser = false,
            existingId = "meituan-txn", existingRank = order, existingIsUser = false,
        )
        assertEquals("meituan-txn", choice.primaryId, "层级更高的已存在记录必须继续当主记录")
        assertEquals("bank-txn", choice.mergedId)
    }

    @Test
    fun `same rank keeps the existing record`() {
        // D12：微信 + 支付宝同金额同时间 —— 层级相同 ⇒ 保留 existing，
        // 避免主记录反复易主导致 UI 上「这条归到哪个平台」每次刷新都跳。
        val choice = DedupPriority.choosePrimary(
            incomingId = "alipay-txn", incomingRank = payment, incomingIsUser = false,
            existingId = "wechat-txn", existingRank = payment, existingIsUser = false,
        )
        assertEquals("wechat-txn", choice.primaryId)
        assertEquals("alipay-txn", choice.mergedId)
    }

    @Test
    fun `an existing user chosen platform is never overwritten by a higher rank`() {
        // D11 / R4：用户把某条手动改成「微信」，随后美团通知进来 ⇒
        // 用户权威豁免，exising 继续当主记录，**不因层级低而被吞掉**。
        val choice = DedupPriority.choosePrimary(
            incomingId = "meituan-txn", incomingRank = order, incomingIsUser = false,
            existingId = "user-picked", existingRank = payment, existingIsUser = true,
        )
        assertEquals("user-picked", choice.primaryId, "用户指定过的记录必须保留")
        assertEquals("meituan-txn", choice.mergedId)
    }

    @Test
    fun `an incoming user chosen platform wins over a higher ranked existing`() {
        // 对称的一侧：用户刚在新记录上指定了平台（例如手动把一条改到「美团」）⇒ 它当主记录。
        val choice = DedupPriority.choosePrimary(
            incomingId = "user-picked", incomingRank = payment, incomingIsUser = true,
            existingId = "meituan-txn", existingRank = order, existingIsUser = false,
        )
        assertEquals("user-picked", choice.primaryId)
        assertEquals("meituan-txn", choice.mergedId)
    }

    @Test
    fun `when both sides are user chosen the existing record stays`() {
        // 双方都是 USER：没有"更权威"的一方 ⇒ 不改写历史（R2）
        val choice = DedupPriority.choosePrimary(
            incomingId = "incoming-user", incomingRank = order, incomingIsUser = true,
            existingId = "existing-user", existingRank = bank, existingIsUser = true,
        )
        assertEquals("existing-user", choice.primaryId)
        assertEquals("incoming-user", choice.mergedId)
    }

    @Test
    fun `rank is the only tie breaker when neither side is user chosen`() {
        // 覆盖全部 4×4 组合的层级关系，防止"某个组合被写反"
        val ranks = listOf(none, bank, payment, order)
        for (incoming in ranks) {
            for (existing in ranks) {
                val choice = DedupPriority.choosePrimary(
                    incomingId = "in", incomingRank = incoming, incomingIsUser = false,
                    existingId = "ex", existingRank = existing, existingIsUser = false,
                )
                val expected = if (incoming > existing) "in" else "ex"
                assertEquals(expected, choice.primaryId, "incoming=$incoming vs existing=$existing")
                assertEquals(if (expected == "in") "ex" else "in", choice.mergedId)
            }
        }
    }

    @Test
    fun `the choice is always a partition of the two ids`() {
        // 结构性不变量：无论怎么裁决，两条 ID 必须一条留下、一条被吸收，不得重复或遗漏。
        val choice = DedupPriority.choosePrimary("a", 1, false, "b", 2, true)
        assertEquals(setOf("a", "b"), setOf(choice.primaryId, choice.mergedId))
        assertTrue(choice.primaryId != choice.mergedId, "不能在没合并的情况下把两条都当主记录")
        assertTrue(choice.reason.isNotBlank(), "裁决必须给出可排查的依据")
    }

    @Test
    fun `unknown platform ranks below a bank card`() {
        // 这条钉死 Tier-2 护栏里「ORDER ↔ NONE 可合并」的前提：
        // unknown 的 rank 必须真的最低，否则「美团通知 + 未识别的银行短信」会被判成层级更高的一方。
        assertEquals(none, priorityOf(PlatformCatalog.UNKNOWN_ID).rank)
        assertEquals(bank, priorityOf(PlatformCatalog.BANK_ID).rank)
        assertTrue(priorityOf(PlatformCatalog.UNKNOWN_ID).rank < priorityOf(PlatformCatalog.BANK_ID).rank)
    }

    @Test
    fun `a meituan notice outranks both payment channels and the card`() {
        // 需求原文「美团 > 微信/支付宝 > 银行卡」的端到端断言（走真实目录，不写死数字）
        val meituan = priorityOf("meituan").rank
        val wechat = priorityOf("wechat").rank
        val alipay = priorityOf("alipay").rank
        val bankCard = priorityOf(PlatformCatalog.BANK_ID).rank
        assertTrue(meituan > wechat && meituan > alipay, "下单平台必须高于支付通道")
        assertTrue(wechat > bankCard && alipay > bankCard, "支付通道必须高于银行卡")
    }
}
