package com.autoledger.app.notif

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 短信读取权限"引导"状态机（需求 1）。
 *
 * 策略（区别于通知使用权的 7 天冷却，短信按需求"每次打开都提示"）：
 * - 首次（未授权且未「不再提醒」）→ 一定提示；
 * - 之后若仍未授权 → **每次**回到前台都提示（[autoPromptCooldownMillis] = 无冷却）；
 * - 用户点「不再提醒」→ 永久不再自动弹（采集箱里始终保留手动入口）；
 * - 一旦检测到已授权 → 立即隐藏，并清除「不再提醒」标记（下次真关了还能提醒）。
 *
 * 公共部分（never-ask / 授权即清标记 / 回前台重判 / markGranted）已上提到 [PermissionPromptGate]，
 * 本类只保留"怎么判定授权"与"提示节奏"。行为与重构前完全一致。
 */
class SmsAccessGate(context: Context) :
    PermissionPromptGate(context, PREFS) {

    override fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** 短信按需求"每次回前台都提示" ⇒ 无冷却。 */
    override fun autoPromptCooldownMillis(): Long = PermissionPromptPolicy.EVERY_FOREGROUND

    companion object {
        private const val PREFS = "autoledger_sms_access"
    }
}
