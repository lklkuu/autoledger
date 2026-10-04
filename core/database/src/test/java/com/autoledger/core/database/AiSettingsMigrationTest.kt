package com.autoledger.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.autoledger.core.model.LedgerSchema
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * **v6 → v7 迁移保真测试**（Robolectric + 真实 SQLite 文件库）。
 *
 * 为什么必须真跑一次迁移：`LedgerDatabaseFactory` 至今挂着 `fallbackToDestructiveMigration()`。
 * 只要 `MIGRATION_6_7` 漏写、或四列的列名与 Room 的生成规则差一个字符，
 * `exportSchema` 校验就会判 "migration didn't properly handle"，Room 随即退回破坏性重建
 * ⇒ **用户全部历史流水与账目被清空**。而 `assembleDebug` 通过与否完全发现不了这件事。
 *
 * 与 [UserPlatformMigrationTest] 同构，但**不手抄建表语句**：直接读仓库里的
 * `schemas/…/6.json`（KSP 导出的权威 v6 结构）来建库，
 * 避免「测试里的 SQL 与实体声明悄悄漂移」这类假绿。
 *
 * 断言四件事：
 *  1. v6 的流水一条不少（= 没走破坏性重建）；
 *  2. 旧行的四个 AI 列取默认值（AI 关、兜底模式、地址与模型留空 ⇒ 升级后行为完全不变）；
 *  3. 四列可写可读（Room 侧映射与列名都对得上）；
 *  4. 版本契约与迁移链登记一致（`DATABASE_VERSION == 7` 且 `ALL_MIGRATIONS` 含 `MIGRATION_6_7`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiSettingsMigrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "ai-settings-migration-test.db"

    /** 从 KSP 导出的 v6 schema 里取出全部建表 / 建索引语句。 */
    private fun v6SchemaSql(): List<String> {
        val file = sequenceOf(
            File("schemas/com.autoledger.core.database.LedgerDatabase/6.json"),
            File("../schemas/com.autoledger.core.database.LedgerDatabase/6.json"),
            File("core/database/schemas/com.autoledger.core.database.LedgerDatabase/6.json"),
        ).firstOrNull { it.exists() }
            ?: error("找不到 schemas/…/6.json，无法构造 v6 库（工作目录=${File("").absolutePath}）")

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

    /** 造一个带真实流水与设置的 v6 库。 */
    private fun seedV6(txnIds: List<String>) {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 6) {
            override fun onCreate(db: SQLiteDatabase) {
                v6SchemaSql().forEach { db.execSQL(it) }
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = helper.writableDatabase
        for (id in txnIds) {
            db.execSQL(
                "INSERT INTO transactions (id, amountMinor, currency, occurredAtMillis, bookedAtMillis," +
                    " type, direction, counterparty, platform_id, platform_confidence, platform_source," +
                    " note, sourceId, sourceRef, accountId, categoryId, transferGroupId, fingerprint," +
                    " status, confidence, rawTextSealed, extras, orderId, refundId, schemaVersion)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    id, 1_470L, "CNY", 1_700_000_000_000L, 1_700_000_000_000L,
                    "EXPENSE", "OUT", "某商户", "unknown", 0.0, "AUTO",
                    null, "notify", id, null, null, null, "fp-$id",
                    "CONFIRMED", 1.0, null, null, null, null, 5,
                ),
            )
        }
        db.execSQL(
            "INSERT INTO app_settings (id, wageSalaryMinor, wagePayMonths, wageWorkCostMinor, wageWorkDays," +
                " wageOfficeHours, wageCommuteMinutes, wageOvertimeHours, goalTargetMinor, goalCushionMinor," +
                " goalCurrentMinor, autoMerge) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("global", 1_200_000L, 13, 0L, 21.75, 8.0, 60, 0.0, 12_000_000L, 0L, 3_000_000L, 1),
        )
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
    fun `version contract and migration chain stay in sync`() {
        // 漏登记 ALL_MIGRATIONS 与推进 DATABASE_VERSION 是同一个事故的两面，先静态钉死。
        assertEquals(7, LedgerSchema.DATABASE_VERSION, "DATABASE_VERSION 必须已推进到 7")
        assertTrue(
            LedgerDatabaseFactory.ALL_MIGRATIONS.any { it.startVersion == 6 && it.endVersion == 7 },
            "ALL_MIGRATIONS 必须含 6→7 的一段，否则升级会退回破坏性重建",
        )
        // 链必须连续：4→5→6→7，不允许有断点。
        val versions = LedgerDatabaseFactory.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(listOf(4 to 5, 5 to 6, 6 to 7), versions, "迁移链必须逐版本连续")
    }

    @Test
    fun `every v6 row survives the migration untouched`() {
        val ids = listOf("txn-a", "txn-b", "txn-c")
        seedV6(ids)

        val db = openMigrated()
        val idsAfter = db.openHelper.readableDatabase
            .query("SELECT id FROM transactions ORDER BY id").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        db.close()

        assertEquals(ids, idsAfter, "v6 的流水一条都不能少（少了就是被 destructive fallback 清库了）")
    }

    @Test
    fun `legacy settings row gets safe AI defaults`() = runBlocking {
        seedV6(emptyList())

        val db = openMigrated()
        val entity = db.settingsDao().global()
        db.close()

        assertEquals(false, entity?.aiEnabled, "旧行的 AI 开关必须是关的（升级后行为完全不变）")
        assertEquals("FALLBACK", entity?.aiMode, "旧行必须是兜底模式")
        assertEquals("", entity?.aiEndpoint)
        assertEquals("", entity?.aiModel)
        assertEquals(1_200_000L, entity?.wageSalaryMinor, "既有列不能被迁移改动")
    }

    @Test
    fun `the four new columns are writable after the migration`() = runBlocking {
        seedV6(emptyList())

        val db = openMigrated()
        val dao = db.settingsDao()
        val before = dao.global()
        assertTrue(before != null, "迁移后必须能读到设置行")

        dao.upsert(
            before!!.copy(
                aiEnabled = true,
                aiMode = "ALWAYS",
                aiEndpoint = "https://ai.example.com/v1/chat/completions",
                aiModel = "gpt-4o-mini",
            ),
        )
        val after = dao.global()
        db.close()

        assertEquals(true, after?.aiEnabled, "新列必须可写可读")
        assertEquals("ALWAYS", after?.aiMode)
        assertEquals("https://ai.example.com/v1/chat/completions", after?.aiEndpoint)
        assertEquals("gpt-4o-mini", after?.aiModel)
        assertFalse(after!!.aiModel.isBlank(), "模型名不该被吞掉")
    }
}
