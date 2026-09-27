package com.autoledger.feature.refund

import com.autoledger.core.model.TxnType
import com.autoledger.core.model.refund.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 退款引擎 —— 全场景单测（金额单位：分）。 */
class RefundEngineTest {

    // ------------------------------------------------------------ 测试夹具

    private val now = 1_700_000_000_000L

    /** 100 元订单 = 余额 60 + 积分 20（200 分）+ 券 20（1 张） */
    private fun order(
        status: OrderStatus = OrderStatus.PAID,
        total: Long = 10_000L,
        balance: Long = 6_000L,
        points: Long = 2_000L,
        pointsQty: Int = 200,
        coupon: Long = 2_000L,
        couponQty: Int = 1,
        couponExpired: Boolean = false,
        couponConsumed: Boolean = false,
        pointsConsumed: Boolean = false,
        reversedBalance: Long = 0L,
        reversedPoints: Long = 0L,
        reversedCoupon: Long = 0L,
        deadline: Long? = null,
        refunds: List<RefundRecord> = emptyList(),
        version: Int = 1,
    ) = OrderRefundState(
        orderId = "o1", orderNo = "NO1", totalMinor = total, status = status,
        occurredAtMillis = now - 86_400_000L, refundDeadlineMillis = deadline,
        deductions = listOf(
            OrderDeduction("d_balance", DeductionKind.BALANCE, balance, 0,
                reversedAmountMinor = reversedBalance),
            OrderDeduction("d_points", DeductionKind.POINTS, points, pointsQty,
                resourceConsumed = pointsConsumed, reversedAmountMinor = reversedPoints),
            OrderDeduction("d_coupon", DeductionKind.COUPON, coupon, couponQty,
                resourceId = "c1", resourceExpired = couponExpired,
                resourceConsumed = couponConsumed, reversedAmountMinor = reversedCoupon),
        ),
        refunds = refunds, version = version,
    )

    private fun request(
        amount: Long,
        key: String = "k1",
        no: String = "R1",
        attempt: Int = 1,
        expectedVersion: Int? = null,
        allowExpired: Boolean = false,
    ) = RefundRequest(no, amount, key, now, expectedVersion, allowExpired, attempt)

    private fun plan(state: OrderRefundState, req: RefundRequest): RefundPlan {
        val result = RefundEngine.evaluate(state, req, now, "r_new")
        return assertIs<RefundResult.Planned>(result, "期望可执行，实际=$result").plan
    }

    private fun rejection(state: OrderRefundState, req: RefundRequest): RefundRejection {
        val result = RefundEngine.evaluate(state, req, now, "r_new")
        return assertIs<RefundResult.Rejected>(result, "期望被拒绝，实际=$result").rejection
    }

    /** 分摊合平：Σ 分摊金额 == 退款额 */
    private fun assertBalanced(p: RefundPlan) =
        assertEquals(p.amountMinor, p.allocations.sumOf { it.amountMinor }, "分摊必须合平")

    private fun alloc(p: RefundPlan, id: String): RefundAllocation =
        p.allocations.first { it.deductionId == id }

    // ------------------------------------------------------------ 全额 / 部分

    @Test
    fun `full refund returns every deduction and closes the order`() {
        val p = plan(order(), request(10_000L))
        assertBalanced(p)
        assertEquals(OrderStatus.REFUNDED, p.nextOrderStatus)
        assertEquals(2, p.newVersion)
        assertEquals(6_000L, alloc(p, "d_balance").amountMinor)
        assertEquals(RefundOutcome.RETURNED, alloc(p, "d_points").outcome)
        assertEquals(200, alloc(p, "d_points").quantity, "积分应整额退还")
        assertEquals(RefundOutcome.RETURNED, alloc(p, "d_coupon").outcome)
        assertEquals(1, alloc(p, "d_coupon").quantity, "全额退应整张返还券")
    }

