package com.autoledger.feature.stats

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.WageProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 统计维度插件 —— 纯 JVM 单元测试。
 *
 * 仪表盘上每一个数字都来自这里：结构占比、商户排行、渠道分布、月度趋势、时间成本。
 * 金额一律以「分」聚合，任何一条算错都会直接体现在用户看到的月度支出上。
 *
 * 注意：本模块当前**无法编译**（Metrics.kt 调用了 LedgerRepository 上不存在的 listRange，
 * 见报告 S1）。修复 S1 后 `./gradlew :feature:stats:test` 即可运行。
 */
class MetricsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L

    private fun range(from: Long = t0, to: Long = t0 + 30 * day) = TimeRange(from, to)

    private fun repo(vararg txns: LedgerTransaction) =
        FakeLedgerRepository(txns.toList(), listOf(Fixtures.food, Fixtures.transport, Fixtures.income))

    // ------------------------------------------------------------ 净额口径（ExpenseMath）

    @Test
    fun `expense and refund classification keep their own kinds and drop the rest`() {
        val list = listOf(
            Fixtures.txn("e", -100),
            Fixtures.txn("i", 100),
            Fixtures.txn("t", -100, type = TxnType.TRANSFER),
            Fixtures.txn("r", 100, type = TxnType.REFUND),
        )
        assertEquals(listOf("e"), list.filter { ExpenseMath.countsAsExpense(it) }.map { it.id })
        assertEquals(listOf("r"), list.filter { ExpenseMath.countsAsRefund(it) }.map { it.id })
    }

    @Test
    fun `refund offsets expense in the net total`() {
        val list = listOf(
            Fixtures.txn("e", -1_000),
            Fixtures.txn("r", 400, type = TxnType.REFUND),
        )
        assertEquals(1_000L, ExpenseMath.grossExpenseMinor(list))
        assertEquals(400L, ExpenseMath.refundMinor(list))
        assertEquals(600L, ExpenseMath.netExpenseMinor(list))
    }

    // ------------------------------------------------------------ 分类占比

    @Test
    fun `category share aggregates by category and sorts descending`() = runBlocking {
        val r = FakeLedgerRepository(
            listOf(
                Fixtures.txn("a", -1_000, categoryId = "cat_food", occurredAtMillis = t0),
                Fixtures.txn("b", -500, categoryId = "cat_food", occurredAtMillis = t0),
                Fixtures.txn("c", -3_000, categoryId = "cat_transport", occurredAtMillis = t0),
            ),
            listOf(Fixtures.food, Fixtures.transport),
        )

        val result = CategoryShareMetric().compute(range(), r) as MetricResult.Breakdown
        assertEquals(2, result.slices.size)
        assertEquals("cat_transport", result.slices.first().key)
        assertEquals(3_000L, result.slices.first().minor)
        assertEquals(1_500L, result.slices.last().minor)
        assertEquals(4_500L, result.totalMinor)
        assertEquals("交通", result.slices.first().label)
    }

    @Test
    fun `category share buckets unassigned transactions`() = runBlocking {
        val r = FakeLedgerRepository(
            listOf(Fixtures.txn("a", -1_000, occurredAtMillis = t0)),
            listOf(Fixtures.food),
        )
        val result = CategoryShareMetric().compute(range(), r) as MetricResult.Breakdown
        assertEquals(1, result.slices.size)
        assertEquals("unassigned", result.slices.first().key)
        assertEquals("未分类", result.slices.first().label)
        assertEquals("#708786", result.slices.first().colorHex)
    }

    @Test
    fun `category share excludes transfers refunds and income`() = runBlocking {
        val r = FakeLedgerRepository(
            listOf(
                Fixtures.txn("e", -1_000, categoryId = "cat_food", occurredAtMillis = t0),
                Fixtures.txn("i", 9_999, categoryId = "cat_income", occurredAtMillis = t0),
                Fixtures.txn("t", -9_999, type = TxnType.TRANSFER, occurredAtMillis = t0),
                Fixtures.txn("r", 9_999, type = TxnType.REFUND, occurredAtMillis = t0),
            ),
            listOf(Fixtures.food, Fixtures.income),
        )
        val result = CategoryShareMetric().compute(range(), r) as MetricResult.Breakdown
        assertEquals(1_000L, result.totalMinor)
        assertEquals(1, result.slices.size)
    }

    @Test
    fun `category share returns empty slices for empty range`() = runBlocking {
        val result = CategoryShareMetric().compute(range(), repo()) as MetricResult.Breakdown
        assertTrue(result.slices.isEmpty())
        assertEquals(0L, result.totalMinor)
    }

    // ------------------------------------------------------------ 商户排行

    @Test
    fun `merchant top ranks by absolute spend and respects topN`() = runBlocking {
        val r = FakeLedgerRepository(listOf(
            Fixtures.txn("a", -1_000, counterparty = "星巴克", occurredAtMillis = t0),
            Fixtures.txn("b", -2_000, counterparty = "星巴克", occurredAtMillis = t0),
            Fixtures.txn("c", -5_000, counterparty = "滴滴", occurredAtMillis = t0),
            Fixtures.txn("d", -500, counterparty = "瑞幸", occurredAtMillis = t0),
        ))
        val result = MerchantTopMetric(topN = 2).compute(range(), r) as MetricResult.Breakdown
        assertEquals(2, result.slices.size)
        assertEquals("滴滴", result.slices.first().key)
        assertEquals(5_000L, result.slices.first().minor)
        // totalMinor 用全部商户（不截断），保证占比分母正确
        assertEquals(8_500L, result.totalMinor)
    }

    @Test
    fun `merchant top labels blank counterparty as unknown`() = runBlocking {
        val r = FakeLedgerRepository(listOf(Fixtures.txn("a", -100, counterparty = "", occurredAtMillis = t0)))
        val result = MerchantTopMetric().compute(range(), r) as MetricResult.Breakdown
        assertEquals("未知商户", result.slices.first().label)
    }

    @Test
    fun `merchant top palette never runs out for more merchants than colors`() = runBlocking {
        val many = (0 until 20).map { i ->
            Fixtures.txn("t$i", -(100L * (i + 1)), counterparty = "商户$i", occurredAtMillis = t0)
        }
        val r = FakeLedgerRepository(many)
        val result = MerchantTopMetric(topN = 20).compute(range(), r) as MetricResult.Breakdown
        assertEquals(20, result.slices.size)
        assertTrue(result.slices.all { it.colorHex.startsWith("#") })
    }

    // ------------------------------------------------------------ 渠道分布

    @Test
    fun `channel share aggregates by source id and maps display names`() = runBlocking {
        val r = FakeLedgerRepository(listOf(
            Fixtures.txn("a", -1_000, sourceId = "notify", occurredAtMillis = t0),
            Fixtures.txn("b", -2_000, sourceId = "notify", occurredAtMillis = t0),
            Fixtures.txn("c", -500, sourceId = "sms", occurredAtMillis = t0),
        ))
        val names = mapOf("notify" to "支付通知", "sms" to "银行短信")
        val result = ChannelShareMetric(names).compute(range(), r) as MetricResult.Breakdown
        assertEquals(2, result.slices.size)
        assertEquals("支付通知", result.slices.first().label)
        assertEquals(3_000L, result.slices.first().minor)
    }

    @Test
    fun `channel share falls back to raw id when name unknown`() = runBlocking {
        val r = FakeLedgerRepository(listOf(Fixtures.txn("a", -100, sourceId = "manual", occurredAtMillis = t0)))
        val result = ChannelShareMetric(emptyMap()).compute(range(), r) as MetricResult.Breakdown
        assertEquals("manual", result.slices.first().label)
    }

    // ------------------------------------------------------------ 月度趋势

    @Test
    fun `monthly trend emits one point per month`() = runBlocking {
        val result = MonthlyTrendMetric(months = 6).compute(range(), repo()) as MetricResult.Trend
        assertEquals(6, result.points.size)
        assertTrue(result.points.all { it.label.endsWith("月") })
        assertEquals("元", result.unit)
    }

    @Test
    fun `monthly trend only counts transactions inside each month`() = runBlocking {
        val now = System.currentTimeMillis()
        val r = FakeLedgerRepository(listOf(Fixtures.txn("a", -1_000, occurredAtMillis = now)))
        val result = MonthlyTrendMetric(months = 3).compute(TimeRange(now - 90 * day, now), r) as MetricResult.Trend
        assertEquals(3, result.points.size)
        // 当月那一个点必须是 1000 分，前两个月是 0
        assertEquals(1_000L, result.points.last().valueMinor)
        assertEquals(0L, result.points.first().valueMinor)
    }

    // ------------------------------------------------------------ 时间成本

    @Test
    fun `time cost sums expenses and converts to work minutes`() = runBlocking {
        val profile = WageProfile(
            monthlyNetSalaryMinor = 1_200_000L,
            payMonthsPerYear = 12,
            monthlyWorkCostMinor = 0L,
            workDaysPerMonth = 20.0,
            dailyOfficeHours = 8.0,
            dailyCommuteMinutes = 0,
            dailyOvertimeHours = 0.0,
        )
        val r = FakeLedgerRepository(listOf(
            Fixtures.txn("a", -7_500, occurredAtMillis = t0),
            Fixtures.txn("b", -7_500, occurredAtMillis = t0),
            Fixtures.txn("c", 99_999, occurredAtMillis = t0),
        ))
        val result = TimeCostMetric { profile }.compute(range(), r) as MetricResult.Scalar
        assertEquals(15_000L, result.valueMinor)
        // 时薪 75 元 -> 150 元 = 2 小时
        assertTrue(result.secondaryText!!.contains("2.0"), "150 元 @75元/时 应为 2.0 小时，实际 ${result.secondaryText}")
    }

    @Test
    fun `time cost never produces NaN when hourly wage is zero`() = runBlocking {
        val idle = WageProfile(monthlyNetSalaryMinor = 0L, workDaysPerMonth = 0.0)
        val r = FakeLedgerRepository(listOf(Fixtures.txn("a", -1_000, occurredAtMillis = t0)))
        val result = TimeCostMetric { idle }.compute(range(), r) as MetricResult.Scalar
        assertEquals(1_000L, result.valueMinor)
        assertTrue(!result.secondaryText!!.contains("NaN"), "时薪为 0 时不得出现 NaN")
        assertTrue(!result.secondaryText!!.contains("Infinity"))
    }

    @Test
    fun `time cost is zero for empty ledger`() = runBlocking {
        val result = TimeCostMetric { WageProfile() }.compute(range(), repo()) as MetricResult.Scalar
        assertEquals(0L, result.valueMinor)
    }

    // ------------------------------------------------------------ 注册表

    @Test
    fun `metric registry sorts providers by order`() {
        val registry = MetricRegistry(
            listOf(
                MonthlyTrendMetric(),   // order 40
                CategoryShareMetric(),  // order 10
                TimeCostMetric { WageProfile() }, // order 5
            )
        )
        assertEquals(listOf("time_cost", "category_share", "monthly_trend"), registry.providers().map { it.id })
        assertEquals("category_share", registry.find("category_share")!!.id)
    }

    @Test
    fun `provider ids are stable constants`() {
        assertEquals("category_share", CategoryShareMetric.CATEGORY_ID)
        assertEquals("merchant_top", MerchantTopMetric.MERCHANT_ID)
        assertEquals("channel_share", ChannelShareMetric.CHANNEL_ID)
        assertEquals("monthly_trend", MonthlyTrendMetric.TREND_ID)
        assertEquals("time_cost", TimeCostMetric.TIME_COST_ID)
    }
}
