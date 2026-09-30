package com.autoledger.core.model

import java.time.Instant
import java.time.YearMonth
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
        /**
         * 主数值的展示文本，非空时**优先于** [valueMinor]。
         *
         * 用于「花掉的时间」这类**主指标不是金额**的卡片：用户要看的是「≈ 0.8 小时」，
         * 而不是它折算出来的钱。金额退到 [secondaryText] 作为补充。
         */
        val primaryText: String? = null,
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

        /**
         * **指定月份**的区间 —— 供「发现」页按月查看。
         *
         * 与 [thisMonth] 的区别：后者锚定「现在」，这里锚定任意月份。
         * 当前月仍把右端夹到 `now`（避免未来日期的预授权流水混进本月）；过去月右端取该月**最后一毫秒**。
         *
         * ⚠️ 右端必须写成「次月 1 日 00:00 − 1ms」，不能写「本月最后一天 00:00」：
         * 查询走 `occurredAtMillis BETWEEN from AND to`（**双闭区间**），
         * 给最后一天 00:00 会漏掉当月 23:59:59 的流水；给次月 1 日 00:00 会多吃下月一笔。
         * 先 `plusMonths(1)` 再减 1ms，闰年 2 月自动正确。
         */
        fun monthOf(yearMonth: YearMonth, now: Long = System.currentTimeMillis()): TimeRange {
            val zone = ZONE
            val start = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val nextMonthStart = yearMonth.plusMonths(1)
                .atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val isCurrentMonth = yearMonth == YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
            return TimeRange(start, if (isCurrentMonth) now else nextMonthStart - 1L)
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
