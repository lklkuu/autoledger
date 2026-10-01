package com.autoledger.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import com.autoledger.core.model.LedgerSchema
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **v5 → v6 迁移保真测试**（Robolectric + 真实 SQLite 文件库）。
 *
 * 为什么这是本批的**硬门槛**：`LedgerDatabaseFactory` 至今挂着 `fallbackToDestructiveMigration()`。
 * 只要 `MIGRATION_5_6` 漏写、或索引名与 Room 的生成规则差一个字符，
 * `exportSchema` 校验就会判 "migration didn't properly handle"，
 * Room 随即退回到破坏性重建 ⇒ **用户全部历史流水与账目被清空**。
 * 而 `assembleDebug` 通过与否**完全发现不了**这件事 —— 只有真跑一次迁移才知道。
 *
 * 因此本测试走完整链路：手工建**带真实数据**的 v5 库 → 用 `LedgerDatabaseFactory` 打开 →
 * 断言①流水一条不少②新列取默认值③新表可读写④索引名逐字符正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserPlatformMigrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "user-platform-migration-test.db"

    /**
     * 与 `schemas/5.json` 中 `createSql` 逐字一致的 v5 全量建表语句。
     *
     * 必须建**全部**表：Room 的迁移校验会逐个实体比对 TableInfo，
     * 只建 transactions / user_platforms 会因其余表缺失而报
     * "Migration didn't properly handle"，结果同样落在 destructive 分支上。
     */
    private val v5SchemaSql = listOf(
        "CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `occurredAtMillis` INTEGER NOT NULL, `bookedAtMillis` INTEGER NOT NULL, `type` TEXT NOT NULL, `direction` TEXT NOT NULL, `counterparty` TEXT NOT NULL, `platform_id` TEXT NOT NULL, `platform_confidence` REAL NOT NULL, `platform_source` TEXT NOT NULL, `note` TEXT, `sourceId` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `accountId` TEXT, `categoryId` TEXT, `transferGroupId` TEXT, `fingerprint` TEXT NOT NULL, `status` TEXT NOT NULL, `confidence` REAL NOT NULL, `rawTextSealed` TEXT, `extras` TEXT, `orderId` TEXT, `refundId` TEXT, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_transactions_occurredAtMillis` ON `transactions` (`occurredAtMillis`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_fingerprint` ON `transactions` (`fingerprint`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_type` ON `transactions` (`type`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_platform_id` ON `transactions` (`platform_id`)",
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

    /** 造一个**带真实流水数据**的 v5 库。 */
    private fun seedV5(rows: List<Triple<String, Long, String>>) {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 5) {
            override fun onCreate(db: SQLiteDatabase) {
                v5SchemaSql.forEach { db.execSQL(it) }
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = helper.writableDatabase
        for ((id, amount, counterparty) in rows) {
            db.execSQL(
                "INSERT INTO transactions (id, amountMinor, currency, occurredAtMillis, bookedAtMillis," +
                    " type, direction, counterparty, platform_id, platform_confidence, platform_source," +
                    " note, sourceId, sourceRef, accountId, categoryId, transferGroupId, fingerprint," +
                    " status, confidence, rawTextSealed, extras, orderId, refundId, schemaVersion)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    id, amount, "CNY", 1_700_000_000_000L, 1_700_000_000_000L,
                    "EXPENSE", "OUT", counterparty, "unknown", 0.0, "AUTO",
                    null, "notify", id, null, null, null, "fp-$id",
                    "CONFIRMED", 1.0, null, null, null, null, 5,
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

    private fun indexNamesOf(db: LedgerDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `every v5 row survives the migration untouched`() {
        seedV5(
            listOf(
                Triple("old-1", -2_500L, "楼下面馆"),
                Triple("old-2", -9_900L, "沃尔玛"),
                Triple("old-3", -1_200L, "便利店"),
                Triple("old-4", 5_055_00L, "工商银行"),
            ),
        )

        val db = openMigrated()
        val all = runBlocking { db.transactionDao().listAll() }

        assertEquals(4, all.size, "流水必须一条不少 —— 迁移绝不能删数据")
        assertEquals(
            setOf("old-1", "old-2", "old-3", "old-4"),
            all.map { it.id }.toSet(),
        )
        assertEquals(
            setOf(-2_500L, -9_900L, -1_200L, 505_500L),
            all.map { it.amountMinor }.toSet(),
            "金额不得被迁移改动",
        )
        assertEquals(
            setOf("楼下面馆", "沃尔玛", "便利店", "工商银行"),
            all.map { it.counterparty }.toSet(),
            "商户名不得被迁移改动",
        )
        db.close()
    }

    @Test
    fun `the new merged_into_id column defaults to null for legacy rows`() {
        seedV5(listOf(Triple("old-1", -2_500L, "楼下面馆")))

        val db = openMigrated()
        val row = runBlocking { db.transactionDao().findById("old-1") }

        assertNull(row?.mergedIntoId, "旧行未被合并 ⇒ 新列必须是 NULL（不是空串、不是 unknown）")
        db.close()
    }

    @Test
    fun `the new user_platforms table is readable and writable after the migration`() {
        seedV5(listOf(Triple("old-1", -2_500L, "楼下面馆")))

        val db = openMigrated()
        runBlocking {
            db.userPlatformDao().upsert(
                UserPlatformEntity(
                    id = "user:jd", displayName = "京东", kind = "ORDER",
                    strongKeywords = "京东支付", mediumKeywords = "京东", weakKeywords = "",
                    packageNames = "com.jingdong.app.mall",
                    sortOrder = 1000, archived = false,
                    createdAtMillis = 1_700_000_000_000L, schemaVersion = LedgerSchema.CURRENT,
                ),
            )

            assertEquals(listOf("user:jd"), db.userPlatformDao().listAll().map { it.id }, "新表可读写")
            assertEquals("京东", db.userPlatformDao().listAll().first().displayName)

            // 归档是**软删除**：不进识别候选，但行必须在（历史流水还要靠它显示原名）
            db.userPlatformDao().archive("user:jd")
            assertTrue(db.userPlatformDao().listActive().isEmpty(), "归档后不再进候选")
            assertEquals(1, db.userPlatformDao().listAll().size, "归档绝不能删行")
        }
        db.close()
    }

    @Test
    fun `merge state can be written and reverse queried after the migration`() {
        seedV5(
            listOf(
                Triple("primary", -8_800L, "美团外卖"),
                Triple("absorbed", -8_800L, "财付通"),
            ),
        )

        val db = openMigrated()
        runBlocking {
            db.transactionDao().updateMergeState("absorbed", "MERGED", "primary")

            val group = db.transactionDao().mergeGroupOf("primary")
            assertEquals(listOf("absorbed"), group.map { it.id }, "被吸收的记录必须能反查出来")
            assertEquals("primary", db.transactionDao().findById("absorbed")?.mergedIntoId)

            // 撤销合并：状态回 RAW + 溯源清空
            db.transactionDao().updateMergeState("absorbed", "RAW", null)
            assertNull(db.transactionDao().findById("absorbed")?.mergedIntoId, "撤销后溯源必须清空")
            assertTrue(db.transactionDao().mergeGroupOf("primary").isEmpty())

            // 主记录本身不在合并组里（它没被吸收）
            assertTrue(db.transactionDao().mergeGroupOf("primary").none { it.id == "primary" })
        }
        db.close()
    }

    @Test
    fun `both new index names match what Room generates`() {
        // 索引名差一个字符 ⇒ exportSchema 校验失败 ⇒ 退回 destructive ⇒ **全库清空**。
        // 这条断言是整个迁移的保险丝，不要因为"看起来太长"就删掉。
        seedV5(listOf(Triple("old-1", -2_500L, "楼下面馆")))

        val db = openMigrated()
        runBlocking { db.transactionDao().listAll() } // 触发迁移

        val txnIndexes = indexNamesOf(db, "transactions")
        assertTrue(
            txnIndexes.contains("index_transactions_merged_into_id"),
            "实得：$txnIndexes",
        )
        // 旧索引（含 v5 迁移建的平台索引）必须仍在 —— 一并回归 v4→v5 没被 v5→v6 破坏
        assertTrue(txnIndexes.contains("index_transactions_platform_id"), "实得：$txnIndexes")

        val platformIndexes = indexNamesOf(db, "user_platforms")
        assertTrue(
            platformIndexes.contains("index_user_platforms_archived"),
            "实得：$platformIndexes",
        )
        db.close()
    }

    @Test
    fun `a fresh v6 install has both the new table and the new column without running a migration`() {
        // 全新安装路径：Room 直接按 v6 建表，不走任何 Migration。
        // 它必须与「迁移出来的 v6」结构一致，否则新老用户会出现两类不同的库。
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .addMigrations(*LedgerDatabaseFactory.ALL_MIGRATIONS.toTypedArray())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val columnNames = db.openHelper.readableDatabase.query(
                "PRAGMA table_info(transactions)",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            assertTrue(columnNames.contains("merged_into_id"), "实得：$columnNames")
            assertTrue(db.userPlatformDao().listAll().isEmpty(), "新库的 user_platforms 必须存在且为空")
        }
        db.close()
    }
}
