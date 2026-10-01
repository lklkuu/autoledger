package com.autoledger.app.notif

/**
 * 权限引导的**纯决策函数**（**零 Android 依赖**，可在纯 JVM 单测里穷举）。
 *
 * 之所以把"该不该弹"从 Android 里剥出来单独成对象：现有三种引导
 * （[NotificationAccessGate] / [SmsAccessGate] / [NotificationPermissionGate]）的节奏
 * 只是同一套公式的参数不同，而 Android 侧的 `Context` / `SharedPreferences` 在无 Robolectric 的
 * 纯 JVM 单测里不可用。把决策抽成纯函数后，"低版本不请求 / 已授予不再弹 / 拒绝后不死缠 / 冷却到没到"
 * 这些**最容易写错**的边界都能被确定性地单测覆盖。
 *
 * 单一真源：本对象是"提示节奏"这一配置的**唯一**出处，各 gate 只引用这里的常量，不再各写各的。
 */
object PermissionPromptPolicy {

    /** Android 13（API 33）起，发送通知需要运行时权限 `POST_NOTIFICATIONS`。 */
    const val POST_NOTIFICATIONS_MIN_SDK: Int = 33

    /**
     * 一次性节奏：提示过一次后，`now - last` 恒远小于 [Long.MAX_VALUE] ⇒ 永不自动再提示。
     * 用于系统权限框（反复弹会被 Android 永久拒绝，只能弹一次）。
     */
    const val ONE_SHOT: Long = Long.MAX_VALUE

    /** 无冷却：只要未授权且未「不再提醒」，每次回到前台都提示（短信权限按需求如此）。 */
    const val EVERY_FOREGROUND: Long = 0L

    /** 通知使用权的 7 天冷却：它是"跳系统设置页"（较打扰），不能每次回前台都弹。 */
    const val NOTIFICATION_ACCESS_COOLDOWN_MS: Long = 7L * 24 * 60 * 60 * 1000L

    /**
     * `POST_NOTIFICATIONS` 是否需要**运行时请求**。
     *
     * - `< 33`：系统**自动授予** ⇒ 不该走请求路径（低版本永不弹、永不请求）；
     * - `>= 33`：需运行时请求。
     */
    fun needsRuntimeRequest(sdkInt: Int): Boolean = sdkInt >= POST_NOTIFICATIONS_MIN_SDK

    /**
     * 此刻是否应**自动**弹出引导弹窗。
     *
     * @param isGranted 已授权 ⇒ 永远不弹（调用方据此清除 never-ask 标记）
     * @param neverAsk 用户点过「不再提醒」⇒ 永远不弹（手动入口仍在）
     * @param promptCount 历史自动提示次数（`<= 0` = 从未提示 ⇒ 首次必弹）
     * @param lastPromptAtMillis 上次自动提示的时间戳
     * @param nowMillis 当前时间
     * @param cooldownMillis 冷却：见 [EVERY_FOREGROUND] / [ONE_SHOT] / [NOTIFICATION_ACCESS_COOLDOWN_MS]
     *
     * ⚠️ 三个 `Long` 时间参数**刻意显式传入**（而非内部取 `System.currentTimeMillis()`），
     * 就是为了让"冷却到没到"这件事可被单测注入时间、确定性地验证。
     */
    fun shouldAutoPrompt(
        isGranted: Boolean,
        neverAsk: Boolean,
        promptCount: Int,
        lastPromptAtMillis: Long,
        nowMillis: Long,
        cooldownMillis: Long,
    ): Boolean {
        if (isGranted) return false
        if (neverAsk) return false
        if (promptCount <= 0) return true
        return nowMillis - lastPromptAtMillis >= cooldownMillis
    }
}
