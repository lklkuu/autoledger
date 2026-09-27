package com.autoledger.core.model

/**
 * 应用级设置（时薪参数 + 自由基金目标 + 行为开关）。
 * 作为一个整体往返于 Room 与备份档案，避免设置散落在多处存储。
 */
data class AppSettings(
    val wage: WageProfile = WageProfile(),
    val goal: FreedomGoal = FreedomGoal(),
    val autoMerge: Boolean = true,
)
