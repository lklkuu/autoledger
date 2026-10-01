package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * QA 独立复验（**必修③**）：Tier-1「同品牌不同门店」消歧。
 *
 * ## 背景（P0-2 反驳的被采纳版本）
 * `normalize()` 会抹掉括号门店后缀 ⇒「中石化(朝阳站)」与「中石化(望京站)」归一化后都是「中石化」，
 * 指纹相同 ⇒ 走 Tier-1；若两侧平台层级不同，`tierOneAllowsAutoMerge` 会放行 ⇒ **两笔真实消费被吞成一笔**。
 * 团队据此提出必修③：**两侧括号内容都非空且不同 ⇒ 判为不同门店，不得自动合并**（降级待确认）。
 *
 * ## 本文件钉死三种形态 + 括号全/半角边界
 * | 形态 | 例 | 期望 |
 * |---|---|---|
 * | ① 仅一侧带门店 | 「星巴克」↔「星巴克(国贸店)」 | **允许合并**（同一笔的两个渠道，一个带门店一个不带） |
 * | ② 两侧都带且**不同** | 「中石化(朝阳站)」↔「中石化(望京站)」 | **拒绝自动合并**（两笔真实消费） |
 * | ③ 两侧都带且**相同** | 「星巴克(国贸店)」↔「星巴克(国贸店)」 | **允许合并**（同店重复抓取） |
 * | ④ 全/半角括号 | 「星巴克(国贸店)」↔「星巴克（国贸店）」 | **允许合并**（仅括号宽度不同，同一门店） |
 *
 * ## 断言口径
 * 采用「**有效自动合并**」而非直接读 `canAutoMerge`：
 * `findDuplicates` 里若守卫把该候选整个剔除（`tierOneCandidate` 返回 null），也是"不会误合并"的正确落地。
 * 两种实现路径都通过，避免把实现细节写进断言。
 *
 * ⚠️ 形态② 与 ④（中石化全/半角）在必修③ 落地前**预期失败**（红 = 缺陷证据），落地后自然转绿。
 * 形态① ③ ④-星巴克 在实现前后都应绿（它们验证"别把该合的也拒了"）。
 */
class StorefrontDisambiguationAuditTest {

    private val anchor = 1_700_000_000_000L
    private val payment = "wechat" // PAYMENT
    private val bank = PlatformCatalog.BANK_ID // BANK

    private fun txn(
        id: String,
        platformId: String,
        sourceId: String,
        counterparty: String,
        occurredAt: Long = anchor,
        fingerprint: String,
    ) = LedgerTransaction(
        id = id,
        amountMinor = -30_000L,
        occurredAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = TxnStatus.CONFIRMED,
        fingerprint = fingerprint,
        platformId = platformId,
    )

    private fun resolver(vararg existing: LedgerTransaction) =
        LedgerDuplicateResolver(FakeLedgerRepository(existing.toList()))

    /**
     * 前置用：确认至少浮出一个候选，否则本用例的前提（"两条记录被视为同一笔的候选"）不成立。
     *
     * 刻意**不**限定 tier：必修③ 无论把消歧放在 `tierOneCandidate`（返回 null ⇒ 落到 Tier-2）
     * 还是 `canAutoMerge`（保留候选但拒绝），候选都应该存在（形态②/④ 落到 Tier-2 是 `REVIEW`）。
     * 只钉"候选存在"这一件事，就不会把某一种实现写法误判成失败。
     */
    private suspend fun assertSomeCandidateSurfaces(incoming: LedgerTransaction, repo: LedgerDuplicateResolver) {
        assertTrue(
            repo.findDuplicates(incoming).isNotEmpty(),
            "前置：抹括号后指纹相同（或 Tier-2 层级互补）⇒ 至少应浮出候选",
        )
    }

    /** 「有效自动合并」：只要存在任一候选可被 canAutoMerge 通过。 */
    private suspend fun effectivelyAutoMerges(incoming: LedgerTransaction, r: LedgerDuplicateResolver): Boolean =
        r.findDuplicates(incoming).any { r.canAutoMerge(incoming, it) }

