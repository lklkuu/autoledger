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
    // 「安全垫金额」已下线（产品改版：已攒改为系统自动计算）。
    // 列本身**保留**：`app_settings` 的历史 schema（3/4/5.json）里有这条 NOT NULL 列，
    // 而 minSdk 26 的 SQLite 版本不可靠地支持 `ALTER TABLE ... DROP COLUMN`（3.35+ 才有），
    // 硬删需要「建新表→拷数据→改名」的重型迁移，风险远大于留一条恒为 0 的死列。
    // 因此这里恒写 0，读取侧（toAppSettings）已不再映射它。
    goalCushionMinor = 0L,
    goalCurrentMinor = goal.currentMinor,
    autoMerge = autoMerge,
)
