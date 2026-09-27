package com.autoledger.feature.classify

import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassifierRule
import com.autoledger.core.model.RuleSource

/** 内存版 RuleSource 测试桩：分类引擎无需真实数据库即可验证。 */
class FakeRuleSource(initial: List<ClassifierRule> = emptyList()) : RuleSource {
    private val rules = mutableListOf<ClassifierRule>().apply { addAll(initial) }

    override suspend fun allRules(): List<ClassifierRule> = rules.toList()

    override suspend fun upsertRules(newRules: List<ClassifierRule>) {
        newRules.forEach { nr ->
            val i = rules.indexOfFirst { it.id == nr.id }
            if (i >= 0) rules[i] = nr else rules.add(nr)
        }
    }

    override suspend fun bumpHit(ruleId: String) {
        val i = rules.indexOfFirst { it.id == ruleId }
        if (i >= 0) rules[i] = rules[i].copy(hitCount = rules[i].hitCount + 1)
    }

    override suspend fun countLearned(): Int = rules.count { it.learned }

    override suspend fun clearLearned() {
        rules.removeAll { it.learned }
    }
}

internal fun ctx(
    counterparty: String,
    note: String? = null,
    amountMinor: Long = -2500,
    occurredAtMillis: Long = 1_700_000_000_000L,
) = ClassificationContext(counterparty, note, amountMinor, occurredAtMillis, "notify", null)
