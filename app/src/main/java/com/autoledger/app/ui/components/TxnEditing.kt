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
import com.autoledger.app.ui.stores.canSwitchType
import com.autoledger.app.ui.stores.typeSwitchBlockReason
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.Category
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
        // v1.1.6 辅入口：一键翻转收支类型（与弹窗里的 chips 同一套判据 canSwitchType）。
        // 不可切换时**不显示**（弹窗里已给出原因，这里是快捷入口，不必重复占位）。
        // 不放进 TxnRowTrailing：行尾已被「纠正分类」「删除」占满且删除紧邻，单击即翻转数据误触成本太高。
        if (canSwitchType(txn)) {
            FilterChip(
                selected = false,
                onClick = { store.switchType(txn); onDone() },
                label = { Text(if (txn.type == TxnType.EXPENSE) "改为收入" else "改为支出") },
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
    /** 这条流水**吸收掉**的记录（`mergeGroupOf(txn.id)`）。空 = 它不是合并后的主记录。 */
    mergedInto: List<LedgerTransaction> = emptyList(),
    /** 撤销某条被吸收记录（传 null = 该入口不可用，例如从只读视图打开）。 */
    onUnmerge: ((String) -> Unit)? = null,
) {
    val amountDateEditable = TxnEditRules.canEditAmountAndDate(txn)
    val blockReason = TxnEditRules.blockReason(txn)
    // 类型切换与「合并链吸收条数」有关（主记录带着吸收来的记录，改类型会让两侧口径不一致），
    // 所以判定要传 mergedInto.size —— 该列表已由调用方通过 store.loadMergeGroup(txn.id) 加载。
    val typeSwitchable = canSwitchType(txn, mergedInto.size)
    var typeSelection by remember(txn.id) { mutableStateOf(txn.type) }

    var name by remember(txn.id) { mutableStateOf(txn.counterparty) }
    var noteText by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }
    var platformId by remember(txn.id) { mutableStateOf(txn.platformId) }
    var amountText by remember(txn.id) {
        // 以「元」为单位展示，且与符号无关 —— 方向由类型决定，不在金额输入框里体现。
        //
        // 必须锁定 Locale.US：默认 Locale 下 `%.2f` 会输出本地化数字（如阿拉伯语环境输出
        // 阿拉伯数字字符），随后 toBigDecimalOrNull() 解析失败 → 金额被判为非法 →
        // 保存按钮永久禁用，用户在非中文环境下根本改不了金额。
        //
        // abs(Long.MIN_VALUE) 会溢出成负数，这里一并兜住（否则显示成负金额）。
        val absMinor = if (txn.amountMinor == Long.MIN_VALUE) Long.MAX_VALUE
        else kotlin.math.abs(txn.amountMinor)
        mutableStateOf(String.format(java.util.Locale.US, "%.2f", absMinor / 100.0))
    }
    var dateText by remember(txn.id) {
        // 时间戳可能来自脏数据（备份导入/解析异常），转换失败时回退到今天，
        // 而不是让 DateTimeException 冒泡把整个弹窗渲染打崩。
        mutableStateOf(
            runCatching {
                LocalDate.ofInstant(Instant.ofEpochMilli(txn.occurredAtMillis), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrDefault(LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)),
        )
    }

    // 自动识别且置信度不足 ⇒ 提示用户确认（用户手选后即变 USER 源，不再提示）
    // 与列表副标题复用同一判定，避免"列表不显示「?」但弹窗还在提示"的不一致。
    val platformUncertain = isPlatformUncertain(txn)

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

                // 收支类型（v1.1.6）：放在金额/日期**之前** —— 方向是用户要显式表达的意思，
                // 夹在两个输入框后面会让人以为「方向由金额决定」。金额输入框本身不含符号。
                Text(
                    "收支类型",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = typeSelection == TxnType.EXPENSE,
                        enabled = typeSwitchable,
                        onClick = { typeSelection = TxnType.EXPENSE },
                        label = { Text("支出") },
                    )
                    FilterChip(
                        selected = typeSelection == TxnType.INCOME,
                        enabled = typeSwitchable,
                        onClick = { typeSelection = TxnType.INCOME },
                        label = { Text("收入") },
                    )
                }
                // 不可切换时写明原因，而不是把 chips 悄悄禁掉（沿用本弹窗既有惯例）
                typeSwitchBlockReason(txn, mergedInto.size)?.let { reason ->
                    Text(
                        reason,
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerPalette.Warning,
                    )
                }

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
                // 合并组：这条是"主记录"，它吸收过来的那些记录仍**原样留在库里**
                // （各自的平台、来源、原文都没被改），只是不再单独计入账单。
                // 这里把它们列出来，用户能看清"这笔记了两次，分别来自哪两个渠道"，也能逐条撤销。
                if (mergedInto.isNotEmpty()) {
                    Text(
                        "已合并 ${mergedInto.size} 条",
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = LedgerPalette.Blue,
                    )
                    mergedInto.forEach { absorbed ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "· ${PlatformCatalog.displayNameOf(absorbed.platformId)}" +
                                    "（${absorbed.sourceId}）" +
                                    absorbed.counterparty.takeIf { it.isNotBlank() }?.let { "　$it" }.orEmpty(),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            onUnmerge?.let { unmerge ->
                                TextButton(onClick = { unmerge(absorbed.id) }) { Text("撤销") }
                            }
                        }
                    }
                    Text(
                        "撤销后该条回到「待确认」；主记录的商户/平台可能仍含合并时补上的值，请核对。",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerPalette.Warning,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = TxnEditRules.canEdit(txn) && !amountInvalid && !dateInvalid,
                onClick = {
                    // 单次写入全部字段：拆成两次写会互相覆盖（改了商户和金额只剩一个生效）。
                    store.saveEdits(
                        txn = txn,
                        counterparty = name,
                        note = noteText,
                        platformId = platformId,
                        amountMinor = parsedAmount,
                        occurredAtMillis = parsedDate,
                        type = if (typeSwitchable) typeSelection else txn.type,
                        // 合并链主记录不得改类型：Store 侧也要用同一判据（不能只靠 UI 禁用 chips）
                        absorbedCount = mergedInto.size,
                    )
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 消费平台选择器：内置平台 + 用户自定义平台 + 未知，流式排列。
 *
 * 用 [PlatformCatalog.selectable] 而不是 `all()`：已停用的自定义平台不该再被指派给新流水。
 * 顺序天然是「内置在前、自定义在后」—— 内置 `sortOrder` 最大 90，自定义从 1000 起，unknown 排最后。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformPicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        // 当前选中的若是已停用平台，仍然要显示出来 —— 否则用户打开编辑框会看到"没有选中任何平台"
        val entries = PlatformCatalog.selectable().let { list ->
            val current = PlatformCatalog.find(selected)
            if (current != null && current.archived && list.none { it.id == current.id }) list + current else list
        }
        entries.forEach { entry ->
            FilterChip(
                selected = entry.id == selected,
                onClick = { onSelect(entry.id) },
                label = { Text(entry.displayName) },
            )
        }
    }
}

/**
 * 分类选择器：点已选中的项即取消选择（回到「未分类」）。
 *
 * 与 [PlatformPicker] 的两点差异是有意的：
 * - 平台有「未知」兜底值、恒有一个选中项；分类允许**不选**（`selectedId = null` = 未分类），
 *   因此给它一个显式的取消语义（再点一次同一个分类）。
 * - 候选列表由调用方给定：本组件**不臆造**也不过滤，`categories` 里有什么就显示什么
 *   —— 记账页传「未归档 + EXPENSE」的过滤结果，避免把收入分类和已归档分类混进支出录入区。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(
    selectedId: String?,
    categories: List<Category>,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        // 空列表给一句说明而不是留一片空白：否则用户会以为这块控件坏了。
        // （调用方负责把「当前已选中的分类」也放进列表 —— 本组件不臆造 entry，
        // 那会显示出一个只剩 id 的怪 chip。）
        if (categories.isEmpty()) {
            Text(
                "还没有可用分类，去「分类」页添加一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        categories.forEach { cat ->
            FilterChip(
                selected = cat.id == selectedId,
                onClick = { onSelect(if (cat.id == selectedId) null else cat.id) },
                label = { Text(cat.name) },
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

/**
 * `yyyy-MM-dd` → 当天 00:00 的 epoch millis；解析失败返回 null。
 *
 * 捕获 Exception 而非只捕获 [DateTimeParseException]：日期格式合法但数值越界
 * （如 `+1000000000-01-01` 超出 LocalDate 年份上限）会抛 [java.time.DateTimeException]，
 * 它是 DateTimeParseException 的**兄弟类**，只 catch 前者会漏掉这类脏输入并导致崩溃。
 */
private fun parseLocalDate(text: String): Long? = try {
    LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
} catch (e: Exception) {
    null
}
