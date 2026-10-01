package com.autoledger.core.backup

import android.content.Context
import com.autoledger.core.database.LedgerDatabaseFactory
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 必修② 的护栏：**被合并（`MERGED`）与被忽略（`IGNORED`）的行必须进备份**，
 * 换机导入后合并链完整可查、被忽略的决定原样保留。
 *
 * ## 为什么必须跑到真实 Room 这一层
 * 缺陷出在**查询**上而不是 JSON 编解码上：导出原先走 `repo.listAll(true)`，
 * 而它的 SQL 是 `WHERE status <> 'MERGED' AND status <> 'IGNORED'` ⇒ 被吸收的行根本不导，
 * 导出 JSON 里 `mergedIntoId` 恒为 null。纯 JSON 的 `BackupCompatTest` 测不出这件事
 * （它直接喂 JSON），只有串起「真实库 → 导出 → 换机导入 → 真实库」才验证得到。
 *
 * 必须跑 Robolectric 的另一个理由：`org.json` / `android.util.Base64` 在纯 JVM 的
 * android.jar 里是 stub，不跑真实实现的话备份编解码全是空操作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MergedRowsBackupTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "merged-rows-backup.db"

    private fun openDb() = LedgerDatabaseFactory.create(
        context = context,
        openHelperFactory = null, // 明文：SQLCipher 原生库无法在 JVM 上加载
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
        mergedIntoId: String? = null,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = 1_700_000_000_000L,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = "fp-$id",
        mergedIntoId = mergedIntoId,
    )

    @Test
    fun `merged and ignored rows round trip so the merge chain survives a device swap`() = runBlocking {
        val db1 = openDb()
        val repo1 = RoomLedgerRepository(db1)
        // 主记录 + 被吸收的两条（微信、银行卡）+ 一条被用户主动忽略的
        repo1.upsert(txn("primary", -8_800L, "美团外卖", TxnStatus.CONFIRMED))
        repo1.upsert(txn("absorbed-wx", -8_800L, "财付通", TxnStatus.MERGED, sourceId = "notify_wechat", mergedIntoId = "primary"))
        repo1.upsert(txn("absorbed-bank", -8_800L, "工商银行", TxnStatus.MERGED, sourceId = "sms", mergedIntoId = "primary"))
        repo1.upsert(txn("ignored", -1_200L, "某店", TxnStatus.IGNORED, sourceId = "sms"))

        val backup = BackupManager(db1, repo1).exportJson(appVersion = "test", device = "device-a")
        db1.close()

        // 换机：全新设备（库文件删掉重开）
        context.deleteDatabase(dbName)
        val db2 = openDb()
        val repo2 = RoomLedgerRepository(db2)
        val outcome = BackupManager(db2, repo2).import(backup, BackupManager.MergeStrategy.REPLACE_ALL)

        assertEquals(4, outcome.transactionsUpserted, "被合并 / 被忽略的行都要一起搬走，不能只搬 1 条")
        // 合并链完整：这笔「分别来自微信和银行卡」查得到、撤销得了
        assertEquals("primary", repo2.findById("absorbed-wx")?.mergedIntoId)
        assertEquals("primary", repo2.findById("absorbed-bank")?.mergedIntoId)
        assertEquals(TxnStatus.MERGED, repo2.findById("absorbed-wx")?.status)
        assertEquals(2, repo2.mergeGroupOf("primary").size, "合并组两条都在")
        // 用户「忽略这笔」的决定换机后必须保留
        assertEquals(TxnStatus.IGNORED, repo2.findById("ignored")?.status)
        // 展示侧仍只看到主记录一条（隐藏行不得混进账单 / 统计）
        assertEquals(
            listOf("primary"),
            repo2.listAll(includeTransfers = true).map { it.id },
            "被合并 / 被忽略的行虽然导入了，但不得再出现在账单查询里",
        )

        db2.close()
    }
}
