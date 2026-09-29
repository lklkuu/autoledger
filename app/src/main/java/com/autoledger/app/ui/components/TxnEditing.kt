package com.autoledger.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoledger.app.ui.stores.LedgerStore
import com.autoledger.app.ui.stores.TxnEditRules
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformResolver
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.core.model.txnExtras
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 流水编辑的**共享** UI：修正对话框 + 行尾动作 + 展开区。
 *
 * 「记账」页与「账单」页都用它 —— 两页编辑能力必须完全一致，
 * 且后续加字段只改这一处，不会再次出现两页行为分叉。
 */

/**
 * 行尾编辑动作：纠正分类（展开/收起）、删除。
 *
 * @param expanded 当前是否展开（分类与标签的编辑区）
 */
@Composable
fun TxnRowTrailing(
    store: LedgerStore,
    txn: LedgerTransaction,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    if (!TxnEditRules.canEdit(txn)) {
        // 已并入其它流水：不给编辑入口，避免"点了没反应"
        Text(
            "已并入",
            style = MaterialTheme.typography.bodySmall,
            color = LedgerPalette.Muted,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 独立的「纠正分类」按钮：不与点行编辑冲突
        IconButton(onClick = onToggleExpanded) {
            Icon(LedgerIcons.Category, if (expanded) "收起分类" else "纠正分类")
        }
        IconButton(onClick = { store.delete(txn.id) }) {
            Icon(LedgerIcons.Delete, "删除")
        }
    }
}

/** 展开区：分类 chips + 「标为内部划转」+ 标签编辑器。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxnEditExtras(
    store: LedgerStore,
    txn: LedgerTransaction,
    categories: Collection<com.autoledger.core.model.Category>,
    onDone: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categories.forEach { cat ->
            CategoryChip(cat) {
                store.correctCategory(txn, cat)
                onDone()
            }
        }
        if (txn.type != TxnType.TRANSFER) {
            FilterChip(
                selected = false,
                onClick = { store.markTransfer(txn); onDone() },
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

/**
 * 修正一笔流水：商户名 / 备注 / 消费平台 + **金额与日期**。
 *
 * 金额与日期在「已关联订单 / 退款 / 内部划转」时禁用（会破坏抵扣对账与配对），
 * 并在界面上写明原因，而不是默默忽略用户的输入。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxnEditDialog(
    store: LedgerStore,
    txn: LedgerTransaction,
    onDismiss: () -> Unit,
) {
    val amountDateEditable = TxnEditRules.canEditAmountAndDate(txn)
    val blockReason = TxnEditRules.blockReason(txn)

    var name by remember(txn.id) { mutableStateOf(txn.counterparty) }
    var noteText by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }
    var platformId by remember(txn.id) { mutableStateOf(txn.platformId) }
    var amountText by remember(txn.id) {
        // 以「元」为单位展示，且与符号无关 —— 方向由类型决定，不在金额输入框里体现
        mutableStateOf((kotlin.math.abs(txn.amountMinor) / 100.0).let { "%.2f".format(it) })
    }
    var dateText by remember(txn.id) {
        mutableStateOf(
            LocalDate.ofInstant(Instant.ofEpochMilli(txn.occurredAtMillis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE),
        )
    }

    // 自动识别且置信度不足 ⇒ 提示用户确认（用户手选后即变 USER 源，不再提示）
    val platformUncertain = txn.platformSource == PlatformSource.AUTO &&
        txn.platformConfidence < PlatformResolver.CONFIRM_THRESHOLD

    val parsedAmount: Long? = amountText.trim().toBigDecimalOrNull()
        ?.let { (it.movePointRight(2)).toLong() }
    val amountInvalid = amountDateEditable && (parsedAmount == null || parsedAmount <= 0L)
    val parsedDate: Long? = remember(dateText) { parseLocalDate(dateText) }
    val dateInvalid = amountDateEditable && parsedDate == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (TxnEditRules.canEdit(txn)) "修正这笔流水" else "这笔不可修改") },
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

                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("金额（元）") },
                        singleLine = true,
                        enabled = amountDateEditable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = amountInvalid,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("日期 yyyy-MM-dd") },
                        singleLine = true,
                        enabled = amountDateEditable,
                        isError = dateInvalid,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!amountDateEditable) {
                    Text(
                        "已与订单 / 退款 / 内部划转关联，金额与日期不可修改。",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerPalette.Warning,
                    )
                }

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
                blockReason?.let {
                    Text(
                        it,
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
            TextButton(
                enabled = TxnEditRules.canEdit(txn) && !amountInvalid && !dateInvalid,
                onClick = {
                    store.updateCounterparty(txn, name, noteText, platformId)
                    if (amountDateEditable && parsedAmount != null && parsedDate != null) {
                        store.updateAmountAndDate(txn, parsedAmount, parsedDate)
                    }
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 消费平台选择器：内置平台 + 未知，流式排列。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformPicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        PlatformCatalog.all().forEach { entry ->
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
fun TagEditor(
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

/** `yyyy-MM-dd` → 当天 00:00 的 epoch millis；解析失败返回 null。 */
private fun parseLocalDate(text: String): Long? = try {
    LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
} catch (e: DateTimeParseException) {
    null
}
