package com.autoledger.feature.capture.notify

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.feature.capture.CaptureAction
import com.autoledger.feature.capture.CaptureSource
import com.autoledger.feature.capture.PermissionState

/**
 * 「系统通知」采集渠道。
 *
 * 注意它**不需要危险运行时权限**：通知监听走的是 Setting.ACTION_NOTIFICATION_LISTENER_SETTINGS，
 * 由用户在设置页里显式打开，不需要弹权限框，也不受 Google Play 对 SMS 的严格策略限制。
 */
class NotificationCaptureSource : CaptureSource {

    override val id: String = ID
    override val displayName: String = "支付通知自动抓取"
    override val description: String = "在系统设置里放行后，微信 / 支付宝 / 银行 App 的支付到账通知会自动进入账本"
    override val requiredPermissions: List<String> = emptyList()
    override val needsSystemToggle: Boolean = true

    override fun isSupported(context: Context): Boolean = true

    override val actions: List<CaptureAction> = listOf(CaptureAction.OpenSystemSettings())

    override fun permissionState(context: Context): PermissionState =
        if (LedgerNotificationListener.accessGranted(context)) PermissionState.GRANTED else PermissionState.NOT_GRANTED

    override fun statusHint(state: PermissionState): String = when (state) {
        PermissionState.GRANTED -> "已开启，支付通知会自动到账本"
        PermissionState.NOT_GRANTED -> "需要在系统「通知使用权」里允许本 App 读取通知"
        PermissionState.NOT_APPLICABLE -> "无需权限"
    }

    /** 打开系统通知监听授权页 */
    override fun openSystemSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object { const val ID = CaptureSourceIds.NOTIFY }
}
