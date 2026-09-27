package com.autoledger.app.ui.stores

import com.autoledger.core.model.Category
import com.autoledger.core.model.CategoryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 分类管理纯逻辑：草稿校验、落库映射、分组排序。 */
class CategoryDraftTest {

    // ---------------------------------------------------------- validate

    @Test
    fun `validate rejects blank name`() {
        assertNotNull(CategoryDraft(name = "   ").validate())
    }

    @Test
    fun `validate rejects overly long name`() {
        assertNotNull(CategoryDraft(name = "一二三四五六七八九十十一十二十三").validate())
    }

    @Test
    fun `validate rejects negative budget`() {
        assertNotNull(CategoryDraft(name = "餐饮", budgetYuan = "-1").validate())
    }

    @Test
    fun `validate rejects non numeric budget`() {
        assertNotNull(CategoryDraft(name = "餐饮", budgetYuan = "abc").validate())
    }

    @Test
    fun `validate accepts a name without budget`() {
        assertNull(CategoryDraft(name = "餐饮").validate())
    }

    @Test
    fun `validate accepts a non negative budget`() {
        assertNull(CategoryDraft(name = "餐饮", budgetYuan = "300").validate())
    }

    // ---------------------------------------------------------- toCategory

    @Test
    fun `toCategory trims name and maps budget yuan to minor`() {
        val category = CategoryDraft(name = "  餐饮  ", kind = CategoryKind.EXPENSE, budgetYuan = "300")
            .toCategory(newId = "cat_x")
        assertEquals("cat_x", category.id)
        assertEquals("餐饮", category.name)
        assertEquals(30_000L, category.monthlyBudgetMinor)
        assertEquals(CategoryKind.EXPENSE, category.kind)
    }

    @Test
    fun `toCategory leaves budget null when blank`() {
        assertNull(CategoryDraft(name = "交通").toCategory(newId = "cat_t").monthlyBudgetMinor)
    }

    @Test
    fun `toCategory preserves existing builtIn sortOrder and archived`() {
        val existing = Category(
            id = "cat_food", name = "餐饮", iconKey = "receipt", colorHex = "#D95F5F",
            sortOrder = 7, builtIn = true, kind = CategoryKind.EXPENSE, archived = true,
        )
        val edited = CategoryDraft.from(existing).copy(name = "吃喝").toCategory(existing)
        assertEquals("cat_food", edited.id)
        assertEquals("吃喝", edited.name)
        assertTrue(edited.builtIn)
        assertEquals(7, edited.sortOrder)
        assertTrue(edited.archived)
    }

    // ---------------------------------------------------------- from / 分组

    @Test
    fun `from round trips name kind and budget`() {
        val category = Category(
            id = "cat_x", name = "订阅", iconKey = "clock", colorHex = "#708786",
            kind = CategoryKind.INCOME, monthlyBudgetMinor = 12_345L,
        )
        val draft = CategoryDraft.from(category)
        assertEquals("cat_x", draft.id)
        assertEquals("订阅", draft.name)
        assertEquals(CategoryKind.INCOME, draft.kind)
        assertEquals("123.45", draft.budgetYuan)
    }

    @Test
    fun `groupCategoriesByKind splits and sorts by sortOrder then name`() {
        val list = listOf(
            Category("a", "乙", "receipt", "#000000", sortOrder = 2, kind = CategoryKind.EXPENSE),
            Category("b", "甲", "receipt", "#000000", sortOrder = 1, kind = CategoryKind.EXPENSE),
            Category("c", "工资", "wallet", "#000000", sortOrder = 0, kind = CategoryKind.INCOME),
        )
        val (expense, income) = groupCategoriesByKind(list)
        assertEquals(listOf("b", "a"), expense.map { it.id })
        assertEquals(listOf("c"), income.map { it.id })
    }
}
