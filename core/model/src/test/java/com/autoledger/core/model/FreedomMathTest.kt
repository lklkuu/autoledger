package com.autoledger.core.model

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「自由」页两个「已攒」指标的纯 JVM 单测。
 *
 * 口径（唯一真源见 [FreedomMath]）：
 * ```
 * 当月已攒 = 到手月薪 − 当月支出
 * 累计已攒 = (使用月数 × 到手月薪) − 累计支出 + 当前存款
 * 使用月数 = 账本最早一笔流水所在月份 → 当月（按自然月，含首尾）
 * ```
 *
 * 重点覆盖：
 * - 起始月为空（账本没流水）/ 只有一个月 / 跨多个月（含跨年）
 * - 支出大于月薪 → **负累计**必须保留，不得夹断
 * - 存款为负、月薪为 0 都得照常算
 * - 进度与「还差」跟随**累计已攒**
 */
class FreedomMathTest {

    // ------------------------------------------------------------------ 夹具

    /** 固定时区：避免 CI 机器时区不同导致月份边界漂移。 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(year: Int, month: Int, day: Int = 1): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun index(year: Int, month: Int): Int = year * 12 + (month - 1)

    private fun txn(
        id: String,
        at: Long,
        type: TxnType = TxnType.EXPENSE,
        amountMinor: Long = -1_000L,
        bookedAt: Long = at,
    ): LedgerTransaction = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = at,
        bookedAtMillis = bookedAt,
        type = type,
        sourceId = "test",
        sourceRef = id,
    )

    // ------------------------------------------------------------------ 当月已攒

    @Test
    fun `monthly saved up is salary minus this month expense`() {
        // 月薪 12000 元 − 当月支出 4500 元 = 7500 元
        assertEquals(
            750_000L,
            FreedomMath.monthlySavedUpMinor(monthlyNetSalaryMinor = 1_200_000L, monthlyExpenseMinor = 450_000L),
        )
    }

    @Test
    fun `monthly saved up ignores the deposit`() {
        // 存款是过去的积累，只进「累计已攒」；当月已攒与它无关。
        // 这条锁住两个指标不会因为「存款被算了两次」而对不上。
        assertEquals(
            -200_000L,
            FreedomMath.monthlySavedUpMinor(monthlyNetSalaryMinor = 1_000_000L, monthlyExpenseMinor = 1_200_000L),
        )
    }

    @Test
    fun `monthly saved up of exactly zero is zero not clamped`() {
        assertEquals(0L, FreedomMath.monthlySavedUpMinor(800_000L, 800_000L))
    }

    @Test
    fun `monthly saved up keeps its negative sign`() {
        // 支出 15000 > 月薪 12000 → −3000 元（这个月在吃老本）
        assertEquals(-300_000L, FreedomMath.monthlySavedUpMinor(1_200_000L, 1_500_000L))
    }

    @Test
    fun `monthly saved up with zero salary does not crash`() {
        assertEquals(-200_000L, FreedomMath.monthlySavedUpMinor(0L, 200_000L))
        assertEquals(0L, FreedomMath.monthlySavedUpMinor(0L, 0L))
    }

    // ------------------------------------------------------------------ 起始月 / 使用月数

    @Test
    fun `year month index is contiguous across a year boundary`() {
        // 2025-12 → 2026-01 数值只差 1，月份差才能用减法算（用 yyyyMM 会差 89）。
        assertEquals(1, index(2026, 1) - index(2025, 12))
        assertEquals(index(2025, 3), FreedomMath.yearMonthIndex(millis(2025, 3, 31), zone))
    }

    @Test
    fun `year month label renders zero padded yyyy dash MM`() {
        assertEquals("2025-03", FreedomMath.yearMonthLabel(index(2025, 3)))
        assertEquals("2025-12", FreedomMath.yearMonthLabel(index(2025, 12)))
        assertEquals("2026-01", FreedomMath.yearMonthLabel(index(2026, 1)))
    }

    @Test
    fun `earliest month comes from the oldest transaction`() {
        val txns = listOf(
            txn("c", millis(2025, 5, 20)),
            txn("a", millis(2025, 3, 2)),
            txn("b", millis(2025, 4, 9)),
        )
        assertEquals(index(2025, 3), FreedomMath.earliestYearMonthIndex(txns, zone))
    }

    @Test
    fun `earliest month uses occurred time not booking time`() {
        // 补录：1 月发生、6 月才入账 → 起始月必须是 1 月，否则补录历史账单会把起始月推后。
        val txns = listOf(txn("late", millis(2025, 1, 15), bookedAt = millis(2025, 6, 1)))
        assertEquals(index(2025, 1), FreedomMath.earliestYearMonthIndex(txns, zone))
    }

    @Test
    fun `earliest month is null for an empty ledger`() {
        assertNull(FreedomMath.earliestYearMonthIndex(emptyList(), zone), "空账本没有起始月")
    }

    @Test
    fun `months used is zero for an empty ledger`() {
        assertEquals(0, FreedomMath.monthsUsed(null, index(2025, 5)))
    }

    @Test
    fun `months used is one when the ledger only has this month`() {
        assertEquals(1, FreedomMath.monthsUsed(index(2025, 5), index(2025, 5)))
    }

    @Test
    fun `months used counts every calendar month including both ends`() {
        // 3 月开始记，现在是 5 月 → 3 个月（3/4/5）
        assertEquals(3, FreedomMath.monthsUsed(index(2025, 3), index(2025, 5)))
        // 跨年：2024-11 → 2025-02 = 4 个月
        assertEquals(4, FreedomMath.monthsUsed(index(2024, 11), index(2025, 2)))
    }

    @Test
    fun `months used is at least one when the earliest transaction is in the future`() {
        // 起始月晚于当月（预授权 / 未来日期流水）：不能算出 0 或负数 —— 那是计数不是金额。
        assertEquals(1, FreedomMath.monthsUsed(index(2026, 1), index(2025, 5)))
    }

    // ------------------------------------------------------------------ 累计已攒

    @Test
    fun `cumulative for a single month is salary minus expense plus deposit`() {
        // 用了 1 个月：月薪 12000 − 支出 4500 + 存款 30000 = 37500 元
        assertEquals(
            3_750_000L,
            FreedomMath.cumulativeSavedUpMinor(
                monthsUsed = 1,
                monthlyNetSalaryMinor = 1_200_000L,
                cumulativeExpenseMinor = 450_000L,
                currentDepositMinor = 3_000_000L,
            ),
        )
    }

    @Test
    fun `cumulative over several months multiplies the salary`() {
        // 2025-03 起始、现在是 2025-05 → 3 个月
        // 3 × 12000 − 累计支出 24000 + 存款 30000 = 42000 元
        val months = FreedomMath.monthsUsed(index(2025, 3), index(2025, 5))
        assertEquals(3, months)
        assertEquals(
            4_200_000L,
            FreedomMath.cumulativeSavedUpMinor(
                monthsUsed = months,
                monthlyNetSalaryMinor = 1_200_000L,
                cumulativeExpenseMinor = 2_400_000L,
                currentDepositMinor = 3_000_000L,
            ),
        )
    }

    @Test
    fun `cumulative is zero for an empty ledger even with a deposit`() {
        // 用户口径：账本为空 → 累计已攒按 0 处理（当月已攒照常算）。
        assertEquals(
            0L,
            FreedomMath.cumulativeSavedUpMinor(
                monthsUsed = 0,
                monthlyNetSalaryMinor = 1_200_000L,
                cumulativeExpenseMinor = 0L,
                currentDepositMinor = 5_000_000L,
            ),
        )
    }

    @Test
    fun `cumulative goes negative when expenses exceed the salary`() {
        // 2 个月：2 × 10000 − 累计支出 25000 + 存款 0 = −5000 元
        assertEquals(
            -500_000L,
            FreedomMath.cumulativeSavedUpMinor(
                monthsUsed = 2,
                monthlyNetSalaryMinor = 1_000_000L,
                cumulativeExpenseMinor = 2_500_000L,
                currentDepositMinor = 0L,
            ),
            "负累计必须原样保留，绝不能 coerceAtLeast(0)",
        )
    }

    @Test
    fun `cumulative accepts a negative deposit`() {
        // 存款记成负（欠款）→ 1 × 10000 − 0 + (−3000) = 7000 元
        assertEquals(
            700_000L,
            FreedomMath.cumulativeSavedUpMinor(
                monthsUsed = 1,
                monthlyNetSalaryMinor = 1_000_000L,
                cumulativeExpenseMinor = 0L,
                currentDepositMinor = -300_000L,
            ),
        )
    }

    @Test
    fun `cumulative with zero salary does not crash`() {
        // 没填月薪：2 × 0 − 支出 2000 + 存款 10000 = 8000 元
        assertEquals(
            800_000L,
            FreedomMath.cumulativeSavedUpMinor(
                monthsUsed = 2,
                monthlyNetSalaryMinor = 0L,
                cumulativeExpenseMinor = 200_000L,
                currentDepositMinor = 1_000_000L,
            ),
        )
        // 没填月薪也没存款：−2000 元
        assertEquals(
            -200_000L,
            FreedomMath.cumulativeSavedUpMinor(
                monthsUsed = 2,
                monthlyNetSalaryMinor = 0L,
                cumulativeExpenseMinor = 200_000L,
                currentDepositMinor = 0L,
            ),
        )
    }

    @Test
    fun `cumulative grows with months and shrinks with expenses`() {
        val base = FreedomMath.cumulativeSavedUpMinor(1, 1_000_000L, 300_000L, 500_000L)
        val oneMoreMonth = FreedomMath.cumulativeSavedUpMinor(2, 1_000_000L, 300_000L, 500_000L)
        val moreExpense = FreedomMath.cumulativeSavedUpMinor(1, 1_000_000L, 400_000L, 500_000L)
        val moreDeposit = FreedomMath.cumulativeSavedUpMinor(1, 1_000_000L, 300_000L, 600_000L)
        assertEquals(base + 1_000_000L, oneMoreMonth, "每多用一个自然月，累计多一个月薪")
        assertEquals(base - 100_000L, moreExpense, "支出每多 1000 元，累计少 1000 元")
        assertEquals(base + 100_000L, moreDeposit, "存款每多 1000 元，累计多 1000 元")
    }

    @Test
    fun `cumulative stays exact in minor units for large values`() {
        // 分单位下 Long 足够；这条锁住「没有中途转 Double 导致精度丢失」
        assertEquals(
            299_999_999_997L,
            FreedomMath.cumulativeSavedUpMinor(3, 99_999_999_999L, 1L, 1L),
        )
    }

    // ------------------------------------------------------------------ 进度 / 还差（都基于累计已攒）

    @Test
    fun `progress follows the cumulative saved up amount`() {
        // 累计 30000 / 目标 120000 = 25%
        assertEquals(0.25f, FreedomMath.progressOf(3_000_000L, 12_000_000L))
    }

    @Test
    fun `progress is capped at 100 percent once the target is reached`() {
        // 累计 54000 > 目标 50000：进度必须封顶，不能显示 108%
        assertEquals(1f, FreedomMath.progressOf(5_400_000L, 5_000_000L))
        assertEquals(1f, FreedomMath.progressOf(5_000_000L, 5_000_000L), "刚好达标就是 100%")
        assertEquals(1f, FreedomMath.progressOf(9_999_999L, 1L), "远超目标同样封顶")
    }

    @Test
    fun `progress floor is zero for a negative saved up amount`() {
        // 累计为负（一直在吃老本）→ 进度 0，不得出现负进度
        assertEquals(0f, FreedomMath.progressOf(-300_000L, 5_000_000L))
    }

    @Test
    fun `progress is zero when no target is set`() {
        // 目标未填 → 不得除零，也不得显示 NaN
        assertEquals(0f, FreedomMath.progressOf(3_000_000L, 0L))
        assertEquals(0f, FreedomMath.progressOf(3_000_000L, -1L))
    }

    @Test
    fun `remaining shows zero once the target is reached`() {
        assertEquals(0L, FreedomMath.remainingMinor(5_400_000L, 5_000_000L), "已达标 → 还差 0")
        assertEquals(0L, FreedomMath.remainingMinor(5_000_000L, 5_000_000L))
        assertEquals(2_000_000L, FreedomMath.remainingMinor(3_000_000L, 5_000_000L), "未达标 → 还差 20000 元")
    }

    @Test
    fun `remaining grows when the saved up amount is negative`() {
        // 累计 −3000、目标 5000 → 还差 8000；这里允许差额大于目标，
        // 因为只有**这个展示值**夹 0，累计本身的负值必须保留（见 cumulativeSavedUpMinor 的注释）。
        assertEquals(8_000_000L, FreedomMath.remainingMinor(-3_000_000L, 5_000_000L))
    }

    @Test
    fun `progress and remaining are driven by the cumulative not the monthly figure`() {
        // 场景：起始 2025-03，现在 2025-05（3 个月），月薪 12000、累计支出 24000、存款 30000
        // → 累计已攒 42000、当月已攒 7500；目标 60000。
        val months = FreedomMath.monthsUsed(index(2025, 3), index(2025, 5))
        val cumulative = FreedomMath.cumulativeSavedUpMinor(months, 1_200_000L, 2_400_000L, 3_000_000L)
        val monthly = FreedomMath.monthlySavedUpMinor(1_200_000L, 800_000L)
        val target = 6_000_000L
        assertEquals(4_200_000L, cumulative)
        assertEquals(400_000L, monthly)
        // 进度按累计：42000 / 60000 = 70%；若错用当月则只有 6.7%
        assertEquals(0.7f, FreedomMath.progressOf(cumulative, target))
        assertEquals(1_800_000L, FreedomMath.remainingMinor(cumulative, target), "还差 = 60000 − 42000")
    }
}
