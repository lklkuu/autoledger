package com.autoledger.core.backup

import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.WageVersion
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 时薪按月版本在**备份档案**里的编解码护栏（v8 新增）。
 *
 * 必须跑 Robolectric：`org.json` 在纯 JVM 的 android.jar 里是 stub，
 * 不跑真实实现的话 `put` / `optLong` 全是空操作，测试通过但什么都没验证。
 *
 * 四条约定：
 *  1. 导出必须带**全量**参数（含工时参数与落库的 `monthlyIncomeMinor`）——
 *     恢复快照后历史月份的收入要逐分一致，不能靠"用某个时薪重新推算"；
 *  2. 旧档案（没有 `wageVersions` 键）解析成**空列表**，绝不抛异常；
 *  3. 脏行（月份非法）**丢弃**而不是抛错 —— 脏数据不能崩 App；
 *  4. 往返（导出 → 解析）不失真。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsJsonWageVersionsTest {

    private fun version(
        month: String,
        salary: Long = 1_200_000L,
        payMonths: Int = 13,
        workDays: Double = 21.75,
        commute: Int = 48,
        createdAt: Long = 1_700_000_000_000L,
        updatedAt: Long = createdAt,
    ): WageVersion = WageVersion.of(
        month = month,
        profile = WageProfile(
            monthlyNetSalaryMinor = salary,
            payMonthsPerYear = payMonths,
            workDaysPerMonth = workDays,
            dailyCommuteMinutes = commute,
        ),
        nowMillis = updatedAt,
        createdAtMillis = createdAt,
    )

    @Test
    fun `export writes every version with all profile fields`() {
        val versions = listOf(
            version("2026-01", salary = 1_200_000L, payMonths = 13, workDays = 21.75, commute = 48),
            version("2026-05", salary = 1_500_000L, payMonths = 12, workDays = 22.0, commute = 30),
        )

        val json = versions.toWageVersionsJson()

        assertEquals(2, json.length(), "每一行版本都必须导出")
        val first = json.getJSONObject(0)
        assertEquals("2026-01", first.getString("month"))
        assertEquals(1_200_000L, first.getLong("monthlyNetSalaryMinor"))
        assertEquals(13, first.getInt("payMonthsPerYear"))
        assertEquals(85_000L, first.getLong("monthlyWorkCostMinor"))
        assertEquals(21.75, first.getDouble("workDaysPerMonth"))
        assertEquals(9.0, first.getDouble("dailyOfficeHours"))
        assertEquals(48, first.getInt("dailyCommuteMinutes"))
        assertEquals(0.6, first.getDouble("dailyOvertimeHours"))
        // 落库值直接导出，不在导出时重算
        assertEquals(1_300_000L, first.getLong("monthlyIncomeMinor"))
        assertEquals(1_700_000_000_000L, first.getLong("createdAtMillis"))
        assertEquals(1_700_000_000_000L, first.getLong("updatedAtMillis"))

        val second = json.getJSONObject(1)
        assertEquals("2026-05", second.getString("month"))
        assertEquals(22.0, second.getDouble("workDaysPerMonth"), "工时参数随版本走，必须逐行导出")
        assertEquals(30, second.getInt("dailyCommuteMinutes"))
    }

    @Test
    fun `legacy backup without wageVersions parses to an empty list`() {
        // 旧档案的 payload 里根本没有这个键 ⇒ optJSONArray 返回 null ⇒ 空列表，不报错。
        val payload = JSONObject().apply { put("transactions", JSONArray()) }

        val parsed = payload.optJSONArray("wageVersions").parseWageVersions()

        assertTrue(parsed.isEmpty(), "旧档案没有 wageVersions 时必须解析成空列表")
    }

    @Test
    fun `malformed version rows are dropped instead of throwing`() {
        val arr = JSONArray().apply {
            // 合法行（保留）
            put(JSONObject().apply { put("month", "2026-03"); put("monthlyNetSalaryMinor", 1_200_000L) })
            // 月份非法（全部丢弃）
            put(JSONObject().apply { put("month", "2026-13") })
            put(JSONObject().apply { put("month", "2026-1") })
            put(JSONObject().apply { put("month", "") })
            put(JSONObject().apply { put("monthlyNetSalaryMinor", 1L) }) // 连 month 都没有
            // 不是对象（丢弃）
            put("garbage")
            put(JSONObject.NULL)
        }

        val parsed = arr.parseWageVersions()

        assertEquals(1, parsed.size, "只有月份合法的那一条能留下")
        assertEquals("2026-03", parsed.single().month)
    }

    @Test
    fun `round trip keeps monthly income and timestamps`() {
        val original = version(
            month = "2026-05",
            salary = 1_500_000L,
            payMonths = 12,
            workDays = 22.0,
            commute = 30,
            createdAt = 1_690_000_000_000L,
            updatedAt = 1_700_000_000_000L,
        )

        val restored = listOf(original).toWageVersionsJson().parseWageVersions().single()

        assertEquals(original.month, restored.month)
        assertEquals(original.monthlyIncomeMinor, restored.monthlyIncomeMinor, "月度收入必须逐分一致")
        assertEquals(original.createdAtMillis, restored.createdAtMillis, "createdAt 是审计线索，必须保留")
        assertEquals(original.updatedAtMillis, restored.updatedAtMillis)
        assertEquals(original.profile, restored.profile, "全量时薪参数必须往返不失真")
        assertNotNull(restored.profile.workDaysPerMonth)
    }

    @Test
    fun `missing monthly income falls back to the current formula result`() {
        // 手写 / 裁剪过的档案可能只填了参数：不能让该月收入变 0，按当时的公式现算兜底。
        val arr = JSONArray().apply {
            put(JSONObject().apply { put("month", "2026-01"); put("monthlyNetSalaryMinor", 1_200_000L); put("payMonthsPerYear", 13) })
        }

        val restored = arr.parseWageVersions().single()

        assertEquals(1_300_000L, restored.monthlyIncomeMinor)
    }

    @Test
    fun `version json keys never carry secret-like substrings`() {
        // 与 SettingsJsonAiTest 同一道护栏，但覆盖**本次新增**的版本数组：
        // 那里只递归检查 `toSettingsJson()` 的输出，管不到 `toWageVersionsJson()`。
        // 键名里出现 key / token / secret / password / sk- 就会被既有安全用例判失败，
        // 所以本数组的字段一律用 month / profile 语义命名（不得用 monthKey / versionKey）。
        val json = listOf(version("2026-01")).toWageVersionsJson().getJSONObject(0)

        val keys = mutableListOf<String>()
        val it = json.keys()
        while (it.hasNext()) keys += it.next()

        assertEquals(
            listOf(
                "month", "monthlyNetSalaryMinor", "payMonthsPerYear", "monthlyWorkCostMinor",
                "workDaysPerMonth", "dailyOfficeHours", "dailyCommuteMinutes", "dailyOvertimeHours",
                "monthlyIncomeMinor", "createdAtMillis", "updatedAtMillis",
            ).sorted(),
            keys.sorted(),
            "版本行的字段名集合变了就必须同步核对安全护栏用例",
        )
        for (forbidden in listOf("key", "token", "secret", "password", "sk-")) {
            assertTrue(
                keys.none { it.contains(forbidden, ignoreCase = true) },
                "版本行的键名里出现了敏感词：$forbidden（实得 $keys）",
            )
        }
    }
}
