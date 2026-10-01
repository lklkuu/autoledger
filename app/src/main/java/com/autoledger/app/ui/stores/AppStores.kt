package com.autoledger.app.ui.stores

import android.content.Context
import android.net.Uri
import com.autoledger.app.di.AppContainer
import com.autoledger.core.database.RefundAllocationEntity
import com.autoledger.core.model.Category
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.FreedomMath
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.TxnExtras
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.txnExtras
import com.autoledger.core.model.refund.DeductionKind
import com.autoledger.core.model.refund.OrderDeduction
import com.autoledger.core.model.refund.OrderRefundState
import com.autoledger.core.model.refund.OrderStatus
import com.autoledger.feature.capture.CaptureSource
import com.autoledger.feature.capture.PermissionState
import com.autoledger.feature.stats.PlatformShareMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * UI 状态容器（Store）。
 *
 * 与 Android ViewModel 的区别：不挂 ViewModelStore，而是由界面 remember 持有，避免 Factory/Hilt 样板；
 * 每个 Store 都遵循同一套 V2 协议：`State(loading, error, data) + load()`。
 * 需要**长驻订阅**的 Store（HomeStore）自带可取消作用域与 close()，其余是一次性任务型。
 */

/**
 * Store 后台协程的异常兜底：任何未捕获异常都只记录日志，绝不带崩进程。
 * （HomeStore 的 Flow 内部另有 .catch 收敛为可见错误态，这里是双保险。）
 */
private val storeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    android.util.Log.e("AutoLedger", "Store 后台任务异常", throwable)
}

/**
 * 共享作用域：仅服务「一次性任务型」Store（LedgerStore / CaptureStore / SettingsStore 的 load 跑完即结束）。
 * HomeStore 需要长驻订阅，因此**单独持有自己的可取消作用域**，见其属性 [HomeStore.scope]。
 */
private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)

/**
 * [runCatching] 的「协程感知」版本：放行 [CancellationException]，只把真实错误收敛为 Result。
 *
 * 直接使用 runCatching 会连取消异常一起吞掉 —— 协程被取消后仍继续执行后续代码，
 * 破坏结构化并发（订阅已取消却还在写状态）。这里把取消重新抛出，语义才正确。
 */
private inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

// ------------------------------------------------------------------ 今日

