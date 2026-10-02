package com.autoledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import java.util.Locale

/** 分 -> 元 的可读串 */
fun Long.yuan(withSign: Boolean = false): String {
    val negative = this < 0
    val v = kotlin.math.abs(this)
    val body = "${v / 100}" + if (v % 100 == 0L) "" else ".${(v % 100).toString().padStart(2, '0')}"
    return (if (negative && withSign) "-" else "") + body
}

/** 一元 = 100 分；一万元 = 1_000_000 分。 */
private const val FEN_PER_YUAN = 100L

/**
 * 趋势图节点内部的间距（金额 ↔ 柱体、柱体 ↔ 月份标签）。
 *
 * 高度预算（容器固定 110.dp，`verticalArrangement = Arrangement.Bottom` 从底部往上排）：
 * 金额 labelSmall ≈ 16.dp + 4 + 柱体 ≤64.dp + 4 + 月份 labelMedium ≈16.dp = **≤104.dp**，
 * 留 6.dp 余量；`coerceAtLeast(2f)` 保证 0 元月份的柱体仍有 2.dp 可见高度（不"消失"）。
 */
private val TREND_NODE_GAP = 4.dp

/** 柱体最大高度（ratio=1.0 时）。取值理由见 [TREND_NODE_GAP] 的高度预算。 */
private const val TREND_MAX_BAR_DP = 64f

/**
 * 「月度趋势」每个柱子上方的金额标签（纯函数 ⇒ 可 JVM 单测，不依赖 Compose）。
 *
 * 为什么不用 [yuan]：节点列宽只有屏宽/6（≈60dp），`¥1,234.00` 必然溢出，必须按量级压缩：
 * - `0` → `¥0`（**0 元月份也显式标注** —— 让用户看出哪些月没花钱，而不是留空白让人猜）；
 * - < 100 元 → 两位小数（分位要看得见）：`¥28.45`；
 * - 100 ~ 10_000 元 → 整数 + 千分位、不带小数：`¥1,000`、`¥9,280`；
 * - ≥ 10_000 元 → 折成「万」、一位小数：`¥1.2万`。
 *
 * ⚠️ 千分位分隔符**固定用 [Locale.US]**：项目里踩过「系统 locale 为阿拉伯语时数字被本地化、
 * 导致 `toBigDecimalOrNull()` 解析失败」的坑（见 `YuanFormatTest`），此处不能跟随系统 locale。
 *
 * 小数部分一律用**整数运算**拼装，不用浮点：避免 `24.45` 这类值在二进制浮点下的
 * 舍入抖动（`%.2f` 偶尔会印出 `24.44`）。
 *
 * 负数保留负号（趋势柱已 `coerceAtLeast(0)`，此处仍按可独立复用的格式化函数对待）：
 * 符号位置与全站其它金额一致（`¥` 在前，如 `InsightScreens` 的「已攒」）。
 *
 * 极值：`Long.MIN_VALUE` 取绝对值会溢出，故对它做**饱和夹取**到 `MAX_VALUE`；
 * 「万」档的舍入用**整数除法**（商 + 余数判进位）实现，不做可能二次溢出的加法
 * —— 保证任意 `Long` 输入都只产出一个负号（至多一个）、且小数部分非负。
 */
