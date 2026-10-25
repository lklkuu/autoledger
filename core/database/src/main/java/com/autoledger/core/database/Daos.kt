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
 * **状态过滤约定（白名单）**：所有「流水集合」查询一律只取 `status IN ('RAW', 'CONFIRMED')`。
 *
 * 为什么是白名单而不是「排除 MERGED / IGNORED」的黑名单：黑名单的默认值是**进账**，
 * 任何新增状态都会被静默算进账单与报表。白名单让新状态默认不可见，必须显式加进来才会出现。
 * 当前被白名单挡在外面的三态各有明确语义：
 * - `MERGED`：已被合并进主记录，金额由主记录承担，再显示就是重复计账；
 * - `IGNORED`：用户在待确认队列里点了「忽略这笔」= 这笔不算账；
 * - `DUPLICATE`：**疑似重复、默认不成立**（找到候选但没能自动合并，见 `IngestPipeline`），
 *   不进账单与统计，只出现在采集箱待确认队列里等用户拍板。
 *
 * 例外：`observeRaw` / `observeRawCount` 取 `RAW` + `DUPLICATE` —— 采集箱要把
 * 「待确认但默认成立」与「待确认且默认不成立」两种**都**列出来，否则疑似重复的记录
 * 会从账单里消失又不在采集箱里出现（用户彻底看不到它）。
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
        WHERE occurredAtMillis >= :fromMillis AND status IN ('RAW', 'CONFIRMED')
        ORDER BY occurredAtMillis DESC
        """
    )
    fun observeSince(fromMillis: Long): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAtMillis >= :fromMillis AND status IN ('RAW', 'CONFIRMED')
        ORDER BY occurredAtMillis DESC
        """
    )
    suspend fun listSince(fromMillis: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE status IN ('RAW', 'CONFIRMED') ORDER BY occurredAtMillis DESC")
    suspend fun listAll(): List<TransactionEntity>

    /**
     * **备份导出专用**：全表流水，**不过滤任何状态**（含 `MERGED` / `IGNORED`）。
     *
     * 为什么必须单开一个查询，而不是复用 [listAll]：被吸收（`MERGED`）的行在正常查询里被隐藏，
     * 若导出也不带上它，导出 JSON 里 `mergedIntoId` 恒为 null ⇒ **换机后"这笔分别来自微信
     * 和银行卡"这条合并链彻底丢失**，用户也无法撤销合并（见《必修②》）。
     * `IGNORED`（用户主动忽略）同理：那是**用户的决定**，换机后必须保留。
     *
     * 只用于备份，**不得**用于账单 / 统计等展示路径（那两处必须继续排除隐藏行）。
     */
    @Query("SELECT * FROM transactions ORDER BY occurredAtMillis DESC")
    suspend fun listAllIncludingHidden(): List<TransactionEntity>

    /** B4：全量流水的实时订阅（账单页）。 */
    @Query("SELECT * FROM transactions WHERE status IN ('RAW', 'CONFIRMED') ORDER BY occurredAtMillis DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAtMillis BETWEEN :fromMillis AND :toMillis AND status IN ('RAW', 'CONFIRMED')
        ORDER BY occurredAtMillis DESC
        """
    )
    suspend fun listRange(fromMillis: Long, toMillis: Long): List<TransactionEntity>

    /** B4：时间窗口流水的实时订阅（发现页 / 自由基金等）。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAtMillis BETWEEN :fromMillis AND :toMillis AND status IN ('RAW', 'CONFIRMED')
        ORDER BY occurredAtMillis DESC
        """
    )
    fun observeRange(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    /**
     * 采集箱待确认队列：`RAW` + `DUPLICATE` 两种待确认**都**列出来。
     *
     * `RAW` = 待确认但默认成立（已计入账单）；`DUPLICATE` = 疑似重复、默认不成立（未计入账单）。
     * 两者都不能漏：前者是「分类/金额没把握」，后者是「可能就是已有那笔」，
     * 但用户都必须在同一个队列里看到并拍板（确认入账 / 忽略）。
     */
    @Query("SELECT * FROM transactions WHERE status IN ('RAW', 'DUPLICATE') ORDER BY occurredAtMillis DESC")
    fun observeRaw(): Flow<List<TransactionEntity>>

    /** 去重查询：相同指纹、时间窗口内的其它记录（只取计入账本的状态，见文件头白名单约定）。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE fingerprint = :fingerprint
          AND abs(occurredAtMillis - :anchor) <= :windowMillis
          AND id <> :excludeId
          AND status IN ('RAW', 'CONFIRMED')
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
     *
     * 状态：只取 `status IN ('RAW', 'CONFIRMED')` —— 一条**已经在待确认的疑似重复**（`DUPLICATE`）
     * 不该再被当作合并目标（否则会形成「疑似重复 → 又被合并成另一条」的链式噪音）。
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountMinor = :amountMinor
          AND occurredAtMillis BETWEEN :fromMillis AND :toMillis
          AND status IN ('RAW', 'CONFIRMED')
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

    /**
     * 待确认条数：`RAW` + `DUPLICATE`。
     *
     * 计数**含疑似重复**（`DUPLICATE`）：它虽然不计入账单，但同样需要用户拍板，
     * 若不计入这个数字，采集箱会出现「没有待确认流水」却躺着一批未处理记录的自相矛盾。
     * 与 [observeRaw] 的集合必须严格一致（同一个判据），否则数字与列表对不上。
     */
    @Query("SELECT COUNT(*) FROM transactions WHERE status IN ('RAW', 'DUPLICATE')")
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

    /**
     * 条数上限淘汰：只保留最近 [keep] 条（createdAtMillis 降序），其余删除。
     *
     * 同毫秒用 opId 降序 tie-break —— 淘汰必须**确定**（同毫秒两行谁留谁删不能取决于
     * SQLite 的扫描顺序），否则单测无法钉住行为。
     */
    @Query(
        """
        DELETE FROM sync_outbox WHERE opId NOT IN (
            SELECT opId FROM sync_outbox ORDER BY createdAtMillis DESC, opId DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int)
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

/**
 * 时薪按月版本（v8 新增表）。
 *
 * ## 三种写入语义为什么必须分开
 * - [upsert] / [upsertAll]：**覆盖**同月行。前者用于用户改时薪（当月），
 *   后者用于导入新档案（"以档案为准"是用户显式表达"恢复这份快照"的意图）；
 * - [insertIfAbsent]：**不覆盖**同月行。用于导入**旧档案**（没有 `wageVersions` 一节）时的播种
 *   —— 本地已有的版本更权威，档案里的 `settings.wage` 只是"最近一次编辑的快照"。
 *
 * ## 为什么没有 UPDATE ... ON CONFLICT
 * minSdk 26 的 SQLite 是 3.18，不支持 `INSERT ... ON CONFLICT DO UPDATE`；
 * "同月二次保存要保留 createdAt"由调用方读-改-写实现（见 `WageBook.save`），
 * 这里只提供最朴素的覆盖语义。
 */
@Dao
interface WageVersionDao {

    /** 按主键覆盖（调用方负责保留 `createdAtMillis`）。 */
    @Upsert suspend fun upsert(version: WageVersionEntity)

    /** 导入新档案：以档案为准覆盖同月行。 */
    @Upsert suspend fun upsertAll(versions: List<WageVersionEntity>)

    /** 旧档案播种：同月已存在则不覆盖（用户本地版本更权威）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(version: WageVersionEntity)

    /** 月份升序（字典序 = 时间序），便于 `WageLedger` 直接使用。 */
    @Query("SELECT * FROM wage_versions ORDER BY month ASC")
    suspend fun listAll(): List<WageVersionEntity>

    /** 实时订阅：时薪改动（含备份导入）后，版本卡片与收入展示自动刷新。 */
    @Query("SELECT * FROM wage_versions ORDER BY month ASC")
    fun observeAll(): Flow<List<WageVersionEntity>>

    @Query("SELECT * FROM wage_versions WHERE month = :month LIMIT 1")
    suspend fun find(month: String): WageVersionEntity?

    /** `REPLACE_ALL` 导入前清表（"以档案为准"）。 */
    @Query("DELETE FROM wage_versions")
    suspend fun clear()
}
