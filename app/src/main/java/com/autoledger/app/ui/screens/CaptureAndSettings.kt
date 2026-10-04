package com.autoledger.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
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
import com.autoledger.app.ui.components.AiSettingsCard
import com.autoledger.app.ui.components.aiPrivacyLine
import com.autoledger.app.ui.components.EmptyHint
import com.autoledger.app.ui.components.LoadingBox
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.TransactionRow
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.CaptureStore
import com.autoledger.app.DonationChannel
import com.autoledger.app.DonationConfig
import com.autoledger.app.ui.components.qrBitmapOf
import com.autoledger.app.ui.stores.SettingsStore
import com.autoledger.app.ui.stores.TransferStore
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.TxnStatus
import com.autoledger.feature.capture.CaptureAction
import com.autoledger.feature.capture.PermissionState
import com.autoledger.feature.capture.notify.NotificationDiag
import com.autoledger.feature.transfer.TransferTicket
import kotlinx.coroutines.launch

/**
 * 采集箱 —— 自动化程度的真相所在：
 * 「哪些渠道开着」「有多少条自动抓到但没把握」都摊在这里，用户永远知道 App 到底在做什么。
 */
@Composable
fun CaptureScreen(container: AppContainer) {
    val store = remember(container) { CaptureStore(container) }
    val state by store.state.collectAsState()
    // 通知采集诊断：监听服务状态 + 最近收到的通知（排查"为什么没记录"）
    val diagConnected by NotificationDiag.connected.collectAsState()
    val diagEntries by NotificationDiag.entries.collectAsState()
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = {
                                            scope.launch {
                                                container.repository.markStatus(txn.id, TxnStatus.CONFIRMED)
                                            }
                                        }) { Icon(LedgerIcons.Check, "确认入账") }
                                        // 与账单页保持一致：待确认的流水也能直接丢弃（标记为忽略）。
                                        IconButton(onClick = {
                                            scope.launch {
                                                container.repository.markStatus(txn.id, TxnStatus.IGNORED)
                                            }
                                        }) { Icon(LedgerIcons.Delete, "忽略这笔") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item {
            AppCard {
                SectionTitle("通知监听诊断", "排查「为什么没记录」：看通知有没有到 App")
                Text(
                    if (diagConnected) "监听服务：已连接" else "监听服务：未连接（可能未授权或被系统限制）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (diagConnected) LedgerPalette.Positive else LedgerPalette.Warning,
                )
                if (diagEntries.isEmpty()) {
                    EmptyHint("还没收到任何通知。请确认系统「通知使用权」已对本 App 开启。")
                } else {
                    diagEntries.take(6).forEach { e ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                e.title.ifBlank { e.packageName },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(e.body.take(80), style = MaterialTheme.typography.bodySmall)
                            Text(e.outcome, style = MaterialTheme.typography.labelMedium, color = LedgerPalette.Blue)
                        }
                    }
                    Button(
                        onClick = { NotificationDiag.clear() },
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Muted),
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("清空诊断记录") }
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
    // v1.1.7 AI 判定配置（开关 / 接口地址）：AI 卡片与隐私文案都按它条件化渲染。
    val appSettings by container.settings.state.collectAsState()
    LaunchedEffect(container) { store.refresh() }
    // 捐赠收款码弹窗当前展示的渠道（null = 不展示）
    var donationChannel by remember { mutableStateOf<DonationChannel?>(null) }

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
    val notifyOnRecord by container.notifyOnRecord.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }

    // C4：数据迁移（设备直连）
    val transferStore = remember(container) { TransferStore(container) }
    val transferState by transferStore.state.collectAsState()
    var showTransferInput by remember { mutableStateOf(false) }
    var transferTicketInput by remember { mutableStateOf("") }
    DisposableEffect(transferStore) { onDispose { transferStore.close() } }

    // 外观：主题（夜间黑皮肤）+ 背景图
    val darkTheme by container.darkTheme.collectAsState()
    val backgroundPath by container.backgroundImagePath.collectAsState()
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val path = copyImageToPrivate(container.applicationContext, uri)
            if (path != null) container.setBackgroundImagePath(path)
        }
    }

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
                SectionTitle("数据迁移", "换新手机时，把账本整体搬到新机")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { transferStore.startSend() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
                    ) { Text("发起迁移（旧机）") }
                    Button(
                        onClick = { showTransferInput = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Blue),
                    ) { Text("接收迁移（新机）") }
                }
                when (val ts = transferState) {
                    is TransferStore.State.ReadyToSend -> {
                        Text(
                            "旧机已就绪，请在新机点「接收迁移」并粘贴下面的配对信息：",
                            Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            ts.ticket.encode(),
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = LedgerPalette.Blue,
                        )
                    }
                    is TransferStore.State.Progress -> {
                        Text(
                            "传输中：${ts.done}/${ts.total} 块",
                            Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            progress = { if (ts.total == 0) 0f else ts.done.toFloat() / ts.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    is TransferStore.State.Finished -> {
                        Text(ts.summary, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = LedgerPalette.Positive)
                    }
                    is TransferStore.State.Failed -> {
                        Text(ts.message, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = LedgerPalette.Danger)
                    }
                    else -> {}
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
                SectionTitle("消费平台管理", "自己加平台（京东、山姆…），参与识别与统计")
                Button(
                    onClick = { container.nav.navigate(com.autoledger.app.ui.nav.Destination.PLATFORM) },
                    Modifier.padding(top = 10.dp),
                ) { Icon(LedgerIcons.Category, null); Text(" 管理消费平台") }
            }
        }

        item {
            // v1.1.7 AI 判定：默认关闭。关闭时全本地处理、通知原文不出设备。
            AiSettingsCard(container = container, enabled = appSettings.aiEnabled)
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
                SectionTitle("关于", "${DonationConfig.APP_DISPLAY_NAME} · 本地优先的自动记账")
                val aboutContext = LocalContext.current
                val versionLabel = remember(aboutContext) {
                    runCatching {
                        aboutContext.packageManager.getPackageInfo(aboutContext.packageName, 0).versionName
                    }.getOrDefault("1.0.0")
                }
                Text(
                    "版本 $versionLabel · 完全开源免费、无广告、不收集任何数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { openExternalUrl(aboutContext, DonationConfig.GITHUB_REPO_URL) },
                    modifier = Modifier.padding(top = 10.dp),
                ) { Text("打开 GitHub 仓库") }
                Text(
                    DonationConfig.GITHUB_REPO_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            AppCard {
                SectionTitle("支持开发者", "如果这个小账本帮到了你")
                val donateContext = LocalContext.current
                if (DonationConfig.enabled) {
                    Text(
                        "感谢支持！款项仅用于覆盖开发与维护成本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DonationConfig.channels.forEach { channel ->
                            Button(onClick = {
                                // 只配了链接、没配图片且**未要求弹窗展示** ⇒ 直接跳出 App（少一次点击）。
                                // 支付宝走这条路；微信没有个人版远程收款链接，只能展示二维码让用户扫。
                                // 落地页入口则要求 showLinkInDialog，走弹窗给链接。
                                if (channel.qrResName == null && channel.qrUrl == null &&
                                    channel.url != null && !channel.showLinkInDialog
                                ) {
                                    openExternalUrl(donateContext, channel.url)
                                } else {
                                    donationChannel = channel
                                }
                            }) { Text(channel.displayName) }
                        }
                    }
                } else {
                    // 未配置收款渠道时如实说明，不做"假入口"
                    Text(
                        "本 App 完全免费、开源、无广告，也不收集你的任何数据。\n" +
                            "捐赠渠道尚未配置；如果它帮到了你，去 GitHub 点个 Star 或提个 Issue，就是最好的支持。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                SectionTitle("记账提醒", "自动记账后要不要提醒你一声")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记账时弹通知", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "每自动记录一笔账，发一条系统通知；没授予通知权限则自动跳过",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notifyOnRecord, onCheckedChange = container::setNotifyOnRecord)
                }
                // Android 13+：发通知需运行时权限 POST_NOTIFICATIONS。首次启动会自动弹一次请求；
                // 一旦被拒就**不再死缠**（反复弹会被系统永久拒绝）⇒ 这里保留一个直达系统设置的兜底入口。
                // 低版本系统自动授予、granted 恒为 true，本按钮不出现。
                val notifyPermissionGranted by container.notifyPermission.granted.collectAsState()
                if (!notifyPermissionGranted) {
                    val permContext = LocalContext.current
                    Button(
                        onClick = {
                            if (!container.notifyPermission.launchAppNotificationSettings()) {
                                Toast.makeText(
                                    permContext,
                                    "无法自动打开，请到 系统设置 → 应用 → 通知 手动开启",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Muted),
                    ) { Text("去系统设置开启通知") }
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
                SectionTitle("外观", "换个颜色，或放一张自己的背景")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !darkTheme,
                        onClick = { container.setDarkTheme(false) },
                        label = { Text("浅色") },
                    )
                    FilterChip(
                        selected = darkTheme,
                        onClick = { container.setDarkTheme(true) },
                        label = { Text("夜间黑皮肤") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = { backgroundLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text("选择背景图") }
                    if (backgroundPath != null) {
                        Button(
                            onClick = { container.setBackgroundImagePath(null) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Muted),
                        ) { Text("清除背景") }
                    }
                }
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
                // 条件化：只有用户自己开启并配了地址，才存在"发上云"这件事。
                // 文案逻辑抽成纯函数 aiPrivacyLine()，由 AiPrivacyCopyTest 钉死。
                BulletLine(
                    LedgerIcons.Lock,
                    aiPrivacyLine(appSettings.aiEnabled, appSettings.aiEndpoint),
                )
                // 与 AiKeyVault 的失败语义对齐：密钥解不开时它返回 null（AI 静默回落本地），
                // 不抛异常、不崩。所以必须在这里告诉用户「换机 / 清除密钥容器后要重填」，
                // 否则他只会看到"AI 一直没生效"却找不到原因。
                BulletLine(
                    LedgerIcons.Lock,
                    "API 密钥存于本机并经系统 Keystore 加密；换机或清除密钥容器后需重新填写",
                )
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

    // 捐赠收款码：在「支持开发者」里点渠道后弹出。
    // 资源名解析不到（还没放置收款码图片）时降级为文字说明 —— 不崩溃、不假装能捐。
    donationChannel?.let { channel ->
        val donationContext = LocalContext.current
        val qrResId = remember(channel.id, channel.qrResName) {
            channel.qrResName
                ?.let { donationContext.resources.getIdentifier(it, "drawable", donationContext.packageName) }
                ?: 0
        }
        // 动态二维码：把落地页地址现场画成二维码。内容是网页地址而非收款码本身，
        // 所以换收款方式只需改网页，所有旧版 App 立刻生效。
        val qrUrlBitmap = remember(channel.id, channel.qrUrl) {
            channel.qrUrl?.let { qrBitmapOf(it) }
        }
        // 「只给链接」模式（如「扫码支持」→ 落地页）：不展示任何图片，只在弹窗里给出地址，
        // 由用户自己点「用浏览器打开」——避免"点一下就跳出 App"的突兀感。
        val url = channel.url
        val linkOnly = channel.showLinkInDialog && url != null &&
            channel.qrResName == null && channel.qrUrl == null
        AlertDialog(
            onDismissRequest = { donationChannel = null },
            title = { Text(if (linkOnly) channel.displayName else "${channel.displayName} 收款码") },
            text = {
                Column {
                    when {
                        linkOnly -> {
                            Text(
                                "点击下面的链接，用浏览器打开支持页面：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                url!!,
                                Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LedgerPalette.Positive,
                                textDecoration = TextDecoration.Underline,
                            )
                        }

                        // 动态二维码优先：内容指向可随时修改的落地页
                        qrUrlBitmap != null -> Image(
                            bitmap = qrUrlBitmap.asImageBitmap(),
                            contentDescription = "${channel.displayName}二维码",
                            modifier = Modifier.fillMaxWidth(),
                        )

                        qrResId != 0 -> Image(
                            painter = painterResource(qrResId),
                            contentDescription = "${channel.displayName}收款码",
                            modifier = Modifier.fillMaxWidth(),
                        )

                        else -> Text("该渠道的收款码尚未配置。", style = MaterialTheme.typography.bodyMedium)
                    }
                    channel.hint?.let {
                        Text(
                            it,
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (linkOnly) {
                    TextButton(onClick = {
                        openExternalUrl(donationContext, url!!)
                        donationChannel = null
                    }) { Text("用浏览器打开") }
                } else {
                    TextButton(onClick = { donationChannel = null }) { Text("关闭") }
                }
            },
            dismissButton = if (linkOnly) {
                { TextButton(onClick = { donationChannel = null }) { Text("取消") } }
            } else {
                url?.let { u ->
                    {
                        TextButton(onClick = {
                            openExternalUrl(donationContext, u)
                            donationChannel = null
                        }) { Text("打开链接") }
                    }
                }
            },
        )
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

    // C4：新机输入配对信息的对话框
    if (showTransferInput) {
        AlertDialog(
            onDismissRequest = { showTransferInput = false },
            title = { Text("接收迁移") },
            text = {
                Column {
                    Text("请粘贴旧机显示的配对信息（形如 SSID|口令|token|端口）：")
                    OutlinedTextField(
                        value = transferTicketInput,
                        onValueChange = { transferTicketInput = it },
                        label = { Text("配对信息") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = transferTicketInput.isNotBlank(),
                    onClick = {
                        val ticket = TransferTicket.decode(transferTicketInput.trim())
                        if (ticket != null) {
                            transferStore.startReceive(ticket)
                            showTransferInput = false
                            transferTicketInput = ""
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTransferInput = false }) { Text("取消") }
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

/** 把用户选择的背景图复制到私有目录，返回绝对路径（失败返回 null）。 */
private fun copyImageToPrivate(context: Context, uri: android.net.Uri): String? = runCatching {
    val input = context.contentResolver.openInputStream(uri) ?: return null
    val file = java.io.File(context.filesDir, "background_image.jpg")
    input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
    file.absolutePath
}.getOrNull()

/**
 * 打开外部链接（仓库地址 / 支付宝收钱码链接等）。
 *
 * 失败时把地址直接显示给用户，而不是"点了没反应"——没装浏览器、链接非法、
 * 或目标 App 未安装都会走到这里。绝不抛异常。
 */
private fun openExternalUrl(context: Context, url: String) {
    val ok = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!ok) {
        Toast.makeText(context, "打不开链接，地址：$url", Toast.LENGTH_LONG).show()
    }
}
