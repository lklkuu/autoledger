package com.autoledger.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TimeRange —— 统计时间窗边界。
 *
 * 所有 MetricProvider 都靠它取数，边界差一天 = 月度报表少一天，必须锁死。
 * 用固定时间戳（2026-03-15 14:30，本地时区）保证用例可重复。
 */
class TimeRangeTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: Long = LocalDateTime.of(2026, 3, 15, 14, 30, 0).atZone(zone).toInstant().toEpochMilli()
    private val todayStart: Long = LocalDate.of(2026, 3, 15).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `today starts at local midnight`() {
        val r = TimeRange.today(now)
        assertEquals(todayStart, r.startMillis)
        assertEquals(now, r.endInclusiveMillis)
    }

    @Test
    fun `thisMonth starts at first day midnight`() {
        val r = TimeRange.thisMonth(now)
        assertEquals(LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(), r.startMillis)
        assertEquals(now, r.endInclusiveMillis)
    }

    @Test
    fun `thisMonth rolls back to day one even mid month`() {
        val midMonth = LocalDateTime.of(2026, 7, 28, 23, 59, 0).atZone(zone).toInstant().toEpochMilli()
        val r = TimeRange.thisMonth(midMonth)
        assertEquals(7, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(r.startMillis), zone).monthValue)
        assertEquals(1, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(r.startMillis), zone).dayOfMonth)
    }

    @Test
    fun `lastDays(1) equals today window`() {
        assertEquals(TimeRange.today(now).startMillis, TimeRange.lastDays(1, now).startMillis)
    }

    @Test
    fun `lastDays is inclusive of today so it spans n days`() {
        val seven = TimeRange.lastDays(7, now)
        val thirty = TimeRange.lastDays(30, now)
        assertTrue(thirty.startMillis < seven.startMillis, "30 天窗口必须比 7 天窗口更早开始")
        assertTrue(seven.startMillis < now)
    }

    @Test
    fun `start never exceeds end for all day counts`() {
        for (days in 1..31) {
            val r = TimeRange.lastDays(days, now)
            assertTrue(
                r.startMillis <= r.endInclusiveMillis,
                "lastDays($days) 产出非法区间: start=${r.startMillis} > end=${r.endInclusiveMillis}",
            )
        }
    }

    @Test
    fun `window is right closed so future txns are excluded by design`() {
        // 现状固化：endInclusiveMillis = now，晚于 now 的流水（预授权、跨时区账单）不会进统计。
        // 见报告 M14 —— 若产品要求"今天"覆盖整天，这里需改成 end = 次日 0 点 - 1ms。
        val r = TimeRange.today(now)
        val future = LocalDateTime.of(2026, 3, 15, 20, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(future > r.endInclusiveMillis, "今日窗口不覆盖 now 之后的时间")
    }

    // ------------------------------------------------------------ RED 用例（已知缺陷）

    @Test
    fun `Red_lastDays must tolerate non positive day count`() {
        // 缺陷 Stats.kt:71 —— days=0 时 minusDays(-1) 变成「加一天」，start > end 产出负区间，
        // 所有统计静默返回空，没有任何报错。
        val r = TimeRange.lastDays(0, now)
        assertTrue(r.startMillis <= r.endInclusiveMillis, "lastDays(0) 不应产出 start > end 的负区间")
    }

    // ------------------------------------------------------------ monthOf（「发现」页按月查看）

    @Test
    fun `monthOf for a past month covers its last millisecond`() {
        // 右端必须取「次月 1 日 00:00 − 1ms」。查询走 occurredAtMillis BETWEEN（**双闭区间**），
        // 若写成「本月最后一天 00:00」，当月 23:59:59 的流水会被整段漏掉。
        val r = TimeRange.monthOf(YearMonth.of(2026, 9), now)
        val expectedEnd = LocalDateTime.of(2026, 10, 1, 0, 0, 0).atZone(zone).toInstant().toEpochMilli() - 1L
        assertEquals(expectedEnd, r.endInclusiveMillis)
        val lastMoment = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)
            .atZone(zone).toInstant().toEpochMilli()
        assertTrue(lastMoment in r.startMillis..r.endInclusiveMillis, "月末 23:59:59.999 必须落在区间内")
    }

    @Test
    fun `monthOf excludes the first millisecond of the next month`() {
        val r = TimeRange.monthOf(YearMonth.of(2026, 9), now)
        val nextMonthStart = LocalDateTime.of(2026, 10, 1, 0, 0, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertTrue(nextMonthStart > r.endInclusiveMillis, "次月 1 日 00:00 不得落进本月区间")
    }

    @Test
    fun `monthOf for the current month is clamped to now`() {
        // 当前月右端夹 now：未来日期的流水（预授权等）不该混进本月。
        val r = TimeRange.monthOf(YearMonth.of(2026, 3), now)
        assertEquals(
            LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            r.startMillis,
        )
        assertEquals(now, r.endInclusiveMillis)
    }

    @Test
    fun `monthOf handles leap February`() {
        // 2024 是闰年（2 月 29 天）。先 plusMonths(1) 再减 1ms 的写法对闰年自动正确，
        // 无需为 2 月写特例。
        val r = TimeRange.monthOf(YearMonth.of(2024, 2), now)
        val expectedEnd = LocalDateTime.of(2024, 3, 1, 0, 0, 0)
            .atZone(zone).toInstant().toEpochMilli() - 1L
        assertEquals(expectedEnd, r.endInclusiveMillis)
        val leapDay = LocalDateTime.of(2024, 2, 29, 12, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(leapDay in r.startMillis..r.endInclusiveMillis, "闰日 2/29 必须落在区间内")
    }

    @Test
    fun `monthOf crosses year boundary without off by one`() {
        val r = TimeRange.monthOf(YearMonth.of(2025, 12), now)
        assertEquals(
            LocalDate.of(2025, 12, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            r.startMillis,
        )
        val expectedEnd = LocalDateTime.of(2026, 1, 1, 0, 0, 0)
            .atZone(zone).toInstant().toEpochMilli() - 1L
        assertEquals(expectedEnd, r.endInclusiveMillis)
    }
}
