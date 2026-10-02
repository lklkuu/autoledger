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
enum class Dimension { CATEGORY, MERCHANT, PLATFORM, ACCOUNT, TIME, BALANCE }

/**
 * 统计卡片的**语义色阶**（v1.1.6）：与 UI 层的 `LedgerTone` 同名同义，但在 `core:model` 里
 * 单独定义，是为了让「卡片要表达什么语义」由领域层决定，UI 只做映射（`MetricTone → LedgerTone`）。
 *
 * 为什么不直接复用 UI 的 `LedgerTone`：`core:model` 不能依赖 app 模块。
 */
enum class MetricTone { EXPENSE, INCOME, NEUTRAL }

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
        /**
         * 语义色阶（v1.1.6）。默认 [MetricTone.EXPENSE] ⇒ 既有支出类卡片（花掉的时间）外观不变。
         *
         * 结余类卡片用 [MetricTone.INCOME] / [MetricTone.NEUTRAL] 区分「有结余」与「入不敷出」，
         * 避免负数被涂成"赚到了"的颜色。
         */
        val tone: MetricTone = MetricTone.EXPENSE,
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

/**
 * 一次聚合批次内可复用的数据快照。
 *
 * 背景：首页（HomeStore）与发现页（InsightsStore）在**同一轮聚合**里渲染多张统计卡片，
 * 而每张卡片各自 `repo.listRange(...)` 会把同一窗口的流水从数据库重复拉 N 遍。
 * 上层把窗口流水取一次装进快照传下来，各卡片直接复用，数据库只查一次。
 *
 * **快照口径契约**（构造方负责遵守，卡片实现侧也应防御性校验）：
 * - [txns] **含退款（REFUND）**——退款冲抵是统计口径的一部分；
 * - [txns] **不含内部划转（TRANSFER）**；
 * - 不含 MERGED / IGNORED 状态的流水；
 * - [range] 必须等于本次 `compute` 收到的 range —— 不一致时实现侧必须回退自查；
 * - [categories] 可为空：不涉及分类维度的调用方不必为此多查一次字典。
 */
data class MetricSnapshot(
    val range: TimeRange,
    val txns: List<LedgerTransaction>,
    val categories: List<Category> = emptyList(),
)

interface MetricProvider {
    val id: String
    val title: String
    val dimension: Dimension
    /** 数值越小越靠前 */
    val order: Int

    /**
     * 计算卡片。
     *
     * @param snapshot 上层预取的窗口快照，可复用则**不得**再查库；`null` 或
     *   `snapshot.range != range` 时回退自行 `repo.listRange(...)`（行为与快照口径一致）。
     */
    suspend fun compute(
        range: TimeRange,
        repo: LedgerRepository,
        snapshot: MetricSnapshot? = null,
    ): MetricResult
}
