package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.countsInLedger
import com.autoledger.core.model.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 独立复核（QA「3fix」）：**30 秒自动合并窗口**的边界、**护栏在 30 秒内是否照旧生效**、
 * 以及合并 / 撤销合并的状态语义。
 *
 * ## 为什么另开一个文件
 * 工程师的 `LedgerDuplicateResolverTest` 刻意测了 29 秒 / 31 秒 / 181 秒，**避开了正好 30000ms**；
 * 而需求的原话是「**30 秒内**」，实现写的是 `<= FAST_MERGE_WINDOW`。等号那一侧没有任何用例覆盖 ——
 * 若有人把 `<=` 改成 `<`（或反过来把常量改成 29_999），既有用例全绿而语义已变。
 * 本文件把 `30_000 / 30_001 / 29_999` 三档都钉死，并补上**候选早于本条**（负偏移）的对称性。
 *
 * 第二条主线是**反向风险**（本批最重要的护栏）：30 秒内的两笔**真实**消费不得被静默合并。
 * 窗口收紧会让「护栏用例」从"因为超窗口而变绿"退化成"因为护栏生效而变绿"，
 * 因此护栏必须被**单独**证明在窗口内仍然生效。
 */
class FastMergeWindowIndependentAuditTest {

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
        amountMinor: Long = -2_500L,
        counterparty: String = "星巴克",
        status: TxnStatus = TxnStatus.CONFIRMED,
        fingerprint: String = "",
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = fingerprint,
        platformId = platformId,
    )

    private fun resolver(vararg existing: LedgerTransaction): Pair<LedgerDuplicateResolver, FakeLedgerRepository> {
        val repo = FakeLedgerRepository(existing.toList())
        return LedgerDuplicateResolver(repo) to repo
    }

    /**
     * Tier-1（指纹精确）的一对：微信支付通知 ↔ 银行扣款短信，同店同额同指纹。
     * 层级不同 ⇒ `tierOneAllowsAutoMerge` 放行 ⇒ 能不能合并**只由时间窗口决定**。
     */
    private suspend fun tierOnePair(offsetMillis: Long): Triple<LedgerDuplicateResolver, LedgerTransaction, List<com.autoledger.core.model.DuplicateCandidate>> {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val base = txn("wx", wechat, "notify_wechat")
        val fp = probe.fingerprintOf(base)
        val existing = base.copy(fingerprint = fp)
        val incoming = txn("bk", bank, "sms", occurredAt = anchor + offsetMillis).copy(fingerprint = fp)
        val (r, _) = resolver(existing)
        return Triple(r, incoming, r.findDuplicates(incoming))
    }

    // ==================================================================
    // C1. 边界必须精确：≤ 30 000 合并，> 30 000 不合并，> 180 000 连候选都不是
    // ==================================================================

    @Test
    fun `C1a - 29 999ms apart auto merges`() = runBlocking<Unit> {
        val (r, incoming, dups) = tierOnePair(29_999L)
        assertEquals(1, dups.size, "29.999 秒在发现窗口内 ⇒ 必须是候选")
        assertTrue(r.canAutoMerge(incoming, dups.single()), "29.999 秒 < 30 秒 ⇒ 允许静默合并")
    }

    @Test
    fun `C1b - exactly 30 000ms apart still auto merges (the untested side of the equals sign)`() = runBlocking<Unit> {
        val (r, incoming, dups) = tierOnePair(30_000L)
        assertEquals(1, dups.size, "30 秒在发现窗口内 ⇒ 必须是候选")
        assertTrue(
            r.canAutoMerge(incoming, dups.single()),
            "需求原话是「**30 秒内**」，实现是 `drift <= FAST_MERGE_WINDOW` ⇒ 正好 30000ms 必须合并。" +
                "若把 <= 写成 <（或把常量改成 29_999），既有 29/31 秒用例全绿而语义已变。",
        )
    }

    @Test
    fun `C1c - exactly 30 000ms in the other direction is symmetric`() = runBlocking<Unit> {
        // 候选**早于**本条 30 秒（负偏移）：abs() 必须两边一致，不能只处理正向漂移。
        val (r, incoming, dups) = tierOnePair(-30_000L)
        assertEquals(1, dups.size, "负向 30 秒同样是候选")
        assertTrue(r.canAutoMerge(incoming, dups.single()), "漂移取绝对值 ⇒ 负向正好 30 秒也必须合并")
    }

    @Test
    fun `C1d - 30 001ms apart is a candidate but must not auto merge`() = runBlocking<Unit> {
        val (r, incoming, dups) = tierOnePair(30_001L)
        assertEquals(1, dups.size, "30.001 秒仍在 180 秒发现窗口内 ⇒ 必须浮出为待确认候选（不能静默丢弃）")
        assertFalse(r.canAutoMerge(incoming, dups.single()), "30.001 秒 > 30 秒 ⇒ 不得静默合并")
    }

    @Test
    fun `C1e - 181 000ms apart is not even a candidate`() = runBlocking<Unit> {
        val (_, _, dups) = tierOnePair(181_000L)
        assertTrue(dups.isEmpty(), "181 秒 > 180 秒发现窗口 ⇒ 连候选都不是（两条各自入账）")
    }

    @Test
    fun `C1f - Tier-2 candidates obey the same 30 second merge window`() = runBlocking<Unit> {
        // 商户名不同 ⇒ 指纹不同 ⇒ 走 Tier-2（美团外卖 ORDER ↔ 财付通 BANK，AUTO_MERGE 格）。
        suspend fun pair(offset: Long): Triple<LedgerDuplicateResolver, LedgerTransaction, List<com.autoledger.core.model.DuplicateCandidate>> {
            val existing = txn("mt", "meituan", "notify", counterparty = "美团外卖")
            val incoming = txn("bk", bank, "sms", occurredAt = anchor + offset, counterparty = "财付通")
            val (r, _) = resolver(existing)
            return Triple(r, incoming, r.findDuplicates(incoming))
        }
        val (r30, in30, d30) = pair(30_000L)
        assertEquals(1, d30.size)
        assertTrue(r30.canAutoMerge(in30, d30.single()), "Tier-2 也必须把「正好 30 秒」算作可合并")

        val (r31, in31, d31) = pair(30_001L)
        assertEquals(1, d31.size, "Tier-2 超窗口仍是候选（交用户核对）")
        assertFalse(r31.canAutoMerge(in31, d31.single()), "Tier-2 超窗口同样不得静默合并")
    }

    // ==================================================================
    // C7. 反向风险：30 秒内的**两笔真实消费**不得被静默合并
    //     （护栏必须被单独证明在窗口内仍然生效，而不是靠"超窗口"混过）
    // ==================================================================

    @Test
    fun `C7a - 微信与支付宝 同店同额 相差 20 秒 绝不静默合并`() = runBlocking<Unit> {
        // 用户最关心的吞账场景：同一家店、同金额、20 秒内 —— 但一次消费只可能走一个支付通道。
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val wx = txn("wx", wechat, "notify_wechat", counterparty = "星巴克(国贸店)")
        val fp = probe.fingerprintOf(wx)
        val existing = wx.copy(fingerprint = fp)
        val incoming = txn("ali", alipay, "notify_alipay", occurredAt = anchor + 20_000L, counterparty = "星巴克(国贸店)")
            .copy(fingerprint = fp)
        val (r, _) = resolver(existing)

        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "指纹相同 ⇒ 必须浮出为候选（用户要能看见）")
        assertFalse(
            r.canAutoMerge(incoming, dups.single()),
            "★ 微信↔支付宝同店同额，即便相差仅 20 秒，也不得静默合并 —— " +
                "一次消费只走一个支付通道，这只能是两笔真实消费（层级护栏 P0-1）",
        )
    }

    @Test
    fun `C7b - 同品牌不同门店 相差 12 秒 绝不静默合并`() = runBlocking<Unit> {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        // 抹括号后指纹相同（Tier-1 的核心能力），但两个加油站 = 两笔真实消费。
        val wx = txn("wx", wechat, "notify_wechat", counterparty = "中石化(朝阳站)")
        val fp = probe.fingerprintOf(wx)
        val existing = wx.copy(fingerprint = fp)
        val incoming = txn("bk", bank, "sms", occurredAt = anchor + 12_000L, counterparty = "中石化(海淀站)")
            .copy(fingerprint = fp)
        val (r, _) = resolver(existing)

        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "指纹相同 ⇒ 是候选")
        assertFalse(
            r.canAutoMerge(incoming, dups.single()),
            "两侧门店都非空且不同 ⇒ 不得静默合并（门店护栏 P0-2）；12 秒远在窗口内，靠的是护栏不是窗口",
        )
    }

    @Test
    fun `C7c - 手工录入的 unknown 行 相差 20 秒 绝不被外卖订单吞掉`() = runBlocking<Unit> {
        // ⚠️ 指纹必须按生产口径算（`fingerprintOf`）。若两侧都留空串，Tier-1 会把「空指纹 == 空指纹」
        // 当成精确命中，从而绕过只作用于 Tier-2 的权威来源护栏 —— 那是**夹具**失真，不是产品行为
        // （生产侧 `IngestPipeline` 一定先算指纹，见 IngestPipeline.kt:115）。
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val manual = txn(
            "manual", none, CaptureSourceIds.MANUAL,
            counterparty = "菜市场", amountMinor = -3_700L,
        ).let { it.copy(fingerprint = probe.fingerprintOf(it)) }
        val incoming = txn(
            "mt", "meituan", "notify",
            occurredAt = anchor + 20_000L, counterparty = "美团外卖", amountMinor = -3_700L,
        ).let { it.copy(fingerprint = probe.fingerprintOf(it)) }
        val (r, _) = resolver(manual)

        val dups = r.findDuplicates(incoming)
        assertTrue(dups.isNotEmpty(), "前置：Tier-2 确实把它当候选（缝隙入口），实际=${dups.size}")
        assertFalse(
            r.canAutoMerge(incoming, dups.single()),
            "用户手工录入的现金消费是权威事实 ⇒ 不得被同金额外卖订单静默吞并（权威来源护栏 P0-4）",
        )
    }

    @Test
    fun `C7d - 同渠道同店同额 20 秒内 仍不自动合并（保守口径未被顺手放宽）`() = runBlocking<Unit> {
        // 同一条 notify 渠道、同一家店、同金额、20 秒内 ⇒ 可能是重复抓取，也可能是两杯咖啡。
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val first = txn("a", none, "notify", counterparty = "便利店")
        val fp = probe.fingerprintOf(first)
        val existing = first.copy(fingerprint = fp)
        val incoming = txn("b", none, "notify", occurredAt = anchor + 20_000L, counterparty = "便利店")
            .copy(fingerprint = fp)
        val (r, _) = resolver(existing)

        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "指纹相同 ⇒ 是候选")
        assertFalse(
            r.canAutoMerge(incoming, dups.single()),
            "同 source 不满足 Tier-1 的「跨渠道」前提 ⇒ 降级待确认（宁可多一步确认也不静默吞掉）",
        )
    }

    // ==================================================================
    // C3. 合并语义：主记录 CONFIRMED、被吸收 MERGED（不是 DUPLICATE）、可反查、可撤销
    // ==================================================================

    @Test
    fun `C3 - merging absorbs as MERGED with a reverse pointer, and unmerge restores RAW`() = runBlocking<Unit> {
        val primary = txn("primary", "meituan", "notify", counterparty = "美团外卖")
        val absorbedA = txn("a", wechat, "notify_wechat", counterparty = "财付通")
        val absorbedB = txn("b", bank, "sms", counterparty = "工商银行")
        val (r, repo) = resolver(primary, absorbedA, absorbedB)

        r.merge("primary", listOf("a", "b"))

        assertEquals(TxnStatus.CONFIRMED, repo.findById("primary")?.status, "主记录必须 CONFIRMED（计入账本）")
        assertEquals(
            TxnStatus.MERGED, repo.findById("a")?.status,
            "被吸收的记录必须是 MERGED（金额由主记录承担），**不是** DUPLICATE —— " +
                "DUPLICATE 的语义是「疑似重复、等用户拍板」，用在这里会让用户看到一条本已合掉的记录",
        )
        assertEquals("primary", repo.findById("a")?.mergedIntoId, "mergedIntoId 必须可反查主记录")
        assertEquals(setOf("a", "b"), repo.mergeGroupOf("primary").map { it.id }.toSet())
        assertFalse(TxnStatus.MERGED.countsInLedger, "MERGED 不得计入账本")
        assertFalse(TxnStatus.DUPLICATE.countsInLedger, "DUPLICATE 不得计入账本")

        // 撤销合并 ⇒ 回 RAW，且**重新计入账本**（否则会从账单与采集箱里同时消失）
        r.unmerge("a")
        assertEquals(TxnStatus.RAW, repo.findById("a")?.status, "撤销合并后回到待确认")
        assertNull(repo.findById("a")?.mergedIntoId, "撤销合并必须清空溯源")
        assertTrue(TxnStatus.RAW.countsInLedger, "RAW 计入账本 ⇒ 会重新出现在账单里")
        assertEquals(setOf("b"), repo.mergeGroupOf("primary").map { it.id }.toSet(), "另一条不受影响")
    }

    @Test
    fun `C3b - a DUPLICATE row must never be used as a merge target`() = runBlocking<Unit> {
        // 一条已在待确认的疑似重复，不该再被另一条同额记录当成合并目标（否则形成链式噪音）。
        val pending = txn("pending", bank, "sms", counterparty = "星巴克", status = TxnStatus.DUPLICATE)
        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(pending)
        val (r, _) = resolver(pending.copy(fingerprint = fp))

        val incoming = txn("new", wechat, "notify_wechat", occurredAt = anchor + 5_000L, counterparty = "星巴克")
            .copy(fingerprint = fp)
        val dups = r.findDuplicates(incoming)
        assertTrue(
            dups.none { it.txnId == "pending" },
            "DUPLICATE 行不得作为候选：它本身还在等用户拍板；实际候选=${dups.map { it.txnId }}",
        )
    }
}
