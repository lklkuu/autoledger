package com.autoledger.core.backup

import com.autoledger.core.model.AiMode
import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.WageLedger
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.WageVersion
import org.json.JSONArray
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

// ---------------------------------------------------------------- 时薪按月版本（v8）

/**
 * 时薪版本数组的导出（v8 新增）。
 *
 * ## 为什么放在 payload **根级**而不是 `settings` 段
 * `BackupManager.buildPayload` 里 `settings` 是 `db.settingsDao().global()?.let { … }` ——
 * **没有设置行时整段不写**，版本数组会被一起丢掉；而版本表完全可能非空
 * （导入过新档案、或迁移播种过）。根级与既有 `platforms` 一节同款做法，
 * 且旧解析器用 `optJSONArray` 取不到就当没有 ⇒ 双向兼容。
 *
 * ## 字段是全量的
 * 每行都带完整 [WageProfile] 参数 + 落库的 `monthlyIncomeMinor`：
 * 恢复快照后历史月份的收入必须与导出时**逐分一致**，
 * 不能靠"用档案里的某个时薪重新推算"。
 */
internal fun List<WageVersion>.toWageVersionsJson(): JSONArray = JSONArray().apply {
    for (version in this@toWageVersionsJson) {
        put(JSONObject().apply {
            put("month", version.month)
            put("monthlyNetSalaryMinor", version.profile.monthlyNetSalaryMinor)
            put("payMonthsPerYear", version.profile.payMonthsPerYear)
            put("monthlyWorkCostMinor", version.profile.monthlyWorkCostMinor)
            put("workDaysPerMonth", version.profile.workDaysPerMonth)
            put("dailyOfficeHours", version.profile.dailyOfficeHours)
            put("dailyCommuteMinutes", version.profile.dailyCommuteMinutes)
            put("dailyOvertimeHours", version.profile.dailyOvertimeHours)
            put("monthlyIncomeMinor", version.monthlyIncomeMinor)
            put("createdAtMillis", version.createdAtMillis)
            put("updatedAtMillis", version.updatedAtMillis)
        })
    }
}

/**
 * 时薪版本数组的解析。**全字段 `optXxx`，月份非法的条目直接丢弃而不是抛错。**
 *
 * 丢弃而不是"修正"的理由：一个 `month = "2026-13"` 的行无法被可靠地归到任何月份，
 * 保留它只会让 [WageLedger.effectiveVersion] 的"最早一条"判定被污染。
 * 视作不存在，最坏结果只是少一个月版本（收入回落到相邻版本），绝不会崩 App。
 *
 * @return `null` / 空数组 ⇒ 空列表（旧档案没有 `wageVersions` 一节）
 */
internal fun JSONArray?.parseWageVersions(): List<WageVersion> {
    if (this == null) return emptyList()
    val out = ArrayList<WageVersion>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        val month = o.optString("month", "")
        if (!WageLedger.isValidMonth(month)) continue
        val profile = WageProfile(
            monthlyNetSalaryMinor = o.optLong("monthlyNetSalaryMinor", WageProfile().monthlyNetSalaryMinor),
            payMonthsPerYear = o.optInt("payMonthsPerYear", WageProfile().payMonthsPerYear),
            monthlyWorkCostMinor = o.optLong("monthlyWorkCostMinor", WageProfile().monthlyWorkCostMinor),
            workDaysPerMonth = o.optDouble("workDaysPerMonth", WageProfile().workDaysPerMonth),
            dailyOfficeHours = o.optDouble("dailyOfficeHours", WageProfile().dailyOfficeHours),
            dailyCommuteMinutes = o.optInt("dailyCommuteMinutes", WageProfile().dailyCommuteMinutes),
            dailyOvertimeHours = o.optDouble("dailyOvertimeHours", WageProfile().dailyOvertimeHours),
        )
        val createdAt = o.optLong("createdAtMillis", 0L)
        out += WageVersion(
            month = month,
            profile = profile,
            // 缺失时用当时的公式结果兜底（例如手写档案只填了参数）：
            // 宁可现算一个合理值，也不要让该月收入变成 0。
            monthlyIncomeMinor = o.optLong("monthlyIncomeMinor", profile.effectiveMonthlySalaryMinor),
            createdAtMillis = createdAt,
            updatedAtMillis = o.optLong("updatedAtMillis", createdAt),
        )
    }
    return out
}
