package com.autoledger.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.autoledger.feature.capture.notify.NotificationDiag
import androidx.compose.ui.unit.dp
import com.autoledger.app.di.AppContainer
import com.autoledger.app.ui.components.AppCard
import com.autoledger.app.ui.components.EmptyHint
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.stores.UserPlatformDraft
import com.autoledger.app.ui.stores.UserPlatformStore
import com.autoledger.app.ui.stores.from
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformKind

/**
 * 自定义消费平台管理（设置 → 消费平台管理）。
 *
 * ## 为什么值得单独一个页面
 * 内置目录覆盖常见平台，但覆盖不了你的实际情况（山姆、某个本地连锁…）。
 * 用户自己加的平台会：① 参与后台识别；② 出现在流水的平台选择器里；③ 进「按平台统计」。
 *
 * ## 两条容易踩的语义（页面文案里也写明了，避免用户误解）
 * 1. **停用是"软删除"**：停用后不再识别新流水，**但历史流水仍显示它的名字**（不是「未知平台」）；
 * 2. **ID 不随名称变**：改名不会换 ID，历史流水因此不会变成孤儿。
 */
@Composable
fun PlatformManageScreen(container: AppContainer) {
    val store = remember(container) { UserPlatformStore(container) }
    DisposableEffect(store) { onDispose { store.close() } }
    val state by store.state.collectAsState()
    var editing by remember { mutableStateOf<UserPlatform?>(null) }
    var creating by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(container) { store.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("消费平台", "自己加的平台会参与识别、可筛选、可统计")
                Text(
                    "内置平台由 App 维护；这里添加的是你自己的（例如「京东」「山姆」）。" +
                        "关键词命中正文或商户名，「通知包名」命中通知来源（更准，但需要你确认过）。",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { creating = true },
                    Modifier.padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                ) { Text("新增平台") }
                state.message?.let {
                    Text(
                        it,
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LedgerPalette.Blue,
                    )
                }
            }
        }

        item { SectionTitle("启用中", "参与识别与指派") }
        if (state.active.isEmpty()) {
            item { EmptyHint("还没有自定义平台。点上面的「新增平台」加一个。") }
        }
        items(state.active) { platform ->
            PlatformCard(
                platform = platform,
                onEdit = { editing = platform },
                onArchiveToggle = { store.setArchived(platform.id, !platform.archived) },
            )
        }

        item {
            SectionTitle("已停用", "不再识别新流水，但历史流水仍显示该名称")
        }
        if (state.archived.isEmpty()) {
            item {
                EmptyHint("没有停用的平台。停用不会删除数据，随时可以恢复。")
            }
        }
        items(state.archived) { platform ->
            PlatformCard(
                platform = platform,
                onEdit = { editing = platform },
                onArchiveToggle = { store.setArchived(platform.id, !platform.archived) },
            )
        }

        item {
            AppCard {
                SectionTitle("识别不出来怎么办", "先看这句再改关键词")
                Text(
                    "平台识别只依据「通知正文 + 商户名 + 通知包名」，且**宁可 unknown 也不瞎猜**。" +
                        "如果某笔没被认出来，先确认那条通知归属哪个 App —— 把它的包名填进来最有效。",
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val draftTarget = if (creating) UserPlatformDraft() else editing?.let { UserPlatformDraft.from(it) }
    if (draftTarget != null) {
        UserPlatformEditDialog(
            initial = draftTarget,
            isNew = creating,
            onDismiss = { creating = false; editing = null },
            onSave = {
                store.save(it)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun PlatformCard(
    platform: UserPlatform,
    onEdit: () -> Unit,
    onArchiveToggle: () -> Unit,
) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${roleLabel(platform.kind)}　${platform.id}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (platform.archived) {
                Text(
                    "已停用",
                    style = MaterialTheme.typography.labelMedium,
                    color = LedgerPalette.Warning,
                )
            }
        }
        val signals = platform.strongKeywords + platform.mediumKeywords +
            platform.weakKeywords + platform.packageNames.toList()
        Text(
            if (signals.isEmpty()) "（没有关键词，不会被识别）" else "线索：${signals.joinToString("、")}",
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            OutlinedButton(onClick = onArchiveToggle) {
                Text(if (platform.archived) "恢复" else "停用")
            }
        }
    }
}

private fun roleLabel(kind: PlatformKind): String = when (kind) {
    PlatformKind.ORDER -> "下单平台"
    PlatformKind.PAYMENT -> "支付通道"
    PlatformKind.E_WALLET -> "数字通道"
    PlatformKind.BANK -> "银行卡"
    PlatformKind.OTHER -> "其他"
}

@Composable
private fun UserPlatformEditDialog(
    initial: UserPlatformDraft,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (UserPlatformDraft) -> Unit,
) {
    var name by remember { mutableStateOf(initial.displayName) }
    var kind by remember { mutableStateOf(initial.kind) }
    var strong by remember { mutableStateOf(initial.strongKeywords) }
    var medium by remember { mutableStateOf(initial.mediumKeywords) }
    var weak by remember { mutableStateOf(initial.weakKeywords) }
    var packages by remember { mutableStateOf(initial.packageNames) }

    val draft = initial.copy(
        displayName = name,
        kind = kind,
        strongKeywords = strong,
        mediumKeywords = medium,
        weakKeywords = weak,
        packageNames = packages,
    )
    val error = draft.validate()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增消费平台" else "编辑「${initial.displayName}」") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Field("平台名称", name, "例如：京东") { name = it }
                }
                item {
                    Text("角色（决定它能不能压过支付通道）", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlatformKind.entries.forEach { k ->
                            // 不开放 BANK / E_WALLET：它们是内置的"整类"通道（银行兜底 / 官方数字通道），
                            // 用户自建一条「银行卡 / 数字人民币 / 云闪付」平台没有意义；自定义的多是商家（ORDER）或通道。
                            if (k == PlatformKind.BANK || k == PlatformKind.E_WALLET) return@forEach
                            OutlinedButton(
                                onClick = { kind = k },
                                colors = if (kind == k) {
                                    ButtonDefaults.outlinedButtonColors(containerColor = LedgerPalette.PositivePale)
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                },
                            ) { Text(roleLabel(k), style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                item { Field("强关键词", strong, "一行一个，例如：京东支付") { strong = it } }
                item { Field("中关键词", medium, "一行一个，例如：京东") { medium = it } }
                item { Field("弱关键词", weak, "一行一个，例如：京东物流") { weak = it } }
                item {
                    // ① 首选：从最近收到的通知里直接选（系统给的真实包名，不用手打）
                    val recentPackages = remember(packages) {
                        NotificationDiag.entries.value
                            // ⚠️ `contains('.')` 这道过滤是**正确性关键**，不能删：
                            // `RawEnvelope.packageName` 被三条渠道以三种语义共用 ——
                            // 短信渠道存的是**发件号码**（如 95555 / 1069xxx，字段名一样但不是包名）、
                            // 账单导入存的是 **CSV 里的中文平台名**（如「支付宝」）。
                            // 而包名在识别里占 0.95 最高权重，塞进非包名会导致**整类通知恒定错判**。
                            // 含点过滤天然把这两类脏数据挡在外面。
                            .map { it.packageName }
                            .filter { it.isNotBlank() && it.contains('.') }
                            .distinct()
                            .take(8)
                    }
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(
                            "从最近收到的通知里选",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (recentPackages.isEmpty()) {
                            Text(
                                "最近还没有收到任何通知。请先让对应 App 发一次支付通知，" +
                                    "或展开下方手动填写。",
                                Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = LedgerPalette.Muted,
                            )
                        } else {
                            recentPackages.forEach { pkg ->
                                val label = remember(pkg) {
                                    NotificationDiag.entries.value
                                        .firstOrNull { it.packageName == pkg }
                                        ?.title?.take(18)?.takeIf { it.isNotBlank() }
                                }
                                val already = packages.split('\n', '\r')
                                    .any { it.trim().equals(pkg, ignoreCase = true) }
                                OutlinedButton(
                                    onClick = {
                                        if (already) return@OutlinedButton
                                        val next = (packages.trim() + "\n" + pkg).trim()
                                        packages = next
                                    },
                                    enabled = !already,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                ) {
                                    Text(
                                        (if (label != null) "$label · " else "") + pkg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Text(
                                "点一下即可填入；只列出本机最近 30 条通知里出现过的真实包名。",
                                Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = LedgerPalette.Muted,
                            )
                        }
                    }
                }
                // ② 兜底：手填保留但降级为次要路径（移除它会让"还没产生过通知的平台"无法添加）
                item {
                    var manualOpen by remember { mutableStateOf(false) }
                    if (manualOpen) {
                        Field(
                            "手动填写包名",
                            packages,
                            "一行一个，例如：com.jingdong.app.mall",
                        ) { packages = it }
                    } else {
                        OutlinedButton(onClick = { manualOpen = true }) {
                            Text("手动填写（不确定就留空，靠关键词识别）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "不确定就留空，靠关键词识别。**填错包名会让这个平台错误命中一整类通知**，" +
                            "比暂时不填更糟。",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerPalette.Warning,
                    )
                }
                // 让用户能提前看到「这个平台会被排序到哪」——内置平台永远排在前面
                item {
                    Text(
                        "排序：内置平台在前，自定义平台在 ${UserPlatform.DEFAULT_SORT_ORDER} 之后。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = error == null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Field(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = label == "平台名称",
        )
    }
}
