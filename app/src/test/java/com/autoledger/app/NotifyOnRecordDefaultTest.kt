package com.autoledger.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NotifyOnRecordDefault] 的纯逻辑护栏（**纯 JVM**）。
 *
 * 核心是那条**最容易在"改默认值"时踩的坑**：不能让新默认值把用户**主动关掉**的开关重新打开。
 * 三态由调用方用 `SharedPreferences.contains` 无损取到后传进来，本用例锁死三态映射。
 */
class NotifyOnRecordDefaultTest {

    @Test
    fun `a fresh install with no stored value defaults to on`() {
        // 键不存在（从没设置过）⇒ 跟随默认 = 开
        assertTrue(NotifyOnRecordDefault.resolve(null), "无任何记录时默认开")
        assertTrue(NotifyOnRecordDefault.DEFAULT_ENABLED, "默认值本身必须是 true")
    }

    @Test
    fun `a user who explicitly turned it off stays off and is never resurrected by the new default`() {
        // ★ 本次核心护栏：用户主动关过 → 落盘 false → 升级后保持关，绝不被新默认值覆盖。
        assertFalse(
            NotifyOnRecordDefault.resolve(false),
            "用户主动关过的开关必须保持关闭（不得被新的默认 true 打脸）",
        )
    }

    @Test
    fun `a user who explicitly turned it on stays on`() {
        assertTrue(NotifyOnRecordDefault.resolve(true))
    }
}
