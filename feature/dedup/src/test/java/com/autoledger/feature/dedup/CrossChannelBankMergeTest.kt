package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 用户实测 Bug 2：**同一笔交易在「银行短信」与「动账通知」两个渠道各入一条，没有合并**。
 *
 * 复现：工行定期到期入账 5,055 元，
 *  - `sourceId = "sms"`    （银行短信）
 *  - `sourceId = "notify"` （工行 App 动账通知）
 * 两条金额相同、时间相差几十秒，却各自独立入账。
 *
 * 根因（两条叠加）：
 *  1. 解析侧没有为银行收入抽出商户/银行名 → `counterparty` 为空；
 *  2. `LedgerDuplicateResolver.fingerprintOf` 在商户为空时退化成
 *     `金额|blank|sourceId|sourceRef`，**跨渠道必然不等**；
 *  3. `isAutoMergeSafe` 又要求商户非空 → 即使指纹相同也不会自动合并。
 *
 * 修复方向：解析侧为银行收入抽出**归一化银行名**（见 BankIncomeDirectionTest），
 * 指纹回到 `金额|归一化实体` 主路径，跨渠道即可命中并自动合并。
 * 本文件锁住「同金额同时间、不同 sourceId 的两条必须合并为一条」这条端到端契约。
 */
class CrossChannelBankMergeTest {

    private val anchor = 1_700_000_000_000L

    private fun txn(
        id: String,
        sourceId: String,
        occurredAt: Long = anchor,
        amountMinor: Long = 505_500L,
        counterparty: String = "工商银行",
        status: TxnStatus = TxnStatus.RAW,
        fingerprint: String = "",
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = TxnType.INCOME,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = fingerprint,
    )

    private fun resolver(vararg existing: LedgerTransaction): Pair<LedgerDuplicateResolver, FakeLedgerRepository> {
        val repo = FakeLedgerRepository(existing.toList())
        return LedgerDuplicateResolver(repo) to repo
    }

    // ------------------------------------------------------------------ 指纹

    @Test
    fun `bank income from sms and from notification share one fingerprint`() {
        val (r, _) = resolver()
        val fromSms = txn("sms-1", sourceId = "sms", occurredAt = anchor)
        val fromApp = txn("notify-1", sourceId = "notify", occurredAt = anchor + 20_000L)
        assertEquals(
            r.fingerprintOf(fromSms),
            r.fingerprintOf(fromApp),
            "同金额 + 同一银行名：两个渠道必须算出同一个指纹（时间**不**进指纹）",
        )
    }

    @Test
    fun `bank income fingerprint differs when the amount differs`() {
        val (r, _) = resolver()
        assertNotEquals(
            r.fingerprintOf(txn("a", sourceId = "sms", amountMinor = 505_500L)),
            r.fingerprintOf(txn("b", sourceId = "notify", amountMinor = 605_605L)),
            "金额不同绝不能合并",
        )
    }

    // ------------------------------------------------------------------ 自动合并前置条件

    @Test
    fun `bank income with a bank name is auto-merge safe`() {
        val (r, _) = resolver()
        assertTrue(
            r.isAutoMergeSafe(txn("sms-1", sourceId = "sms")),
            "抽到银行名后，跨渠道自动合并的安全条件必须成立",
        )
        assertTrue(r.isAutoMergeSafe(txn("notify-1", sourceId = "notify")))
    }

    @Test
    fun `blank counterparty still refuses auto merge`() {
        // 反向护栏：解析抽不到实体时，仍然不得自动合并（宁可进待确认，也不能吞掉真实流水）。
        val (r, _) = resolver()
        assertFalse(
            r.isAutoMergeSafe(txn("sms-1", sourceId = "sms", counterparty = "")),
            "商户为空时不得自动合并",
        )
    }

    // ------------------------------------------------------------------ 端到端：两条合成一条

    @Test
    fun `the cross channel twin is found as a duplicate and merged into one`() = runBlocking {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())

        // ① 银行短信先入库
        val first = txn("sms-1", sourceId = "sms", occurredAt = anchor).let {
            it.copy(fingerprint = probe.fingerprintOf(it))
        }
        // ② 几十秒后工行 App 动账通知到达（不同 sourceId、不同 sourceRef）
        val second = txn("notify-1", sourceId = "notify", occurredAt = anchor + 35_000L).let {
            it.copy(fingerprint = probe.fingerprintOf(it))
        }

        val (r, repo) = resolver(first)
        // IngestPipeline 的实际顺序：先 upsert 新记录，再判重，再合并。
        repo.upsert(second)
        val dups = r.findDuplicates(second)

        assertEquals(1, dups.size, "必须找到那一条跨渠道重复，实际=${dups.size}")
        assertEquals("sms-1", dups.first().txnId)
        assertTrue(dups.first().crossSource, "sms 与 notify 属于跨渠道")

        // ③ 合并：重复的被标记 MERGED，保留的那条置 CONFIRMED
        r.merge(dups.first().txnId, listOf(second.id))

        val snapshot = repo.snapshot()
        assertEquals(2, snapshot.size, "两条记录都还在库里，只是状态不同")
        assertEquals(1, snapshot.count { it.status != TxnStatus.MERGED }, "合并后只剩一条有效流水")
        assertEquals(TxnStatus.CONFIRMED, repo.findById("sms-1")!!.status)
        assertEquals(TxnStatus.MERGED, repo.findById("notify-1")!!.status)
    }

    @Test
    fun `two genuinely different bank incomes of the same amount outside the window are not merged`() = runBlocking {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val early = txn("sms-1", sourceId = "sms", occurredAt = anchor - 30 * 60 * 1000L).let {
            it.copy(fingerprint = probe.fingerprintOf(it))
        }
        val late = txn("notify-1", sourceId = "notify", occurredAt = anchor).let {
            it.copy(fingerprint = probe.fingerprintOf(it))
        }
        val (r, _) = resolver(early)
        assertTrue(
            r.findDuplicates(late).isEmpty(),
            "超出 3 分钟窗口的同金额流水不得判重（可能是两笔真实入账）",
        )
    }
}
