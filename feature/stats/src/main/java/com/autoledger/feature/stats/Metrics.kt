package com.autoledger.feature.stats

import com.autoledger.core.model.Dimension
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MetricProvider
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.MetricSnapshot
import com.autoledger.core.model.Money
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.formatYuan
import com.autoledger.core.model.platform.PlatformCatalog
import java.time.YearMonth

/**
 * 同一批聚合里「快照优先、自查回退」的统一取数：快照存在且窗口一致时**不得**再查库。
 * 抽成一个函数，避免每张卡片各写一份 `snapshot?.takeIf { ... } ?: repo.listRange(...)`。
 */
private suspend fun resolveTxns(
    snapshot: MetricSnapshot?,
    range: TimeRange,
    repo: LedgerRepository,
): List<LedgerTransaction> =
    snapshot?.takeIf { it.range == range }?.txns
        ?: repo.listRange(range.startMillis, range.endInclusiveMillis)

/**
 * 统计维度插件集合（工程要求 1：统计维度模块化可插拔）。
 *
 * 仪表盘不做任何硬编码：它遍历 [MetricRegistry.providers] 依次渲染卡片。
 * 想加「按星期分布」「按城市分布」，写一个类注册进来即可，UI 一行都不用改。
 */

/** 分类占比 —— 一眼看出钱花在哪 */
class CategoryShareMetric : MetricProvider {
    override val id: String = CATEGORY_ID
    override val title: String = "消费结构"
    override val dimension: Dimension = Dimension.CATEGORY
    override val order: Int = 10

    override suspend fun compute(range: TimeRange, repo: LedgerRepository, snapshot: MetricSnapshot?): MetricResult {
        // 分类字典优先取快照（快照非空时省掉一次 listCategories），空则照旧自查
        val categories = (snapshot?.categories?.takeIf { it.isNotEmpty() } ?: repo.listCategories())
            .associateBy { it.id }
        val txns = resolveTxns(snapshot, range, repo)
        // 退款按分类冲抵支出（口径唯一真源见 ExpenseMath）
        val buckets = ExpenseMath.netBy(txns) { it.categoryId.orEmpty() }
        val slices = buckets.entries.sortedByDescending { it.value }.map { (rawKey, minor) ->
            val key = rawKey.orEmpty()
            val cat = categories[key]
            MetricResult.Breakdown.Slice(
                key = key.ifBlank { "unassigned" },
                label = cat?.name ?: "未分类",
                minor = minor,
                colorHex = cat?.colorHex ?: "#708786",
                iconKey = cat?.iconKey,
            )
        }
        return MetricResult.Breakdown(
            CATEGORY_ID, title,
            "净支出（已扣退款），共 ${txns.count { ExpenseMath.countsAsExpense(it) }} 笔",
            buckets.values.sum(), slices,
        )
    }

    companion object { const val CATEGORY_ID = "category_share" }
}

/** 商户排行 —— 找出真正在掏空钱包的几家店 */
class MerchantTopMetric(private val topN: Int = 8) : MetricProvider {
    override val id: String = MERCHANT_ID
    override val title: String = "商户排行"
    override val dimension: Dimension = Dimension.MERCHANT
    override val order: Int = 20

    override suspend fun compute(range: TimeRange, repo: LedgerRepository, snapshot: MetricSnapshot?): MetricResult {
        val txns = resolveTxns(snapshot, range, repo)
        val buckets = ExpenseMath.netBy(txns) { it.counterparty.ifBlank { "未知商户" } }
        val ranked = buckets.entries.sortedByDescending { it.value }.take(topN)
        val palette = listOf("#16856F", "#5C88B8", "#F6C95F", "#D95F5F", "#9B6AD0", "#116B5B", "#708786", "#163B3D")
        val slices = ranked.mapIndexed { i, (rawKey, minor) ->
            val key = rawKey.orEmpty()
            MetricResult.Breakdown.Slice(key, key, minor, palette[i % palette.size])
        }
        return MetricResult.Breakdown(MERCHANT_ID, title, null, buckets.values.sum(), slices)
    }

