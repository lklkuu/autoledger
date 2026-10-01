package com.autoledger.app.notif

import android.content.Context

/**
 * 通知使用权"引导"状态机：决定何时该弹出授权提示、何时闭嘴。
 *
 * 防打扰策略（公共骨架见基类 [PermissionPromptGate]，决策内核见 [PermissionPromptPolicy]）：
 * - 首次启动（从未提示过）→ 一定提示；
 * - 之后若仍未授权 → 每 [COOLDOWN_MS]（默认 7 天）最多再提示一次；
 * - 用户点「不再提醒」→ 永久不再自动弹出（但采集箱里始终保留手动入口）；
 * - 一旦检测到已授权 → 立即隐藏提示，并清除"不再提醒"标记（下次真关了还能提醒）。
 */
class NotificationAccessGate(context: Context) :
    PermissionPromptGate(context, PREFS) {

    override fun isGranted(): Boolean = NotificationAccessManager.isGranted(appContext)

    /** 7 天冷却：授权方式是"跳系统设置页"（较打扰），不能每次回前台都弹。 */
    override fun autoPromptCooldownMillis(): Long = COOLDOWN_MS

    /** 用户点「去开启」或「暂不」：记录一次提示，进入冷却期，并隐藏弹窗。 */
    fun markPrompted() {
        recordPrompted()
    }

    fun launchSettings(): Boolean = NotificationAccessManager.launchSettings(appContext)

    companion object {
        private const val PREFS = "autoledger_notif_access"
        private const val COOLDOWN_MS = PermissionPromptPolicy.NOTIFICATION_ACCESS_COOLDOWN_MS
    }
}
