package com.autoledger.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.app.ui.theme.colorOf
import com.autoledger.core.model.BudgetStatus

/**
 * 预算进度卡片：每个设了预算的支出分类一行，展示「已花 / 预算」与进度条；超支整行标红。
 * 只负责渲染 [BudgetStatus]，口径全部来自 feature:stats 的 BudgetCalculator。
 */
@Composable
fun BudgetCard(statuses: List<BudgetStatus>, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        val overCount = statuses.count { it.isOverBudget }
        SectionTitle("本月预算", if (statuses.isEmpty()) "还没有分类设置预算" else "$overCount 个分类超支")
        if (statuses.isEmpty()) {
            EmptyHint("去「分类管理」给分类设个月度预算")
        } else {
            statuses.forEach { status ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            LedgerIcons.forCategory(status.iconKey),
                            contentDescription = null,
                            tint = colorOf(status.colorHex),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            status.categoryName,
                            Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "¥${status.spentMinor.yuan()} / ¥${status.budgetMinor.yuan()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (status.isOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProgressLine(
                        progress = status.ratio.toFloat(),
                        label = if (status.isOverBudget) "已超支" else "已用 ${"%.0f".format(status.ratio * 100)}%",
                        targetLabel = if (status.isOverBudget) {
                            "超 ¥${(-status.remainingMinor).yuan()}"
                        } else {
                            "剩 ¥${status.remainingMinor.yuan()}"
                        },
                        color = if (status.isOverBudget) MaterialTheme.colorScheme.error else LedgerPalette.Positive,
                    )
                }
            }
        }
    }
}
