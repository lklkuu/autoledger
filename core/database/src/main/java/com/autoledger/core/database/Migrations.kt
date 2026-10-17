package com.autoledger.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.platform.PlatformCatalog

/**
 * Room 构建工厂。
 *
 * 历史策略：**结构变更即重建**（`fallbackToDestructiveMigration`）——
 * schema 版本不匹配时直接删表重建，省去逐版本手写 Migration。
 *
 * ⚠️ 该策略在 v5 已**终止**：用户在待确认队列里点「忽略这笔」的流水、以及所有历史账目，
 * 都不该因为一次字段新增而消失。从 v5 起每个结构变更都必须提供显式 Migration，
 * `fallbackToDestructiveMigration()` 仅作为**最后的兜底**保留（正常路径不会再走到）。
 */

/**
 * 消费平台字段落地。
 *
 * 三列均为 NOT NULL + 非空默认值 ⇒ **旧行原样保留，平台列落 `unknown`**
 * （用户口径：「历史数据留未知即可」= 旧行保留，不是把旧数据删光）。
 *
 * 索引名 `index_transactions_platform_id` 必须与 Room 对
 * `Index(value = ["platform_id"])` 的生成规则**完全一致**，
 * 否则 `exportSchema` 校验会报 "migration didn't properly handle"，
 * 进而退回到 destructive fallback ⇒ **全库流水被清空**。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE transactions ADD COLUMN platform_id TEXT NOT NULL DEFAULT '${PlatformCatalog.UNKNOWN_ID}'",
        )
        db.execSQL(
            "ALTER TABLE transactions ADD COLUMN platform_confidence REAL NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE transactions ADD COLUMN platform_source TEXT NOT NULL DEFAULT 'AUTO'",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_transactions_platform_id ON transactions (platform_id)",
        )
    }
}

/**
 * v5 → v6：用户自定义平台表 + 合并溯源列。
 *
 * ⚠️ **本文件是本次改造的最高风险点**（设计文档 §2.4 / R1）：
 * 版本号从 5 推进到 6 而漏写本 Migration（或索引名与 Room 生成规则不一致），
 * Room 找不到迁移路径 ⇒ 触发 `fallbackToDestructiveMigration` ⇒ **全库流水与账目被清空**。
 * 而 `assembleDebug` 通过与否**完全发现不了**这件事，只有真跑一次迁移才知道
 * （见 `UserPlatformMigrationTest` 的 v5→v6 保真测试）。
 *
 * 两条硬性约束：
 * 1. 索引名必须逐字符等于 Room 对 `Index(value = ["..."])` 的生成规则
 *    （`index_<表名>_<列名>`，多列用 `_` 连接）；
 * 2. 建表语句必须与 KSP 导出的 `schemas/6.json` 中 `createSql` **完全一致**，
 *    包括列顺序、NOT NULL、主键位置 —— Room 的 TableInfo 校验会逐项比对。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ① 用户自定义平台表（列顺序与 UserPlatformEntity 的声明顺序一致）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `user_platforms` (
                `id` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `strongKeywords` TEXT NOT NULL,
                `mediumKeywords` TEXT NOT NULL,
                `weakKeywords` TEXT NOT NULL,
                `packageNames` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `archived` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_user_platforms_archived` ON `user_platforms` (`archived`)",
        )

        // ② 合并溯源列。可空 ⇒ 无需 DEFAULT；旧行原样保留，`merged_into_id` 为 NULL（= 未被合并）。
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `merged_into_id` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transactions_merged_into_id` ON `transactions` (`merged_into_id`)",
        )
    }
}

/**
 * v6 → v7：AI 判定配置四列（`app_settings` 单行表）。
 *
 * 四列全部 NOT NULL + 非空默认值 ⇒ **旧行原样保留**（用户口径与 MIGRATION_4_5 一致：
 * 升级只是把「AI 关」的默认状态补上，不是把老设置改坏）。
 *
 * ⚠️ 列名必须是 **camelCase 的 `aiEnabled` / `aiMode` / `aiEndpoint` / `aiModel`**。
 * 本项目的 Room **没有开启** camelCase→snake_case 转换：`app_settings` 既有列全是
 * `wageSalaryMinor` 这种原样列名，只有显式写了 `@ColumnInfo(name = "...")` 的字段
 * （如 `transactions.platform_id`）才是 snake_case。写成 `ai_enabled` 会让
 * `exportSchema` 校验报 "migration didn't properly handle" ⇒ 退回
 * `fallbackToDestructiveMigration` ⇒ **全库流水清空**。
 * （这条不是推断：`AiSettingsMigrationTest` 第一版就踩了，12 个迁移测试一起红。）
 *
 * 本段只加列、不建索引、不改既有列；漏掉整段（`ALL_MIGRATIONS` 没登记）后果同样是清库，
 * 务必与 [LedgerSchema.DATABASE_VERSION] 同步推进。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `aiEnabled` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `aiMode` TEXT NOT NULL DEFAULT 'FALLBACK'")
        db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `aiEndpoint` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `aiModel` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v7 → v8：时薪按月版本表 `wage_versions` + 用 `app_settings` 里现存的时薪播种一行。
 *
 * ⚠️ 与 MIGRATION_5_6 / 6_7 同级的**清库级**风险点：
 * 漏登记 `ALL_MIGRATIONS`、建表语句与 `schemas/8.json` 的 `createSql` 不一致、
 * 列名大小写写错，都会让 Room 判 "migration didn't properly handle"
 * ⇒ `fallbackToDestructiveMigration` ⇒ **全库清空**。
 * 只有 `WageVersionMigrationTest`（Robolectric 真跑迁移）能发现，`assembleDebug` 不能。
 *
 * ## 播种规则（为什么是"最早流水所在月"）
 * - `month` = 账本**最早一笔流水**所在月；账本为空 ⇒ 当月。
 *   不选 `1970-01` 的原因：功能上等价（`WageLedger.effectiveVersion` 已保证更早月份
 *   会回落到最早版本、不会被清零），但 `1970-01` 会在 UI 版本列表里出现一条荒谬的
 *   「1970-01 起生效」，用户第一反应是"数据坏了"。
 * - `localtime` 修饰符与 App 业务时区（`ZoneId.systemDefault()`）一致，
 *   不会因 UTC 偏移把月份错切到上个月。
 * - **没有 `app_settings` 行 ⇒ 不播种（0 行）**：`app_settings` 只在用户**首次修改设置**
 *   时才写入（`UserSettings.init()` 只读不写），因此"全新安装 / 从未进过时薪页"的用户
 *   升级后没有版本行 —— 收入按 0 展示 + 提示「未设置时薪」，绝不凭空编造收入。
 *
 * ## 两处易踩点
 * 1. `MIN(occurredAtMillis)` 在**空表**上返回 `NULL`（聚合函数对空集返回一行 NULL），
 *    必须由 `COALESCE` 兜到当月 —— 不能依赖"表非空"。
 * 2. `CAST(ROUND(…) AS INTEGER)` 必须与 Kotlin `WageProfile.effectiveMonthlySalaryMinor`
 *    的 `roundToLong()` 给出同一结果：两者都是"四舍五入、.5 远离零"。
 *    `WageVersionMigrationTest` 用同一组数值双向断言（整除 / 非整除 / .5 边界）。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ① 时薪版本表。列顺序 = WageVersionEntity 的声明顺序（Room 的 TableInfo 校验会逐项比对）。
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `wage_versions` (
                `month` TEXT NOT NULL,
                `monthlyNetSalaryMinor` INTEGER NOT NULL,
                `payMonthsPerYear` INTEGER NOT NULL,
                `monthlyWorkCostMinor` INTEGER NOT NULL,
                `workDaysPerMonth` REAL NOT NULL,
                `dailyOfficeHours` REAL NOT NULL,
                `dailyCommuteMinutes` INTEGER NOT NULL,
                `dailyOvertimeHours` REAL NOT NULL,
                `monthlyIncomeMinor` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                PRIMARY KEY(`month`)
            )
            """.trimIndent(),
        )

        // ② 播种一行（没有 app_settings 行时这条 SELECT 无结果 ⇒ 0 行，符合预期）。
        //    `schemaVersion` 用 LedgerSchema.CURRENT 而不是硬编码数字：它是**行级**结构标记，
        //    与 transactions / user_platforms 用同一个常量，避免同表出现两种取值。
        db.execSQL(
            """
            INSERT INTO `wage_versions` (
                `month`, `monthlyNetSalaryMinor`, `payMonthsPerYear`, `monthlyWorkCostMinor`,
                `workDaysPerMonth`, `dailyOfficeHours`, `dailyCommuteMinutes`, `dailyOvertimeHours`,
                `monthlyIncomeMinor`, `createdAtMillis`, `updatedAtMillis`, `schemaVersion`
            )
            SELECT
                COALESCE(
                    strftime('%Y-%m', (SELECT MIN(`occurredAtMillis`) FROM `transactions`) / 1000,
                             'unixepoch', 'localtime'),
                    strftime('%Y-%m', 'now', 'localtime')
                ),
                s.`wageSalaryMinor`, s.`wagePayMonths`, s.`wageWorkCostMinor`,
                s.`wageWorkDays`, s.`wageOfficeHours`, s.`wageCommuteMinutes`, s.`wageOvertimeHours`,
                CAST(ROUND(s.`wageSalaryMinor` * s.`wagePayMonths` / 12.0) AS INTEGER),
                0, 0, ${LedgerSchema.CURRENT}
            FROM `app_settings` s
            WHERE s.`id` = 'global'
            """.trimIndent(),
        )
    }
}

object LedgerDatabaseFactory {

    /** 全部显式迁移。**每次推进 [LedgerSchema.DATABASE_VERSION] 都必须在这里补齐对应的一段。** */
    val ALL_MIGRATIONS: List<Migration> = listOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

    /**
     * @param openHelperFactory SQLCipher 工厂；传 null 表示降级为明文库（仅当用户在设置里明确允许）
     */
    fun create(
        context: Context,
        openHelperFactory: SupportSQLiteOpenHelper.Factory?,
        databaseName: String = LedgerDatabase.DATABASE_NAME,
    ): LedgerDatabase = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName)
        .apply { if (openHelperFactory != null) openHelperFactory(openHelperFactory) }
        // 显式迁移优先于破坏性回退；版本号与 [LedgerSchema.DATABASE_VERSION] 必须同步推进。
        // 用 ALL_MIGRATIONS 而不是逐个 addMigrations：漏加一段就是「清库」级事故，
        // 集中成一个列表后能一眼看出链是否连续（4→5、5→6…）。
        .addMigrations(*ALL_MIGRATIONS.toTypedArray())
        // 兜底保留，但正常路径不会再走到（任何版本跃迁都必须先补 Migration）
        .fallbackToDestructiveMigration()
        .build()
}
