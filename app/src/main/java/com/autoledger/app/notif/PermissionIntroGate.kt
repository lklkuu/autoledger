package com.autoledger.app.notif

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首次启动的「权限与隐私说明」（需求 2）。
 *
 * 只在真正第一次启动时展示一次，向用户一次性说明本 App 会申请哪些权限、
 * 每项权限的作用，以及"数据全部本地加密、不上传"的隐私立场。
 */
class PermissionIntroGate(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _shouldShow = MutableStateFlow(!prefs.getBoolean(KEY_SEEN, false))
    val shouldShow: StateFlow<Boolean> = _shouldShow.asStateFlow()

    fun markSeen() {
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
        _shouldShow.value = false
    }

    companion object {
        private const val PREFS = "autoledger_permission_intro"
        private const val KEY_SEEN = "seen"
    }
}