    companion object { const val MERCHANT_ID = "merchant_top" }
}

/**
 * 消费平台分布 —— 与 [MerchantTopMetric] 构成「消费平台 / 商户」两个并列的业务维度。
 *
 * 与「采集来源」的区别：[LedgerTransaction.sourceId]（通知 / 短信 / 账单导入）是**技术追溯**字段，
 * 不是业务维度，因此不再作为统计口径（原 `ChannelShareMetric` 已删除）。
 */
class PlatformShareMetric(private val topN: Int = 8) : MetricProvider {
    override val id: String = PLATFORM_ID
    override val title: String = "消费平台分布"
    override val dimension: Dimension = Dimension.PLATFORM
    override val order: Int = 30

    override suspend fun compute(range: TimeRange, repo: LedgerRepository, snapshot: MetricSnapshot?): MetricResult {
        val txns = resolveTxns(snapshot, range, repo)
        val buckets = ExpenseMath.netBy(txns) { it.platformId }
        val ranked = buckets.entries.sortedByDescending { it.value }.take(topN)
        val slices = ranked.mapIndexed { i, (rawKey, minor) ->
            val key = rawKey.orEmpty().ifBlank { PlatformCatalog.UNKNOWN_ID }
            MetricResult.Breakdown.Slice(
                key = key,
                label = PlatformCatalog.displayNameOf(key),
                minor = minor,
                colorHex = PLATFORM_PALETTE[i % PLATFORM_PALETTE.size],
            )
        }
        // 「未知」笔数单独提示：让用户一眼看出还有多少笔待补平台，形成修正闭环。
        val unknownCount = txns.count {
            it.platformId.isBlank() || it.platformId == PlatformCatalog.UNKNOWN_ID
        }
        val subtitle = if (unknownCount > 0) "其中 $unknownCount 笔平台未知，可在账单页筛选后补全" else null
        return MetricResult.Breakdown(PLATFORM_ID, title, subtitle, buckets.values.sum(), slices)
    }

    companion object {
        const val PLATFORM_ID = "platform_share"
        private val PLATFORM_PALETTE =
            listOf("#16856F", "#5C88B8", "#F6C95F", "#D95F5F", "#9B6AD0", "#116B5B", "#708786", "#163B3D")
    }
}

