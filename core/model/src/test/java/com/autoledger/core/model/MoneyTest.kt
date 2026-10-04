package com.autoledger.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Money / 金额解析 —— 纯 JVM 单元测试。
 *
 * 覆盖点：
 * 1. 元文本 -> 分 的解析（带 ¥、千分位、负数、空格）
 * 2. 符号约定（负数 = 流出）
 * 3. 展示格式化
 * 4. 边界与已知缺陷
 *
 * 说明：方法名带 `Red_` 前缀的用例是**刻意写红的**——它们断言的是"正确行为"，
 * 当前实现不满足，对应 docs/review/qa-review.md 中登记的缺陷编号。修复后应转为绿色。
 */
class MoneyTest {

    // ------------------------------------------------------------ 解析：正常路径

    @Test
    fun `fromYuan parses plain yuan symbol`() {
        assertEquals(1230L, Money.fromYuan("¥12.30")!!.minor)
    }

    @Test
    fun `fromYuan parses full width symbol`() {
        assertEquals(1230L, Money.fromYuan("￥12.30")!!.minor)
    }

    @Test
    fun `fromYuan parses thousand separators`() {
        assertEquals(123450L, Money.fromYuan("1,234.5")!!.minor)
    }

    @Test
    fun `fromYuan parses negative amount`() {
        assertEquals(-3200L, Money.fromYuan("-32.00")!!.minor)
    }

    @Test
    fun `fromYuan parses integer yuan`() {
        assertEquals(1200L, Money.fromYuan("12")!!.minor)
    }

    @Test
    fun `fromYuan ignores inner spaces`() {
        assertEquals(850L, Money.fromYuan("  8.5 元")!!.minor)
    }

    @Test
    fun `fromYuan keeps two decimal precision for 0_29`() {
        // BigDecimal 路径是精确的，0.29 必须等于 29 分
        assertEquals(29L, Money.fromYuan("0.29")!!.minor)
    }

    @Test
    fun `fromYuan truncates third decimal because regex caps at two`() {
        // 现状固化：正则只允许 1~2 位小数，所以 12.345 取到 12.34
        assertEquals(1234L, Money.fromYuan("12.345")!!.minor)
    }

    @Test
    fun `fromYuan returns null when no number present`() {
        assertNull(Money.fromYuan("本次无金额"))
    }

    @Test
    fun `fromYuan returns null for blank input`() {
        assertNull(Money.fromYuan("   "))
    }

    @Test
    fun `fromYuan default currency is CNY`() {
        assertEquals(Money.DEFAULT_CURRENCY, Money.fromYuan("1.00")!!.currency)
    }

    // ------------------------------------------------------------ 值对象语义

    @Test
    fun `negative minor is outflow`() {
        val m = Money(-100)
        assertEquals(true, m.isOutflow)
        assertEquals(false, m.isInflow)
    }

    @Test
    fun `zero is neither outflow nor inflow`() {
        val m = Money(0)
        assertEquals(false, m.isOutflow)
        assertEquals(false, m.isInflow)
    }

    @Test
    fun `abs and negate`() {
        assertEquals(100L, Money(-100).abs().minor)
        assertEquals(-100L, Money(100).negate().minor)
        assertEquals(100L, Money(100).abs().minor)
    }

    @Test
    fun `formatYuan renders decimals`() {
        assertEquals("12.34", Money(1234).formatYuan())
        assertEquals("0.05", Money(5).formatYuan())
    }

    @Test
    fun `formatYuan drops trailing zeros`() {
        // 现状固化：fen == 0 时只输出整数部分（"12" 而非 "12.00"）。
        // 与 Long.yuan() (Common.kt:40) 行为一致，但列表里会出现 "12" / "12.30" 混排。
        assertEquals("12", Money(1200).formatYuan())
        assertEquals("0", Money(0).formatYuan())
    }

    @Test
    fun `formatYuan always keeps the minus for negative amounts`() {
        // 缺陷 L1 已修：`withSign` 是两分支返回同一空串的死参数，现已整体删除，
        // 「账本格式化必须始终可见符号」由"默认真相"固化为"唯一选项"（见 formatYuan 的 KDoc）。
        // 「去掉负号」这一支**刻意不实现**——需要绝对值请显式 .abs()。
        assertEquals("-12.34", Money(-1234).formatYuan())
        assertEquals("12.34", Money(1234).formatYuan())
        assertEquals("0", Money(0).formatYuan())
    }

    // ------------------------------------------------------------ RED 用例（已知缺陷）

    @Test
    fun `Red_fromYuanDouble must not lose a cent to binary floating point`() {
        // 缺陷 Money.kt:30 —— (0.29 * 100) = 28.999999999999996 -> toLong() = 28
        assertEquals(29L, Money.fromYuanDouble(0.29).minor, "0.29 元必须等于 29 分")
    }

    @Test
    fun `Red_fromYuanDouble handles 1_15`() {
        // 缺陷同上：1.15 * 100 = 114.99999999999999 -> 114
        assertEquals(115L, Money.fromYuanDouble(1.15).minor)
    }

    @Test
    fun `Red_fromYuan must prefer money like token over long digit run`() {
        // 缺陷 Money.kt:20 —— 正则取最左匹配，"订单号12345678" 会被当成金额（12345678.00 元）。
        assertEquals(1230L, Money.fromYuan("订单号12345678 支付12.30元")!!.minor)
    }
}