    @Test
    fun `partial refund splits pro rata and keeps the order refunding`() {
        val p = plan(order(), request(5_000L))
        assertBalanced(p)
        assertEquals(OrderStatus.REFUNDING, p.nextOrderStatus)
        assertEquals(3_000L, alloc(p, "d_balance").amountMinor)          // 60 * 50%
        assertEquals(1_000L, alloc(p, "d_points").amountMinor)           // 20 * 50%
        assertEquals(100, alloc(p, "d_points").quantity)                 // 200 分 * 50%
        // 券：部分退不拆券 → 按占比折算成余额
        val coupon = alloc(p, "d_coupon")
        assertEquals(1_000L, coupon.amountMinor)
        assertEquals(0, coupon.quantity, "部分退款不应退券")
        assertEquals(RefundOutcome.USED_FALLBACK, coupon.outcome)
        assertEquals(1_000L, coupon.fallbackAmountMinor, "折算必须等额，保证可对账")
    }

    @Test
    fun `rounding residual is pushed into the balance item`() {
        // 3333 + 6667 = 10000（无券），退 5000 → floor 造成 1 分残差，必须补齐
        val state = order(balance = 3_333L, points = 6_667L, coupon = 0L, couponQty = 0, total = 10_000L)
        val p = plan(state, request(5_000L))
        assertBalanced(p)
        assertEquals(1_667L, alloc(p, "d_balance").amountMinor, "残差应归入余额项")
    }

    @Test
    fun `second refund only consumes what is left`() {
        val state = order(
            reversedBalance = 6_000L, reversedPoints = 2_000L, reversedCoupon = 2_000L,
        )
        // 全部抵扣已回退 → 无可退项
        assertEquals(RefundRejectCode.NO_DEDUCTION_LEFT, rejection(state, request(1_000L)).code)
    }

    // ------------------------------------------------------------ 幂等

    @Test
    fun `duplicate request returns the existing refund without reverting again`() {
        val existing = RefundRecord("r1", "R1", 2_000L, RefundStatus.APPLIED, "k1", now)
        val state = order(refunds = listOf(existing))
        val result = RefundEngine.evaluate(state, request(2_000L, key = "k1"), now, "r_new")
        val dup = assertIs<RefundResult.Duplicate>(result)
        assertEquals("r1", dup.existing.id)
        assertTrue(dup.message.contains("不会重复回退"))
    }

    @Test
    fun `different idempotency key is treated as a new refund`() {
        val state = order(refunds = listOf(RefundRecord("r1", "R1", 2_000L, RefundStatus.APPLIED, "k_old", now)))
        val p = plan(state, request(3_000L, key = "k2"))
        assertBalanced(p)
        assertEquals(3_000L, p.amountMinor)
    }

    // ------------------------------------------------------------ 金额校验

    @Test
    fun `amount must be positive`() {
        assertEquals(RefundRejectCode.AMOUNT_NOT_POSITIVE, rejection(order(), request(0L)).code)
        assertEquals(RefundRejectCode.AMOUNT_NOT_POSITIVE, rejection(order(), request(-1L)).code)
    }

    @Test
    fun `amount above the order total is rejected`() {
        val r = rejection(order(), request(10_001L))
        assertEquals(RefundRejectCode.AMOUNT_EXCEEDS_TOTAL, r.code)
        assertTrue(r.message.contains("超过订单总额"))
    }

    @Test
    fun `amount above the remaining refundable is rejected`() {
        val state = order(refunds = listOf(RefundRecord("r1", "R1", 4_000L, RefundStatus.APPLIED, "k_old", now)))
        val r = rejection(state, request(7_000L))
        assertEquals(RefundRejectCode.AMOUNT_EXCEEDS_REMAINING, r.code)
        assertTrue(r.message.contains("剩余可退"))
    }

    @Test
    fun `rejected refunds do not consume the quota`() {
        // 被拒绝的退款单不应占用额度
        val state = order(refunds = listOf(RefundRecord("r1", "R1", 9_000L, RefundStatus.REJECTED, "k_old", now)))
        val p = plan(state, request(9_000L))
        assertBalanced(p)
    }

    @Test
    fun `pending and processing refunds do reserve the quota`() {
        val state = order(refunds = listOf(RefundRecord("r1", "R1", 6_000L, RefundStatus.PROCESSING, "k_old", now)))
        assertEquals(RefundRejectCode.AMOUNT_EXCEEDS_REMAINING, rejection(state, request(5_000L)).code)
    }

    // ------------------------------------------------------------ 订单状态

