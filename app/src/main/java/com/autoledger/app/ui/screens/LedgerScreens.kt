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
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.LedgerStore
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.txnExtras
import com.autoledger.core.model.MetricResult
import com.autoledger.feature.stats.BudgetCalculator
import kotlinx.coroutines.launch
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var isRefund by remember { mutableStateOf(false) }
    // 正在编辑（改商户名 / 备注）的流水；null = 关闭对话框。
    var editing by remember { mutableStateOf<LedgerTransaction?>(null) }

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
                        val yuanValue = amountText.toDoubleOrNull() ?: return@Button
                        val minor = com.autoledger.core.model.Money.fromYuanDouble(kotlin.math.abs(yuanValue)).minor
                            .coerceAtLeast(1L)
                        scope.launch {
                            val source = container.captureRegistry.find("manual")
                                as? com.autoledger.feature.capture.manual.ManualCaptureSource
                            // 退款记为独立 REFUND 流水（正数、显式类型），不依赖订单
                            val envelope = source?.envelope(
                                amountMinor = if (isRefund) minor else -minor,
                                counterparty = merchant,
                                note = note,
                                explicitType = if (isRefund) com.autoledger.core.model.TxnType.REFUND else null,
                            ) ?: return@launch
                            runCatching { container.ingestPipeline.ingest(envelope) }
                            amountText = ""; merchant = ""; note = ""
                        }
                    },
                    Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                ) { Text(if (isRefund) "记下退款" else "保存并换算工时") }
            }
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = store::onQueryChange,
                        label = { Text("搜索商户 / 备注 / 金额") },
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
                    com.autoledger.core.model.platform.PlatformCatalog.all().forEach { entry ->
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

        if (state.loading) item { LoadingBox() }
        state.error?.let { item { ErrorPanel(it, store::load) } }

        val visible = store.visibleItems()
        if (!state.loading && visible.isEmpty()) {
            item { EmptyHint("还没有匹配的流水") }
        }

        items(visible, key = { it.id }) { txn ->
            val category = state.categories[txn.categoryId]
            Column(Modifier.padding(horizontal = 4.dp)) {
                TransactionRow(
                    txn = txn,
                    category = category,
                    // 点整行 → 弹「修正商户名 / 备注」对话框（自动抓取的商户名经常缺失）。
                    onClick = { editing = txn },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 原「点行展开分类」入口改到独立按钮，避免与编辑动作冲突。
                            IconButton(onClick = { expandedId = if (expandedId == txn.id) null else txn.id }) {
                                Icon(LedgerIcons.Category, "纠正分类")
                            }
                            IconButton(onClick = { store.delete(txn.id) }) {
                                Icon(LedgerIcons.Delete, "删除")
                            }
                        }
                    },
                )
                if (expandedId == txn.id) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.categories.values.forEach { cat ->
                            CategoryChip(cat) {
                                store.correctCategory(txn, cat)
                                expandedId = null
                            }
                        }
                        if (txn.type != TxnType.TRANSFER) {
                            FilterChip(
                                selected = false,
                                onClick = { store.markTransfer(txn); expandedId = null },
                                label = { Text("标为内部划转") },
                            )
                        }
                    }
                    TagEditor(
                        tags = txn.txnExtras.tags,
                        suggestions = store.allTags(),
                        onAdd = { store.setTags(txn, txn.txnExtras.tags + it) },
                        onRemove = { store.setTags(txn, txn.txnExtras.tags - it) },
                    )
                }
            }
        }
    }

    // 修正对话框：允许补全 / 修改商户名、消费平台与备注。
    editing?.let { txn ->
        var name by remember(txn.id) { mutableStateOf(txn.counterparty) }
        var noteText by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }
        var platformId by remember(txn.id) { mutableStateOf(txn.platformId) }
        // 自动识别且置信度不足 ⇒ 提示用户确认（用户手选后即变 USER 源，不再提示）
        val platformUncertain = txn.platformSource == com.autoledger.core.model.platform.PlatformSource.AUTO &&
            txn.platformConfidence < com.autoledger.core.model.platform.PlatformResolver.CONFIRM_THRESHOLD
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("修正这笔流水") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("商户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("备注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "消费平台",
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    PlatformPicker(selected = platformId, onSelect = { platformId = it })
                    if (platformUncertain) {
                        Text(
                            "自动识别不确定，请确认平台是否正确。",
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = LedgerPalette.Warning,
                        )
                    }
                    Text(
                        "自动抓取的商户名 / 消费平台常缺失或不准，在这里补上即可；改过之后不会再被自动识别覆盖。",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.updateCounterparty(txn, name, noteText, platformId)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }
}

