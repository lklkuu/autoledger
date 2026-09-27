package com.autoledger.core.database

import com.autoledger.core.model.Category
import com.autoledger.core.model.CategoryKind

/**
 * 出厂即用的消费分类。
 *
 * 颜色取自参考仪表盘的奶油薄荷色板，保证 App 与目标页面观感一致。
 * 用户可以自由增删改；builtIn=true 的条目不允许真正删除，只能隐藏。
 */
object DefaultSeed {

    fun categories(): List<Category> = listOf(
        Category("cat_food", "餐饮", "receipt", "#D95F5F"),
        Category("cat_transport", "交通", "wallet", "#5C88B8"),
        Category("cat_shopping", "购物", "pig", "#F6C95F"),
        Category("cat_home", "居住", "dashboard", "#16856F"),
        Category("cat_fun", "娱乐", "spark", "#9B6AD0"),
        Category("cat_health", "医疗健康", "clock", "#708786"),
        Category("cat_study", "学习进修", "spark", "#5C88B8"),
        Category("cat_social", "人情往来", "pig", "#D95F5F"),
        Category("cat_subscription", "订阅会员", "clock", "#708786"),
        Category("cat_other", "其他", "receipt", "#708786"),
        Category("cat_income", "收入", "wallet", "#116B5B", kind = CategoryKind.INCOME),
    )

    fun asEntities(): List<CategoryEntity> = categories().map {
        CategoryEntity(
            id = it.id,
            name = it.name,
            iconKey = it.iconKey,
            colorHex = it.colorHex,
            parentId = it.parentId,
            sortOrder = it.sortOrder,
            builtIn = true,
            kind = it.kind,
            monthlyBudgetMinor = it.monthlyBudgetMinor,
            archived = it.archived,
        )
    }
}
