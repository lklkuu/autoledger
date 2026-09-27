package com.autoledger.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.net.Uri
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoledger.app.di.AppContainer
import com.autoledger.app.ui.components.AppCard
import com.autoledger.app.ui.components.EmptyHint
import com.autoledger.app.ui.components.LoadingBox
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.TransactionRow
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.CaptureStore
import com.autoledger.app.ui.stores.SettingsStore
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.TxnStatus
import com.autoledger.feature.capture.CaptureAction
import com.autoledger.feature.capture.PermissionState
import kotlinx.coroutines.launch

/**
 * 采集箱 —— 自动化程度的真相所在：
 * 「哪些渠道开着」「有多少条自动抓到但没把握」都摊在这里，用户永远知道 App 到底在做什么。
 */
@Composable
fun CaptureScreen(container: AppContainer) {
    val store = remember(container) { CaptureStore(container) }
    val state by store.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // remember 必须在组合阶段调用；回调只复用已创建的 scope。
    val scope = rememberCoroutineScope()
    LaunchedEffect(container) { store.load(context) }
    // B4：离开页面时释放订阅（实例级作用域）。
    DisposableEffect(store) { onDispose { store.close() } }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { store.refreshRows(context) }

    var pendingPickSource by remember { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val sourceId = pendingPickSource ?: return@rememberLauncherForActivityResult
        pendingPickSource = null
        if (uri != null) store.pullBacklog(context, sourceId, uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("自动采集渠道", "开着几个，就自动到什么程度")
                state.rows.forEach { row ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.source.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(row.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val isOn = row.state == PermissionState.GRANTED
                            Text(
                                if (isOn) "已开启" else "未开启",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isOn) LedgerPalette.Positive else LedgerPalette.Muted,
                            )
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.source.actions.forEach { action ->
                                when (action) {
                                    is CaptureAction.OpenSystemSettings ->
                                        if (row.state != PermissionState.GRANTED) {
                                            Button(onClick = { row.source.openSystemSettings(context) }) { Text(action.label) }
                                        }

                                    is CaptureAction.RequestPermission ->
                                        Button(onClick = { smsPermission.launch(action.permission) }) { Text(action.label) }

                                    is CaptureAction.ScanBacklog ->
                                        Button(
                                            onClick = { store.pullBacklog(context, row.source.id) },
                                            enabled = row.state == PermissionState.GRANTED,
                                        ) { Text(action.label) }

                                    is CaptureAction.PickFile ->
                                        Button(onClick = {
                                            pendingPickSource = row.source.id
                                            pickFile.launch(action.mimeType)
                                        }) { Text(action.label) }
                                }
                            }
                        }
                    }
                }
                state.message?.let {
                    Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = LedgerPalette.Positive)
                }
                if (state.working) LoadingBox()
            }
        }

        item {
            AppCard {
                SectionTitle("待确认流水", "自动抓到但没把握的都在这里，不替用户做决定")
                if (state.rawQueue.isEmpty()) {
                    EmptyHint("没有待确认的流水，全都很确定")
                } else {
                    state.rawQueue.forEach { txn ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TransactionRow(
                                txn = txn,
                                category = null,
                                modifier = Modifier.weight(1f),
                                trailing = {
                                    IconButton(onClick = {
                                        scope.launch {
                                            container.repository.markStatus(txn.id, TxnStatus.CONFIRMED)
                                        }
                                    }) { Icon(LedgerIcons.Check, "确认入账") }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** C3：加密导出/导入的待处理请求 —— 先选文件，再输口令。 */
private sealed interface EncryptRequest {
    data class Export(val uri: Uri) : EncryptRequest
    data class Import(val uri: Uri) : EncryptRequest
}

@Composable
fun SettingsScreen(container: AppContainer) {
    val store = remember(container) { SettingsStore(container) }
    val state by store.state.collectAsState()
    LaunchedEffect(container) { store.refresh() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) store.exportJson(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) store.importJson(uri) }

    // C3：加密导出/导入 —— 先选文件，再弹口令对话框
    var encryptRequest by remember { mutableStateOf<EncryptRequest?>(null) }
    var passphrase by remember { mutableStateOf("") }
    val encryptExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) encryptRequest = EncryptRequest.Export(uri) }
    val encryptImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) encryptRequest = EncryptRequest.Import(uri) }

    val autoMerge by container.settings.autoMerge.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("数据备份", "导出文件自带版本号，跨版本也能导回来")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exportLauncher.launch("autoledger-backup.json") },
                        modifier = Modifier.weight(1f),
                    ) { Icon(LedgerIcons.Upload, null); Text(" 导出 JSON") }
                    Button(
                        onClick = { importLauncher.launch("application/json") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Blue),
                    ) { Icon(LedgerIcons.Download, null); Text(" 导入备份") }
                }
                // C3：加密导出/导入（口令保护，适合存网盘 / 发别人）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Button(
                        onClick = { encryptExportLauncher.launch("autoledger-backup-encrypted.json") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                    ) { Text(" 加密导出") }
                    Button(
                        onClick = { encryptImportLauncher.launch("application/json") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                    ) { Text(" 加密导入") }
                }
                state.message?.let {
                    Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = LedgerPalette.Positive)
                }
            }
        }

        item {
            AppCard {
                SectionTitle("分类管理", "增删改分类、设月度预算")
                Button(
                    onClick = { container.nav.navigate(com.autoledger.app.ui.nav.Destination.CATEGORY) },
                    Modifier.padding(top = 10.dp),
                ) { Icon(LedgerIcons.Category, null); Text(" 管理分类") }
            }
        }

        item {
            AppCard {
                SectionTitle("订单与退款", "按原抵扣构成原路回退，金额可对账")
                Button(
                    onClick = { container.nav.navigate(com.autoledger.app.ui.nav.Destination.REFUND) },
                    Modifier.padding(top = 10.dp),
                ) { Icon(LedgerIcons.Download, null); Text(" 订单与退款") }
            }
        }

        item {
            AppCard {
                SectionTitle("反馈问题", "记错账 / 打不开 / 想要新功能")
                Text(
                    "覆盖安装新版不会丢数据（只要包名和签名一致）；升级前建议先「导出 JSON」备份一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val feedbackContext = LocalContext.current
                Button(
                    onClick = {
                        val versionName = runCatching {
                            feedbackContext.packageManager.getPackageInfo(feedbackContext.packageName, 0).versionName
                        }.getOrDefault("unknown")
                        // 诊断信息刻意不含任何金额/商户明文，只描述环境与状态，可放心外发。
                        val diagnostic = buildString {
                            appendLine("App 版本: $versionName")
                            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                            appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
                            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                            appendLine("整库加密: ${if (container.isEncryptedAtRest) "是" else "否（已降级明文）"}")
                            appendLine("流水条数: ${state.transactionCount}")
                            appendLine("已学习分类规则: ${state.learnedRules} 条")
                            appendLine("问题描述: <请在这里补充>")
                        }
                        val cm = feedbackContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("AutoLedger 诊断", diagnostic))
                        Toast.makeText(feedbackContext, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(top = 10.dp),
                ) { Text("复制诊断信息") }
                Text(
                    "复制后把诊断信息连同「导出 JSON」一起发给开发者，即可精确定位记账错误。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            AppCard {
                SectionTitle("自动去重")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("跨渠道重复自动合并", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关闭后，疑似重复的流水会进采集箱等人确认，不再自动处理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoMerge, onCheckedChange = container.settings::setAutoMerge)
                }
            }
        }

        item {
            AppCard {
                SectionTitle("学习记录", "你纠正过 ${state.learnedRules} 条分类，它们会一直生效")
                Button(
                    onClick = { store.resetLearning() },
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Muted),
                ) { Text("清空学习记录") }
            }
        }

        item {
            AppCard {
                SectionTitle("隐私与安全")
                BulletLine(
                    LedgerIcons.Lock,
                    if (container.isEncryptedAtRest) {
                        "整库 SQLCipher 加密，密钥由系统 Keystore 保管"
                    } else {
                        "当前为明文存储：加密连续失败 3 次后已放弃加密；通知/短信原文仍经 Keystore 密封，不会明文入库"
                    },
                )
                BulletLine(LedgerIcons.Lock, "通知原文二次加密后才落盘，日志一律脱敏")
                BulletLine(LedgerIcons.Lock, "不发任何数据上云；云同步仅有接口，当前是无操作的占位实现")
            }
        }

        item {
            AppCard {
                SectionTitle("危险操作", "共 ${state.transactionCount} 笔流水")
                Button(
                    onClick = { showClearConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Icon(LedgerIcons.Delete, null); Text(" 清空全部流水") }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部流水？") },
            text = { Text("将删除全部 ${state.transactionCount} 笔流水，此操作无法撤销。建议先「导出 JSON」备份。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    store.clearAll()
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    // C3：加密导出/导入的口令对话框
    encryptRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { encryptRequest = null; passphrase = "" },
            title = { Text(if (request is EncryptRequest.Export) "加密导出" else "加密导入") },
            text = {
                Column {
                    Text("请输入口令（至少 6 位），用于加密 / 解密备份文件。请务必牢记，口令丢失无法找回。")
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= 6,
                    onClick = {
                        val pwd = passphrase.toCharArray()
                        when (request) {
                            is EncryptRequest.Export -> store.exportEncrypted(request.uri, pwd)
                            is EncryptRequest.Import -> store.importEncrypted(request.uri, pwd)
                        }
                        encryptRequest = null
                        passphrase = ""
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { encryptRequest = null; passphrase = "" }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BulletLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LedgerPalette.Positive, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
