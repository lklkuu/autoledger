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

    // ------------------------------------------------------------------ v8：时薪版本数组与 settings 段解耦

    @Test
    fun `legacy single wage object still round trips after the v8 change`() {
        // 老档案只有 `wage` 单对象（没有 `wageVersions`）：必须照旧能导入、照旧能导出。
        // 这条防的是「改用版本表后顺手把老字段删了」——那会让所有存量档案丢时薪。
        val legacy = JSONObject(
            """
            {"wage":{"monthlyNetSalaryMinor":1200000,"payMonthsPerYear":13,
             "monthlyWorkCostMinor":85000,"workDaysPerMonth":21.75,
             "dailyOfficeHours":9.0,"dailyCommuteMinutes":48,"dailyOvertimeHours":0.6},
             "goal":{"targetMinor":12000000,"currentMinor":3000000},"autoMerge":true}
            """.trimIndent(),
        )

        val parsed = legacy.parseAppSettings()
        assertEquals(1_200_000L, parsed.wage.monthlyNetSalaryMinor, "老档案的时薪必须原样导入")

        val reExported = parsed.toSettingsJson()
        assertEquals(
            1_200_000L,
            reExported.getJSONObject("wage").getLong("monthlyNetSalaryMinor"),
            "`wage` 单对象必须继续导出（时薪页初值 / 旧版本导入都依赖它）",
        )
        assertTrue(reExported.has("wage"), "wage 段不得被版本表取代")
    }

    @Test
    fun `settings json never carries the wage version array`() {
        // 版本数组必须放在 payload **根级**（与 platforms 同级）。
        // 若误放进 settings 段，`db.settingsDao().global()?.let { … }` 在设置行缺失时会
        // 把版本数组一起丢掉（见 BackupManager.buildPayload 的注释）。
        val json = sample.toSettingsJson()
        assertFalse(json.has("wageVersions"), "settings 段里不允许出现 wageVersions")
    }
}
