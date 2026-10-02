package com.autoledger.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoledger.app.di.AppContainer
import com.autoledger.app.ui.components.AppCard
import com.autoledger.app.ui.components.EmptyHint
import com.autoledger.app.ui.components.ErrorPanel
import com.autoledger.app.ui.components.HeroTile
import com.autoledger.app.ui.components.LoadingBox
import com.autoledger.app.ui.components.MetricCard
import com.autoledger.app.ui.components.MonthSelector
import com.autoledger.app.ui.components.ProgressLine
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.TransactionRow
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.FreedomStore
import com.autoledger.app.ui.stores.InsightsStore
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.FreedomMath
import com.autoledger.core.model.Money

/** 自由 —— 自由基金目标与「已攒」进度 */
@Composable
fun FreedomScreen(container: AppContainer) {
    val store = remember(container) { FreedomStore(container) }
    LaunchedEffect(container) { store.load() }
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()

    var target by remember(state.targetMinor) { mutableStateOf(state.targetMinor.yuan()) }
    var current by remember(state.currentMinor) { mutableStateOf(state.currentMinor.yuan()) }

    val targetMinor = Money.fromYuanDouble(target.toDoubleOrNull() ?: 0.0).minor
    // 存款用**输入框当前值**而不是已保存值：这样用户一边敲「累计已攒」就一边变（需求 3 的实时更新）。
    val currentMinor = Money.fromYuanDouble(current.toDoubleOrNull() ?: 0.0).minor
    // 两个「已攒」的口径都在 FreedomMath（纯函数、有单测），UI 不发明公式：
    //   当月已攒 = 到手月薪 − 当月支出（不含存款）
    //   累计已攒 = 使用月数 × 月薪 − 累计支出 + 存款
    // 刻意不夹断：结果为负说明这个月在吃老本，是真实且需要被看见的状态。
    val monthlySavedUpMinor = FreedomMath.monthlySavedUpMinor(
        monthlyNetSalaryMinor = state.monthlyNetSalaryMinor,
        monthlyExpenseMinor = state.monthlyExpenseMinor,
    )
    val cumulativeSavedUpMinor = FreedomMath.cumulativeSavedUpMinor(
        monthsUsed = state.monthsUsed,
        monthlyNetSalaryMinor = state.monthlyNetSalaryMinor,
        cumulativeExpenseMinor = state.cumulativeExpenseMinor,
        currentDepositMinor = currentMinor,
    )
    // 进度与「还差」都基于**累计已攒**：目标是一个长期总量，
    // 拿「当月」这个单月切片去比会让页面自相矛盾（显示累计 42000、进度却按 7500 算）。
    val progress = FreedomMath.progressOf(cumulativeSavedUpMinor, targetMinor)
    val surplusMinor = state.monthlySurplusMinor
    val monthsLeft = if (surplusMinor > 0 && targetMinor > cumulativeSavedUpMinor) {
        (targetMinor - cumulativeSavedUpMinor).toDouble() / surplusMinor
    } else null
    // 「开始使用的月份」= 账本最早一笔流水所在月份；账本为空时显示占位符而不是崩。
    val startLabel = state.startYearMonth?.let { FreedomMath.yearMonthLabel(it) } ?: "—"
    // 空账本时累计按 0 处理（FreedomMath 口径），这句提示要把它讲清楚，
    // 否则用户会以为「填了存款却显示 0」是 bug。
    val cumulativeHint = if (state.monthsUsed <= 0) {
        "账本暂无流水，累计按 0 处理"
    } else {
        "起始月 $startLabel 起 ${state.monthsUsed} 个月（按月薪估算）"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("自由坐标", "攒到多少钱就不用勉强自己了")
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    // 负值必须显示负号：Long.yuan() 默认会吞掉它，这里显式开 withSign。
                    HeroTile(
                        "当月已攒",
                        "¥${monthlySavedUpMinor.yuan(withSign = true)}",
                        hint = "= 到手月薪 − 当月支出",
                    )
                    HeroTile(
                        "累计已攒",
                        "¥${cumulativeSavedUpMinor.yuan(withSign = true)}",
                        hint = cumulativeHint,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    HeroTile("目标", "¥${targetMinor.yuan()}", accent = LedgerPalette.InkDeep)
                    HeroTile("起始月", startLabel, accent = LedgerPalette.InkDeep)
                }
                ProgressLine(
                    progress = progress,
                    label = "进度 ${"%.1f".format(progress * 100)}%",
                    // 差额只在**展示位**夹 0（见 FreedomMath.remainingMinor），累计本身不夹断。
                    targetLabel = "还差 ¥${FreedomMath.remainingMinor(cumulativeSavedUpMinor, targetMinor).yuan()}",
                )
                monthsLeft?.let {
                    Text(
                        "按本月结余 ¥${surplusMinor.yuan()} 计算，约 ${"%.1f".format(it)} 个月达成。",
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Text(
                    "当前月结余为负，先调结构：把「内部划转」排干净，再看看固定支出。",
                    Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LedgerPalette.Warning,
                )
            }
        }

        item {
            AppCard {
                SectionTitle("目标设置")
                MoneyField("目标金额", target) { target = it }
                MoneyField("当前存款", current) { current = it }
                Button(
                    onClick = { store.saveGoal(targetMinor, currentMinor) },
                    Modifier.padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                ) { Text("保存目标") }
            }
        }
    }
}

