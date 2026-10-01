package com.autoledger.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.autoledger.app.di.AppContainer
import com.autoledger.app.LedgerApp
import com.autoledger.app.ui.nav.Destination
import com.autoledger.app.ui.screens.CaptureScreen
import com.autoledger.app.ui.screens.CategoryManageScreen
import com.autoledger.app.ui.screens.DashboardScreen
import com.autoledger.app.ui.screens.ExpensesScreen
import com.autoledger.app.ui.screens.FreedomScreen
import com.autoledger.app.ui.screens.HourlyScreen
import com.autoledger.app.ui.screens.InsightsScreen
import com.autoledger.app.ui.screens.MonthlyScreen
import com.autoledger.app.ui.screens.PlatformManageScreen
import com.autoledger.app.ui.screens.RefundScreen
import com.autoledger.app.ui.screens.SettingsScreen
import com.autoledger.app.ui.theme.LedgerIcons
import com.autoledger.app.ui.theme.LedgerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LedgerApp).container
        // 通知点击（冷启动路径）：直接落到通知指定的页面（默认账单）
        handleOpenDestination(intent)
        setContent { AppContent(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 通知点击（App 已在前台路径）：PendingIntent 带 CLEAR_TOP|SINGLE_TOP，走到这里
        setIntent(intent)
        handleOpenDestination(intent)
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时重新检测通知使用权，按冷却策略决定是否再次提示
        (application as LedgerApp).container.notificationAccess.onAppForeground()
        // 短信权限：未授权时每次回到前台都提示（需求 1）
        (application as LedgerApp).container.smsAccess.onAppForeground()
        // 通知发送权限（POST_NOTIFICATIONS，Android 13+）：首次提示一次，之后不再自动弹
        (application as LedgerApp).container.notifyPermission.onAppForeground()
    }

    /**
     * 通知点击的落页处理：extra 里带 [EXTRA_OPEN_DESTINATION]（Destination 的 name），
     * 解析成功就压栈过去；解析失败（脏值）静默忽略，保持默认首页。
     */
    private fun handleOpenDestination(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_OPEN_DESTINATION) ?: return
        val target = runCatching { Destination.valueOf(name) }.getOrNull() ?: return
        (application as LedgerApp).container.nav.navigate(target)
    }

    companion object {
        /** 通知跳转参数：值是 [com.autoledger.app.ui.nav.Destination] 的 name（如 MONTHLY）。 */
        const val EXTRA_OPEN_DESTINATION = "com.autoledger.app.extra.OPEN_DESTINATION"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(container: AppContainer) {
    val darkTheme by container.darkTheme.collectAsState()
    LedgerTheme(darkTheme = darkTheme) {
        AppShell(container)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(container: AppContainer) {
    val nav = container.nav
    val startupError by container.startupError.collectAsState()

    // 启动初始化失败：展示友好错误页而不是白屏/闪退，用户可重试。
    if (startupError != null) {
        StartupErrorScreen(startupError!!) { container.retryInit() }
        return
    }

    // 存储状态告知：一旦"放弃加密、改用明文"，必须让用户看见（当初就是静默降级掩盖了缺陷）
    val storageNotice by container.storageNotice.collectAsState()
    var noticeDismissed by remember { mutableStateOf(false) }

    // 首次启动权限说明（需求 2）
    val showPermissionIntro by container.permissionIntro.shouldShow.collectAsState()
    // 通知使用权引导（首次必弹；之后按冷却/不再提醒策略控制频率）
    val notifContext = LocalContext.current
    val showNotifPrompt by container.notificationAccess.shouldShowPrompt.collectAsState()
    // 通知发送权限（POST_NOTIFICATIONS，Android 13+）：首启说明之后提示一次
    val showNotifyPermissionPrompt by container.notifyPermission.shouldShowPrompt.collectAsState()
    // 短信授权（需求 1：未授权时每次打开都提示）
    val showSmsPrompt by container.smsAccess.shouldShowPrompt.collectAsState()
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) container.smsAccess.markGranted() }
    // 通知发送权限请求（与上面短信的写法对称，便于后人对照维护）。
    // 由于发起请求前已 markRequested()，这里只在"授予成功"时清 never-ask 标记；拒绝则维持不再自动弹。
    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) container.notifyPermission.markGranted() }

    // 同一时刻只弹一个，优先级：存储告知 > 权限说明 > 通知发送权限 > 通知使用权 > 短信授权
    when {
        storageNotice != null && !noticeDismissed -> {
            AlertDialog(
                onDismissRequest = { noticeDismissed = true },
                title = { Text(storageNotice!!.title) },
                text = { Text(storageNotice!!.message, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(onClick = { noticeDismissed = true }) { Text("我知道了") }
                },
            )
        }

        showPermissionIntro -> {
            AlertDialog(
                onDismissRequest = { container.permissionIntro.markSeen() },
                title = { Text("开始前，说明一下权限") },
                text = {
                    Text(
                        "本 App 会申请以下权限，全部只用于记账、不用于其它目的，数据一律本地加密、不上传：\n\n" +
                            "· 通知使用权：读取微信/支付宝/银行 App 的支付通知，自动记一笔账（不读取其它通知内容）。\n" +
                            "· 短信读取（可选）：扫描银行扣款短信补录，可随时关闭。\n" +
                            "· 网络/热点（仅换机迁移时）：只在两台设备间直连传输你的账本。\n" +
                            "· 通知发送：自动记账后提醒你一声（可在系统设置里关闭）。\n\n" +
                            "以上权限都可拒绝，App 其余功能照常可用。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { container.permissionIntro.markSeen() }) { Text("知道了") }
                },
            )
        }

        showNotifyPermissionPrompt -> {
            AlertDialog(
                onDismissRequest = { container.notifyPermission.markRequested() },
                title = { Text("开启记账提醒") },
                text = {
                    Text(
                        "记账后，本 App 会发一条系统通知，让你知道「刚刚自动记了一笔」。\n\n" +
                            "需要你允许「发送通知」。即使不允许，App 其余功能照常；之后也可到 系统设置 → 通知 里再开。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 先记录"已处理"，再拉起系统框：避免弹框期间 onResume 再次触发引导（闪一下）
                        container.notifyPermission.markRequested()
                        notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("允许") }
                },
                dismissButton = {
                    TextButton(onClick = { container.notifyPermission.markRequested() }) { Text("暂不") }
                },
            )
        }

        showNotifPrompt -> {
            AlertDialog(
                onDismissRequest = { container.notificationAccess.markPrompted() },
                title = { Text("开启自动记账") },
                text = {
                    Text(
                        "开启「通知使用权」后，微信/支付宝/银行的付款通知会自动进入账本，全程本地加密、不上传。该开关只在本机生效。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val ok = container.notificationAccess.launchSettings()
                        if (!ok) {
                            Toast.makeText(
                                notifContext,
                                "无法自动打开，请到 设置 → 通知 → 通知使用权 手动开启",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        container.notificationAccess.markPrompted()
                    }) { Text("去开启") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { container.notificationAccess.markPrompted() }) { Text("暂不") }
                        TextButton(onClick = { container.notificationAccess.setNeverAsk() }) { Text("不再提醒") }
                    }
                },
            )
        }

        showSmsPrompt -> {
            AlertDialog(
                onDismissRequest = { container.smsAccess.onAppForeground() },
                title = { Text("开启短信识别") },
                text = {
                    Text(
                        "开启「短信读取」后，银行扣款短信会自动补录成账（可选）。\n\n" +
                            "若选择「不再提醒」，可随时到 采集箱 → 银行短信识别 手动授权。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { smsPermissionLauncher.launch(Manifest.permission.READ_SMS) }) { Text("去授权") }
                },
                dismissButton = {
                    TextButton(onClick = { container.smsAccess.setNeverAsk() }) { Text("不再提醒") }
                },
            )
        }
    }

    // 观察返回栈：stack 是 SnapshotStateList，改动即触发重组
    val current = nav.stack.last()
    val stackSize: Int = nav.stack.size

    // 返回栈里超过一屏时先返回上一屏；只有一屏时交给系统退出
    BackHandler(enabled = stackSize > 1) { nav.back() }

    // 自定义背景图（低透明度，不干扰组件显示）
    val backgroundPath by container.backgroundImagePath.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
        topBar = {
            TopAppBar(
                title = { Text(current.label) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    IconButton(onClick = { nav.navigate(Destination.CAPTURE) }) {
                        Icon(LedgerIcons.Notifications, "采集箱")
                    }
                    IconButton(onClick = { nav.navigate(Destination.SETTINGS) }) {
                        Icon(LedgerIcons.Settings, "设置")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Destination.PRIMARY.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == current,
                        onClick = { nav.navigate(destination) },
                        icon = { Icon(destination.icon, null) },
                        label = { Text(destination.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            // 自定义背景图（低透明度，垫在最底层）
            backgroundPath?.let { path ->
                val bmp = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.12f,
                    )
                }
            }
            when (current) {
                Destination.DASHBOARD -> DashboardScreen(container)
                Destination.HOURLY -> HourlyScreen(container)
                Destination.EXPENSES -> ExpensesScreen(container)
                Destination.MONTHLY -> MonthlyScreen(container)
                Destination.FREEDOM -> FreedomScreen(container)
                Destination.INSIGHTS -> InsightsScreen(container)
                Destination.CAPTURE -> CaptureScreen(container)
                Destination.SETTINGS -> SettingsScreen(container)
                Destination.CATEGORY -> CategoryManageScreen(container)
                Destination.REFUND -> RefundScreen(container)
                Destination.PLATFORM -> PlatformManageScreen(container)
            }
        }
    }
}

@Composable
private fun StartupErrorScreen(error: com.autoledger.app.di.AppContainer.StartupError, onRetry: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("启动初始化失败", style = MaterialTheme.typography.headlineSmall)
            Text(
                error.message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "数据未被改动。点下方「复制错误信息」即可把完整错误发给我们定位，无需去文件目录查找。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val full = buildString {
                        appendLine(error.message)
                        error.cause?.let { appendLine(android.util.Log.getStackTraceString(it)) }
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("AutoLedger 错误", full))
                    Toast.makeText(context, "已复制，去粘贴给开发者即可", Toast.LENGTH_SHORT).show()
                }) { Text("复制错误信息") }
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}
