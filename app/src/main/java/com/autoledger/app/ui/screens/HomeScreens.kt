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
import androidx.compose.ui.Alignment
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
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.HomeStore
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.Money
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.formatYuan

/** 今日（驾驶舱）—— 参考仪表盘的首屏信息层级：三个指标 → 待办 → 最近流水 → 统计卡片 */
@Composable
fun DashboardScreen(container: AppContainer) {
    val store = remember(container) { HomeStore(container) }
    LaunchedEffect(container) { store.load() }
    // 离开首页时取消数据库订阅：HomeStore 用的是实例级作用域，
    // 不 close() 的话，导航往返会不断累积孤儿订阅与协程（R1）。
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()
    val nav = container.nav

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp, 6.dp, 10.dp, 6.dp),
            ) {
                AppCard {
                    if (state.loading) { LoadingBox(); return@AppCard }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        HeroTile("今日支出", "¥${state.todayMinor.yuan()}", hint = "换算 ${hoursOf(container, state.todayMinor)} 工作小时")
                        HeroTile(
                            "本月支出", "¥${state.monthMinor.yuan()}",
                            // 同时给出「付款总额」与「退款总额」两个独立数值，口径见 ExpenseMath。
                            hint = if (state.monthRefundMinor > 0) {
                                "付款 ¥${state.monthGrossMinor.yuan()} · 退款 ¥${state.monthRefundMinor.yuan()}"
                            } else null,
                            accent = LedgerPalette.InkDeep,
                        )
                        HeroTile("真实时薪", "¥${"%.1f".format(state.realHourly)}", hint = "每小时")
                    }
                }
                Text(
                    "今天也要算清楚——不是为了苛责每一笔钱，而是让每一小时更接近想要的生活。",
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.pendingReview > 0) {
            item {
                AppCard(onClick = { nav.navigate(com.autoledger.app.ui.nav.Destination.CAPTURE) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${state.pendingReview} 笔待确认", style = MaterialTheme.typography.titleMedium, color = LedgerPalette.Warning)
                            Text("自动抓到但没把握的流水都在采集箱里等你拍板", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("去处理", style = MaterialTheme.typography.labelMedium, color = LedgerPalette.Positive)
                    }
                }
            }
        }

        state.error?.let { error ->
            item { ErrorPanel(error) { store.load() } }
        }

        items(state.metrics, key = { it.providerId }) { metric -> MetricCard(metric as MetricResult) }

        item {
            AppCard {
                SectionTitle("刚刚花掉的时光", "最近 ${state.recent.size} 笔")
                if (state.recent.isEmpty()) {
                    EmptyHint("还没有流水，去「记账」tab 记第一笔吧")
                } else {
                    state.recent.forEach { txn ->
                        com.autoledger.app.ui.components.TransactionRow(
                            txn = txn,
                            category = state.categories[txn.categoryId],
                            workText = "${hoursOf(container, txn.amountMinor)} 小时",
                            // 这张卡片突出「花掉的时间」，右侧显示工时而非金额。
                            timeOnRight = true,
                        )
                    }
                }
            }
        }
    }
}

private fun hoursOf(container: AppContainer, amountMinor: Long): String {
    val minutes = container.settings.wage.value.minutesOfWork(amountMinor)
    return if (minutes >= 60) "%.1f".format(minutes / 60.0) else "%.0f".format(minutes) + "分"
}

/**
 * 时薪 —— 把「真实时薪」的完整算式摊开给用户看。
 * 参考仪表盘最有说服力的一点就是不藏公式：每一步的中间结果都展示出来，用户可以自己验算。
 */
@Composable
fun HourlyScreen(container: AppContainer) {
    var profile by remember { mutableStateOf(container.settings.wage.value) }
    LaunchedEffect(Unit) { /* 本地编辑状态，进入时取一次快照 */ }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HeroTile("真实时薪", "¥${"%.1f".format(profile.realHourly)}", "每小时")
                    HeroTile("名义时薪", "¥${"%.1f".format(profile.nominalHourly)}", "每小时", LedgerPalette.Muted)
                }
                Text(
                    "两者差 ¥${"%.1f".format(profile.nominalHourly - profile.realHourly)}/时，就是通勤、加班和上班开销悄悄吃掉的部分。",
                    Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            AppCard {
                SectionTitle("工作时间参数")
                NumberField("到手月薪（元）", profile.monthlyNetSalaryMinor / 100.0) { profile = profile.copy(monthlyNetSalaryMinor = Money.fromYuanDouble(it).minor) }
                NumberField("一年发薪月数", profile.payMonthsPerYear.toDouble()) { profile = profile.copy(payMonthsPerYear = it.toInt()) }
                NumberField("每月为工作花的钱（元）", profile.monthlyWorkCostMinor / 100.0) { profile = profile.copy(monthlyWorkCostMinor = Money.fromYuanDouble(it).minor) }
                NumberField("每月工作日", profile.workDaysPerMonth) { profile = profile.copy(workDaysPerMonth = it) }
                NumberField("每天在公司小时", profile.dailyOfficeHours) { profile = profile.copy(dailyOfficeHours = it) }
                NumberField("每天通勤分钟", profile.dailyCommuteMinutes.toDouble()) { profile = profile.copy(dailyCommuteMinutes = it.toInt()) }
                NumberField("每天加班小时", profile.dailyOvertimeHours) { profile = profile.copy(dailyOvertimeHours = it) }
                Button(
                    onClick = { container.settings.updateWage(profile) },
                    Modifier.padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                ) { Text("保存参数") }
            }
        }

        item {
            AppCard {
                SectionTitle("公式展开", "代入你现在的数字")
                FormulaLine(
                    "① 名义月收入", "月薪 × 发薪月数 ÷ 12",
                    "¥${Money(profile.monthlyNetSalaryMinor).formatYuan()} × ${profile.payMonthsPerYear} ÷ 12 = ¥${Money(profile.effectiveMonthlySalaryMinor).formatYuan()}",
                )
                FormulaLine(
                    "② 实际月收入", "名义月收入 − 每月工作成本",
                    "¥${Money(profile.effectiveMonthlySalaryMinor).formatYuan()} − ¥${Money(profile.monthlyWorkCostMinor).formatYuan()} = ¥${Money(profile.realMonthlyIncomeMinor).formatYuan()}",
                )
                FormulaLine(
                    "③ 每月工时", "工作日 × (在场 + 通勤÷60 + 加班)",
                    "%.2f × (%.2f + %.2f + %.2f) = %.2f 时".format(profile.workDaysPerMonth, profile.dailyOfficeHours, profile.dailyCommuteMinutes / 60.0, profile.dailyOvertimeHours, profile.monthlyWorkHours),
                )
                FormulaLine(
                    "④ 真实时薪", "实际月收入 ÷ 每月工时",
                    "¥${Money(profile.realMonthlyIncomeMinor).formatYuan()} ÷ %.2f = %.2f 元/时".format(profile.monthlyWorkHours, profile.realHourly),
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Double, onChange: (Double) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { raw -> raw.toDoubleOrNull()?.let(onChange) },
        label = { Text(label) },
        // 当前 Compose 版本从 foundation.text 暴露 KeyboardOptions。
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true,
    )
}

@Composable
private fun FormulaLine(step: String, formula: String, substituted: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(step, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formula, style = MaterialTheme.typography.bodyMedium)
        Text(substituted, style = MaterialTheme.typography.bodySmall, color = LedgerPalette.Positive)
    }
}