class HomeStore(private val container: AppContainer) {

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val todayMinor: Long = 0L,
        /** 本月净支出（已扣退款） */
        val monthMinor: Long = 0L,
        /** 本月付款总额（毛支出，未扣退款） */
        val monthGrossMinor: Long = 0L,
        /** 本月退款合计（供账单"退款冲抵"展示） */
        val monthRefundMinor: Long = 0L,
        val realHourly: Double = 0.0,
        val recent: List<LedgerTransaction> = emptyList(),
        val categories: Map<String, Category> = emptyMap(),
        val metrics: List<MetricResult> = emptyList(),
        val pendingReview: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 实例级作用域：每个 HomeStore 独占一条**可取消**的生命周期。
     *
     * 之前所有 Store 共用一个进程级 storeScope（永不取消），而 Dashboard 每次进入都会
     * remember 一个新的 HomeStore 并新开一条数据库订阅，退出后旧订阅仍在跑 ——
     * 「首页 ↔ 其它页」来回几次就线性泄漏几条订阅。改为实例级后，界面 onDispose 调 [close] 即可精确回收。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)

    /** 实时订阅句柄。 */
    private var observeJob: Job? = null

    /**
     * 启动/重启实时订阅（幂等）：写入后由 Room Flow 触发自动刷新。
     * 重复调用（含错误页「重试」）会先取消旧订阅再重建，不会叠加。
     */
    fun load() {
        observeJob?.cancel()
        // 不要写成 `_state.value = State()`：那会把 loading 从 false 拉回 true 且清空已有数据，
        // 每次刷新/重试都闪一下 LoadingBox。这里只翻转 loading 与错误位，保留旧数据（R6）。
        _state.value = _state.value.copy(loading = true, error = null)
        observeJob = scope.launch { observe() }
    }

    /** 释放数据库订阅；由 Compose 的 DisposableEffect 在离开首页时调用（配合实例级作用域消除泄漏，R1）。 */
    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    /**
     * 同时订阅两条 Flow 并合成一次聚合：
     * - 本月流水（observeSince）：驱动今日/本月金额、最近流水、统计卡片；
     * - 待确认数量（observeRawCount）：**独立订阅**，与时间窗口解耦 ——
     *   补录一笔更早日期的流水时窗口集合不变，但待确认卡片仍要刷新（R3）。
     */
    private suspend fun observe() {
        val month = TimeRange.thisMonth(System.currentTimeMillis())
        combine(
            container.repository.observeSince(month.startMillis),
            container.repository.observeRawCount(),
        ) { rawList, rawCount -> clipToMonthSpending(rawList, month) to rawCount }
            .flowOn(Dispatchers.Default) // 过滤/聚合移出主线程（R5）
            .catch { e ->
                // 上游（Room Flow）发射异常不能打崩进程，收敛为可见错误态（R2）。
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
            .collect { (monthTxns, rawCount) ->
                applyResult(catching { compute(month, monthTxns, rawCount) })
            }
    }

    private fun applyResult(result: Result<State>) {
        result.onSuccess { _state.value = it }
            // 失败时保留旧数据，只补充错误信息，避免整屏清空。
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "加载失败") }
    }

    /**
     * @param monthTransactions Flow 推送的本月流水（已剔除内部划转、**保留退款**，并裁剪到本月窗口，见 [clipToMonthSpending]）。
     * @param rawCount Flow 推送的全表待确认数量（与时间窗口解耦）。
     */
    private suspend fun compute(
        month: TimeRange,
        monthTransactions: List<LedgerTransaction>,
        rawCount: Int,
    ): State = withContext(Dispatchers.Default) { // 排序/求和等 CPU 聚合移出主线程（R5）
        val now = System.currentTimeMillis()
        val today = TimeRange.today(now)
        val todayTxns = monthTransactions.filter { it.occurredAtMillis in today.startMillis..today.endInclusiveMillis }
        val metrics = container.metricRegistry.providers()
            .filter { it.id != PlatformShareMetric.PLATFORM_ID }
            .map { it.compute(month, container.repository) }
        State(
            loading = false,
            // 净支出（退款冲抵），口径唯一真源见 ExpenseMath
            todayMinor = ExpenseMath.netExpenseMinor(todayTxns).coerceAtLeast(0L),
            monthMinor = ExpenseMath.netExpenseMinor(monthTransactions).coerceAtLeast(0L),
            // 付款总额（毛支出）：与净支出/退款同源，均走 ExpenseMath，避免 UI 手写 sumOf 造成口径漂移
            monthGrossMinor = ExpenseMath.grossExpenseMinor(monthTransactions),
            monthRefundMinor = ExpenseMath.refundMinor(monthTransactions),
            realHourly = container.settings.wage.value.realHourly,
            recent = monthTransactions.sortedByDescending { it.occurredAtMillis }.take(4),
            categories = container.repository.listCategories().associateBy { it.id },
            metrics = metrics,
            pendingReview = rawCount,
        )
    }
}

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
    ) {
        if (!TxnEditRules.canEdit(txn)) return
        val amountDateAllowed = TxnEditRules.canEditAmountAndDate(txn)
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

// ------------------------------------------------------------------ 采集

class CaptureStore(private val container: AppContainer) {

    data class SourceRow(
        val source: CaptureSource,
        val state: PermissionState,
        val hint: String,
    )

    data class State(
        val rows: List<SourceRow> = emptyList(),
        val rawQueue: List<LedgerTransaction> = emptyList(),
        val working: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：rawQueue 用 observeRaw 订阅（待确认队列实时刷新）；rows 权限状态依赖 context，进入时刷新一次。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load(context: Context) {
        observeJob?.cancel()
        observeJob = scope.launch { observe() }
        refreshRows(context)
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    /** 待确认队列实时订阅（写操作后自动刷新）。 */
    private suspend fun observe() {
        container.repository.observeRaw()
            .flowOn(Dispatchers.Default)
            .collect { raw ->
                _state.value = _state.value.copy(
                    rawQueue = raw.sortedByDescending { it.occurredAtMillis },
                )
            }
    }

    /** 渠道权限状态（依赖 context，非 Room 数据，进入页面时刷新一次即可）。 */
    fun refreshRows(context: Context) {
        storeScope.launch {
            val rows = container.captureSources.map { source ->
                val st = source.permissionState(context)
                SourceRow(source, st, source.statusHint(st))
            }
            _state.value = _state.value.copy(rows = rows)
        }
    }

    /** 拉历史：短信 / 账单文件这类需要主动补录的渠道 */
    fun pullBacklog(context: Context, sourceId: String, uri: Uri? = null) {
        val source = container.captureRegistry.find(sourceId) ?: return
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    source.pullBacklog(context, uri?.let { mapOf("uri" to it) } ?: emptyMap())
                }
            }.onSuccess { envelopes ->
                // 一次补录可能几十条：放进**单个数据库事务**——
                // 把多次提交压缩成一次，缩小"入了一半"的不一致窗口（R5）；
                // 单条解析失败用 catching 容错，不影响其余批次。
                val outcomes = catching {
                    container.repository.inTransaction {
                        envelopes.map { envelope ->
                            catching { container.ingestPipeline.ingest(envelope) }.isSuccess
                        }
                    }
                }.getOrElse { List(envelopes.size) { false } }
                val ok = outcomes.count { it }
                _state.value = _state.value.copy(
                    working = false,
                    message = "已采集 ${envelopes.size} 条，成功入账 $ok 条",
                )
            }.onFailure {
                _state.value = _state.value.copy(working = false, message = "采集失败：${it.message}")
            }
            // B4：待确认队列由 observeRaw 自动刷新，无需手动 refresh。
        }
    }
}

