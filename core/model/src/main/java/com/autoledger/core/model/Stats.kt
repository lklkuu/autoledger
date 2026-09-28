package com.autoledger.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * 统计维度插件契约。
 *
 * 仪表盘不写死任何一张图：它会遍历注册进来的 [MetricProvider]，按顺序渲染卡片。
 * 想加「按星期分布」「按城市分布」这类新维度，新增一个实现类注册进来即可。
 */
enum class Dimension { CATEGORY, MERCHANT, PLATFORM, ACCOUNT, TIME }

sealed interface MetricResult {
    val providerId: String
    val title: String
    val subtitle: String?

    /** 占比类（饼图 / 条形榜） */
    data class Breakdown(
        override val providerId: String,
        override val title: String,
        override val subtitle: String? = null,
        val totalMinor: Long,
        val slices: List<Slice>,
    ) : MetricResult {
        data class Slice(
            val key: String,
            val label: String,
            val minor: Long,
            val colorHex: String,
            val iconKey: String? = null,
        )
    }

    /** 趋势类（折线 / 柱状序列） */
    data class Trend(
        override val providerId: String,
        override val title: String,
        override val subtitle: String? = null,
        val unit: String,
        val points: List<Point>,
    ) : MetricResult {
        data class Point(val label: String, val valueMinor: Long)
    }

    /** 单值 KPI */
    data class Scalar(
        override val providerId: String,
        override val title: String,
        override val subtitle: String? = null,
        val valueMinor: Long,
        val secondaryText: String? = null,
        val iconKey: String? = null,
    ) : MetricResult
}

data class TimeRange(val startMillis: Long, val endInclusiveMillis: Long) {

    companion object {
        fun today(now: Long = System.currentTimeMillis()): TimeRange {
            val midnight = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
                .atStartOfDay(ZONE).toInstant().toEpochMilli()
            return TimeRange(midnight, now)
        }

        fun thisMonth(now: Long = System.currentTimeMillis()): TimeRange {
            val firstDay = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
                .withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
            return TimeRange(firstDay, now)
        }

        /** 最近 n 天的滚动窗口（含今天） */
        fun lastDays(days: Int, now: Long = System.currentTimeMillis()): TimeRange {
            // 非正数按一天处理，避免调用方得到 start > end 的静默空区间。
            val safeDays = days.coerceAtLeast(1)
            val anchor = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
                .minusDays(safeDays.toLong() - 1L).atStartOfDay(ZONE).toInstant().toEpochMilli()
            return TimeRange(anchor, now)
        }

        /** 统一使用 java.time 导入，避免时间窗口实现漏掉类型依赖。 */
        private val ZONE: ZoneId get() = ZoneId.systemDefault()
    }
}

interface MetricProvider {
    val id: String
    val title: String
    val dimension: Dimension
    /** 数值越小越靠前 */
    val order: Int
    suspend fun compute(range: TimeRange, repo: LedgerRepository): MetricResult
}
