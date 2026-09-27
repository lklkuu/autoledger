package com.autoledger.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoledger.app.di.AppContainer
import com.autoledger.app.ui.components.AppCard
import com.autoledger.app.ui.components.EmptyHint
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.CategoryDraft
import com.autoledger.app.ui.stores.CategoryStore
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.app.ui.theme.colorOf
import com.autoledger.core.model.Category
import com.autoledger.core.model.CategoryKind

/** 分类管理 —— 增删改分类、设月度预算。 */
@Composable
fun CategoryManageScreen(container: AppContainer) {
    val store = remember(container) { CategoryStore(container) }
    LaunchedEffect(container) { store.load() }
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()
    var editing by remember { mutableStateOf<CategoryDraft?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("分类管理", "增删改分类、设月度预算")
                Button(onClick = { editing = CategoryDraft() }, Modifier.padding(top = 10.dp)) { Text("新建分类") }
                state.message?.let {
                    Text(
                        it,
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerPalette.Positive,
                    )
                }
            }
        }
        item {
            CategorySection(
                title = "支出分类",
                list = state.expense,
                onEdit = { editing = CategoryDraft.from(it) },
                onDelete = store::delete,
            )
        }
        item {
            CategorySection(
                title = "收入分类",
                list = state.income,
                onEdit = { editing = CategoryDraft.from(it) },
                onDelete = store::delete,
            )
        }
    }

    editing?.let { draft ->
        CategoryEditorDialog(
            initial = draft,
            onDismiss = { editing = null },
            onSave = {
                store.save(it)
                editing = null
            },
        )
    }
}

@Composable
private fun CategorySection(
    title: String,
    list: List<Category>,
    onEdit: (Category) -> Unit,
    onDelete: (String) -> Unit,
) {
    AppCard {
        SectionTitle(title, "共 ${list.size} 个")
        if (list.isEmpty()) {
            EmptyHint("这一栏还没有分类")
        } else {
            list.forEach { category ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorOf(category.colorHex).copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            LedgerIcons.forCategory(category.iconKey),
                            contentDescription = null,
                            tint = colorOf(category.colorHex),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(category.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildList {
                                category.monthlyBudgetMinor?.let { add("预算 ¥${it.yuan()}/月") }
                                if (category.builtIn) add("内置")
                                if (category.archived) add("已归档")
                            }.ifEmpty { listOf("未设预算") }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onEdit(category) }) { Icon(LedgerIcons.Edit, "编辑") }
                    IconButton(onClick = { onDelete(category.id) }) { Icon(LedgerIcons.Delete, "删除") }
                }
            }
        }
    }
}

@Composable
private fun CategoryEditorDialog(
    initial: CategoryDraft,
    onDismiss: () -> Unit,
    onSave: (CategoryDraft) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    var budget by remember(initial) { mutableStateOf(initial.budgetYuan) }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "新建分类" else "编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == CategoryKind.EXPENSE,
                        onClick = { kind = CategoryKind.EXPENSE },
                        label = { Text("支出") },
                    )
                    FilterChip(
                        selected = kind == CategoryKind.INCOME,
                        onClick = { kind = CategoryKind.INCOME },
                        label = { Text("收入") },
                    )
                }
                OutlinedTextField(
                    value = budget,
                    onValueChange = { budget = it },
                    label = { Text("月度预算（元，可留空）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val draft = initial.copy(name = name, kind = kind, budgetYuan = budget)
                val message = draft.validate()
                if (message != null) error = message else onSave(draft)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