// ------------------------------------------------------------------ 自由基金 / 发现

class FreedomStore(private val container: AppContainer) {

    data class State(
        val targetMinor: Long = 0L,
        val currentMinor: Long = 0L,
        /** 到手月薪（分）：来自时薪页的 WageProfile，两个「已攒」公式的收入项。 */
        val monthlyNetSalaryMinor: Long = 0L,
        val monthlySurplusMinor: Long = 0L,
        /** 当月支出（分）：「当月已攒」公式的扣减项，由 ExpenseMath 口径算出。 */
        val monthlyExpenseMinor: Long = 0L,
        /** 起始月起到现在的累计支出（分）：「累计已攒」公式的扣减项。 */
        val cumulativeExpenseMinor: Long = 0L,
        /**
         * 「开始使用的月份」= 账本**最早一笔流水所在的月份**（年月序号，见 [FreedomMath.yearMonthIndex]）。
         * 账本为空时为 `null` —— 此时 [monthsUsed] = 0、累计已攒按 0 处理。
         *
         * 刻意**不用** App 安装时间：用户可能装了很久才开始记，也可能补录历史账单。
         */
        val startYearMonth: Int? = null,
        /** 使用月数（含起始月与当月），账本为空时为 0。见 [FreedomMath.monthsUsed]。 */
        val monthsUsed: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：改为实例级作用域 + Flow 订阅（settings.goal 是 StateFlow，可直接 combine）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        observeJob = scope.launch { observe() }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        val zone = ZoneId.systemDefault()
        // 三个源一起订阅：目标/存款 + 到手月薪 + **全量**流水。
        // 之所以从「当月窗口」换成全量：累计口径需要两样当月窗口给不了的东西 ——
        //   ① 最早一笔流水所在月份（起始月）；② 起始月至今的累计支出。
        // 当月支出就在内存里按月份窗口裁出来，省掉一路 observeRange 订阅。
        // 月薪 / 流水 / 目标都是 Flow，任一侧变化都会重新发射 → 两个「已攒」实时刷新。
        combine(
            container.settings.goal,
            container.settings.wage,
            container.repository.observeAll(includeTransfers = true),
        ) { goal, wage, txns -> Triple(goal, wage, txns) }
            .flowOn(Dispatchers.Default)
            .collect { (goal, wage, txns) ->
                val now = System.currentTimeMillis()
                val month = TimeRange.thisMonth(now)
                val monthTxns = txns.filter { it.occurredAtMillis in month.startMillis..month.endInclusiveMillis }
                val expense = ExpenseMath.netExpenseMinor(monthTxns).coerceAtLeast(0L)
                val income = monthTxns.filter { it.type == TxnType.INCOME }.sumOf { kotlin.math.abs(it.amountMinor) }
                // 累计支出只算到「现在」：未来日期的流水（预授权 / 跨时区账单）不该把累计支出抬高。
                val cumulativeExpense = ExpenseMath
                    .netExpenseMinor(txns.filter { it.occurredAtMillis <= now })
                    .coerceAtLeast(0L)
                // 起始月取全量流水里最早一笔（含未来流水），口径见 FreedomMath.earliestYearMonthIndex。
                val startYearMonth = FreedomMath.earliestYearMonthIndex(txns, zone)
                _state.value = State(
                    targetMinor = goal.targetMinor,
                    currentMinor = goal.currentMinor,
                    monthlyNetSalaryMinor = wage.monthlyNetSalaryMinor,
                    monthlySurplusMinor = income - expense,
                    monthlyExpenseMinor = expense,
                    cumulativeExpenseMinor = cumulativeExpense,
                    startYearMonth = startYearMonth,
                    monthsUsed = FreedomMath.monthsUsed(startYearMonth, FreedomMath.yearMonthIndex(now, zone)),
                )
            }
    }

