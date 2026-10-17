package com.autoledger.core.model

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **收入口径内核**（[WageLedger]）的纯 JVM 单测 —— 用例逐条对应设计文档 §8.1 的表格。
 *
 * 为什么这组测试是整个改造的地基：`WageLedger` 是「收入的唯一真源」，
 * 账单页月度/年度/逐月表、统计卡、自由页全部经它取收入。
 * 这里错一分，全站都错，而且是**静默**错（不会崩、只是数字不对）。
 *
 * 重点钉住四类行为：
 * 1. 版本解析：取「不晚于该月的最大一条」；该月早于最早版本时**回落最早一条**（历史不清零）；
 * 2. 脏数据不崩：月份格式非法 / 同月重复行 / 空列表，一律给保守结果而不是抛异常；
 * 3. 年度与区间：往年 12 个月、当年只到当月、未来年 0；区间只算「月首落在区间内」的月份；
 * 4. 累计：跨版本来回比对，证明"逐月求和"而非"月数 × 单个月薪"。
 */
class WageLedgerTest {

    // ------------------------------------------------------------------ 夹具

    /** 固定时区：避免 CI 机器时区不同导致月份边界漂移。 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 与 WageProfile 默认值一致（月薪 4000 元、13 薪 ⇒ 月度收入 433333 分，非整除，便于发现取整错误）。 */
    private fun profile(
        monthlyNetSalaryMinor: Long = 400_000L,
        payMonthsPerYear: Int = 13,
        workDaysPerMonth: Double = 21.75,
    ): WageProfile = WageProfile(
        monthlyNetSalaryMinor = monthlyNetSalaryMinor,
        payMonthsPerYear = payMonthsPerYear,
        workDaysPerMonth = workDaysPerMonth,
    )

    private fun version(
        month: String,
        salary: Long = 1_200_000L,
        payMonths: Int = 13,
        createdAt: Long = 1_700_000_000_000L,
    ): WageVersion = WageVersion.of(month, profile(salary, payMonths), createdAt)

    /** 12 000 元 × 13 / 12 = 13 000 元 = 1_300_000 分（整除，便于心算）。 */
    private val salary12k13 = 1_300_000L

    /** 三个版本：2026-01（12k/13 薪）、2026-03（15k/13 薪）、2026-05（15k/12 薪）。 */
    private fun ladder(): List<WageVersion> = listOf(
        version("2026-01", salary = 1_200_000L, payMonths = 13),
        version("2026-03", salary = 1_500_000L, payMonths = 13),
        version("2026-05", salary = 1_500_000L, payMonths = 12),
    )

    // ------------------------------------------------------------------ 版本解析

    @Test
    fun `effective version picks the newest row not after the month`() {
        val versions = ladder()

        // 2026-04 之前最近的版本是 2026-03（15k × 13 / 12 = 16 250 元）
        assertEquals("2026-03", WageLedger.effectiveVersion(versions, "2026-04")?.month)
        // 恰好命中版本月 ⇒ 取该月自己
        assertEquals("2026-03", WageLedger.effectiveVersion(versions, "2026-03")?.month)
        // 版本之间取前一条（2026-02 ⇒ 2026-01）
        assertEquals("2026-01", WageLedger.effectiveVersion(versions, "2026-02")?.month)
    }

    @Test
    fun `same month appears twice resolves to the latest`() {
        // 模拟手改库 / 并发写入留下的重复行：不抛错，取列表靠后的一条（= 后写的那次）。
        val duplicate = listOf(
            version("2026-01", salary = 1_000_000L),
            version("2026-01", salary = 2_000_000L),
        )

        val picked = WageLedger.effectiveVersion(duplicate, "2026-01")

        assertEquals(
            profile(2_000_000L).effectiveMonthlySalaryMinor,
            picked?.monthlyIncomeMinor,
            "同月两行必须取后写的那条",
        )
        assertEquals("2026-01", picked?.month)
    }