/** 近 6 个月趋势 */
class MonthlyTrendMetric(
    private val months: Int = 6,
    /**
     * 时钟注入点：生产默认取系统时钟。
     * 「当前月柱右端 = now」这条口径必须可被确定性断言，故把 now 提为构造参数（测试注入固定值）。
     */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MetricProvider {
    override val id: String = TREND_ID
    override val title: String = "月度趋势"
    override val dimension: Dimension = Dimension.TIME
    override val order: Int = 40

    override suspend fun compute(range: TimeRange, repo: LedgerRepository, snapshot: MetricSnapshot?): MetricResult {
        // 趋势卡**忽略快照**：快照只装着本次聚合的当月窗口，而趋势需要最近 N 个月的宽窗，口径不同，
        // 误用会把其余月份算成 0。这里一律自查一次宽窗（见 [monthlyWideWindow]）。
        val zone = java.time.ZoneId.systemDefault()
        val anchor = java.time.Instant.ofEpochMilli(range.endInclusiveMillis).atZone(zone).toLocalDate()
        val now = nowMillis()
        val anchorMonth = YearMonth.from(anchor)
        val wide = monthlyWideWindow(months, anchorMonth, now)
        // 一次宽窗查询 + 内存分桶，替代旧实现逐月 6 次独立 listRange（数据库往返 6 次 → 1 次）。
        // includeTransfers=true：宽窗必须**带退款**——退款冲抵趋势柱是本次口径修正的一部分
        // （旧实现的默认 listRange 把 REFUND 一并剔除，退款从不参与趋势）。netExpenseMinor
        // 只认 EXPENSE/REFUND，混进来的 TRANSFER 会被自动忽略，无需预过滤。
        val txns = repo.listRange(wide.startMillis, wide.endInclusiveMillis, includeTransfers = true)
        return MetricResult.Trend(TREND_ID, title, null, "元", monthlyBuckets(txns, months, anchorMonth, now))
    }

    companion object { const val TREND_ID = "monthly_trend" }
}

/**
 * [MonthlyTrendMetric] 的宽窗：**首月初 00:00 → now**（右端实时 now，与当前月柱口径一致；
 * 过去月的右端反正小于 now，宽窗不会多吃未来流水）。
 */
internal fun monthlyWideWindow(months: Int, anchorMonth: YearMonth, nowMillis: Long): TimeRange {
    val earliest = TimeRange.monthOf(anchorMonth.minusMonths((months - 1).coerceAtLeast(0).toLong()), nowMillis)
    return TimeRange(earliest.startMillis, nowMillis)
}

/**
 * [MonthlyTrendMetric] 的内存分桶（纯函数）：把宽窗流水按 [TimeRange.monthOf] 的月度口径切回各月。
 *
 * 月末边界统一走 monthOf（月度口径的单一真源）：右端 = 「次月 1 日 00:00 − 1ms」（双闭区间），
 * 闰年 2 月自动正确；当前月右端夹到 now，不再计入未来日期的流水。
 * 趋势柱不做负值：退款多于支出时夹到 0。
 */
internal fun monthlyBuckets(
    txns: List<LedgerTransaction>,
    months: Int,
    anchorMonth: YearMonth,
    nowMillis: Long,
): List<MetricResult.Trend.Point> =
    (months - 1 downTo 0).map { offset ->
        val month = anchorMonth.minusMonths(offset.toLong())
        val window = TimeRange.monthOf(month, nowMillis)
        val inMonth = txns.filter { it.occurredAtMillis in window.startMillis..window.endInclusiveMillis }
        val total = ExpenseMath.netExpenseMinor(inMonth).coerceAtLeast(0L)
        MetricResult.Trend.Point("${month.monthValue}月", total)
    }

/**
 * 把钱换算成时间 —— 参考仪表盘的招牌指标。
 * 「这个月花掉的钱 = 你 XXX 小时的人生」。
 */
class TimeCostMetric(private val profileProvider: () -> WageProfile) : MetricProvider {
    override val id: String = TIME_COST_ID
    override val title: String = "花掉的时间"
    override val dimension: Dimension = Dimension.TIME
    override val order: Int = 5

    override suspend fun compute(range: TimeRange, repo: LedgerRepository, snapshot: MetricSnapshot?): MetricResult {
        val profile = profileProvider()
        val totalMinor = ExpenseMath.netExpenseMinor(
            resolveTxns(snapshot, range, repo),
        ).coerceAtLeast(0L)
        val minutes = profile.minutesOfWork(totalMinor)
        val hoursText = "%.1f".format(minutes / 60.0)
        return MetricResult.Scalar(
            providerId = TIME_COST_ID,
            title = title,
            subtitle = "按真实时薪 ¥${"%.1f".format(profile.realHourly)}/时 换算",
            valueMinor = totalMinor,
            // 主指标是「时间」而不是钱：这张卡片的灵魂是「这笔钱 = 你多少小时的人生」，
            // 故大字显示工时，折算金额退到次要位置。
            primaryText = "≈ $hoursText 小时",
            secondaryText = "折合 ¥${Money(totalMinor).formatYuan(withSign = false)}",
            iconKey = "clock",
        )
    }

    companion object { const val TIME_COST_ID = "time_cost" }
}

/**
 * 维度注册表。App 启动时把想要的维度注册进来；设置页可以按主导地位关掉不需要的卡片。
 */
class MetricRegistry(providers: List<MetricProvider>) {
    private val ordered = providers.sortedBy { it.order }
    fun providers(): List<MetricProvider> = ordered
    fun find(id: String): MetricProvider? = ordered.firstOrNull { it.id == id }
}