    /**
     * 保存目标。「已攒」不再由用户填写，因此这里只落「目标金额 + 当前存款」。
     *
     * @param currentMinor 当前存款（分）。它同时是「已攒」公式的加项，
     *   页面上的输入框未保存也会实时参与展示（见 FreedomScreen）。
     */
    fun saveGoal(targetMinor: Long, currentMinor: Long) {
        // B4：settings.goal 是 StateFlow，updateGoal 后 combine 会自动重新发射，无需手动 load()。
        container.settings.updateGoal(FreedomGoal(targetMinor, currentMinor))
    }
}

class InsightsStore(private val container: AppContainer) {

    data class Facts(
        val largestTxn: LedgerTransaction? = null,
        val topMerchant: Pair<String, Long>? = null,
        val weekdayVsWeekend: Pair<Long, Long> = 0L to 0L,
        val avgDailyMinor: Long = 0L,
        val unclassifiedCount: Int = 0,
        /** 本月最近几笔（含收入与退款，不含内部划转），供「发现」页直接展示，避免为此再挂一个 HomeStore（R1）。 */
        val recent: List<LedgerTransaction> = emptyList(),
        /** 分类字典，供 [recent] 渲染类目名。 */
        val categories: Map<String, Category> = emptyMap(),
        /**
         * 该区间内的**非划转流水条数**（`spending.size`）。
         *
         * 空态判定必须用它，不能靠"金额都是 0"来猜：一个只有内部划转的月份
         * 金额全为 0 但确实"没有可展示的收支记录"，而一个净额为 0 的月份
         * （收入支出正好相等）却是有记录的 —— 两者不能混。
         */
        val recordCount: Int = 0,
    )

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val facts: Facts = Facts(),
        val metrics: List<MetricResult> = emptyList(),
        /** 当前查看的月份。默认当月 ⇒ 与「按月查看」上线前的行为完全一致。 */
        val selectedMonth: YearMonth = YearMonth.now(),
        /** 可选月份，**降序**（当前月在最前）。从账本最早一笔流水所在月到当前月。 */
        val availableMonths: List<YearMonth> = emptyList(),
        val canGoPrev: Boolean = false,
        val canGoNext: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 选中月份的**唯一真源**。
     *
     * ⚠️ **只能经 [selectMonth] 修改** —— 它负责 cancel 旧订阅再重订阅。
     * UI 若直接改这里（或自己另起一个 observe 协程），新旧月份的 Flow 会同时写 [_state]，
     * 页面就会出现跳月、闪回、数据错乱的竞态。
     */
    private val _selected = MutableStateFlow(YearMonth.now())

    /** 最早流水月：懒算一次后缓存。切月不重算，避免每次切月都全表扫一遍求最早时间。 */
    private var earliestMonth: YearMonth? = null
    private var monthsBuilt = false

