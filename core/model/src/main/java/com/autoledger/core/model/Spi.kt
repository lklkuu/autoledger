package com.autoledger.core.model

import kotlinx.coroutines.flow.Flow

/**
 * ==============================
 * 可插拔契约（SPI）
 * ==============================
 * 这些接口定义在 core-model 中，**实现放在各自的 feature 模块**。
 * 依赖方向永远是 feature -> core，任何 core 模块都不认识具体实现类，
 * 新增渠道 / 分类引擎 / 统计维度都不需要改动已有代码，只要注册一个新实现即可。
 */

// ---------------------------------------------------------------- 归类

data class ClassificationResult(
    val categoryId: String?,
    val confidence: Float,
    val reason: String,
) {
    companion object {
        fun unresolved(reason: String = "未命中规则") = ClassificationResult(null, 0f, reason)
    }
}

/**
 * 分类插件。实现可以是关键词、商户记忆、规则引擎，将来也可以换成端上模型推理，
 * 只要仍然输出 [ClassificationResult] 就能挂进责任链。
 */
interface TransactionClassifier {
    val id: String
    val displayName: String
    /** 数值越大越先执行 */
    val order: Int
    suspend fun classify(ctx: ClassificationContext): ClassificationResult
}

data class ClassificationContext(
    val counterparty: String,
    val note: String?,
    val amountMinor: Long,
    val occurredAtMillis: Long,
    val sourceId: String,
    val packageName: String?,
)

/**
 * 分类规则的读写抽象。
 *
 * 分类引擎（关键词/记忆/学习闭环）只面向本接口编程，不依赖 Room/数据库具体实现：
 * - 实现可换成 Room（见 core:database 的 RoomRuleSource）、远程规则服务或内存测试桩；
 * - 由此 feature:classify 成为纯逻辑模块，可做 JVM 单测、可独立演进。
 */
interface RuleSource {
    suspend fun allRules(): List<ClassifierRule>
    suspend fun upsertRules(rules: List<ClassifierRule>)
    suspend fun bumpHit(ruleId: String)
    suspend fun countLearned(): Int
    suspend fun clearLearned()
}

// ---------------------------------------------------------------- 转账识别

data class TransferVerdict(
    val kind: TransferKind,
    val confidence: Float,
    val reason: String,
    val matchedAccountId: String? = null,
) {
    companion object {
        fun none(reason: String = "未识别为内部划转") = TransferVerdict(TransferKind.NONE, 0f, reason)
    }
}

interface TransferDetector {
    val id: String
    suspend fun detect(ctx: TransferContext): TransferVerdict
}

data class TransferContext(
    val counterparty: String,
    val note: String?,
    val amountMinor: Long,
    val occurredAtMillis: Long,
    val sourceId: String,
    val accounts: List<Account>,
)

// ---------------------------------------------------------------- 去重

data class DuplicateCandidate(
    val txnId: String,
    val score: Int,
    /** true = 候选与待入账流水来自不同渠道（真正可能的一笔账两渠道）；false = 同渠道（更可能是独立消费） */
    val crossSource: Boolean = true,
)

interface DuplicateResolver {
    val id: String
    /** 计算去重指纹：同源（跨渠道）且语义一致的两笔会得到同一个 key */
    fun fingerprintOf(txn: LedgerTransaction): String
    /** 只有信息足够可靠时才允许静默自动合并，缺省保持原兼容行为。 */
    fun isAutoMergeSafe(txn: LedgerTransaction): Boolean = true
    /** 找出与给定流水重复的记录 */
    suspend fun findDuplicates(txn: LedgerTransaction): List<DuplicateCandidate>
    /** 手动合并：把 duplicates 吸收进 primary */
    suspend fun merge(primaryId: String, duplicateIds: List<String>)
}

// ---------------------------------------------------------------- 存储