@Composable
private fun MoneyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("$label（元）") },
        // 当前 Compose 版本从 foundation.text 暴露 KeyboardOptions。
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

/** 发现 —— 消费里有意思的几个事实 */
@Composable
fun InsightsScreen(container: AppContainer) {
    val store = remember(container) { InsightsStore(container) }
    LaunchedEffect(container) { store.load() }
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        if (state.loading) item { LoadingBox() }
        state.error?.let { item { ErrorPanel(it, store::load) } }

        // 月份选择器：默认当前月 ⇒ 与「按月查看」上线前的行为一致。
        // 选中月只能经 store.selectMonth 修改 —— 它负责取消旧订阅再重订阅，
        // UI 若直接改状态会让新旧月份的 Flow 同时写 state，出现跳月/闪回。
        item {
            val selected = state.selectedMonth
            MonthSelector(
                months = state.availableMonths.ifEmpty { listOf(selected) },
                selected = selected,
                canGoPrev = state.canGoPrev,
                canGoNext = state.canGoNext,
                onSelect = store::selectMonth,
                onPrev = store::prevMonth,
                onNext = store::nextMonth,
            )
        }

        item {
            AppCard {
                val monthLabel = "${state.selectedMonth.monthValue}月"
                // 标题动态化：数据来自所选月份的整月区间，写死「今日」会名不副实
                SectionTitle("$monthLabel 小发现", "${state.selectedMonth.year} 年")
                val facts = state.facts
                if (facts.recordCount == 0) {
                    EmptyHint("$monthLabel 没有可统计的记录")
                } else {
                facts.largestTxn?.let { txn ->
                    FactLine("最大一笔", "¥${kotlin.math.abs(txn.amountMinor).yuan()} · ${txn.counterparty.ifBlank { "未知名交易" }}")
                }
                facts.topMerchant?.let { (name, minor) ->
                    FactLine("最常光顾", "$name 共 ¥${minor.yuan()}")
                }
                FactLine("工作日 vs 周末", "¥${facts.weekdayVsWeekend.first.yuan()} / ¥${facts.weekdayVsWeekend.second.yuan()}")
                FactLine("日均花销", "¥${facts.avgDailyMinor.yuan()}")
                FactLine("待补分类", "${facts.unclassifiedCount} 笔")
                }
            }
        }

        items(state.metrics, key = { it.providerId }) { MetricCard(it) }

        item {
            AppCard {
                val monthLabel = "${state.selectedMonth.monthValue}月"
                SectionTitle("最近记下的", "$monthLabel 前几笔")
                // 直接复用 InsightsStore 自己算出的 facts，避免为这一张卡片再挂一个 HomeStore（R1）。
                val recent = state.facts.recent
                if (recent.isEmpty()) {
                    // 用 recordCount 区分「该月真的没有记录」与「有记录但无可展示项」
                    EmptyHint(
                        if (state.facts.recordCount == 0) "$monthLabel 还没有记录" else "还没有数据",
                    )
                } else {
                    recent.forEach { TransactionRow(it, state.facts.categories[it.categoryId]) }
                }
            }
        }
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // v1.1.6：这行展示的是「最大一笔 / 最常光顾 / 日均花销」等**支出**口径数值 ⇒ 支出语义绿
        Text(value, style = MaterialTheme.typography.labelMedium, color = LedgerPalette.ExpenseGreen)
    }
}
