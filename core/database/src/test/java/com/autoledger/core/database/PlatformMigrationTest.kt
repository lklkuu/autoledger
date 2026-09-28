package com.autoledger.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **真实 Room 迁移测试**（Robolectric + 真实 SQLite 文件库）。
 *
 * 为什么必须测这个：`LedgerDatabaseFactory` 至今挂着 `fallbackToDestructiveMigration()`。
 * 只要 `MIGRATION_4_5` 写得不对（尤其是索引名与 Room 的生成规则不一致，
 * `exportSchema` 校验会判 "migration didn't properly handle"），
 * Room 就会退回到破坏性重建 ⇒ **用户全部历史流水被清空**。
 * 而 `assembleDebug` 通过与否**完全发现不了**这件事 —— 只有真跑一次迁移才知道。
 *
 * 因此本测试走的是完整链路：手工建 v4 库 → 走 `LedgerDatabaseFactory` 打开 → 校验迁移结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlatformMigrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "platform-migration-test.db"

    /**
     * 与 `schemas/4.json` 逐字一致的 v4 全量建表语句（**不含**平台列）。
     *
     * 必须建**全部**表：Room 的迁移校验会逐个实体比对 TableInfo，
     * 只建 transactions 会因其余表缺失而报 "Migration didn't properly handle"。
     */
    private val v4SchemaSql = listOf(
        "CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `occurredAtMillis` INTEGER NOT NULL, `bookedAtMillis` INTEGER NOT NULL, `type` TEXT NOT NULL, `direction` TEXT NOT NULL, `counterparty` TEXT NOT NULL, `note` TEXT, `sourceId` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `accountId` TEXT, `categoryId` TEXT, `transferGroupId` TEXT, `fingerprint` TEXT NOT NULL, `status` TEXT NOT NULL, `confidence` REAL NOT NULL, `rawTextSealed` TEXT, `extras` TEXT, `orderId` TEXT, `refundId` TEXT, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_transactions_occurredAtMillis` ON `transactions` (`occurredAtMillis`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_fingerprint` ON `transactions` (`fingerprint`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_type` ON `transactions` (`type`)",
        "CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `parentId` TEXT, `sortOrder` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL, `kind` TEXT NOT NULL, `monthlyBudgetMinor` INTEGER, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `institution` TEXT, `identifierHints` TEXT NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_accounts_kind` ON `accounts` (`kind`)",
        "CREATE TABLE IF NOT EXISTS `classifier_rules` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `pattern` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `priority` INTEGER NOT NULL, `learned` INTEGER NOT NULL, `hitCount` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_classifier_rules_categoryId` ON `classifier_rules` (`categoryId`)",
        "CREATE INDEX IF NOT EXISTS `index_classifier_rules_pattern` ON `classifier_rules` (`pattern`)",
        "CREATE TABLE IF NOT EXISTS `sync_outbox` (`opId` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `operation` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`opId`))",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_entityType_entityId` ON `sync_outbox` (`entityType`, `entityId`)",
        "CREATE TABLE IF NOT EXISTS `app_settings` (`id` TEXT NOT NULL, `wageSalaryMinor` INTEGER NOT NULL, `wagePayMonths` INTEGER NOT NULL, `wageWorkCostMinor` INTEGER NOT NULL, `wageWorkDays` REAL NOT NULL, `wageOfficeHours` REAL NOT NULL, `wageCommuteMinutes` INTEGER NOT NULL, `wageOvertimeHours` REAL NOT NULL, `goalTargetMinor` INTEGER NOT NULL, `goalCushionMinor` INTEGER NOT NULL, `goalCurrentMinor` INTEGER NOT NULL, `autoMerge` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `orders` (`id` TEXT NOT NULL, `orderNo` TEXT NOT NULL, `counterparty` TEXT NOT NULL, `totalMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `occurredAtMillis` INTEGER NOT NULL, `refundDeadlineMillis` INTEGER, `status` TEXT NOT NULL, `version` INTEGER NOT NULL, `sourceId` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_orders_orderNo` ON `orders` (`orderNo`)",
        "CREATE INDEX IF NOT EXISTS `index_orders_status` ON `orders` (`status`)",
        "CREATE TABLE IF NOT EXISTS `order_deductions` (`id` TEXT NOT NULL, `orderId` TEXT NOT NULL, `kind` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `resourceId` TEXT, `resourceExpired` INTEGER NOT NULL, `resourceConsumed` INTEGER NOT NULL, `reversedAmountMinor` INTEGER NOT NULL, `reversedQuantity` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_order_deductions_orderId` ON `order_deductions` (`orderId`)",
        "CREATE TABLE IF NOT EXISTS `refunds` (`id` TEXT NOT NULL, `orderId` TEXT NOT NULL, `refundNo` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `status` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `occurredAtMillis` INTEGER NOT NULL, `attempt` INTEGER NOT NULL, `reason` TEXT, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_refunds_idempotencyKey` ON `refunds` (`idempotencyKey`)",
        "CREATE INDEX IF NOT EXISTS `index_refunds_orderId` ON `refunds` (`orderId`)",
        "CREATE TABLE IF NOT EXISTS `refund_allocations` (`id` TEXT NOT NULL, `refundId` TEXT NOT NULL, `deductionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `fallbackAmountMinor` INTEGER NOT NULL, `resourceId` TEXT, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_refund_allocations_refundId_deductionId` ON `refund_allocations` (`refundId`, `deductionId`)",
        "CREATE TABLE IF NOT EXISTS `resource_balances` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `resourceId` TEXT NOT NULL, `ownerAccountId` TEXT, `availableMinor` INTEGER NOT NULL, `availableQuantity` INTEGER NOT NULL, `expiresAtMillis` INTEGER, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_resource_balances_kind_resourceId` ON `resource_balances` (`kind`, `resourceId`)",
    )

    private fun seedV4(rows: List<Triple<String, Long, String>>) {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 4) {
            override fun onCreate(db: SQLiteDatabase) {
                v4SchemaSql.forEach { db.execSQL(it) }
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = helper.writableDatabase
        for ((id, amount, counterparty) in rows) {
            db.execSQL(
                "INSERT INTO transactions (id, amountMinor, currency, occurredAtMillis, bookedAtMillis," +
                    " type, direction, counterparty, note, sourceId, sourceRef, accountId, categoryId," +
                    " transferGroupId, fingerprint, status, confidence, rawTextSealed, extras, orderId," +
                    " refundId, schemaVersion) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    id, amount, "CNY", 1_700_000_000_000L, 1_700_000_000_000L,
                    "EXPENSE", "OUT", counterparty, null, "notify", id,
                    null, null, null, "fp-$id", "CONFIRMED", 1.0,
                    null, null, null, null, 4,
                ),
            )
        }
        db.close()
        helper.close()
    }

    private fun openMigrated(): LedgerDatabase = LedgerDatabaseFactory.create(
        context = context,
        openHelperFactory = null, // 明文：SQLCipher 原生库无法在 JVM 上加载
        databaseName = dbName,
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `v4 rows survive the migration and land on unknown platform`() {
        seedV4(
            listOf(
                Triple("old-1", -2_500L, "楼下面馆"),
                Triple("old-2", -9_900L, "沃尔玛"),
                Triple("old-3", -1_200L, "便利店"),
            ),
        )

        val db = openMigrated()
        val all = runBlocking { db.transactionDao().listAll() }

        assertEquals(3, all.size, "旧行必须全部保留 —— 迁移绝不能删数据")
        assertEquals(setOf("old-1", "old-2", "old-3"), all.map { it.id }.toSet())
        assertTrue(
            all.all { it.platformId == PlatformCatalog.UNKNOWN_ID },
            "历史数据一律落 unknown（用户口径：旧行保留，平台留未知）",
        )
        assertTrue(all.all { it.platformConfidence == 0f })
        assertTrue(all.all { it.platformSource == PlatformSource.AUTO.name })
        assertEquals(setOf("楼下面馆", "沃尔玛", "便利店"), all.map { it.counterparty }.toSet(), "商户名不得被迁移改动")
        assertEquals(setOf(-2_500L, -9_900L, -1_200L), all.map { it.amountMinor }.toSet(), "金额不得被迁移改动")
    }

    @Test
    fun `new writes after the migration can carry an explicit platform`() {
        seedV4(listOf(Triple("old-1", -2_500L, "楼下面馆")))

        val db = openMigrated()
        runBlocking {
            db.transactionDao().upsertAll(
                listOf(
                    TransactionEntity(
                        id = "new-1", amountMinor = -4_500L, currency = "CNY",
                        occurredAtMillis = 1_700_000_100_000L, bookedAtMillis = 1_700_000_100_000L,
                        type = com.autoledger.core.model.TxnType.EXPENSE,
                        direction = com.autoledger.core.model.Direction.OUT,
                        counterparty = "肯德基", platformId = "wechat",
                        platformConfidence = 0.95f, platformSource = "AUTO",
                        note = null, sourceId = "notify", sourceRef = "new-1",
                        accountId = null, categoryId = null, transferGroupId = null,
                        fingerprint = "fp-new-1",
                        status = com.autoledger.core.model.TxnStatus.CONFIRMED,
                        confidence = 1f, rawTextSealed = null, extras = null,
                        orderId = null, refundId = null, schemaVersion = LedgerSchema.CURRENT,
                    ),
                ),
            )
        }

        val fresh = runBlocking { db.transactionDao().findById("new-1") }
        assertEquals("wechat", fresh?.platformId, "新写入必须能带上明确平台")
        assertEquals(0.95f, fresh?.platformConfidence)
    }

    @Test
    fun `the platform index exists with the exact name Room generates`() {
        seedV4(listOf(Triple("old-1", -2_500L, "楼下面馆")))

        val db = openMigrated()
        // 触发一次查询，确保迁移已执行
        runBlocking { db.transactionDao().listAll() }

        val names = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'transactions'",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertTrue(
            names.contains("index_transactions_platform_id"),
            "索引名必须与 Room 对 Index([\"platform_id\"]) 的生成规则完全一致，" +
                "否则 exportSchema 校验失败 ⇒ 退回 destructive migration ⇒ 全库清空。实得：$names",
        )
    }

    @Test
    fun `assignPlatform writes USER source and is idempotent`() {
        seedV4(listOf(Triple("old-1", -2_500L, "楼下面馆")))

        val db = openMigrated()
        val repo = com.autoledger.core.database.repository.RoomLedgerRepository(db)

        runBlocking {
            repo.assignPlatform("old-1", "meituan")
            val first = db.transactionDao().findById("old-1")
            assertEquals("meituan", first?.platformId)
            assertEquals(1f, first?.platformConfidence, "用户指定 = 绝对确定")
            assertEquals("USER", first?.platformSource, "USER 是权威标记，自动流程不得再改写")

            repo.assignPlatform("old-1", "alipay")
            assertEquals("alipay", db.transactionDao().findById("old-1")?.platformId, "可反复覆盖")
            assertEquals("USER", db.transactionDao().findById("old-1")?.platformSource)
        }
    }

    @Test
    fun `a freshly created v5 database already has the platform columns`() {
        // 全新安装路径（无旧库）：Room 直接按 v5 建表，不走迁移
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .addMigrations(MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.transactionDao().upsertAll(
                listOf(
                    TransactionEntity(
                        id = "fresh-1", amountMinor = -100L, currency = "CNY",
                        occurredAtMillis = 1L, bookedAtMillis = 1L,
                        type = com.autoledger.core.model.TxnType.EXPENSE,
                        direction = com.autoledger.core.model.Direction.OUT,
                        counterparty = "测试", platformId = "pdd",
                        platformConfidence = 0.6f, platformSource = "AUTO",
                        note = null, sourceId = "manual", sourceRef = "fresh-1",
                        accountId = null, categoryId = null, transferGroupId = null,
                        fingerprint = "fp-fresh",
                        status = com.autoledger.core.model.TxnStatus.CONFIRMED,
                        confidence = 1f, rawTextSealed = null, extras = null,
                        orderId = null, refundId = null, schemaVersion = LedgerSchema.CURRENT,
                    ),
                ),
            )
            assertEquals("pdd", db.transactionDao().findById("fresh-1")?.platformId)
        }
        db.close()
    }
}
