package com.autoledger.feature.classify

import com.autoledger.core.model.ClassifierRule
import com.autoledger.core.model.RuleKind
import com.autoledger.core.model.RuleSource
import java.util.UUID

/**
 * 「用户纠正 → 学习」闭环。
 *
 * 用户在流水详情里把分类改成正确的那一刻，就写一条 learned 规则。
 * 下一次同一家商户出现时 [MemoryClassifier] 会直接命中，
 * 不需要任何模型训练，也不需要联网 —— 准确率只会随着使用越来越贴合本人。
 */
class CorrectionLearner(
    private val ruleSource: RuleSource,
) {

    /** 商户名 → 分类。已经存在则只更新分类，避免重复规则堆积 */
    suspend fun remember(counterparty: String, categoryId: String) {
        val key = counterparty.trim()
        if (key.isBlank() || categoryId.isBlank()) return
        val existing = ruleSource.allRules()
            .firstOrNull { it.learned && it.pattern.equals(key, ignoreCase = true) }

        if (existing != null) {
            ruleSource.upsertRules(listOf(existing.copy(categoryId = categoryId, learned = true)))
        } else {
            ruleSource.upsertRules(
                listOf(
                    ClassifierRule(
                        id = "learned:${UUID.randomUUID()}",
                        kind = RuleKind.MERCHANT_EXACT,
                        pattern = key,
                        categoryId = categoryId,
                        priority = Int.MAX_VALUE,
                        learned = true,
                        hitCount = 1,
                        createdAtMillis = System.currentTimeMillis(),
                    )
                )
            )
        }
    }

    suspend fun learnedCount(): Int = ruleSource.countLearned()

    /** 「清空学习记录」：只删用户回流产生的规则，出厂规则包不受影响 */
    suspend fun resetLearning() = ruleSource.clearLearned()
}
