package com.autoledger.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.WageLedger
import com.autoledger.core.model.WageProfile
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **v7 → v8 迁移保真测试**（Robolectric + 真实 SQLite 文件库）。
 *
 * 为什么必须真跑一次迁移：`LedgerDatabaseFactory` 至今挂着 `fallbackToDestructiveMigration()`。
 * 只要 `MIGRATION_7_8` 漏写 / 没登记进 `ALL_MIGRATIONS` / 建表语句与 `schemas/8.json` 的
 * `createSql` 差一个字符 / 列名写成 snake_case，`exportSchema` 校验就会判
 * "migration didn't properly handle"，Room 随即退回破坏性重建 ⇒ **用户全部历史流水被清空**。
 * 而 `assembleDebug` 通过与否完全发现不了这件事。
 *
 * 与 [AiSettingsMigrationTest] 同构，但**不手抄建表语句**：直接读仓库里的
 * `schemas/…/7.json`（KSP 导出的权威 v7 结构）来建库，避免「测试里的 SQL 与实体声明悄悄漂移」。
 *
 * 播种规则（设计文档 D4）是本次最容易静默出错的业务逻辑，因此逐条钉死：
 * 恰 1 行、月份 = 最早流水月、空账本 = 当月、无设置行 = 0 行、月度收入 = 四舍五入值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WageVersionMigrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "wage-version-migration-test.db"

    /** 业务时区：与 `Migration` 里 `localtime` 修饰符的取值域一致。 */
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 取当月 15 日中午的毫秒时间戳 —— 用中午而不是午夜，避开任何时区偏移把月份切到相邻月。 */
    private fun noonMillis(year: Int, month: Int, day: Int = 15): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** 从 KSP 导出的 v7 schema 里取出全部建表 / 建索引语句。 */
    private fun v7SchemaSql(): List<String> {
        val file = sequenceOf(
            File("schemas/com.autoledger.core.database.LedgerDatabase/7.json"),
            File("../schemas/com.autoledger.core.database.LedgerDatabase/7.json"),
            File("core/database/schemas/com.autoledger.core.database.LedgerDatabase/7.json"),
        ).firstOrNull { it.exists() }
            ?: error("找不到 schemas/…/7.json，无法构造 v7 库（工作目录=${File("").absolutePath}）")

        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        val out = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            out += e.getString("createSql").replace("\${TABLE_NAME}", table)
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                out += indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
            }
        }
        return out
    }

    /**
     * 造一个带真实流水与设置的 v7 库。
     *
     * @param txns 流水 id → 发生时间（毫秒）
     * @param withSettings 是否写 `app_settings` 单行（关键分支：无设置行时迁移必须**不播种**）
     * @param salary 到手月薪（分）
     * @param payMonths 每年发薪月数（13 薪 = 13）
     */
    private fun seedV7(
        txns: List<Pair<String, Long>>,
        withSettings: Boolean = true,
        salary: Long = 1_200_000L,
        payMonths: Int = 13,
    ) {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 7) {
            override fun onCreate(db: SQLiteDatabase) {
                v7SchemaSql().forEach { db.execSQL(it) }
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = helper.writableDatabase
        for ((id, at) in txns) {
            db.execSQL(
                "INSERT INTO transactions (id, amountMinor, currency, occurredAtMillis, bookedAtMillis," +
                    " type, direction, counterparty, platform_id, platform_confidence, platform_source," +
                    " note, sourceId, sourceRef, accountId, categoryId, transferGroupId, fingerprint," +
                    " status, confidence, rawTextSealed, extras, orderId, refundId, schemaVersion)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    id, -1_470L, "CNY", at, at,
                    "EXPENSE", "OUT", "某商户", "unknown", 0.0, "AUTO",
                    null, "notify", id, null, null, null, "fp-$id",
                    "CONFIRMED", 1.0, null, null, null, null, 6,
                ),
            )
        }
        if (withSettings) {
            db.execSQL(
                "INSERT INTO app_settings (id, wageSalaryMinor, wagePayMonths, wageWorkCostMinor, wageWorkDays," +
                    " wageOfficeHours, wageCommuteMinutes, wageOvertimeHours, goalTargetMinor, goalCushionMinor," +
                    " goalCurrentMinor, autoMerge, aiEnabled, aiMode, aiEndpoint, aiModel)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    "global", salary, payMonths, 85_000L, 21.75, 9.0, 48, 0.6,
                    12_000_000L, 0L, 3_000_000L, 1, 0, "FALLBACK", "", "",
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

    /** 直接读原始行（绕过 Room 映射），用于断言"迁移写进库里的到底是什么"。 */
    private fun rawVersions(db: LedgerDatabase): List<Map<String, Any?>> {
        val columns = listOf(
            "month", "monthlyNetSalaryMinor", "payMonthsPerYear", "monthlyWorkCostMinor",
            "workDaysPerMonth", "dailyOfficeHours", "dailyCommuteMinutes", "dailyOvertimeHours",
            "monthlyIncomeMinor", "createdAtMillis", "updatedAtMillis", "schemaVersion",
        )
        return db.openHelper.readableDatabase
            .query("SELECT * FROM wage_versions ORDER BY month").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(columns.associateWith { name -> c.getColumnIndex(name).let { c.getString(it) } })
                    }
                }
            }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    // ------------------------------------------------------------------ 契约

    @Test
    fun `version contract and migration chain stay in sync`() {
        // 漏登记 ALL_MIGRATIONS 与推进 DATABASE_VERSION 是同一个事故的两面，先静态钉死。
        assertEquals(8, LedgerSchema.DATABASE_VERSION, "DATABASE_VERSION 必须已推进到 8")
        assertTrue(
            LedgerDatabaseFactory.ALL_MIGRATIONS.any { it.startVersion == 7 && it.endVersion == 8 },
            "ALL_MIGRATIONS 必须含 7→8 的一段，否则升级会退回破坏性重建",
        )
        // 链必须连续：4→5→6→7→8，不允许有断点。
        val versions = LedgerDatabaseFactory.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(listOf(4 to 5, 5 to 6, 6 to 7, 7 to 8), versions, "迁移链必须逐版本连续")
    }

    // ------------------------------------------------------------------ 保真

    @Test
    fun `v7 rows survive the migration untouched`() {
        val ids = listOf("txn-a", "txn-b", "txn-c")
        seedV7(ids.map { it to noonMillis(2025, 3) }, withSettings = false)

        val db = openMigrated()
        val idsAfter = db.openHelper.readableDatabase
            .query("SELECT id FROM transactions ORDER BY id").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        db.close()

        assertEquals(ids, idsAfter, "v7 的流水一条都不能少（少了就是被 destructive fallback 清库了）")
    }

    @Test
    fun `migration seeds exactly one version from the saved profile`() {
        seedV7(listOf("txn-a" to noonMillis(2025, 3)))

        val db = openMigrated()
        val rows = rawVersions(db)
        val settings = runBlocking { db.settingsDao().global() }
        db.close()

        assertEquals(1, rows.size, "播种必须恰好一行（多行说明 SQL 写成了循环/缺 WHERE）")
        // 全量快照：工时参数一起搬进版本行，历史月份的工时换算才能用当时的参数。
        assertEquals("1200000", rows[0]["monthlyNetSalaryMinor"])
        assertEquals("13", rows[0]["payMonthsPerYear"])
        assertEquals("85000", rows[0]["monthlyWorkCostMinor"])
        assertEquals("21.75", rows[0]["workDaysPerMonth"])
        assertEquals("9.0", rows[0]["dailyOfficeHours"])
        assertEquals("48", rows[0]["dailyCommuteMinutes"])
        assertEquals("0.6", rows[0]["dailyOvertimeHours"])
        // 行级结构标记与 toEntity() 的默认值一致（同表不允许出现两种取值）
        assertEquals(LedgerSchema.CURRENT.toString(), rows[0]["schemaVersion"])
        assertEquals(0L, rows[0]["createdAtMillis"]?.toString()?.toLong(), "播种行没有真实写入时间，记 0")
        // 既有设置行不得被迁移改动
        assertEquals(1_200_000L, settings?.wageSalaryMinor, "迁移只读 app_settings，绝不改它")
        assertEquals(13, settings?.wagePayMonths)
    }

    @Test
    fun `seeded month equals the earliest transaction month`() {
        // 最晚一笔在 2025-06，最早一笔在 2025-03（乱序插入，验证取的是 MIN 而不是第一行）
        seedV7(
            listOf(
                "txn-late" to noonMillis(2025, 6, 20),
                "txn-earliest" to noonMillis(2025, 3, 2),
                "txn-mid" to noonMillis(2025, 4, 9),
            ),
        )

        val db = openMigrated()
        val rows = rawVersions(db)
        db.close()

        assertEquals("2025-03", rows.single()["month"], "播种月必须是**最早**流水所在月")
    }

    @Test
    fun `empty ledger seeds the current month`() {
        // 空账本：MIN(occurredAtMillis) 返回 NULL ⇒ COALESCE 兜到当月。
        seedV7(emptyList())

        val db = openMigrated()
        val rows = rawVersions(db)
        db.close()

        val expected = WageLedger.monthKeyOf(YearMonth.now(zone).atDay(1))
        assertEquals(1, rows.size)
        assertEquals(expected, rows.single()["month"], "空账本必须回落当月")
    }

    @Test
    fun `no settings row seeds nothing`() {
        // app_settings 只在用户首次修改设置时才写入 ⇒ 全新安装的用户升级后没有版本行。
        // 此时收入按 0 展示（+「未设置时薪」提示），绝不凭空编造收入。
        seedV7(listOf("txn-a" to noonMillis(2025, 3)), withSettings = false)

        val db = openMigrated()
        val rows = rawVersions(db)
        db.close()

        assertEquals(0, rows.size, "没有 app_settings 行时不得播种任何版本")
    }

    @Test
    fun `seeded monthly income matches effective monthly salary`() {
        // 三组双向断言：迁移 SQL 的 CAST(ROUND(...)) 必须与 Kotlin 的 roundToLong() 逐分相等。
        // ① 13 薪整除；② 非整除（.33…）；③ .5 边界（SQLite ROUND 与 Kotlin roundToLong 都远离零）。
        val cases = listOf(
            1_200_000L to 13, // 1 300 000
            400_000L to 13, // 433 333.33… → 433 333
            1_200_006L to 13, // 1 300 006.5 → 1 300 007
        )

        for ((salary, payMonths) in cases) {
            context.deleteDatabase(dbName)
            seedV7(emptyList(), salary = salary, payMonths = payMonths)

            val db = openMigrated()
            val rows = rawVersions(db)
            db.close()

            val expected = WageProfile(monthlyNetSalaryMinor = salary, payMonthsPerYear = payMonths)
                .effectiveMonthlySalaryMinor
            assertEquals(
                expected.toString(),
                rows.single()["monthlyIncomeMinor"],
                "月度收入必须等于 effectiveMonthlySalaryMinor（salary=$salary, payMonths=$payMonths）",
            )
        }
    }

    // ------------------------------------------------------------------ 可读写

    @Test
    fun `wage_versions is readable and writable after migration`() = runBlocking {
        seedV7(listOf("txn-a" to noonMillis(2025, 3)))

        val db = openMigrated()
        val dao = db.wageVersionDao()

        // ① 读得到播种行，且能还原成领域模型（列名映射正确）
        val seeded = dao.listAll()
        assertEquals(1, seeded.size)
        assertEquals("2025-03", seeded.single().month)
        assertEquals(1_200_000L, dao.toModelOf("2025-03")?.profile?.monthlyNetSalaryMinor)

        // ② 可写：新增一行 + 覆盖同月行（createdAt 由调用方保留，这里手动模拟）
        val newMonth = seeded.single().let { it.copy(month = "2025-05", monthlyIncomeMinor = 1_500_000L) }
        dao.upsert(newMonth)
        val bumped = newMonth.copy(updatedAtMillis = 42L)
        dao.upsert(bumped)

        val all = dao.listAll()
        assertEquals(listOf("2025-03", "2025-05"), all.map { it.month }, "listAll 必须按月份升序")
        assertEquals(42L, dao.find("2025-05")?.updatedAtMillis, "同月 upsert 必须覆盖而不是新增")

        // ③ insertIfAbsent 不得覆盖已有同月行（旧档案播种语义）
        dao.insertIfAbsent(newMonth.copy(monthlyIncomeMinor = 9_999_999L))
        assertEquals(1_500_000L, dao.find("2025-05")?.monthlyIncomeMinor, "insertIfAbsent 必须保留原行")
        // 但可以插入新月份
        dao.insertIfAbsent(newMonth.copy(month = "2025-06", monthlyIncomeMinor = 111L))
        assertEquals(111L, dao.find("2025-06")?.monthlyIncomeMinor)

        // ④ clear 清空（REPLACE_ALL 导入前）
        dao.clear()
        assertEquals(0, dao.listAll().size)

        db.close()
    }

    /** `find` + 映射的小工具，避免在断言里写两行。 */
    private suspend fun WageVersionDao.toModelOf(month: String) = find(month)?.toWageVersion()

    // ------------------------------------------------------------------ 辅助断言

    /** 让 `WageLedger` 在迁移后的库上也能正常工作（真实读路径的最小冒烟）。 */
    @Test
    fun `ledger resolves income from the seeded version`() = runBlocking {
        seedV7(
            listOf("txn-a" to noonMillis(2025, 3)),
            salary = 1_200_000L,
            payMonths = 13,
        )

        val db = openMigrated()
        val versions = db.wageVersionDao().listAll().map { it.toWageVersion() }
        db.close()

        assertNotNull(versions.firstOrNull())
        // 播种月本身
        assertEquals(1_300_000L, WageLedger.incomeForMonth(versions, "2025-03"))
        // 早于播种月 ⇒ 回落最早版本，绝不清零
        assertEquals(1_300_000L, WageLedger.incomeForMonth(versions, "2024-01"))
        // 晚于播种月 ⇒ 沿用最近版本
        assertEquals(1_300_000L, WageLedger.incomeForMonth(versions, "2026-12"))
    }
}
