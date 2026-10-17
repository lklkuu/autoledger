package com.autoledger.app

import com.autoledger.core.database.WageVersionDao
import com.autoledger.core.database.toEntity
import com.autoledger.core.database.toWageVersion
import com.autoledger.core.model.WageLedger
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.WageVersion
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **时薪版本的唯一写入口**（app 层）。
 *
 * ## 它解决什么问题
 * 改造前 `UserSettings.updateWage` 只 upsert `app_settings` 的单行快照，
 * 用户改一次时薪就把上一版覆盖掉 ⇒ 历史的「当月收入 / 已攒」只能靠"用今天的时薪重算"，
 * 也就是回溯覆盖。本类把每次修改落成 `wage_versions` 的一行（主键 = 生效月），
 * 使"改时薪不回写历史"成为**数据结构的性质**。
 *
 * ## `save()` 为什么不接受 month 参数
 * 这是**结构性**的禁止回溯：一旦开放 `save(profile, month)`，
 * 任何调用点都能悄悄改写历史月份的收入，"不回溯"就退回成自觉遵守的约定。
 * 需要"从某月生效"时只能等那个月到来再改（未来月份会自动沿用最近版本）。
 *
 * ## 同月二次保存必须保留 `createdAtMillis`
 * `createdAt` 的语义是"这个月的时薪**首次**确定的时间"，是审计线索；
 * 重复保存只应刷新 `updatedAt`。minSdk 26 的 SQLite 3.18 不支持
 * `INSERT ... ON CONFLICT DO UPDATE`，所以用读-改-写（先 [WageVersionDao.find] 再 upsert）。
 *
 * ## 为什么仍然双写 `app_settings.wage`
 * 三处仍在读它：① 时薪页编辑器的初值；② 旧备份档案（只有 `wage` 单对象）；
 * ③ 「花掉的时间」等在**没有版本行**时的兜底。它已降级为「最近一次编辑的快照」，
 * **不再作为收入来源**。
 */
class WageBook(
    private val dao: WageVersionDao,
    private val settings: UserSettings,
    private val scope: CoroutineScope,
) {

    private val _versions = MutableStateFlow<List<WageVersion>>(emptyList())

    /**
     * 全部版本（按月份升序，字典序 = 时间序），供 UI 直接喂给 [WageLedger]。
     *
     * 初值是空列表：**空列表 ⇒ 一切收入为 0**，这是诚实的初始状态
     * （"未设置时薪"），绝不回落到 INCOME 流水求和。
     */
    val versions: StateFlow<List<WageVersion>> = _versions.asStateFlow()

    private var observeJob: Job? = null

    /**
     * `save()` 的串行锁。
     *
     * `find(month) → upsert` 是读-改-写：两次并发保存（用户连点两下按钮、或保存与备份导入
     * 撞在一起）时，后一次读到的可能是"还没有行"或"旧的 createdAt"，于是
     * **`createdAtMillis` 会被覆盖成后一次的时间**，同月还可能出现两次"新建"。
     * 那个字段是"该月时薪首次确定时间"的审计线索，被覆盖后无法复原。
     *
     * minSdk 26 的 SQLite 3.18 没有 `INSERT … ON CONFLICT DO UPDATE`（不能把
     * "保留旧 createdAt"下推到 SQL 里原子完成），因此用协程 Mutex 在**进程内**串行化。
     * 单进程内的竞争源就是同一批 UI/导入调用，够用；跨进程（多开）不在本 App 的形态内。
     */
    private val saveMutex = Mutex()

    /**
     * 订阅版本表（幂等）。由 `AppContainer.bootstrap()` 预热调用。
     *
     * 用 [WageVersionDao.observeAll] 而不是一次性 `listAll`：备份导入、迁移播种、
     * 其他入口的写入都能让 UI 实时刷新，不需要各处手动"改完再通知一遍"。
     */
    fun init() {
        if (observeJob != null) return
        observeJob = scope.launch {
            dao.observeAll().collect { rows -> _versions.value = rows.map { it.toWageVersion() } }
        }
    }

    /**
     * 保存当前月份的时薪版本。
     *
     * 行为（顺序固定，别改）：
     * 1. 用 [nowMillis] + [zone] 算出当月月份键（**不接受调用方指定月份**）；
     * 2. 读同月旧行，沿用其 `createdAtMillis`；
     * 3. upsert 版本行 → 立即用 `listAll()` 刷新 [versions]；
     * 4. 双写 `app_settings.wage`（兼容旧编辑器 / 旧备份 / 无版本兜底）。
     *
     * @param nowMillis 写入时间，默认当前时间。仅作测试注入点（真实调用不要传）。
     * @param zone 业务时区，默认系统时区（与 `TimeRange.ZONE` 一致）。
     * @return 本次写入的版本（含锁定后的 `monthlyIncomeMinor`）
     */
    suspend fun save(
        profile: WageProfile,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WageVersion = saveMutex.withLock {
        val month = WageLedger.monthKeyOf(nowMillis, zone)
        val existing = dao.find(month)
        // 同月重存：保留 createdAt（审计"这个月的时薪首次确定于何时"），刷新 updatedAt。
        val version = WageVersion.of(
            month = month,
            profile = profile,
            nowMillis = nowMillis,
            createdAtMillis = existing?.createdAtMillis ?: nowMillis,
        )
        dao.upsert(version.toEntity())
        // 不依赖 Flow 的下一帧：状态同步刷新，保证"保存后立刻读到新值"。
        refresh()
        // 双写 app_settings（不 await：UserSettings.updateWage 内部已把写库放进 scope）。
        settings.updateWage(profile)
        version
    }

    /** 重新从库里读一遍版本表（[init] 的订阅之外，供一次性确定性刷新用）。 */
    suspend fun refresh() {
        _versions.value = dao.listAll().map { it.toWageVersion() }
    }

    /** 某月收入（分）。无版本 ⇒ `0`（不回落流水求和）。 */
    fun incomeForMonth(month: String): Long = WageLedger.incomeForMonth(_versions.value, month)

    /** 任意区间应计收入（分），只累加「月首落在区间内」的月份。 */
    fun incomeForRange(from: LocalDate, to: LocalDate): Long =
        WageLedger.incomeForRange(_versions.value, from, to)

    /** 某月生效的时薪参数快照；无版本 ⇒ `null`（调用方自行回落到 `settings.wage`）。 */
    fun profileForMonth(month: String): WageProfile? = WageLedger.profileForMonth(_versions.value, month)
}
