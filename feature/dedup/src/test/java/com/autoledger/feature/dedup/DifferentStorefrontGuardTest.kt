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
 * 必修③【P0-2】：**不同门店护栏**（`branchSuffixesConflict`）。
 *
 * 反例：`normalize()` 抹括号 ⇒「中石化(朝阳站)」与「中石化(海淀站)」指纹相同；
 * 若两侧平台**层级不同**（微信通知 = `PAYMENT`、银行短信 = `BANK`），
 * [tierOneAllowsAutoMerge] 会放行 ⇒ 两笔真实消费被静默合并。
 *
 * 本文件两层覆盖：
 *  1. **纯函数真值表** —— 逐格钉死判据（两侧都带且不同才算冲突）；
 *  2. **端到端** —— 走 `findDuplicates → canAutoMerge` 完整链路，证明候选仍浮出（降级待确认）、
 *     且**不误伤** normalize 抹括号要保住的能力（一侧带门店、一侧不带 ⇒ 仍能自动合并）。
 */
class DifferentStorefrontGuardTest {

    private val anchor = 1_700_000_000_000L
    private val wechat = "wechat"
    private val alipay = "alipay"
    private val bank = PlatformCatalog.BANK_ID
    private val none = PlatformCatalog.UNKNOWN_ID

    private fun txn(
        id: String,
        platformId: String,
        sourceId: String,
        occurredAt: Long = anchor,
        amountMinor: Long = -30_000L,
        counterparty: String = "中石化(朝阳站)",
        status: TxnStatus = TxnStatus.CONFIRMED,
        fingerprint: String = "",
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = if (amountMinor < 0) TxnType.EXPENSE else TxnType.INCOME,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = fingerprint,
        platformId = platformId,
    )