    @Test
    fun `shape 1 - one side without storefront still merges (same transaction, two channels)`() = runBlocking<Unit> {
        // 商户名因为指纹相同必然落在同一指纹上；这里两侧层级不同（PAYMENT vs BANK）以走 Tier-1 放行路径。
        val wechat = txn("wx", payment, "notify_wechat", counterparty = "星巴克", fingerprint = "fp-starbucks")
        val bankSms = txn("bs", bank, "sms", counterparty = "星巴克(国贸店)", occurredAt = anchor + 20_000L, fingerprint = "fp-starbucks")
        val r = resolver(wechat)

        assertSomeCandidateSurfaces(bankSms, r)
        assertTrue(
            effectivelyAutoMerges(bankSms, r),
            "一侧带门店、一侧不带 ⇒ 还是同一笔的两个渠道，必须仍能自动合并（不能把正确能力一起拒掉）",
        )
    }

    /**
     * ⚠️ **预期失败**（必修③ 落地前）。
     * 两个**不同**加油站、同金额、3 分钟内 ⇒ 抹括号后指纹相同 ⇒ 层级不同（PAYMENT/BANK）⇒ 当前被静默合并。
     */
    @Test
    fun `shape 2 - both sides with different storefronts must not auto merge`() = runBlocking<Unit> {
        val wechat = txn("wx", payment, "notify_wechat", counterparty = "中石化(朝阳站)", fingerprint = "fp-sinopec")
        val bankSms = txn("bs", bank, "sms", counterparty = "中石化(望京站)", occurredAt = anchor + 40_000L, fingerprint = "fp-sinopec")
        val r = resolver(wechat)

        assertSomeCandidateSurfaces(bankSms, r)
        assertFalse(
            effectivelyAutoMerges(bankSms, r),
            "两侧门店不同 = 两笔真实消费，绝不能被抹括号 + 层级护栏放行而静默合并（吞账）",
        )
    }

    @Test
    fun `shape 3 - identical storefront on both sides still merges`() = runBlocking<Unit> {
        val wechat = txn("wx", payment, "notify_wechat", counterparty = "星巴克(国贸店)", fingerprint = "fp-starbucks2")
        val bankSms = txn("bs", bank, "sms", counterparty = "星巴克(国贸店)", occurredAt = anchor + 15_000L, fingerprint = "fp-starbucks2")
        val r = resolver(wechat)

        assertSomeCandidateSurfaces(bankSms, r)
        assertTrue(
            effectivelyAutoMerges(bankSms, r),
            "同一门店被两个渠道抓到 ⇒ 必须仍能自动合并（这是 Tier-1 的核心价值）",
        )
    }

    @Test
    fun `shape 4a - full-width vs half-width brackets with same content still merges`() = runBlocking<Unit> {
        val wechat = txn("wx", payment, "notify_wechat", counterparty = "星巴克(国贸店)", fingerprint = "fp-starbucks3")
        val bankSms = txn("bs", bank, "sms", counterparty = "星巴克（国贸店）", occurredAt = anchor + 10_000L, fingerprint = "fp-starbucks3")
        val r = resolver(wechat)

        assertSomeCandidateSurfaces(bankSms, r)
        assertTrue(
            effectivelyAutoMerges(bankSms, r),
            "仅括号全/半角不同（门店内容一致）⇒ 仍是同一门店，必须能自动合并",
        )
    }

    /**
     * ⚠️ **预期失败**（与形态②同源，但用全/半角混用放大边界）。
     * 「中石化(朝阳站)」（半角）↔「中石化（望京站）」（全角）—— 门店不同，必须拒绝。
     */
    @Test
    fun `shape 4b - full-width vs half-width brackets with different content must not merge`() = runBlocking<Unit> {
        val wechat = txn("wx", payment, "notify_wechat", counterparty = "中石化(朝阳站)", fingerprint = "fp-sinopec2")
        val bankSms = txn("bs", bank, "sms", counterparty = "中石化（望京站）", occurredAt = anchor + 50_000L, fingerprint = "fp-sinopec2")
        val r = resolver(wechat)

        assertSomeCandidateSurfaces(bankSms, r)
        assertFalse(
            effectivelyAutoMerges(bankSms, r),
            "全/半角括号只是书写差异，两侧门店内容不同 ⇒ 仍是两笔真实消费，不得静默合并",
        )
    }
}
