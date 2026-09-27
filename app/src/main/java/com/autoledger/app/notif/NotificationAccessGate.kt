package com.autoledger.app.notif

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知使用权"引导"状态机：决定何时该弹出授权提示、何时闭嘴。
 *
 * 防打扰策略：
 * - 首次启动（从未提示过）→ 一定提示；
 * - 之后若仍未授权 → 每 [COOLDOWN_MS]（默认 7 天）最多再提示一次；
 * - 用户点「不再提醒」→ 永久不再自动弹出（但采集箱里始终保留手动入口）；
 * - 一旦检测到已授权 → 立即隐藏提示，并清除"不再提醒"标记（下次真关了还能提醒）。
 */
class NotificationAccessGate(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _shouldShowPrompt = MutableStateFlow(false)
    val shouldShowPrompt: StateFlow<Boolean> = _shouldShowPrompt.asStateFlow()

    fun isGranted(): Boolean = NotificationAccessManager.isGranted(appContext)

    /** 应用回到前台时调用：重新检测并决定是否要提示。 */
    fun onAppForeground() {
        if (isGranted()) {
            prefs.edit().remove(KEY_NEVER).apply()
            _shouldShowPrompt.value = false
            return
        }
        if (prefs.getBoolean(KEY_NEVER, false)) {
            _shouldShowPrompt.value = false
            return
        }
        _shouldShowPrompt.value = shouldPromptNow()
    }

    /** 用户点「去开启」或「暂不」：记录一次提示，进入冷却期，并隐藏弹窗。 */
    fun markPrompted() {
        prefs.edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1)
            .apply()
        _shouldShowPrompt.value = false
    }

    /** 用户点「不再提醒」：永久关闭自动弹窗。 */
    fun setNeverAsk() {
        prefs.edit().putBoolean(KEY_NEVER, true).apply()
        _shouldShowPrompt.value = false
    }

    fun launchSettings(): Boolean = NotificationAccessManager.launchSettings(appContext)

    private fun shouldPromptNow(): Boolean {
        val count = prefs.getInt(KEY_COUNT, 0)
        if (count == 0) return true
        val last = prefs.getLong(KEY_LAST, 0L)
        return System.currentTimeMillis() - last >= COOLDOWN_MS
    }

    companion object {
        private const val PREFS = "autoledger_notif_access"
        private const val KEY_COUNT = "prompt_count"
        private const val KEY_LAST = "last_prompt_ms"
        private const val KEY_NEVER = "never_ask"
        private const val COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000L
    }
}
