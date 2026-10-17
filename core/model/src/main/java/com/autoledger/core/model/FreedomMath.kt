package com.autoledger.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * 「自由」页的口径计算 —— 与 [ExpenseMath] 同理，抽成不依赖 Android / Room 的纯函数，
 * 让口径能被 JVM 单测直接钉死：UI 只负责展示，不该自己发明公式。
 *
 * ## 两个指标（用户明确要求）
 * ```
 * 当月已攒 = 当月收入 − 当月支出
 * 累计已攒 = 逐月收入之和 − 累计支出 + 当前存款
 * ```
 *
 * ## v8 口径变更：收入项不再自己取数
 * 改造前两个公式的收入项是「到手月薪 × 使用月数」（乘法压缩），一旦用户改时薪，
 * 历史月份会被**整体重算** —— 这正是用户明令禁止的回溯覆盖。
 * 现在收入项由调用方经 [WageLedger] 逐月求和后传入（[cumulativeSavedUpMinor] 的第一个参数），
 * 本对象只做加减，不再持有任何"月薪"概念。
 *
 * ## 「使用月数」的定义
 * 从**账本最早一笔流水所在的月份**到**当月**，按**自然月**计数，**含首尾**：
 * 3 月开始记、现在是 5 月 → 使用月数 = 3（3 月、4 月、5 月）。
 *
 * ⚠️ **这是刻意的近似，不是 bug**：中间没有流水的月份照样算一个月收入。
 * 因此补录一笔很久以前的历史流水会把起始月突然拉前，累计已攒随之跳变 ——
 * 这是「按自然月计数」公式的固有含义。若哪天要改成
 * 「只统计有流水的月份」，必须**改这里的公式 + 改单测**，不能在 UI 层偷偷补偿。
 *
 * ## 一律不做 `coerceAtLeast(0)`
 * 支出大于「累计收入 + 存款」是真实且有意义的状态（在吃老本），
 * 夹到 0 会让用户误以为收支平衡。负值 / 0 都是合法结果，
 * 展示层负责把符号显示出来（见 `Long.yuan(withSign = true)` —— 默认的 `yuan()` 会吞掉负号）。
 */
object FreedomMath {

    // ------------------------------------------------------------------ 当月已攒

    /**
     * 当月已攒（分）= [monthlyIncomeMinor] − [monthlyExpenseMinor]。
     *
     * @param monthlyIncomeMinor 当月收入（分），由 [WageLedger.incomeForMonth] 给出。
     *   **不是** INCOME 流水合计，也**不是**「到手月薪」——见 [WageLedger] 的口径定义。
     *
     * 注意：**不含存款**。存款是「过去的积累」，属于累计口径的加项，
     * 混进当月会让它看起来像这个月又赚了一笔。
     */
    fun monthlySavedUpMinor(
        monthlyIncomeMinor: Long,
        monthlyExpenseMinor: Long,
    ): Long = monthlyIncomeMinor - monthlyExpenseMinor

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

    /**
     * [yearMonthIndex] 的展示文本（`2025-03`）。用于页面上的「起始月」标签，
     * **也是** `WageLedger.cumulativeIncomeMinor(fromMonth=…)` 的入参来源。
     *
     * ⚠️ 手工补零，**不能用** `String.format("%04d-%02d")`：后者走默认 `Locale`，
     * 在阿拉伯语 / 波斯语等区域会输出非 ASCII 数字（`٢٠٢٥-٠٣`），
     * 而 `WageLedger.isValidMonth` 是严格 ASCII 校验 ⇒ `fromMonth` 被判非法 ⇒
     * `cumulativeIncomeMinor` 直接返回 0 ⇒ **累计已攒的收入项静默变 0**（当月已攒却正常）。
     * 这类"某个区域下某个数字变 0"的 bug 极难复现，必须在源头掐掉。
     */
    fun yearMonthLabel(yearMonthIndex: Int): String =
        "${(yearMonthIndex / 12).toString().padStart(4, '0')}-" +
            "${(yearMonthIndex % 12 + 1).toString().padStart(2, '0')}"

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
     * 账本为空（[earliestYearMonthIndex] 为 `null`）→ `0`，此时 [cumulativeSavedUpMinor] 直接按 0 处理。
     *
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
     * 累计已攒（分）= [cumulativeIncomeMinor] − [cumulativeExpenseMinor] + [currentDepositMinor]。
     *
     * 与 `Σ(每个月：该月收入 − 该月支出) + 存款` 等价。
     *
     * ## 为什么收入项改成「逐月之和」而不是「月数 × 月薪」
     * v8 起收入按月版本化（[WageVersion]），跨版本时"月数 × 单个月薪"在数学上就不成立：
     * 3 月 12000、4 月起 15000 的两个月，正确合计是 27000，乘法压缩只能给出 24000 或 30000，
     * 而且会把历史月份**整体重算**。收入侧逐月求和由 [WageLedger.cumulativeIncomeMinor] 负责
     * （它按月解析生效版本），本函数只做剩下的加减。
     *
     * @param cumulativeIncomeMinor 起始月到当月的**逐月收入之和**（分）
     * @param cumulativeExpenseMinor 同期累计支出（分）
     * @param currentDepositMinor 当前存款（分，可负 = 欠款）
     *
     * ⚠️ 这里**刻意不**写成 `if (累计收入 <= 0) return 0L`：存款是用户**明确输入的真实值**，
     * 不该因为账本还空（或用户没设时薪）就被抹掉。刚装 App、先填了「当前存款 5 万」的用户，
     * 若看到「累计已攒 ¥0」只会困惑 —— 他要的是"我总共攒了多少"，而存款正是其中真实的一部分。
     * 空账本真正要防的是**报错 / NaN**，而不是把结果改成 0。
     */
    fun cumulativeSavedUpMinor(
        cumulativeIncomeMinor: Long,
        cumulativeExpenseMinor: Long,
        currentDepositMinor: Long,
    ): Long = cumulativeIncomeMinor - cumulativeExpenseMinor + currentDepositMinor

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
