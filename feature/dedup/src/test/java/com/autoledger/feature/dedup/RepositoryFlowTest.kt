package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 针对新增仓储契约 `observeSince` / `observeRawCount` / `inTransaction` 的回归测试。
 *
 * 这些语义是首页「实时刷新」的地基：
 * - observeSince 写后必须重发（Room 由 InvalidationTracker 驱动，夹具用变更计数模拟）；
 * - observeSince 只有左边界、且排除 MERGED；
 * - observeRawCount 与时间窗口**解耦** —— 这是 R3 的核心（补录更早日期流水也要刷新待确认卡片）。
 */
class RepositoryFlowTest {

    private fun txn(
        id: String,
        occurredAt: Long,
        status: TxnStatus = TxnStatus.CONFIRMED,
        type: TxnType = TxnType.EXPENSE,
        amountMinor: Long = -1000L,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = type,
        counterparty = "某商户",
        sourceId = "notify",
        sourceRef = "notify:$id",
        status = status,
    )

    @Test
    fun observeSince_reemitsOnWrite_andExcludesMerged() = runBlocking {
        val repo = FakeLedgerRepository()
        assertEquals(0, repo.observeSince(0L).first().size)

        repo.upsert(txn("a", occurredAt = 1000))
        assertEquals(1, repo.observeSince(0L).first().size)

        // 被合并掉的流水不应再出现在订阅集合里（对齐 Room 的 `status <> 'MERGED'`）。
        repo.markStatus("a", TxnStatus.MERGED)
        assertEquals(0, repo.observeSince(0L).first().size)
    }

    @Test
    fun observeSince_hasLowerBoundOnly_noRightBoundary() = runBlocking {
        val repo = FakeLedgerRepository()
        repo.upsert(txn("future", occurredAt = 5000))

        assertEquals(0, repo.observeSince(6000L).first().size) // 左边界生效
        assertEquals(1, repo.observeSince(0L).first().size)    // 没有右边界：>= 左界一律返回
    }

    @Test
    fun observeRawCount_isWindowIndependent_soBacklogInsertStillRefreshes_R3() = runBlocking {
        val monthStart = 1_000_000L
        val repo = FakeLedgerRepository()
        repo.upsert(txn("inMonth", occurredAt = monthStart + 100, status = TxnStatus.RAW))
        assertEquals(1, repo.observeRawCount().first())

        val windowBefore = repo.observeSince(monthStart).first()
        // 补录一笔「上个月」的待确认流水：本月窗口集合不变……
        repo.upsert(txn("lastMonth", occurredAt = monthStart - 10_000, status = TxnStatus.RAW))
        val windowAfter = repo.observeSince(monthStart).first()

        assertEquals(windowBefore, windowAfter, "observeSince 的本月窗口不应因补录旧流水而变化")
        // ……但全表待确认数量必须变化，否则「待确认」卡片永远不刷新（R3 的失效点）。
        assertEquals(2, repo.observeRawCount().first())
    }

    @Test
    fun observeRawCount_countsOnlyRaw() = runBlocking {
        val repo = FakeLedgerRepository()
        repo.upsert(txn("raw", occurredAt = 1, status = TxnStatus.RAW))
        repo.upsert(txn("confirmed", occurredAt = 2, status = TxnStatus.CONFIRMED))
        assertEquals(1, repo.observeRawCount().first())
    }

    @Test
    fun inTransaction_executesBlock_andReturnsValue() = runBlocking {
        val repo = FakeLedgerRepository()
        val result = repo.inTransaction {
            repo.upsert(txn("x", occurredAt = 1))
            repo.upsert(txn("y", occurredAt = 2))
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, repo.snapshot().size)
    }
}