/** 数据仓库抽象，feature 层只面向它编程，不直接碰 Room。 */
interface LedgerRepository {
    suspend fun upsert(txn: LedgerTransaction)
    suspend fun upsertAll(txns: List<LedgerTransaction>)
    suspend fun delete(id: String)
    suspend fun findById(id: String): LedgerTransaction?
    /**
     * 取 [fromMillis] 起（含）的流水。
     *
     * @param includeTransfers 缺省 `false` 时，实现会**同时排除内部划转(TRANSFER) 与退款(REFUND)**，
     *   即只保留 EXPENSE/INCOME。需要退款参与统计（退款冲抵口径）时必须**显式传 `true`**，
     *   再自行按 `type` 过滤 —— 否则退款会被静默吞掉（见 InsightsStore 口径失效的历史缺陷）。
     */
    suspend fun listSince(fromMillis: Long, includeTransfers: Boolean = false): List<LedgerTransaction>

    /**
     * 统计查询必须通过仓储契约，避免 feature 层依赖 Room 具体实现。
     *
     * @param includeTransfers 语义同 [listSince]：`false` 会**同时排除 TRANSFER 与 REFUND**。
     */
    suspend fun listRange(
        fromMillis: Long,
        toMillis: Long,
        includeTransfers: Boolean = false,
    ): List<LedgerTransaction>

    /**
     * @param includeTransfers 语义同 [listSince]：`false` 会**同时排除 TRANSFER 与 REFUND**。
     */
    suspend fun listAll(includeTransfers: Boolean = false): List<LedgerTransaction>
    /** 去重必须走指纹索引查询，避免历史账单导入时反复扫描全表。 */
    suspend fun findByFingerprintNear(
        fingerprint: String,
        anchor: Long,
        windowMillis: Long,
        excludeId: String,
    ): List<LedgerTransaction>
    suspend fun markStatus(id: String, status: TxnStatus)
    suspend fun assignCategory(id: String, categoryId: String, confidence: Float)
    suspend fun listAccounts(): List<Account>
    suspend fun listCategories(): List<Category>

    /** 新建或更新一个分类（含名称、图标、颜色、收支归属、月度预算、归档标记）。 */
    suspend fun upsertCategory(category: Category)

    /** 删除一个分类。 */
    suspend fun deleteCategory(id: String)

    /**
     * 实时订阅：从 [fromMillis]（含）起的流水集合变化，用于「新增流水后 UI 自动刷新」。
     *
     * 契约提醒：本查询**只有左边界**（`occurredAtMillis >= fromMillis`），没有右边界；
     * 需要「本月」语义的调用方必须自行补右边界过滤，否则未来日期流水（预授权、跨时区账单）
     * 会混进统计（见 HomeStore 的裁剪逻辑）。
     */
    fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>>

    /**
     * 实时订阅：全表「待确认(RAW)」流水数量。
     *
     * 之所以与 [observeSince] 分开订阅：待确认卡片统计的是**全表** RAW，
     * 而 [observeSince] 只覆盖某个时间窗口。补录一笔更早日期的流水时窗口内集合不变，
     * 若两者合并在同一 Flow 上，卡片就永远不会刷新。
     */
    fun observeRawCount(): Flow<Int>

    /**
     * 实时订阅：全量流水（按时间倒序）。用于账单页的实时刷新（B4）。
     *
     * @param includeTransfers 语义同 [listSince]：`false` 会同时排除 TRANSFER 与 REFUND。
     */
    fun observeAll(includeTransfers: Boolean = false): Flow<List<LedgerTransaction>>

    /**
     * 实时订阅：[fromMillis, toMillis] 区间内的流水（按时间倒序）。用于发现页 / 自由基金等（B4）。
     *
     * @param includeTransfers 语义同 [listSince]：`false` 会同时排除 TRANSFER 与 REFUND。
     */
    fun observeRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean = false): Flow<List<LedgerTransaction>>

    /**
     * 实时订阅：全部分类（按 sortOrder, name 排序）。用于分类管理页与账单页分类映射（B4）。
     */
    fun observeCategories(): Flow<List<Category>>

    /**
     * 在单个数据库事务内执行一批写操作。
     *
     * 用于「一次补录多笔」场景：要么整批落库、要么整批回滚，避免出现「入了一半」的中间态，
     * 同时把多次提交压缩成一次。无事务能力的实现（内存测试夹具）直接执行 [block] 即可。
     */
    suspend fun <R> inTransaction(block: suspend () -> R): R
}
