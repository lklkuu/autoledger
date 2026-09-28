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

    @Query("UPDATE transactions SET categoryId = :categoryId, confidence = :confidence WHERE id = :id")
    suspend fun updateCategory(id: String, categoryId: String, confidence: Float)

    @Query("UPDATE transactions SET transferGroupId = :groupId WHERE id = :id")
    suspend fun updateTransferGroup(id: String, groupId: String)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transactions")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'RAW'")
    fun observeRawCount(): Flow<Int>
}

@Dao
interface CategoryDao {
    @Upsert suspend fun upsert(item: CategoryEntity)
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
