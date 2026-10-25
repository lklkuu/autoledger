package com.autoledger.core.database

import androidx.room.Room
import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.countsInLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 独立复核（QA「3fix」）：`DUPLICATE` 在**真实 Room + 真实 SQL** 下的账本口径。
 *
 * ## 为什么必须再开一份（工程师已有 `RoomDatabaseIntegrationTest` 的一条用例）
 * 那条用例只放了 1 条 `DUPLICATE`、0 条 `RAW`，于是**测不出两件事**：
 *  ① 采集箱的「列表条数」与「角标数字」是否一致（两者是两条独立 SQL，
 *     若有人只改一条，就会出现「列表 3 条、角标 0」这种自相矛盾）；
 *  ② 去重候选查询（`findByFingerprintNear` / `findByAmountWithin`）是否也把
 *     `DUPLICATE` 挡在外面 —— 它直接决定「疑似重复会不会被再合并成另一条」。
 * 本文件补上这两块，并额外钉住「状态以 `.name` 落盘」（备份 / 迁移的前提）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DuplicateStatusLedgerScopeAuditTest {

    private fun open(): LedgerDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        LedgerDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun txn(
        id: String,
        status: TxnStatus,
        occurredAt: Long = 1_700_000_000_000L,
        amountMinor: Long = -2_500L,
        fingerprint: String = "fp-$id",
    ) = TransactionEntity(
        id = id,
        amountMinor = amountMinor,
        currency = "CNY",
        occurredAtMillis = occurredAt,
        bookedAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        direction = Direction.OUT,
        counterparty = "星巴克",
        note = null,
        sourceId = "notify",
        sourceRef = "r:$id",
        accountId = null,
        categoryId = null,
        transferGroupId = null,
        fingerprint = fingerprint,
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
    fun `duplicate stays out of every ledger view, and the review queue count matches the list`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            val t0 = 1_700_000_000_000L
            dao.upsert(txn("confirmed", TxnStatus.CONFIRMED, t0, amountMinor = -1_000L))
            dao.upsert(txn("raw-1", TxnStatus.RAW, t0 + 1_000L, amountMinor = -2_000L))
            dao.upsert(txn("dup-1", TxnStatus.DUPLICATE, t0 + 2_000L, amountMinor = -3_000L))
            dao.upsert(txn("dup-2", TxnStatus.DUPLICATE, t0 + 3_000L, amountMinor = -4_000L))
            dao.upsert(txn("merged", TxnStatus.MERGED, t0 + 4_000L, amountMinor = -5_000L))
            dao.upsert(txn("ignored", TxnStatus.IGNORED, t0 + 5_000L, amountMinor = -6_000L))

            val ledger = setOf("confirmed", "raw-1")
            assertEquals(ledger, dao.listAll().map { it.id }.toSet(), "账单列表不得出现 DUPLICATE")
            assertEquals(ledger, dao.listSince(t0).map { it.id }.toSet(), "时间窗口列表同样")
            assertEquals(ledger, dao.listRange(t0, t0 + 10_000L).map { it.id }.toSet(), "区间列表同样")
            assertEquals(ledger, dao.observeAll().first().map { it.id }.toSet(), "订阅集合同样")
            assertEquals(ledger, dao.observeSince(t0).first().map { it.id }.toSet())
            assertEquals(ledger, dao.observeRange(t0, t0 + 10_000L).first().map { it.id }.toSet())

            // 采集箱：RAW + DUPLICATE 都必须在，且**列表与角标严格一致**
            val queue = dao.observeRaw().first()
            assertEquals(
                setOf("raw-1", "dup-1", "dup-2"), queue.map { it.id }.toSet(),
                "采集箱必须同时列出 RAW 与 DUPLICATE（否则疑似重复从账单消失后又不在采集箱里）",
            )
            assertEquals(
                queue.size, dao.observeRawCount().first(),
                "角标数字必须与列表条数一致 —— 「列表 3 条、角标 0」是两条独立 SQL 漂移的典型症状",
            )
            // 排序契约：按时间倒序
            assertEquals(
                listOf("dup-2", "dup-1", "raw-1"), queue.map { it.id },
                "采集箱按 occurredAtMillis 倒序",
            )

            // 备份口径：全量读取必须包含 DUPLICATE（否则换机后疑似重复会丢）
            assertTrue(
                dao.listAllIncludingHidden().any { it.id == "dup-1" },
                "备份导出用的 listAllIncludingHidden 必须包含 DUPLICATE 行",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a duplicate row is never returned as a merge candidate`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            val t0 = 1_700_000_000_000L
            dao.upsert(txn("dup", TxnStatus.DUPLICATE, t0, amountMinor = -2_500L, fingerprint = "fp-shared"))

            assertTrue(
                dao.findByFingerprintNear("fp-shared", t0, 180_000L, "new").isEmpty(),
                "Tier-1 候选查询必须排除 DUPLICATE（它本身还在等用户拍板，不该再被当成合并目标）",
            )
            assertTrue(
                dao.findByAmountWithin(-2_500L, t0 - 180_000L, t0 + 180_000L, "new").isEmpty(),
                "Tier-2 候选查询同样必须排除 DUPLICATE",
            )

            // 对照：同一行改成 CONFIRMED 后必须能被查到（否则上面的断言可能是因为 SQL 写错了才「通过」）
            dao.updateStatus("dup", TxnStatus.CONFIRMED.name)
            assertEquals(
                listOf("dup"),
                dao.findByFingerprintNear("fp-shared", t0, 180_000L, "new").map { it.id },
                "对照用例：CONFIRMED 行必须能被候选查询命中",
            )
            assertEquals(
                listOf("dup"),
                dao.findByAmountWithin(-2_500L, t0 - 180_000L, t0 + 180_000L, "new").map { it.id },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `confirming a duplicate moves it into the ledger, ignoring drops it from the queue`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            val t0 = 1_700_000_000_000L
            dao.upsert(txn("a", TxnStatus.DUPLICATE, t0, amountMinor = -3_000L))
            dao.upsert(txn("b", TxnStatus.DUPLICATE, t0 + 1_000L, amountMinor = -4_000L))

            // 采集箱「确认入账」：DUPLICATE → CONFIRMED ⇒ 进入账单
            dao.updateStatus("a", TxnStatus.CONFIRMED.name)
            assertEquals(
                listOf("a"), dao.listAll().map { it.id },
                "点「确认入账」后必须出现在账单里（这正是期望路径，不需要另建状态机）",
            )
            assertEquals(setOf("b"), dao.observeRaw().first().map { it.id }.toSet(), "确认后应移出待确认队列")

            // 采集箱「忽略」：DUPLICATE → IGNORED ⇒ 既不在账单，也不再占待确认角标
            dao.updateStatus("b", TxnStatus.IGNORED.name)
            assertEquals(listOf("a"), dao.listAll().map { it.id }, "被忽略的疑似重复不得出现在账单")
            assertTrue(dao.observeRaw().first().isEmpty(), "被忽略后不得留在待确认队列")
            assertEquals(0, dao.observeRawCount().first(), "被忽略后角标必须归零（否则用户永远清不掉）")
        } finally {
            db.close()
        }
    }

    @Test
    fun `status is persisted by enum name so backups and migrations keep working`() = runBlocking {
        val db = open()
        try {
            val dao = db.transactionDao()
            dao.upsert(txn("dup", TxnStatus.DUPLICATE))

            // 落盘必须是枚举名（Converters 用 .name）：备份 JSON 与迁移脚本都按字符串判定。
            val stored = db.openHelper.readableDatabase
                .query("SELECT status FROM transactions WHERE id = 'dup'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst(), "行未落库")
                    cursor.getString(0)
                }
            assertEquals("DUPLICATE", stored, "状态必须以枚举名落盘（不是 ordinal）")

            // 读回必须仍是 DUPLICATE（不能被兜底成 CONFIRMED —— 那会让疑似重复直接进账单）
            assertEquals(TxnStatus.DUPLICATE, dao.listAllIncludingHidden().single().status)
            assertFalse(TxnStatus.DUPLICATE.countsInLedger, "DUPLICATE 的口径：不计入账本")
        } finally {
            db.close()
        }
    }

    @Test
    fun `the duplicate enum was appended last so no persisted ordinal shifts`() {
        // `Converters` 用 .name 落盘，但任何按 ordinal 存取的旧代码 / 旧备份都不该错位。
        assertEquals(
            listOf("RAW", "CONFIRMED", "MERGED", "IGNORED", "DUPLICATE"),
            TxnStatus.entries.map { it.name },
            "DUPLICATE 必须追加在末尾",
        )
        assertEquals(listOf(true, true, false, false, false), TxnStatus.entries.map { it.countsInLedger }, "白名单口径")
    }
}
