package com.autoledger.app.notif

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 各类「权限引导」状态机的**公共骨架**（单一真源，消除三份雷同代码）。
 *
 * 抽出它，是因为 [NotificationAccessGate] / [SmsAccessGate] / [NotificationPermissionGate]
 * 三者的"该不该弹、何时闭嘴"策略**高度重复**：同一套 never-ask 标记、同一套
 * 「一旦授权就清除 never-ask 标记」的易漏策略、同一套每次回前台重判的骨架。
 * 把公共部分收敛到这里后，子类只剩两件**真正不同**的事：
 *  1. [isGranted]：该项权限怎么判定（通知使用权查 `Settings`、短信/通知发送查 `checkSelfPermission`）；
 *  2. [autoPromptCooldownMillis]：在"未授权且未点不再提醒"的前提下，提示的**节奏**
 *     （通知使用权 7 天冷却；短信每次回前台；通知发送一次性）。
 *
 * 决策内核委托给**纯函数** [PermissionPromptPolicy.shouldAutoPrompt]，便于单测。
 *
 * **刻意不放进基类**（避免过度抽象）：拉起系统设置页 / 运行时权限请求的入口
 * （[NotificationAccessGate.launchSettings] / [NotificationPermissionGate.launchAppNotificationSettings]）
 * —— 它们形态差异大（跳设置页 vs 弹系统权限框），硬塞进来反而难懂。
 */
abstract class PermissionPromptGate(
    context: Context,
    prefsName: String,
) {

    protected val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _shouldShowPrompt = MutableStateFlow(false)
    /** 是否应弹出"引导用户去授权"的说明弹窗。 */
    val shouldShowPrompt: StateFlow<Boolean> = _shouldShowPrompt.asStateFlow()

    private val _granted = MutableStateFlow(false)
    /**
     * 该项权限**上一次**检测到的授权状态（由 [onAppForeground] 刷新）。
     * 供 UI 做反应式展示（如设置页据此决定是否显示"去系统设置开启通知"）。
     * 注意：初值为 `false`，首次 [onAppForeground] 前不代表真实状态。
     */
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** 该项权限当前是否已授予（子类各自实现）。 */
    abstract fun isGranted(): Boolean

    /** 提示节奏（冷却毫秒）。见 [PermissionPromptPolicy] 的 `EVERY_FOREGROUND` / `ONE_SHOT` 等常量。 */
    protected abstract fun autoPromptCooldownMillis(): Long

    /**
     * 应用回到前台时调用：重新检测并决策。
     *
     * 三条策略（与 [PermissionPromptPolicy.shouldAutoPrompt] 一致）：
     * - 已授权 → 隐藏提示，并**清除**「不再提醒」标记（下次真被系统关掉还能再提醒）；
     * - 点过「不再提醒」→ 永久隐藏自动弹窗（手动入口仍在）；
     * - 其余 → 按 [autoPromptCooldownMillis] 决定的节奏提示。
     */
    fun onAppForeground() {
        val grantedNow = isGranted()
        _granted.value = grantedNow
        if (grantedNow) clearNeverAskFlag()
        _shouldShowPrompt.value = PermissionPromptPolicy.shouldAutoPrompt(
            isGranted = grantedNow,
            neverAsk = neverAskFlag,
            promptCount = promptCount(),
            lastPromptAtMillis = lastPromptAtMillis(),
            nowMillis = System.currentTimeMillis(),
            cooldownMillis = autoPromptCooldownMillis(),
        )
    }

    /** 用户点「不再提醒」：永久关闭自动弹窗（手动入口仍在）。 */
    fun setNeverAsk() {
        prefs.edit().putBoolean(KEY_NEVER, true).apply()
        _shouldShowPrompt.value = false
    }

    /** 授权成功后调用：隐藏提示并清除「不再提醒」标记。 */
    fun markGranted() {
        clearNeverAskFlag()
        _shouldShowPrompt.value = false
    }

    // ---------------- 供子类实现"节奏 / 记录"所需的持久化原语 ----------------

    /** 已自动提示过的次数（冷却 / 一次性判定用）。 */
    protected fun promptCount(): Int = prefs.getInt(KEY_COUNT, 0)

    /** 上次自动提示的时间戳（冷却判定用）。 */
    protected fun lastPromptAtMillis(): Long = prefs.getLong(KEY_LAST, 0L)

    /** 记录一次"已提示"：刷新时间戳、累加次数，并隐藏弹窗。 */
    protected fun recordPrompted() {
        prefs.edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putInt(KEY_COUNT, promptCount() + 1)
            .apply()
        _shouldShowPrompt.value = false
    }

    private fun clearNeverAskFlag() {
        prefs.edit().remove(KEY_NEVER).apply()
    }

    /** 用户是否已点过「不再提醒」。 */
    protected val neverAskFlag: Boolean
        get() = prefs.getBoolean(KEY_NEVER, false)

    companion object {
        private const val KEY_NEVER = "never_ask"
        private const val KEY_COUNT = "prompt_count"
        private const val KEY_LAST = "last_prompt_ms"
    }
}
