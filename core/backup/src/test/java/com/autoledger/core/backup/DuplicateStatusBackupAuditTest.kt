package com.autoledger.core.backup

import android.content.Context
import com.autoledger.core.database.LedgerDatabaseFactory
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 独立复核（QA「3fix」）：`DUPLICATE` 状态的**备份往返**。
 *
 * ## 为什么这条必须单独测（而不是相信「枚举加了 valueOf 就行」）
 * 备份的 `status` 是**字符串落盘 + 宽松解析**（`TxnJson.kt:75`）：
 * ```
 * status = runCatching { TxnStatus.valueOf(o.optString("status", "CONFIRMED")) }
 *     .getOrDefault(TxnStatus.CONFIRMED)
 * ```
 * 兜底是 `CONFIRMED`（**进账**）—— 恰好是最坏的方向：一条疑似重复若在导入时被兜底成
 * `CONFIRMED`，换机后它会**直接出现在账单里**，「重复记录不进账单」这条需求
 * 在那个用户身上静默失效，而账面上看不出任何异常。
 *
 * 本文件钉三件事：
 *  ① `DUPLICATE` 能原样往返（`valueOf` 认得它，没有被任何白名单过滤掉）；
 *  ② 整库导出→导入后，它仍然**不在**账单视图里、状态仍是 `DUPLICATE`；
 *  ③ 把既有兜底（未知值 ⇒ `CONFIRMED`）写成显式契约，说明为什么①不能省。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DuplicateStatusBackupAuditTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** 设备 A / 设备 B 用两个不同的库文件名：避免「删库后同名重开」在 Robolectric 上的 WAL 竞争。 */
    private val deviceADb = "dup-backup-a.db"
    private val deviceBDb = "dup-backup-b.db"

    private fun openDb(name: String) = LedgerDatabaseFactory.create(
        context = context,
        openHelperFactory = null,
        databaseName = name,
    )

    @After
    fun tearDown() {
        context.deleteDatabase(deviceADb)
        context.deleteDatabase(deviceBDb)
    }

    private fun txn(
        id: String,
        status: TxnStatus,
        occurredAt: Long = 1_700_000_000_000L,
        amountMinor: Long = -3_000L,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        counterparty = "星巴克",
        sourceId = "notify",
        sourceRef = "notify:$id",
        status = status,
        fingerprint = "fp-$id",
        platformId = "unknown",
    )

    @Test
    fun `a duplicate row survives export and import without being promoted into the ledger`() = runBlocking {
        val db1 = openDb(deviceADb)
        val repo1 = RoomLedgerRepository(db1)
        repo1.upsert(txn("confirmed", TxnStatus.CONFIRMED, amountMinor = -1_000L))
        repo1.upsert(txn("dup", TxnStatus.DUPLICATE, occurredAt = 1_700_000_002_000L))
        repo1.upsert(txn("raw", TxnStatus.RAW, occurredAt = 1_700_000_004_000L))
        // 前置：设备 A 上疑似重复不在账单里
        assertEquals(setOf("confirmed", "raw"), repo1.listAll(includeTransfers = true).map { it.id }.toSet())

        val backup = BackupManager(db1, repo1).exportJson(appVersion = "test", device = "device-a")
        db1.close()

        // 换机：全新设备（另一个库文件）
        val db2 = openDb(deviceBDb)
        val repo2 = RoomLedgerRepository(db2)
        val outcome = BackupManager(db2, repo2).import(backup, BackupManager.MergeStrategy.REPLACE_ALL)
        assertEquals(3, outcome.transactionsUpserted, "三条流水都必须搬走（含疑似重复）")

        assertEquals(
            TxnStatus.DUPLICATE, repo2.findById("dup")?.status,
            "★ DUPLICATE 必须原样往返。若被兜底成 CONFIRMED，这条疑似重复会直接出现在账单里 —— " +
                "「疑似重复不进账单」在换机用户身上会静默失效",
        )
        assertEquals(TxnStatus.RAW, repo2.findById("raw")?.status)
        assertNull(repo2.findById("dup")?.mergedIntoId, "DUPLICATE 不应带有合并溯源")

        // 口径跨设备一致：账单看不见它
        assertEquals(
            setOf("confirmed", "raw"), repo2.listAll(includeTransfers = true).map { it.id }.toSet(),
            "导入后 DUPLICATE 不得出现在账单列表",
        )
        assertEquals(
            setOf("confirmed", "raw"),
            repo2.listRange(1_600_000_000_000L, 1_800_000_000_000L, includeTransfers = true).map { it.id }.toSet(),
            "发现页 / 自由基金走的 listRange 同样不得包含它",
        )

        db2.close()
    }

    @Test
    fun `the json codec round trips DUPLICATE but still falls back to CONFIRMED for unknown values`() {
        // ① 正常往返：DUPLICATE 原样保留
        val json = JSONArray().put(txn("dup", TxnStatus.DUPLICATE).toJson())
        assertEquals(
            TxnStatus.DUPLICATE, json.toTransactions().single().status,
            "备份 JSON 编解码必须认得 DUPLICATE（否则整库导出/导入会把它降级）",
        )

        // ② 既有兜底：未知值 ⇒ CONFIRMED（进账）。把这条钉成显式契约 ——
        //    它正是「新增任何枚举都必须同时验证往返」的原因：
        //    漏了往返，新状态就会被这条兜底静默升级成「已确认」。
        val dirty = JSONArray().put(txn("dup", TxnStatus.DUPLICATE).toJson().put("status", "NOT_A_REAL_STATUS"))
        assertEquals(
            TxnStatus.CONFIRMED, dirty.toTransactions().single().status,
            "脏状态兜底为 CONFIRMED（既有契约，本批未改动）",
        )

        // ③ 缺失字段同样兜底为 CONFIRMED
        val missing = JSONArray().put(txn("dup", TxnStatus.DUPLICATE).toJson().apply { remove("status") })
        assertEquals(
            TxnStatus.CONFIRMED, missing.toTransactions().single().status,
            "字段缺失兜底为 CONFIRMED（旧档案兼容路径）",
        )
    }
}
