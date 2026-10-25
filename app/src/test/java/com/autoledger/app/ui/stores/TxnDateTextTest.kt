package com.autoledger.app.ui.stores

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [isoDateOf] 的纯 JVM 单测（v1.1.14 缺陷：「编辑账单时日期被覆盖成修改当天」）。
 *
 * 背景：`TxnEditDialog` 的日期初值此前用 `LocalDate.ofInstant(Instant, ZoneId)`，
 * 而那是 **API 34** 才有的方法（本项目 `minSdk = 26`、未开 desugaring）。
 * Android 13 及以下会抛 `NoSuchMethodError`，被 `runCatching` 吞掉后
 * `getOrDefault(今天)` 把初值降级成**今天** —— 用户打开历史账单只改备注，
 * 点保存就把「今天 00:00」写回了 `occurredAtMillis`。
 *
 * 这里钉死三件事：
 *  1. 正常时间戳 → 正确的 `yyyy-MM-dd`；
 *  2. 时区参与换算（不是硬编码 UTC / 本地）；
 *  3. 脏时间戳 → `null`，**不抛异常**。
 *     返回 null 而不是「今天」，是为了让 UI 能区分「读不出来」与「用户想改成今天」。
 */
class TxnDateTextTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-17 13:45:00 +08:00 */
    private val thirteenFortyFive = 1_792_215_900_000L

    /** 2026-10-17 00:00:00 +08:00 */
    private val startOfDay = 1_792_166_400_000L

    @Test
    fun `converts epoch millis to the local iso date`() {
        assertEquals("2026-10-17", isoDateOf(thirteenFortyFive, shanghai))
    }

    @Test
    fun `keeps the same calendar day at the very start of the day`() {
        // 边界：不少写法会在这里差一天
        assertEquals("2026-10-17", isoDateOf(startOfDay, shanghai))
        // 往前 1 毫秒 = 前一天 23:59:59.999
        assertEquals("2026-10-16", isoDateOf(startOfDay - 1, shanghai))
    }

    @Test
    fun `uses the given zone instead of hardcoding one`() {
        // 同一瞬间：上海 = 10-17 00:00，UTC = 10-16 16:00
        assertEquals("2026-10-16", isoDateOf(startOfDay, ZoneId.of("UTC")))
    }

    @Test
    fun `dirty timestamps return null instead of blowing up`() {
        // ⚠️ 这两个值**不会**让 java.time 抛异常：±2.9 亿年仍在 LocalDate 的
        // ±999999999 年范围内，atZone 会成功，格式化成 `+292278994-08-17` 这种 14 字符串。
        // 所以 null 来自「4 位年份」这条 UI 契约判据，而不是 try/catch ——
        // 若有人删掉年份护栏，本用例会立刻变红。
        assertNull(isoDateOf(Long.MIN_VALUE, shanghai), "Long.MIN_VALUE 必须是 null，且不得抛异常")
        assertNull(isoDateOf(Long.MAX_VALUE, shanghai), "Long.MAX_VALUE 必须是 null，且不得抛异常")
    }

    @Test
    fun `epoch zero is a valid date and must not be treated as dirty`() {
        // 1970-01-01T08:00 +08:00：0 是**合法**时间戳，不能被当成「读不出来」。
        assertEquals("1970-01-01", isoDateOf(0L, shanghai))
    }

    @Test
    fun `output always fits the yyyy-MM-dd input field`() {
        // 输入框是 10 字符的 `yyyy-MM-dd`：任何非 null 输出都必须是这个形状，
        // 否则用户看到的是一个自己无法重新输入的值。
        listOf(thirteenFortyFive, startOfDay, 0L, 1_700_000_000_000L).forEach { millis ->
            val text = isoDateOf(millis, shanghai)
            assertEquals(10, text?.length, "millis=$millis 输出=$text 必须是 10 字符")
            assertEquals(true, text!!.matches(Regex("""\d{4}-\d{2}-\d{2}""")), "millis=$millis 输出=$text 形状不对")
        }
    }

    // ------------------------------------------------------------ 保存判据（editedOccurredAtOrNull）

    /** 2026-10-17 23:59:59.999 +08:00 —— 一天最末尾，被抹成 0 点或顺延一天都藏不住。 */
    private val lateNight = 1_792_252_799_999L

    @Test
    fun `picking the same calendar day yields null so the original instant survives`() {
        // 原记录 13:45，用户在日期框里重新选了同一天（parseLocalDate 得到当天 00:00）。
        assertNull(
            editedOccurredAtOrNull(parsedDate = startOfDay, originalMillis = thirteenFortyFive, zone = shanghai),
            "同一天 ⇒ null（不改），否则 13:45 会被 atStartOfDay 抹成 00:00",
        )
    }

    @Test
    fun `a trailing space in the date field must not count as a change`() {
        // 用户把 `2026-10-17` 敲成 `2026-10-17 `：`parseLocalDate` 会 trim，
        // 两串解析结果**完全相同**（都是 startOfDay）。比文本会误判成「改了」，
        // 于是把 23:59:59.999 抹成 00:00 —— 这条用例钉的就是这句话。
        val fromTrimmed = startOfDay // "2026-10-17" 解析结果
        val fromTrailingSpace = startOfDay // "2026-10-17 " 解析结果（trim 后同上）
        assertEquals(fromTrimmed, fromTrailingSpace, "前提：两种写法解析结果必须相同")

        assertNull(
            editedOccurredAtOrNull(fromTrailingSpace, lateNight, shanghai),
            "同一天只是多了一个空格 ⇒ 必须判为「没改」",
        )
    }

    @Test
    fun `a genuinely different day is written back`() {
        val nextDay = startOfDay + 86_400_000L
        assertEquals(
            nextDay,
            editedOccurredAtOrNull(parsedDate = nextDay, originalMillis = thirteenFortyFive, zone = shanghai),
            "换了一天就必须写回（值等于那天的 00:00）",
        )
        assertEquals(
            nextDay,
            editedOccurredAtOrNull(parsedDate = nextDay, originalMillis = lateNight, zone = shanghai),
            "从当天最末尾改到次日也必须写回",
        )
    }

    @Test
    fun `an unparseable date field never writes anything`() {
        assertNull(
            editedOccurredAtOrNull(parsedDate = null, originalMillis = thirteenFortyFive, zone = shanghai),
            "解析失败 ⇒ null（纵深防御：即使将来放宽了保存按钮，也不得把非法输入写进库）",
        )
    }

    @Test
    fun `a dirty original timestamp falls back to what the user typed`() {
        // 原值读不出日历日（备份导入的越界时间戳）⇒ 没有「原来那天」可比，
        // 用户既然在空框里填了日期，就用用户的。
        assertEquals(
            startOfDay,
            editedOccurredAtOrNull(parsedDate = startOfDay, originalMillis = Long.MIN_VALUE, zone = shanghai),
            "原值脏 ⇒ 采用用户填的日期",
        )
        assertEquals(
            startOfDay,
            editedOccurredAtOrNull(parsedDate = startOfDay, originalMillis = Long.MAX_VALUE, zone = shanghai),
            "原值脏 ⇒ 采用用户填的日期（MAX 侧同样）",
        )
    }

    @Test
    fun `the day comparison uses the given zone`() {
        // 2026-10-17 07:00 +08:00 == 2026-10-16 23:00 UTC
        val morningShanghai = 1_792_191_600_000L
        // 2026-10-17 08:00 +08:00 == 2026-10-17 00:00 UTC
        val eightShanghai = 1_792_195_200_000L

        // 上海时区：两者都是 10-17 ⇒ 同一天 ⇒ null
        assertNull(
            editedOccurredAtOrNull(eightShanghai, morningShanghai, shanghai),
            "上海时区下 07:00 与 08:00 是同一天",
        )
        // UTC：分别是 10-16 与 10-17 ⇒ 不同天 ⇒ 写回
        // （换成 UTC 结论就变，说明判据确实按传入时区折算，而不是写死某个时区）
        assertEquals(
            eightShanghai,
            editedOccurredAtOrNull(eightShanghai, morningShanghai, ZoneId.of("UTC")),
            "UTC 下跨了零点 ⇒ 应判为不同天",
        )
    }
}
