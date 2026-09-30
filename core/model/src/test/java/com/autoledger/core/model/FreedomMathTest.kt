package com.autoledger.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「自由」页「已攒」口径的纯 JVM 单测。
 *
 * 公式：`已攒 = 到手月薪 − 当月支出 + 当前存款`。
 *
 * 重点覆盖：结果为**正 / 0 / 负**三种情况都不得报错、不得被夹断 ——
 * 「支出 > 月薪 + 存款」是真实状态（这个月在吃老本），夹到 0 会骗人。
 */
class FreedomMathTest {

    // ------------------------------------------------------------------ 基本口径

    @Test
    fun `saved up is salary minus expense plus deposit`() {
        // 月薪 12000 元 − 支出 4500 元 + 存款 30000 元 = 37500 元
        val saved = FreedomMath.savedUpMinor(
            monthlyNetSalaryMinor = 1_200_000L,
            monthlyExpenseMinor = 450_000L,
            currentDepositMinor = 3_000_000L,
        )
        assertEquals(3_750_000L, saved, "12000 − 4500 + 30000 = 37500 元")
    }

    @Test
    fun `salary alone with no expense and no deposit`() {
        assertEquals(
            1_200_000L,
            FreedomMath.savedUpMinor(1_200_000L, 0L, 0L),
            "边界：支出与存款都为 0 时就是月薪本身",
        )
    }

    // ------------------------------------------------------------------ 0 与负值（不得报错、不得夹断）

    @Test
    fun `exactly zero is returned as zero not clamped`() {
        // 月薪 8000 = 支出 8000，存款 0 → 恰好 0
        assertEquals(0L, FreedomMath.savedUpMinor(800_000L, 800_000L, 0L))
    }

    @Test
    fun `negative result is preserved instead of clamped to zero`() {
        // 支出 15000 > 月薪 12000 + 存款 0 → −3000 元（在吃老本）
        val saved = FreedomMath.savedUpMinor(1_200_000L, 1_500_000L, 0L)
        assertEquals(-300_000L, saved, "结果必须保留负号，绝不能 coerceAtLeast(0)")
    }

    @Test
    fun `deposit offsets the deficit but a big deficit stays negative`() {
        // 存款补上一部分仍不够：−3000 + 1000 = −2000 元
        assertEquals(-200_000L, FreedomMath.savedUpMinor(1_200_000L, 1_500_000L, 100_000L))
        // 补够就转正：−3000 + 5000 = +2000 元
        assertEquals(200_000L, FreedomMath.savedUpMinor(1_200_000L, 1_500_000L, 500_000L))
    }

    @Test
    fun `zero salary does not crash`() {
        // 没填月薪：0 − 支出 2000 + 存款 10000 = 8000 元
        assertEquals(800_000L, FreedomMath.savedUpMinor(0L, 200_000L, 1_000_000L))
        // 没填月薪且没存款：−2000 元
        assertEquals(-200_000L, FreedomMath.savedUpMinor(0L, 200_000L, 0L))
    }

    @Test
    fun `all zeros is zero`() {
        assertEquals(0L, FreedomMath.savedUpMinor(0L, 0L, 0L))
    }

    // ------------------------------------------------------------------ 进度 / 还差（都基于已攒）

    @Test
    fun `progress follows the saved up amount`() {
        // 已攒 30000 / 目标 120000 = 25%
        assertEquals(0.25f, FreedomMath.progressOf(3_000_000L, 12_000_000L))
    }

    @Test
    fun `progress is capped at 100 percent once the target is reached`() {
        // 已攒 54000 > 目标 50000：进度必须封顶，不能显示 108%
        assertEquals(1f, FreedomMath.progressOf(5_400_000L, 5_000_000L))
        assertEquals(1f, FreedomMath.progressOf(5_000_000L, 5_000_000L), "刚好达标就是 100%")
        assertEquals(1f, FreedomMath.progressOf(9_999_999L, 1L), "远超目标同样封顶")
    }

    @Test
    fun `progress floor is zero for a negative saved up amount`() {
        // 已攒为负（这个月在吃老本）→ 进度 0，不得出现负进度
        assertEquals(0f, FreedomMath.progressOf(-300_000L, 5_000_000L))
    }

    @Test
    fun `progress is zero when no target is set`() {
        // 目标未填 → 不得除零，也不得显示 NaN
        assertEquals(0f, FreedomMath.progressOf(3_000_000L, 0L))
        assertEquals(0f, FreedomMath.progressOf(3_000_000L, -1L))
    }

    @Test
    fun `remaining shows zero once the target is reached`() {
        assertEquals(0L, FreedomMath.remainingMinor(5_400_000L, 5_000_000L), "已达标 → 还差 0")
        assertEquals(0L, FreedomMath.remainingMinor(5_000_000L, 5_000_000L))
        assertEquals(2_000_000L, FreedomMath.remainingMinor(3_000_000L, 5_000_000L), "未达标 → 还差 20000 元")
    }

    @Test
    fun `remaining grows when the saved up amount is negative`() {
        // 已攒 −3000、目标 5000 → 还差 8000；这里允许差额大于目标，
        // 因为只有**这个展示值**夹 0，已攒本身的负值必须保留（见 savedUpMinor 的注释）。
        assertEquals(8_000_000L, FreedomMath.remainingMinor(-3_000_000L, 5_000_000L))
    }

    // ------------------------------------------------------------------ 口径性质

    @Test
    fun `expense reduces and deposit increases the result monotonically`() {
        val base = FreedomMath.savedUpMinor(1_000_000L, 300_000L, 500_000L)
        val moreExpense = FreedomMath.savedUpMinor(1_000_000L, 400_000L, 500_000L)
        val moreDeposit = FreedomMath.savedUpMinor(1_000_000L, 300_000L, 600_000L)
        assertEquals(base - 100_000L, moreExpense, "支出每多 1000 元，已攒少 1000 元")
        assertEquals(base + 100_000L, moreDeposit, "存款每多 1000 元，已攒多 1000 元")
    }

    @Test
    fun `negative deposit is accepted as is`() {
        // 存款字段允许负数（用户可能记一笔欠款），口径不做额外假设
        assertEquals(700_000L, FreedomMath.savedUpMinor(1_000_000L, 0L, -300_000L))
    }

    @Test
    fun `large values stay exact in minor units`() {
        // 分单位下 Long 足够；这条锁住「没有中途转 Double 导致精度丢失」
        val saved = FreedomMath.savedUpMinor(99_999_999_999L, 1L, 1L)
        assertEquals(99_999_999_999L, saved)
    }
}
