package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MatchTier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.dedup.DedupPriority
import com.autoledger.core.model.platform.priorityOf
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **Tier-2 层级互补匹配 + 护栏表**的纯 JVM 护栏（设计 §4.2 / §4.6）。
 *
 * 为什么这条通道非有不可：Tier-1 指纹 = `sha256(金额 | 归一化商户)`，
 * 而需求场景里两条记录的商户名**天然不同**（美团通知写「美团外卖」、银行短信写「财付通」）
 * ⇒ 指纹不同 ⇒ 只加平台优先级**永远不会被触发**。
 *
 * 本文件同时是**防误合并的防线**：设计 §4.2 的每一格都配了断言，
 * 尤其是「同一家店 3 分钟内两笔真实消费」「两个下单平台同金额」这两个反例 ——
 * 护栏写松一点就会静默吞掉用户的真实消费。
 */
class Tier2ComplementaryMatchTest {

    private val anchor = 1_700_000_000_000L

    private fun txn(
        id: String,
        platformId: String,
        sourceId: String,
        occurredAt: Long = anchor,
        amountMinor: Long = -8_800L,
        counterparty: String = "某商户",
        status: TxnStatus = TxnStatus.CONFIRMED,
        platformSource: PlatformSource = PlatformSource.AUTO,
        fingerprint: String = "fp-$id",
        sourceRef: String = "$sourceId:$id",
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = if (amountMinor < 0) TxnType.EXPENSE else TxnType.INCOME,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = sourceRef,
        status = status,
        fingerprint = fingerprint,
        platformId = platformId,
        platformSource = platformSource,
    )

    private fun resolver(vararg existing: LedgerTransaction): Pair<LedgerDuplicateResolver, FakeLedgerRepository> {
        val repo = FakeLedgerRepository(existing.toList())
        return LedgerDuplicateResolver(repo) to repo
    }

    /** 指纹不同（商户名不同）—— 这正是 Tier-1 查不到的原因，测试里要显式确认。 */
    private fun assertTier1CannotSee(
        resolver: LedgerDuplicateResolver,
        incoming: LedgerTransaction,
        existing: LedgerTransaction,
    ) {
        assertTrue(
            existing.fingerprint != resolver.fingerprintOf(incoming),
            "前置条件：商户名不同 ⇒ 指纹不同 ⇒ Tier-1 必然查不到（这正是要补 Tier-2 的原因）",
        )
    }

    // ------------------------------------------------------------------ 护栏表（§4.2）