    // B4：改为实例级作用域 + observeRange 订阅。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        observeJob = scope.launch { observe() }
    }

    /** 切换查看月份（UI 必须走这里，见 [_selected] 的说明）。越界会被 [clampMonth] 夹回合法范围。 */
    fun selectMonth(target: YearMonth) {
        val clamped = clampMonth(target)
        if (clamped == _selected.value) return
        _selected.value = clamped
        load()
    }

    fun prevMonth() = selectMonth(_selected.value.minusMonths(1))

    fun nextMonth() = selectMonth(_selected.value.plusMonths(1))

    /** 把目标月夹到 `[最早流水月, 当前月]`；账本为空（还没算出最早月）时只允许当前月。 */
    private fun clampMonth(target: YearMonth): YearMonth {
        val now = YearMonth.now()
        val lower = earliestMonth ?: return now
        return when {
            target.isAfter(now) -> now
            target.isBefore(lower) -> lower
            else -> target
        }
    }

    /**
     * 构建可选月份列表（降序）。
     *
     * 只在首次加载时算一次并缓存。账本为空 ⇒ 退化为 `[当前月]`（不报错、不死循环）。
     * `while` 留了 240 个月（20 年）上限兜底：即便最早流水是脏数据（如 1970 年），
     * 也不会构造出上千个 chip 把页面拖死。
     */
    private suspend fun buildMonthsIfNeeded() {
        if (monthsBuilt) return
        monthsBuilt = true
        val zone = ZoneId.systemDefault()
        val earliestIndex = runCatching {
            FreedomMath.earliestYearMonthIndex(
                txns = container.repository.listAll(includeTransfers = true),
                zone = zone,
            )
        }.getOrNull()
        earliestMonth = earliestIndex?.let { YearMonth.of(it / 12, it % 12 + 1) }

        val now = YearMonth.now()
        val start = earliestMonth?.takeIf { it.isBefore(now) } ?: now
        val months = ArrayList<YearMonth>()
        var cursor = now
        while (!cursor.isBefore(start) && months.size < 240) {
            months += cursor
            cursor = cursor.minusMonths(1)
        }
        _state.value = _state.value.copy(availableMonths = months)
    }

    /**
     * 「日均花销」的分母：**已过去的天数**。
     *
     * 当前月 = 今天几号；过去月 = 该月总天数（如 9 月 = 30）。
     * 若一律用「今天几号」，查看已过完的 9 月会变成「月支出 ÷ 1」→ 日均虚高 30 倍。
     */
    private fun elapsedDays(yearMonth: YearMonth, zone: ZoneId): Int {
        val today = LocalDate.now(zone)
        val current = YearMonth.from(today)
        return when {
            yearMonth == current -> today.dayOfMonth.coerceAtLeast(1)
            yearMonth.isAfter(current) -> 1
            else -> yearMonth.lengthOfMonth()
        }.coerceAtLeast(1)
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        buildMonthsIfNeeded()
        val zone = ZoneId.systemDefault()
        // 读一次快照：_selected 可能在本轮订阅期间被 selectMonth 改掉，那会由 load() 重新订阅。
        val selected = _selected.value
        val month = TimeRange.monthOf(selected, System.currentTimeMillis())
        // 注意：includeTransfers=false 会连 REFUND 一起剔除（见 RoomLedgerRepository.filterTypes），
        // 本页口径需要退款参与，故取全量后自行剔除内部划转（口径计算见 computeInsightsFacts）。
        container.repository.observeRange(month.startMillis, month.endInclusiveMillis, includeTransfers = true)
            .flowOn(Dispatchers.Default)
            .catch { e -> _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败") }
            .collect { allMonth ->
                val days = elapsedDays(selected, zone)
                val facts = computeInsightsFacts(
                    allMonth = allMonth,
                    categories = container.repository.listCategories().associateBy { it.id },
                    days = days,
                    zone = zone,
                )
                val metrics = container.metricRegistry.providers().filter {
                    it.id == com.autoledger.feature.stats.MerchantTopMetric.MERCHANT_ID ||
                        it.id == PlatformShareMetric.PLATFORM_ID ||
                        it.id == com.autoledger.feature.stats.TimeCostMetric.TIME_COST_ID
                }.map { it.compute(month, container.repository) }
                // 用 copy 而非新建 State：保住 availableMonths 这类"不随月份重算"的字段。
                val months = _state.value.availableMonths
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    facts = facts,
                    metrics = metrics,
                    selectedMonth = selected,
                    canGoPrev = months.any { it.isBefore(selected) },
                    canGoNext = selected.isBefore(YearMonth.now()),
                )
            }
    }
}

