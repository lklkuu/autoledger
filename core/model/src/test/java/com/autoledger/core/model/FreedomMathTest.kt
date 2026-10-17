package com.autoledger.core.model

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「自由」页两个「已攒」指标的纯 JVM 单测。
 *
 * 口径（唯一真源见 [FreedomMath]）：
 * ```
 * 当月已攒 = 当月收入 − 当月支出
 * 累计已攒 = 逐月收入之和 − 累计支出 + 当前存款
 * 使用月数 = 账本最早一笔流水所在月份 → 当月（按自然月，含首尾）
 * ```
 *
 * v8 口径变更：收入项不再由 [FreedomMath] 自己取（旧版是「使用月数 × 到手月薪」的乘法压缩），
 * 改由调用方经 [WageLedger] **逐月求和**后传入。因此本文件的断言全部按新签名书写，
 * 并额外保留一条**与原实现逐分相等**的对照断言（[legacyCumulativeSavedUpMinor]），
 * 证明「每月收入同额时」新公式与老公式在数值上完全一致 —— 避免口径切换顺手改了算术。
 *
 * 重点覆盖：
 * - 起始月为空（账本没流水）/ 只有一个月 / 跨多个月（含跨年）
 * - 支出大于累计收入 → **负累计**必须保留，不得夹断
 * - 存款为负、收入为 0 都得照常算
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

    /**
     * **旧实现的对照公式**（v7 及以前）：`月数 × 固定月薪 − 累计支出 + 存款`。
     *
     * 只出现在测试里，用于证明「每月收入同额」时新公式逐分等价 —— 这是口径迁移的回归护栏。
     */
    private fun legacyCumulativeSavedUpMinor(
        monthsUsed: Int,
        monthlyNetSalaryMinor: Long,
        cumulativeExpenseMinor: Long,
        currentDepositMinor: Long,
    ): Long = monthsUsed.toLong().coerceAtLeast(0L) * monthlyNetSalaryMinor -
        cumulativeExpenseMinor + currentDepositMinor

    /** 每月同额场景下，新旧公式必须逐分相等。 */
    private fun assertMatchesLegacy(
        monthsUsed: Int,
        monthlyIncomeMinor: Long,
        cumulativeExpenseMinor: Long,
        currentDepositMinor: Long,
    ) {
        assertEquals(
            legacyCumulativeSavedUpMinor(
                monthsUsed = monthsUsed,
                monthlyNetSalaryMinor = monthlyIncomeMinor,
                cumulativeExpenseMinor = cumulativeExpenseMinor,
                currentDepositMinor = currentDepositMinor,
            ),
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = monthsUsed.toLong() * monthlyIncomeMinor,
                cumulativeExpenseMinor = cumulativeExpenseMinor,
                currentDepositMinor = currentDepositMinor,
            ),
            "每月收入同额时，新公式必须与原实现逐分相等",
        )
    }

    // ------------------------------------------------------------------ 当月已攒

    @Test
    fun `monthly saved up is income minus this month expense`() {
        // 当月收入 12000 元 − 当月支出 4500 元 = 7500 元
        assertEquals(
            750_000L,
            FreedomMath.monthlySavedUpMinor(monthlyIncomeMinor = 1_200_000L, monthlyExpenseMinor = 450_000L),
        )
    }

    @Test
    fun `monthly saved up ignores the deposit`() {
        // 存款是过去的积累，只进「累计已攒」；当月已攒与它无关。
        // 这条锁住两个指标不会因为「存款被算了两次」而对不上。
        assertEquals(
            -200_000L,
            FreedomMath.monthlySavedUpMinor(monthlyIncomeMinor = 1_000_000L, monthlyExpenseMinor = 1_200_000L),
        )
    }

    @Test
    fun `monthly saved up of exactly zero is zero not clamped`() {
        assertEquals(0L, FreedomMath.monthlySavedUpMinor(800_000L, 800_000L))
    }

    @Test
    fun `monthly saved up keeps its negative sign`() {
        // 支出 15000 > 收入 12000 → −3000 元（这个月在吃老本）
        assertEquals(-300_000L, FreedomMath.monthlySavedUpMinor(1_200_000L, 1_500_000L))
    }

    @Test
    fun `monthly saved up with zero income does not crash`() {
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
    fun `year month label stays ascii under an arabic default locale`() {
        // 回归钉子（真实 Locale 切换版）：旧实现用 `String.format("%04d-%02d")`，走默认 Locale，
        // 在阿拉伯语区输出 `٢٠٢٥-٠٣`（阿拉伯-印度数字）⇒ 该串作为
        // `WageLedger.cumulativeIncomeMinor(fromMonth = …)` 的入参被严格 ASCII 的
        // `isValidMonth` 拒绝 ⇒ **「累计已攒」的收入项静默变 0**，而「当月已攒」正常。
        // 这类"某个语言环境下某个数字变 0"的缺陷在没有这条用例时几乎不可能被发现。
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))

            val label = FreedomMath.yearMonthLabel(index(2025, 3))

            assertEquals("2025-03", label, "阿拉伯语默认 Locale 下也必须输出 ASCII 数字")
            assertTrue(WageLedger.isValidMonth(label), "必须能被 WageLedger 接受：$label")
            // 12 个月全覆盖，防止只有个位数月份被"漏补"
            for (m in 1..12) {
                val key = FreedomMath.yearMonthLabel(index(2030, m))
                assertEquals("2030-${m.toString().padStart(2, '0')}", key)
                assertTrue(WageLedger.isValidMonth(key), "12 个月都必须合法：$key")
            }
        } finally {
            // 必须恢复：Locale 是**进程全局**状态，漏恢复会污染其它用例（顺序相关的假红）
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `year month label is always ascii digits so WageLedger accepts it`() {
        // 回归钉子：该串是 `WageLedger.cumulativeIncomeMinor(fromMonth = …)` 的入参，
        // 而 `isValidMonth` 是严格 ASCII 校验。若实现退回 `String.format("%04d-%02d")`，
        // 在阿拉伯语 / 波斯语区域会输出非 ASCII 数字 ⇒ 累计已攒的收入项静默变 0。
        // 这里做的是"输出必须能被 isValidMonth 接受"的**契约断言**（与运行 Locale 无关）。
        val label = FreedomMath.yearMonthLabel(index(2025, 3))
        assertTrue(label.all { it == '-' || it in '0'..'9' }, "必须是 ASCII 数字：$label")
        assertTrue(WageLedger.isValidMonth(label), "yyyy-MM 必须被 WageLedger 接受：$label")
        for (m in 1..12) {
            val key = FreedomMath.yearMonthLabel(index(2030, m))
            assertTrue(WageLedger.isValidMonth(key), "12 个月都必须合法：$key")
        }
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
    fun `cumulative for a single month is income minus expense plus deposit`() {
        // 累计收入 12000 − 支出 4500 + 存款 30000 = 37500 元
        assertEquals(
            3_750_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = 1_200_000L,
                cumulativeExpenseMinor = 450_000L,
                currentDepositMinor = 3_000_000L,
            ),
        )
        assertMatchesLegacy(monthsUsed = 1, monthlyIncomeMinor = 1_200_000L, 450_000L, 3_000_000L)
    }

    @Test
    fun `cumulative over several months sums the monthly incomes`() {
        // 2025-03 起始、现在是 2025-05 → 3 个月，每月同额 12000 ⇒ 累计收入 36000
        // 3 × 12000 − 累计支出 24000 + 存款 30000 = 42000 元
        val months = FreedomMath.monthsUsed(index(2025, 3), index(2025, 5))
        assertEquals(3, months)
        assertEquals(
            4_200_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = months.toLong() * 1_200_000L,
                cumulativeExpenseMinor = 2_400_000L,
                currentDepositMinor = 3_000_000L,
            ),
        )
        assertMatchesLegacy(monthsUsed = months, monthlyIncomeMinor = 1_200_000L, 2_400_000L, 3_000_000L)
    }

    @Test
    fun `cumulative sees the summed income when the hourly wage changed mid way`() {
        // 新口径的核心场景：3 月收入 13000、4 月起 15000 ⇒ 两个月累计 28000（不是 26000 / 30000）。
        // 旧公式「月数 × 单个月薪」在数学上无法表达它 —— 这条锁住"逐月求和"。
        val cumulatedIncome = 1_300_000L + 1_500_000L
        assertEquals(
            2_800_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = cumulatedIncome,
                cumulativeExpenseMinor = 0L,
                currentDepositMinor = 0L,
            ),
        )
        // 对照组：若错用「2 × 13000」或「2 × 15000」，结果分别是 26000 / 30000，都不等于 28000。
        assertEquals(2_600_000L, legacyCumulativeSavedUpMinor(2, 1_300_000L, 0L, 0L))
        assertEquals(3_000_000L, legacyCumulativeSavedUpMinor(2, 1_500_000L, 0L, 0L))
    }

    @Test
    fun `cumulative equals the deposit for an empty ledger`() {
        // 用户口径（决策 B）：账本为空 ⇒ 累计收入 0，公式**照跑** → 累计 = 0 − 0 + 存款 = 存款。
        //
        // 刻意不写成 `if (累计收入 <= 0) return 0L`：存款是用户明确输入的真实值，
        // 不该因为账本还空就被抹掉 —— 刚装 App、先填了 5 万存款的用户
        // 若看到「累计已攒 ¥0」只会困惑。空账本要防的是**报错 / NaN**，不是把结果改成 0。
        assertEquals(
            5_000_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = 0L,
                cumulativeExpenseMinor = 0L,
                currentDepositMinor = 5_000_000L,
            ),
            "空账本时累计已攒应等于存款，而不是硬置 0",
        )
    }

    @Test
    fun `cumulative is zero for an empty ledger with no deposit`() {
        // 补充边界：账本空且没填存款 ⇒ 0（不是负数、不是异常）
        assertEquals(
            0L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = 0L,
                cumulativeExpenseMinor = 0L,
                currentDepositMinor = 0L,
            ),
        )
    }

    @Test
    fun `cumulative goes negative when expenses exceed the income`() {
        // 2 个月每月 10000 ⇒ 累计收入 20000 − 累计支出 25000 + 存款 0 = −5000 元
        assertEquals(
            -500_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = 2_000_000L,
                cumulativeExpenseMinor = 2_500_000L,
                currentDepositMinor = 0L,
            ),
            "负累计必须原样保留，绝不能 coerceAtLeast(0)",
        )
        assertMatchesLegacy(monthsUsed = 2, monthlyIncomeMinor = 1_000_000L, 2_500_000L, 0L)
    }

    @Test
    fun `cumulative accepts a negative deposit`() {
        // 存款记成负（欠款）→ 10000 − 0 + (−3000) = 7000 元
        assertEquals(
            700_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = 1_000_000L,
                cumulativeExpenseMinor = 0L,
                currentDepositMinor = -300_000L,
            ),
        )
        assertMatchesLegacy(monthsUsed = 1, monthlyIncomeMinor = 1_000_000L, 0L, -300_000L)
    }

    @Test
    fun `cumulative with zero income does not crash`() {
        // 没设时薪：累计收入 0 − 支出 2000 + 存款 10000 = 8000 元
        assertEquals(
            800_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = 0L,
                cumulativeExpenseMinor = 200_000L,
                currentDepositMinor = 1_000_000L,
            ),
        )
        // 没设时薪也没存款：−2000 元
        assertEquals(
            -200_000L,
            FreedomMath.cumulativeSavedUpMinor(
                cumulativeIncomeMinor = 0L,
                cumulativeExpenseMinor = 200_000L,
                currentDepositMinor = 0L,
            ),
        )
    }

    @Test
    fun `cumulative grows with income and shrinks with expenses`() {
        val base = FreedomMath.cumulativeSavedUpMinor(1_000_000L, 300_000L, 500_000L)
        val oneMoreMonth = FreedomMath.cumulativeSavedUpMinor(2_000_000L, 300_000L, 500_000L)
        val moreExpense = FreedomMath.cumulativeSavedUpMinor(1_000_000L, 400_000L, 500_000L)
        val moreDeposit = FreedomMath.cumulativeSavedUpMinor(1_000_000L, 300_000L, 600_000L)
        assertEquals(base + 1_000_000L, oneMoreMonth, "每多一个月的收入，累计多该月收入")
        assertEquals(base - 100_000L, moreExpense, "支出每多 1000 元，累计少 1000 元")
        assertEquals(base + 100_000L, moreDeposit, "存款每多 1000 元，累计多 1000 元")
    }

    @Test
    fun `cumulative stays exact in minor units for large values`() {
        // 分单位下 Long 足够；这条锁住「没有中途转 Double 导致精度丢失」
        assertEquals(
            299_999_999_997L,
            FreedomMath.cumulativeSavedUpMinor(299_999_999_999L, 1L, -1L),
        )
        assertMatchesLegacy(monthsUsed = 3, monthlyIncomeMinor = 99_999_999_999L, 1L, 1L)
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
        // 场景：起始 2025-03，现在 2025-05（3 个月），每月收入 12000、累计支出 24000、存款 30000
        // → 累计已攒 42000、当月已攒 7500；目标 60000。
        val months = FreedomMath.monthsUsed(index(2025, 3), index(2025, 5))
        val cumulative = FreedomMath.cumulativeSavedUpMinor(
            cumulativeIncomeMinor = months.toLong() * 1_200_000L,
            cumulativeExpenseMinor = 2_400_000L,
            currentDepositMinor = 3_000_000L,
        )
        val monthly = FreedomMath.monthlySavedUpMinor(1_200_000L, 800_000L)
        val target = 6_000_000L
        assertEquals(4_200_000L, cumulative)
        assertEquals(400_000L, monthly)
        // 进度按累计：42000 / 60000 = 70%；若错用当月则只有 6.7%
        assertEquals(0.7f, FreedomMath.progressOf(cumulative, target))
        assertEquals(1_800_000L, FreedomMath.remainingMinor(cumulative, target), "还差 = 60000 − 42000")
    }
}
