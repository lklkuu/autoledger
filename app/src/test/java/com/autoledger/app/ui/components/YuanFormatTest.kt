package com.autoledger.app.ui.components

import com.autoledger.core.model.Money
import com.autoledger.core.model.formatYuan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「已攒 / 结余」可能为负时的**展示格式**护栏。
 *
 * `Long.yuan()` 默认 `withSign = false`，会把负号吞掉（−1234 分显示成 "12.34"）——
 * 这是历史行为，全站大量调用点依赖它，不能改默认值；
 * 因此「自由」页显示已攒时必须显式传 `withSign = true`，本测试钉死这条约定。
 */
class YuanFormatTest {

    @Test
    fun `default yuan keeps the historical behaviour of dropping the sign`() {
        // 反向护栏：默认值不得被顺手改掉，否则全站金额展示会多出一堆负号
        assertEquals("12.34", (-1_234L).yuan())
        assertEquals("12.34", 1_234L.yuan())
    }

    @Test
    fun `withSign renders the minus for negative amounts`() {
        assertEquals("-12.34", (-1_234L).yuan(withSign = true))
        assertEquals("-3000", (-300_000L).yuan(withSign = true))
    }

    @Test
    fun `withSign leaves positive and zero untouched`() {
        assertEquals("12.34", 1_234L.yuan(withSign = true))
        assertEquals("0", 0L.yuan(withSign = true))
        assertEquals("37500", 3_750_000L.yuan(withSign = true))
    }

    @Test
    fun `zero and negative zero are both plain zero`() {
        // 分单位下没有 −0，0 与极小负值都要落在可读串上
        assertEquals("0", 0L.yuan(withSign = true))
        assertEquals("-0.01", (-1L).yuan(withSign = true))
    }

    // ------------------------------------------------------------ 账单页结余（v8）

    @Test
    fun `negative balance rendered through formatYuan keeps the minus`() {
        // 账单页三处结余（本月 / 年度 / 逐月表）走 `Money(x).formatYuan()`，
        // 因为 `Long.yuan()` 默认吞负号：收 0 支 2317.91 会显示成「+2317.91」，
        // 把亏损读成盈利。这条锁住"负数一定带负号"。
        assertEquals("-2317.91", Money(-231_791L).formatYuan())
        assertEquals("-150", Money(-15_000L).formatYuan())
        assertEquals("-0.01", Money(-1L).formatYuan())
    }

    @Test
    fun `formatYuan and yuan agree on non negative amounts`() {
        // 换格式函数不得改变正数与零的观感（迁移是"同值换符号策略"，不是改格式）
        for (minor in listOf(0L, 1L, 100L, 1_234L, 250_000L, 12_345_678L)) {
            assertEquals(minor.yuan(), Money(minor).formatYuan(), "正值展示必须一致：$minor")
        }
    }
}
