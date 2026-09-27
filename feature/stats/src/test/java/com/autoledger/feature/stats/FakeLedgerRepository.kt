package com.autoledger.feature.stats

import com.autoledger.core.model.Account
import com.autoledger.core.model.AccountKind
import com.autoledger.core.model.Category
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 [LedgerRepository]，供 feature-stats 的纯 JVM 测试使用。
 *
 * 额外提供 `listRange`，与 RoomLedgerRepository:73 语义一致（闭区间 + 过滤划转/退款），
 * 并提供 observeSince / observeRawCount 的等价实现，保证新增契约后测试源集可独立编译。
 */
class FakeLedgerRepository(
    initial: List<LedgerTransaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
) : LedgerRepository {

    private val store = LinkedHashMap<String, LedgerTransaction>()
    private val categoryStore = LinkedHashMap<String, Category>()

    /** 变更计数，用于模拟 Room 的 Observable 索引通知（对齐生产 Flow 的重发语义）。 */
    private val revision = MutableStateFlow(0)

    private fun touch() {
        revision.value += 1
    }

    init {
        initial.forEach { store[it.id] = it }
        categories.forEach { categoryStore[it.id] = it }
    }

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

    override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> =
        store.values.toList().filterTypes(includeTransfers)

    /** 测试仓储完整实现公共去重契约，便于接口独立编译。 */
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

    /** 闭区间查询，对齐 RoomLedgerRepository.listRange。 */
    override suspend fun listRange(
        fromMillis: Long,
        toMillis: Long,
        includeTransfers: Boolean,
    ): List<LedgerTransaction> =
        store.values.filter { it.occurredAtMillis in fromMillis..toMillis }.filterTypes(includeTransfers)

    /** 语义对齐 Room.observeSince：左边界（>=）且排除 MERGED，无右边界。 */
    override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> =
        revision.map {
            store.values.filter { it.occurredAtMillis >= fromMillis && it.status != TxnStatus.MERGED }
        }

    /** 语义对齐 Room.observeRawCount：全表 RAW 计数。 */
    override fun observeRawCount(): Flow<Int> =
        revision.map { store.values.count { it.status == TxnStatus.RAW } }

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

    /** 内存夹具无真实事务，直接执行 block。 */
    override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

    override suspend fun markStatus(id: String, status: TxnStatus) {
        store[id]?.let { store[id] = it.copy(status = status) }
        touch()
    }

    override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) {
        store[id]?.let { store[id] = it.copy(categoryId = categoryId, confidence = confidence) }
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
}

/** 测试夹具工厂 */
object Fixtures {

    val food = Category("cat_food", "餐饮", "receipt", "#D95F5F")
    val transport = Category("cat_transport", "交通", "wallet", "#5C88B8")
    val income = Category("cat_income", "收入", "wallet", "#116B5B")

    fun account(): Account = Account("acc1", "招商银行", AccountKind.BANK_CARD)

    fun txn(
        id: String,
        amountMinor: Long,
        counterparty: String = "某商户",
        categoryId: String? = null,
        type: TxnType = if (amountMinor < 0) TxnType.EXPENSE else TxnType.INCOME,
        sourceId: String = "notify",
        occurredAtMillis: Long = 0L,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAtMillis,
        type = type,
        counterparty = counterparty,
        categoryId = categoryId,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = TxnStatus.CONFIRMED,
    )
}
