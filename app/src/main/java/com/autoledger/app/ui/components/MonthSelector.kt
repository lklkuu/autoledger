package com.autoledger.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoledger.app.ui.theme.LedgerIcons
import java.time.YearMonth

/**
 * 月份选择器（**无状态**）。
 *
 * 横向可滚动的月份 chips + 两侧「‹ / ›」切相邻月；首末位置由调用方通过
 * [canGoPrev] / [canGoNext] 禁用，避免越界。
 *
 * ## 为什么降序（当前月在最左）
 * 降序时默认选中的当前月天然落在首屏可见位置，**不需要 scrollToItem**。
 * 升序则必须额外做滚动定位，多一个失败面 —— 定位失败时用户会以为"没有选择器"。
 *
 * ## 为什么跨年要补年份
 * 同屏可能出现两个「12月」（2025 年 12 月 / 2026 年 12 月），用户无法分辨。
 * 因此非当年显示「2025年12月」，当年只显示「10月」。
 *
 * 组件不碰 Store：选中月与切换回调都由调用方持有，便于将来账单页直接复用。
 */
@Composable
fun MonthSelector(
    months: List<YearMonth>,
    selected: YearMonth,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    onSelect: (YearMonth) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, enabled = canGoPrev) {
            Icon(LedgerIcons.ChevronLeft, "上一个月")
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(months, key = { it.toString() }) { month ->
                FilterChip(
                    selected = month == selected,
                    onClick = { onSelect(month) },
                    label = { Text(monthLabel(month, selected)) },
                )
            }
        }
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(LedgerIcons.ChevronRight, "下一个月")
        }
    }
}

/** 当年只写「10月」，跨年补上「2025年12月」—— 避免同屏两个「12月」难以分辨。 */
private fun monthLabel(month: YearMonth, reference: YearMonth): String =
    if (month.year == reference.year) "${month.monthValue}月"
    else "${month.year}年${month.monthValue}月"