    @Test
    fun `months before the first version reuse the earliest row`() {
        val versions = ladder()

        // 2025-12 早于最早版本（2026-01）：必须**回落最早一条**，而不是 0。
        // 这是「补录一笔很久以前的流水不会把历史收入清零」的唯一保障。
        assertEquals("2026-01", WageLedger.effectiveVersion(versions, "2025-12")?.month)
        assertEquals(salary12k13, WageLedger.incomeForMonth(versions, "2025-12"))
        assertNotEquals(0L, WageLedger.incomeForMonth(versions, "2025-12"), "历史月份绝不能是 0")
    }

    @Test
    fun `future month uses the latest version`() {
        val versions = ladder()

        // 未来月份没有专门的行，沿用最近版本（2026-05：15k × 12 / 12 = 15 000 元）
        assertEquals("2026-05", WageLedger.effectiveVersion(versions, "2027-12")?.month)
        assertEquals(1_500_000L, WageLedger.incomeForMonth(versions, "2027-12"))
    }

    @Test
    fun `no versions yields zero income and null version`() {
        assertEquals(0L, WageLedger.incomeForMonth(emptyList(), "2026-04"))
        assertNull(WageLedger.effectiveVersion(emptyList(), "2026-04"))
        assertNull(WageLedger.profileForMonth(emptyList(), "2026-04"))
        assertEquals(0L, WageLedger.incomeForYear(emptyList(), 2026, "2026-06"))
        assertEquals(0L, WageLedger.cumulativeIncomeMinor(emptyList(), "2026-01", "2026-06"))
    }

    @Test
    fun `illegal month format is rejected without throwing`() {
        val versions = ladder()

        for (illegal in listOf("2026-13", "2026-1", "", "abc", "2026-00", "2026-01-01", " 2026-01")) {
            assertTrue(!WageLedger.isValidMonth(illegal), "必须判为非法：$illegal")
            assertNull(WageLedger.effectiveVersion(versions, illegal), "非法月份必须给 null：$illegal")
            assertEquals(0L, WageLedger.incomeForMonth(versions, illegal), "非法月份收入必须为 0：$illegal")
        }

        // 合法值不得被误杀
        assertTrue(WageLedger.isValidMonth("2026-01"))
        assertTrue(WageLedger.isValidMonth("1999-12"))
        assertTrue(WageLedger.isValidMonth("2026-12"))
    }

    // ------------------------------------------------------------------ 落库的月度收入

    @Test
    fun `stored monthly income equals effective monthly salary`() {
        // 三组：13 薪整除 / 非整除（四舍五入）/ 0
        val divisible = WageVersion.of("2026-01", profile(1_200_000L, 13), 0L)
        assertEquals(1_300_000L, divisible.monthlyIncomeMinor)
        assertEquals(profile(1_200_000L, 13).effectiveMonthlySalaryMinor, divisible.monthlyIncomeMinor)

        // 400 000 × 13 / 12 = 433 333.33… ⇒ 433 333
        val fractional = WageVersion.of("2026-02", profile(400_000L, 13), 0L)
        assertEquals(433_333L, fractional.monthlyIncomeMinor)
        assertEquals(profile(400_000L, 13).effectiveMonthlySalaryMinor, fractional.monthlyIncomeMinor)

        val zero = WageVersion.of("2026-03", profile(0L, 13), 0L)
        assertEquals(0L, zero.monthlyIncomeMinor)
        assertEquals(profile(0L, 13).effectiveMonthlySalaryMinor, zero.monthlyIncomeMinor)
    }

    @Test
    fun `monthly income does not subtract work cost`() {
        // D1 钉子：工作成本属支出侧，收入**不扣**它；扣了就是双计。
        val withCost = WageProfile(
            monthlyNetSalaryMinor = 1_200_000L,
            payMonthsPerYear = 13,
            monthlyWorkCostMinor = 85_000L,
        )
        val versions = listOf(WageVersion.of("2026-01", withCost, 0L))

        assertEquals(1_300_000L, WageLedger.incomeForMonth(versions, "2026-01"))
        assertEquals(withCost.effectiveMonthlySalaryMinor, WageLedger.incomeForMonth(versions, "2026-01"))
        assertNotEquals(withCost.realMonthlyIncomeMinor, WageLedger.incomeForMonth(versions, "2026-01"))
        assertEquals(1_215_000L, withCost.realMonthlyIncomeMinor, "对照组：扣了工作成本的『实际月收入』是 12150 元")
    }

