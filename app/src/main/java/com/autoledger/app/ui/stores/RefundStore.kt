package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.database.RefundAllocationEntity
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.refund.DeductionKind
import com.autoledger.core.model.refund.OrderDeduction
import com.autoledger.core.model.refund.OrderRefundState
import com.autoledger.core.model.refund.OrderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

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
