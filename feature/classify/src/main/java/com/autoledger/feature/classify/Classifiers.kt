package com.autoledger.feature.classify

import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassificationResult
import com.autoledger.core.model.RuleKind
import com.autoledger.core.model.RuleSource
import com.autoledger.core.model.TransactionClassifier

/**
 * 关键词分类器：拿商户名 / 备注去规则表里做包含匹配。
 *
 * 命中多条时取**最长**的关键词 —— 「星巴克」比泛化的「餐饮」更可信，
 * 这条启发式让规则包可以安全地加入粗粒度词而不会抢走精确词的席位。
 */
class KeywordClassifier(
    private val ruleSource: RuleSource,
) : TransactionClassifier {

    override val id: String = KEYWORD_ID
    override val displayName: String = "关键词规则"
    override val order: Int = 100

    override suspend fun classify(ctx: ClassificationContext): ClassificationResult {
        val haystack = buildList {
            add(ctx.counterparty)
            ctx.note?.let(::add)
        }.joinToString(" ").lowercase()
        if (haystack.isBlank()) return ClassificationResult.unresolved("没有可用的商户信息")

        val hit = ruleSource.allRules()
            .filter { it.kind == RuleKind.KEYWORD || it.kind == RuleKind.MERCHANT_EXACT }
            .filter { haystack.contains(it.pattern.lowercase()) }
            .maxByOrNull { it.pattern.length }
            ?: return ClassificationResult.unresolved("未命中关键词")

        ruleSource.bumpHit(hit.id)
        val base = if (hit.kind == RuleKind.MERCHANT_EXACT) 0.95f else 0.6f
        return ClassificationResult(
            categoryId = hit.categoryId,
            // 关键词越长置信度越高，最高不超过 0.85：机器判断永远留一档给人
            confidence = (base + hit.pattern.length / 100f).coerceAtMost(0.85f),
            reason = "命中「${hit.pattern}」",
        )
    }

    companion object { const val KEYWORD_ID = "keyword" }
}

/**
 * 记忆分类器：用户纠正过什么，就永远记住什么。
 *
 * 这是「用户纠正 -> 提升后续准确率」闭环的读取端（写入端见 [CorrectionLearner]）。
 * 商户名精确相等才生效，所以不会把一家店的学习结果错误推广到别家。
 */
class MemoryClassifier(
    private val ruleSource: RuleSource,
) : TransactionClassifier {

    override val id: String = MEMORY_ID
    override val displayName: String = "用户纠正记忆"
    override val order: Int = 0

    override suspend fun classify(ctx: ClassificationContext): ClassificationResult {
        val counterparty = ctx.counterparty.trim()
        if (counterparty.isBlank()) return ClassificationResult.unresolved("缺少商户名")
        val learned = ruleSource.allRules()
            .filter { it.learned && it.kind == RuleKind.MERCHANT_EXACT }
            .firstOrNull { it.pattern.equals(counterparty, ignoreCase = true) }
            ?: return ClassificationResult.unresolved("没有历史纠正记录")
        ruleSource.bumpHit(learned.id)
        return ClassificationResult(learned.categoryId, 0.98f, "你以前把它归到这一类")
    }

    companion object { const val MEMORY_ID = "memory" }
}

/**
 * 兜底启发式：既没有规则也没有记忆时，用「时间 + 金额形态」猜一个。
 *
 * 置信度刻意压在 0.35 左右，低于自动入账阈值 —— 它只负责给出排序合理的候选，
 * 由用户在「待确认」里点一下确认，同时喂给记忆分类器。
 */
class AmountHeuristicClassifier : TransactionClassifier {

    override val id: String = HEURISTIC_ID
    override val displayName: String = "金额时间启发式"
    override val order: Int = 500

    override suspend fun classify(ctx: ClassificationContext): ClassificationResult {
        val yuan = kotlin.math.abs(ctx.amountMinor) / 100.0
        val hour = java.time.Instant.ofEpochMilli(ctx.occurredAtMillis)
            .atZone(java.time.ZoneId.systemDefault()).hour

        val guess = when {
            yuan <= 30 && hour in MEAL_HOURS -> "cat_food" to "小额 + 饭点"
            yuan <= 50 && hour in COMMUTE_HOURS -> "cat_transport" to "小额 + 通勤时段"
            hour in 0..5 -> "cat_fun" to "深夜消费"
            else -> null
        }
        return guess?.let { ClassificationResult(it.first, 0.35f, it.second) }
            ?: ClassificationResult.unresolved("启发式无结论")
    }

    companion object {
        const val HEURISTIC_ID = "heuristic"
        private val MEAL_HOURS = 6..9
        private val COMMUTE_HOURS = 7..9
    }
}

/**
 * 责任链聚合。
 *
 * 按 [TransactionClassifier.order] 从小到大依次执行，**取置信度最高的结果**，
 * 而不是「第一个命中的」：启发式虽然排在最后，但只有当它比前面的规则更可信时才会胜出。
 * 新增分类器只要实现接口并注册，不会打乱已有逻辑。
 */
class CompositeClassifier(
    private val chain: List<TransactionClassifier>,
) : TransactionClassifier {

    override val id: String = COMPOSITE_ID
    override val displayName: String = "组合分类器"
    override val order: Int = Int.MAX_VALUE

    override suspend fun classify(ctx: ClassificationContext): ClassificationResult {
        val results = chain.sortedBy { it.order }.mapNotNull {
            runCatching { it.classify(ctx) }
                .onFailure { /* 单个分类器挂掉不应该拖垮整条链路 */ }
                .getOrNull()
        }
        return results.filter { it.categoryId != null }.maxByOrNull { it.confidence }
            ?: ClassificationResult.unresolved("所有分类器都没有结论")
    }

    companion object { const val COMPOSITE_ID = "composite" }
}