// ------------------------------------------------------------------ 设置

class SettingsStore(private val container: AppContainer) {

    data class State(
        val message: String? = null,
        val working: Boolean = false,
        val learnedRules: Int = 0,
        val transactionCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh() {
        storeScope.launch {
            val learned = catching { container.correctionLearner.learnedCount() }.getOrDefault(0)
            val count = catching { container.repository.countAll() }.getOrDefault(0)
            _state.value = _state.value.copy(learnedRules = learned, transactionCount = count)
        }
    }

    fun exportJson(uri: Uri) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val json = container.backupManager.exportJson("1.0.0", android.os.Build.MODEL)
                    container.applicationContext.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: error("无法写入文件")
                }
            }.onSuccess { _state.value = _state.value.copy(working = false, message = "导出成功") }
                .onFailure { _state.value = _state.value.copy(working = false, message = "导出失败：${it.message}") }
        }
    }

    fun importJson(uri: Uri) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val text = container.applicationContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                    container.backupManager.import(text, com.autoledger.core.backup.BackupManager.MergeStrategy.MERGE_BY_ID)
                }
            }.onSuccess { outcome ->
                _state.value = _state.value.copy(
                    working = false,
                    message = "导入成功：${outcome.transactionsUpserted} 笔流水（档案版本 v${outcome.fileVersion} → v${outcome.migratedToVersion}）",
                )
            }.onFailure { _state.value = _state.value.copy(working = false, message = "导入失败：${it.message}") }
            refresh()
        }
    }

    /** 加密导出：口令派生密钥 + AES-GCM 密封，适合「存网盘 / 发别人」的场景。 */
    fun exportEncrypted(uri: Uri, passphrase: CharArray) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val json = container.backupManager.exportEncrypted("1.0.0", android.os.Build.MODEL, passphrase)
                    container.applicationContext.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: error("无法写入文件")
                }
            }.onSuccess { _state.value = _state.value.copy(working = false, message = "加密导出成功") }
                .onFailure { _state.value = _state.value.copy(working = false, message = "加密导出失败：${it.message}") }
        }
    }

    /** 加密导入：先注入口令，再走统一导入管线；口令用完即弃，不落盘。 */
    fun importEncrypted(uri: Uri, passphrase: CharArray) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val text = container.applicationContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                    container.backupManager.currentPassphrase = passphrase
                    try {
                        container.backupManager.import(text, com.autoledger.core.backup.BackupManager.MergeStrategy.MERGE_BY_ID)
                    } finally {
                        container.backupManager.currentPassphrase = null
                    }
                }
            }.onSuccess { outcome ->
                _state.value = _state.value.copy(
                    working = false,
                    message = "加密导入成功：${outcome.transactionsUpserted} 笔流水",
                )
            }.onFailure { e ->
                val msg = if (e is javax.crypto.AEADBadTagException || e.cause is javax.crypto.AEADBadTagException) {
                    "口令错误，请重新输入"
                } else {
                    "导入失败：${e.message}"
                }
                _state.value = _state.value.copy(working = false, message = msg)
            }
        }
    }

    fun clearAll() {
        storeScope.launch {
            catching { container.repository.clearAllTransactions() }
            refresh()
        }
    }

    fun resetLearning() {
        storeScope.launch {
            catching { container.correctionLearner.resetLearning() }
            refresh()
        }
    }
}


// ------------------------------------------------------------------ 订单 / 退款

/** 订单与退款：对账视图 + 发起退款。任务型 Store。 */
class RefundStore(private val container: AppContainer) {

