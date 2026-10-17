package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.FreedomMath
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.WageLedger
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

// ------------------------------------------------------------------ 自由基金 / 发现

class FreedomStore(private val container: AppContainer) {

    data class State(
        val targetMinor: Long = 0L,
        val currentMinor: Long = 0L,
        /**
         * **当月收入（分）**：`WageLedger.incomeForMonth` 按月版本算出的应计收入。
         *
         * 既不是 INCOME 流水合计（覆盖不全），也不是「到手月薪」原值
         * （13 薪用户会少算 1/12）。无版本时恒为 0。
         */
        val monthlyIncomeMinor: Long = 0L,
        /**
         * **起始月到当月的逐月收入之和（分）**：`WageLedger.cumulativeIncomeMinor`。
         *
         * 取代了旧口径的「使用月数 × 固定月薪」—— 一旦时薪变化，乘法压缩会把历史月份
         * 整体重算（回溯覆盖），逐月求和才是正确口径。无版本 / 空账本时为 0。
         */
        val cumulativeIncomeMinor: Long = 0L,
        /** 当月结余（分）= [monthlyIncomeMinor] − [monthlyExpenseMinor]。 */
        val monthlySurplusMinor: Long = 0L,
        /** 当月支出（分）：「当月已攒」公式的扣减项，由 ExpenseMath 口径算出。 */
        val monthlyExpenseMinor: Long = 0L,
        /** 起始月起到现在的累计支出（分）：「累计已攒」公式的扣减项。 */
        val cumulativeExpenseMinor: Long = 0L,
        /**
         * 「开始使用的月份」= 账本**最早一笔流水所在的月份**（年月序号，见 [FreedomMath.yearMonthIndex]）。
         * 账本为空时为 `null` —— 此时 [monthsUsed] = 0、累计收入按 0 处理。
         *
         * 刻意**不用** App 安装时间：用户可能装了很久才开始记，也可能补录历史账单。
         */
        val startYearMonth: Int? = null,
        /** 使用月数（含起始月与当月），账本为空时为 0。见 [FreedomMath.monthsUsed]。 */
        val monthsUsed: Int = 0,
        /**
         * 是否存在时薪版本。
         *
         * 为 `false` 时收入恒为 0（[WageLedger] 对空版本返回 0），页面必须显式说明
         * 「未设置时薪」并指路，而不是让用户以为"这个月没赚钱"。
         */
        val hasWageVersion: Boolean = false,
        /**
         * 版本表里**全是迁移预填**的行（`createdAtMillis == 0`，用户升级后还没在 v8 里保存过时薪）。
         *
         * 与 [hasWageVersion] 组合出三种状态：没有版本（收入 0，需去设置）/
         * 只有预填（收入来自升级前的时薪快照，需用户确认）/ 用户已确认。
         * 判据由 [WageLedger.allMigrationSeeded] 统一给出，UI 不自己发明规则。
         */
        val wageVersionSeeded: Boolean = false,
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
        val zone = TimeRange.ZONE
        // 三个源一起订阅：目标/存款 + **时薪版本** + **全量**流水。
        // 之所以从「当月窗口」换成全量：累计口径需要两样当月窗口给不了的东西 ——
        //   ① 最早一笔流水所在月份（起始月）；② 起始月至今的累计支出。
        // 当月支出就在内存里按月份窗口裁出来，省掉一路 observeRange 订阅。
        //
        // ⚠️ versions 必须进 combine：用户改时薪后两个「已攒」要立刻刷新，否则收入已变、
        // 页面还显示旧值（而这正是用户最关心的那个数字）。
        // ⚠️ 刻意**不再**订阅 `settings.wage`：收入已完全由版本表决定，app_settings 的月薪快照
        // 只服务「时薪页编辑器初值 / 旧备份 / 无版本兜底」，把它留在 combine 里只会制造
        // 一次无意义的重新聚合，且容易让人误以为它还是收入来源。
        combine(
            container.settings.goal,
            container.wages.versions,
            container.repository.observeAll(includeTransfers = true),
        ) { goal, versions, txns -> Triple(goal, versions, txns) }
            .flowOn(Dispatchers.Default)
            .collect { (goal, versions, txns) ->
                val now = System.currentTimeMillis()
                val month = TimeRange.thisMonth(now)
                val monthTxns = txns.filter { it.occurredAtMillis in month.startMillis..month.endInclusiveMillis }
                val expense = ExpenseMath.netExpenseMinor(monthTxns).coerceAtLeast(0L)
                // 当月收入：按月版本取应计值（不再对 INCOME 流水求和 —— 那是第三套算法）。
                val currentMonthKey = WageLedger.monthKeyOf(now, zone)
                val income = WageLedger.incomeForMonth(versions, currentMonthKey)
                // 累计支出只算到「现在」：未来日期的流水（预授权 / 跨时区账单）不该把累计支出抬高。
                val cumulativeExpense = ExpenseMath
                    .netExpenseMinor(txns.filter { it.occurredAtMillis <= now })
                    .coerceAtLeast(0L)
                // 起始月取全量流水里最早一笔（含未来流水），口径见 FreedomMath.earliestYearMonthIndex。
                val startYearMonth = FreedomMath.earliestYearMonthIndex(txns, zone)
                // 累计收入：从起始月到当月**逐月**求和（跨版本正确）；无起始月 = 0，不再"月数 × 月薪"。
                val cumulativeIncome = if (startYearMonth == null) {
                    0L
                } else {
                    WageLedger.cumulativeIncomeMinor(
                        versions = versions,
                        fromMonth = FreedomMath.yearMonthLabel(startYearMonth),
                        toMonth = currentMonthKey,
                    )
                }
                _state.value = State(
                    targetMinor = goal.targetMinor,
                    currentMinor = goal.currentMinor,
                    monthlyIncomeMinor = income,
                    cumulativeIncomeMinor = cumulativeIncome,
                    monthlySurplusMinor = income - expense,
                    monthlyExpenseMinor = expense,
                    cumulativeExpenseMinor = cumulativeExpense,
                    startYearMonth = startYearMonth,
                    monthsUsed = FreedomMath.monthsUsed(startYearMonth, FreedomMath.yearMonthIndex(now, zone)),
                    hasWageVersion = versions.isNotEmpty(),
                    wageVersionSeeded = WageLedger.allMigrationSeeded(versions),
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
