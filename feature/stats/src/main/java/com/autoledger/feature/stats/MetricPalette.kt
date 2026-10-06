package com.autoledger.feature.stats

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 统计卡配色的**唯一真源**。
 *
 * 以前每张 Breakdown 卡片各写一份 `listOf("#16856F", ...)`（分类卡甚至还混用分类字典自带的
 * `colorHex`），结果是：同一屏里多张饼图的第 1 片颜色撞车，用户根本分不清哪片属于哪张卡。
 * ——「年度分类结构」卡曾经就是这样第二份重复实现，已在 v1.1.9 收敛到本类。
 * 现在所有 Breakdown 一律通过 [at] 按下标取色：
 * - 同一张卡内不出现重复色；
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

    /** 基础 8 色环：低饱和、明暗交替，保证相邻片在深色/浅色背景下都能分辨。 */
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

    /**
     * 取第 [index] 片的颜色，**保证同一张卡内不会有两种片落到同一个色**。
     *
     * 为什么不能按下标取模回绕：分类卡与「年度分类结构」卡是**按分类逐片出图、没有 topN 截断**的
     * （商户卡 / 平台卡有 `topN = 8`），而出厂种子就有 **10 个支出分类**，年度窗口几乎必然用到 8 个以上。
     * 一旦回绕，第 9 片就和第 1 片同色 —— 又回到「两个分类一个颜色」这个最初的毛病上
     * （`DefaultSeed` 里「餐饮」与「人情往来」本来都是 `#D95F5F`，用户就是这么发现问题的）。
     *
     * 所以下标 < [COLORS].size 走色环，超出后用**色轮取色**：
     * - 色相按黄金角 [GOLDEN_ANGLE_DEG] 递进，相邻下标在色相环上拉开约 137°，天然不撞色；
     * - 明度按下标奇偶在 44% / 62% 之间交替；
     * - 饱和度再按 `index % 3` 微调，让「色相接近又恰好相邻」的两片也能分辨。
     *
     * 颜色**只由下标决定**，因此同一个排名在任何卡片、任何时间都是同一个颜色，
     * 不随分类字典内容漂移（这条稳定性在 MetricsTest 里有断言钉死）。
     */
    fun at(index: Int): String {
        require(index >= 0) { "配色下标不能为负，实际 $index" }
        if (index < COLORS.size) return COLORS[index]
        val hue = (GOLDEN_ANGLE_DEG * index) % 360.0
        val lightness = if (index % 2 == 0) 0.44 else 0.62
        val saturation = if (index % 3 == 0) 0.62 else 0.50
        return hslToHex(hue, saturation, lightness)
    }

    /**
     * 黄金角（137.508°）：任意相邻两项在色相环上相距约 137.5°，
     * 是「用最少的颜色尽量铺满色环」的经典取法，比均分（360/n，n 变化时要整体重排）更适合不定长序列。
     */
    private const val GOLDEN_ANGLE_DEG = 137.508

    /** HSL → `#RRGGBB`。色相 [hueDeg] 单位为度，[saturation] / [lightness] 取 0..1。 */
    private fun hslToHex(hueDeg: Double, saturation: Double, lightness: Double): String {
        val c = (1 - abs(2 * lightness - 1)) * saturation
        val h = hueDeg / 60.0
        val x = c * (1 - abs(h % 2 - 1))
        val (r1, g1, b1) = when {
            h < 1 -> Triple(c, x, 0.0)
            h < 2 -> Triple(x, c, 0.0)
            h < 3 -> Triple(0.0, c, x)
            h < 4 -> Triple(0.0, x, c)
            h < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = lightness - c / 2
        fun channel(v: Double): Int = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(channel(r1), channel(g1), channel(b1))
    }
}