    data class State(
        val loading: Boolean = true,
        val orders: List<OrderRefundState> = emptyList(),
        val selected: OrderRefundState? = null,
        val allocations: List<RefundAllocationEntity> = emptyList(),
        val message: String? = null,
        val hint: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val service = com.autoledger.app.refund.RefundService(container.refundRepository)

    // B4：实例级作用域 + observeOrders 订阅（订单列表实时刷新）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        observeJob = scope.launch { observe() }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        container.refundRepository.observeOrders()
            .flowOn(Dispatchers.Default)
            .collect { orders ->
                _state.value = _state.value.copy(loading = false, orders = orders)
            }
    }

    fun select(orderId: String?) {
        storeScope.launch {
            if (orderId == null) {
                _state.value = _state.value.copy(selected = null, allocations = emptyList())
                return@launch
            }
            val detail = catching { container.refundRepository.loadState(orderId) }.getOrNull()
            val allocs = catching {
                container.refundRepository.refundsOf(orderId)
                    .flatMap { container.refundRepository.allocationsOf(it.id) }
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(selected = detail, allocations = allocs)
        }
    }

    /** 发起退款；结果文案直接来自引擎错误码（含下一步提示）。 */
    fun requestRefund(amountMinor: Long) {
        val order = _state.value.selected ?: return
        storeScope.launch {
            val outcome = service.requestRefund(
                orderId = order.orderId,
                amountMinor = amountMinor,
                refundNo = "R${System.currentTimeMillis()}",
            )
            _state.value = _state.value.copy(message = outcome.message, hint = outcome.hint)
            // B4：订单列表由 observeOrders 自动刷新；这里只需重新加载详情。
            select(order.orderId)
        }
    }

    /** 手动登记一笔订单（含抵扣构成），用于在没有订单来源时也能完整走通退款。 */
    fun addOrder(orderNo: String, totalMinor: Long, balanceMinor: Long, pointsMinor: Long, couponMinor: Long) {
        if (orderNo.isBlank() || totalMinor <= 0L) {
            _state.value = _state.value.copy(message = "订单号与总额必填", hint = "请填写正确的订单号与金额")
            return
        }
        storeScope.launch {
            catching {
                val orderId = container.refundRepository.saveOrder(
                    orderNo = orderNo.trim(),
                    counterparty = "手动登记",
                    totalMinor = totalMinor,
                    status = OrderStatus.PAID,
                    occurredAtMillis = System.currentTimeMillis(),
                    refundDeadlineMillis = null,
                    sourceId = CaptureSourceIds.MANUAL,
                    sourceRef = orderNo.trim(),
                )
                val deductions = buildList {
                    if (balanceMinor > 0) add(OrderDeduction("${orderId}:balance", DeductionKind.BALANCE, balanceMinor, 0))
                    if (pointsMinor > 0) add(OrderDeduction("${orderId}:points", DeductionKind.POINTS, pointsMinor, (pointsMinor / 10).toInt().coerceAtLeast(1)))
                    if (couponMinor > 0) add(OrderDeduction("${orderId}:coupon", DeductionKind.COUPON, couponMinor, 1, resourceId = "coupon_$orderNo"))
                }
                container.refundRepository.saveDeductions(orderId, deductions)
            }.onFailure { _state.value = _state.value.copy(message = "登记失败：${it.message}", hint = "") }
                .onSuccess { _state.value = _state.value.copy(message = "已登记订单 $orderNo", hint = "现在可以发起退款") }
            // B4：订单列表由 observeOrders 自动刷新。
        }
    }
}

// ------------------------------------------------------------------ 分类管理

/** 分类管理：增删改分类、设月度预算。任务型 Store（load/save/delete 跑完即止），沿用共享 [storeScope]。 */
class CategoryStore(private val container: AppContainer) {

    data class State(
        val expense: List<Category> = emptyList(),
        val income: List<Category> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：实例级作用域 + observeCategories 订阅。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        observeJob = scope.launch { observe() }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        container.repository.observeCategories()
            .flowOn(Dispatchers.Default)
            .collect { categories ->
                val (expense, income) = groupCategoriesByKind(categories)
                _state.value = _state.value.copy(expense = expense, income = income)
            }
    }

