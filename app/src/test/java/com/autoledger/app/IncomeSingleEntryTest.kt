package com.autoledger.app

import com.autoledger.core.model.WageLedger
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.WageVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **单一入口护栏（设计文档 R4）**：同一份时薪版本，三个地方算出的收入必须逐分相等。
 *
 * 三处调用点（改动后全部经 `WageLedger`，没有任何一处自己求和）：
 * 1. 账单页 · 月度总览：`incomeForMonth(versions, monthKey)`
 * 2. 账单页 · 年度总览：`incomeForYear(versions, year, currentMonthKey)`
 * 3. 账单页 · 逐月表：逐行 `incomeForMonth(versions, monthKey(selectedYear, m))`
 *
 * 三者口径的**恒等关系**：
 * ```
 * 年度收入(某年) == Σ 逐月表该年每一行
 * ```
 * 只要有人偷偷在某一处改成"对 INCOME 流水求和"或"月数 × 月薪"，这条恒等式立刻破裂。
 *
 * 为什么这组断言值得单独存在：三处分散在同一个 `LazyColumn` 的不同 item 里，
 * 中间隔着预算卡 / 分类结构 / 趋势卡，用户很难一眼发现"月度 8000、年度合计却对不上"。
 * 这种错**不会崩、不会报错**，只会让用户不再相信账本。
 */
class IncomeSingleEntryTest {

    /** 用户已确认的版本（时间戳必须非 0：0 是「迁移预填」的指纹）。 */
    private fun version(month: String, salary: Long, payMonths: Int): WageVersion =
        WageVersion.of(
            month,
            WageProfile(monthlyNetSalaryMinor = salary, payMonthsPerYear = payMonths),
            nowMillis = 1_700_000_000_000L,
        )

    /** 该参数的折合月度收入（分）—— 与 `WageVersion.of` 锁定的值同源。 */
    private fun monthlyIncome(salary: Long, payMonths: Int): Long =
        WageProfile(monthlyNetSalaryMinor = salary, payMonthsPerYear = payMonths).effectiveMonthlySalaryMinor

    /** 三个版本：1 月起 8000（12 薪）、4 月起 12000（12 薪）、9 月起 10000（13 薪）。 */
    private fun versions(): List<WageVersion> = listOf(
        version("2025-01", salary = 800_000L, payMonths = 12),
        version("2025-04", salary = 1_200_000L, payMonths = 12),
        version("2025-09", salary = 1_000_000L, payMonths = 13),
    )

    /** 逐月表实际渲染的行：当年只到当月，往年 12 行（与 `LedgerScreens` 的 lastMonth 逻辑一致）。 */
    private fun tableRows(versions: List<WageVersion>, year: Int, currentMonthKey: String): List<Long> {
        val currentYear = currentMonthKey.substring(0, 4).toInt()
        val currentMonth = currentMonthKey.substring(5, 7).toInt()
        val last = if (year == currentYear) currentMonth else 12
        return (1..last).map { m -> WageLedger.incomeForMonth(versions, WageLedger.monthKeyOf(year, m)) }
    }

    @Test
    fun `annual total equals the sum of the monthly table rows for a past year`() {
        val versions = versions()
        // 固定的「当前月」放在 2025 之后，让 2025 稳定走"往年 12 个月"分支（不依赖真实运行时间）
        val currentMonthKey = "2026-03"

        val annual = WageLedger.incomeForYear(versions, 2025, currentMonthKey)
        val rows = tableRows(versions, 2025, currentMonthKey)

        // 13 薪那一段的取整先钉死：10 000 元 × 13 / 12 = 10 833.33… 元 ⇒ 1 083 333 分
        assertEquals(1_083_333L, monthlyIncome(1_000_000L, 13))
        assertEquals(1_083_333L, WageLedger.incomeForMonth(versions, "2025-12"))

        assertEquals(12, rows.size, "往年逐月表必须 12 行")
        assertEquals(annual, rows.sum(), "年度收入必须等于逐月表 12 行之和")
        // 手算：1–3 月 800000×3 + 4–8 月 1200000×5 + 9–12 月 1083333×4
        assertEquals(12_733_332L, annual, "跨三个版本逐月求和，不能被任何乘法压缩替代")
    }

