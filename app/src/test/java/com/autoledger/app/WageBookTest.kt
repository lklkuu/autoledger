package com.autoledger.app

import com.autoledger.core.database.SettingsDao
import com.autoledger.core.database.SettingsEntity
import com.autoledger.core.database.WageVersionDao
import com.autoledger.core.database.WageVersionEntity
import com.autoledger.core.database.toWageVersion
import com.autoledger.core.model.WageProfile
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * **时薪版本写入口**（[WageBook]）的行为测试 —— 纯 JVM + fake DAO，不碰 Room。
 *
 * 为什么这组测试是整个改造里最关键的行为护栏：用户的原始诉求是
 * 「改时薪**不能**回溯覆盖历史月份的收入」。这条约束只有两层保障：
 * 1. **结构**：`save()` 不接受 month 参数，写入月份只能由 `now` 决定；
 * 2. **行为**：同月重存保留 `createdAt`、历史月永不新增行、`app_settings` 继续双写。
 * 第 2 条一旦被破坏，用户改一次时薪就会看到去年的「已攒」被重算 —— 而这是静默的。
 *
 * 用 [Dispatchers.Unconfined] 起作用域：fake DAO 的 suspend 方法不真正挂起，
 * 因此 `scope.launch` 里的写库与 Flow 分发都是同步完成的，断言无需等待或轮询。
 */
class WageBookTest {

    // ------------------------------------------------------------------ 夹具

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(year: Int, month: Int, day: Int = 15, hour: Int = 10): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun profile(salary: Long = 1_200_000L, payMonths: Int = 13): WageProfile = WageProfile(
        monthlyNetSalaryMinor = salary,
        payMonthsPerYear = payMonths,
    )

    /** fake DAO：内存状态 + 记录"被写入了哪个月"，用于断言"永不写历史月"。 */
    private class FakeWageVersionDao : WageVersionDao {
        val rows = MutableStateFlow<List<WageVersionEntity>>(emptyList())

        /** 所有经过 [upsert] / [insertIfAbsent] 的月份键（按调用顺序） */
        val writtenMonths = mutableListOf<String>()
        var clearCount = 0

        override suspend fun upsert(version: WageVersionEntity) {
            writtenMonths += version.month
            rows.value = (rows.value.filterNot { it.month == version.month } + version).sortedBy { it.month }
        }

        override suspend fun upsertAll(versions: List<WageVersionEntity>) {
            writtenMonths += versions.map { it.month }
            val incoming = versions.associateBy { it.month }
            rows.value = (rows.value.filterNot { incoming.containsKey(it.month) } + versions).sortedBy { it.month }
        }

        override suspend fun insertIfAbsent(version: WageVersionEntity) {
            if (rows.value.any { it.month == version.month }) return
            writtenMonths += version.month
            rows.value = (rows.value + version).sortedBy { it.month }
        }

        override suspend fun listAll(): List<WageVersionEntity> = rows.value

        override fun observeAll(): Flow<List<WageVersionEntity>> = rows

        override suspend fun find(month: String): WageVersionEntity? =
            rows.value.firstOrNull { it.month == month }

        override suspend fun clear() {
            clearCount += 1
            rows.value = emptyList()
        }
    }

    /** fake 设置 DAO：只关心"有没有被写"以及写进去的时薪快照。 */
    private class FakeSettingsDao : SettingsDao {
        val state = MutableStateFlow<SettingsEntity?>(null)
        var upsertCount = 0

        override suspend fun global(): SettingsEntity? = state.value

        override fun observeGlobal(): Flow<SettingsEntity?> = state

        override suspend fun upsert(settings: SettingsEntity) {
            upsertCount += 1
            state.value = settings
        }
    }

    private class Fixture {
        val dao = FakeWageVersionDao()
        val settingsDao = FakeSettingsDao()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val settings = UserSettings(settingsDao, scope)
        val book = WageBook(dao, settings, scope)

