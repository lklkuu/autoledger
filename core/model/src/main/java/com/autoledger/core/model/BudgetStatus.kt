package com.autoledger.core.model

/**
 * 某个分类在某个月份的预算执行情况（纯数据）。
 *
 * 由 feature:stats 的预算计算产出、UI 负责渲染；金额一律「分」，比率用 [ratio] 派生，避免浮点存取。
 */
data class BudgetStatus(
    val categoryId: String,
    val categoryName: String,
    val colorHex: String,
    val iconKey: String,
    /** 月度预算（分），恒 > 0 */
    val budgetMinor: Long,
    /** 当月已花费（分，正数） */
    val spentMinor: Long,
) {
    /** 执行率；> 1 表示超支 */
    val ratio: Double get() = if (budgetMinor <= 0L) 0.0 else spentMinor.toDouble() / budgetMinor

    /** 是否超支 */
    val isOverBudget: Boolean get() = spentMinor > budgetMinor

    /** 剩余（分）；为负表示超支额度 */
    val remainingMinor: Long get() = budgetMinor - spentMinor
}
