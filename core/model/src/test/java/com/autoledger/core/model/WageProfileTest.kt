package com.autoledger.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WageProfile（真实时薪）—— 纯 JVM 单元测试。
 *
 * 这是首页「花掉的时间」与「真实时薪」两个招牌指标的数学内核，
 * 一旦算错，整个 App 的核心叙事就站不住，必须锁死。
 * 金额字段为「分」(Long)，断言用分，杜绝浮点误差。
 */
class WageProfileTest {

    private val base = WageProfile(
        monthlyNetSalaryMinor = 1_200_000L, // 12000 元
        payMonthsPerYear = 12,
        monthlyWorkCostMinor = 0L,
        workDaysPerMonth = 20.0,
        dailyOfficeHours = 8.0,
        dailyCommuteMinutes = 0,
        dailyOvertimeHours = 0.0,
    )

    @Test
    fun `effective monthly salary spreads bonus months`() {
        assertEquals(1_200_000L, base.effectiveMonthlySalaryMinor)
        val with13 = base.copy(payMonthsPerYear = 13)
        assertEquals(1_300_000L, with13.effectiveMonthlySalaryMinor)
    }

    @Test
    fun `real monthly income subtracts work cost`() {
        assertEquals(1_200_000L, base.realMonthlyIncomeMinor)
        val costly = base.copy(monthlyWorkCostMinor = 85_000L) // 850 元
        assertEquals(1_115_000L, costly.realMonthlyIncomeMinor)
    }

    @Test
    fun `monthly work hours adds office commute and overtime`() {
        assertEquals(160.0, base.monthlyWorkHours, 1e-6)
        val realistic = WageProfile(
            monthlyNetSalaryMinor = 1_380_000L,
            payMonthsPerYear = 13,
            monthlyWorkCostMinor = 85_000L,
            workDaysPerMonth = 21.75,
            dailyOfficeHours = 9.0,
            dailyCommuteMinutes = 48,
            dailyOvertimeHours = 0.6,
        )
        assertEquals(226.2, realistic.monthlyWorkHours, 1e-6)
    }

    @Test
    fun `real hourly is lower than nominal hourly when hidden costs exist`() {
        val p = WageProfile(
            monthlyNetSalaryMinor = 1_380_000L,
            payMonthsPerYear = 13,
            monthlyWorkCostMinor = 85_000L,
            workDaysPerMonth = 21.75,
            dailyOfficeHours = 9.0,
            dailyCommuteMinutes = 48,
            dailyOvertimeHours = 0.6,
        )
        assertTrue(p.realHourly < p.nominalHourly, "真实时薪必须低于名义时薪")
        assertTrue(p.realHourly > 0.0)
    }

    @Test
    fun `nominal hourly ignores commute and work cost`() {
        // 12000 / (20 * 8) = 75
        assertEquals(75.0, base.nominalHourly, 1e-6)
    }

    @Test
    fun `minutesOfWork converts yuan to minutes`() {
        assertEquals(60.0, base.minutesOfWork(7_500), 1e-6)
        assertEquals(30.0, base.minutesOfWork(3_750), 1e-6)
    }

    @Test
    fun `minutesOfWork uses absolute value`() {
        assertEquals(base.minutesOfWork(7_500), base.minutesOfWork(-7_500), 1e-6)
    }

    @Test
    fun `minutesOfWork is zero when zero amount`() {
        assertEquals(0.0, base.minutesOfWork(0), 1e-6)
    }

    @Test
    fun `zero work hours yields zero hourly instead of NaN or Infinity`() {
        val idle = base.copy(workDaysPerMonth = 0.0)
        assertEquals(0.0, idle.monthlyWorkHours, 1e-6)
        assertEquals(0.0, idle.realHourly, 1e-6)
        assertEquals(0.0, idle.nominalHourly, 1e-6)
        assertEquals(0.0, idle.minutesOfWork(10_000), 1e-6)
    }

    @Test
    fun `zero salary yields zero hourly`() {
        val unpaid = base.copy(monthlyNetSalaryMinor = 0L, monthlyWorkCostMinor = 0L)
        assertEquals(0.0, unpaid.realHourly, 1e-6)
        assertEquals(0.0, unpaid.minutesOfWork(10_000), 1e-6)
    }

    @Test
    fun `negative real income is allowed but never divides by zero`() {
        val upsideDown = base.copy(monthlyWorkCostMinor = 2_000_000L) // 20000 元
        assertTrue(upsideDown.realHourly < 0.0)
        assertTrue(upsideDown.minutesOfWork(10_000).isFinite())
    }

    @Test
    fun `isSane rejects zero salary and zero hours`() {
        assertTrue(base.isSane())
        assertFalse(base.copy(monthlyNetSalaryMinor = 0L).isSane())
        assertFalse(base.copy(workDaysPerMonth = 0.0).isSane())
    }

    @Test
    fun `defaults are sane out of the box`() {
        val d = WageProfile()
        assertTrue(d.isSane())
        assertTrue(d.realHourly > 0.0)
        assertTrue(d.minutesOfWork(10_000).isFinite())
    }

    @Test
    fun `minutesOfWork stays finite for extreme amounts`() {
        assertTrue(base.minutesOfWork(Long.MAX_VALUE / 100).isFinite())
        assertTrue(base.minutesOfWork(Long.MIN_VALUE / 100).isFinite())
    }
}
