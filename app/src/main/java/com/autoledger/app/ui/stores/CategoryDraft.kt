package com.autoledger.app.ui.stores

import com.autoledger.core.model.Category
import com.autoledger.core.model.CategoryKind
import com.autoledger.core.model.Money
import com.autoledger.core.model.formatYuan
import java.util.UUID

/**
 * 分类编辑草稿：承载「新建 / 编辑分类」表单，含校验与落库映射。
 * 纯逻辑、无 Compose/Android 依赖，可直接 JVM 单测。
 */
data class CategoryDraft(
    val id: String? = null,
    val name: String = "",
    val iconKey: String = DEFAULT_ICON,
    val colorHex: String = DEFAULT_COLOR,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    /** 月度预算，输入用「元」字符串；空白 = 不设预算 */
    val budgetYuan: String = "",
) {
    /** 返回第一条校验错误；null 表示可保存。 */
    fun validate(): String? = when {
        name.isBlank() -> "名称不能为空"
        name.trim().length > 12 -> "名称不要超过 12 个字"
        budgetYuan.isNotBlank() && (budgetYuan.trim().toDoubleOrNull()?.let { it >= 0 } != true) -> "预算需为非负数字"
        else -> null
    }

    /** 映射为领域对象。[existing] 保留排序/内置/归档等既有状态；[newId] 用于新建。 */
    fun toCategory(existing: Category? = null, newId: String = "cat_${UUID.randomUUID()}"): Category = Category(
        id = id ?: newId,
        name = name.trim(),
        iconKey = iconKey,
        colorHex = colorHex,
        parentId = existing?.parentId,
        sortOrder = existing?.sortOrder ?: 0,
        builtIn = existing?.builtIn ?: false,
        kind = kind,
        monthlyBudgetMinor = budgetYuan.trim().takeIf { it.isNotBlank() }
            ?.let { Money.fromYuanDouble(it.toDoubleOrNull() ?: 0.0).minor },
        archived = existing?.archived ?: false,
    )

    companion object {
        const val DEFAULT_ICON = "receipt"
        const val DEFAULT_COLOR = "#708786"

        fun from(category: Category): CategoryDraft = CategoryDraft(
            id = category.id,
            name = category.name,
            iconKey = category.iconKey,
            colorHex = category.colorHex,
            kind = category.kind,
            budgetYuan = category.monthlyBudgetMinor?.let { Money(it).formatYuan() } ?: "",
        )
    }
}

/** 按收支归属分组并稳定排序（sortOrder → 名称），供分类管理页两栏展示。 */
fun groupCategoriesByKind(categories: List<Category>): Pair<List<Category>, List<Category>> {
    fun sorted(list: List<Category>) = list.sortedWith(compareBy({ it.sortOrder }, { it.name }))
    return sorted(categories.filter { it.kind == CategoryKind.EXPENSE }) to
        sorted(categories.filter { it.kind == CategoryKind.INCOME })
}
