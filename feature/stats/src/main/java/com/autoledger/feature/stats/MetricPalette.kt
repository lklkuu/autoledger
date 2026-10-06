package com.autoledger.feature.stats

/**
 * 统计卡配色的**唯一真源**。
 *
 * 以前每张 Breakdown 卡片各写一份 `listOf("#16856F", ...)`（分类卡甚至还混用分类字典自带的
 * `colorHex`），结果是：同一屏里多张饼图的第 1 片颜色撞车，用户根本分不清哪片属于哪张卡。
 * ——「年度分类结构」卡曾经就是这样第二份重复实现，已在 v1.1.9 收敛到本类。
 * 现在所有 Breakdown 一律通过 [at] 按下标取色：
 * - 下标在调色板内循环，保证同一张卡内相邻片颜色互不相同；
 * - 各卡共用同一组色序，视觉语言统一；
 * - 以后要换主题色，只改这一处。
 *
 * **为什么是 public 而不是 internal**：账单页的「年度分类结构」卡在 `app` 模块
 * （`LedgerScreens.kt`）里就地构造 `MetricResult.Breakdown`，它也要遵守同一套配色；
 * `internal` 跨模块不可见，会逼着调用方另写一份色表 —— 那正是本类要消灭的东西。
 * 既然它的定位就是「**占比**配色的单一真源」，公开给 app 复用是对的。
 *
 * ⚠️ 只管 `Breakdown.Slice.colorHex`（占比条 / 饼图）。流水行图标、分类 chip、预算状态
 * 等用的是**分类自身**的颜色做语义标识，不在这里取色。
 */
object MetricPalette {
    /** 8 色环：低饱和、明暗交替，保证相邻片在深色/浅色背景下都能分辨。 */
    val COLORS: List<String> = listOf(
        "#16856F",
        "#5C88B8",
        "#F6C95F",
        "#D95F5F",
        "#9B6AD0",
        "#116B5B",
        "#708786",
        "#163B3D",
    )

    /** 取第 [index] 片的颜色；超出色环长度时循环回绕，绝不越界。 */
    fun at(index: Int): String = COLORS[index % COLORS.size]
}
