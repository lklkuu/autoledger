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
}
