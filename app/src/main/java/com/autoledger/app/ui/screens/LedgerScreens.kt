package com.autoledger.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoledger.app.di.AppContainer
import com.autoledger.app.ui.components.AppCard
import com.autoledger.app.ui.components.BreakdownTile
import com.autoledger.app.ui.components.BudgetCard
import com.autoledger.app.ui.components.CategoryChip
import com.autoledger.app.ui.components.EmptyHint
import com.autoledger.app.ui.components.ErrorPanel
import com.autoledger.app.ui.components.HeroTile
import com.autoledger.app.ui.components.LoadingBox
import com.autoledger.app.ui.components.MetricCard
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.TransactionRow
import com.autoledger.app.ui.components.TxnEditDialog
import com.autoledger.app.ui.components.TxnEditExtras
import com.autoledger.app.ui.components.TxnRowTrailing
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.LedgerStore
import com.autoledger.app.ui.stores.TxnEditRules
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.app.ui.theme.LedgerTone
import com.autoledger.app.ui.theme.toneColor
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.txnExtras
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformEntry
import com.autoledger.feature.stats.BudgetCalculator
import com.autoledger.feature.stats.MetricPalette
import com.autoledger.feature.stats.MonthlyTrendMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 记账 —— 10 秒补记 + 全量流水 + 分类纠正入口 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpensesScreen(container: AppContainer) {
    val store = remember(container) { LedgerStore(container) }
    LaunchedEffect(container) { store.load() }
    // B4：离开页面时释放订阅（实例级作用域），避免往返导航累积孤儿订阅（R1）。
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()

    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var isRefund by remember { mutableStateOf(false) }
    // 正在编辑（商户名 / 备注 / 消费平台 / 金额 / 日期）的流水；null = 关闭对话框。
    var editing by remember { mutableStateOf<LedgerTransaction?>(null) }
    // 点到「已并入其它流水」的行：用它弹说明，而不是默默无响应。
    var mergedHint by remember { mutableStateOf<String?>(null) }
    // v1.1.9：保存按钮的「进行中 / 结果提示」状态。旧实现把保存放在 rememberCoroutineScope()
    // 里且无反馈：协程可能半路被取消，用户既看不到失败也看不到成功，"点了没反应"。
    var saving by remember { mutableStateOf(false) }
    var saveHint by remember { mutableStateOf<String?>(null) }
    var saveHintOk by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("现在记一笔", "金额、商户、随手一句")
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    // 当前 Compose 版本从 foundation.text 暴露 KeyboardOptions。
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = merchant,
                        onValueChange = { merchant = it },
                        label = { Text("商户") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                FilterChip(
                    selected = isRefund,
                    onClick = { isRefund = !isRefund },
                    label = { Text(if (isRefund) "退款（冲抵支出）" else "支出") },
                    modifier = Modifier.padding(top = 10.dp),
                )
                Button(
                    onClick = {
                        // v1.1.9 根因修复：
                        // 1) 保存走 container.appScope（进程级、SupervisorJob），**不**用 rememberCoroutineScope()。
                        //    后者绑在组合上，页面一被切走 / 配置变更就 cancel，ingest 跑一半中断 ——
                        //    表现就是「点了保存没反应，偶尔才记上」。写库不该受页面生命周期管辖。
                        // 2) 金额先校验再发起：非法输入给明确提示，绝不静默 return@Button（旧实现那样按钮像坏了）。
                        // 3) 只有真的写成功了才清空输入框：失败时保留用户输入，避免辛苦打的字白丢。
                        val yuanValue = amountText.trim().toDoubleOrNull()
                        if (yuanValue == null || yuanValue <= 0.0) {
                            saveHintOk = false
                            saveHint = "金额要填大于 0 的数字"
                            return@Button
                        }
                        if (saving) return@Button
                        val minor = com.autoledger.core.model.Money.fromYuanDouble(kotlin.math.abs(yuanValue)).minor
                            .coerceAtLeast(1L)
                        saving = true
                        saveHint = null
                        container.appScope.launch {
                            val result = runCatching {
                                val source = container.captureRegistry
                                    .find(com.autoledger.core.model.capture.CaptureSourceIds.MANUAL)
                                    as? com.autoledger.feature.capture.manual.ManualCaptureSource
                                // 退款记为独立 REFUND 流水（正数、显式类型），不依赖订单
                                val envelope = source?.envelope(
                                    amountMinor = if (isRefund) minor else -minor,
                                    counterparty = merchant,
                                    note = note,
                                    explicitType = if (isRefund) com.autoledger.core.model.TxnType.REFUND else null,
                                ) ?: error("手动录入来源不可用")
                                container.ingestPipeline.ingest(envelope)
                            }
                            // 结果回填必须回到主线程：Compose 状态只能在 Main 上写。
                            withContext(Dispatchers.Main) {
                                saving = false
                                result.onSuccess {
                                    saveHintOk = true
                                    saveHint = "已记下"
                                    amountText = ""; merchant = ""; note = ""
                                }.onFailure {
                                    saveHintOk = false
                                    saveHint = "保存失败：${it.message ?: "未知错误"}"
                                }
                            }
                        }
                    },
                    Modifier.padding(top = 8.dp),
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                ) { Text(if (saving) "保存中…" else if (isRefund) "记下退款" else "保存并换算工时") }
                saveHint?.let { hint ->
                    Text(
                        hint,
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (saveHintOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = store::onQueryChange,
                        label = { Text("搜索商户 / 平台 / 备注 / 金额") },
                        singleLine = true,
                        leadingIcon = { Icon(LedgerIcons.Search, null) },
                        modifier = Modifier.weight(1f),
                    )
                }
                FilterChip(
                    selected = state.showTransfers,
                    onClick = store::toggleTransfers,
                    label = { Text("显示内部划转与退款") },
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 消费平台筛选：点即筛选（再点取消）；「未知」用于集中补全历史/识别失败的流水
                Text(
                    "消费平台",
                    Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 已停用平台不再出现在筛选条里；正被筛选的那个除外 ——
                    // 否则用户看到列表被过滤了、却找不到是哪个平台在起作用。
                    platformFilterChips(state.platformFilter).forEach { entry ->
                        FilterChip(
                            selected = state.platformFilter == entry.id,
                            onClick = { store.setPlatformFilter(entry.id) },
                            label = { Text(entry.displayName) },
                        )
                    }
                }
                val tags = store.allTags()
                if (tags.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = state.tagFilter == tag,
                                onClick = { store.setTagFilter(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
            }
        }

        // 流水检索区：**默认折叠**，不展示任何流水条目 —— 流水浏览统一由「账单」页承载。
        // 但搜索框 / 消费平台筛选 / 标签筛选目前只挂在这一块上，直接删掉会把这三项能力一起弄丢，
        // 故改为「有检索条件且命中结果时才展开」。
        // 「显示内部划转与退款」是**展示开关**而非检索条件：单独勾选不应展开列表，
        // 否则等于把全部流水搬回记账页，与「流水归账单」的初衷相悖。
        val hasCriteria =
            state.query.isNotBlank() || state.platformFilter != null || state.tagFilter != null

        if (!hasCriteria) {
            // 默认态：只给一句引导，不渲染任何流水条目
            item {
                AppCard {
                    Text(
                        "想找某笔流水？输入关键词，或选消费平台 / 标签，匹配到的会显示在这里。完整的流水列表在「账单」页。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // 有检索条件：出错 / 加载中 / 无命中 / 命中列表 —— 四个状态互斥，不得同帧并现
            state.error?.let { item { ErrorPanel(it, store::load) } }
            if (state.loading) item { LoadingBox() }
            val visible = store.visibleItems()
            // 加载中不判「无匹配」：此刻 items 还没到，否则会与上面的 LoadingBox 同帧并现，
            // 用户刚敲一个字就看到「没有匹配」，看起来像功能坏了。
            if (!state.loading && visible.isEmpty()) {
                // 无匹配：列表回到隐藏，只给一条无数据提示（不清空用户的检索条件，让他能改关键词重试）
                item { AppCard { EmptyHint("没有匹配的流水，换个关键词或清除筛选试试") } }
            } else if (visible.isNotEmpty()) {
                item {
                    AppCard {
                        SectionTitle("匹配结果", "共 ${visible.size} 笔")
                    }
                }
                items(visible, key = { it.id }) { txn ->
                    val category = state.categories[txn.categoryId]
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        TransactionRow(
                            txn = txn,
                            category = category,
                            // 点整行 → 弹「修正商户名 / 备注 / 消费平台 / 金额 / 日期」对话框
                            // （自动抓取的商户名经常缺失）。已并入其它流水时不给编辑入口。
                            onClick = if (TxnEditRules.canEdit(txn)) {
                                { editing = txn }
                            } else {
                                { mergedHint = txn.id }
                            },
                            trailing = {
                                TxnRowTrailing(
                                    store = store,
                                    txn = txn,
                                    expanded = expandedId == txn.id,
                                    onToggleExpanded = { expandedId = if (expandedId == txn.id) null else txn.id },
                                )
                            },
                        )
                        if (expandedId == txn.id) {
                            TxnEditExtras(
                                store = store,
                                txn = txn,
                                categories = state.categories.values,
                                onDone = { expandedId = null },
                            )
                        }
                    }
                }
            }
        }
    }

    // 已并入其它流水的行被点到：明确告知原因，而不是"点了没反应"
    mergedHint?.let {
        AlertDialog(
            onDismissRequest = { mergedHint = null },
            title = { Text("这笔不可修改") },
            text = { Text("该笔已并入其他流水，如需调整请修改并入后的那条记录。") },
            confirmButton = { TextButton(onClick = { mergedHint = null }) { Text("知道了") } },
        )
    }

    // 修正对话框：商户名 / 备注 / 消费平台 / 金额 / 日期（两页共用同一实现）
    editing?.let { txn ->
        // 合并组要按「当前打开的这条」加载：它是不是主记录、吸收了几条，只有查了才知道。
        val mergeGroup by store.mergeGroup.collectAsState()
        LaunchedEffect(txn.id) { store.loadMergeGroup(txn.id) }
        TxnEditDialog(
            store = store,
            txn = txn,
            onDismiss = { editing = null; store.clearMergeGroup() },
            mergedInto = mergeGroup,
            onUnmerge = { mergedId -> store.unmerge(mergedId, txn.id) },
        )
    }
}

/** 账单视图模式 */
enum class BillMode(val label: String) { MONTH("月度账单"), YEAR("年度账单") }

/**
 * 账单 —— 原「月结」升级：
 * 月度账单看当月明细；年度账单看全年收入/支出/结余、分类结构与逐月趋势。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthlyScreen(container: AppContainer) {
    val store = remember(container) { LedgerStore(container) }
    LaunchedEffect(container) { store.load() }
    // B4：离开页面时释放订阅（实例级作用域），避免往返导航累积孤儿订阅（R1）。
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()

    val zone = ZoneId.systemDefault()
    val months = remember {
        val now = LocalDate.now(zone)
        (0 until 12).map { now.minusMonths(it.toLong()) }
    }
    val years = remember { LocalDate.now(zone).let { now -> (0 until 5).map { now.year - it } } }

    var mode by remember { mutableStateOf(BillMode.MONTH) }
    var selectedMonth by remember { mutableStateOf(months.first()) }
    var selectedYear by remember { mutableStateOf(LocalDate.now(zone).year) }

    // 账单页与记账页共用同一套编辑实现（TxnEditDialog / TxnRowTrailing / TxnEditExtras），
    // 两页编辑能力完全一致，后续加字段只改一处。
    var editing by remember { mutableStateOf<com.autoledger.core.model.LedgerTransaction?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var mergedHint by remember { mutableStateOf<String?>(null) }

    fun monthOf(t: com.autoledger.core.model.LedgerTransaction): LocalDate =
        Instant.ofEpochMilli(t.occurredAtMillis).atZone(zone).toLocalDate()

    // 月度口径在 composable 体内算好（LazyColumn 的 content lambda 非 composable，不能在其中 remember）
    val monthTxns = state.items.filter {
        monthOf(it).let { d -> d.year == selectedMonth.year && d.monthValue == selectedMonth.monthValue }
    }
    val monthIncome = ExpenseMath.incomeMinor(monthTxns)
    // 退款冲抵支出（口径见 ExpenseMath）：支出显示净额，并在提示里说明冲抵了多少
    val monthRefund = ExpenseMath.refundMinor(monthTxns)
    val monthExpense = ExpenseMath.netExpenseMinor(monthTxns).coerceAtLeast(0L)
    // 付款总额（毛支出）：与支出/退款同源，均走 ExpenseMath，避免 UI 手写 sumOf 造成口径漂移
    val monthGross = ExpenseMath.grossExpenseMinor(monthTxns)
    val monthBudgets = BudgetCalculator.statuses(state.categories.values.toList(), monthTxns)

    // v1.1.9：月度趋势卡从今日页迁到账单页 —— 它是跨月宽窗（最近 N 个月），
    // 搁在「只看本月」的今日页里口径突兀；账单页本来就在按时间看账，是它的自然归属。
    // remember / LaunchedEffect 必须写在 composable 体内：LazyColumn 的 content lambda
    // 不是 composable 上下文，在里面 remember 会直接编译不过。
    var trend by remember { mutableStateOf<MetricResult?>(null) }
    LaunchedEffect(container) {
        val provider = container.metricRegistry.find(MonthlyTrendMetric.TREND_ID) ?: return@LaunchedEffect
        // 趋势卡忽略快照、自查宽窗，故 snapshot 传 null（见 MonthlyTrendMetric 的说明）
        trend = provider.compute(TimeRange.thisMonth(System.currentTimeMillis()), container.repository, null)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BillMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(m.label) },
                        )
                    }
                }
            }
        }

        when (mode) {
            BillMode.MONTH -> {
                item {
                    AppCard {
                        SectionTitle("本月总览", subtitle = DateTimeFormatter.ofPattern("yyyy 年 M 月").format(selectedMonth))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            // v1.1.6 收支颜色规范反转：收入红、支出绿（走 toneColor，深浅色自动切换）
                            HeroTile("收入", "¥${monthIncome.yuan()}", tone = LedgerTone.INCOME)
                            HeroTile(
                                "支出", "¥${monthExpense.yuan()}",
                                tone = LedgerTone.EXPENSE,
                                hint = if (monthRefund > 0) "已扣退款 ¥${monthRefund.yuan()}" else null,
                            )
                            // 结余是中性数值（既非收入也非支出）⇒ NEUTRAL
                            HeroTile("结余", "¥${(monthIncome - monthExpense).yuan()}", tone = LedgerTone.NEUTRAL)
                        }
                        // 三格主行保持不变，另起一行展示「付款总额 / 退款总额」两个独立数值
                        PaymentRefundTiles(monthGross, monthRefund)
                    }
                }
                // 月度趋势（v1.1.9 从今日页迁来）：算完才渲染，加载中不占位
                trend?.let { result -> item { MetricCard(result) } }
                if (monthBudgets.isNotEmpty()) {
                    item { BudgetCard(monthBudgets) }
                }
                item {
                    AppCard {
                        SectionTitle("选择月份", subtitle = DateTimeFormatter.ofPattern("yyyy 年 M 月").format(selectedMonth))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            months.forEach { month ->
                                FilterChip(
                                    selected = month == selectedMonth,
                                    onClick = { selectedMonth = month },
                                    label = { Text("${month.monthValue}月") },
                                    modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
                if (monthTxns.isEmpty()) {
                    item { AppCard { EmptyHint("这个月还没有记录") } }
                } else {
                    items(monthTxns.take(60), key = { it.id }) { txn ->
                        Column(Modifier.padding(horizontal = 4.dp)) {
                            TransactionRow(
                                txn = txn,
                                category = state.categories[txn.categoryId],
                                onClick = if (TxnEditRules.canEdit(txn)) {
                                    { editing = txn }
                                } else {
                                    { mergedHint = txn.id }
                                },
                                trailing = {
                                    TxnRowTrailing(
                                        store = store,
                                        txn = txn,
                                        expanded = expandedId == txn.id,
                                        onToggleExpanded = {
                                            expandedId = if (expandedId == txn.id) null else txn.id
                                        },
                                    )
                                },
                            )
                            if (expandedId == txn.id) {
                                TxnEditExtras(
                                    store = store,
                                    txn = txn,
                                    categories = state.categories.values,
                                    onDone = { expandedId = null },
                                )
                            }
                        }
                    }
                }
            }

            BillMode.YEAR -> {
                val yearTxns = state.items.filter { monthOf(it).year == selectedYear }
                val yIncome = ExpenseMath.incomeMinor(yearTxns)
                val yRefund = ExpenseMath.refundMinor(yearTxns)
                val yExpense = ExpenseMath.netExpenseMinor(yearTxns).coerceAtLeast(0L)
                // 付款总额（毛支出）：与支出/退款同源，均走 ExpenseMath
                val yGross = ExpenseMath.grossExpenseMinor(yearTxns)

                item {
                    AppCard {
                        SectionTitle("年度总览", subtitle = "${selectedYear} 年")
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            HeroTile("收入", "¥${yIncome.yuan()}", tone = LedgerTone.INCOME)
                            HeroTile(
                                "支出", "¥${yExpense.yuan()}",
                                tone = LedgerTone.EXPENSE,
                                hint = if (yRefund > 0) "已扣退款 ¥${yRefund.yuan()}" else null,
                            )
                            HeroTile("结余", "¥${(yIncome - yExpense).yuan()}", tone = LedgerTone.NEUTRAL)
                        }
                        // 三格主行保持不变，另起一行展示「付款总额 / 退款总额」两个独立数值
                        PaymentRefundTiles(yGross, yRefund)
                    }
                }
                item {
                    AppCard {
                        SectionTitle("选择年份")
                        Row(Modifier.fillMaxWidth()) {
                            years.forEach { year ->
                                FilterChip(
                                    selected = year == selectedYear,
                                    onClick = { selectedYear = year },
                                    label = { Text("${year}年") },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                    }
                }

                // 年度分类结构：复用统计卡的渲染（堆叠条 + TOP 榜）
                val byCategory = ExpenseMath.netBy(yearTxns) { it.categoryId.orEmpty() }
                    .map { (key, net) -> state.categories[key] to net }
                    .sortedByDescending { it.second }
                if (byCategory.isEmpty()) {
                    item { AppCard { EmptyHint("${selectedYear} 年还没有支出记录") } }
                } else {
                    item {
                        MetricCard(
                            MetricResult.Breakdown(
                                providerId = "year_category",
                                title = "年度分类结构",
                                subtitle = "全年共 ${yearTxns.size} 笔",
                                totalMinor = yExpense,
                                // v1.1.9：与分类占比卡一样按下标取统一调色板的色。
                                // 原来取 cat?.colorHex —— 分类字典里相邻分类常常是同一个色系
                                // （「人情往来 / 餐饮」撞色就是这么来的），且和本月其它占比卡
                                // 各自从第 1 色起步，同屏必然撞。byCategory 上方已按金额降序排好，
                                // 排名下标即配色下标，与 CategoryShareMetric 口径一致。
                                slices = byCategory.mapIndexed { i, (cat, minor) ->
                                    MetricResult.Breakdown.Slice(
                                        key = cat?.id ?: "unassigned",
                                        label = cat?.name ?: "未分类",
                                        minor = minor,
                                        colorHex = MetricPalette.at(i),
                                        iconKey = cat?.iconKey,
                                    )
                                },
                            )
                        )
                    }
                }

                // 逐月趋势表：12 个月的收入/支出/结余
                item {
                    AppCard {
                        SectionTitle("逐月趋势", subtitle = "${selectedYear} 年")
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("月份", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            // 收支语义色（v1.1.6）：收入红、支出绿；「结余」列保持中性（不是收支）。
                            // 用 toneColor 而非裸色常量：后者是浅色值，深色模式看不清。
                            Text("收入", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = toneColor(LedgerTone.INCOME))
                            Text("支出", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = toneColor(LedgerTone.EXPENSE))
                            Text("结余", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
                        }
                        (1..12).forEach { m ->
                            val list = yearTxns.filter { monthOf(it).monthValue == m }
                            val i = ExpenseMath.incomeMinor(list)
                            val e = ExpenseMath.netExpenseMinor(list).coerceAtLeast(0L)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${m}月", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("¥${i.yuan()}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, color = toneColor(LedgerTone.INCOME))
                                Text("¥${e.yuan()}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, color = toneColor(LedgerTone.EXPENSE))
                                Text("¥${(i - e).yuan()}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // 已并入其它流水的行被点到：明确告知原因
    mergedHint?.let {
        AlertDialog(
            onDismissRequest = { mergedHint = null },
            title = { Text("这笔不可修改") },
            text = { Text("该笔已并入其他流水，如需调整请修改并入后的那条记录。") },
            confirmButton = { TextButton(onClick = { mergedHint = null }) { Text("知道了") } },
        )
    }

    // 修正对话框：与记账页共用 TxnEditDialog（商户名 / 备注 / 消费平台 / 金额 / 日期）
    editing?.let { txn ->
        val mergeGroup by store.mergeGroup.collectAsState()
        LaunchedEffect(txn.id) { store.loadMergeGroup(txn.id) }
        TxnEditDialog(
            store = store,
            txn = txn,
            onDismiss = { editing = null; store.clearMergeGroup() },
            mergedInto = mergeGroup,
            onUnmerge = { mergedId -> store.unmerge(mergedId, txn.id) },
        )
    }
}

/** 月/年汇总共用：「付款总额」与「退款总额」两个独立数值并排展示。 */
@Composable
private fun PaymentRefundTiles(grossMinor: Long, refundMinor: Long) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 「付款总额」是支出语义 ⇒ EXPENSE（原先 Danger 红在浅底上对比度不足 4.5:1）；
        // 「退款总额」是冲抵项 ⇒ INFO（保持中性蓝，不参与收支红绿）。
        BreakdownTile("付款总额", "¥${grossMinor.yuan()}", LedgerTone.EXPENSE, Modifier.weight(1f))
        BreakdownTile("退款总额", "¥${refundMinor.yuan()}", LedgerTone.INFO, Modifier.weight(1f))
    }
}

/**
 * 平台筛选条的条目：**可指派**的平台 + **当前正在筛选的那个**（即便它已被停用）。
 *
 * 抽成纯函数是为了能直接单测这条容易漏的规则：用户把某个自定义平台停用后，
 * 若恰好还在按它筛选，筛选条上必须仍能看到它 ——
 * 否则会出现「列表被过滤了、却找不到是哪个平台在起作用」这种查不出原因的怪状态。
 *
 * 顺序天然是「内置在前、自定义在后」（`sortOrder`：内置 ≤ 90，自定义 1000 起，unknown 最后）。
 */
internal fun platformFilterChips(currentFilter: String?): List<PlatformEntry> {
    val selectable = PlatformCatalog.selectable()
    val current = currentFilter?.let { PlatformCatalog.find(it) }
    return if (current != null && current.archived && selectable.none { it.id == current.id }) {
        selectable + current
    } else {
        selectable
    }
}
