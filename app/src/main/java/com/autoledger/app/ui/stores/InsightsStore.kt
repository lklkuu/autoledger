package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.Category
import com.autoledger.core.model.FreedomMath
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.MetricSnapshot
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.TxnType
import com.autoledger.feature.stats.PlatformShareMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class InsightsStore(private val container: AppContainer) {

    data class Facts(
        val largestTxn: LedgerTransaction? = null,
        val topMerchant: Pair<String, Long>? = null,
        val weekdayVsWeekend: Pair<Long, Long> = 0L to 0L,
        val avgDailyMinor: Long = 0L,
        val unclassifiedCount: Int = 0,
        /** 本月最近几笔（含收入与退款，不含内部划转），供「发现」页直接展示，避免为此再挂一个 HomeStore（R1）。 */
        val recent: List<LedgerTransaction> = emptyList(),
        /** 分类字典，供 [recent] 渲染类目名。 */
        val categories: Map<String, Category> = emptyMap(),
        /**
         * 该区间内的**非划转流水条数**（`spending.size`）。
         *
         * 空态判定必须用它，不能靠"金额都是 0"来猜：一个只有内部划转的月份
         * 金额全为 0 但确实"没有可展示的收支记录"，而一个净额为 0 的月份
         * （收入支出正好相等）却是有记录的 —— 两者不能混。
         */
        val recordCount: Int = 0,
    )

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val facts: Facts = Facts(),
        val metrics: List<MetricResult> = emptyList(),
        /** 当前查看的月份。默认当月 ⇒ 与「按月查看」上线前的行为完全一致。 */
        val selectedMonth: YearMonth = YearMonth.now(),
        /** 可选月份，**降序**（当前月在最前）。从账本最早一笔流水所在月到当前月。 */
        val availableMonths: List<YearMonth> = emptyList(),
        val canGoPrev: Boolean = false,
        val canGoNext: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 选中月份的**唯一真源**。
     *
     * ⚠️ **只能经 [selectMonth] 修改** —— 它负责 cancel 旧订阅再重订阅。
     * UI 若直接改这里（或自己另起一个 observe 协程），新旧月份的 Flow 会同时写 [_state]，
     * 页面就会出现跳月、闪回、数据错乱的竞态。
     */
    private val _selected = MutableStateFlow(YearMonth.now())

    /** 最早流水月：懒算一次后缓存。切月不重算，避免每次切月都全表扫一遍求最早时间。 */
    private var earliestMonth: YearMonth? = null
    private var monthsBuilt = false

    // B4：改为实例级作用域 + observeRange 订阅。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        observeJob = scope.launch { observe() }
    }

    /** 切换查看月份（UI 必须走这里，见 [_selected] 的说明）。越界会被 [clampMonth] 夹回合法范围。 */
    fun selectMonth(target: YearMonth) {
        val clamped = clampMonth(target)
        if (clamped == _selected.value) return
        _selected.value = clamped
        load()
    }

    fun prevMonth() = selectMonth(_selected.value.minusMonths(1))

    fun nextMonth() = selectMonth(_selected.value.plusMonths(1))

    /** 把目标月夹到 `[最早流水月, 当前月]`；账本为空（还没算出最早月）时只允许当前月。 */
    private fun clampMonth(target: YearMonth): YearMonth {
        val now = YearMonth.now()
        val lower = earliestMonth ?: return now
        return when {
            target.isAfter(now) -> now
            target.isBefore(lower) -> lower
            else -> target
        }
    }

    /**
     * 构建可选月份列表（降序）。
     *
     * 只在首次加载时算一次并缓存。账本为空 ⇒ 退化为 `[当前月]`（不报错、不死循环）。
     * `while` 留了 240 个月（20 年）上限兜底：即便最早流水是脏数据（如 1970 年），
     * 也不会构造出上千个 chip 把页面拖死。
     */
    private suspend fun buildMonthsIfNeeded() {
        if (monthsBuilt) return
        monthsBuilt = true
        val zone = ZoneId.systemDefault()
        val earliestIndex = runCatching {
            FreedomMath.earliestYearMonthIndex(
                txns = container.repository.listAll(includeTransfers = true),
                zone = zone,
            )
        }.getOrNull()
        earliestMonth = earliestIndex?.let { YearMonth.of(it / 12, it % 12 + 1) }

        val now = YearMonth.now()
        val start = earliestMonth?.takeIf { it.isBefore(now) } ?: now
        val months = ArrayList<YearMonth>()
        var cursor = now
        while (!cursor.isBefore(start) && months.size < 240) {
            months += cursor
            cursor = cursor.minusMonths(1)
        }
        _state.value = _state.value.copy(availableMonths = months)
    }

    /**
     * 「日均花销」的分母：**已过去的天数**。
     *
     * 当前月 = 今天几号；过去月 = 该月总天数（如 9 月 = 30）。
     * 若一律用「今天几号」，查看已过完的 9 月会变成「月支出 ÷ 1」→ 日均虚高 30 倍。
     */
    private fun elapsedDays(yearMonth: YearMonth, zone: ZoneId): Int {
        val today = LocalDate.now(zone)
        val current = YearMonth.from(today)
        return when {
            yearMonth == current -> today.dayOfMonth.coerceAtLeast(1)
            yearMonth.isAfter(current) -> 1
            else -> yearMonth.lengthOfMonth()
        }.coerceAtLeast(1)
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        buildMonthsIfNeeded()
        val zone = ZoneId.systemDefault()
        // 读一次快照：_selected 可能在本轮订阅期间被 selectMonth 改掉，那会由 load() 重新订阅。
        val selected = _selected.value
        val isCurrentMonth = selected == YearMonth.now()
        // 过去月的窗口两端在订阅时定死即可（该月最后一毫秒不会变）；
        // **当前月不行**：右端必须随「现在」推进 —— observeRange 固定右端会把窗口焊死在订阅时刻，
        // 「今天刚落的流水」要等切页/重试才出现。当前月只给左边界（observeSince），
        // 右端在 collect 内经 TimeRange.monthOf(selected, 实时 now) 夹紧。
        val monthStart = TimeRange.monthOf(selected, System.currentTimeMillis()).startMillis
        val upstream = if (isCurrentMonth) {
            container.repository.observeSince(monthStart)
        } else {
            val fixed = TimeRange.monthOf(selected, System.currentTimeMillis())
            container.repository.observeRange(fixed.startMillis, fixed.endInclusiveMillis, includeTransfers = true)
        }
        // 注意：observeSince 不过滤类型（含 REFUND 与 TRANSFER），本页口径需要退款参与
        //（划转由 computeInsightsFacts / 快照契约自行剔除）。
        upstream
            .flowOn(Dispatchers.Default)
            .catch { e -> _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败") }
            .collect { raw ->
                // 发射时实时窗口：当前月右端 = now（未来日期的流水不得混入本月）；
                // 过去月右端 = 该月最后一毫秒（与订阅的 observeRange 双端一致，此处仅做同一裁剪）。
                val fresh = TimeRange.monthOf(selected, System.currentTimeMillis())
                val allMonth = raw.filter { it.occurredAtMillis <= fresh.endInclusiveMillis }
                val days = elapsedDays(selected, zone)
                val facts = computeInsightsFacts(
                    allMonth = allMonth,
                    categories = container.repository.listCategories().associateBy { it.id },
                    days = days,
                    zone = zone,
                )
                val metrics = container.metricRegistry.providers().filter {
                    it.id == com.autoledger.feature.stats.MerchantTopMetric.MERCHANT_ID ||
                        it.id == PlatformShareMetric.PLATFORM_ID ||
                        it.id == com.autoledger.feature.stats.TimeCostMetric.TIME_COST_ID
                }.map {
                    // 三卡（商户/平台/时间成本）复用同一份窗口快照：allMonth 已含退款，
                    // 只需剔除内部划转即满足 MetricSnapshot 契约 —— 数据库整月窗口只查一次。
                    val snap = MetricSnapshot(
                        range = fresh,
                        txns = allMonth.filter { it.type != TxnType.TRANSFER },
                    )
                    it.compute(fresh, container.repository, snap)
                }
                // 用 copy 而非新建 State：保住 availableMonths 这类"不随月份重算"的字段。
                val months = _state.value.availableMonths
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    facts = facts,
                    metrics = metrics,
                    selectedMonth = selected,
                    canGoPrev = months.any { it.isBefore(selected) },
                    canGoNext = selected.isBefore(YearMonth.now()),
                )
            }
    }
}