    @Test
    fun `annual total for the current year equals the rows rendered up to this month`() {
        val versions = versions()
        // 固定「当前月」= 2025-10
        val currentMonthKey = "2025-10"

        val annual = WageLedger.incomeForYear(versions, 2025, currentMonthKey)
        val rows = tableRows(versions, 2025, currentMonthKey)

        assertEquals(10, rows.size, "当年逐月表只渲染到当月（未来月不显示 ¥0 假行）")
        assertEquals(annual, rows.sum(), "年度收入（只到当月）必须等于逐月表已渲染行之和")
        // 1–3 月 800000×3 + 4–8 月 1200000×5 + 9–10 月 1083333×2
        assertEquals(10_566_666L, annual, "当年收入只累加到当月")
    }

    @Test
    fun `a future year is zero at the year level`() {
        // 年度选择器只提供「当年 + 过去 4 年」（`LedgerScreens.years`），未来年不可选；
        // 这里仍钉住 WageLedger 的契约：未来年 ⇒ 0，绝不"预支"未来收入。
        assertEquals(0L, WageLedger.incomeForYear(versions(), 2026, "2025-10"))
        assertEquals(0L, WageLedger.incomeForYear(versions(), 2030, "2025-10"))
    }

    @Test
    fun `every month resolves to exactly one version and never to two`() {
        // 单一入口的另一面：某月只会命中**一个**版本（否则同一个月会出现两种收入）。
        // 早于最早版本的月份 ⇒ **没有版本**（收入 0）：只向后沿用，不向前回填。
        val versions = versions()
        assertNull(WageLedger.effectiveVersion(versions, "2024-06"))
        assertEquals("2025-01", WageLedger.effectiveVersion(versions, "2025-01")?.month)
        assertEquals("2025-01", WageLedger.effectiveVersion(versions, "2025-03")?.month)
        assertEquals("2025-04", WageLedger.effectiveVersion(versions, "2025-04")?.month)
        assertEquals("2025-04", WageLedger.effectiveVersion(versions, "2025-08")?.month)
        assertEquals("2025-09", WageLedger.effectiveVersion(versions, "2025-09")?.month)
        assertEquals("2025-09", WageLedger.effectiveVersion(versions, "2026-05")?.month)
    }

    @Test
    fun `monthly twelve month sum equals the annual figure`() {
        // 上一组用例的另一种写法：12 个「月度总览」值逐个相加 == 年度总览。
        // 这两个数字在 UI 上分别位于「月度」「年度」两个 tab，用户会来回对照。
        val versions = versions()
        val currentMonthKey = "2026-03"
        var sumOfMonths = 0L
        for (m in 1..12) {
            val monthKey = WageLedger.monthKeyOf(2025, m)
            sumOfMonths += WageLedger.incomeForMonth(versions, monthKey)
        }
        assertEquals(sumOfMonths, WageLedger.incomeForYear(versions, 2025, currentMonthKey))
    }

    @Test
    fun `income never depends on the transactions`() {
        // 结构性保证：WageLedger 的签名里根本没有流水，所以"收入 = 流水合计"这条路被删掉了。
        // 这条用「同一组版本、任意月份键，结果只由版本决定」来表达（重复调用结果恒定）。
        val versions = versions()
        val first = WageLedger.incomeForYear(versions, 2025, "2026-03")
        val second = WageLedger.incomeForYear(versions, 2025, "2026-03")
        assertEquals(first, second)
        assertEquals(first, WageLedger.cumulativeIncomeMinor(versions, "2025-01", "2025-12"))
        assertEquals(
            first,
            WageLedger.cumulativeIncomeMinor(versions, "2025-01", "2025-12"),
            "同一组版本在同一区间内的年度收入与逐月累计必须一致",
        )
    }
}
