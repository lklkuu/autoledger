package com.autoledger.app.notif

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 「通知发送」权限（`POST_NOTIFICATIONS`）引导状态机 —— **Android 13 / API 33+ 才需要**。
 *
 * ## 为什么必须单独管它（v1.1.3 线上就存在的准入缺口）
 * `AndroidManifest` 里**声明了** `POST_NOTIFICATIONS`，但全仓**从未在运行期请求**它。
 * 于是 Android 13+ 的新装用户默认**不授予**该权限，后果是：
 *  - 「已自动记一笔账」「记账提醒」这类通知**一条都发不出**；
 *  - 而「已自动记一笔账」恰恰是用户**感知"自动记账在正常工作"的唯一途径** ⇒ 用户以为功能坏了。
 *
 * ## 策略
 * - **低版本（< API 33）**：[isGranted] 恒为 `true`（系统自动授予）⇒ 永不弹、永不请求；
 * - **API 33+ 未授权**：回前台时提示一次（节奏 = [PermissionPromptPolicy.ONE_SHOT] 一次性）；
 * - **拒绝后不死缠**：系统权限框只弹一次；之后改走 [launchAppNotificationSettings] 引导用户去系统设置，
 *   避免反复弹导致 Android 标记 `Don't ask again`（永久拒绝）；
 * - 一旦授权（含用户在系统设置里手动开）→ 立即隐藏并清除「不再提醒」。
 *
 * 公共骨架见 [PermissionPromptGate]，决策内核见 [PermissionPromptPolicy]，与短信/通知使用权两套门控同源。
 */
class NotificationPermissionGate(context: Context) :
    PermissionPromptGate(context, PREFS) {

    /** 低版本系统自动授予；API 33+ 才查真实授权状态。 */
    override fun isGranted(): Boolean {
        if (!PermissionPromptPolicy.needsRuntimeRequest(Build.VERSION.SDK_INT)) return true
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 一次性：首次（`count == 0`）提示，之后**不再自动**弹系统框
     * （[PermissionPromptPolicy.ONE_SHOT] ⇒ 提示过一次后 `now - last >= MAX` 恒为假）。
     */
    override fun autoPromptCooldownMillis(): Long = PermissionPromptPolicy.ONE_SHOT

    /**
     * 用户已处理一次系统请求（点了「允许」发起请求、或点「暂不」跳过，或弹窗被划走）：
     * 记录一次 ⇒ 之后不再自动弹，改由 [launchAppNotificationSettings] 兜底。
     */
    fun markRequested() {
        recordPrompted()
    }

    /**
     * 拉起本 App 的「通知设置」页（拒绝 / 永久拒绝后的手动兜底入口）。
     * @return true 成功唤起；false 失败（极少见，调用方给 Toast 兜底）。
     */
    fun launchAppNotificationSettings(): Boolean = try {
        appContext.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val PREFS = "autoledger_notify_permission"
    }
}
