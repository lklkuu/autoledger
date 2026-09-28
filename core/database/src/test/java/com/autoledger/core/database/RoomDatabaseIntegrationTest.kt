package com.autoledger.core.database

import androidx.room.Room
import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 真实 Room 库的运行态测试（Robolectric，无需真机 / 模拟器）。
 *
 * 目的是补上此前「只用内存夹具验证 DAO/Flow」的缺口：这里跑的是**真实 Room + 真实 SQL**，
 * 验证 A5 修复所依赖的查询语义（observeSince 窗口、observeRawCount 与窗口无关、写后重发）。
 * 注：用明文内存库（SQLCipher 原生库无法在 JVM 上加载），故本测试不覆盖加密层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomDatabaseIntegrationTest {

    private fun open(): LedgerDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        LedgerDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun txn(id: String, occurredAt: Long, status: TxnStatus) = TransactionEntity(
        id = id,
        amountMinor = -2500L,
        currency = "CNY",
        occurredAtMillis = occurredAt,
        bookedAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        direction = Direction.OUT,
        counterparty = "星巴克",
        note = null,
        sourceId = "manual",
        sourceRef = "r:$id",
        accountId = null,
        categoryId = null,
        transferGroupId = null,
        fingerprint = "fp-$id",
        status = status,
        confidence = 1f,
        platformId = "unknown",
        platformConfidence = 0f,
        platformSource = "AUTO",
        rawTextSealed = null,
        extras = null,
        orderId = null,
        refundId = null,
        schemaVersion = 4,
    )

    @Test
    fun `raw count sql reflects inserts and status updates on a real database`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            val t0 = 1_700_000_000_000L
            dao.upsert(txn("a", t0, TxnStatus.RAW))
            assertEquals(1, dao.observeRawCount().first())
            dao.upsert(txn("b", t0 - 1000, TxnStatus.RAW))
            assertEquals(2, dao.observeRawCount().first())
            dao.updateStatus("a", TxnStatus.CONFIRMED.name)
            assertEquals(1, dao.observeRawCount().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun `observeSince keeps rows at or after the anchor and drops merged`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            val monthStart = 1_700_000_000_000L
            dao.upsert(txn("in", monthStart + 10, TxnStatus.RAW))
            dao.upsert(txn("old", monthStart - 10, TxnStatus.RAW))
            dao.upsert(txn("merged", monthStart + 20, TxnStatus.MERGED))
            assertEquals(listOf("in"), dao.listSince(monthStart).map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun `backlog insert outside the window still bumps raw count (F2 scenario, real db)`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            val t0 = 1_700_000_000_000L
            dao.upsert(txn("now", t0, TxnStatus.RAW))
            val windowBefore = dao.listSince(t0).map { it.id }
            // 补录一笔 90 天前的 RAW（落在窗口之外）
            dao.upsert(txn("backlog", t0 - 90L * 24 * 3600 * 1000, TxnStatus.RAW))
            // 窗口内集合不变 —— 这正是当初 distinctUntilChanged 会吞掉、导致待确认卡片不刷新的情形
            assertEquals(windowBefore, dao.listSince(t0).map { it.id })
            // 但全表 RAW 计数必须 +1（F2 修复所依赖的窗口无关查询）
            assertEquals(2, dao.observeRawCount().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun `ignored rows disappear from every ledger view (real db)`() = runBlocking {
        // P1：用户在待确认队列点「忽略这笔」后，该行必须从账单列表 / 时间窗口 / 订阅集合里消失。
        // 只改 observeRaw（status='RAW'）是不够的 —— listAll / listSince / listRange /
        // observeAll / observeSince 此前只排除 MERGED，被忽略的流水仍会被算进账单与统计。
        val db = open()
        try {
            val dao = db.transactionDao()
            val t0 = 1_700_000_000_000L
            dao.upsert(txn("keep", t0, TxnStatus.CONFIRMED))
            dao.upsert(txn("merged", t0 + 1, TxnStatus.MERGED))
            dao.upsert(txn("ignored", t0 + 2, TxnStatus.IGNORED))

            assertEquals(listOf("keep"), dao.listAll().map { it.id }, "被忽略的流水不得出现在账单列表")
            assertEquals(listOf("keep"), dao.listSince(t0).map { it.id })
            assertEquals(listOf("keep"), dao.listRange(t0, t0 + 100).map { it.id })
            assertEquals(listOf("keep"), dao.observeAll().first().map { it.id })
            assertEquals(listOf("keep"), dao.observeSince(t0).first().map { it.id })
            assertEquals(listOf("keep"), dao.observeRange(t0, t0 + 100).first().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun `raw count flow emits again after a write on a real database`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            val t0 = 1_700_000_000_000L
            dao.upsert(txn("a", t0, TxnStatus.RAW))
            val seen = mutableListOf<Int>()
            val job = launch { dao.observeRawCount().collect { seen.add(it) } }
            withTimeout(10_000) { while (seen.isEmpty()) delay(20) }
            dao.upsert(txn("b", t0, TxnStatus.RAW))
            withTimeout(10_000) { while (seen.lastOrNull() != 2) delay(20) }
            job.cancel()
            assertEquals(1, seen.first())
            assertEquals(2, seen.last())
        } finally {
            db.close()
        }
    }
}
