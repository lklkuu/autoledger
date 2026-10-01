package com.autoledger.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.autoledger.core.model.refund.DeductionKind
import com.autoledger.core.model.refund.OrderStatus
import com.autoledger.core.model.refund.RefundStatus
import kotlinx.coroutines.flow.Flow

/**
 * 流水表访问。
 *
 * **状态过滤约定**：所有「流水集合」查询都排除 `MERGED`（已被合并，不重复计）
 * 与 `IGNORED`（用户在待确认队列里点了「忽略这笔」= 这笔不算账）。
 * 只排除 MERGED 是不够的 —— 被忽略的流水会从账单列表与支出统计里消失，
 * 否则「忽略」就只是把它移出待确认队列，账单与汇总仍会把它算进去。
 *
 * 例外：`observeRaw` / `observeRawCount` 只看 `RAW`，天然不包含 IGNORED。
 */
@Dao
interface TransactionDao {

    @Upsert
    suspend fun upsert(txn: TransactionEntity)

    @Upsert
    suspend fun upsertAll(txns: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: String): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAtMillis >= :fromMillis AND status <> 'MERGED' AND status <> 'IGNORED'
        ORDER BY occurredAtMillis DESC
        """
    )
    fun observeSince(fromMillis: Long): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAtMillis >= :fromMillis AND status <> 'MERGED' AND status <> 'IGNORED'
        ORDER BY occurredAtMillis DESC
        """
    )
    suspend fun listSince(fromMillis: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE status <> 'MERGED' AND status <> 'IGNORED' ORDER BY occurredAtMillis DESC")
    suspend fun listAll(): List<TransactionEntity>

    /** B4：全量流水的实时订阅（账单页）。 */
    @Query("SELECT * FROM transactions WHERE status <> 'MERGED' AND status <> 'IGNORED' ORDER BY occurredAtMillis DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAtMillis BETWEEN :fromMillis AND :toMillis AND status <> 'MERGED' AND status <> 'IGNORED'
        ORDER BY occurredAtMillis DESC
        """
    )
    suspend fun listRange(fromMillis: Long, toMillis: Long): List<TransactionEntity>

    /** B4：时间窗口流水的实时订阅（发现页 / 自由基金等）。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAtMillis BETWEEN :fromMillis AND :toMillis AND status <> 'MERGED' AND status <> 'IGNORED'
        ORDER BY occurredAtMillis DESC
        """
    )
    fun observeRange(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE status = 'RAW' ORDER BY occurredAtMillis DESC")
    fun observeRaw(): Flow<List<TransactionEntity>>

    /** 去重查询：相同指纹、时间窗口内的其它记录 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE fingerprint = :fingerprint
          AND abs(occurredAtMillis - :anchor) <= :windowMillis
          AND id <> :excludeId
          AND status <> 'MERGED'
          AND status <> 'IGNORED'
        """
    )
    suspend fun findByFingerprintNear(fingerprint: String, anchor: Long, windowMillis: Long, excludeId: String): List<TransactionEntity>

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /**
     * **Tier-2 层级互补匹配**的候选查询：同金额 + 时间窗口。
     *
     * 只做「同金额 + 时间窗口」，**不做层级判定** —— 护栏（两侧平台层级是否互补、能否自动合并）
     * 属于领域规则，放在 `LedgerDuplicateResolver` 里以便纯 JVM 单测。
     *
     * 金额**带符号**比较（`amountMinor` 原值）：负=支出、正=收入/退款。
     * 这天然挡住了「退款(+88) 与原单(−88)」被合并（设计文档 §4.1）。
     *
     * 性能：3 分钟窗口下，SQLite 先用 `occurredAtMillis` 索引把范围缩到个位数量级，
     * 再过滤金额 ⇒ 无需为它新增索引；且只在 Tier-1 未命中时才执行。
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountMinor = :amountMinor
          AND occurredAtMillis BETWEEN :fromMillis AND :toMillis
          AND status <> 'MERGED'
          AND status <> 'IGNORED'
          AND id <> :excludeId
        ORDER BY occurredAtMillis DESC
        """
    )
    suspend fun findByAmountWithin(
        amountMinor: Long,
        fromMillis: Long,
        toMillis: Long,
        excludeId: String,
    ): List<TransactionEntity>

    /**
     * 合并 / 撤销合并的**唯一写入口**：状态与溯源一次写入。
     *
     * 与 [updateStatus] 分开的理由：合并是成对语义（`MERGED` + `mergedIntoId` 必须一致），
     * 拆成两次调用会出现「置了 MERGED 却没记主记录」的中间态 ——
     * 那一行既不在账单里，也查不出被谁吸收。
     *
     * @param primaryId 被吸收进的主记录；`null` = 撤销合并（清空溯源）
     *
     * 注意 SQL 里写的是**列名** `merged_into_id`（实体上带 `@ColumnInfo`），
     * 不是 Kotlin 属性名 `mergedIntoId` —— Room 会拿 SQL 里的标识符去比对真实列名。
     */
    @Query("UPDATE transactions SET status = :status, merged_into_id = :primaryId WHERE id = :id")
    suspend fun updateMergeState(id: String, status: String, primaryId: String?)

    /** 某个主记录吸收掉的全部记录（UI：「已合并 N 条：微信、银行卡」）。 */
    @Query("SELECT * FROM transactions WHERE merged_into_id = :primaryId ORDER BY occurredAtMillis DESC")
    suspend fun mergeGroupOf(primaryId: String): List<TransactionEntity>

    @Query("UPDATE transactions SET categoryId = :categoryId, confidence = :confidence WHERE id = :id")
    suspend fun updateCategory(id: String, categoryId: String, confidence: Float)

    /**
     * 用户手选消费平台（与 [updateCategory] 对称）。
     *
     * 由调用方负责写入 `confidence = 1f` / `source = 'USER'` ——
     * `USER` 是权威标记，后续自动流程不得再改写该行的平台。
     */
    @Query(
        """
        UPDATE transactions
        SET platform_id = :platformId, platform_confidence = :confidence, platform_source = :source
        WHERE id = :id
        """,
    )
    suspend fun updatePlatform(id: String, platformId: String, confidence: Float, source: String)

    @Query("UPDATE transactions SET transferGroupId = :groupId WHERE id = :id")
    suspend fun updateTransferGroup(id: String, groupId: String)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transactions")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'RAW'")
    fun observeRawCount(): Flow<Int>
}

