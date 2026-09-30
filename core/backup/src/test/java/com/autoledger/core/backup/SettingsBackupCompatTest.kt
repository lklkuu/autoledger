package com.autoledger.core.backup

import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.WageProfile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置（时薪参数 / 自由基金目标）备份 JSON 的**字段兼容**护栏。
 *
 * 同样必须跑 Robolectric：`org.json` 在纯 JVM 的 android.jar 里是 stub，
 * 不跑真实实现的话 `put` / `optLong` 全是空操作，测试通过但什么都没验证。
 *
 * 本次改造删掉了「安全垫金额（cushionMinor）」字段，这里锁住三件事：
 *  1. 新导出的档案**不再包含** cushionMinor；
 *  2. 老档案（仍带 cushionMinor）必须**照旧能导入**，多余字段被忽略、不报错；
 *  3. 其余字段（月薪 / 目标 / 当前存款 / autoMerge）往返不失真。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsBackupCompatTest {

    private val sample = AppSettings(
        wage = WageProfile(monthlyNetSalaryMinor = 1_200_000L, payMonthsPerYear = 13),
        goal = FreedomGoal(targetMinor = 12_000_000L, currentMinor = 3_000_000L),
        autoMerge = false,
    )

    // ------------------------------------------------------------------ 导出侧：字段已下线

    @Test
    fun `exported settings json no longer carries the retired cushionMinor field`() {
        val json = sample.toSettingsJson()
        val goal = json.getJSONObject("goal")
        assertFalse(goal.has("cushionMinor"), "导出侧必须彻底移除 cushionMinor")
        assertTrue(goal.has("targetMinor"))
        assertTrue(goal.has("currentMinor"))
    }

    @Test
    fun `round trip keeps every live field`() {
        val back = sample.toSettingsJson().parseAppSettings()
        assertEquals(1_200_000L, back.wage.monthlyNetSalaryMinor)
        assertEquals(13, back.wage.payMonthsPerYear)
        assertEquals(12_000_000L, back.goal.targetMinor)
        assertEquals(3_000_000L, back.goal.currentMinor)
        assertEquals(false, back.autoMerge)
    }

    // ------------------------------------------------------------------ 导入侧：老档案必须能进

    @Test
    fun `legacy settings json with cushionMinor still imports without throwing`() {
        // 老档案：goal 里仍带已下线的 cushionMinor
        val legacy = JSONObject().apply {
            put("wage", JSONObject().apply {
                put("monthlyNetSalaryMinor", 800_000L)
                put("payMonthsPerYear", 12)
            })
            put("goal", JSONObject().apply {
                put("targetMinor", 5_000_000L)
                put("cushionMinor", 560_000L) // ← 已下线字段，必须被忽略
                put("currentMinor", 1_000_000L)
            })
            put("autoMerge", true)
        }

        val parsed = legacy.parseAppSettings()

        assertEquals(5_000_000L, parsed.goal.targetMinor, "目标金额必须原样导入")
        assertEquals(1_000_000L, parsed.goal.currentMinor, "当前存款必须原样导入")
        assertEquals(800_000L, parsed.wage.monthlyNetSalaryMinor)
        assertEquals(12, parsed.wage.payMonthsPerYear)
        assertTrue(parsed.autoMerge)
    }

    @Test
    fun `import tolerates a missing goal block entirely`() {
        // 更老的 / 被裁剪过的档案：整段 settings 缺失也不得抛异常
        val bare = JSONObject().put("autoMerge", false)
        val parsed = bare.parseAppSettings()
        assertEquals(FreedomGoal().targetMinor, parsed.goal.targetMinor)
        assertEquals(FreedomGoal().currentMinor, parsed.goal.currentMinor)
        assertFalse(parsed.autoMerge)
    }

    @Test
    fun `import tolerates unknown extra fields`() {
        // 前向兼容：将来新增字段时，旧版本 App 打开新档案也不应崩溃
        val future = sample.toSettingsJson().apply {
            put("goal", getJSONObject("goal").put("somethingNew", 42))
        }
        val parsed = future.parseAppSettings()
        assertEquals(12_000_000L, parsed.goal.targetMinor)
        assertEquals(3_000_000L, parsed.goal.currentMinor)
    }
}
