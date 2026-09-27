package com.autoledger.core.model

import kotlin.math.roundToLong

/**
 * 真实时薪参数。**金额一律以「分」存储**，杜绝浮点误差。
 *
 * 名义时薪只看合同工时；真实时薪要把通勤、加班、为上班而花的钱都算进分母。
 */
data class WageProfile(
    /** 到手月薪（分） */
    val monthlyNetSalaryMinor: Long = 1_380_000L,
    val payMonthsPerYear: Int = 13,
    /** 每月为工作花的钱（分） */
    val monthlyWorkCostMinor: Long = 85_000L,
    val workDaysPerMonth: Double = 21.75,
    val dailyOfficeHours: Double = 9.0,
    val dailyCommuteMinutes: Int = 48,
    val dailyOvertimeHours: Double = 0.6,
) {
    /** 每月名义收入（把年终折算进月薪，分） */
    val effectiveMonthlySalaryMinor: Long
        get() = (monthlyNetSalaryMinor.toDouble() * payMonthsPerYear / 12.0).roundToLong()

    /** 扣掉「为了上班而花的钱」之后的实际月收入（分） */
    val realMonthlyIncomeMinor: Long get() = effectiveMonthlySalaryMinor - monthlyWorkCostMinor

    /** 每月为工作付出的总小时数 */
    val monthlyWorkHours: Double
        get() = workDaysPerMonth * (dailyOfficeHours + dailyCommuteMinutes / 60.0 + dailyOvertimeHours)

    /** 真实时薪（元/小时） */
    val realHourly: Double get() = if (monthlyWorkHours <= 0) 0.0 else realMonthlyIncomeMinor / 100.0 / monthlyWorkHours

    /** 名义时薪（元/小时），两者的差距就是「隐性成本」 */
    val nominalHourly: Double
        get() {
            val hours = workDaysPerMonth * dailyOfficeHours
            return if (hours <= 0) 0.0 else monthlyNetSalaryMinor / 100.0 / hours
        }

    /** 一笔钱要工作多久才能换来（分钟） */
    fun minutesOfWork(amountMinor: Long): Double {
        val hourly = realHourly
        val yuan = kotlin.math.abs(amountMinor) / 100.0
        return if (hourly <= 0) 0.0 else yuan / hourly * 60.0
    }

    fun isSane(): Boolean = monthlyNetSalaryMinor > 0 && monthlyWorkHours > 0
}
