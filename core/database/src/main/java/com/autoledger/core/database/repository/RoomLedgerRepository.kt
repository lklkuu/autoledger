package com.autoledger.core.database.repository

import androidx.room.withTransaction
import com.autoledger.core.database.AccountEntity
import com.autoledger.core.database.CategoryEntity
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.TransactionEntity
import com.autoledger.core.database.SyncOutboxEntity
import com.autoledger.core.model.Account
import com.autoledger.core.model.AccountKind
import com.autoledger.core.model.Category
import com.autoledger.core.model.Direction
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformSource
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room 实现。所有写操作同时登记一条云同步 op —— 写入路径今天就为将来的同步做好了准备，
 * 但当前没有一个真正会上传的 Client，数据永远只留在本机。
 */
class RoomLedgerRepository(private val db: LedgerDatabase) : LedgerRepository {

    private val txnDao = db.transactionDao()
    private val catDao = db.categoryDao()
    private val accountDao = db.accountDao()
    private val userPlatformDao = db.userPlatformDao()
    private val outbox = db.syncOutboxDao()

    // ---------------- 写 ----------------

    override suspend fun upsert(txn: LedgerTransaction) = upsertAll(listOf(txn))

    override suspend fun upsertAll(txns: List<LedgerTransaction>) = db.withTransaction {
        txnDao.upsertAll(txns.map { it.toEntity() })
        txns.forEach { enqueue("transaction", it.id, "UPSERT") }
    }

    override suspend fun delete(id: String) = db.withTransaction {
        txnDao.delete(id)
        enqueue("transaction", id, "DELETE")
    }

    override suspend fun markStatus(id: String, status: TxnStatus) = db.withTransaction {
        txnDao.updateStatus(id, status.name)
        enqueue("transaction", id, "STATUS:${status.name}")
    }

