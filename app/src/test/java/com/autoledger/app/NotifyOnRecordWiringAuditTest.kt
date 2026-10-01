package com.autoledger.app

import android.app.Application
import android.content.Context
import com.autoledger.app.di.AppContainer
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * QA 独立复验：`37c2968` 把「记账提醒」默认值从 off 改成 on 时，
 * **`AppContainer` 的三态接线**（`SharedPreferences.contains` + [NotifyOnRecordDefault.resolve]）
 * 是否真的做到「绝不覆盖用户主动关过的开关」。
 *
 * ## 为什么必须跑到 `AppContainer` 这一层
 * 工程师的 `NotifyOnRecordDefaultTest` 只锁了**纯函数** `resolve(stored)`。
 * 但本批真正的风险在**接线**：调用方有没有用 `contains` 把「键不存在」与「键存在且 false」
 * 如实区分？如果接线写成 `uiPrefs.getBoolean(key, DEFAULT)`，纯函数再对也没用 ——
 * 用户明明关掉的开关会在升级后被默默打开。本文件用**真实 `AppContainer` + 真实 `SharedPreferences`**
 * 端到端验证这条护栏（含"重建容器后仍为关"的模拟重启场景）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotifyOnRecordWiringAuditTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun uiPrefs() = app.getSharedPreferences("autoledger_ui", Context.MODE_PRIVATE)

    @Test
    fun `fresh install with no stored key defaults to on`() {
        uiPrefs().edit().clear().commit()
        val container = AppContainer(app)
        assertTrue(container.notifyOnRecord.value, "从没设置过（键不存在）⇒ 跟随默认 = 开")
    }

    @Test
    fun `a user who explicitly turned it off is never resurrected by the new default`() {
        // ★ 本次核心护栏：模拟"用户曾主动关过"——该键已落盘为 false。
        uiPrefs().edit().clear().putBoolean("notify_on_record", false).commit()
        val container = AppContainer(app)
        assertFalse(
            container.notifyOnRecord.value,
            "用户主动关过（键存在且为 false）⇒ 升级后必须保持关，绝不被新的默认 true 打脸",
        )
    }

    @Test
    fun `a stored true stays true`() {
        uiPrefs().edit().clear().putBoolean("notify_on_record", true).commit()
        val container = AppContainer(app)
        assertTrue(container.notifyOnRecord.value)
    }

    @Test
    fun `turning it off writes the key explicitly and survives a container rebuild`() {
        uiPrefs().edit().clear().commit()
        val first = AppContainer(app)
        first.setNotifyOnRecord(false)
        assertTrue(
            uiPrefs().contains("notify_on_record"),
            "关闭必须**显式落盘**该键，否则无法与「从没设置过」区分",
        )
        // 模拟杀进程重开：重建容器（同一份 SharedPreferences）后仍应为关。
        val rebuilt = AppContainer(app)
        assertFalse(rebuilt.notifyOnRecord.value, "重建容器后仍为关")
    }
}
