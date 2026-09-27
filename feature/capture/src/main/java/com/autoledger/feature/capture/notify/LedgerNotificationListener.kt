package com.autoledger.feature.capture.notify

import android.app.Notification
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.autoledger.feature.capture.CaptureDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 系统通知监听。
 *
 * 这是**唯一真正接近「全自动」的技术路径**：用户一旦在系统设置里放行，
 * 微信 / 支付宝 / 银行 App 每弹出一笔支付通知，这里就能立刻收到，无需 root、无需 Hook。
 *
 * 工程细节：
 * - Service 回调直接投递到 IO 协程；NotificationListenerService 不提供 BroadcastReceiver.goAsync。
 * - 常驻通知（音乐、外卖进度条）与折叠摘要一律跳过，否则会每天产生几十条噪音。
 */
class LedgerNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parser by lazy { NotificationParser() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        // 常驻 / 进度类通知没有记账价值
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (NotificationCompat.isGroupSummary(notification)) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }.orEmpty()

        val body = listOf(text, bigText, lines).filter { it.isNotBlank() }.toSet().joinToString(" | ")
        if (body.isBlank() && title.isBlank()) return

        val parsed = parser.parse(sbn.packageName, title, body) ?: return

        val envelope = toRawEnvelope(
            sourceId = NotificationCaptureSource.ID,
            sourceRef = sbn.key,
            occurredAtMillis = sbn.postTime,
            rawText = listOf(title, body).filter { it.isNotBlank() }.joinToString("\n"),
            packageName = sbn.packageName,
            parsed = parsed,
        )

        // Service 没有 goAsync；回调仅负责把后续工作投递到受生命周期管理的 IO scope。
        scope.launch { CaptureDispatcher.submit(envelope) }
    }

    override fun onDestroy() {
        // 系统重建监听服务时取消旧任务，避免 scope 与 Service 泄漏。
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 供 UI 判断用户有没有在系统设置里开启本服务。任何系统异常按"未授权"处理，不抛异常。 */
        fun accessGranted(context: android.content.Context): Boolean = try {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            val expected = ComponentName(context, LedgerNotificationListener::class.java).flattenToString()
            val short = ComponentName(context, LedgerNotificationListener::class.java).flattenToShortString()
            flat.split(":").any { it.equals(expected, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }
}
