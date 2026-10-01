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
 * 必修④【P0-4】：**Tier-2 权威来源护栏**（`noneSideIsAuthoritative`）。
 *
 * 反例：12:00 用户手工记「菜市场 现金 25 元」（`sourceId=manual`、平台 `unknown`），
 * 12:01 美团外卖通知 25 元（`ORDER`）。商户不同 ⇒ 指纹不同 ⇒ Tier-1 不命中；
 * 但满足 Tier-2 的 `ORDER↔NONE` 互补 ⇒ 被**静默合并**，用户的现金消费被吞。
 *
 * 护栏：Tier-2 的 `unknown(NONE)` 一侧若来自**权威 / 导入来源**（手工录入 / 账单导入）⇒ 拒绝自动合并。
 * 本文件两层覆盖：纯函数真值表 + 端到端（含「不得误伤自动采集的银行侧」的对照）。
 */
class AuthoritativeSourceGuardTest {

    private val anchor = 1_700_000_000_000L
    private val meituan = "meituan"
    private val none = PlatformCatalog.UNKNOWN_ID
    private val manual = CaptureSourceIds.MANUAL
    private val billImport = CaptureSourceIds.BILL_IMPORT
    private val notify = CaptureSourceIds.NOTIFY
    private val sms = CaptureSourceIds.SMS