/** 消费平台选择器：内置平台 + 未知，流式排列。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformPicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        com.autoledger.core.model.platform.PlatformCatalog.all().forEach { entry ->
            FilterChip(
                selected = entry.id == selected,
                onClick = { onSelect(entry.id) },
                label = { Text(entry.displayName) },
            )
        }
    }
}

/** 交易行内的标签编辑器：显示已有标签（点即移除）、输入新增、并给出常用标签建议。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(
    tags: List<String>,
    suggestions: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("加标签") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val tag = input.trim()
                if (tag.isNotEmpty()) {
                    onAdd(tag)
                    input = ""
                }
            }) { Icon(LedgerIcons.Add, "添加标签") }
        }
        if (tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag ->
                    FilterChip(selected = true, onClick = { onRemove(tag) }, label = { Text("$tag ✕") })
                }
            }
        }
        val candidates = suggestions.filter { it !in tags }.take(6)
        if (candidates.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                candidates.forEach { tag ->
                    FilterChip(selected = false, onClick = { onAdd(tag) }, label = { Text(tag) })
                }
            }
        }
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

    fun monthOf(t: com.autoledger.core.model.LedgerTransaction): LocalDate =
        Instant.ofEpochMilli(t.occurredAtMillis).atZone(zone).toLocalDate()

    // 月度口径在 composable 体内算好（LazyColumn 的 content lambda 非 composable，不能在其中 remember）
    val monthTxns = state.items.filter {
        monthOf(it).let { d -> d.year == selectedMonth.year && d.monthValue == selectedMonth.monthValue }
    }
    val monthIncome = monthTxns.filter { it.type == TxnType.INCOME }.sumOf { kotlin.math.abs(it.amountMinor) }
    // 退款冲抵支出（口径见 ExpenseMath）：支出显示净额，并在提示里说明冲抵了多少
    val monthRefund = ExpenseMath.refundMinor(monthTxns)
    val monthExpense = ExpenseMath.netExpenseMinor(monthTxns).coerceAtLeast(0L)
    // 付款总额（毛支出）：与支出/退款同源，均走 ExpenseMath，避免 UI 手写 sumOf 造成口径漂移
    val monthGross = ExpenseMath.grossExpenseMinor(monthTxns)
    val monthBudgets = BudgetCalculator.statuses(state.categories.values.toList(), monthTxns)

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
                            HeroTile("收入", "¥${monthIncome.yuan()}", accent = LedgerPalette.Positive)
                            HeroTile(
                                "支出", "¥${monthExpense.yuan()}",
                                hint = if (monthRefund > 0) "已扣退款 ¥${monthRefund.yuan()}" else null,
                                accent = LedgerPalette.Danger,
                            )
                            HeroTile("结余", "¥${(monthIncome - monthExpense).yuan()}", accent = LedgerPalette.InkDeep)
                        }
                        // 三格主行保持不变，另起一行展示「付款总额 / 退款总额」两个独立数值
                        PaymentRefundTiles(monthGross, monthRefund)
                    }
                }
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
                        TransactionRow(txn, state.categories[txn.categoryId])
                    }
                }
            }

            BillMode.YEAR -> {
                val yearTxns = state.items.filter { monthOf(it).year == selectedYear }
                val yIncome = yearTxns.filter { it.type == TxnType.INCOME }.sumOf { kotlin.math.abs(it.amountMinor) }
                val yRefund = ExpenseMath.refundMinor(yearTxns)
                val yExpense = ExpenseMath.netExpenseMinor(yearTxns).coerceAtLeast(0L)
                // 付款总额（毛支出）：与支出/退款同源，均走 ExpenseMath
                val yGross = ExpenseMath.grossExpenseMinor(yearTxns)

                item {
                    AppCard {
                        SectionTitle("年度总览", subtitle = "${selectedYear} 年")
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            HeroTile("收入", "¥${yIncome.yuan()}", accent = LedgerPalette.Positive)
                            HeroTile(
                                "支出", "¥${yExpense.yuan()}",
                                hint = if (yRefund > 0) "已扣退款 ¥${yRefund.yuan()}" else null,
                                accent = LedgerPalette.Danger,
                            )
                            HeroTile("结余", "¥${(yIncome - yExpense).yuan()}", accent = LedgerPalette.InkDeep)
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
                                slices = byCategory.map { (cat, minor) ->
                                    MetricResult.Breakdown.Slice(
                                        key = cat?.id ?: "unassigned",
                                        label = cat?.name ?: "未分类",
                                        minor = minor,
                                        colorHex = cat?.colorHex ?: "#708786",
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
                            Text("收入", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = LedgerPalette.Positive)
                            Text("支出", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = LedgerPalette.Danger)
                            Text("结余", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
                        }
                        (1..12).forEach { m ->
                            val list = yearTxns.filter { monthOf(it).monthValue == m }
                            val i = list.filter { it.type == TxnType.INCOME }.sumOf { kotlin.math.abs(it.amountMinor) }
                            val e = ExpenseMath.netExpenseMinor(list).coerceAtLeast(0L)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${m}月", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("¥${i.yuan()}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, color = LedgerPalette.Positive)
                                Text("¥${e.yuan()}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, color = LedgerPalette.Danger)
                                Text("¥${(i - e).yuan()}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 月/年汇总共用：「付款总额」与「退款总额」两个独立数值并排展示。 */
@Composable
private fun PaymentRefundTiles(grossMinor: Long, refundMinor: Long) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BreakdownTile("付款总额", "¥${grossMinor.yuan()}", LedgerPalette.Danger, Modifier.weight(1f))
        BreakdownTile("退款总额", "¥${refundMinor.yuan()}", LedgerPalette.Blue, Modifier.weight(1f))
    }
}
