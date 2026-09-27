package com.autoledger.feature.stats

import com.autoledger.core.model.TxnStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * R7 契约测试：feature:stats 的内存夹具也必须实现新增的 observeSince / observeRawCount / inTransaction，
 * 否则测试源集无法编译。这里把该夹具的行为也钉一遍（与 feature:dedup 侧同源）。
 */
class RepositoryFlowContractTest {

    @Test
    fun observeSince_and_observeRawCount_respectStatusAndWindow() = runBlocking {
        val raw = Fixtures.txn("raw", -1500L, occurredAtMillis = 100).copy(status = TxnStatus.RAW)
        val confirmed = Fixtures.txn("ok", -200L, occurredAtMillis = 200)
        val repo = FakeLedgerRepository(listOf(raw, confirmed))

        assertEquals(2, repo.observeSince(0L).first().size) // 左边界（>= 0）覆盖两笔
        assertEquals(0, repo.observeSince(300L).first().size) // 越界后为空
        assertEquals(1, repo.observeRawCount().first()) // 只有 raw 计入待确认
    }

    @Test
    fun inTransaction_executesBlock() = runBlocking {
        val repo = FakeLedgerRepository()
        val value = repo.inTransaction {
            repo.upsert(Fixtures.txn("x", -100L, occurredAtMillis = 1))
            42
        }
        assertEquals(42, value)
        assertEquals(1, repo.listAll(includeTransfers = true).size)
    }
}