    private fun txn(
        id: String,
        platformId: String,
        sourceId: String,
        occurredAt: Long = anchor,
        amountMinor: Long = -2_500L,
        counterparty: String = "美团外卖",
        status: TxnStatus = TxnStatus.CONFIRMED,
        // 默认给每条一个**互不相同**的指纹：这样商户名不同时 Tier-1 必然查不到对方，
        // 才会真正走 Tier-2 互补通道（否则两条空指纹会被 Tier-1 当成指纹相同的同一条，绕过本用例要审的路径）。
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

    // ------------------------------------------------------------------ 纯函数真值表

    @Test
    fun `an unknown side coming from manual entry blocks auto merge`() {
        assertTrue(
            noneSideIsAuthoritative(none, manual, meituan, notify),
            "incoming 是手工录入(unknown) ⇒ 阻断",
        )
        assertTrue(
            noneSideIsAuthoritative(meituan, notify, none, manual),
            "existing 是手工录入(unknown) ⇒ 阻断（与参数顺序无关）",
        )
    }

    @Test
    fun `an unknown side coming from bill import blocks auto merge`() {
        // ⚠️ 账单导入的实际 sourceId 是 `bill_import`（不是团队口述的 'bill'）—— 用常量比较，勿写字面量。
        assertTrue(noneSideIsAuthoritative(none, billImport, meituan, notify), "incoming 是账单导入(unknown) ⇒ 阻断")
        assertTrue(noneSideIsAuthoritative(meituan, notify, none, billImport), "existing 是账单导入(unknown) ⇒ 阻断")
    }

    @Test
    fun `the bill-import fixture alias is also treated as authoritative`() {
        // 规范 ID 是 `bill_import`；但既有测试夹具长期用短写 `"bill"` 表示账单导入行。
        // 护栏一并纳入（见 AUTHORITATIVE_PLATFORM_SOURCES 的说明），否则夹具与实现的字面差异会让
        // 这一整类权威来源静默漏过 —— QA 的 TierTwoGapAuditTest 正是用 `"bill"`。
        assertTrue(noneSideIsAuthoritative(none, "bill", meituan, notify), "短写 bill 也要认得")
        assertTrue(noneSideIsAuthoritative(meituan, notify, none, "bill"))
    }

    @Test
    fun `an unknown side from an automatic channel does not block auto merge`() {
        // 银行短信(unknown, sms) / 通知(unknown, notify) 是「自动抓来的银行侧」的正常情形，不得阻断。
        assertFalse(noneSideIsAuthoritative(none, sms, meituan, notify), "sms 来源不是权威来源")
        assertFalse(noneSideIsAuthoritative(none, notify, meituan, sms), "notify 来源不是权威来源")
        assertFalse(noneSideIsAuthoritative(meituan, notify, none, sms))
        assertFalse(noneSideIsAuthoritative(meituan, notify, none, ""), "空来源 ⇒ 按非权威保守放行")
        assertFalse(noneSideIsAuthoritative(none, "some-other-source", meituan, notify))
    }

    @Test
    fun `a non-none authoritative side does not block auto merge`() {
        // 权威来源若自己也带了确定平台，就不是 unknown 一侧 ⇒ 本护栏不管（层级护栏另有裁决）。
        assertFalse(noneSideIsAuthoritative("wechat", manual, "bank", manual), "两侧都非 NONE ⇒ 不适用本护栏")
        assertFalse(noneSideIsAuthoritative(meituan, notify, "bank", billImport), "bank 非 NONE ⇒ 不适用")
    }

    @Test
    fun `both sides unknown counts as an authoritative none side`() {
        // NONE↔NONE 在真实链路上已被 complementaryVerdict 判 REJECT、轮不到自动合并；
        // 这里钉住函数的定义域，防止将来被误解成「只在恰好一侧 NONE 时才生效」。
        assertTrue(noneSideIsAuthoritative(none, manual, none, manual))
        assertFalse(noneSideIsAuthoritative(none, sms, none, notify))
    }

    // ------------------------------------------------------------------ 端到端（走 findDuplicates + canAutoMerge）

    @Test
    fun `a manual cash entry is surfaced as a candidate but never auto merged`() = runBlocking<Unit> {
        // QA 的反例：手工「菜市场」现金 25 元 vs 12:01 美团外卖 25 元（商户不同 ⇒ Tier-2）。
        val manualRow = txn("manual", none, manual, counterparty = "菜市场")
        val incoming = txn("mt", meituan, notify, occurredAt = anchor + 60_000L, counterparty = "美团外卖")
        val r = resolver(manualRow)

        val dups = r.findDuplicates(incoming)
        assertTrue(dups.isNotEmpty(), "前置：Tier-2 确实把它当候选（这正是要堵的缝隙入口）")
        assertEquals(MatchTier.COMPLEMENTARY, dups.first().tier)
        assertFalse(
            r.canAutoMerge(incoming, dups.first()),
            "用户手工录入的现金消费是权威数据，不得被同金额的外卖订单静默吞并",
        )
    }

    @Test
    fun `a bill-imported unknown row is surfaced as a candidate but never auto merged`() = runBlocking<Unit> {
        val billRow = txn("bill", none, billImport, counterparty = "楼下超市")
        val incoming = txn("mt", meituan, notify, occurredAt = anchor + 30_000L, counterparty = "美团外卖")
        val r = resolver(billRow)

        val dups = r.findDuplicates(incoming)
        assertTrue(dups.isNotEmpty(), "前置：Tier-2 候选存在")
        assertFalse(
            r.canAutoMerge(incoming, dups.first()),
            "账单导入的独立消费行不应被同金额的 ORDER 通知静默吞并",
        )
    }

    @Test
    fun `a manual order row is blocked no matter which side is incoming`() = runBlocking<Unit> {
        // 对称性：权威来源是 incoming 时，同样必须被挡住。
        val orderRow = txn("mt", meituan, notify, counterparty = "美团外卖")
        val manualRow = txn("manual", none, manual, occurredAt = anchor + 60_000L, counterparty = "菜市场")
        val r = resolver(orderRow)

        val dups = r.findDuplicates(manualRow)
        assertTrue(dups.isNotEmpty())
        assertFalse(r.canAutoMerge(manualRow, dups.first()), "无论哪一侧是权威来源，都不得静默合并")
    }

    @Test
    fun `an automatically captured bank-side unknown still auto merges with an order`() = runBlocking<Unit> {
        // 对照：不得因为这道护栏把「ORDER↔自动采集的 unknown 银行侧」也一起拒掉（否则功能倒退）。
        val bankSide = txn("sms-1", none, sms, counterparty = "尾号1234")
        val incoming = txn("mt", meituan, notify, occurredAt = anchor + 60_000L, counterparty = "美团外卖")
        val r = resolver(bankSide)

        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size, "ORDER↔NONE 是 Tier-2 的核心通道")
        assertEquals(MatchTier.COMPLEMENTARY, dups.first().tier)
        assertTrue(
            r.canAutoMerge(incoming, dups.first()),
            "自动抓取的 unknown 银行侧仍应能与订单自动合并（护栏只拦权威来源）",
        )
    }
}