    override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) = db.withTransaction {
        txnDao.updateCategory(id, categoryId, confidence)
        enqueue("transaction", id, "CATEGORY:$categoryId")
    }

    /**
     * 用户手选消费平台。写 `USER` 源 + 满置信度：
     * `USER` 是权威标记，后续任何自动流程（重解析 / 合并继承 / 再次 ingest）都不得再改写。
     */
    override suspend fun assignPlatform(id: String, platformId: String) = db.withTransaction {
        txnDao.updatePlatform(id, platformId, 1f, PlatformSource.USER.name)
        enqueue("transaction", id, "PLATFORM:$platformId")
    }

    /** 配对待定转账：把 discoveredPaired 的两笔打上同一个 transferGroupId */
    suspend fun linkTransferPair(firstId: String, secondId: String, groupId: String = UUID.randomUUID().toString()) {
        txnDao.updateTransferGroup(firstId, groupId)
        txnDao.updateTransferGroup(secondId, groupId)
    }

    // ---------------- 读 ----------------

    override suspend fun findById(id: String): LedgerTransaction? = txnDao.findById(id)?.toDomain()

    override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> =
        txnDao.listSince(fromMillis).filterTypes(includeTransfers).map { it.toDomain() }

    override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> =
        txnDao.listAll().filterTypes(includeTransfers).map { it.toDomain() }

    /** 实现公共仓储契约，确保统计模块无需依赖 Room 实现类。 */
    override suspend fun listRange(
        fromMillis: Long,
        toMillis: Long,
        includeTransfers: Boolean,
    ): List<LedgerTransaction> =
        txnDao.listRange(fromMillis, toMillis).filterTypes(includeTransfers).map { it.toDomain() }

    /** 首页实时刷新订阅入口：Observe -> 数据变化自动推送（左边界在 SQL 里，右边界由调用方裁剪）。 */
    override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> =
        txnDao.observeSince(fromMillis).map { list -> list.map { it.toDomain() } }

    /**
     * 待确认数量的实时订阅：统计**全表** RAW，不受时间窗口影响。
     * 与 observeSince 解耦后，补录更早日期的流水也能即时刷新首页「待确认」卡片。
     */
    override fun observeRawCount(): Flow<Int> = txnDao.observeRawCount()

    /** B4：待确认队列实时订阅。 */
    override fun observeRaw(): Flow<List<LedgerTransaction>> =
        txnDao.observeRaw().map { list -> list.map { it.toDomain() } }

    /** B4：全量流水实时订阅（账单页）。 */
    override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> =
        txnDao.observeAll().map { list -> list.filterTypes(includeTransfers).map { it.toDomain() } }

    /** B4：时间窗口流水实时订阅（发现页 / 自由基金）。 */
    override fun observeRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): Flow<List<LedgerTransaction>> =
        txnDao.observeRange(fromMillis, toMillis).map { list -> list.filterTypes(includeTransfers).map { it.toDomain() } }

    /** 单事务执行一批写操作：Room 侧走 withTransaction，保证整批原子性。 */
    override suspend fun <R> inTransaction(block: suspend () -> R): R = db.withTransaction { block() }

    /** 对外暴露 DAO 的索引查询，避免去重逻辑回退到全表加载。 */
    override suspend fun findByFingerprintNear(
        fingerprint: String,
        anchor: Long,
        windowMillis: Long,
        excludeId: String,
    ): List<LedgerTransaction> =
        txnDao.findByFingerprintNear(fingerprint, anchor, windowMillis, excludeId).map { it.toDomain() }

    suspend fun countAll(): Int = txnDao.listAll().size

    /**
     * **备份导出专用**的全量读取：**包含** `MERGED` / `IGNORED` 行。
     *
     * 与 [listAll] 的区别是**有意为之**：展示路径必须隐藏这两种状态（否则被合并 / 被忽略的流水
     * 会重复计入账单与统计），但备份是"把用户的整库搬走"，必须原样带上 ——
     * 否则换机后合并链（`mergedIntoId`）与被忽略的决定都会丢。
     *
     * 刻意**不**放进 [LedgerRepository] 接口：它只为 [com.autoledger.core.backup.BackupManager]
     * 服务（后者直接持具体实现类），放进接口会迫使所有测试夹具为它写实现却没有语义价值。
     */
    suspend fun listAllForBackup(): List<LedgerTransaction> =
        txnDao.listAllIncludingHidden().map { it.toDomain() }

    // ---------------- 用户自定义消费平台 ----------------

    /** @param includeArchived 默认含归档：**展示**需要它（否则历史流水塌成「未知平台」）。 */
    override suspend fun listUserPlatforms(includeArchived: Boolean): List<UserPlatform> =
        (if (includeArchived) userPlatformDao.listAll() else userPlatformDao.listActive())
            .map { it.toDomain() }

    override suspend fun upsertUserPlatform(platform: UserPlatform) = db.withTransaction {
        userPlatformDao.upsert(platform.toEntity())
        enqueue("user_platform", platform.id, "UPSERT")
    }

    /**
     * 软删除：只置 `archived = 1`，行保留。
     *
     * 刻意**不**物理删除：历史流水的 `platform_id` 指向这一行，
     * 删掉会让那些流水变成孤儿 ID、展示塌成「未知平台」，用户会以为数据坏了。
     */
    override suspend fun archiveUserPlatform(id: String) = db.withTransaction {
        userPlatformDao.archive(id)
        enqueue("user_platform", id, "ARCHIVE")
    }

    // ---------------- 去重：层级互补匹配与合并溯源 ----------------

    /** 纯金额 + 时间窗口查询；护栏判定在 `LedgerDuplicateResolver`（可纯 JVM 单测）。 */
    override suspend fun findByAmountWithin(
        amountMinor: Long,
        fromMillis: Long,
        toMillis: Long,
        excludeId: String,
    ): List<LedgerTransaction> =
        txnDao.findByAmountWithin(amountMinor, fromMillis, toMillis, excludeId).map { it.toDomain() }

    /** 状态 + 溯源一次写入，避免「置了 MERGED 却没记主记录」的中间态。`primaryId == null` = 撤销合并。 */
    override suspend fun setMergeState(id: String, status: TxnStatus, primaryId: String?) = db.withTransaction {
        txnDao.updateMergeState(id, status.name, primaryId)
        enqueue("transaction", id, "MERGE:${primaryId ?: "none"}")
    }

    override suspend fun mergeGroupOf(primaryId: String): List<LedgerTransaction> =
        txnDao.mergeGroupOf(primaryId).map { it.toDomain() }

    override suspend fun listCategories(): List<Category> = catDao.listAll().map { it.toDomain() }

    /** B4：分类实时订阅（分类管理页 / 账单页分类映射）。 */
    override fun observeCategories(): Flow<List<Category>> = catDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun upsertCategory(category: Category) = catDao.upsert(category.toEntity())

    override suspend fun deleteCategory(id: String) = catDao.delete(id)

    override suspend fun listAccounts(): List<Account> = accountDao.listAll().map { it.toDomain() }

    suspend fun upsertCategories(categories: List<Category>) = catDao.upsertAll(categories.map { it.toEntity() })

    suspend fun upsertAccounts(accounts: List<Account>) = accountDao.upsertAll(accounts.map { it.toEntity() })

    suspend fun clearAllTransactions() = txnDao.clearAll()

    // ---------------- 内部 ----------------

    private suspend fun enqueue(entityType: String, entityId: String, operation: String) {
        outbox.enqueue(
            SyncOutboxEntity(
                opId = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                createdAtMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun List<TransactionEntity>.filterTypes(includeTransfers: Boolean): List<TransactionEntity> =
        if (includeTransfers) this else filter { it.type != TxnType.TRANSFER && it.type != TxnType.REFUND }
}
