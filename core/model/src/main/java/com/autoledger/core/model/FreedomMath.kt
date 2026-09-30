package com.autoledger.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * 「自由」页的口径计算 —— 与 [ExpenseMath] 同理，抽成不依赖 Android / Room 的纯函数，
 * 让口径能被 JVM 单测直接钉死：UI 只负责展示，不该自己发明公式。
 *
 * ## 两个指标（用户明确要求）
 * ```
 * 当月已攒 = 到手月薪 − 当月支出
 * 累计已攒 = (使用月数 × 到手月薪) − 累计支出 + 当前存款
 * ```
 *
 * ## 「使用月数」的定义
 * 从**账本最早一笔流水所在的月份**到**当月**，按**自然月**计数，**含首尾**：
 * 3 月开始记、现在是 5 月 → 使用月数 = 3（3 月、4 月、5 月）。
 *
 * ⚠️ **这是刻意的近似，不是 bug**：中间没有流水的月份照样算一个月工资。
 * 因此补录一笔很久以前的历史流水会把起始月突然拉前，累计已攒随之跳变 ——
 * 这是「按自然月 × 固定月薪」公式的固有含义。若哪天要改成
 * 「只统计有流水的月份」或「用当月账本实际收入替代月薪」，必须**改这里的公式 + 改单测**，
 * 不能在 UI 层偷偷补偿。
 *
 * ## 「月薪」为什么用固定值而不是账本收入
 * 账本里的 `INCOME` 流水来自通知抓取，**覆盖不全**：某月没抓到工资短信，
 * 该月就会被算成净支出，累计值被严重低估且不可复现。
 * `WageProfile.monthlyNetSalaryMinor` 是用户主动填的稳定值，口径可解释、可测。
 *
 * ## 一律不做 `coerceAtLeast(0)`
 * 支出大于「月薪 × 月数 + 存款」是真实且有意义的状态（在吃老本），
 * 夹到 0 会让用户误以为收支平衡。负值 / 0 都是合法结果，
 * 展示层负责把符号显示出来（见 `Long.yuan(withSign = true)` —— 默认的 `yuan()` 会吞掉负号）。
 */
object FreedomMath {

    // ------------------------------------------------------------------ 当月已攒

    /**
     * 当月已攒（分）= [monthlyNetSalaryMinor] − [monthlyExpenseMinor]。
     *
     * 注意：**不含存款**。存款是「过去的积累」，属于累计口径的加项，
     * 混进当月会让它看起来像这个月又赚了一笔。
     */
    fun monthlySavedUpMinor(
        monthlyNetSalaryMinor: Long,
        monthlyExpenseMinor: Long,
    ): Long = monthlyNetSalaryMinor - monthlyExpenseMinor

    // ------------------------------------------------------------------ 起始月 / 使用月数

    /**
     * 把一个时间戳折算成「年月序号」= `year * 12 + (month - 1)`。
     *
     * 用序号而不是 `yyyyMM` 整数：后者在算月份差时要自己处理进位（202512 → 202601 差 1 个月
     * 但数值差 89），序号做减法即可，边界不会出错。
     */
    fun yearMonthIndex(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return date.year * 12 + (date.monthValue - 1)
    }

    /** [yearMonthIndex] 的展示文本（`2025-03`）。用于页面上的「起始月」标签。 */
    fun yearMonthLabel(yearMonthIndex: Int): String =
        "%04d-%02d".format(yearMonthIndex / 12, yearMonthIndex % 12 + 1)

    /**
     * 账本最早一笔流水所在的月份序号；账本为空时返回 `null`。
     *
     * 只看 `occurredAtMillis`（流水**发生**时间），不看 `bookedAtMillis`（入账时间）：
     * 补录一笔去年的账单不该把「开始使用的月份」推到今年。
     */
    fun earliestYearMonthIndex(
        txns: List<LedgerTransaction>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? = txns.minByOrNull { it.occurredAtMillis }?.let { yearMonthIndex(it.occurredAtMillis, zone) }

    /**
     * 使用月数（含起始月与当月）。
     *
     * - 账本为空（[earliestYearMonthIndex] 为 `null`）→ `0`，此时 [cumulativeSavedUpMinor] 直接按 0 处理。
     * - 至少为 `1`：只要账本里有流水就至少算一个月。起始月晚于当月
     *   （存在未来日期的流水，如预授权）时也不会算出 0 或负数 —— 这是**计数**不是金额，
     *   夹下界是为了避免出现「有流水却算 0 个月」的自相矛盾状态。
     */
    fun monthsUsed(earliestYearMonthIndex: Int?, currentYearMonthIndex: Int): Int =
        if (earliestYearMonthIndex == null) {
            0
        } else {
            (currentYearMonthIndex - earliestYearMonthIndex + 1).coerceAtLeast(1)
        }

    // ------------------------------------------------------------------ 累计已攒

    /**
     * 累计已攒（分）= [monthsUsed] × [monthlyNetSalaryMinor] − [cumulativeExpenseMinor] + [currentDepositMinor]。
     *
     * 与 `Σ(每个月：月薪 − 该月支出) + 存款` 等价，只是把逐月求和压成一次乘法。
     *
     * @param monthsUsed 使用月数，见 [monthsUsed]。**0 = 账本为空**，此时公式照跑：
     *   累计 = 0 − 0 + 存款 = **存款**。
     *
     *   ⚠️ 这里**刻意不**写成 `if (monthsUsed <= 0) return 0L`：存款是用户**明确输入的真实值**，
     *   不该因为账本还空就被抹掉。刚装 App、先填了「当前存款 5 万」的用户，
     *   若看到「累计已攒 ¥0」只会困惑 —— 他要的是"我总共攒了多少"，而存款正是其中真实的一部分。
     *   空账本真正要防的是**报错 / NaN**，而不是把结果改成 0。
     */
    fun cumulativeSavedUpMinor(
        monthsUsed: Int,
        monthlyNetSalaryMinor: Long,
        cumulativeExpenseMinor: Long,
        currentDepositMinor: Long,
    ): Long = monthsUsed.toLong().coerceAtLeast(0L) * monthlyNetSalaryMinor -
        cumulativeExpenseMinor + currentDepositMinor

    // ------------------------------------------------------------------ 进度 / 还差

    /**
     * 进度 = 累计已攒 / 目标，**封顶 100%、下限 0%**。
     *
     * 封顶必须在**这里**做而不能只靠 ProgressLine：进度条内部会 clamp，
     * 但旁边的「进度 XX%」文字用的是原始值，不封顶会显示「108.0%」这种怪数字。
     * 目标未填（<= 0）时为 0，避免除零。
     */
    fun progressOf(savedUpMinor: Long, targetMinor: Long): Float =
        if (targetMinor <= 0L) {
            0f
        } else {
            (savedUpMinor.toDouble() / targetMinor.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    /**
     * 还差多少（分）。
     *
     * ⚠️ **只有这个展示值**才夹到 0：已攒超过目标时差额是负的，
     * 显示「还差 ¥-5000」没有意义，显示 0 即代表已达标。
     * 绝不能反过来把 [savedUpMinor] 本身夹断 —— 负值是有意义的真实状态（在吃老本）。
     */
    fun remainingMinor(savedUpMinor: Long, targetMinor: Long): Long =
        (targetMinor - savedUpMinor).coerceAtLeast(0L)
}
