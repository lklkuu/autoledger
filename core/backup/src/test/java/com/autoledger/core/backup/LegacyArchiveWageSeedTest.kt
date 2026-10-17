package com.autoledger.core.backup

import android.content.Context
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.LedgerDatabaseFactory
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.database.toEntity
import com.autoledger.core.database.toWageVersion
import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.WageLedger
import com.autoledger.core.model.WageProfile
import kotlin.test.assertEquals
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
 * **旧档案（无 `wageVersions` 键）导入时的时薪播种**护栏。
 *
 * ## 为什么这条路径一直没被测到
 * `BackupManager.buildPayload` **无条件** `put("wageVersions", …)`，所以本机导出的档案
 * 必然带这个键（哪怕是空数组），永远走 `wageVersionArray != null` 分支。
 * 只有 v7 及更早版本导出的档案才没有该键 —— 也就是说 `else` 分支在真实世界里
 * 只对"存量备份"生效，而存量备份**不能靠自测生成**。
 * 因此这里的做法是：先在**本机**导出一份（拿到合法信封与 settings 段），
 * 再**手工删掉** `payload.wageVersions` 键，模拟 v1.1.12 现场导出的档案。
 *
 * ## 这条路径的缺陷比迁移那条更隐蔽（QA 第二轮发现）
 * 旧实现直接用档案里的 `settings.wage` 播种，并写**真实时间戳**：
 * - 若用户只改过目标 / 自动合并，档案里的 `wage` 就是出厂默认 `WageProfile()`
 *   ⇒ 收入变回 ¥4333.33/月（迁移那道门已经关掉了同样的编数）；
 * - 因为时间戳非 0，`WageLedger.isMigrationSeeded` 判为 false
 *   ⇒ **连「时薪为升级前保存的值，请确认」的三态提示都不会出现**。
 * 现在两条播种路径共用判据：整块时薪 == 出厂默认 ⇒ 不播种；否则播种且 `createdAtMillis = 0`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyArchiveWageSeedTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "legacy-archive-wage.db"

    /** 记录流水时间用的固定时刻（当月 15 日中午，避开月末/时区边界）。 */
    private val txnAt: Long = run {
        val now = java.time.LocalDate.now()
        now.withDayOfMonth(15).atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun openDb(): LedgerDatabase = LedgerDatabaseFactory.create(
        context = context,
        openHelperFactory = null, // 明文：SQLCipher 原生库无法在 JVM 上加载
        databaseName = dbName,
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /**
     * 造一份"v1.1.12 形态"的旧档案：本机导出（保证 `settings` 段合法、信封合法），
     * 然后**删掉** `wageVersions` 键。
     *
     * @param wage 写进 `app_settings` 的时薪（`null` 表示连设置行都不写 ⇒ 档案无 `settings` 段）
     */
    private fun legacyArchive(wage: WageProfile?): String = runBlocking {
        val db = openDb()
        val repo = RoomLedgerRepository(db)
        if (wage != null) {
            // `AppSettings` 是整行写：这正是"只改过其它设置的人也会有一行、且 wage 是默认值"的成因
            db.settingsDao().upsert(AppSettings(wage = wage).toEntity())
        }
        // 放一条流水，让 `earliestMonthKeyOf` 有确定的月份可推
        repo.upsert(
            com.autoledger.core.model.LedgerTransaction(
                id = "t1",
                amountMinor = -1_000L,
                occurredAtMillis = txnAt,
                type = com.autoledger.core.model.TxnType.EXPENSE,
                counterparty = "某店",
                sourceId = "notify",
                sourceRef = "notify:t1",
                status = com.autoledger.core.model.TxnStatus.CONFIRMED,
                fingerprint = "fp-t1",
            ),
        )
        val modern = BackupManager(db, repo).exportJson(appVersion = "1.1.12", device = "old-phone")
        db.close()

        // 撕掉 v8 才有的键 ⇒ 得到 v1.1.12 现场会导出的形状
        val root = JSONObject(modern)
        root.getJSONObject("payload").remove("wageVersions")
        root.toString()
    }

    /** 导入到一台"新设备"，返回版本表行数与（可选的）唯一一行。 */
    private fun importAndReadVersions(raw: String): Pair<Int, com.autoledger.core.database.WageVersionEntity?> =
        runBlocking {
            context.deleteDatabase(dbName)
            val db = openDb()
            val repo = RoomLedgerRepository(db)
            BackupManager(db, repo).import(raw, BackupManager.MergeStrategy.REPLACE_ALL)
            val rows = db.wageVersionDao().listAll()
            db.close()
            rows.size to rows.singleOrNull()
        }

    @Test
    fun `legacy archive with default wage does not seed`() {
        // 用户只改过目标 / 自动合并 ⇒ app_settings 有行，但 wage 是出厂默认。
        // 导入旧档案不得因此编出一个月收入（与 MIGRATION_7_8 同一判据）。
        val raw = legacyArchive(WageProfile())

        val (count, _) = importAndReadVersions(raw)

        assertEquals(0, count, "档案里的时薪整块是出厂默认值 ⇒ 视为『从未设过时薪』，不播种")
    }

    @Test
    fun `legacy archive with customized wage seeds one pending version`() {
        // 真设过时薪的用户换机恢复：必须保住他的时薪，但要标记为"预填待确认"
        // （真实时间戳会让它看起来像用户在本机 v8 里亲手确认过）。
        val raw = legacyArchive(WageProfile(monthlyNetSalaryMinor = 1_200_000L, payMonthsPerYear = 13))

        val (count, row) = importAndReadVersions(raw)

        assertEquals(1, count, "自定义时薪必须播种一条，否则换机后收入变 0")
        val seeded = row ?: error("应当有且仅有一行版本")
        assertEquals(1_200_000L, seeded.monthlyNetSalaryMinor)
        assertEquals(13, seeded.payMonthsPerYear)
        assertEquals(1_300_000L, seeded.monthlyIncomeMinor, "12000 × 13 / 12 = 13000 元")
        assertEquals(0L, seeded.createdAtMillis, "旧档案播种必须标记为『预填、未确认』")
        assertEquals(0L, seeded.updatedAtMillis)
    }

    @Test
    fun `legacy archive seeding is recognised as pending confirmation by the ledger`() {
        // 三态提示的判据必须对这条路径同样生效 —— 这正是原缺陷最隐蔽的地方：
        // 时间戳是真值时，`isMigrationSeeded` 为 false，UI 不会提示"请确认"。
        val raw = legacyArchive(WageProfile(monthlyNetSalaryMinor = 1_200_000L))

        val (count, _) = importAndReadVersions(raw)
        assertEquals(1, count)

        // 重新打开库读成领域模型，验证判据
        val db = openDb()
        val versions = runBlocking { db.wageVersionDao().listAll() }.map { it.toWageVersion() }
        db.close()

        assertTrue(WageLedger.allMigrationSeeded(versions), "旧档案播种的版本必须被判为『预填待确认』")
        assertEquals(1_300_000L, WageLedger.incomeForMonth(versions, versions.single().month))
    }

    @Test
    fun `legacy archive without settings section seeds nothing`() {
        // 更老的档案 / 被裁剪过的档案：连 `settings` 段都没有 ⇒ 没有任何时薪信息可推断。
        val raw = legacyArchive(wage = null)

        val (count, _) = importAndReadVersions(raw)

        assertEquals(0, count, "没有 settings 段 ⇒ 不播种（也绝不抛异常）")
    }
}
