package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.Category
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.TxnExtras
import com.autoledger.core.model.txnExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ 流水 / 月结

class LedgerStore(private val container: AppContainer) {

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val items: List<LedgerTransaction> = emptyList(),
        val categories: Map<String, Category> = emptyMap(),
        val query: String = "",
        val showTransfers: Boolean = false,
        val tagFilter: String? = null,
        /** 消费平台筛选：null = 全部；[PlatformCatalog.UNKNOWN_ID] = 只看未识别（便于集中补全）。 */
        val platformFilter: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：账单页是长驻页面，改为实例级可取消作用域 + Flow 订阅（对齐 HomeStore），
    // 写入后由 Room Flow 自动刷新，不再手动 load()。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    /** 幂等启动订阅；重复调用（含错误页「重试」）先取消旧订阅再重建。 */
    fun load() {
        observeJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        observeJob = scope.launch { observe() }
    }

    /** 离开账单页时释放订阅（配合实例级作用域消除泄漏，R1）。 */
    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        combine(
            container.repository.observeAll(includeTransfers = true),
            container.repository.observeCategories(),
        ) { txns, cats -> txns to cats }
            .flowOn(Dispatchers.Default)
            .catch { e ->
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
            .collect { (txns, cats) ->
                val s = _state.value
                _state.value = State(
                    loading = false,
                    items = txns.sortedByDescending { it.occurredAtMillis },
                    categories = cats.associateBy { it.id },
                    query = s.query,
                    showTransfers = s.showTransfers,
                    tagFilter = s.tagFilter,
                    platformFilter = s.platformFilter,
                )
            }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    /**
     * 当前查看的「合并组」：某条主记录**吸收掉了哪些记录**（供编辑对话框展示与逐条撤销）。
     *
     * 刻意放在**独立**的 StateFlow 而不是塞进 [State]：`observe()` 每次发射都会重建整个
     * `State(...)`，往那里加字段就得在重建处再手工搬一次，漏一行就"撤销后列表不刷新"这种怪 bug。
     */
    private val _mergeGroup = MutableStateFlow<List<LedgerTransaction>>(emptyList())
    val mergeGroup: StateFlow<List<LedgerTransaction>> = _mergeGroup.asStateFlow()

    fun loadMergeGroup(primaryId: String) {
        scope.launch {
            _mergeGroup.value = catching { container.repository.mergeGroupOf(primaryId) }
                .getOrDefault(emptyList())
        }
    }

    fun clearMergeGroup() {
        _mergeGroup.value = emptyList()
    }

    /**
     * 撤销合并：把被吸收的记录恢复成待确认并**清空溯源**。
     *
     * 刻意**不**回滚当初继承到主记录的字段（设计 §4.6）：用户在合并之后可能又编辑过主记录，
     * 自动回滚会覆盖他的新编辑。UI 负责提示「主记录的商户/平台可能仍含继承值，请核对」。
     */
    fun unmerge(mergedId: String, primaryId: String) {
        scope.launch {
            catching { container.repository.setMergeState(mergedId, TxnStatus.RAW, null) }
            loadMergeGroup(primaryId)
        }
    }

    fun toggleTransfers() {
        _state.value = _state.value.copy(showTransfers = !_state.value.showTransfers)
    }

    /** 切换标签筛选（再点一次同一标签 = 取消筛选）。 */
    fun setTagFilter(tag: String) {
        _state.value = _state.value.copy(tagFilter = if (_state.value.tagFilter == tag) null else tag)
    }

    /** 切换消费平台筛选（再点一次同一平台 = 取消；「未知」同样可筛，便于集中补全）。 */
    fun setPlatformFilter(platformId: String) {
        _state.value = _state.value.copy(
            platformFilter = if (_state.value.platformFilter == platformId) null else platformId,
        )
    }

    /** 全部已用标签（按出现频次降序），供筛选栏展示。 */
    fun allTags(): List<String> = _state.value.items
        .flatMap { it.txnExtras.tags }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .map { it.key }

    /** 设置某笔流水的标签：写回 extras，保留其它扩展属性；标签清空且无其它属性时把 extras 置回 null。 */
    fun setTags(txn: LedgerTransaction, tags: List<String>) {
        storeScope.launch {
            catching {
                val extras = txn.txnExtras.withTags(tags)
                container.repository.upsert(
                    txn.copy(extras = if (extras == TxnExtras.EMPTY) null else extras.encode())
                )
            }
        }
    }

    fun delete(id: String) {
        storeScope.launch { catching { container.repository.delete(id) } }
    }

    /**
     * 修正流水：改商户名 / 消费平台 / 备注。
     * - 自动抓取的商户名经常缺失（显示「未知名交易」），用户需要能补全；
     * - 平台识别可能不准或缺失，用户改过后以 `USER` 源为准（自动流程不得覆盖）。
     * 走 [applyTxnEdit] 统一处理（去空白 + 重算指纹），复用既有 `upsert` 写回。
     */
    fun updateCounterparty(
        txn: LedgerTransaction,
        counterparty: String,
        note: String?,
        platformId: String = txn.platformId,
    ) {
        storeScope.launch {
            catching {
                container.repository.upsert(
                    applyTxnEdit(
                        txn = txn,
                        counterparty = counterparty,
                        note = note,
                        platformId = platformId,
                        fingerprintOf = container.duplicateResolver::fingerprintOf,
                    ),
                )
            }
        }
    }

    /**
     * 改金额 / 日期。
     *
     * - **符号与类型不变**（见 [applyAmountAndDateEdit]）：只替换绝对值，避免"改个金额把支出变成收入"；
     * - **重算指纹**：指纹材料含 `amountMinor`，不重算则跨渠道去重再也匹配不上；
     * - 调用方（UI）已按 [TxnEditRules] 在「已关联订单/退款/划转」时禁用这两个输入框，
     *   这里再兜一道：即便被绕过也不写入，避免破坏抵扣对账与配对。
     *
     * @param amountMinor 用户输入金额的**绝对值**（单位分）
     */
    fun updateAmountAndDate(txn: LedgerTransaction, amountMinor: Long, occurredAtMillis: Long) {
        if (!TxnEditRules.canEdit(txn) || !TxnEditRules.canEditAmountAndDate(txn)) return
        storeScope.launch {
            catching {
                container.repository.upsert(
                    applyAmountAndDateEdit(
                        txn = txn,
                        amountMinor = amountMinor,
                        occurredAtMillis = occurredAtMillis,
                        fingerprintOf = container.duplicateResolver::fingerprintOf,
                    ),
                )
            }
        }
    }

    /**
     * 一次保存全部编辑（商户 / 备注 / 平台 / 金额 / 日期）。
     *
     * 用**单次 upsert** 而非分别调用 [updateCounterparty] 与 [updateAmountAndDate]：
     * 后者是两条并发协程各基于旧副本 copy 后整行写入，后写的会覆盖先写的，
     * 造成"改了商户和金额，只剩一个生效"。
     *
     * 约束在这里再兜一道：不可编辑的流水直接返回；已关联订单/退款/划转时忽略金额与日期。
     */
    fun saveEdits(
        txn: LedgerTransaction,
        counterparty: String,
        note: String?,
        platformId: String,
        amountMinor: Long? = null,
        occurredAtMillis: Long? = null,
        type: TxnType = txn.type,
        /**
         * 这条流水**吸收掉**的记录数（合并链主记录）。
         *
         * 必须由调用方传入：类型切换的阻断判据之一就是"合并链主记录改类型会让两侧口径不一致"，
         * 若这里默认 0 就等于把这条判据在 Store 侧漏掉 —— UI 侧 chips 已禁用，但纵深防御必须一致。
         */
        absorbedCount: Int = 0,
    ) {
        if (!TxnEditRules.canEdit(txn)) return
        val amountDateAllowed = TxnEditRules.canEditAmountAndDate(txn)
        // 类型切换比改金额更严：已关联订单/退款/划转、已并入、合并链主记录都不许改类型
        val typeAllowed = canSwitchType(txn, absorbedCount)
        storeScope.launch {
            catching {
                container.repository.upsert(
                    applyFullEdit(
                        txn = txn,
                        counterparty = counterparty,
                        note = note,
                        platformId = platformId,
                        amountMinor = if (amountDateAllowed) amountMinor else null,
                        occurredAtMillis = if (amountDateAllowed) occurredAtMillis else null,
                        type = if (typeAllowed) type else txn.type,
                        fingerprintOf = container.duplicateResolver::fingerprintOf,
                    ),
                )
            }
        }
    }

    /** 用户纠正分类：写回 + 存入学习记忆 */
    fun correctCategory(txn: LedgerTransaction, category: Category) {
        storeScope.launch {
            catching {
                container.repository.assignCategory(txn.id, category.id, 1f)
                container.repository.markStatus(txn.id, TxnStatus.CONFIRMED)
                if (txn.counterparty.isNotBlank()) {
                    container.correctionLearner.remember(txn.counterparty, category.id)
                }
            }
        }
    }

    /** 标记为「非消费」：退款 / 内部划转，立即从统计里剔除 */
    fun markTransfer(txn: LedgerTransaction) {
        storeScope.launch {
            catching {
                container.repository.upsert(txn.copy(type = TxnType.TRANSFER, status = TxnStatus.CONFIRMED))
            }
        }
    }

    /**
     * 切换收支类型（支出 ⇄ 收入）：符号随类型翻转，并**重算指纹**（纯函数见 [applyTypeSwitch]）。
     *
     * 形状照 [markTransfer]：一次 `upsert` 写回整行，不新增 DAO 方法、不改 `LedgerRepository`
     * 接口、不加 Migration —— 与「标为内部划转」是同一类操作。
     *
     * ⚠️ **不调 `store.load()`**：那会把 `loading` 拉回 true 闪一下 LoadingBox 并重订阅，
     * 打断用户当前的筛选/展开/选中月状态。本 Store 是 Flow 驱动的，`upsert` 后
     * InvalidationTracker 会自动重发、`visibleItems()` 在组合期重算 ⇒ 状态全保留。
     */
    fun switchType(txn: LedgerTransaction, absorbedCount: Int = 0) {
        if (!canSwitchType(txn, absorbedCount)) return
        storeScope.launch {
            catching {
                container.repository.upsert(
                    applyTypeSwitch(
                        txn = txn,
                        newType = nextType(txn),
                        fingerprintOf = container.duplicateResolver::fingerprintOf,
                    ),
                )
            }
        }
    }

    fun visibleItems(): List<LedgerTransaction> {
        val s = _state.value
        var list = s.items
        if (!s.showTransfers) list = list.filter { it.type != TxnType.TRANSFER && it.type != TxnType.REFUND }
        // 搜索范围：商户 / 消费平台展示名 / 备注 / 金额（口径与测试见 LedgerQuery.kt）
        if (s.query.isNotBlank()) list = list.filter { matchesQuery(it, s.query) }
        s.tagFilter?.let { tag -> list = list.filter { tag in it.txnExtras.tags } }
        // 消费平台筛选：空串按「未知」处理（历史数据与识别失败都落 unknown）
        s.platformFilter?.let { pid ->
            list = list.filter {
                it.platformId.ifBlank { com.autoledger.core.model.platform.PlatformCatalog.UNKNOWN_ID } == pid
            }
        }
        return list
    }
}
