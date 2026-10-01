package com.autoledger.core.backup

import android.content.Context
import com.autoledger.core.database.LedgerDatabaseFactory
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * QA 独立复验（必修②）：**MERGED / IGNORED 行必须进备份，且导入后不得混进展示路径**。
 *
 * 独立于工程师的 `MergedRowsBackupTest`：本文件额外断言
 *  ① 两轮「导出→导入」幂等（合并链不会在第二次换机时丢失）；
 *  ② 隐藏行导入后**不泄漏进 `listRange`**（发现页/自由基金走的也是这条）；
 *  ③ `listAllForBackup()` 只读隐藏行、`listAll()` 只读可见行，两者互补。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MergedRowsBackupAuditTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "merged-rows-audit.db"

    private fun openDb() = LedgerDatabaseFactory.create(
        context = context,
        openHelperFactory = null,
        databaseName = dbName,
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun txn(
        id: String,
        amountMinor: Long,
        counterparty: String,
        status: TxnStatus,
        sourceId: String = "notify",
        platformId: String = "unknown",
        mergedIntoId: String? = null,
        occurredAt: Long = 1_700_000_000_000L,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = "fp-$id",
        platformId = platformId,
        mergedIntoId = mergedIntoId,
    )

    @Test
    fun `merge chain and ignored decision survive two device swaps, hidden rows never leak`() = runBlocking {
        val db1 = openDb()
        val repo1 = RoomLedgerRepository(db1)
        repo1.upsert(txn("primary", -8_800L, "美团外卖", TxnStatus.CONFIRMED, platformId = "meituan"))
        repo1.upsert(txn("absorbed-wx", -8_800L, "财付通", TxnStatus.MERGED, sourceId = "notify_wechat", platformId = "wechat", mergedIntoId = "primary"))
        repo1.upsert(txn("absorbed-bank", -8_800L, "工商银行", TxnStatus.MERGED, sourceId = "sms", platformId = "bank", mergedIntoId = "primary"))
        repo1.upsert(txn("ignored", -1_200L, "某店", TxnStatus.IGNORED, sourceId = "sms"))
        repo1.upsert(txn("normal", -3_000L, "便利店", TxnStatus.CONFIRMED, occurredAt = 1_700_000_100_000L))

        // 展示路径前置：只有 2 条可见
        assertEquals(setOf("primary", "normal"), repo1.listAll(includeTransfers = true).map { it.id }.toSet())
        // 备份路径前置：5 条全在（含 2 个 MERGED + 1 个 IGNORED）
        assertEquals(5, repo1.listAllForBackup().size, "listAllForBackup 必须包含隐藏行")
        assertEquals(
            setOf("primary", "absorbed-wx", "absorbed-bank", "ignored", "normal"),
            repo1.listAllForBackup().map { it.id }.toSet(),
        )

        val backup1 = BackupManager(db1, repo1).exportJson(appVersion = "test", device = "device-a")
        db1.close()

        // 换机①：全新设备
        context.deleteDatabase(dbName)
        val db2 = openDb()
        val repo2 = RoomLedgerRepository(db2)
        val outcome1 = BackupManager(db2, repo2).import(backup1, BackupManager.MergeStrategy.REPLACE_ALL)
        assertEquals(5, outcome1.transactionsUpserted, "隐藏行（2 MERGED + 1 IGNORED）也要一起搬走")

        // 合并链完整可查
        assertEquals("primary", repo2.findById("absorbed-wx")?.mergedIntoId)
        assertEquals("primary", repo2.findById("absorbed-bank")?.mergedIntoId)
        assertEquals(TxnStatus.MERGED, repo2.findById("absorbed-wx")?.status)
        assertEquals(setOf("absorbed-wx", "absorbed-bank"), repo2.mergeGroupOf("primary").map { it.id }.toSet())
        // 用户「忽略」的决定保留
        assertEquals(TxnStatus.IGNORED, repo2.findById("ignored")?.status)
        // 隐藏行不得混进任何展示路径
        assertEquals(setOf("primary", "normal"), repo2.listAll(includeTransfers = true).map { it.id }.toSet())
        assertEquals(
            setOf("primary", "normal"),
            repo2.listRange(1_600_000_000_000L, 1_800_000_000_000L, includeTransfers = true).map { it.id }.toSet(),
            "listRange（发现页/自由基金）也必须排除隐藏行",
        )
        assertNotNull(repo2.findById("absorbed-wx"))

        // 换机②：再导一次 ⇒ 合并链不得退化（防止「导入把 MERGED 还原成 CONFIRMED」这类漂移）
        val backup2 = BackupManager(db2, repo2).exportJson(appVersion = "test", device = "device-b")
        db2.close()
        context.deleteDatabase(dbName)
        val db3 = openDb()
        val repo3 = RoomLedgerRepository(db3)
        val outcome2 = BackupManager(db3, repo3).import(backup2, BackupManager.MergeStrategy.REPLACE_ALL)
        assertEquals(5, outcome2.transactionsUpserted, "第二次换机仍应搬 5 条")
        assertEquals("primary", repo3.findById("absorbed-bank")?.mergedIntoId)
        assertEquals(TxnStatus.MERGED, repo3.findById("absorbed-bank")?.status)
        assertEquals(TxnStatus.IGNORED, repo3.findById("ignored")?.status)
        assertEquals(setOf("primary", "normal"), repo3.listAll(includeTransfers = true).map { it.id }.toSet())

        db3.close()
    }
}
