package com.autoledger.feature.stats

import com.autoledger.core.model.Category
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.MetricSnapshot
import com.autoledger.core.model.MetricTone
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.WageVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 统计维度插件 —— 纯 JVM 单元测试。
 *
 * 仪表盘上每一个数字都来自这里：结构占比、商户排行、消费平台结构、月度趋势、时间成本。
 * 金额一律以「分」聚合，任何一条算错都会直接体现在用户看到的月度支出上。
 *
 * 运行：`./gradlew :feature:stats:test`。
 * （历史注记：本模块曾因 `Metrics.kt` 调用了 `LedgerRepository` 上不存在的 `listRange`
 * 而无法编译，该缺陷早已修复；此说明保留仅为解释旧报告的 S1 指代，不要据此判断当前状态。）
 */
class MetricsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L

    private fun range(from: Long = t0, to: Long = t0 + 30 * day) = TimeRange(from, to)

    private fun repo(vararg txns: LedgerTransaction) =
        FakeLedgerRepository(txns.toList(), listOf(Fixtures.food, Fixtures.transport, Fixtures.income))

    /** 记录 listRange 调用次数：快照复用的核心断言是「带快照时不再查库」。 */
    private class CountingRepo(
        initial: List<LedgerTransaction>,
        categories: List<Category>,
    ) : FakeLedgerRepository(initial, categories) {
        var listRangeCalls: Int = 0
            private set
        override suspend fun listRange(
            fromMillis: Long,
            toMillis: Long,
            includeTransfers: Boolean,
        ): List<LedgerTransaction> {
            listRangeCalls += 1
            return super.listRange(fromMillis, toMillis, includeTransfers)
        }
    }

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
        // v1.1.9：分类卡不再取分类字典自带的 colorHex（字典里相邻分类常常同色系，
        // 且和商户/平台卡各自从第 1 色起步 ⇒ 同一屏多张饼图颜色必然撞车）。
        // 统一取 MetricPalette 的第 0 色。
        assertEquals(MetricPalette.at(0), result.slices.first().colorHex)
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

    // ------------------------------------------------------------ 消费平台结构

    @Test
    fun `platform share aggregates by platform id and maps display names`() = runBlocking {
        val r = FakeLedgerRepository(listOf(
            Fixtures.txn("a", -1_000, occurredAtMillis = t0).copy(platformId = "wechat"),
            Fixtures.txn("b", -2_000, occurredAtMillis = t0).copy(platformId = "wechat"),
            Fixtures.txn("c", -500, occurredAtMillis = t0).copy(platformId = "alipay"),
        ))
        val result = PlatformShareMetric().compute(range(), r) as MetricResult.Breakdown
        assertEquals(2, result.slices.size)
        assertEquals("微信", result.slices.first().label)
        assertEquals(3_000L, result.slices.first().minor)
    }

    @Test
    fun `platform share labels unknown platform and hints how many need fixing`() = runBlocking {
        val r = FakeLedgerRepository(listOf(Fixtures.txn("a", -100, occurredAtMillis = t0)))
        val result = PlatformShareMetric().compute(range(), r) as MetricResult.Breakdown
        // 默认 platformId = unknown -> 展示名「未知」，并在副标题提示待补笔数
        assertEquals("未知", result.slices.first().label)
        assertTrue(result.subtitle!!.contains("1"), "应提示有 1 笔平台未知，实际 ${result.subtitle}")
    }

    // ------------------------------------------------------------ 配色收口（v1.1.9）

    @Test
    fun `every breakdown card gives its leading slices distinct colors`() = runBlocking {
        // 回归钉子。v1.1.9 之前：
        // - 分类卡取分类字典自带的 colorHex —— 字典里相邻分类常常是同一个色系；
        // - 商户卡、平台卡各写一份 palette 字面量，三份重复实现且会各自漂移。
        // 结果：同一屏里多张饼图的第 1 片撞色，用户分不清哪片属于哪张卡。
        // 现在三张卡一律走 MetricPalette：前 N 片互不相同，且「第 i 名 = 调色板第 i 色」。
        val n = MetricPalette.COLORS.size
        // 故意给所有分类同一个 colorHex —— 一旦实现回退到 cat?.colorHex，下面必然撞色
        val categories = (0 until n).map { i -> Category("cat_$i", "分类$i", "receipt", "#708786") }
        val catTxns = (0 until n).map { i ->
            Fixtures.txn("c$i", -(100L * (n - i)), categoryId = "cat_$i", occurredAtMillis = t0)
        }
        val merchantTxns = (0 until n).map { i ->
            Fixtures.txn("m$i", -(100L * (n - i)), counterparty = "商户$i", occurredAtMillis = t0)
        }
        val platformTxns = (0 until n).map { i ->
            Fixtures.txn("p$i", -(100L * (n - i)), occurredAtMillis = t0).copy(platformId = "plat_$i")
        }

        val cards = listOf(
            "分类占比" to CategoryShareMetric().compute(
                range(), FakeLedgerRepository(catTxns, categories),
            ) as MetricResult.Breakdown,
            "商户排行" to MerchantTopMetric(topN = n).compute(
                range(), FakeLedgerRepository(merchantTxns),
            ) as MetricResult.Breakdown,
            "消费平台结构" to PlatformShareMetric(topN = n).compute(
                range(), FakeLedgerRepository(platformTxns),
            ) as MetricResult.Breakdown,
        )

        cards.forEach { (name, result) ->
            val colors = result.slices.take(n).map { it.colorHex }
            assertEquals(n, colors.size, "$name 应给出 $n 片，实际 ${colors.size}")
            assertEquals(
                colors.size, colors.distinct().size,
                "$name 的前 $n 片颜色必须互不相同，实际 $colors",
            )
            // 第 i 名固定取调色板第 i 色：顺序稳定，不随分类字典内容漂移
            colors.forEachIndexed { i, color ->
                assertEquals(MetricPalette.at(i), color, "$name 第 $i 片应取 MetricPalette.at($i)")
            }
        }
    }

    @Test
    fun `palette never repeats a colour across a wide index range`() {
        // 上面的用例只把三张卡覆盖到「基础色环长度」为止，恰好漏掉了回绕区间 ——
        // 而分类卡逐片出图、无 topN 截断，分类数由用户增删决定、上不封顶。
        // 所以这里直接对 at() 本身下钉子：任意下标都必须给出互不相同的合法色值。
        val n = 64
        val colors = (0 until n).map { MetricPalette.at(it) }
        assertEquals(n, colors.distinct().size, "at(0..${n - 1}) 必须两两不同，实际 $colors")
        colors.forEachIndexed { i, c ->
            assertTrue(
                Regex("#[0-9A-F]{6}").matches(c),
                "at($i) 必须是 #RRGGBB 形式的大写十六进制，实际 $c",
            )
        }
    }

    @Test
    fun `category share keeps colours distinct when categories outnumber the base palette`() = runBlocking {
        // QA 独立验证发现的残余缺陷：出厂种子自带 10 个支出分类（DefaultSeed.categories()），
        // 而基础色环只有 8 色；分类卡又是逐片出图 ⇒ 第 9 片会与第 1 片撞色，
        // 用户又看到「两个分类一个颜色」—— 也就是本次要修的那个最初毛病。
        val n = MetricPalette.COLORS.size + 6
        // 分类字典故意全给同一个色：一旦实现回退成取 cat?.colorHex，下面必然撞色
        val categories = (0 until n).map { i -> Category("cat_$i", "分类$i", "receipt", "#D95F5F") }
        val txns = (0 until n).map { i ->
            Fixtures.txn("c$i", -(100L * (n - i)), categoryId = "cat_$i", occurredAtMillis = t0)
        }
        val card = CategoryShareMetric().compute(
            range(), FakeLedgerRepository(txns, categories),
        ) as MetricResult.Breakdown

        assertEquals(n, card.slices.size, "分类卡不截断，应给出全部 $n 片")
        val colors = card.slices.map { it.colorHex }
        assertEquals(
            n, colors.distinct().size,
            "分类数（$n）超出基础色环（${MetricPalette.COLORS.size}）时仍不得撞色，实际 $colors",
        )
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

    // ---------------- 月末 999ms 边界（月度口径统一到 TimeRange.monthOf）----------------

    private val zone: java.time.ZoneId get() = java.time.ZoneId.systemDefault()

    private fun millis(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0, nano: Int = 0): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi, s, nano).atZone(zone).toInstant().toEpochMilli()

    /** 固定时钟注入，让「当前月右端 = now」的口径可以被确定性断言（不依赖测试真实运行时刻）。 */
    private fun trendAt(months: Int, now: Long) = MonthlyTrendMetric(months = months, nowMillis = { now })

    @Test
    fun `a transaction in the last 999ms of a month counts into that month`() = runBlocking {
        // 2026-09-30T23:59:59.500：旧实现自算右端 23:59:59.000 会漏掉这条（回归钉子）
        val r = FakeLedgerRepository(
            listOf(Fixtures.txn("a", -1_000, occurredAtMillis = millis(2026, 9, 30, 23, 59, 59, 500_000_000))),
        )
        val result = trendAt(months = 2, now = millis(2026, 10, 15, 12))
            .compute(TimeRange(millis(2026, 10, 1), millis(2026, 10, 15, 12)), r) as MetricResult.Trend
        assertEquals(2, result.points.size)
        assertEquals(1_000L, result.points.first().valueMinor, "9 月柱必须计入 23:59:59.500 的流水")
        assertEquals(0L, result.points.last().valueMinor)
    }

    @Test
    fun `the first millisecond of the next month does not leak into the previous month`() = runBlocking {
        val r = FakeLedgerRepository(
            listOf(Fixtures.txn("a", -1_000, occurredAtMillis = millis(2026, 10, 1))),
        )
        val result = trendAt(months = 2, now = millis(2026, 10, 15, 12))
            .compute(TimeRange(millis(2026, 10, 1), millis(2026, 10, 15, 12)), r) as MetricResult.Trend
        assertEquals(0L, result.points.first().valueMinor, "10/1 00:00:00.000 不得计入 9 月柱")
        assertEquals(1_000L, result.points.last().valueMinor)
    }

    @Test
    fun `leap february boundaries are both counted`() = runBlocking {
        val r = FakeLedgerRepository(
            listOf(
                Fixtures.txn("a", -1_000, occurredAtMillis = millis(2028, 2, 28, 23, 59, 59, 999_000_000)),
                Fixtures.txn("b", -2_000, occurredAtMillis = millis(2028, 2, 29, 23, 59, 59, 500_000_000)),
            ),
        )
        val result = trendAt(months = 2, now = millis(2028, 3, 15, 12))
            .compute(TimeRange(millis(2028, 3, 1), millis(2028, 3, 15, 12)), r) as MetricResult.Trend
        assertEquals(2, result.points.size)
        assertEquals(3_000L, result.points.first().valueMinor, "闰年 2 月最后 1ms 内的两条都必须计入")
        assertEquals(0L, result.points.last().valueMinor)
    }

    @Test
    fun `the current month bar excludes transactions dated after now`() = runBlocking {
        val now = millis(2026, 10, 15, 12)
        val r = FakeLedgerRepository(
            listOf(
                Fixtures.txn("a", -1_000, occurredAtMillis = now),
                Fixtures.txn("future", -5_000, occurredAtMillis = now + 3_600_000L),
            ),
        )
        val result = trendAt(months = 1, now = now)
            .compute(TimeRange(now - 30L * 24 * 3600_000, now), r) as MetricResult.Trend
        assertEquals(1, result.points.size)
        assertEquals(1_000L, result.points.single().valueMinor, "当前月柱右端 = now，未来日期的流水不得计入")
    }

    // ------------------------------------------------------------ 快照复用（MetricSnapshot）

    @Test
    fun `snapshot reuse means listRange is never queried`() = runBlocking {
        val r = range(t0, t0 + day)
        val txn = Fixtures.txn("e1", -500, categoryId = "cat_food", occurredAtMillis = t0)
        val snap = MetricSnapshot(
            range = r,
            txns = listOf(txn),
            categories = listOf(Fixtures.food),
        )
        val repo = CountingRepo(listOf(txn), listOf(Fixtures.food))
        val result = CategoryShareMetric().compute(r, repo, snap) as MetricResult.Breakdown
        assertEquals(0, repo.listRangeCalls, "带快照且窗口一致时绝不能再查 listRange")
        assertEquals(500L, result.totalMinor, "切片数据必须来自快照")
        assertEquals("餐饮", result.slices.single().label, "分类字典同样应来自快照")
    }

    @Test
    fun `null snapshot falls back to repo query`() = runBlocking {
        val r = range(t0, t0 + day)
        val repo = CountingRepo(listOf(Fixtures.txn("m1", -300, occurredAtMillis = t0)), emptyList())
        val result = MerchantTopMetric().compute(r, repo, snapshot = null) as MetricResult.Breakdown
        assertEquals(1, repo.listRangeCalls, "无快照必须回退自查 listRange")
        assertEquals(300L, result.totalMinor)
        assertEquals("某商户", result.slices.single().key)
    }

    @Test
    fun `wide window buckets three months in one query including refunds`() = runBlocking {
        val now = millis(2026, 3, 15)
        val r = TimeRange(millis(2026, 3, 1), now)
        val repo = CountingRepo(
            listOf(
                Fixtures.txn("jan", -10_000, occurredAtMillis = millis(2026, 1, 10)),
                Fixtures.txn("feb", -5_000, occurredAtMillis = millis(2026, 2, 5)),
                Fixtures.txn("feb-refund", 2_000, type = TxnType.REFUND, occurredAtMillis = millis(2026, 2, 20)),
                Fixtures.txn("mar", -3_000, occurredAtMillis = millis(2026, 3, 3)),
            ),
            emptyList(),
        )
        val result = trendAt(months = 3, now = now).compute(r, repo) as MetricResult.Trend
        assertEquals(1, repo.listRangeCalls, "整段趋势必须只查一次宽窗（旧实现逐月 6 次查询）")
        assertEquals(
            listOf(10_000L, 3_000L, 3_000L),
            result.points.map { it.valueMinor },
            "分桶口径 = 净支出（毛支出 − 退款），退款必须参与所在月的冲抵",
        )
        assertEquals(listOf("1月", "2月", "3月"), result.points.map { it.label })
    }

    @Test
    fun `snapshot containing refund nets category slice to gross minus refund`() = runBlocking {
        val r = range(t0, t0 + day)
        val txns = listOf(
            Fixtures.txn("e1", -10_000, categoryId = "cat_food", occurredAtMillis = t0),
            Fixtures.txn("r1", 4_000, categoryId = "cat_food", type = TxnType.REFUND, occurredAtMillis = t0),
        )
        val snap = MetricSnapshot(range = r, txns = txns, categories = listOf(Fixtures.food))
        val repo = CountingRepo(txns, listOf(Fixtures.food))
        val result = CategoryShareMetric().compute(r, repo, snap) as MetricResult.Breakdown
        assertEquals(1, result.slices.size)
        assertEquals(
            6_000L,
            result.slices.single().minor,
            "快照含 REFUND 时分类切片 = 毛支出 − 退款（口径修正：退款冲抵开始生效）",
        )
    }

    @Test
    fun `stale snapshot with mismatched range falls back to repo query`() = runBlocking {
        // P3-1 护栏：resolveTxns 的防御分支（takeIf { snapshot.range == range }）。
        // 快照装着**另一个窗口**的数据 + 失配的 range：实现必须识别失配并回退自查，
        // 绝不能把别的窗口的数据当成本次结果 —— 窗口串味（多算/漏算整月）比多查一次库严重得多。
        val r = range(t0, t0 + day)
        val stale = MetricSnapshot(
            range = TimeRange(t0 - day, t0), // 与查询窗口 r 不一致
            txns = listOf(Fixtures.txn("stale", -9_999, categoryId = "cat_food", occurredAtMillis = t0 - 1)),
            categories = listOf(Fixtures.food),
        )
        val repo = CountingRepo(
            listOf(Fixtures.txn("fresh", -500, categoryId = "cat_food", occurredAtMillis = t0)),
            listOf(Fixtures.food),
        )
        val result = CategoryShareMetric().compute(r, repo, stale) as MetricResult.Breakdown
        assertEquals(1, repo.listRangeCalls, "快照窗口失配必须回退自查 listRange")
        assertEquals(
            500L,
            result.totalMinor,
            "结果必须来自仓储当前窗口，而不是失配快照（若误用快照会得到 9_999）",
        )
    }

    @Test
    fun `transfers pulled into the wide window never leak into monthly buckets`() = runBlocking {        // P3-2 护栏：MonthlyTrend 宽窗查询带 includeTransfers=true（为让退款参与冲抵），
        // 内部划转因此也会被拉进内存 —— 钉死它不进任何月份桶、不污染柱值（口径泄漏到全链路）。
        val now = millis(2026, 5, 15, 12)
        val r = TimeRange(millis(2026, 5, 1), now)
        val repo = CountingRepo(
            listOf(
                Fixtures.txn("may", -4_000, occurredAtMillis = millis(2026, 5, 3)),
                Fixtures.txn("transfer", -50_000, type = TxnType.TRANSFER, occurredAtMillis = millis(2026, 5, 7)),
            ),
            emptyList(),
        )
        val result = trendAt(months = 1, now = now).compute(r, repo) as MetricResult.Trend
        assertEquals(
            4_000L,
            result.points.single().valueMinor,
            "TRANSFER 被宽窗拉进内存后不得计入月份柱（若泄漏会得到 54_000）",
        )
    }

    // ------------------------------------------------------------ 收入 · 结余（v8：时薪版本口径）

    // 收入的唯一真源是时薪版本（WageLedger），**不是** INCOME 流水合计。
    // 测试窗口固定为 2023-12 整月：`incomeForRange` 只累加「月首落在区间内」的月份，
    // 所以必须用月对齐区间，否则收入恒为 0（那不是 bug，是"收入按月应计、不按天摊分"）。
    private val salary12k = WageProfile(monthlyNetSalaryMinor = 1_200_000L, payMonthsPerYear = 12)

    /** 2023-12 整月区间（右端 = 该月最后一毫秒，锚定在 2024-01 之后，稳定为"过去月"）。 */
    private fun monthRange(): TimeRange =
        TimeRange.monthOf(java.time.YearMonth.of(2023, 12), millis(2024, 1, 15, 12))

    /**
     * 一个**用户已确认**的版本。
     *
     * ⚠️ `nowMillis` 必须给真实时间戳：`0` 是「迁移预填、未确认」的指纹
     * （见 `WageLedger.isMigrationSeeded`），用了它会让副标题走到"请确认"分支，
     * 与"有版本 ⇒ 正常口径说明"的断言互相打架。
     */
    private fun versionAt(month: String = "2023-12", profile: WageProfile = salary12k): WageVersion =
        WageVersion.of(month, profile, nowMillis = 1_700_000_000_000L)

    /** 注入版本列表的卡片（生产侧签名是 `() -> List<WageVersion>`，无默认值）。 */
    private fun cardWith(vararg versions: WageVersion): IncomeBalanceMetric =
        IncomeBalanceMetric { versions.toList() }

    @Test
    fun `balance is negative and income is zero when no wage version exists`() = runBlocking {
        // 无版本 ⇒ 收入按 ¥0 计（绝不回落到流水求和来"凑一个数"），并显式提示未设置时薪。
        val r = monthRange()
        val repo = repo(Fixtures.txn("e1", -50_000, occurredAtMillis = millis(2023, 12, 5)))
        val card = cardWith().compute(r, repo) as MetricResult.Scalar

        assertEquals(-50_000L, card.valueMinor, "只有支出 ⇒ 结余为负、收入 0")
        assertEquals("0", card.stats[0].text, "收入行必须是 ¥0")
        assertEquals(MetricTone.NEUTRAL, card.tone, "入不敷出不得涂成收入色")
        assertTrue(
            card.subtitle!!.contains("未设置时薪"),
            "无版本时必须显式提示去设置时薪，实得：${card.subtitle}",
        )
    }

    @Test
    fun `balance uses the wage version and never sums income transactions`() = runBlocking {
        // 反向钉子：账内塞一笔 999_999 分的 INCOME 流水，收入行**必须**仍等于版本值。
        // 若有人把实现改回 `actualReceivedMinor(txns)`，这条会立刻红。
        val r = monthRange()
        val repo = repo(
            Fixtures.txn("i1", 999_999, type = TxnType.INCOME, occurredAtMillis = millis(2023, 12, 10)),
            Fixtures.txn("e1", -135_000, occurredAtMillis = millis(2023, 12, 10)),
        )
        val card = cardWith(versionAt()).compute(r, repo) as MetricResult.Scalar

        assertEquals("12000", card.stats[0].text, "收入 = 时薪版本值 12000 元（不是流水合计 9999.99）")
        assertEquals(1_200_000L - 135_000L, card.valueMinor, "结余 = 版本收入 − 净支出")
        assertEquals(MetricTone.INCOME, card.tone)
        assertTrue(
            card.subtitle!!.contains("时薪推算收入"),
            "有版本时副标题必须说明口径来源，实得：${card.subtitle}",
        )
    }

    @Test
    fun `refund offsets the expense once and never twice`() = runBlocking {
        // 口径钉子：若写成「收入 − 毛支出 − 退款」会把退款扣两次 ⇒ 1_045_000；
        // 正确口径是「收入 − 净支出(已扣退款)」⇒ 1_085_000。
        val r = monthRange()
        val repo = repo(
            Fixtures.txn("e1", -135_000, occurredAtMillis = millis(2023, 12, 10)),
            Fixtures.txn("r1", 20_000, type = TxnType.REFUND, occurredAtMillis = millis(2023, 12, 11)),
        )
        val card = cardWith(versionAt()).compute(r, repo) as MetricResult.Scalar

        assertEquals(115_000L, ExpenseMath.netExpenseMinor(listOf(
            Fixtures.txn("e1", -135_000, occurredAtMillis = millis(2023, 12, 10)),
            Fixtures.txn("r1", 20_000, type = TxnType.REFUND, occurredAtMillis = millis(2023, 12, 11)),
        )), "净支出 1150（退款已冲抵一次）")
        assertEquals(
            1_200_000L - 115_000L,
            card.valueMinor,
            "结余 = 12000 − 1150 = 10850（不是 12000 − 1350 − 200 = 10450）",
        )
        assertEquals("1150", card.stats[1].text, "支出行是净支出 1150 元")
        assertEquals(MetricTone.INCOME, card.tone)
    }

    @Test
    fun `income balance card reuses the snapshot instead of querying again`() = runBlocking {
        val r = monthRange()
        val snap = MetricSnapshot(
            range = r,
            txns = listOf(Fixtures.txn("e1", -20_000, occurredAtMillis = millis(2023, 12, 10))),
        )
        val repo = CountingRepo(emptyList(), emptyList())
        val card = cardWith(versionAt()).compute(r, repo, snap) as MetricResult.Scalar

        assertEquals(0, repo.listRangeCalls, "带快照时不得再查库")
        assertEquals(1_200_000L - 20_000L, card.valueMinor, "结余 = 12000 − 200")
        assertEquals(MetricTone.INCOME, card.tone, "有结余 ⇒ 收入语义色")
    }

    @Test
    fun `balance card exposes income expense and balance as three labelled rows`() = runBlocking {
        // 需求钉子（v1.1.9）：卡片必须同时给出「收入 / 支出 / 结余」三项，且按因果顺序排列
        // （先进钱 → 再花钱 → 最后才是结余）。任何一项缺失、或支出行改用**毛支出**，
        // 用户读到的结余都会和账本对不上。
        val r = monthRange()
        val repo = repo(
            Fixtures.txn("e1", -135_000, occurredAtMillis = millis(2023, 12, 10)),
            Fixtures.txn("r1", 20_000, type = TxnType.REFUND, occurredAtMillis = millis(2023, 12, 11)),
        )
        val card = cardWith(versionAt()).compute(r, repo) as MetricResult.Scalar

        assertEquals(
            "支出与结余",
            card.title,
            "标题必须是「支出与结余」（原名「收入 · 结余」把支出藏进了副标题的一句话里）",
        )
        assertEquals(
            listOf("收入", "支出", "结余"),
            card.stats.map { it.label },
            "三行必须齐全，且按 收入 → 支出 → 结余 排列",
        )
        assertEquals("12000", card.stats[0].text, "收入行 = 时薪版本 12000 元")
        // 支出行给的是**净支出**（毛支出 1350 − 退款 200 = 1150），不是毛支出 1350。
        assertEquals("1150", card.stats[1].text, "支出行是净支出 1150 元（退款已冲抵一次）")
        assertEquals("10850", card.stats[2].text, "结余行 = 12000 − 1150 = 10850 元")
        assertEquals(MetricTone.INCOME, card.stats[0].tone, "收入行用收入色")
        assertEquals(MetricTone.EXPENSE, card.stats[1].tone, "支出行用支出色")
        assertEquals(MetricTone.INCOME, card.stats[2].tone, "正结余用收入色")
    }

    @Test
    fun `subtitle flags a migration seeded wage as pending confirmation`() = runBlocking {
        // 迁移预填（createdAtMillis == 0）与"用户确认过"必须给不同副标题：
        // 预填值照常参与计算（不隐藏、不清零），但要让用户知道这个时薪是升级前带过来的、
        // 且 v8 口径变了（13 薪摊平），请他确认 —— 否则数字与升级前不同会被当成算错。
        val seeded = WageVersion(
            month = "2023-12",
            profile = salary12k,
            monthlyIncomeMinor = 1_200_000L,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )
        val card = cardWith(seeded).compute(monthRange(), repo(), null) as MetricResult.Scalar

        assertEquals("12000", card.stats[0].text, "预填值照常参与计算，不得清零")
        assertTrue(
            card.subtitle!!.contains("确认"),
            "升级预填必须提示用户确认，实得：${card.subtitle}",
        )
    }

    @Test
    fun `subtitle explains that a range before the first version counts no income`() = runBlocking {
        // 第 ④ 态（防御性）：versions 非空，但整个统计窗口早于首个版本 ⇒ 收入恒为 0（不回填）。
        // 生产上今日页只传本月窗口、发现页不渲染这张卡，所以当前不可达；
        // 留着是为了将来有人给这卡传历史月窗口时，不会显示"结余 = 收入 − 支出"这种误导性口径。
        val card = cardWith(versionAt(month = "2026-01"))
            .compute(monthRange(), repo(), null) as MetricResult.Scalar

        assertEquals("0", card.stats[0].text, "收入必须是 0")
        assertEquals("早于首个时薪版本，不计收入", card.subtitle)
        assertEquals(0L, card.valueMinor, "没有收入记录时，结余 = −净支出（此处无支出 ⇒ 0）")
    }

    @Test
    fun `month aligned range counts exactly one month while a mid month range counts none`() = runBlocking {
        // 收入是**月度应计值，不按天摊分**：3/15–4/14 只含 4/1 一个月首 ⇒ 一个月收入；
        // 3/15–3/20 不含任何月首 ⇒ 0。这条锁住 incomeForRange 的边界语义不被"帮用户摊分"改坏。
        val repo = repo()
        val versions = { listOf(versionAt(month = "2023-03")) }

        val crossing = IncomeBalanceMetric(versions).compute(
            TimeRange(millis(2023, 3, 15), millis(2023, 4, 14, 23, 59, 59)),
            repo,
        ) as MetricResult.Scalar
        val inside = IncomeBalanceMetric(versions).compute(
            TimeRange(millis(2023, 3, 15), millis(2023, 3, 20, 23, 59, 59)),
            repo,
        ) as MetricResult.Scalar

        assertEquals(1_200_000L, crossing.valueMinor, "3/15–4/14 只含 4/1 ⇒ 恰一个月收入")
        assertEquals(0L, inside.valueMinor, "3/15–3/20 不含月首 ⇒ 收入 0（不按天摊分）")
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
        // 时薪 75 元 -> 150 元 = 2 小时。主指标是**时间**（大字显示），金额退到次要文本。
        assertTrue(
            result.primaryText!!.contains("2.0"),
            "150 元 @75元/时 应为 2.0 小时，实际 ${result.primaryText}",
        )
        assertTrue(
            result.secondaryText!!.contains("150"),
            "折算金额应在次要文本里，实际 ${result.secondaryText}",
        )
    }

    @Test
    fun `time cost never produces NaN when hourly wage is zero`() = runBlocking {
        val idle = WageProfile(monthlyNetSalaryMinor = 0L, workDaysPerMonth = 0.0)
        val r = FakeLedgerRepository(listOf(Fixtures.txn("a", -1_000, occurredAtMillis = t0)))
        val result = TimeCostMetric { idle }.compute(range(), r) as MetricResult.Scalar
        assertEquals(1_000L, result.valueMinor)
        assertTrue(!result.primaryText!!.contains("NaN"), "时薪为 0 时不得出现 NaN")
        assertTrue(!result.primaryText!!.contains("Infinity"))
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
        assertEquals("platform_share", PlatformShareMetric.PLATFORM_ID)
        assertEquals("monthly_trend", MonthlyTrendMetric.TREND_ID)
        assertEquals("time_cost", TimeCostMetric.TIME_COST_ID)
    }
}
