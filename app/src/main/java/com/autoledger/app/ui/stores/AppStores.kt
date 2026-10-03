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
import com.autoledger.core.model.MetricSnapshot
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
        val isCurrentMonth = selected == YearMonth.now()
        // 过去月的窗口两端在订阅时定死即可（该月最后一毫秒不会变）；
        // **当前月不行**：右端必须随「现在」推进 —— observeRange 固定右端会把窗口焊死在订阅时刻，
        // 「今天刚落的流水」要等切页/重试才出现。当前月只给左边界（observeSince），
        // 右端在 collect 内经 TimeRange.monthOf(selected, 实时 now) 夹紧。
        val monthStart = TimeRange.monthOf(selected, System.currentTimeMillis()).startMillis
        val upstream = if (isCurrentMonth) {
            container.repository.observeSince(monthStart)
        } else {
            val fixed = TimeRange.monthOf(selected, System.currentTimeMillis())
            container.repository.observeRange(fixed.startMillis, fixed.endInclusiveMillis, includeTransfers = true)
        }
        // 注意：observeSince 不过滤类型（含 REFUND 与 TRANSFER），本页口径需要退款参与
        //（划转由 computeInsightsFacts / 快照契约自行剔除）。
        upstream
            .flowOn(Dispatchers.Default)
            .catch { e -> _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败") }
            .collect { raw ->
                // 发射时实时窗口：当前月右端 = now（未来日期的流水不得混入本月）；
                // 过去月右端 = 该月最后一毫秒（与订阅的 observeRange 双端一致，此处仅做同一裁剪）。
                val fresh = TimeRange.monthOf(selected, System.currentTimeMillis())
                val allMonth = raw.filter { it.occurredAtMillis <= fresh.endInclusiveMillis }
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
                }.map {
                    // 三卡（商户/平台/时间成本）复用同一份窗口快照：allMonth 已含退款，
                    // 只需剔除内部划转即满足 MetricSnapshot 契约 —— 数据库整月窗口只查一次。
                    val snap = MetricSnapshot(
                        range = fresh,
                        txns = allMonth.filter { it.type != TxnType.TRANSFER },
                    )
                    it.compute(fresh, container.repository, snap)
                }
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
