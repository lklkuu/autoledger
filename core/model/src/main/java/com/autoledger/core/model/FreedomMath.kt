package com.autoledger.core.model

/**
 * 「自由」页的口径计算 —— 与 [ExpenseMath] 同理，抽成不依赖 Android / Room 的纯函数，
 * 让口径能被 JVM 单测直接钉死：UI 只负责展示，不该自己发明公式。
 *
 * 公式（用户明确要求）：
 * ```
 * 已攒 = 到手月薪收入 − 当月支出 + 当前存款
 * ```
 */
object FreedomMath {

    /**
     * 已攒金额（分）= [monthlyNetSalaryMinor] − [monthlyExpenseMinor] + [currentDepositMinor]。
     *
     * 刻意**不做** `coerceAtLeast(0)`：支出大于「月薪 + 存款」是真实且有意义的状态
     * （这个月已经在吃老本），夹到 0 会让用户误以为收支平衡。
     * 负值 / 0 都是合法结果，展示层负责把符号显示出来
     * （见 `Long.yuan(withSign = true)` —— 默认的 `yuan()` 会吞掉负号）。
     */
    fun savedUpMinor(
        monthlyNetSalaryMinor: Long,
        monthlyExpenseMinor: Long,
        currentDepositMinor: Long,
    ): Long = monthlyNetSalaryMinor - monthlyExpenseMinor + currentDepositMinor

    /**
     * 进度 = 已攒 / 目标，**封顶 100%**。
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
