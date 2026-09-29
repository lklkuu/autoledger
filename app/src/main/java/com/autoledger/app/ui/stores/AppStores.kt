package com.autoledger.app.ui.stores

import android.content.Context
import android.net.Uri
import com.autoledger.app.di.AppContainer
import com.autoledger.core.database.RefundAllocationEntity
import com.autoledger.core.model.Category
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MetricResult
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.TxnExtras
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
        if (s.query.isNotBlank()) {
            val q = s.query.trim()
            list = list.filter {
                it.counterparty.contains(q, true) || it.note?.contains(q, true) == true ||
                    (kotlin.math.abs(it.amountMinor) / 100.0).toString().contains(q)
            }
        }
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
        val cushionMinor: Long = 0L,
        val currentMinor: Long = 0L,
        val monthlySurplusMinor: Long = 0L,
        val monthlyExpenseMinor: Long = 0L,
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
        val month = TimeRange.thisMonth(System.currentTimeMillis())
        combine(
            container.settings.goal,
            container.repository.observeRange(month.startMillis, month.endInclusiveMillis, true),
        ) { goal, txns -> goal to txns }
            .flowOn(Dispatchers.Default)
            .collect { (goal, txns) ->
                val expense = ExpenseMath.netExpenseMinor(txns).coerceAtLeast(0L)
                val income = txns.filter { it.type == TxnType.INCOME }.sumOf { kotlin.math.abs(it.amountMinor) }
                _state.value = State(
                    targetMinor = goal.targetMinor,
                    cushionMinor = goal.cushionMinor,
                    currentMinor = goal.currentMinor,
                    monthlySurplusMinor = income - expense,
                    monthlyExpenseMinor = expense,
                )
            }
    }

    fun saveGoal(targetMinor: Long, cushionMinor: Long, currentMinor: Long) {
        // B4：settings.goal 是 StateFlow，updateGoal 后 combine 会自动重新发射，无需手动 load()。
        container.settings.updateGoal(FreedomGoal(targetMinor, cushionMinor, currentMinor))
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
    )

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val facts: Facts = Facts(),
        val metrics: List<MetricResult> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：改为实例级作用域 + observeRange 订阅。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        observeJob = scope.launch { observe() }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        val month = TimeRange.thisMonth(System.currentTimeMillis())
        val zone = ZoneId.systemDefault()
        // 注意：includeTransfers=false 会连 REFUND 一起剔除（见 RoomLedgerRepository.filterTypes），
        // 本页口径需要退款参与，故取全量后自行剔除内部划转（口径计算见 computeInsightsFacts）。
        container.repository.observeRange(month.startMillis, month.endInclusiveMillis, includeTransfers = true)
            .flowOn(Dispatchers.Default)
            .catch { e -> _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败") }
            .collect { allMonth ->
                val days = LocalDate.now(zone).dayOfMonth.coerceAtLeast(1)
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
                _state.value = State(loading = false, facts = facts, metrics = metrics)
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
                    sourceId = "manual",
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
