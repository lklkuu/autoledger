package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.Category
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.MetricSnapshot
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.withRangeLabel
import com.autoledger.feature.stats.MonthlyTrendMetric
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ 今日

class HomeStore(private val container: AppContainer) {

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val todayMinor: Long = 0L,
        /** 本月净支出（已扣退款） */
        val monthMinor: Long = 0L,
        /** 本月付款总额（毛支出，未扣退款） */
        val monthGrossMinor: Long = 0L,
        /** 本月退款合计（供账单"退款冲抵"展示） */
        val monthRefundMinor: Long = 0L,
        val realHourly: Double = 0.0,
        val recent: List<LedgerTransaction> = emptyList(),
        val categories: Map<String, Category> = emptyMap(),
        val metrics: List<MetricResult> = emptyList(),
        val pendingReview: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 实例级作用域：每个 HomeStore 独占一条**可取消**的生命周期。
     *
     * 之前所有 Store 共用一个进程级 storeScope（永不取消），而 Dashboard 每次进入都会
     * remember 一个新的 HomeStore 并新开一条数据库订阅，退出后旧订阅仍在跑 ——
     * 「首页 ↔ 其它页」来回几次就线性泄漏几条订阅。改为实例级后，界面 onDispose 调 [close] 即可精确回收。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)

    /** 实时订阅句柄。 */
    private var observeJob: Job? = null

    /**
     * 启动/重启实时订阅（幂等）：写入后由 Room Flow 触发自动刷新。
     * 重复调用（含错误页「重试」）会先取消旧订阅再重建，不会叠加。
     */
    fun load() {
        observeJob?.cancel()
        // 不要写成 `_state.value = State()`：那会把 loading 从 false 拉回 true 且清空已有数据，
        // 每次刷新/重试都闪一下 LoadingBox。这里只翻转 loading 与错误位，保留旧数据（R6）。
        _state.value = _state.value.copy(loading = true, error = null)
        observeJob = scope.launch { observe() }
    }

    /** 释放数据库订阅；由 Compose 的 DisposableEffect 在离开首页时调用（配合实例级作用域消除泄漏，R1）。 */
    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    /**
     * 同时订阅两条 Flow 并合成一次聚合：
     * - 本月流水（observeSince）：驱动今日/本月金额、最近流水、统计卡片；
     * - 待确认数量（observeRawCount）：**独立订阅**，与时间窗口解耦 ——
     *   补录一笔更早日期的流水时窗口集合不变，但待确认卡片仍要刷新（R3）。
     */
    private suspend fun observe() {
        // 月初锚点订阅时取一次即可（本订阅生命周期内不变）；
        // 右端（now）**不在订阅时定死** —— 每次发射都经 [spendingWindow] 重新取实时 now，
        // 否则长驻订阅里「今天刚落的流水」会被订阅时刻的旧右端裁掉，首页看起来"没记上"。
        val monthStart = TimeRange.thisMonth(System.currentTimeMillis()).startMillis
        combine(
            container.repository.observeSince(monthStart),
            container.repository.observeRawCount(),
        ) { rawList, rawCount ->
            // 裁剪在 flowOn(Dispatchers.Default) 上游执行（R5，离主线程）
            clipToMonthSpending(rawList, spendingWindow(monthStart, System.currentTimeMillis())) to rawCount
        }
            .flowOn(Dispatchers.Default) // 过滤/聚合移出主线程（R5）
            .catch { e ->
                // 上游（Room Flow）发射异常不能打崩进程，收敛为可见错误态（R2）。
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
            .collect { (monthTxns, rawCount) ->
                // 统计卡片的窗口同样用发射时的实时右端（与裁剪窗一致）
                val fresh = spendingWindow(monthStart, System.currentTimeMillis())
                applyResult(catching { compute(fresh, monthTxns, rawCount) })
            }
    }

    private fun applyResult(result: Result<State>) {
        result.onSuccess { _state.value = it }
            // 失败时保留旧数据，只补充错误信息，避免整屏清空。
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "加载失败") }
    }

    /**
     * @param monthTransactions Flow 推送的本月流水（已剔除内部划转、**保留退款**，并裁剪到本月窗口，见 [clipToMonthSpending]）。
     * @param rawCount Flow 推送的全表待确认数量（与时间窗口解耦）。
     */
    private suspend fun compute(
        month: TimeRange,
        monthTransactions: List<LedgerTransaction>,
        rawCount: Int,
    ): State = withContext(Dispatchers.Default) { // 排序/求和等 CPU 聚合移出主线程（R5）
        val now = System.currentTimeMillis()
        val today = TimeRange.today(now)
        val todayTxns = monthTransactions.filter { it.occurredAtMillis in today.startMillis..today.endInclusiveMillis }
        // 上提公共取数：分类字典查一次；窗口流水本就在内存里（Flow 推送的 monthTransactions），
        // 装进快照传给各统计卡片，四张卡不再各自 listRange 把同一窗口重复拉 N 遍。
        // 快照口径：monthTransactions 已剔除 TRANSFER、保留 REFUND、由 Flow 排除 MERGED（契约见 MetricSnapshot）。
        val categories = container.repository.listCategories()
        val snap = MetricSnapshot(range = month, txns = monthTransactions, categories = categories)
        // v1.1.9：「消费平台分布」不再从今日页剔除（同一屏既有消费结构也有平台结构，信息互补）；
        // 「月度趋势」则迁到账单页（它是跨月宽窗，放在只看本月的今日页里口径突兀）。
        val metrics = container.metricRegistry.providers()
            .filter { it.id != MonthlyTrendMetric.TREND_ID }
            .map { it.compute(month, container.repository, snap).withRangeLabel("本月") }
        State(
            loading = false,
            // 净支出（退款冲抵），口径唯一真源见 ExpenseMath
            todayMinor = ExpenseMath.netExpenseMinor(todayTxns).coerceAtLeast(0L),
            monthMinor = ExpenseMath.netExpenseMinor(monthTransactions).coerceAtLeast(0L),
            // 付款总额（毛支出）：与净支出/退款同源，均走 ExpenseMath，避免 UI 手写 sumOf 造成口径漂移
            monthGrossMinor = ExpenseMath.grossExpenseMinor(monthTransactions),
            monthRefundMinor = ExpenseMath.refundMinor(monthTransactions),
            realHourly = container.settings.wage.value.realHourly,
            recent = monthTransactions.sortedByDescending { it.occurredAtMillis }.take(4),
            categories = categories.associateBy { it.id },
            metrics = metrics,
            pendingReview = rawCount,
        )
    }
}