    // ------------------------------------------------------------------ 年度 / 区间 / 累计

    @Test
    fun `annual income is the sum of twelve monthly incomes`() {
        // 2026 年跨**三个**版本（分段求和，任何一段都不能被"整体重算"）：
        //   1–2 月 = 13000（12k × 13 / 12）
        //   3–4 月 = 16250（15k × 13 / 12）
        //   5–12 月 = 15000（15k × 12 / 12，5 月起改成 12 薪）
        // = 2 × 13000 + 2 × 16250 + 8 × 15000 = 26000 + 32500 + 120000 = 178500 元
        val versions = ladder()
        val expected = 2 * salary12k13 + 2 * 1_625_000L + 8 * 1_500_000L

        assertEquals(17_850_000L, expected)
        assertEquals(expected, WageLedger.incomeForYear(versions, 2026, "2026-12"))
        // 往年（2025）没有更早的版本 ⇒ 12 个月全按最早版本 13000 = 156000 元
        assertEquals(12 * salary12k13, WageLedger.incomeForYear(versions, 2025, "2026-12"))
    }

    @Test
    fun `annual income stops at the current month`() {
        val versions = ladder()

        // 当年 now = 2026-06 ⇒ 只算 1..6 月：
        //   1–2 月 13000、3–4 月 16250、5–6 月 15000
        // = 2 × 13000 + 2 × 16250 + 2 × 15000 = 26000 + 32500 + 30000 = 88500 元
        assertEquals(8_850_000L, WageLedger.incomeForYear(versions, 2026, "2026-06"))
        // 未来年 ⇒ 0
        assertEquals(0L, WageLedger.incomeForYear(versions, 2027, "2026-06"))
        // 当年 1 月 ⇒ 只有 1 个月
        assertEquals(salary12k13, WageLedger.incomeForYear(versions, 2026, "2026-01"))
    }

