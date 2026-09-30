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
}
