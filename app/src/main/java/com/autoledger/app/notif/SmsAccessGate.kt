package com.autoledger.app.notif

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 短信读取权限"引导"状态机（需求 1）。
 *
 * 策略（区别于通知使用权 7 天冷却，短信按需求"每次打开都提示"）：
 * - 首次（未授权且未「不再提醒」）→ 一定提示；
 * - 之后若仍未授权 → 每次回到前台都提示；
 * - 用户点「不再提醒」→ 永久不再自动弹（采集箱里始终保留手动入口）；
 * - 一旦检测到已授权 → 立即隐藏，并清除「不再提醒」标记（下次真关了还能提醒）。
 */
class SmsAccessGate(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _shouldShowPrompt = MutableStateFlow(false)
    val shouldShowPrompt: StateFlow<Boolean> = _shouldShowPrompt.asStateFlow()

    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** 应用回到前台时调用：未授权且未「不再提醒」则提示。 */
    fun onAppForeground() {
        if (isGranted()) {
            prefs.edit().remove(KEY_NEVER).apply()
            _shouldShowPrompt.value = false
            return
        }
        _shouldShowPrompt.value = !prefs.getBoolean(KEY_NEVER, false)
    }

    /** 用户点「不再提醒」：永久关闭自动弹窗。 */
    fun setNeverAsk() {
        prefs.edit().putBoolean(KEY_NEVER, true).apply()
        _shouldShowPrompt.value = false
    }

    /** 授权成功后调用：隐藏提示并清除「不再提醒」标记。 */
    fun markGranted() {
        prefs.edit().remove(KEY_NEVER).apply()
        _shouldShowPrompt.value = false
    }

    companion object {
        private const val PREFS = "autoledger_sms_access"
        private const val KEY_NEVER = "never_ask"
    }
}
