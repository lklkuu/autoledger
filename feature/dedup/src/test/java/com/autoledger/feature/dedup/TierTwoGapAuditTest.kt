package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MatchTier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * QA 独立复审（P0-2 / P0-3 / 新缝隙）：**Tier-2 层级互补匹配在修复后是否还有漏洞**。
 *
 * 必修① 只改了 Tier-1；Tier-2 的护栏（[complementaryVerdict]）**一个字没动**，
 * 所以这里要重新推演「层级互补」两个允许自动合并的格子（`ORDER↔PAYMENT` / `ORDER↔NONE`）
 * 是否会被**非同一笔**的记录钻空子。
 */
class TierTwoGapAuditTest {

    private val anchor = 1_700_000_000_000L
    private val none = PlatformCatalog.UNKNOWN_ID
    private val bank = PlatformCatalog.BANK_ID

    private fun txn(
        id: String,
        platformId: String,
        sourceId: String,
        occurredAt: Long = anchor,
        amountMinor: Long = -2_500L,
        counterparty: String = "美团外卖",
        status: TxnStatus = TxnStatus.CONFIRMED,
        fingerprint: String = "fp-$id",
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

    private fun resolver(vararg existing: LedgerTransaction) =
        LedgerDuplicateResolver(FakeLedgerRepository(existing.toList()))

    // ================================================================ 新缝隙：ORDER ↔ NONE

    /**
     * ⚠️ 本用例**预期失败**（红 = 暴露 Tier-2 缝隙）。
     *
     * 场景：「我 12:00 手工记了一笔现金买菜 25 元，12:01 又点了 25 元美团外卖」。
     * 手工流水（sourceId=`manual`、平台 `unknown`）与美团通知（`ORDER`）**商户不同**（指纹不同 ⇒ Tier-1 不命中），
     * 但符合 Tier-2 的「同金额 + 3 分钟 + 跨 source + 层级互补（ORDER↔NONE）」⇒ **被自动合并**。
     *
     * 用户的现金消费被静默吸收进美团订单并隐藏（MERGED），账单里两笔变一笔 —— 这是吞账。
     * `unknown` 并不等于"同一笔的银行侧"，它也可能是**用户亲手录入**或**账单导入**的独立消费。
     */
    @Test
    fun `a manual cash entry must not be silently absorbed by an unrelated same-amount order`() = runBlocking<Unit> {
        val manual = txn("manual", none, CaptureSourceIds.MANUAL, counterparty = "菜市场")
        val meituan = txn("mt", "meituan", "notify", occurredAt = anchor + 60_000L, counterparty = "美团外卖")
        val r = resolver(manual)

        val dups = r.findDuplicates(meituan)
        assertTrue(dups.isNotEmpty(), "前置：Tier-2 确实把它当候选（这正是要审的缝隙入口）")
        assertEquals(MatchTier.COMPLEMENTARY, dups.first().tier)

        assertFalse(
            r.canAutoMerge(meituan, dups.first()),
            "用户手工录入的现金消费是权威数据，不得被同金额的外卖订单静默吞并",
        )
    }

    /**
     * 同类：账单导入行（sourceId=`bill`）作为 `unknown` 一侧，也会被同金额的 ORDER 通知吞并。
     */
    @Test
    fun `a bill-imported unknown row must not be silently absorbed by an unrelated order`() = runBlocking<Unit> {
        val bill = txn("bill", none, CaptureSourceIds.BILL_IMPORT, counterparty = "楼下超市")
        val meituan = txn("mt", "meituan", "notify", occurredAt = anchor + 30_000L, counterparty = "美团外卖")
        val r = resolver(bill)

        val dups = r.findDuplicates(meituan)
        assertTrue(dups.isNotEmpty(), "前置：Tier-2 候选存在")
        assertFalse(
            r.canAutoMerge(meituan, dups.first()),
            "账单导入的独立消费行不应被同金额的 ORDER 通知静默吞并",
        )
    }

    // ================================================================ 需锁死的正确行为（应绿）

    @Test
    fun `ORDER plus PAYMENT with different merchants is a Tier-2 candidate but the residual risk is explicit`() =
        runBlocking<Unit> {
            // 设计**明知且接受**的取舍：美团下单 30 元 + 微信在同店/异店 30 元，可能是同一笔、也可能是两笔。
            // 本用例只把「当前行为」钉住（防止无声漂移），真正的风险已在报告里单独说明。
            val meituan = txn("mt", "meituan", "notify", counterparty = "美团外卖")
            val wechat = txn("wx", "wechat", "notify_wechat", occurredAt = anchor + 90_000L, counterparty = "星巴克")
            val r = resolver(meituan)

            val dups = r.findDuplicates(wechat)
            assertEquals(1, dups.size, "ORDER↔PAYMENT 层级互补 ⇒ 是候选")
            assertTrue(r.canAutoMerge(wechat, dups.first()), "当前实现允许静默合并（设计已知取舍）")
        }

    @Test
    fun `PAYMENT plus BANK goes to review and is never auto merged`() = runBlocking<Unit> {
        val wechat = txn("wx", "wechat", "notify_wechat", counterparty = "某商户")
        val bankSms = txn("bank", bank, "sms", occurredAt = anchor + 30_000L, counterparty = "沃尔玛")
        val r = resolver(wechat)

        val dups = r.findDuplicates(bankSms)
        assertEquals(1, dups.size, "唯一真实歧义组合：作为候选浮出，等用户确认")
        assertFalse(r.canAutoMerge(bankSms, dups.first()), "PAYMENT↔BANK 绝不静默合并")
    }

    @Test
    fun `two unknown rows with different merchants are not even candidates`() = runBlocking<Unit> {
        val a = txn("a", none, "sms", counterparty = "商户甲")
        val b = txn("b", none, CaptureSourceIds.BILL_IMPORT, counterparty = "商户乙")
        val r = resolver(a)
        assertTrue(r.findDuplicates(b).isEmpty(), "NONE↔NONE 连候选都不是")
    }

    // ================================================================ P2-3 修复的前置条件

    @Test
    fun `an incoming record can surface both a review candidate and an auto-mergeable one`() = runBlocking<Unit> {
        // P2-3 之所以必要：同一笔 incoming 可能同时匹配到「REVIEW 候选」和「AUTO_MERGE 候选」。
        // 若取 duplicates.first()（按时间倒序），可能先撞上 REVIEW 而白白退化为待确认。
        // 本用例独立证明「两个候选确实并存」，从而证明 IngestPipeline 的 firstOrNull{canAutoMerge} 有意义。
        val wechatLater = txn("wx", "wechat", "notify_wechat", occurredAt = anchor + 120_000L, counterparty = "星巴克")
        val meituanEarlier = txn("mt", "meituan", "notify", occurredAt = anchor, counterparty = "美团外卖")
        val r = resolver(wechatLater, meituanEarlier)

        val incoming = txn("bank", bank, "sms", occurredAt = anchor + 60_000L, counterparty = "财付通")
        val dups = r.findDuplicates(incoming)

        assertEquals(2, dups.size, "同金额窗口内同时存在 PAYMENT 与 ORDER 两个候选")
        // 时间倒序 ⇒ REVIEW 的微信候选排在前面（这正是旧代码会踩的坑）
        assertEquals("wx", dups.first().txnId)
        assertFalse(r.canAutoMerge(incoming, dups.first()), "第一个候选是 REVIEW，不能自动合并")
        assertTrue(r.canAutoMerge(incoming, dups.last()), "第二个候选（ORDER）可以自动合并")
    }
}
