package com.autoledger.feature.classify

import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassificationResult
import com.autoledger.core.model.ClassifierRule
import com.autoledger.core.model.RuleKind
import com.autoledger.core.model.TransactionClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ClassifiersTest {

    private fun rule(
        id: String,
        pattern: String,
        categoryId: String,
        kind: RuleKind = RuleKind.KEYWORD,
        learned: Boolean = false,
    ) = ClassifierRule(
        id = id,
        kind = kind,
        pattern = pattern,
        categoryId = categoryId,
        priority = pattern.length,
        learned = learned,
        hitCount = 0,
    )

    @Test
    fun `keyword picks the longest matching keyword`() = runBlocking {
        val source = FakeRuleSource(listOf(rule("r1", "餐饮", "cat_food"), rule("r2", "星巴克", "cat_food")))
        val result = KeywordClassifier(source).classify(ctx("星巴克咖啡"))
        assertEquals("cat_food", result.categoryId)
        assertTrue(result.reason.contains("星巴克"), "应命中更具体的「星巴克」而不是「餐饮」")
    }

    @Test
    fun `keyword returns unresolved when nothing matches`() = runBlocking {
        val source = FakeRuleSource(listOf(rule("r1", "餐饮", "cat_food")))
        assertNull(KeywordClassifier(source).classify(ctx("健身房")).categoryId)
    }

    @Test
    fun `keyword also matches note text`() = runBlocking {
        val source = FakeRuleSource(listOf(rule("r1", "美团外卖", "cat_food")))
        val result = KeywordClassifier(source).classify(ctx("未知商户", note = "美团外卖订单"))
        assertEquals("cat_food", result.categoryId)
    }

    @Test
    fun `merchant exact base confidence is higher than keyword`() = runBlocking {
        val kw = KeywordClassifier(FakeRuleSource(listOf(rule("r1", "星巴克", "cat_food", RuleKind.KEYWORD))))
        val me = KeywordClassifier(FakeRuleSource(listOf(rule("r1", "星巴克", "cat_food", RuleKind.MERCHANT_EXACT))))
        val kwConf = kw.classify(ctx("星巴克")).confidence
        val meConf = me.classify(ctx("星巴克")).confidence
        assertTrue(meConf > kwConf, "MERCHANT_EXACT 基础置信度应高于 KEYWORD")
        assertTrue(kotlin.math.abs(meConf - 0.85f) < 0.001f, "置信度应被封顶在 0.85")
    }

    @Test
    fun `keyword bumps hit count on match`() = runBlocking {
        val source = FakeRuleSource(listOf(rule("r1", "餐饮", "cat_food")))
        KeywordClassifier(source).classify(ctx("餐饮"))
        assertEquals(1, source.allRules().first { it.id == "r1" }.hitCount)
    }

    @Test
    fun `memory recalls a learned merchant`() = runBlocking {
        val source = FakeRuleSource(listOf(rule("m1", "星巴克", "cat_food", RuleKind.MERCHANT_EXACT, learned = true)))
        val result = MemoryClassifier(source).classify(ctx("星巴克"))
        assertEquals("cat_food", result.categoryId)
        assertEquals(0.98f, result.confidence)
    }

    @Test
    fun `memory ignores non learned rules`() = runBlocking {
        val source = FakeRuleSource(listOf(rule("m1", "星巴克", "cat_food", RuleKind.MERCHANT_EXACT, learned = false)))
        assertNull(MemoryClassifier(source).classify(ctx("星巴克")).categoryId)
    }

    @Test
    fun `memory requires a counterparty`() = runBlocking {
        assertNull(MemoryClassifier(FakeRuleSource()).classify(ctx("")).categoryId)
    }

    @Test
    fun `composite picks the highest confidence result`() = runBlocking {
        val low = object : TransactionClassifier {
            override val id = "low"
            override val displayName = "low"
            override val order = 1
            override suspend fun classify(ctx: ClassificationContext) = ClassificationResult("cat_a", 0.3f, "low")
        }
        val high = object : TransactionClassifier {
            override val id = "high"
            override val displayName = "high"
            override val order = 2
            override suspend fun classify(ctx: ClassificationContext) = ClassificationResult("cat_b", 0.9f, "high")
        }
        val result = CompositeClassifier(listOf(low, high)).classify(ctx("x"))
        assertEquals("cat_b", result.categoryId)
    }
}
