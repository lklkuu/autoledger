package com.autoledger.app.notif

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.autoledger.feature.capture.notify.LedgerNotificationListener

/**
 * 通知使用权的检测与跳转（含异常兜底）。
 *
 * 关键事实：Android **不允许**应用用代码直接"授予"通知使用权，
 * 只能拉起系统设置页让用户手动打开。所以"自动弹授权窗口" = 拉起系统设置页（或先弹说明再跳）。
 */
object NotificationAccessManager {

    /** 安全检测：任何系统异常都按"未授权"处理，绝不抛异常导致崩溃。 */
    fun isGranted(context: Context): Boolean = try {
        // 注意：系统没有公开 ENABLED_NOTIFICATION_LISTENERS 常量，必须用字符串字面量。
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val component = ComponentName(context, LedgerNotificationListener::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        flat.split(":")
            .any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    } catch (e: Exception) {
        false
    }

    /**
     * 拉起系统"通知使用权"设置页。
     * @return true 表示成功唤起；false 表示失败（极少见，会给出兜底）。
     */
    fun launchSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        // 兜底 1：部分 ROM 没有"通知使用权"独立入口，退到"应用通知设置"
        try {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e2: Exception) {
            false
        }
    }
}
