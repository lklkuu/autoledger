package com.autoledger.app.ui.components

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「月度趋势」节点金额标签 [trendAmountLabel] 的格式护栏。
 *
 * 钉死三件事：
 * 1. **按量级压缩**：节点列宽只有 ≈60dp，完整金额串（`¥1,234.00`）必然溢出，
 *    所以 ≥1 万必须折成「万」、100~1 万必须是整数千分位、<100 元才留小数。
 * 2. **0 元月份必须显式标注**（`¥0`）：留空白会让用户以为"这个月没数据"而不是"没花钱"。
 * 3. **千分位不跟随系统 locale**：项目里踩过阿拉伯语 locale 下数字被本地化、
 *    导致金额解析失败的坑（见 [YuanFormatTest]）。
 */
class TrendAmountLabelTest {

    @Test
    fun `zero is labelled explicitly`() {
        assertEquals("¥0", trendAmountLabel(0L), "0 元月份必须显式标注，不能留空白")
    }

    @Test
    fun `small amounts keep two decimals`() {
        assertEquals("¥28.45", trendAmountLabel(2_845L))
        assertEquals("¥0.01", trendAmountLabel(1L), "分位不能被吞（1 分仍是真实金额）")
        assertEquals("¥99.99", trendAmountLabel(9_999L), "99.99 元仍是两位小数档")
    }

    @Test
    fun `mid range amounts are integers with thousands separators and no decimals`() {
        assertEquals("¥1,000", trendAmountLabel(100_000L))
        assertEquals("¥9,280", trendAmountLabel(928_000L))
        assertEquals("¥100", trendAmountLabel(10_000L), "100 元是「不带小数」档的下界")
        assertEquals("¥9,999", trendAmountLabel(999_999L), "9,999.99 元仍走整数档（上限）")
    }

    @Test
    fun `five digits and above collapse to ten thousands`() {
        assertEquals("¥1.2万", trendAmountLabel(1_234_500L), "12,345 元 = 1.2345 万 ⇒ 1.2万")
        assertEquals("¥1.0万", trendAmountLabel(1_000_000L), "整万元不能被算成 0.1万（量纲护栏）")
        assertEquals("¥12.3万", trendAmountLabel(12_345_000L), "123,450 元 = 12.345 万 ⇒ 12.3万")
        // 9,999,990 元 = 999.999 万 ⇒ 四舍五入到 1000.0万（0.1 万 = 1,000 元，故以千分位量级取整）
        assertEquals("¥1000.0万", trendAmountLabel(999_999_000L))
    }

    @Test
    fun `negative amounts keep the sign in front of the currency symbol`() {
        // 趋势柱本身已 coerceAtLeast(0)，但该函数是可独立复用的格式化函数，负数必须正确。
        assertEquals("¥-28.45", trendAmountLabel(-2_845L))
        assertEquals("¥-1,000", trendAmountLabel(-100_000L))
        assertEquals("¥-1.2万", trendAmountLabel(-1_234_500L))
        assertEquals("¥0", trendAmountLabel(-0L), "没有 −0 这个状态")
    }

    @Test
    fun `thousands separators do not follow the system locale`() {
        val previous = Locale.getDefault()
        try {
            // 阿拉伯语 locale 会把数字本地化（ Eastern Arabic numerals ），
            // 千分位/数字形态随之变化 —— 这正是历史上金额解析失败（toBigDecimalOrNull）的根因。
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("¥1,000", trendAmountLabel(100_000L), "千分位必须固定为 Locale.US 的 ',' 与 ASCII 数字")
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ------------------------------------------------------------ Long 极值（溢出回归护栏）

    @Test
    fun `Long MAX value formats without overflow`() {
        // 曾经的 bug：(absMinor + 50_000) 在接近 MAX_VALUE 时二次溢出回绕成负数，
        // 拼出「¥-9223372036854.-7万」——符号反转 + 小数为负。
        // MAX_VALUE = 9,223,372,036,854,775,807 分 ⇒ 92,233,720,368,547.758 万级。
        val label = trendAmountLabel(Long.MAX_VALUE)
        assertNoIllegalSign(label)
        assertEquals("¥9223372036854.8万", label)
    }

    @Test
    fun `values just below MAX value also stay legal`() {
        // MAX − 1_000 与 MAX − 50_000 都落在「舍入要进位」的边界上，最容易触发加法溢出
        listOf(Long.MAX_VALUE - 1_000L, Long.MAX_VALUE - 50_000L).forEach { value ->
            val label = trendAmountLabel(value)
            assertNoIllegalSign(label)
            assertTrue(label.startsWith("¥"), "应只以货币符号开头：$label")
            assertTrue(label.endsWith("万"), "应落在「万」档：$label")
        }
    }

    @Test
    fun `Long MIN value is saturated and never double signed`() {
        // MIN 取绝对值会溢出 ⇒ 饱和夹取到 MAX_VALUE；负号只应出现一次，且不得有「¥--」
        val label = trendAmountLabel(Long.MIN_VALUE)
        assertNoIllegalSign(label)
        assertFalse(label.contains("--"), "不得出现双负号：$label")
        assertEquals("¥-9223372036854.8万", label, "MIN 饱和夹取后与 MAX 同值，仅多一个负号")
    }

    /** 非法串的共同特征：除至多一个前导负号外不含任何 '-'，且小数部分不得带负号。 */
    private fun assertNoIllegalSign(label: String) {
        val minusCount = label.count { it == '-' }
        assertTrue(minusCount <= 1, "负号至多一个：$label")
        assertFalse(label.contains("--"), "不得出现双负号：$label")
        assertFalse(hasIllegalFractionSign(label), "小数部分不得带负号：$label")
    }

    // ------------------------------------------------------------ 护栏自身的鉴别力（QA T1）

    @Test
    fun `the fraction guard actually rejects a negative fraction`() {
        // 鉴别力钉子：旧护栏写成 substringAfter('.').filter{isDigit()}.startsWith("-")，
        // filter 先滤掉负号 ⇒ 恒 false，`¥123.-4万` 这类真非法串能畅通通过。
        // 这里断言它**必须**被识别为非法，否则护栏又在空转。
        assertTrue(hasIllegalFractionSign("¥123.-4万"), "小数负号必须被识别（护栏不得空转）")
    }

    @Test
    fun `the fraction guard accepts every legal shape`() {
        listOf("¥28.45", "¥-1.2万", "¥9223372036854.8万", "¥0", "¥1,000").forEach { label ->
            assertFalse(hasIllegalFractionSign(label), "$label 是合法串，不应被判非法")
        }
    }

    @Test
    fun `the fraction guard is not fooled by unrelated garbage`() {
        // 无小数点的串：substringAfter 返回空串 ⇒ 合法（护栏只管小数位，不越权judge整体形态）
        assertFalse(hasIllegalFractionSign("¥abc"))
        assertFalse(hasIllegalFractionSign("TotallyGarbage!!!"))
    }
}
