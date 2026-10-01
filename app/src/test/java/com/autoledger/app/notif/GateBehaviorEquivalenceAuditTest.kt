package com.autoledger.app.notif

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.autoledger.feature.capture.notify.LedgerNotificationListener
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * QA 独立复验：`feadfc8` 把三套门控**上提到共同基类** `PermissionPromptGate` 后，
 * `NotificationAccessGate` / `SmsAccessGate` 的**对外行为是否真的没变**。
 *
 * ## 做法（不靠读码，靠跑真类 + 真 SharedPreferences）
 * 用 Robolectric 起真 `Application`，实例化**真实门控**，并用真实 `SharedPreferences` 驱动状态；
 * 期望值全部取自 `feadfc8^`（重构前）那两个类的**原始实现逐行推导**出的真值表：
 *
 * - `NotificationAccessGate`（旧）：granted⇒清除 never-ask 且不弹；never-ask⇒不弹；
 *   否则 `count==0 ? 弹 : (now-last ≥ 7d ? 弹 : 不弹)`。
 * - `SmsAccessGate`（旧）：granted⇒清除 never-ask 且不弹；never-ask⇒不弹；否则**恒弹**。
 *
 * ## 另外单独验证的三件"最容易在重构里丢"的事
 * 1. **prefs 文件名与键名不变**（否则老用户的 never-ask / 冷却记录会读不到 = 行为突变）；
 * 2. **"一旦授权即清除 never-ask"** 仍然生效（下次真被系统关掉还能再提醒）；
 * 3. 新增的 `NotificationPermissionGate` 在 **API<33 恒不弹**、**API≥33 一次性**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GateBehaviorEquivalenceAuditTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetSecureSettings() {
        // 每个用例前把"通知使用权"清空，避免用例间串味。
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
    }

    // ------------------------------------------------------------------ 夹具

    private fun grantNotificationAccess() {
        val flat = ComponentName(app, LedgerNotificationListener::class.java).flattenToString()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", flat)
    }

    private fun revokeNotificationAccess() {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
    }

    private fun notifPrefs() = app.getSharedPreferences("autoledger_notif_access", Context.MODE_PRIVATE)
    private fun smsPrefs() = app.getSharedPreferences("autoledger_sms_access", Context.MODE_PRIVATE)
    private fun permPrefs() = app.getSharedPreferences("autoledger_notify_permission", Context.MODE_PRIVATE)

    private fun eightDaysAgo() = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000L

    // ================================================== 一、NotificationAccessGate（7 天冷却）等价性

    @Test
    fun `notif access - first ever foreground with no record prompts`() {
        revokeNotificationAccess()
        val gate = NotificationAccessGate(app)
        gate.onAppForeground()
        assertTrue(gate.shouldShowPrompt.value, "从未提示过 ⇒ 首次必弹（与重构前一致）")
    }

    @Test
    fun `notif access - after prompting now it stays silent (cooldown)`() {
        revokeNotificationAccess()
        val gate = NotificationAccessGate(app)
        gate.markPrompted()
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "刚提示过 ⇒ 冷却期内不弹（与重构前一致）")
    }

    @Test
    fun `notif access - prompts again once the seven day cooldown elapsed`() {
        revokeNotificationAccess()
        notifPrefs().edit().putInt("prompt_count", 1).putLong("last_prompt_ms", eightDaysAgo()).commit()
        val gate = NotificationAccessGate(app)
        gate.onAppForeground()
        assertTrue(gate.shouldShowPrompt.value, "距上次提示已 >7 天 ⇒ 再弹一次（与重构前一致）")
    }

    @Test
    fun `notif access - granted clears never ask and stays silent, then can prompt again after revoke`() {
        revokeNotificationAccess()
        val gate = NotificationAccessGate(app)
        gate.setNeverAsk()
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "never-ask ⇒ 不弹")
        assertTrue(notifPrefs().getBoolean("never_ask", false), "never-ask 应落盘到旧键名")

        grantNotificationAccess()
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "已授权 ⇒ 不弹")
        assertFalse(notifPrefs().getBoolean("never_ask", false), "★一旦授权 ⇒ 必须清除 never-ask 标记")

        revokeNotificationAccess()
        gate.onAppForeground()
        assertTrue(gate.shouldShowPrompt.value, "标记已清、count 仍为 0 ⇒ 又能提示（与重构前一致）")
    }

    // ================================================== 二、SmsAccessGate（每次回前台）等价性

    @Test
    fun `sms - not granted prompts on every foreground`() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_SMS)
        val gate = SmsAccessGate(app)
        repeat(3) {
            gate.onAppForeground()
            assertTrue(gate.shouldShowPrompt.value, "短信无冷却 ⇒ 未授权时每次回前台都弹（与重构前一致）")
        }
    }

    @Test
    fun `sms - granted stays silent and never ask persists`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        val gate = SmsAccessGate(app)
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "已授权 ⇒ 不弹")
    }

    @Test
    fun `sms - granted clears never ask, revoke prompts again`() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_SMS)
        val gate = SmsAccessGate(app)
        gate.setNeverAsk()
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "never-ask ⇒ 不弹")
        assertTrue(smsPrefs().getBoolean("never_ask", false), "never-ask 应落盘到旧键名")

        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        gate.onAppForeground()
        assertFalse(smsPrefs().getBoolean("never_ask", false), "★一旦授权 ⇒ 清除 never-ask")

        shadowOf(app).denyPermissions(Manifest.permission.READ_SMS)
        gate.onAppForeground()
        assertTrue(gate.shouldShowPrompt.value, "未授权且未 never-ask ⇒ 弹")
    }

    // ================================================== 三、NotificationPermissionGate（本批新增）

    @Test
    fun `notify permission - api 33 not granted prompts once, then never auto prompts again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val gate = NotificationPermissionGate(app)
        gate.onAppForeground()
        assertTrue(gate.shouldShowPrompt.value, "API33+ 未授权 ⇒ 首次提示一次")

        gate.markRequested() // 用户在弹窗上点了「允许/暂不」或被划走
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "已处理过一次 ⇒ 不再自动弹（拒绝后不死缠）")

        // 即便过了一个月也不再自动弹（一次性节奏）
        permPrefs().edit().putLong("last_prompt_ms", System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L)
            .putInt("prompt_count", 1).commit()
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "一次性节奏 ⇒ 30 天后也不自动弹")
    }

    @Test
    fun `notify permission - granted clears never ask and stays silent`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val gate = NotificationPermissionGate(app)
        gate.setNeverAsk()
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value)
        assertFalse(permPrefs().getBoolean("never_ask", false), "已授权 ⇒ 清除 never-ask")
    }

    @Test
    @Config(sdk = [32])
    fun `notify permission - android 12 and below is considered granted and never prompts`() {
        // 低版本系统自动授予 ⇒ isGranted 恒 true ⇒ 永不弹、永不请求（与 needsRuntimeRequest 口径一致）。
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) // 故意"撤权"，低版本也应无视
        val gate = NotificationPermissionGate(app)
        assertTrue(gate.isGranted(), "API<33 应视为已授予")
        gate.onAppForeground()
        assertFalse(gate.shouldShowPrompt.value, "低版本永不弹")
    }

    // ================================================== 四、键名 / 文件名不变（迁移安全）

    @Test
    fun `prefs file names and key names are unchanged so existing records still load`() {
        // 这些字面量取自 feadfc8^ 旧实现；改动它们等于让老用户的 never-ask/冷却记录失效。
        val notif = NotificationAccessGate(app)
        revokeNotificationAccess()
        notif.markPrompted()
        notif.setNeverAsk()
        val np = app.getSharedPreferences("autoledger_notif_access", Context.MODE_PRIVATE)
        assertTrue(np.getBoolean("never_ask", false), "键名必须仍是 never_ask")
        assertTrue(np.getInt("prompt_count", 0) >= 1, "键名必须仍是 prompt_count")
        assertTrue(np.getLong("last_prompt_ms", 0L) > 0L, "键名必须仍是 last_prompt_ms")

        val sms = SmsAccessGate(app)
        sms.setNeverAsk()
        assertTrue(smsPrefs().getBoolean("never_ask", false), "短信键名必须仍是 never_ask")
    }
}