    /** 保存分类；校验不过则只提示、不落库。 */
    fun save(draft: CategoryDraft) {
        val error = draft.validate()
        if (error != null) {
            _state.value = _state.value.copy(message = error)
            return
        }
        storeScope.launch {
            val existing = catching { container.repository.listCategories() }
                .getOrDefault(emptyList()).firstOrNull { it.id == draft.id }
            catching { container.repository.upsertCategory(draft.toCategory(existing)) }
                .onFailure { _state.value = _state.value.copy(message = "保存失败：${it.message}") }
                .onSuccess {
                    // B4：observeCategories Flow 会自动刷新列表，这里只设置操作提示。
                    _state.value = _state.value.copy(message = "已保存「${draft.name.trim()}」")
                }
        }
    }

    fun delete(id: String) {
        storeScope.launch {
            catching { container.repository.deleteCategory(id) }
                .onFailure { _state.value = _state.value.copy(message = "删除失败：${it.message}") }
                .onSuccess {
                    _state.value = _state.value.copy(message = "已删除")
                }
        }
    }
}

// ------------------------------------------------------------------ 自定义消费平台

/**
 * 自定义消费平台管理（设置页入口）。
 *
 * ⚠️ **每次写成功后必须重建进程内目录**（[AppContainer.syncUserPlatformsToCatalog]）：
 * 目录是进程级缓存，只在启动时注入一次的话，新增的平台要等下次冷启动才生效 ——
 * 本次会话里编辑流水的选择器仍是旧的、采集也仍识别不到它（R6 的运行时版本）。
 */
class UserPlatformStore(private val container: AppContainer) {

    data class State(
        /** 启用中的（参与识别与指派）。 */
        val active: List<UserPlatform> = emptyList(),
        /** 已停用的（不再识别，但历史流水仍显示其名称）。 */
        val archived: List<UserPlatform> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)

    fun load() {
        scope.launch { refresh() }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun refresh() {
        val all = catching { container.repository.listUserPlatforms(includeArchived = true) }
            .getOrDefault(emptyList())
        _state.value = _state.value.copy(
            active = all.filter { !it.archived }.sortedBy { it.sortOrder },
            archived = all.filter { it.archived }.sortedBy { it.sortOrder },
        )
    }

    /** 新增或更新一个自定义平台。校验不过只提示、不落库。 */
    fun save(draft: UserPlatformDraft) {
        val error = draft.validate()
        if (error != null) {
            _state.value = _state.value.copy(message = error)
            return
        }
        scope.launch {
            val existing = draft.id?.takeIf { it.isNotBlank() }?.let { id ->
                catching { container.repository.listUserPlatforms(includeArchived = true) }
                    .getOrDefault(emptyList()).firstOrNull { it.id == id }
            }
            val platform = draft.toUserPlatform(existing)
            catching {
                container.repository.upsertUserPlatform(platform)
                container.syncUserPlatformsToCatalog()
            }
                .onFailure { _state.value = _state.value.copy(message = "保存失败：${it.message}") }
                .onSuccess { _state.value = _state.value.copy(message = "已保存「${platform.displayName}」") }
            refresh()
        }
    }

    /**
     * 停用 / 恢复。
     *
     * 停用走**软删除**（`archived = true`，行保留）：历史流水的 `platformId` 指向它，
     * 物理删除会让那些流水变成孤儿 ID、展示塌成「未知平台」（R7）。
     * 恢复就是把它重新写回 `archived = false` —— 复用 upsert，不需要额外的 DAO 方法。
     */
    fun setArchived(id: String, archived: Boolean) {
        scope.launch {
            val current = catching { container.repository.listUserPlatforms(includeArchived = true) }
                .getOrDefault(emptyList()).firstOrNull { it.id == id }
            if (current == null) {
                _state.value = _state.value.copy(message = "这条平台已经不在了")
                return@launch
            }
            catching {
                container.repository.upsertUserPlatform(current.copy(archived = archived))
                container.syncUserPlatformsToCatalog()
            }
                .onFailure { _state.value = _state.value.copy(message = "操作失败：${it.message}") }
                .onSuccess {
                    _state.value = _state.value.copy(
                        message = if (archived) "已停用「${current.displayName}」（历史流水仍显示该名称）"
                        else "已恢复「${current.displayName}」",
                    )
                }
            refresh()
        }
    }
}
