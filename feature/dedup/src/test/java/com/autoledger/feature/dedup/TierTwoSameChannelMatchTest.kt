package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MatchTier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 设计文档 §10-⑨：**Tier-2 不再要求「必须跨渠道」**。
 *
 * 原来 Tier-2 的入口有一条 `if (other.sourceId == txn.sourceId) return null`，
 * 于是「一笔支付触发多条**同渠道**通知」（数币 App / 云闪付 App / 银行 App 都走 `notify`）
 * 互相连候选都不是 ⇒ 静默漏合并（用户看到 4 条 → 记成 2/3 条）。
 *
 * 放宽后由**层级护栏**兜底误合并，本文件把两侧都钉死：
 *  - 应合并 / 应浮出的同渠道对 ⇒ 成为候选（`crossSource` 如实标记为 false）；
 *  - 不应合并的同渠道对（两个支付通道、两个下单平台、两条官方数字通道）⇒ 连候选都不是。
 *
 * ⚠️ 判定表以 [complementaryVerdict] 为唯一真源（本次已加入 `E_WALLET` 层），本文件只覆盖
 * 「**放宽了来源约束**」这一个正交维度，不重复钉层级表（那由 [TierTwoVerdictMatrixTest] 负责）。
 */
class TierTwoSameChannelMatchTest {

    private val anchor = 1_700_000_000_000L

    private fun txn(
        id: String,
        platformId: String,
        sourceId: String,
        counterparty: String,
        occurredAt: Long = anchor,
        amountMinor: Long = -1_200L,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = TxnStatus.RAW,
        // 指纹各不相同（商户名不同）⇒ 强制走 Tier-2，排除 Tier-1 干扰。
        fingerprint = "fp-$id",
        platformId = platformId,
    )

    private fun resolver(vararg existing: LedgerTransaction) =
        LedgerDuplicateResolver(FakeLedgerRepository(existing.toList()))

    @Test
    fun `same-channel order plus bank is a candidate and auto merges`() = runBlocking<Unit> {
        // 美团 App 通知 与 银行 App 通知 都是 notify 渠道、商户名不同（一个是美团外卖、一个是某银行）。
        val existing = txn("mt", "meituan", "notify", "美团外卖")
        val incoming = txn("bk", PlatformCatalog.BANK_ID, "notify", "某银行", occurredAt = anchor + 5_000L)
        val r = resolver(existing)

        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "同渠道但层级互补 ⇒ 必须成为 Tier-2 候选（旧实现会漏掉）")
        assertEquals(MatchTier.COMPLEMENTARY, dups.first().tier)
        assertFalse(dups.first().crossSource, "两者同渠道 ⇒ crossSource 必须如实为 false")
        assertTrue(r.canAutoMerge(incoming, dups.first()), "ORDER ↔ BANK 层级互补 ⇒ 允许自动合并")
    }

    @Test
    fun `same-channel order plus e-wallet is a candidate and auto merges`() = runBlocking<Unit> {
        // 数币 App 通知 与 下单平台通知：都是 notify、商户名不同。
        val existing = txn("rmb", "digital_rmb", "notify", "数字人民币钱包")
        val incoming = txn("mt", "meituan", "notify", "美团外卖", occurredAt = anchor + 3_000L)
        val r = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "同渠道 + 层级互补（ORDER ↔ E_WALLET）⇒ 候选")
        assertTrue(r.canAutoMerge(incoming, dups.first()))
    }

    /**
     * 截图场景的核心一格：**同渠道**的数币通知 与 **银行卡**通知 —— 必须**浮出候选**（REVIEW），
     * 但**不得静默合并**（用户拍板：「宁可保守，也不静默吞掉真实消费」）。
     */
    @Test
    fun `same-channel e-wallet and bank is a candidate but never auto merged`() = runBlocking<Unit> {
        val existing = txn("rmb", "digital_rmb", "notify", "数字人民币钱包")
        val incoming = txn("bk", PlatformCatalog.BANK_ID, "notify", "某银行", occurredAt = anchor + 3_000L)
        val r = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "E_WALLET ↔ BANK ⇒ 必须浮出候选交用户（不能连候选都不是）")
        assertFalse(r.canAutoMerge(incoming, dups.first()), "E_WALLET ↔ BANK ⇒ 用户拍板：不自动合并")
    }

    @Test
    fun `same-channel same-id bank twins are candidates but never auto merged`() = runBlocking<Unit> {
        // 反向护栏：同一条 bank 通道的两条（可能是两张卡各扣一笔）⇒ 候选但不自动合并。
        val existing = txn("b1", PlatformCatalog.BANK_ID, "notify", "某银行")
        val incoming = txn("b2", PlatformCatalog.BANK_ID, "notify", "另一银行", occurredAt = anchor + 3_000L)
        val r = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "同层级同通道 ⇒ 仍是候选（交用户核对）")
        assertFalse(r.canAutoMerge(incoming, dups.first()), "同一条通道 ⇒ 必须交用户，不得静默合并")
    }

    @Test
    fun `same-channel two payment channels are not even candidates`() = runBlocking<Unit> {
        // 不变量：同渠道的微信 + 支付宝仍是两笔真实消费（PAYMENT↔PAYMENT 不同 id ⇒ REJECT）。
        val existing = txn("wx", "wechat", "notify", "星巴克")
        val incoming = txn("ali", "alipay", "notify", "星巴克", occurredAt = anchor + 3_000L)
        val r = resolver(existing)
        assertTrue(r.findDuplicates(incoming).isEmpty(), "两个支付通道 ⇒ 连候选都不是")
    }

    @Test
    fun `same-channel two order platforms are not even candidates`() = runBlocking<Unit> {
        val existing = txn("mt", "meituan", "notify", "某商户")
        val incoming = txn("tb", "taobao", "notify", "某商户", occurredAt = anchor + 3_000L)
        val r = resolver(existing)
        assertTrue(r.findDuplicates(incoming).isEmpty(), "两个下单场所 ⇒ 连候选都不是")
    }

    @Test
    fun `same-channel two official digital channels are not even candidates`() = runBlocking<Unit> {
        val existing = txn("rmb", "digital_rmb", "notify", "某商户")
        val incoming = txn("up", "unionpay", "notify", "某商户", occurredAt = anchor + 3_000L)
        val r = resolver(existing)
        assertTrue(r.findDuplicates(incoming).isEmpty(), "数币 vs 云闪付（同层级不同 id）⇒ 连候选都不是")
    }

    @Test
    fun `relaxation does not allow cross-level auto merge to be denied`() = runBlocking<Unit> {
        // 回归：ORDER↔BANK（跨层级）在同一渠道下也必须照旧自动合并。
        val existing = txn("mt", "meituan", "notify", "美团外卖")
        val incoming = txn("bk", PlatformCatalog.BANK_ID, "notify", "某银行", occurredAt = anchor + 2_000L)
        val r = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size)
        assertTrue(r.canAutoMerge(incoming, dups.first()), "ORDER↔BANK 层级互补 ⇒ 自动合并")
    }
}
