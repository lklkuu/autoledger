package com.autoledger.core.database

import com.autoledger.core.model.ClassifierRule
import com.autoledger.core.model.RuleSource

/**
 * [RuleSource] 的 Room 实现：把 DAO 包装成核心层可见的规则读写契约。
 * 分类引擎只依赖接口，这里负责「数据库实体 <-> 领域模型」的映射。
 */
class RoomRuleSource(private val dao: ClassifierRuleDao) : RuleSource {

    override suspend fun allRules(): List<ClassifierRule> = dao.listAll().map { it.toDomain() }

    override suspend fun upsertRules(rules: List<ClassifierRule>) = dao.upsertAll(rules.map { it.toEntity() })

    override suspend fun bumpHit(ruleId: String) = dao.bumpHit(ruleId)

    override suspend fun countLearned(): Int = dao.countLearned()

    override suspend fun clearLearned() = dao.clearLearned()

    private fun ClassifierRuleEntity.toDomain() = ClassifierRule(
        id = id,
        kind = kind,
        pattern = pattern,
        categoryId = categoryId,
        priority = priority,
        learned = learned,
        hitCount = hitCount,
        createdAtMillis = createdAtMillis,
    )

    private fun ClassifierRule.toEntity() = ClassifierRuleEntity(
        id = id,
        kind = kind,
        pattern = pattern,
        categoryId = categoryId,
        priority = priority,
        learned = learned,
        hitCount = hitCount,
        createdAtMillis = createdAtMillis,
    )
}