        init {
            settings.init()
            book.init()
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun `save writes the current month and locks monthly income`() = runBlocking {
        val f = Fixture()
        val now = millis(2026, 5)

        val saved = f.book.save(profile(salary = 1_200_000L, payMonths = 13), nowMillis = now, zone = zone)

        assertEquals("2026-05", saved.month, "写入月份必须由 now 决定")
        // 12 000 × 13 / 12 = 13 000 元 = 1 300 000 分，写入时锁定
        assertEquals(1_300_000L, saved.monthlyIncomeMinor)
        assertEquals(1_300_000L, f.dao.rows.value.single().monthlyIncomeMinor, "落库值必须与返回值一致")
        assertEquals("2026-05", f.dao.rows.value.single().month)
        assertEquals(now, saved.createdAtMillis)
        assertEquals(now, saved.updatedAtMillis)
        // 版本流同步刷新（UI 保存后立即可读）
        assertEquals(1, f.book.versions.value.size)
        assertEquals(1_300_000L, f.book.incomeForMonth("2026-05"))
    }

    @Test
    fun `saving twice in the same month keeps createdAt and bumps updatedAt`() = runBlocking {
        val f = Fixture()
        val first = millis(2026, 5, day = 3)
        val second = millis(2026, 5, day = 20)

        f.book.save(profile(salary = 1_200_000L), nowMillis = first, zone = zone)
        f.book.save(profile(salary = 1_500_000L), nowMillis = second, zone = zone)

        val rows = f.dao.rows.value
        assertEquals(1, rows.size, "同月重复保存必须覆盖同一行，而不是新增版本")
        assertEquals(first, rows.single().createdAtMillis, "createdAt 是『该月时薪首次确定』的审计线索，必须保留")
        assertEquals(second, rows.single().updatedAtMillis, "updatedAt 必须刷新")
        // 15 000 × 13 / 12 = 16 250 元
        assertEquals(1_625_000L, rows.single().monthlyIncomeMinor, "同月改薪只改当月，历史月份仍按旧版本")
    }

    @Test
    fun `save never writes a past month`() = runBlocking {
        val f = Fixture()
        val april = millis(2026, 4)
        val may = millis(2026, 5)

        // 先在 4 月存一版（模拟用户 4 月用过 App），再在 5 月改薪
        f.book.save(profile(salary = 1_000_000L), nowMillis = april, zone = zone)
        f.book.save(profile(salary = 2_000_000L), nowMillis = may, zone = zone)

        assertEquals(listOf("2026-04", "2026-05"), f.dao.writtenMonths, "每次保存只能写『当时』的月份")
        // 4 月那一行不得被 5 月的修改覆盖 —— 这是"改时薪不回溯"的核心断言
        assertEquals(
            profile(salary = 1_000_000L).effectiveMonthlySalaryMinor,
            f.dao.rows.value.first { it.month == "2026-04" }.monthlyIncomeMinor,
            "历史月份的月度收入必须原样保留",
        )
        assertEquals(1_000_000L, f.dao.rows.value.first { it.month == "2026-04" }.monthlyNetSalaryMinor)
        // 接口上根本没有「指定月份」的入口（结构性禁止回溯）—— 这里锁住签名语义：
        // 只有 nowMillis 能决定月份，没有 month 参数可传。
        assertTrue(f.dao.writtenMonths.none { it > "2026-05" }, "不得写入未来月份")
    }

    @Test
    fun `save also updates the legacy app_settings snapshot`() = runBlocking {
        val f = Fixture()
        val newProfile = profile(salary = 1_500_000L, payMonths = 12)

        f.book.save(newProfile, nowMillis = millis(2026, 5), zone = zone)

        // 双写：版本表给收入汇总用，app_settings 给「时薪页初值 / 旧备份 / 无版本兜底」用。
        assertEquals(1, f.settingsDao.upsertCount, "app_settings 必须被写一次（只写一次）")
        assertEquals(1_500_000L, f.settingsDao.state.value?.wageSalaryMinor, "app_settings 的月薪必须同步")
        assertEquals(12, f.settingsDao.state.value?.wagePayMonths, "app_settings 的发薪月数必须同步")
        assertEquals(newProfile, f.settings.wage.value, "UserSettings.wage 必须同步刷新")
    }

    @Test
    fun `versions flow reflects the newest list after save`() = runBlocking {
        val f = Fixture()
        assertTrue(f.book.versions.value.isEmpty(), "初始为空：没有任何版本 ⇒ 收入为 0（诚实的空状态）")
        assertEquals(0L, f.book.incomeForMonth("2026-05"))
        assertNull(f.book.profileForMonth("2026-05"))

        f.book.save(profile(salary = 1_200_000L), nowMillis = millis(2026, 3), zone = zone)
        f.book.save(profile(salary = 1_500_000L), nowMillis = millis(2026, 5), zone = zone)

        val versions = f.book.versions.value
        assertEquals(listOf("2026-03", "2026-05"), versions.map { it.month }, "版本流必须按月份升序")
        assertEquals(versions.map { it.month }, f.dao.rows.value.map { it.month }, "流内容必须与库一致")

        // 流刷新后派生数据立即正确：4 月沿用 3 月版本，5 月起用新版本
        assertEquals(1_300_000L, f.book.incomeForMonth("2026-04"))
        assertEquals(1_625_000L, f.book.incomeForMonth("2026-05"))
        assertEquals(21.75, f.book.profileForMonth("2026-04")?.workDaysPerMonth)
        // 区间收入只累加「月首落在区间内」的月份：
        //   3/15–4/30 ⇒ 只有 4/1 ⇒ 13000 元
        //   3/15–5/14 ⇒ 4/1 与 5/1 都落在区间内 ⇒ 13000 + 16250 = 29250 元
        //   （5/1 在 5/14 之前，所以 5 月必须计入 —— "只算整月"不等于"只算一个月"）
        assertEquals(
            1_300_000L,
            f.book.incomeForRange(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 30)),
        )
        assertEquals(
            2_925_000L,
            f.book.incomeForRange(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 5, 14)),
        )
        // 直接比对 DAO 行与领域模型映射（防列名/字段漂移）
        assertEquals(versions, f.dao.rows.value.map { it.toWageVersion() })
    }
}
