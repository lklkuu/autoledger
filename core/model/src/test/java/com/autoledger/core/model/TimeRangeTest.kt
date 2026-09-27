package com.autoledger.core.model

import java.time.LocalDate
import java.time.LocalDateTime
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
}