    @Test
    fun `pending payment order cannot be refunded`() {
        val r = rejection(order(status = OrderStatus.PENDING_PAYMENT), request(1_000L))
        assertEquals(RefundRejectCode.ORDER_NOT_PAID, r.code)
        assertTrue(r.message.contains("尚未支付"))
    }

    @Test
    fun `cancelled order must go through cancellation flow`() {
        val r = rejection(order(status = OrderStatus.CANCELLED), request(1_000L))
        assertEquals(RefundRejectCode.ORDER_CANCELLED, r.code)
    }

    @Test
    fun `closed order is not refundable`() {
        assertEquals(RefundRejectCode.ORDER_CLOSED,
            rejection(order(status = OrderStatus.CLOSED), request(1_000L)).code)
    }

    @Test
    fun `paid shipped completed and refunding are refundable`() {
        for (status in listOf(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED, OrderStatus.REFUNDING)) {
            val p = plan(order(status = status), request(1_000L))
            assertBalanced(p)
            // 只退 1000/10000 → 订单进入"退款中"，不是"已全额退款"
            assertEquals(OrderStatus.REFUNDING, p.nextOrderStatus, "订单 $status 应可部分退款")
        }
    }

    @Test
    fun `already fully refunded order has nothing left`() {
        val state = order(
            status = OrderStatus.REFUNDED,
            reversedBalance = 6_000L, reversedPoints = 2_000L, reversedCoupon = 2_000L,
        )
        assertEquals(RefundRejectCode.NO_DEDUCTION_LEFT, rejection(state, request(1_000L)).code)
    }

    // ------------------------------------------------------------ 超时订单

    @Test
    fun `expired order is rejected unless manually approved`() {
        val state = order(deadline = now - 1L)
        assertEquals(RefundRejectCode.REFUND_WINDOW_EXPIRED, rejection(state, request(1_000L)).code)
        // 人工审核放行
        val p = plan(state, request(1_000L, allowExpired = true))
        assertBalanced(p)
    }

    @Test
    fun `order inside the refund window is allowed`() {
        val p = plan(order(deadline = now + 1_000L), request(1_000L))
        assertBalanced(p)
    }

    // ------------------------------------------------------------ 并发 / 重试

    @Test
    fun `concurrent refund with stale version is rejected`() {
        val r = rejection(order(version = 7), request(1_000L, expectedVersion = 5))
        assertEquals(RefundRejectCode.CONFLICT_VERSION, r.code)
        assertTrue(r.message.contains("v5"))
    }

    @Test
    fun `matching version passes and bumps the version`() {
        val p = plan(order(version = 7), request(1_000L, expectedVersion = 7))
        assertEquals(8, p.newVersion)
    }

    @Test
    fun `retry beyond the max attempt is rejected`() {
        val r = rejection(order(), request(1_000L, attempt = 4))
        assertEquals(RefundRejectCode.RETRY_EXHAUSTED, r.code)
    }

    // ------------------------------------------------------------ 资源失效

    @Test
    fun `expired coupon is converted to balance at equal value`() {
        val p = plan(order(couponExpired = true), request(10_000L))
        assertBalanced(p)
        val coupon = alloc(p, "d_coupon")
        assertEquals(RefundOutcome.EXPIRED_FALLBACK, coupon.outcome)
        assertEquals(2_000L, coupon.fallbackAmountMinor, "券过期需等额折现，金额不能消失")
    }

    @Test
    fun `consumed points are converted to balance at equal value`() {
        val p = plan(order(pointsConsumed = true), request(10_000L))
        assertBalanced(p)
        val points = alloc(p, "d_points")
        assertEquals(RefundOutcome.USED_FALLBACK, points.outcome)
        assertEquals(2_000L, points.fallbackAmountMinor)
        assertEquals(0, points.quantity)
    }

    // ------------------------------------------------------------ 流水映射

    @Test
    fun `refund plan maps to a positive REFUND ledger transaction`() {
        val state = order()
        val p = plan(state, request(10_000L))
        val txn = RefundLedgerMapper.toLedgerTransaction(p, state, now, "notify", "ref1", "fp1")
        assertEquals(TxnType.REFUND, txn.type)
        assertTrue(txn.amountMinor > 0, "退款是资金流入，金额为正")
        assertEquals(10_000L, txn.amountMinor)
        assertEquals("NO1", txn.counterparty)
    }
}