    private fun fpOf(amountMinor: Long, counterparty: String): String =
        LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("x", none, "notify", amountMinor = amountMinor, counterparty = counterparty))

    private fun resolver(vararg existing: LedgerTransaction) =
        LedgerDuplicateResolver(FakeLedgerRepository(existing.toList()))

    // ------------------------------------------------------------------ 纯函数真值表

    @Test
    fun `two different storefronts of the same brand conflict`() {
        assertTrue(branchSuffixesConflict("中石化(朝阳站)", "中石化(海淀站)"), "同品牌不同门店 ⇒ 冲突")
        assertTrue(branchSuffixesConflict("星巴克(国贸店)", "星巴克(南京西路店)"))
        // 对称：与参数顺序无关
        assertTrue(branchSuffixesConflict("中石化(海淀站)", "中石化(朝阳站)"))
    }

    @Test
    fun `an identical storefront is not a conflict`() {
        assertFalse(branchSuffixesConflict("星巴克(国贸店)", "星巴克(国贸店)"), "同一门店 ⇒ 不冲突")
    }

    @Test
    fun `a missing storefront on either side is not a conflict`() {
        // 这是 normalize() 抹括号要保住的核心能力：银行短信「星巴克(国贸店)」↔ 微信通知「星巴克」。
        assertFalse(branchSuffixesConflict("星巴克(国贸店)", "星巴克"), "一侧没带门店 ⇒ 不冲突")
        assertFalse(branchSuffixesConflict("星巴克", "星巴克(国贸店)"), "反向同样不冲突")
        assertFalse(branchSuffixesConflict("星巴克(国贸店)", ""), "空串 ⇒ 无门店信息 ⇒ 不冲突")
        assertFalse(branchSuffixesConflict("", ""))
    }

    @Test
    fun `no parentheses at all is not a conflict`() {
        assertFalse(branchSuffixesConflict("星巴克", "瑞幸"), "没有括号内容 ⇒ 无从判定门店")
        assertFalse(branchSuffixesConflict("星巴克", "星巴克"))
    }

    @Test
    fun `full width parentheses and casing are normalised before comparing`() {
        // 半/全角括号、大小写差异都不得造成「假冲突」
        assertFalse(branchSuffixesConflict("星巴克(国贸店)", "星巴克（国贸店）"), "全角/半角括号等价")
        assertFalse(branchSuffixesConflict("Starbucks(Shanghai)", "Starbucks(SHANGHAI)"), "门店名忽略大小写")
        assertTrue(branchSuffixesConflict("Starbucks(Shanghai)", "Starbucks(Beijing)"), "不同城市门店 ⇒ 冲突")
    }

    @Test
    fun `multiple parenthetical groups are compared order insensitively`() {
        assertFalse(branchSuffixesConflict("星巴克（国贸店）(2F)", "星巴克(2F)(国贸店)"), "多段门店信息整体相等（与顺序无关）")
        assertTrue(branchSuffixesConflict("星巴克（国贸店）(2F)", "星巴克（国贸店）(3F)"), "楼层不同也是不同门店")
    }

    // ------------------------------------------------------------------ 端到端（走 findDuplicates + canAutoMerge）

    @Test
    fun `different storefronts across tiers must not be silently merged`() = runBlocking<Unit> {
        // QA 的反例：微信支付通知「中石化(朝阳站)」(PAYMENT) + 银行 POS 短信「中石化(海淀站)」(BANK)。
        val incoming = txn("a", wechat, "notify_wechat", counterparty = "中石化(朝阳站)")
        val existing = txn("b", bank, "sms", occurredAt = anchor + 60_000L, counterparty = "中石化(海淀站)")
        val fp = fpOf(-30_000L, "中石化(朝阳站)")
        assertEquals(fp, fpOf(-30_000L, "中石化(海淀站)"), "抹括号 ⇒ 两门店指纹必然相同（前置）")

        val incomingFp = incoming.copy(fingerprint = fp)
        val r = resolver(existing.copy(fingerprint = fp))
        val dups = r.findDuplicates(incomingFp)

        assertEquals(1, dups.size, "指纹相同 ⇒ 仍是 Tier-1 候选（降级待确认，交由用户核对）")
        assertEquals(MatchTier.FINGERPRINT, dups.single().tier)
        assertFalse(
            r.canAutoMerge(incomingFp, dups.single()),
            "两个不同加油站的两笔真实消费，绝不能因抹括号 + 跨层级放行而被静默合并",
        )
    }

    @Test
    fun `the same storefront across tiers still auto merges`() = runBlocking<Unit> {
        // 反向护栏：门店也相同（都「中石化(朝阳站)」）⇒ 不冲突 ⇒ 层级互补仍应自动合并。
        val incoming = txn("a", wechat, "notify_wechat", counterparty = "中石化(朝阳站)")
        val existing = txn("b", bank, "sms", occurredAt = anchor + 60_000L, counterparty = "中石化(朝阳站)")
        val fp = fpOf(-30_000L, "中石化(朝阳站)")

        val incomingFp = incoming.copy(fingerprint = fp)
        val r = resolver(existing.copy(fingerprint = fp))
        val dups = r.findDuplicates(incomingFp)

        assertEquals(1, dups.size)
        assertTrue(
            r.canAutoMerge(incomingFp, dups.single()),
            "门店相同 + 层级互补（PAYMENT↔BANK）⇒ 允许自动合并（护栏不得误伤）",
        )
    }

    @Test
    fun `storefront on only one side still auto merges so normalize keeps its value`() = runBlocking<Unit> {
        // 证明抹括号的核心价值未被护栏破坏：银行短信带门店、微信通知不带 ⇒ 归一化后指纹相同 ⇒ 仍合并。
        val incoming = txn("a", wechat, "notify_wechat", counterparty = "星巴克(国贸店)")
        val existing = txn("b", bank, "sms", occurredAt = anchor + 60_000L, counterparty = "星巴克")
        val fp = fpOf(-30_000L, "星巴克")
        assertEquals(fp, fpOf(-30_000L, "星巴克(国贸店)"), "抹括号让一带一不带仍同指纹（Tier-1 的核心能力）")

        val incomingFp = incoming.copy(fingerprint = fp)
        val r = resolver(existing.copy(fingerprint = fp))
        val dups = r.findDuplicates(incomingFp)

        assertEquals(1, dups.size)
        assertTrue(
            r.canAutoMerge(incomingFp, dups.single()),
            "一侧没带门店 ⇒ 无冲突 ⇒ 必须仍能自动合并（否则 Tier-1 的同笔识别能力倒退）",
        )
    }

    @Test
    fun `different storefronts on the same tier are still surfaced as a candidate`() = runBlocking<Unit> {
        // 微信通知 + 支付宝通知（同层级不同通道）本就由层级护栏拒绝；这里确认门店护栏不会把候选也吞掉。
        val incoming = txn("a", wechat, "notify_wechat", counterparty = "中石化(朝阳站)")
        val existing = txn("b", alipay, "notify_alipay", occurredAt = anchor + 30_000L, counterparty = "中石化(海淀站)")
        val fp = fpOf(-30_000L, "中石化(朝阳站)")

        val incomingFp = incoming.copy(fingerprint = fp)
        val r = resolver(existing.copy(fingerprint = fp))
        val dups = r.findDuplicates(incomingFp)

        assertEquals(1, dups.size, "候选仍须浮出（用户可能想核对）")
        assertFalse(r.canAutoMerge(incomingFp, dups.single()), "同层级且不同通道 ⇒ 拒绝自动合并")
    }
}
