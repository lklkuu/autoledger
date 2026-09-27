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
import com.autoledger.app.ui.components.ProgressLine
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.TransactionRow
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.FreedomStore
import com.autoledger.app.ui.stores.InsightsStore
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.Money

/** 自由 —— 自由基金目标与安全垫 */
@Composable
fun FreedomScreen(container: AppContainer) {
    val store = remember(container) { FreedomStore(container) }
    LaunchedEffect(container) { store.load() }
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()

    var target by remember(state.targetMinor) { mutableStateOf(state.targetMinor.yuan()) }
    var cushion by remember(state.cushionMinor) { mutableStateOf(state.cushionMinor.yuan()) }
    var current by remember(state.currentMinor) { mutableStateOf(state.currentMinor.yuan()) }

    val targetMinor = Money.fromYuanDouble(target.toDoubleOrNull() ?: 0.0).minor
    val currentMinor = Money.fromYuanDouble(current.toDoubleOrNull() ?: 0.0).minor
    val cushionMinor = Money.fromYuanDouble(cushion.toDoubleOrNull() ?: 0.0).minor
    val progress = if (targetMinor <= 0) 0f else (currentMinor.toDouble() / targetMinor).toFloat()
    val surplusMinor = state.monthlySurplusMinor
    val monthsLeft = if (surplusMinor > 0 && targetMinor > currentMinor) {
        (targetMinor - currentMinor).toDouble() / surplusMinor
    } else null
    val cushionMonths = if (state.monthlyExpenseMinor > 0) cushionMinor.toDouble() / state.monthlyExpenseMinor else 0.0

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("自由坐标", "攒到多少钱就不用勉强自己了")
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    HeroTile("已攒", "¥${currentMinor.yuan()}")
                    HeroTile("目标", "¥${targetMinor.yuan()}", accent = LedgerPalette.InkDeep)
                    HeroTile("安全垫", "¥${cushionMinor.yuan()}", hint = "约 ${
                        "%.1f".format(cushionMonths)
                    } 个月开销")
                }
                ProgressLine(
                    progress = progress,
                    label = "进度 ${"%.1f".format(progress * 100)}%",
                    targetLabel = "还差 ¥${(targetMinor - currentMinor).coerceAtLeast(0L).yuan()}",
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
                MoneyField("安全垫金额", cushion) { cushion = it }
                MoneyField("当前存款", current) { current = it }
                Button(
                    onClick = { store.saveGoal(targetMinor, cushionMinor, currentMinor) },
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

        item {
            AppCard {
                SectionTitle("今日小发现")
                val facts = state.facts
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

        items(state.metrics, key = { it.providerId }) { MetricCard(it) }

        item {
            AppCard {
                SectionTitle("最近记下的", "本月前几笔")
                // 直接复用 InsightsStore 自己算出的 facts，避免为这一张卡片再挂一个 HomeStore（R1）。
                val recent = state.facts.recent
                if (recent.isEmpty()) {
                    EmptyHint("还没有数据")
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
        Text(value, style = MaterialTheme.typography.labelMedium, color = LedgerPalette.Positive)
    }
}
