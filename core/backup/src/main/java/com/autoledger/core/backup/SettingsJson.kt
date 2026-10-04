package com.autoledger.core.backup

import com.autoledger.core.model.AiMode
import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.WageProfile
import org.json.JSONObject

/**
 * 设置（时薪参数 / 自由基金目标 / 行为开关）的备份 JSON 编解码。
 *
 * 与 `TxnJson.kt` 同理：抽成**顶层纯函数**并放宽为 `internal`，
 * 这样「旧版本档案能不能导进来」这件事可以脱离 Room / BackupManager 直接单测
 * —— 否则为了测两行 JSON 就得先造一个 LedgerDatabase。
 *
 * 兼容约定（增删字段时必须遵守）：
 * - 导出用 `put`，导入一律用 `optXxx` + 默认值，**永不**用 `getXxx`（缺字段会抛异常）；
 * - 因此**旧档案多出来的字段会被天然忽略**，已下线的字段（如 `cushionMinor`）
 *   只需从导出侧删掉，导入侧无需任何特殊处理，存量备份照旧可用。
 */
internal fun AppSettings.toSettingsJson(): JSONObject = JSONObject().apply {
    put("wage", JSONObject().apply {
        put("monthlyNetSalaryMinor", wage.monthlyNetSalaryMinor)
        put("payMonthsPerYear", wage.payMonthsPerYear)
        put("monthlyWorkCostMinor", wage.monthlyWorkCostMinor)
        put("workDaysPerMonth", wage.workDaysPerMonth)
        put("dailyOfficeHours", wage.dailyOfficeHours)
        put("dailyCommuteMinutes", wage.dailyCommuteMinutes)
        put("dailyOvertimeHours", wage.dailyOvertimeHours)
    })
    put("goal", JSONObject().apply {
        put("targetMinor", goal.targetMinor)
        put("currentMinor", goal.currentMinor)
    })
    put("autoMerge", autoMerge)
    // ---- AI 判定配置（可导出：换机不必重填）----
    // ⚠️ 这里**只允许**出现开关 / 模式 / 接口地址 / 模型名四项。
    // **绝不**导出 API 密钥 —— 密钥由 core:crypto 的 AiKeyVault 单独保管，不进 Room、不进备份。
    // 见 SettingsJsonAiTest 的「密钥绝不出现」护栏。
    put("aiEnabled", aiEnabled)
    put("aiMode", aiMode.name)
    put("aiEndpoint", aiEndpoint)
    put("aiModel", aiModel)
}

/**
 * 反向解析。**全字段 `optXxx`**：任一段缺失都退回默认值，绝不抛异常。
 */
internal fun JSONObject.parseAppSettings(): AppSettings {
    val w = optJSONObject("wage")
    val g = optJSONObject("goal")
    return AppSettings(
        wage = WageProfile(
            monthlyNetSalaryMinor = w?.optLong("monthlyNetSalaryMinor") ?: WageProfile().monthlyNetSalaryMinor,
            payMonthsPerYear = w?.optInt("payMonthsPerYear") ?: WageProfile().payMonthsPerYear,
            monthlyWorkCostMinor = w?.optLong("monthlyWorkCostMinor") ?: WageProfile().monthlyWorkCostMinor,
            workDaysPerMonth = w?.optDouble("workDaysPerMonth") ?: WageProfile().workDaysPerMonth,
            dailyOfficeHours = w?.optDouble("dailyOfficeHours") ?: WageProfile().dailyOfficeHours,
            dailyCommuteMinutes = w?.optInt("dailyCommuteMinutes") ?: WageProfile().dailyCommuteMinutes,
            dailyOvertimeHours = w?.optDouble("dailyOvertimeHours") ?: WageProfile().dailyOvertimeHours,
        ),
        goal = FreedomGoal(
            targetMinor = g?.optLong("targetMinor") ?: FreedomGoal().targetMinor,
            currentMinor = g?.optLong("currentMinor") ?: FreedomGoal().currentMinor,
        ),
        autoMerge = optBoolean("autoMerge", true),
        // 全字段 optXxx + 默认值：老档案（v1.1.6 及更早）没有这四个键，必须能照旧导入。
        aiEnabled = optBoolean("aiEnabled", false),
        // 未知枚举名（未来版本写入、或手改档案）回落 FALLBACK，绝不抛异常。
        aiMode = runCatching { AiMode.valueOf(optString("aiMode") ?: "FALLBACK") }.getOrDefault(AiMode.FALLBACK),
        aiEndpoint = optString("aiEndpoint").orEmpty(),
        aiModel = optString("aiModel").orEmpty(),
    )
}