fun trendAmountLabel(valueMinor: Long): String {
    // Long.MIN_VALUE 取绝对值会溢出（Long 无对应正数），对它做**饱和夹取**到 MAX_VALUE。
    val absMinor = if (valueMinor == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(valueMinor)
    val sign = if (valueMinor < 0) "-" else ""
    return when {
        absMinor == 0L -> "¥0"
        // < 100 元：保留两位小数
        absMinor < 100L * FEN_PER_YUAN ->
            "¥$sign${absMinor / FEN_PER_YUAN}.${(absMinor % FEN_PER_YUAN).toString().padStart(2, '0')}"
        // 100 ~ 10_000 元：整数 + 千分位（Locale.US，不跟随系统）
        absMinor < 10_000L * FEN_PER_YUAN ->
            "¥$sign" + String.format(Locale.US, "%,d", absMinor / FEN_PER_YUAN)
        // ≥ 10_000 元：折成「万」、一位小数。
        // 0.1 万 = 1_000 元 = 100_000 分 ⇒ 以「万分位」为单位四舍五入。
        // ⚠️ 舍入必须用「商 + 余数判进位」，**不能**写成 (absMinor + 50_000) / 100_000：
        // 当 absMinor > MAX_VALUE − 50_000 时那次加法会二次溢出回绕成负数，
        // 随后 wanTenths / 10 与 % 10 双负，拼出「¥-9223372036854.-7万」这类非法串。
        // 商最大约 9.2e13，+1 永不溢出。
        else -> {
            val wanTenths = absMinor / 100_000L + (if (absMinor % 100_000L >= 50_000L) 1L else 0L)
            "¥$sign${wanTenths / 10}.${wanTenths % 10}万"
        }
    }
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
fun MetricCard(result: MetricResult, modifier: Modifier = Modifier.fillMaxWidth()) {
    AppCard(modifier) {
        when (result) {
            is MetricResult.Scalar -> {
                Text(result.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    // 主指标不一定是钱：「花掉的时间」要显示「≈ 0.8 小时」而非折算金额。
                    result.primaryText ?: "¥${result.valueMinor.yuan()}",
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
                            // 每个节点都显式标注金额（含 ¥0 的月份），放在柱体**上方**；
                            // 格式按量级压缩（见 trendAmountLabel），节点列宽 ≈60dp 也不溢出。
                            Text(
                                text = trendAmountLabel(point.valueMinor),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(TREND_NODE_GAP))
                            Box(
                                Modifier
                                    .width(22.dp)
                                    // 柱高上限 64.dp：与「金额 + 月份」两行文字一起放进 110.dp 容器
                                    // （labelSmall≈16 + 4 + 64 + 4 + labelMedium≈16 = 104.dp ≤ 110.dp）。
                                    .height((ratio * TREND_MAX_BAR_DP).coerceAtLeast(2f).dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(if (point.valueMinor >= max) LedgerPalette.PositiveStrong else LedgerPalette.PositivePale),
                            )
                            Spacer(Modifier.height(TREND_NODE_GAP))
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
    /**
     * true 时右侧以「折算工时」替代金额 —— 用于「刚刚花掉的时光」这类**强调时间成本**的场景；
     * 金额不再重复展示，工时不重复出现在副标题里。
     */
    timeOnRight: Boolean = false,
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
                    // 消费平台（业务维度）：自动识别不确定时带「?」，提示用户点开修正。
                    val platformLabel =
                        com.autoledger.core.model.platform.PlatformCatalog.displayNameOf(txn.platformId)
                    val platformUncertain =
                        txn.platformSource == com.autoledger.core.model.platform.PlatformSource.AUTO &&
                            txn.platformConfidence <
                            com.autoledger.core.model.platform.PlatformResolver.CONFIRM_THRESHOLD
                    add(if (platformUncertain) "$platformLabel?" else platformLabel)

                    add(category?.name ?: "未分类")
                    // timeOnRight 时工时已占据右侧，副标题不再重复。
                    if (!timeOnRight) workText?.let { add("≈ $it") }
                    if (txn.type == com.autoledger.core.model.TxnType.TRANSFER) add("已识别为内部划转")
                    if (txn.status == com.autoledger.core.model.TxnStatus.RAW) add("待确认")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (timeOnRight && workText != null) {
            // 突出「花掉的时间」而非金额：时间尺度上的痛感是这个 App 的灵魂。
            Text(
                workText,
                style = MaterialTheme.typography.titleMedium,
                color = LedgerPalette.PositiveStrong,
            )
        } else {
            Text(
                "¥${txn.amountMinor.yuan()}",
                style = MaterialTheme.typography.titleMedium,
                color = if (txn.amountMinor < 0) MaterialTheme.colorScheme.onSurface else LedgerPalette.Positive,
            )
        }
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
