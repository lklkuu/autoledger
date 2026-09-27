package com.autoledger.feature.stats

import com.autoledger.core.model.BudgetStatus
import com.autoledger.core.model.Category
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.CategoryKind
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.math.abs

/**
 * 预算执行计算。
 *
 * 输入「分类 + 某月流水」，输出各**设了预算的支出类分类**的预算进度。
 * 纯函数、无 Android 依赖，可 JVM 单测；UI 只负责渲染 [BudgetStatus]，不参与口径计算。
 */
object BudgetCalculator {

    /**
     * @param categories 全部分类
     * @param monthTxns 某个自然月内的流水（内部划转是否包含由调用方决定；本函数只统计支出）
     * @return 按执行率从高到低排序的预算进度（只含设了正预算、未归档的支出类分类）
     */
    fun statuses(categories: List<Category>, monthTxns: List<LedgerTransaction>): List<BudgetStatus> {
        // 净支出：退款冲抵同分类（见 ExpenseMath）；负值夹到 0（退多了不算负预算）
        val spentByCategory = ExpenseMath.netExpenseByCategory(monthTxns)
            .mapValues { (_, net) -> net.coerceAtLeast(0L) }

        return categories
            .asSequence()
            .filter { it.kind == CategoryKind.EXPENSE && !it.archived }
            .mapNotNull { category ->
                val budget = category.monthlyBudgetMinor ?: return@mapNotNull null
                if (budget <= 0L) return@mapNotNull null
                BudgetStatus(
                    categoryId = category.id,
                    categoryName = category.name,
                    colorHex = category.colorHex,
                    iconKey = category.iconKey,
                    budgetMinor = budget,
                    spentMinor = spentByCategory[category.id] ?: 0L,
                )
            }
            .sortedByDescending { it.ratio }
            .toList()
    }
}
