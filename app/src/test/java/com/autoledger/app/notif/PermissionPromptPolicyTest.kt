package com.autoledger.app.notif

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PermissionPromptPolicy] 的纯逻辑护栏（**纯 JVM，无需 Android / Robolectric**）。
 *
 * 覆盖三条最容易被写错、也最容易被"改坏"的边界：
 * 1. **低版本不请求**：`POST_NOTIFICATIONS` 只在 API 33+ 需要运行时请求；
 * 2. **已授予不重复弹 / 拒绝后不死缠**：授权即静默；一次性节奏下提示过就不再自动弹；
 * 3. **冷却到没到**：通知使用权的 7 天冷却要能"到点再弹"，短信则每次回前台都弹。
 *
 * ⚠️ 本文件只锁**决策函数**。门控里"调用 `ContextCompat.checkSelfPermission` / 拉起系统框"
 * 这类 Android 胶水不在纯 JVM 可测范围内（见交付说明的「真机项」）。
 */
class PermissionPromptPolicyTest {

    // 固定"当前时间"，所有时间参数显式传入 ⇒ 结果确定，不依赖真实时钟。
    private val now = 1_700_000_000_000L

    private fun shouldShow(
        isGranted: Boolean = false,
        neverAsk: Boolean = false,
        promptCount: Int = 0,
        lastPromptAtMillis: Long = 0L,
        cooldownMillis: Long = 0L,
    ) = PermissionPromptPolicy.shouldAutoPrompt(
        isGranted = isGranted,
        neverAsk = neverAsk,
        promptCount = promptCount,
        lastPromptAtMillis = lastPromptAtMillis,
        nowMillis = now,
        cooldownMillis = cooldownMillis,
    )

    // ---------------------------------------------------------------- 低版本不请求

    @Test
    fun `post notifications needs a runtime request only on android 13 and above`() {
        // Android 8–12（26–32）：系统自动授予，不该走请求路径
        assertFalse(PermissionPromptPolicy.needsRuntimeRequest(26))
        assertFalse(PermissionPromptPolicy.needsRuntimeRequest(31))
        assertFalse(PermissionPromptPolicy.needsRuntimeRequest(32))
        // Android 13（33）起：必须运行时请求
        assertTrue(PermissionPromptPolicy.needsRuntimeRequest(33))
        assertTrue(PermissionPromptPolicy.needsRuntimeRequest(34))
        assertTrue(PermissionPromptPolicy.needsRuntimeRequest(35))
        assertEquals(33, PermissionPromptPolicy.POST_NOTIFICATIONS_MIN_SDK)
    }

    @Test
    fun `a low version reports granted so the prompt never fires`() {
        // 低版本 isGranted() 恒为 true ⇒ 决策必为"不弹"（对应"低版本不应出现该请求路径"）
        assertFalse(shouldShow(isGranted = true, promptCount = 0))
    }

    // ---------------------------------------------------------------- 已授予 / 首次

    @Test
    fun `a first ever prompt always fires`() {
        assertTrue(shouldShow(promptCount = 0), "从未提示过 ⇒ 首次必弹")
    }

    @Test
    fun `a granted permission never auto prompts`() {
        assertFalse(shouldShow(isGranted = true, promptCount = 0))
        assertFalse(shouldShow(isGranted = true, promptCount = 9, lastPromptAtMillis = now - 365L * 24 * 3600_000))
    }

    // ---------------------------------------------------------------- 不再提醒

    @Test
    fun `never ask silences the prompt forever`() {
        assertFalse(shouldShow(neverAsk = true, promptCount = 0))
        // 即便冷却为 0（本可每次都弹）也不能弹
        assertFalse(shouldShow(neverAsk = true, promptCount = 5, cooldownMillis = PermissionPromptPolicy.EVERY_FOREGROUND))
        // 即便时间过了很久也不能弹
        assertFalse(
            shouldShow(
                neverAsk = true,
                promptCount = 5,
                lastPromptAtMillis = 0L,
                cooldownMillis = PermissionPromptPolicy.NOTIFICATION_ACCESS_COOLDOWN_MS,
            ),
        )
    }

    // ---------------------------------------------------------------- 一次性（POST_NOTIFICATIONS）

    @Test
    fun `a denied notification permission is never auto prompted again`() {
        // 场景：用户拒绝了通知发送权限（首次已提示过 ⇒ count=1）。
        // 之后**永不自动弹系统框**（避免 Android 永久拒绝），改由"去设置"手动兜底。
        assertFalse(
            shouldShow(promptCount = 1, lastPromptAtMillis = now - 1_000L, cooldownMillis = PermissionPromptPolicy.ONE_SHOT),
            "拒绝后不得死缠",
        )
        assertFalse(
            shouldShow(
                promptCount = 1,
                lastPromptAtMillis = now - 30L * 24 * 3600_000, // 过了一个月也不再自动弹
                cooldownMillis = PermissionPromptPolicy.ONE_SHOT,
            ),
        )
    }

    @Test
    fun `re granting the permission is allowed to prompt again since granted clears the never ask flag`() {
        // 语义说明：门控在"授权成功"时会清除 never-ask 标记（见 PermissionPromptGate.markGranted）。
        // 决策层据此：一旦变回未授权（用户在系统里关掉），若从未提示过仍会提示。
        assertTrue(shouldShow(isGranted = false, neverAsk = false, promptCount = 0))
    }

    // ---------------------------------------------------------------- 冷却节奏

    @Test
    fun `notification access re prompts only after its seven day cooldown`() {
        val cd = PermissionPromptPolicy.NOTIFICATION_ACCESS_COOLDOWN_MS
        // 刚提示过 ⇒ 冷却中，不弹
        assertFalse(
            shouldShow(promptCount = 1, lastPromptAtMillis = now - 1_000L, cooldownMillis = cd),
            "刚提示过不得立刻再弹",
        )
        // 差一点点到 7 天 ⇒ 仍不弹
        assertFalse(
            shouldShow(promptCount = 1, lastPromptAtMillis = now - (cd - 1), cooldownMillis = cd),
            "未到冷却期不得弹",
        )
        // 正好到 7 天 ⇒ 弹
        assertTrue(
            shouldShow(promptCount = 1, lastPromptAtMillis = now - cd, cooldownMillis = cd),
            "到点应再提示一次",
        )
        // 远超 7 天 ⇒ 弹
        assertTrue(shouldShow(promptCount = 3, lastPromptAtMillis = now - 10 * cd, cooldownMillis = cd))
    }

    @Test
    fun `sms prompts on every foreground because it has no cooldown`() {
        assertTrue(
            shouldShow(
                promptCount = 1,
                lastPromptAtMillis = now - 1_000L,
                cooldownMillis = PermissionPromptPolicy.EVERY_FOREGROUND,
            ),
            "短信权限无冷却 ⇒ 每次回前台都提示",
        )
        assertTrue(
            shouldShow(
                promptCount = 99,
                lastPromptAtMillis = now,
                cooldownMillis = PermissionPromptPolicy.EVERY_FOREGROUND,
            ),
        )
    }
}