    @Test
    fun `range income only counts months whose first day is inside`() {
        // 3/15–4/14 只跨 4 月一个月首（4/1）；3 月的月首（3/1）在区间外 ⇒ 只算 4 月。
        val versions = listOf(version("2026-01", salary = 1_200_000L))
        val from = LocalDate.of(2026, 3, 15)
        val to = LocalDate.of(2026, 4, 14)

        assertEquals(salary12k13, WageLedger.incomeForRange(versions, from, to))

        // 月对齐区间等价于「该月收入」
        assertEquals(
            salary12k13,
            WageLedger.incomeForRange(versions, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
        )
        // 两个整月 ⇒ 两个月
        assertEquals(
            2 * salary12k13,
            WageLedger.incomeForRange(versions, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30)),
        )
        // 单日落在月首之后 ⇒ 0（不是按天摊分）
        assertEquals(
            0L,
            WageLedger.incomeForRange(versions, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 20)),
        )
        // 反向区间 ⇒ 0（不抛异常）
        assertEquals(
            0L,
            WageLedger.incomeForRange(versions, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1)),
        )
    }

    @Test
    fun `cumulative sums each month's own version`() {
        // 用户场景：1 月、2 月到手 8000 元（12 薪），3 月起涨到 12000 元。
        // 逐月求和 ⇒ 8000 + 8000 + 12000 = 28000；任何"月数 × 单个月薪"的乘法压缩
        // 都只能给出 24000（3×8000）或 36000（3×12000），结构上无法表达"逐月不同"。
        val versions = listOf(
            version("2026-01", salary = 800_000L, payMonths = 12),
            version("2026-03", salary = 1_200_000L, payMonths = 12),
        )

        assertEquals(800_000L, WageLedger.incomeForMonth(versions, "2026-01"))
        assertEquals(800_000L, WageLedger.incomeForMonth(versions, "2026-02"))
        assertEquals(1_200_000L, WageLedger.incomeForMonth(versions, "2026-03"))
        assertEquals(
            2_800_000L,
            WageLedger.cumulativeIncomeMinor(versions, "2026-01", "2026-03"),
            "跨版本累计 = 各月自己版本之和（8000 + 8000 + 12000）",
        )
        assertNotEquals(2_400_000L, WageLedger.cumulativeIncomeMinor(versions, "2026-01", "2026-03"), "不是 3×8000")
        assertNotEquals(3_600_000L, WageLedger.cumulativeIncomeMinor(versions, "2026-01", "2026-03"), "不是 3×12000")
    }

    @Test
    fun `cumulative income equals per-month sum across versions`() {
        val versions = ladder()

        // 2026-01 → 2026-05：1–2 月 13000、3–4 月 16250、5 月 15000（当月即 5 月版）
        // = 2 × 13000 + 2 × 16250 + 15000 = 26000 + 32500 + 15000 = 73500 元
        val expected = 2 * salary12k13 + 2 * 1_625_000L + 1_500_000L
        assertEquals(7_350_000L, expected)
        assertEquals(expected, WageLedger.cumulativeIncomeMinor(versions, "2026-01", "2026-05"))

        // 与逐月 incomeForMonth 求和逐分相等（证明"逐月求和"而非"月数 × 单个月薪"）
        var manual = 0L
        for (month in 1..5) {
            manual += WageLedger.incomeForMonth(versions, "2026-0$month")
        }
        assertEquals(manual, WageLedger.cumulativeIncomeMinor(versions, "2026-01", "2026-05"))

        // 单月区间 = 该月收入
        assertEquals(
            salary12k13,
            WageLedger.cumulativeIncomeMinor(versions, "2026-01", "2026-01"),
        )
        // 跨年：2025-12 → 2026-01 = 13000 + 13000（2025-12 回落最早版本）
        assertEquals(2 * salary12k13, WageLedger.cumulativeIncomeMinor(versions, "2025-12", "2026-01"))
        // 非法 / 反向区间 ⇒ 0
        assertEquals(0L, WageLedger.cumulativeIncomeMinor(versions, "2026-05", "2026-01"))
        assertEquals(0L, WageLedger.cumulativeIncomeMinor(versions, "abc", "2026-01"))
    }

    @Test
    fun `profile for month falls back to the version snapshot`() {        // 历史月份必须拿到**当时**的工时参数（否则「花掉的时间」永远用今天的参数换算）。
        val old = WageProfile(
            monthlyNetSalaryMinor = 1_200_000L,
            payMonthsPerYear = 13,
            workDaysPerMonth = 21.75,
            dailyOfficeHours = 8.0,
            dailyCommuteMinutes = 48,
        )
        val new = old.copy(workDaysPerMonth = 22.0, dailyCommuteMinutes = 30)
        val versions = listOf(
            WageVersion.of("2026-01", old, 0L),
            WageVersion.of("2026-06", new, 0L),
        )

        assertEquals(21.75, WageLedger.profileForMonth(versions, "2026-03")?.workDaysPerMonth)
        assertEquals(48, WageLedger.profileForMonth(versions, "2026-03")?.dailyCommuteMinutes)
        assertEquals(22.0, WageLedger.profileForMonth(versions, "2026-07")?.workDaysPerMonth)
        assertEquals(30, WageLedger.profileForMonth(versions, "2026-07")?.dailyCommuteMinutes)
    }

    // ------------------------------------------------------------------ 月份键

    @Test
    fun `month key is zero padded and timezone aware`() {
        assertEquals("2026-03", WageLedger.monthKeyOf(LocalDate.of(2026, 3, 15)))
        assertEquals("2026-12", WageLedger.monthKeyOf(LocalDate.of(2026, 12, 1)))
        assertEquals("2026-01", WageLedger.monthKeyOf(LocalDate.of(2026, 1, 31)))

        // 时间戳版本必须按**业务时区**折算：2026-01-01 00:30 (Asia/Shanghai) 在东八区是 1 月，
        // 在 UTC 下却是 2025-12-31 16:30 ⇒ 上个月。跨月边界最容易在这里出错。
        val justAfterNewYear = LocalDate.of(2026, 1, 1).atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-01", WageLedger.monthKeyOf(justAfterNewYear, zone))
        assertEquals("2025-12", WageLedger.monthKeyOf(justAfterNewYear, ZoneId.of("UTC")))
    }
}
