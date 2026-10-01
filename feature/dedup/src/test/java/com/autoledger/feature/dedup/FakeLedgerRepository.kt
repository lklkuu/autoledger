package com.autoledger.feature.dedup

import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 [LedgerRepository]，供纯 JVM 单元测试使用（不引入 Robolectric / Room）。
 *
 * 行为对齐 Room 实现：
 * - upsert 按主键覆盖；
 * - listSince / listAll 按 includeTransfers 过滤 TRANSFER / REFUND（对齐 RoomLedgerRepository:121）；
 * - observeSince / observeRawCount 用变更计数模拟 Room 的 Observable 索引通知。
 */
class FakeLedgerRepository(
    initial: List<LedgerTransaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
) : LedgerRepository {

    private val store = LinkedHashMap<String, LedgerTransaction>()
    private val categoryStore = LinkedHashMap<String, Category>()
    private val userPlatformStore = LinkedHashMap<String, UserPlatform>()

    /**
     * 变更计数：Room 的 `Flow` 查询由 InvalidationTracker 驱动，任何写操作都会重新发射。
     * 内存夹具用一个自增 [MutableStateFlow] 复刻这一语义，
     * 从而能对 observeSince / observeRawCount 的「写后重发」「窗口解耦」做回归测试。
     */
    private val revision = MutableStateFlow(0)

    private fun touch() {
        revision.value += 1
    }

    init {
        initial.forEach { store[it.id] = it }
        categories.forEach { categoryStore[it.id] = it }
    }

    fun snapshot(): List<LedgerTransaction> = store.values.toList()

    override suspend fun upsert(txn: LedgerTransaction) {
        store[txn.id] = txn
        touch()
    }

    override suspend fun upsertAll(txns: List<LedgerTransaction>) {
        txns.forEach { store[it.id] = it }
        touch()
    }

    override suspend fun delete(id: String) {
        store.remove(id)
        touch()
    }

    override suspend fun findById(id: String): LedgerTransaction? = store[id]

    override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> =
        store.values.filter { it.occurredAtMillis >= fromMillis }.filterTypes(includeTransfers)

    /** 新增仓储契约后测试夹具也保持闭区间查询语义一致。 */
    override suspend fun listRange(
        fromMillis: Long,
        toMillis: Long,
        includeTransfers: Boolean,
    ): List<LedgerTransaction> =
        store.values.filter { it.occurredAtMillis in fromMillis..toMillis }.filterTypes(includeTransfers)

    override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> =
        store.values.toList().filterTypes(includeTransfers)

    /** 测试夹具模拟生产的指纹与时间窗口索引查询。 */
    override suspend fun findByFingerprintNear(
        fingerprint: String,
        anchor: Long,
        windowMillis: Long,
        excludeId: String,
    ): List<LedgerTransaction> = store.values.filter { txn ->
        txn.fingerprint == fingerprint &&
            txn.id != excludeId &&
            txn.status != TxnStatus.MERGED &&
            kotlin.math.abs(txn.occurredAtMillis - anchor) <= windowMillis
    }

    /** 语义对齐 Room.observeSince：occurredAtMillis >= from，排除 MERGED，且**没有右边界**。 */
    override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> =
        revision.map {
            store.values.filter { it.occurredAtMillis >= fromMillis && it.status != TxnStatus.MERGED }
        }

    /** 语义对齐 Room.observeRawCount：全表 RAW 计数，与时间窗口无关。 */
    override fun observeRawCount(): Flow<Int> =
        revision.map { store.values.count { it.status == TxnStatus.RAW } }

    /** 语义对齐 Room.observeRaw：全表 RAW 流水（按时间倒序）。 */
    override fun observeRaw(): Flow<List<LedgerTransaction>> =
        revision.map { store.values.filter { it.status == TxnStatus.RAW }.sortedByDescending { it.occurredAtMillis } }

    /** 语义对齐 Room.observeAll：全量流水（排除 MERGED），按 includeTransfers 过滤。 */
    override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> =
        revision.map { store.values.filter { it.status != TxnStatus.MERGED }.filterTypes(includeTransfers) }

    /** 语义对齐 Room.observeRange：闭区间窗口流水（排除 MERGED）。 */
    override fun observeRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): Flow<List<LedgerTransaction>> =
        revision.map {
            store.values
                .filter { it.occurredAtMillis in fromMillis..toMillis && it.status != TxnStatus.MERGED }
                .filterTypes(includeTransfers)
        }

    /** 内存夹具没有真实事务，直接执行 block（与 Room 的 withTransaction 语义在单线程测试中一致）。 */
    override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

    override suspend fun markStatus(id: String, status: TxnStatus) {
        store[id]?.let { store[id] = it.copy(status = status) }
        touch()
    }

    override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) {
        store[id]?.let { store[id] = it.copy(categoryId = categoryId, confidence = confidence) }
        touch()
    }

    override suspend fun assignPlatform(id: String, platformId: String) {
        // 与真实实现对齐：用户指定 ⇒ 满置信度 + USER 源
        store[id]?.let {
            store[id] = it.copy(
                platformId = platformId,
                platformConfidence = 1f,
                platformSource = PlatformSource.USER,
            )
        }
        touch()
    }

    override suspend fun listAccounts(): List<Account> = accounts

    override suspend fun listCategories(): List<Category> = categoryStore.values.toList()

    /** 语义对齐 Room.observeCategories：分类实时订阅。 */
    override fun observeCategories(): Flow<List<Category>> =
        revision.map { categoryStore.values.toList() }

    override suspend fun upsertCategory(category: Category) {
        categoryStore[category.id] = category
        touch()
    }

    override suspend fun deleteCategory(id: String) {
        categoryStore.remove(id)
        touch()
    }

    private fun List<LedgerTransaction>.filterTypes(includeTransfers: Boolean): List<LedgerTransaction> =
        if (includeTransfers) this else filter { it.type != TxnType.TRANSFER && it.type != TxnType.REFUND }

    // ------------------------------------------------------------------ 用户自定义消费平台

    override suspend fun listUserPlatforms(includeArchived: Boolean): List<UserPlatform> =
        userPlatformStore.values
            .filter { includeArchived || !it.archived }
            .sortedBy { it.sortOrder }

    override suspend fun upsertUserPlatform(platform: UserPlatform) {
        userPlatformStore[platform.id] = platform
        touch()
    }

    /** 软删除（与 Room 侧 archive 语义一致）：只置 archived，行保留。 */
    override suspend fun archiveUserPlatform(id: String) {
        userPlatformStore[id]?.let { userPlatformStore[id] = it.copy(archived = true) }
        touch()
    }

    // ------------------------------------------------------------------ 去重：层级互补匹配与合并溯源

    /** 语义对齐 Room.findByAmountWithin：金额带符号相等 + 闭区间窗口 + 排除自身/已合并/已忽略。 */
    override suspend fun findByAmountWithin(
        amountMinor: Long,
        fromMillis: Long,
        toMillis: Long,
        excludeId: String,
    ): List<LedgerTransaction> = store.values.filter { txn ->
        txn.amountMinor == amountMinor &&
            txn.id != excludeId &&
            txn.status != TxnStatus.MERGED &&
            txn.status != TxnStatus.IGNORED &&
            txn.occurredAtMillis in fromMillis..toMillis
    }.sortedByDescending { it.occurredAtMillis }

    override suspend fun setMergeState(id: String, status: TxnStatus, primaryId: String?) {
        store[id]?.let { store[id] = it.copy(status = status, mergedIntoId = primaryId) }
        touch()
    }

    override suspend fun mergeGroupOf(primaryId: String): List<LedgerTransaction> =
        store.values.filter { it.mergedIntoId == primaryId }.sortedByDescending { it.occurredAtMillis }
}
