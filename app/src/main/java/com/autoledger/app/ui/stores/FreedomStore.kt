package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.FreedomMath
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.TxnType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.ZoneId

// ------------------------------------------------------------------ 自由基金 / 发现

class FreedomStore(private val container: AppContainer) {

    data class State(
        val targetMinor: Long = 0L,
        val currentMinor: Long = 0L,
        /** 到手月薪（分）：来自时薪页的 WageProfile，两个「已攒」公式的收入项。 */
        val monthlyNetSalaryMinor: Long = 0L,
        val monthlySurplusMinor: Long = 0L,
        /** 当月支出（分）：「当月已攒」公式的扣减项，由 ExpenseMath 口径算出。 */
        val monthlyExpenseMinor: Long = 0L,
        /** 起始月起到现在的累计支出（分）：「累计已攒」公式的扣减项。 */
        val cumulativeExpenseMinor: Long = 0L,
        /**
         * 「开始使用的月份」= 账本**最早一笔流水所在的月份**（年月序号，见 [FreedomMath.yearMonthIndex]）。
         * 账本为空时为 `null` —— 此时 [monthsUsed] = 0、累计已攒按 0 处理。
         *
         * 刻意**不用** App 安装时间：用户可能装了很久才开始记，也可能补录历史账单。
         */
        val startYearMonth: Int? = null,
        /** 使用月数（含起始月与当月），账本为空时为 0。见 [FreedomMath.monthsUsed]。 */
        val monthsUsed: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：改为实例级作用域 + Flow 订阅（settings.goal 是 StateFlow，可直接 combine）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        observeJob = scope.launch { observe() }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        val zone = ZoneId.systemDefault()
        // 三个源一起订阅：目标/存款 + 到手月薪 + **全量**流水。
        // 之所以从「当月窗口」换成全量：累计口径需要两样当月窗口给不了的东西 ——
        //   ① 最早一笔流水所在月份（起始月）；② 起始月至今的累计支出。
        // 当月支出就在内存里按月份窗口裁出来，省掉一路 observeRange 订阅。
        // 月薪 / 流水 / 目标都是 Flow，任一侧变化都会重新发射 → 两个「已攒」实时刷新。
        combine(
            container.settings.goal,
            container.settings.wage,
            container.repository.observeAll(includeTransfers = true),
        ) { goal, wage, txns -> Triple(goal, wage, txns) }
            .flowOn(Dispatchers.Default)
            .collect { (goal, wage, txns) ->
                val now = System.currentTimeMillis()
                val month = TimeRange.thisMonth(now)
                val monthTxns = txns.filter { it.occurredAtMillis in month.startMillis..month.endInclusiveMillis }
                val expense = ExpenseMath.netExpenseMinor(monthTxns).coerceAtLeast(0L)
                val income = monthTxns.filter { it.type == TxnType.INCOME }.sumOf { kotlin.math.abs(it.amountMinor) }
                // 累计支出只算到「现在」：未来日期的流水（预授权 / 跨时区账单）不该把累计支出抬高。
                val cumulativeExpense = ExpenseMath
                    .netExpenseMinor(txns.filter { it.occurredAtMillis <= now })
                    .coerceAtLeast(0L)
                // 起始月取全量流水里最早一笔（含未来流水），口径见 FreedomMath.earliestYearMonthIndex。
                val startYearMonth = FreedomMath.earliestYearMonthIndex(txns, zone)
                _state.value = State(
                    targetMinor = goal.targetMinor,
                    currentMinor = goal.currentMinor,
                    monthlyNetSalaryMinor = wage.monthlyNetSalaryMinor,
                    monthlySurplusMinor = income - expense,
                    monthlyExpenseMinor = expense,
                    cumulativeExpenseMinor = cumulativeExpense,
                    startYearMonth = startYearMonth,
                    monthsUsed = FreedomMath.monthsUsed(startYearMonth, FreedomMath.yearMonthIndex(now, zone)),
                )
            }
    }

    /**
     * 保存目标。「已攒」不再由用户填写，因此这里只落「目标金额 + 当前存款」。
     *
     * @param currentMinor 当前存款（分）。它同时是「已攒」公式的加项，
     *   页面上的输入框未保存也会实时参与展示（见 FreedomScreen）。
     */
    fun saveGoal(targetMinor: Long, currentMinor: Long) {
        // B4：settings.goal 是 StateFlow，updateGoal 后 combine 会自动重新发射，无需手动 load()。
        container.settings.updateGoal(FreedomGoal(targetMinor, currentMinor))
    }
}