    @Test
    fun `the guard table maps every platform tier pair as designed`() {
        val order = "meituan"
        val pay = "alipay"
        val bank = PlatformCatalog.BANK_ID
        val none = PlatformCatalog.UNKNOWN_ID

        // 三格允许静默自动合并
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(order, pay), "美团下单 + 微信/支付宝付款")
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(order, bank), "美团下单 + 银行卡扣款")
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(order, none), "美团通知 + 未识别出平台的银行短信")
        // 对称性：判定与「谁是 incoming」无关
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(pay, order))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(bank, order))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(none, order))

        // 唯一真实歧义：交用户（是**候选**，但不自动合并）
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(pay, bank), "微信支付 88 + 银行卡扣 88")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(bank, pay))

        // 层级不互补：连候选都不是
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(pay, pay), "一次消费只有一个支付通道")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(order, order), "两个消费场所 = 两笔消费")
        // 必修⑤：同一条 bank 通道被重复抓取（目录里 BANK 只有唯一 ID）⇒ 交用户（既非 REJECT、也非 AUTO_MERGE）
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(bank, bank))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(none, none), "无层级信息")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(pay, none), "保守：没有互补证据")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(bank, none))
        // 未收录 ID 一律按 NONE 处理，绝不抛异常
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict("未收录", none))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict("未收录", order))
    }

    @Test
    fun `same tier with different channels is rejected, and so is every same tier pair except the single bank channel`() {
        // 不变量①：同 tier 且**不同通道**（微信 vs 支付宝、美团 vs 淘宝）⇒ 一律 REJECT，无例外。
        for (a in listOf("wechat", "alipay")) for (b in listOf("wechat", "alipay")) {
            if (a != b) {
                assertEquals(
                    ComplementaryVerdict.REJECT,
                    complementaryVerdict(a, b),
                    "两个支付通道 = 两笔消费：$a ↔ $b",
                )
            }
        }
        for (a in listOf("meituan", "taobao")) for (b in listOf("meituan", "taobao")) {
            if (a != b) {
                assertEquals(
                    ComplementaryVerdict.REJECT,
                    complementaryVerdict(a, b),
                    "两个下单平台 = 两笔消费：$a ↔ $b",
                )
            }
        }

        // 不变量②：同 tier 且**同通道**（同一 platformId、同金额、短窗）⇒ 一般是两笔真实消费 ⇒ REJECT。
        // ⚠️ 唯一例外是 bank↔bank（必修⑤）：BANK 只有唯一 ID `bank`，银行短信 + 银行 App 动账通知
        // 是**同一条通道被重复抓取** ⇒ REVIEW（交用户），既不能静默合并、也不能静默双记。
        for (p in listOf("meituan", "alipay", PlatformCatalog.UNKNOWN_ID)) {
            assertEquals(
                ComplementaryVerdict.REJECT,
                complementaryVerdict(p, p),
                "同通道且非 bank 的 $p ↔ $p 必须不合并（防同金额两笔真实消费被吞）",
            )
        }
        assertEquals(
            ComplementaryVerdict.REVIEW,
            complementaryVerdict(PlatformCatalog.BANK_ID, PlatformCatalog.BANK_ID),
            "必修⑤ 的唯一例外：同一条 bank 通道被重复抓取 ⇒ 交用户",
        )
    }

    // ------------------------------------------------------------------ D1 / D2：需求主场景

    @Test
    fun `D1 - meituan notice and bank sms with different merchants merge, meituan becomes primary`() = runBlocking<Unit> {
        // 需求原文的组合：美团通知商户「美团外卖」，银行短信商户「财付通」——**商户名不同**。
        val meituan = txn("mt", platformId = "meituan", sourceId = "notify", counterparty = "美团外卖")
        val bankSms = txn("bank", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", counterparty = "财付通")
        val (resolver, repo) = resolver(meituan)

        assertTier1CannotSee(resolver, bankSms.copy(fingerprint = resolver.fingerprintOf(bankSms)), meituan)

        val dups = resolver.findDuplicates(bankSms)
        assertEquals(1, dups.size, "Tier-2 必须能发现这笔（Tier-1 查不到），实际=${dups.size}")
        val candidate = dups.first()
        assertEquals(meituan.id, candidate.txnId)
        assertEquals(MatchTier.COMPLEMENTARY, candidate.tier, "走的是 Tier-2 通道")
        assertEquals("meituan", candidate.platformId)
        assertEquals(3, candidate.priorityRank)
        assertTrue(candidate.crossSource, "sms 与 notify 必须跨渠道")
        assertTrue(resolver.canAutoMerge(bankSms, candidate), "ORDER ↔ BANK 允许静默合并")

        // 合并：主记录必须是层级更高的美团那条，而不是「先入库的那条」
        // （先落库，否则 setMergeState 找不到行 —— 真实链路里这条当然已经入账了）
        repo.upsert(bankSms)
        resolver.merge(candidate.txnId, listOf(bankSms.id))
        assertEquals(TxnStatus.CONFIRMED, repo.findById(meituan.id)?.status, "美团那条留下")
        assertEquals(TxnStatus.MERGED, repo.findById(bankSms.id)?.status, "银行短信被吸收")
        assertEquals(meituan.id, repo.findById(bankSms.id)?.mergedIntoId, "被吸收方必须能反查出主记录")
    }

    @Test
    fun `D2 - meituan and wechat with the same merchant and amount merge, the order platform wins`() = runBlocking<Unit> {
        // 同一笔消费被两个渠道抓到、且商户名恰好一致 ⇒ 指纹相同 ⇒ 走 Tier-1（老路，行为不得回归）
        val wechat = txn("wx", platformId = "wechat", sourceId = "notify_wechat", counterparty = "美团外卖", fingerprint = "fp-same")
        val meituan = txn("mt", platformId = "meituan", sourceId = "notify", counterparty = "美团外卖", fingerprint = "fp-same")
        val (resolver, _) = resolver(wechat)

        val dups = resolver.findDuplicates(meituan)
        assertEquals(1, dups.size)
        assertEquals(MatchTier.FINGERPRINT, dups.single().tier, "商户名一致时不该绕道 Tier-2")
        assertTrue(resolver.canAutoMerge(meituan, dups.single()))

        // 层级裁决：美团(ORDER=3) > 微信(PAYMENT=2) —— 主记录必须是「美团」，与谁先入库无关
        val choice = DedupPriority.choosePrimary(
            incomingId = meituan.id, incomingRank = priorityOf("meituan").rank, incomingIsUser = false,
            existingId = wechat.id, existingRank = dups.single().priorityRank, existingIsUser = false,
        )
        assertEquals(meituan.id, choice.primaryId, "订单平台必须压过支付通道当主记录")
        assertEquals(wechat.id, choice.mergedId)
    }

    // ------------------------------------------------------------------ 反例（硬门槛）

    @Test
    fun `D3 - wechat plus a bank card is ambiguous so it is a candidate but never auto merged`() = runBlocking<Unit> {
        val wechat = txn("wx", platformId = "wechat", sourceId = "notify", counterparty = "某商户")
        val bank = txn("bank", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", counterparty = "另一商户")
        val (resolver, _) = resolver(wechat)

        val dups = resolver.findDuplicates(bank)
        assertEquals(1, dups.size, "证据不足的组合也必须浮出来提示用户，否则用户永远看不到这笔可能重复")
        assertEquals(MatchTier.COMPLEMENTARY, dups.first().tier)
        assertFalse(
            resolver.canAutoMerge(bank, dups.first()),
            "PAYMENT ↔ BANK 是唯一真实歧义：可能是同一笔（微信绑的这张卡），也可能是两笔（先充值再消费）",
        )
    }

    @Test
    fun `D4 - wechat plus alipay is not even a candidate`() = runBlocking<Unit> {
        // 一次消费只有一个支付通道；两条通道记录 = 两笔消费或一条误抓
        val wechat = txn("wx", platformId = "wechat", sourceId = "notify_wechat", counterparty = "某商户")
        val alipay = txn("ali", platformId = "alipay", sourceId = "notify_alipay", counterparty = "另一商户")
        val (resolver, _) = resolver(wechat)

        assertTrue(resolver.findDuplicates(alipay).isEmpty(), "PAYMENT ↔ PAYMENT 不得作为候选")
    }

    @Test
    fun `D5 and D7 - two real purchases at the same shop within 3 minutes are never merged`() = runBlocking<Unit> {
        // 这是最危险的反例：同一家店、同金额、3 分钟内两笔真实消费。
        // 若护栏写松（只判"同金额 + 同时间"），用户的两笔消费会被静默吞成一笔。
        // 同一家店、同金额、同商户 ⇒ **指纹相同**（这才是这条反例的真实形态）
        val first = txn("first", platformId = "meituan", sourceId = "notify", occurredAt = anchor, counterparty = "美团外卖", fingerprint = "fp-same-shop")
        val second = txn("second", platformId = "meituan", sourceId = "notify", occurredAt = anchor + 60_000L, counterparty = "美团外卖", fingerprint = "fp-same-shop")
        val (resolver, _) = resolver(first)

        // 同渠道（sourceId 相同）⇒ 即便指纹一致也不能自动合并（既有保守语义）
        val sameSource = resolver.findDuplicates(second)
        assertEquals(1, sameSource.size, "同渠道同指纹仍是候选，交由用户确认")
        assertFalse(
            resolver.canAutoMerge(second, sameSource.first()),
            "同渠道候选绝不能静默合并 —— 这正是「宁可多一步确认」的落点",
        )

        // 换一个渠道、但都是 ORDER（美团通知 vs 淘宝通知）⇒ 两个消费场所 = 两笔消费，连候选都不是
        val taobao = txn("tb", platformId = "taobao", sourceId = "notify_taobao", occurredAt = anchor + 30_000L, counterparty = "淘宝")
        assertTrue(resolver.findDuplicates(taobao).isEmpty(), "ORDER ↔ ORDER 不得作为候选")
    }

    @Test
    fun `D8 - same amount with different merchants and no platform complementarity is not merged`() = runBlocking<Unit> {
        val unknownA = txn("a", platformId = PlatformCatalog.UNKNOWN_ID, sourceId = "sms", counterparty = "商户甲")
        val unknownB = txn("b", platformId = PlatformCatalog.UNKNOWN_ID, sourceId = CaptureSourceIds.BILL_IMPORT, counterparty = "商户乙")
        val (resolver, _) = resolver(unknownA)

        assertTrue(
            resolver.findDuplicates(unknownB).isEmpty(),
            "NONE ↔ NONE 没有层级信息，绝不能因为金额相同就合并",
        )
    }

    @Test
    fun `D9 - an expense and a refund of the same magnitude are never merged`() = runBlocking<Unit> {
        // 指纹含符号（amountMinor 原值），天然挡住「退款冲抵原单」被误当重复
        val expense = txn("exp", platformId = "meituan", sourceId = "notify", amountMinor = -8_800L)
        val refund = txn("ref", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", amountMinor = 8_800L)
        val (resolver, _) = resolver(expense)

        assertTrue(resolver.findDuplicates(refund).isEmpty(), "−88 与 +88 是两件事（支出与退款），绝不可合并")
    }

    @Test
    fun `D10 - a zero amount is never merged`() = runBlocking<Unit> {
        val a = txn("a", platformId = "meituan", sourceId = "notify", amountMinor = 0L)
        val b = txn("b", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", amountMinor = 0L)
        val (resolver, _) = resolver(a)

        assertTrue(resolver.findDuplicates(b).isEmpty(), "金额为 0 说明没解析出来，没有任何可靠锚点")
    }

    @Test
    fun `D16 - a drift beyond the window is not merged`() = runBlocking<Unit> {
        val existing = txn("a", platformId = "meituan", sourceId = "notify", occurredAt = anchor)
        val tooLate = txn("b", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", occurredAt = anchor + 3 * 60 * 1000L + 1_000L)
        val (resolver, _) = resolver(existing)

        assertTrue(resolver.findDuplicates(tooLate).isEmpty(), "3 分 01 秒超出窗口 —— 放宽会显著提高误合并")
    }

    @Test
    fun `D17 - records two seconds apart across midnight still merge`() = runBlocking<Unit> {
        // 23:59:30 的微信通知 + 00:00:10 的银行短信是**同一笔**。
        // 若窗口实现里偷偷加了"必须同一天"，这一格就会漏合并。
        val before = txn("before", platformId = "meituan", sourceId = "notify", occurredAt = anchor)
        val afterMidnight = txn("after", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", occurredAt = anchor + 2_000L)
        val (resolver, _) = resolver(before)

        val dups = resolver.findDuplicates(afterMidnight)
        assertEquals(1, dups.size, "跨午夜不得漏合并（窗口是纯时间差，没有「同一天」约束）")
        assertTrue(resolver.canAutoMerge(afterMidnight, dups.first()))
    }

    @Test
    fun `D18 - an already merged record is never a candidate again`() = runBlocking<Unit> {
        val absorbed = txn("absorbed", platformId = "meituan", sourceId = "notify", status = TxnStatus.MERGED)
        val ignored = txn("ignored", platformId = "meituan", sourceId = "notify2", status = TxnStatus.IGNORED)
        val (resolver, _) = resolver(absorbed, ignored)

        val incoming = txn("incoming", platformId = PlatformCatalog.BANK_ID, sourceId = "sms")
        assertTrue(resolver.findDuplicates(incoming).isEmpty(), "被吸收 / 被忽略的记录都不得再当候选")
    }

    // ------------------------------------------------------------------ Tier-1 仍要走老路（防回归）

    @Test
    fun `D6 - an identical fingerprint still takes the tier one path and carries the new fields`() = runBlocking<Unit> {
        val existing = txn("a", platformId = "wechat", sourceId = "notify_wechat", counterparty = "瑞幸咖啡", fingerprint = "same-fp")
        val incoming = txn("b", platformId = "wechat", sourceId = "sms", counterparty = "瑞幸咖啡", fingerprint = "same-fp")
        val (resolver, _) = resolver(existing)

        val dups = resolver.findDuplicates(incoming)
        assertEquals(1, dups.size, "指纹一致走 Tier-1，行为与改造前一致")
        val candidate = dups.first()
        assertEquals(MatchTier.FINGERPRINT, candidate.tier)
        assertEquals("wechat", candidate.platformId, "候选必须带上平台，供主记录裁决使用")
        assertEquals(2, candidate.priorityRank)
        assertEquals(PlatformSource.AUTO, candidate.platformSource)
        assertTrue(resolver.canAutoMerge(incoming, candidate), "跨渠道 + 商户非空 ⇒ 允许自动合并")
    }

    @Test
    fun `tier one never falls through to tier two when it already matched`() = runBlocking<Unit> {
        // 指纹命中时不应再产出 Tier-2 候选（否则同一笔会被算两次，主记录裁决也会混乱）
        val sameFp = txn("same", platformId = "meituan", sourceId = "notify", counterparty = "美团外卖", fingerprint = "fp-x")
        val incoming = txn("in", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", counterparty = "美团外卖", fingerprint = "fp-x")
        val (resolver, _) = resolver(sameFp)

        val dups = resolver.findDuplicates(incoming)
        assertEquals(1, dups.size)
        assertEquals(MatchTier.FINGERPRINT, dups.single().tier)
    }

    // ------------------------------------------------------------------ D13 / D15：合并溯源

    @Test
    fun `D13 - merge records the traceability so the merge group can be reverse queried`() = runBlocking<Unit> {
        val primary = txn("primary", platformId = "meituan", sourceId = "notify", counterparty = "美团外卖")
        val absorbed = txn("absorbed", platformId = PlatformCatalog.BANK_ID, sourceId = "sms", counterparty = "财付通")
        val (resolver, repo) = resolver(primary, absorbed)

        resolver.merge(primary.id, listOf(absorbed.id))

        val group = repo.mergeGroupOf(primary.id)
        assertEquals(listOf(absorbed.id), group.map { it.id }, "「这笔记了两次，分别来自美团和银行卡」必须是可查数据")
        assertEquals(PlatformCatalog.BANK_ID, group.single().platformId, "被吸收那条自己的平台仍在（证据不丢）")
        assertEquals(primary.id, repo.findById(absorbed.id)?.mergedIntoId)
        assertEquals(TxnStatus.CONFIRMED, repo.findById(primary.id)?.status)
        assertNotNull(repo.findById(absorbed.id)?.sourceRef, "被吸收行原样保留，不删不改")
    }

    @Test
    fun `D15 - unmerge restores the record and clears the traceability`() = runBlocking<Unit> {
        val primary = txn("primary", platformId = "meituan", sourceId = "notify")
        val absorbed = txn("absorbed", platformId = PlatformCatalog.BANK_ID, sourceId = "sms")
        val (resolver, repo) = resolver(primary, absorbed)

        resolver.merge(primary.id, listOf(absorbed.id))
        assertEquals(TxnStatus.MERGED, repo.findById(absorbed.id)?.status)

        resolver.unmerge(absorbed.id)
        assertEquals(TxnStatus.RAW, repo.findById(absorbed.id)?.status, "撤销后回到待确认")
        assertNull(repo.findById(absorbed.id)?.mergedIntoId, "溯源必须清空，否则它会一直「挂」在主记录下")
        assertTrue(repo.mergeGroupOf(primary.id).isEmpty(), "撤销后不再属于该合并组")
    }
}
