package com.autoledger.core.database

import androidx.room.Room
import com.autoledger.core.database.repository.RoomLedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * sync_outbox 条数上限淘汰（[SyncOutboxDao.trim] / [RoomLedgerRepository.trimOutbox]）。
 *
 * 真实 Room + 真实 SQL（Robolectric）：淘汰语义的关键在 `NOT IN (… ORDER BY … LIMIT :keep)`
 * 的 SQL 行为，内存夹具验不出来。四条用例：
 * 1. 超过上限 ⇒ 最旧的被删、最新的保留；
 * 2. **同毫秒碰撞** ⇒ 用 opId 降序 tie-break，淘汰**确定**（谁留谁删不取决于扫描顺序）；
 * 3. trim 后 outbox 仍可正常 enqueue / upsert（淘汰不是"锁死"）；
 * 4. keep ≥ 行数 ⇒ no-op（一条都不删）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxTrimTest {

    private fun open(): LedgerDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        LedgerDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun op(opId: String, createdAt: Long) = SyncOutboxEntity(
        opId = opId,
        entityType = "transaction",
        entityId = opId,
        operation = "UPSERT",
        createdAtMillis = createdAt,
    )

    private fun remaining(db: LedgerDatabase): List<String> =
        runBlocking { db.syncOutboxDao().peek(1_000).map { it.opId } }

    @Test
    fun `trim keeps only the newest N and drops the oldest`() {
        val db = open()
        runBlocking {
            // 12 条 createdAt 递增：op-01 最旧 … op-12 最新
            (1..12).forEach { i -> db.syncOutboxDao().enqueue(op("op-%02d".format(i), 1_000L + i)) }

            db.syncOutboxDao().trim(keep = 10)

            val left = remaining(db)
            assertEquals(10, left.size, "trim 后应只剩 10 条")
            // 最旧的 2 条（op-01 / op-02）被删，其余保留
            assertEquals((3..12).map { "op-%02d".format(it) }.toSet(), left.toSet())
        }
    }

    @Test
    fun `same millisecond collisions are evicted deterministically by opId desc`() {
        val db = open()
        runBlocking {
            // 12 条 createdAt 完全相同 ⇒ 靠 opId 降序 tie-break：保留 op-03…op-12，淘汰 op-01 / op-02
            (1..12).forEach { i -> db.syncOutboxDao().enqueue(op("op-%02d".format(i), 7_777L)) }

            db.syncOutboxDao().trim(keep = 10)

            val left = remaining(db).toSet()
            assertEquals(10, left.size)
            assertEquals(false, "op-01" in left, "同毫秒下 opId 最小者必须被淘汰（确定性）")
            assertEquals(false, "op-02" in left)
            assertEquals((3..12).map { "op-%02d".format(it) }.toSet(), left, "其余 10 条必须完整保留")
        }
    }

    @Test
    fun `outbox stays usable after a trim`() {
        val db = open()
        runBlocking {
            (1..12).forEach { i -> db.syncOutboxDao().enqueue(op("op-%02d".format(i), 1_000L + i)) }
            val repo = RoomLedgerRepository(db)
            repo.trimOutbox(keep = 10)
            assertEquals(10, remaining(db).size)

            // trim 之后照常写入：enqueue 一条 + upsert 一笔流水（会再 enqueue 一条 outbox op）
            db.syncOutboxDao().enqueue(op("op-after", 9_999L))
            repo.upsert(
                com.autoledger.core.model.LedgerTransaction(
                    id = "t1",
                    amountMinor = -100L,
                    occurredAtMillis = 1L,
                    type = com.autoledger.core.model.TxnType.EXPENSE,
                    counterparty = "某店",
                    sourceId = "manual",
                    sourceRef = "manual:t1",
                ),
            )
            assertEquals(12, remaining(db).size, "trim 不是锁死：之后必须能继续写入")
        }
    }

    @Test
    fun `keep greater than row count is a no-op`() {
        val db = open()
        runBlocking {
            (1..5).forEach { i -> db.syncOutboxDao().enqueue(op("op-%02d".format(i), 1_000L + i)) }

            db.syncOutboxDao().trim(keep = 10)

            assertEquals(5, remaining(db).size, "行数少于 keep 时一条都不能删")
        }
    }
}
