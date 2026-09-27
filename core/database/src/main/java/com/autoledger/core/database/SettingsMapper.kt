package com.autoledger.core.database

import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.WageProfile

/** 应用设置实体 <-> 领域模型的映射（app 与 core:backup 共用，故为 public）。 */

fun SettingsEntity.toAppSettings(): AppSettings = AppSettings(
    wage = WageProfile(
        monthlyNetSalaryMinor = wageSalaryMinor,
        payMonthsPerYear = wagePayMonths,
        monthlyWorkCostMinor = wageWorkCostMinor,
        workDaysPerMonth = wageWorkDays,
        dailyOfficeHours = wageOfficeHours,
        dailyCommuteMinutes = wageCommuteMinutes,
        dailyOvertimeHours = wageOvertimeHours,
    ),
    goal = FreedomGoal(
        targetMinor = goalTargetMinor,
        cushionMinor = goalCushionMinor,
        currentMinor = goalCurrentMinor,
    ),
    autoMerge = autoMerge,
)

fun AppSettings.toEntity(): SettingsEntity = SettingsEntity(
    id = "global",
    wageSalaryMinor = wage.monthlyNetSalaryMinor,
    wagePayMonths = wage.payMonthsPerYear,
    wageWorkCostMinor = wage.monthlyWorkCostMinor,
    wageWorkDays = wage.workDaysPerMonth,
    wageOfficeHours = wage.dailyOfficeHours,
    wageCommuteMinutes = wage.dailyCommuteMinutes,
    wageOvertimeHours = wage.dailyOvertimeHours,
    goalTargetMinor = goal.targetMinor,
    goalCushionMinor = goal.cushionMinor,
    goalCurrentMinor = goal.currentMinor,
    autoMerge = autoMerge,
)
