package com.autoledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.autoledger.app.ui.theme.colorOf
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.Category

/** 分 -> 元 的可读串 */
fun Long.yuan(withSign: Boolean = false): String {
    val negative = this < 0
    val v = kotlin.math.abs(this)
    val body = "${v / 100}" + if (v % 100 == 0L) "" else ".${(v % 100).toString().padStart(2, '0')}"
    return (if (negative && withSign) "-" else "") + body
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun HeroTile(label: String, value: String, hint: String? = null, accent: Color = LedgerPalette.Positive) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 汇总里的一个「细分数值」小块（付款总额 / 退款总额）。
 * 比 [HeroTile] 更紧凑，专门用于并排展示同一总额的两个组成部分。
 */
@Composable
fun BreakdownTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CategoryChip(category: Category?, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colorOf(category?.colorHex ?: "#708786").copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LedgerIcons.forCategory(category?.iconKey ?: "receipt"),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colorOf(category?.colorHex ?: "#708786"),
        )
        Text(
            category?.name ?: "未分类",
            Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colorOf(category?.colorHex ?: "#708786"),
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorPanel(message: String, onRetry: () -> Unit) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("加载失败了", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun EmptyHint(text: String, iconKey: String = "spark") {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(LedgerIcons.forCategory(iconKey), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** 一张统计卡片：Render 逻辑按 [MetricResult] 类型分发，新增维度类型时这里加一个分支即可 */
@Composable
fun MetricCard(result: MetricResult, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        when (result) {
            is MetricResult.Scalar -> {
                Text(result.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "¥${result.valueMinor.yuan()}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = LedgerPalette.PositiveStrong,
                )
                listOfNotNull(result.subtitle, result.secondaryText).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            is MetricResult.Breakdown -> {
                Text(result.title, style = MaterialTheme.typography.titleMedium)
                result.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (result.slices.isEmpty()) {
                    EmptyHint("暂无数据")
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp).height(12.dp)
                            .clip(RoundedCornerShape(999.dp)),
                    ) {
                        result.slices.forEach { slice ->
                            val weight = if (result.totalMinor == 0L) 1f else slice.minor.toFloat() / result.totalMinor
                            Box(
                                Modifier
                                    .weight(weight.coerceAtLeast(0.001f))
                                    .fillMaxHeight()
                                    .background(colorOf(slice.colorHex)),
                            )
                        }
                    }
                    result.slices.take(5).forEach { slice ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colorOf(slice.colorHex)))
                            Text(slice.label, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("¥${slice.minor.yuan()}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            is MetricResult.Trend -> {
                Text(result.title, style = MaterialTheme.typography.titleMedium)
                val max = result.points.maxOfOrNull { it.valueMinor } ?: 0L
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).height(110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    result.points.forEach { point ->
                        val ratio = if (max == 0L) 0f else point.valueMinor.toFloat() / max
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                Modifier
                                    .width(22.dp)
                                    .height((ratio * 80f).coerceAtLeast(2f).dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(if (point.valueMinor >= max) LedgerPalette.PositiveStrong else LedgerPalette.PositivePale),
                            )
                            Text(point.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单条流水行。
 *
 * 除了金额，右侧刻意显示「折算工时」——这是参考仪表盘的灵魂：
 * 让人对花出去的钱有**时间尺度上的痛感**。
 */
@Composable
fun TransactionRow(
    txn: LedgerTransaction,
    category: Category?,
    workText: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colorOf(category?.colorHex ?: "#708786").copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                LedgerIcons.forCategory(category?.iconKey ?: "receipt"),
                contentDescription = null,
                tint = colorOf(category?.colorHex ?: "#708786"),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                txn.counterparty.ifBlank { "未知名交易" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                buildList {
                    add(category?.name ?: "未分类")
                    workText?.let { add("≈ $it") }
                    if (txn.type == com.autoledger.core.model.TxnType.TRANSFER) add("已识别为内部划转")
                    if (txn.status == com.autoledger.core.model.TxnStatus.RAW) add("待确认")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            "¥${txn.amountMinor.yuan()}",
            style = MaterialTheme.typography.titleMedium,
            color = if (txn.amountMinor < 0) MaterialTheme.colorScheme.onSurface else LedgerPalette.Positive,
        )
        trailing?.invoke()
    }
}

@Composable
fun ProgressLine(
    progress: Float,
    label: String,
    targetLabel: String,
    color: Color = LedgerPalette.Positive,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(targetLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = LedgerPalette.PositivePale,
        )
    }
}
