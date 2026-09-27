package com.autoledger.feature.classify

import com.autoledger.core.model.ClassifierRule
import com.autoledger.core.model.RuleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class CorrectionLearnerTest {

    @Test
    fun `remember creates a learned rule for a new merchant`() = runBlocking {
        val source = FakeRuleSource()
        CorrectionLearner(source).remember("星巴克", "cat_food")
        val learned = source.allRules().filter { it.learned }
        assertEquals(1, learned.size)
        assertEquals("cat_food", learned.first().categoryId)
        assertEquals(RuleKind.MERCHANT_EXACT, learned.first().kind)
    }

    @Test
    fun `remember updates category of an existing learned rule`() = runBlocking {
        val existing = ClassifierRule(
            id = "learned:1", kind = RuleKind.MERCHANT_EXACT, pattern = "星巴克",
            categoryId = "cat_food", priority = Int.MAX_VALUE, learned = true, hitCount = 1,
        )
        val source = FakeRuleSource(listOf(existing))
        CorrectionLearner(source).remember("星巴克", "cat_shopping")
        val learned = source.allRules().filter { it.learned }
        assertEquals(1, learned.size, "不应重复堆积规则")
        assertEquals("cat_shopping", learned.first().categoryId)
    }

    @Test
    fun `remember ignores blank input`() = runBlocking {
        val source = FakeRuleSource()
        CorrectionLearner(source).remember("   ", "cat_food")
        assertEquals(0, source.allRules().size)
    }

    @Test
    fun `resetLearning clears only learned rules`() = runBlocking {
        val seed = ClassifierRule(
            id = "seed:1", kind = RuleKind.KEYWORD, pattern = "餐饮",
            categoryId = "cat_food", priority = 2, learned = false, hitCount = 0,
        )
        val learned = ClassifierRule(
            id = "learned:1", kind = RuleKind.MERCHANT_EXACT, pattern = "星巴克",
            categoryId = "cat_food", priority = Int.MAX_VALUE, learned = true, hitCount = 1,
        )
        val source = FakeRuleSource(listOf(seed, learned))
        CorrectionLearner(source).resetLearning()
        val remaining = source.allRules()
        assertEquals(1, remaining.size)
        assertEquals("seed:1", remaining.first().id)
    }
}
