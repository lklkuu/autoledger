package com.autoledger.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autoledger.core.model.BudgetStatus
import com.autoledger.core.model.Category
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI 测试（Robolectric，无需真机 / 模拟器）。
 *
 * 这里跑的是**真实 Compose 渲染 + 真实点击**，断言的是屏幕上真的出现/响应了什么，
 * 而不是「代码存在」。注意：本测试只覆盖不依赖数据库的纯组件；
 * 整屏测试需要数据库（SQLCipher 原生库无法在 JVM 加载），留给真机/模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BudgetCardUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `budget card renders spent budget and remaining`() {
        val statuses = listOf(
            BudgetStatus("c1", "餐饮", "#D95F5F", "receipt", budgetMinor = 100_000L, spentMinor = 40_000L),
        )
        composeRule.setContent { BudgetCard(statuses) }

        composeRule.onNodeWithText("本月预算").assertIsDisplayed()
        composeRule.onNodeWithText("餐饮").assertIsDisplayed()
        composeRule.onNodeWithText("¥400 / ¥1000").assertIsDisplayed()
        composeRule.onNodeWithText("已用 40%").assertIsDisplayed()
        composeRule.onNodeWithText("剩 ¥600").assertIsDisplayed()
    }

    @Test
    fun `over budget category is highlighted`() {
        val statuses = listOf(
            BudgetStatus("c1", "餐饮", "#D95F5F", "receipt", budgetMinor = 100_000L, spentMinor = 130_000L),
        )
        composeRule.setContent { BudgetCard(statuses) }

        composeRule.onNodeWithText("¥1300 / ¥1000").assertIsDisplayed()
        composeRule.onNodeWithText("已超支").assertIsDisplayed()
        composeRule.onNodeWithText("超 ¥300").assertIsDisplayed()
    }

    @Test
    fun `empty budgets shows guidance instead of rows`() {
        composeRule.setContent { BudgetCard(emptyList()) }
        composeRule.onNodeWithText("去「分类管理」给分类设个月度预算").assertIsDisplayed()
    }

    @Test
    fun `category chip click really fires the callback`() {
        var clicks = 0
        val category = Category("c1", "餐饮", "receipt", "#D95F5F")
        composeRule.setContent { CategoryChip(category) { clicks++ } }

        composeRule.onNodeWithText("餐饮").performClick()

        assertEquals(1, clicks)
    }
}
