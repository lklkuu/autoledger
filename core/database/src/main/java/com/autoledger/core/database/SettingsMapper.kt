package com.autoledger.core.database

import com.autoledger.core.model.AiMode
import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.WageVersion

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
    aiEnabled = aiEnabled,
    // 库里存的是枚举名；遇到未来版本写入的未知值（或手改库的脏数据）回落 FALLBACK，绝不抛异常。
    aiMode = runCatching { AiMode.valueOf(aiMode) }.getOrDefault(AiMode.FALLBACK),
    aiEndpoint = aiEndpoint,
    aiModel = aiModel,
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
    aiEnabled = aiEnabled,
    aiMode = aiMode.name,
    aiEndpoint = aiEndpoint,
    aiModel = aiModel,
)

// ---------------------------------------------------------------- 时薪按月版本（v8）

/**
 * `wage_versions` 行 → 领域模型。
 *
 * 工时参数也一并还原：历史月份的「花掉的时间」换算必须用**当时**的参数，
 * 而不是今天 `app_settings` 里的那一套。
 */
fun WageVersionEntity.toWageVersion(): WageVersion = WageVersion(
    month = month,
    profile = WageProfile(
        monthlyNetSalaryMinor = monthlyNetSalaryMinor,
        payMonthsPerYear = payMonthsPerYear,
        monthlyWorkCostMinor = monthlyWorkCostMinor,
        workDaysPerMonth = workDaysPerMonth,
        dailyOfficeHours = dailyOfficeHours,
        dailyCommuteMinutes = dailyCommuteMinutes,
        dailyOvertimeHours = dailyOvertimeHours,
    ),
    // ⚠️ 用落库值而不是临时重算 `profile.effectiveMonthlySalaryMinor`：
    // 一旦将来调整年化方式或舍入策略，重算会把历史月份的收入改写掉，
    // 而"历史月份保留当时结果"正是这张表存在的理由。
    monthlyIncomeMinor = monthlyIncomeMinor,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

/**
 * 领域模型 → `wage_versions` 行。
 *
 * @param schemaVersion 行级结构标记，默认取 [LedgerSchema.CURRENT]
 *   （与 transactions / user_platforms 用同一个常量；迁移播种也用同一个值）。
 */
fun WageVersion.toEntity(schemaVersion: Int = LedgerSchema.CURRENT): WageVersionEntity = WageVersionEntity(
    month = month,
    monthlyNetSalaryMinor = profile.monthlyNetSalaryMinor,
    payMonthsPerYear = profile.payMonthsPerYear,
    monthlyWorkCostMinor = profile.monthlyWorkCostMinor,
    workDaysPerMonth = profile.workDaysPerMonth,
    dailyOfficeHours = profile.dailyOfficeHours,
    dailyCommuteMinutes = profile.dailyCommuteMinutes,
    dailyOvertimeHours = profile.dailyOvertimeHours,
    monthlyIncomeMinor = monthlyIncomeMinor,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    schemaVersion = schemaVersion,
)
