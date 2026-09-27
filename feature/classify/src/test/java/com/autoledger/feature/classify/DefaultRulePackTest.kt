package com.autoledger.feature.classify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultRulePackTest {

    @Test
    fun `rules have unique ids`() {
        val rules = DefaultRulePack.rules()
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
    }

    @Test
    fun `rules are non empty and include a food keyword`() {
        val rules = DefaultRulePack.rules()
        assertTrue(rules.isNotEmpty())
        assertTrue(rules.any { it.pattern == "星巴克" && it.categoryId == "cat_food" })
    }
}