/**
 * 用户自定义消费平台（v6 新增表）。
 *
 * [listAll] 与 [listActive] 的分工是**展示 vs 识别**：
 * - 展示要 [listAll]（含归档）—— 历史流水的 `platformId` 指向它，隐藏会让这些流水塌成「未知平台」；
 * - 注入识别目录要 [listActive] —— 归档条目不该再参与识别候选。
 */
@Dao
interface UserPlatformDao {
    @Upsert suspend fun upsert(item: UserPlatformEntity)

    @Query("SELECT * FROM user_platforms ORDER BY sortOrder, displayName")
    suspend fun listAll(): List<UserPlatformEntity>

    @Query("SELECT * FROM user_platforms WHERE archived = 0 ORDER BY sortOrder, displayName")
    suspend fun listActive(): List<UserPlatformEntity>

    /** 软删除：置 `archived = 1`，行保留（历史流水仍能解析出名称）。 */
    @Query("UPDATE user_platforms SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)
}

@Dao
interface CategoryDao {    @Upsert suspend fun upsert(item: CategoryEntity)
    @Upsert suspend fun upsertAll(items: List<CategoryEntity>)
    @Query("SELECT * FROM categories ORDER BY sortOrder, name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories ORDER BY sortOrder, name") suspend fun listAll(): List<CategoryEntity>
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM categories") suspend fun clear()
}

@Dao
interface AccountDao {
    @Upsert suspend fun upsert(item: AccountEntity)
    @Upsert suspend fun upsertAll(items: List<AccountEntity>)
    @Query("SELECT * FROM accounts ORDER BY name") fun observeAll(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts ORDER BY name") suspend fun listAll(): List<AccountEntity>
    @Query("DELETE FROM accounts") suspend fun clear()
}

@Dao
interface ClassifierRuleDao {
    @Upsert suspend fun upsert(item: ClassifierRuleEntity)
    @Upsert suspend fun upsertAll(items: List<ClassifierRuleEntity>)
    @Query("SELECT * FROM classifier_rules ORDER BY learned DESC, priority DESC, hitCount DESC")
    suspend fun listAll(): List<ClassifierRuleEntity>
    @Query("SELECT * FROM classifier_rules ORDER BY learned DESC, priority DESC, hitCount DESC")
    fun observeAll(): Flow<List<ClassifierRuleEntity>>
    @Query("UPDATE classifier_rules SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun bumpHit(id: String)
    @Query("SELECT * FROM classifier_rules WHERE pattern = :pattern AND categoryId = :categoryId LIMIT 1")
    suspend fun findByPattern(pattern: String, categoryId: String): ClassifierRuleEntity?
    @Query("SELECT COUNT(*) FROM classifier_rules WHERE learned = 1")
    suspend fun countLearned(): Int
    @Query("DELETE FROM classifier_rules WHERE learned = 1")
    suspend fun clearLearned()
}

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueue(op: SyncOutboxEntity)
    @Query("SELECT * FROM sync_outbox ORDER BY createdAtMillis ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<SyncOutboxEntity>
    @Update suspend fun update(op: SyncOutboxEntity)
    @Query("DELETE FROM sync_outbox WHERE opId = :opId") suspend fun remove(opId: String)
    @Query("SELECT COUNT(*) FROM sync_outbox") fun observePendingCount(): Flow<Int>
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 'global'")
    suspend fun global(): SettingsEntity?

    @Query("SELECT * FROM app_settings WHERE id = 'global'")
    fun observeGlobal(): Flow<SettingsEntity?>

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

// ---------------------------------------------------------------- 订单 / 退款

@Dao
interface OrderDao {
    @Upsert suspend fun upsert(order: OrderEntity)
    @Upsert suspend fun upsertDeductions(items: List<OrderDeductionEntity>)

    @Query("SELECT * FROM orders WHERE id = :id") suspend fun findById(id: String): OrderEntity?
    @Query("SELECT * FROM orders WHERE orderNo = :orderNo LIMIT 1") suspend fun findByNo(orderNo: String): OrderEntity?
    @Query("SELECT * FROM orders ORDER BY occurredAtMillis DESC") suspend fun listAll(): List<OrderEntity>
    /** B4：订单列表的实时订阅（退款对账页）。 */
    @Query("SELECT * FROM orders ORDER BY occurredAtMillis DESC") fun observeAll(): Flow<List<OrderEntity>>
    @Query("SELECT * FROM order_deductions WHERE orderId = :orderId") suspend fun deductionsOf(orderId: String): List<OrderDeductionEntity>

    /** CAS：仅当版本匹配才更新状态；返回受影响行数，0 表示并发冲突。 */
    @Query("UPDATE orders SET status = :status, version = :newVersion WHERE id = :id AND version = :expectedVersion")
    suspend fun casStatus(id: String, status: OrderStatus, newVersion: Int, expectedVersion: Int): Int
}

@Dao
interface RefundDao {
    /** ABORT：幂等键唯一约束被触发时抛异常，由上层转成"重复请求"。 */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(refund: RefundEntity)
    @Upsert suspend fun upsertAllocations(items: List<RefundAllocationEntity>)

    @Query("SELECT * FROM refunds WHERE idempotencyKey = :key LIMIT 1") suspend fun findByIdempotencyKey(key: String): RefundEntity?
    @Query("SELECT * FROM refunds WHERE refundNo = :refundNo LIMIT 1") suspend fun findByNo(refundNo: String): RefundEntity?
    @Query("SELECT * FROM refunds WHERE orderId = :orderId") suspend fun listByOrder(orderId: String): List<RefundEntity>
    /** B4：退款单列表的实时订阅（退款对账页）。 */
    @Query("SELECT * FROM refunds ORDER BY occurredAtMillis DESC") fun observeAll(): Flow<List<RefundEntity>>
    @Query("SELECT * FROM refund_allocations WHERE refundId = :refundId") suspend fun allocationsOf(refundId: String): List<RefundAllocationEntity>
    @Query("UPDATE refunds SET status = :status WHERE id = :id") suspend fun updateStatus(id: String, status: RefundStatus)
    @Query("UPDATE refunds SET attempt = attempt + 1 WHERE id = :id") suspend fun bumpAttempt(id: String)
}

@Dao
interface ResourceBalanceDao {
    @Upsert suspend fun upsert(balance: ResourceBalanceEntity)
    @Query("SELECT * FROM resource_balances WHERE kind = :kind AND resourceId = :resourceId LIMIT 1")
    suspend fun find(kind: DeductionKind, resourceId: String): ResourceBalanceEntity?
}
