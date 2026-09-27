package com.autoledger.feature.stats

import com.autoledger.core.model.Category
import com.autoledger.core.model.CategoryKind
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 预算执行计算 —— 纯 JVM 单测。 */
class BudgetCalculatorTest {

    private fun cat(
        id: String,
        name: String,
        budget: Long?,
        kind: CategoryKind = CategoryKind.EXPENSE,
        archived: Boolean = false,
    ) = Category(
        id = id, name = name, iconKey = "receipt", colorHex = "#D95F5F",
        kind = kind, monthlyBudgetMinor = budget, archived = archived,
    )

    private fun expense(id: String, minor: Long, categoryId: String, status: TxnStatus = TxnStatus.CONFIRMED) =
        Fixtures.txn(id, -minor, categoryId = categoryId).copy(status = status)

    @Test
    fun `under budget computes ratio and remaining`() {
        val status = BudgetCalculator.statuses(
            listOf(cat("c1", "餐饮", 100_000L)),
            listOf(expense("t1", 40_000L, "c1")),
        ).single()
        assertEquals(0.4, status.ratio, 1e-9)
        assertFalse(status.isOverBudget)
        assertEquals(60_000L, status.remainingMinor)
    }

    @Test
    fun `over budget flags and negative remaining`() {
        val status = BudgetCalculator.statuses(
            listOf(cat("c1", "餐饮", 100_000L)),
            listOf(expense("t1", 130_000L, "c1")),
        ).single()
        assertTrue(status.isOverBudget)
        assertEquals(-30_000L, status.remainingMinor)
        assertTrue(status.ratio > 1.0)
    }

    @Test
    fun `spending exactly the budget is not over`() {
        val status = BudgetCalculator.statuses(
            listOf(cat("c1", "餐饮", 100_000L)),
            listOf(expense("t1", 100_000L, "c1")),
        ).single()
        assertFalse(status.isOverBudget)
        assertEquals(0L, status.remainingMinor)
    }

    @Test
    fun `category without budget is excluded`() {
        val out = BudgetCalculator.statuses(listOf(cat("c1", "餐饮", null)), listOf(expense("t1", 10_000L, "c1")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `zero or negative budget is excluded`() {
        val out = BudgetCalculator.statuses(listOf(cat("c1", "餐饮", 0L), cat("c2", "交通", -5L)), emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `archived and income categories are excluded`() {
        val out = BudgetCalculator.statuses(
            listOf(
                cat("c1", "餐饮", 100_000L, archived = true),
                cat("c2", "工资", 100_000L, kind = CategoryKind.INCOME),
            ),
            emptyList(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `only confirmed expenses count, transfers income and merged are ignored`() {
        val txns = listOf(
            expense("t1", 10_000L, "c1"),
            Fixtures.txn("t2", -50_000L, categoryId = "c1", type = TxnType.TRANSFER),
            Fixtures.txn("t3", 20_000L, categoryId = "c1", type = TxnType.INCOME),
            expense("t4", 99_000L, "c1", TxnStatus.MERGED),
        )
        val status = BudgetCalculator.statuses(listOf(cat("c1", "餐饮", 100_000L)), txns).single()
        assertEquals(10_000L, status.spentMinor)
    }

    @Test
    fun `sorted by ratio descending`() {
        val cats = listOf(cat("c1", "餐饮", 100_000L), cat("c2", "交通", 100_000L))
        val txns = listOf(expense("t1", 20_000L, "c1"), expense("t2", 90_000L, "c2"))
        val out = BudgetCalculator.statuses(cats, txns)
        assertEquals(listOf("c2", "c1"), out.map { it.categoryId })
    }
}
