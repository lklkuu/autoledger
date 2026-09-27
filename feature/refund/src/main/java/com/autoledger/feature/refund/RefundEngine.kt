package com.autoledger.feature.refund

import com.autoledger.core.model.refund.*
import kotlin.math.floor

/**
 * 退款引擎 —— **纯函数，无 Android / 无 IO / 无副作用**，可 JVM 单测。
 *
 * 覆盖：全额 / 部分退款、幂等、超额校验、各订单状态可行性、超时订单、
 * 并发竞态（乐观锁）、失败重试、回调对账。
 *
 * 引擎只"算账"并产出 [RefundPlan] / [RefundRejection]；
 * **事务与落库由调用方负责**（在单个事务内写 refunds + allocations + 更新抵扣累计 + 写流水）。
 */
object RefundEngine {

    /** 失败重试上限。 */
    const val MAX_ATTEMPTS = 3

    /**
     * 评估一次退款请求。
     *
     * @param now 当前时间（用于退款时限判断）
     * @param newRefundId 由调用方生成的退款单 ID（引擎不产生随机值，保持可测）
     */
    fun evaluate(
        state: OrderRefundState,
        request: RefundRequest,
        now: Long,
        newRefundId: String,
    ): RefundResult {
        // ① 参数校验
        if (request.amountMinor <= 0L) {
            return RefundResult.Rejected(RefundRejection(RefundRejectCode.AMOUNT_NOT_POSITIVE))
        }
        if (request.attempt > MAX_ATTEMPTS) {
            return RefundResult.Rejected(
                RefundRejection(RefundRejectCode.RETRY_EXHAUSTED, "已尝试 ${request.attempt} 次")
            )
        }

        // ② 幂等：同一 idempotencyKey 已受理 → 直接返回既有，绝不重复回退
        state.refunds.firstOrNull { it.idempotencyKey == request.idempotencyKey }?.let { existing ->
            return RefundResult.Duplicate(existing, "该退款请求已受理过，不会重复回退")
        }

        // ③ 并发竞态：乐观锁版本校验
        if (request.expectedVersion != null && request.expectedVersion != state.version) {
            return RefundResult.Rejected(
                RefundRejection(
                    RefundRejectCode.CONFLICT_VERSION,
                    "期望 v${request.expectedVersion}，实际 v${state.version}"
                )
            )
        }

        // ④ 订单状态可行性
        val notAllowed = rejectCodeForStatus(state.status)
        if (notAllowed != null) return RefundResult.Rejected(RefundRejection(notAllowed))

        // ⑤ 退款时限（超时订单可被人工审核放行：allowExpired）
        val deadline = state.refundDeadlineMillis
        if (deadline != null && now > deadline && !request.allowExpired) {
            return RefundResult.Rejected(
                RefundRejection(RefundRejectCode.REFUND_WINDOW_EXPIRED, "截止时间 $deadline")
            )
        }

        // ⑥ 额度校验：已计入的退款（排除被拒绝的）之和
        val counted = state.refunds
            .filter { it.status != RefundStatus.REJECTED }
            .sumOf { it.amountMinor }
        val remaining = state.totalMinor - counted

        if (request.amountMinor > state.totalMinor) {
            return RefundResult.Rejected(
                RefundRejection(RefundRejectCode.AMOUNT_EXCEEDS_TOTAL, "订单总额 ${state.totalMinor} 分")
            )
        }
        if (request.amountMinor > remaining) {
            return RefundResult.Rejected(
                RefundRejection(RefundRejectCode.AMOUNT_EXCEEDS_REMAINING, "剩余可退 ${remaining.coerceAtLeast(0)} 分")
            )
        }

        // ⑦ 可回退池
        val pool = state.deductions.filter { it.remainingAmountMinor > 0L }
        val poolRemaining = pool.sumOf { it.remainingAmountMinor }
        if (pool.isEmpty() || poolRemaining <= 0L) {
            return RefundResult.Rejected(RefundRejection(RefundRejectCode.NO_DEDUCTION_LEFT))
        }
        if (request.amountMinor > poolRemaining) {
            return RefundResult.Rejected(
                RefundRejection(RefundRejectCode.AMOUNT_EXCEEDS_REMAINING, "可回退池 $poolRemaining 分")
            )
        }

        // ⑧ 分摊
        val allocations = allocate(pool, request.amountMinor, poolRemaining)

        // ⑨ 引擎自检：分摊必须合平（防止算法回归）
        val sum = allocations.sumOf { it.amountMinor }
        check(sum == request.amountMinor) { "退款分摊不平：$sum != ${request.amountMinor}" }

        // ⑩ 订单迁移状态
        val afterTotal = counted + request.amountMinor
        val nextOrderStatus =
            if (afterTotal >= state.totalMinor) OrderStatus.REFUNDED else OrderStatus.REFUNDING

        return RefundResult.Planned(
            RefundPlan(
                refundId = newRefundId,
                refundNo = request.refundNo,
                amountMinor = request.amountMinor,
                allocations = allocations,
                nextOrderStatus = nextOrderStatus,
                newVersion = state.version + 1,
                idempotencyKey = request.idempotencyKey,
            )
        )
    }

    /**
     * 按原抵扣构成做退款分摊。
     *
     * 规则：
     * - **金额型**（余额 / 第三方）：按 `退款额 / 可退池` **等比例**返还，原路退回。
     * - **积分**：未消耗 → 按比例退积分；已消耗 → 等额折现退余额（[RefundOutcome.USED_FALLBACK]）。
     * - **券 / 权益次数（整张型）**：**只有退完所有剩余时才整张返还**；
     *   部分退款不拆券，按面值占比折算成余额退还（过期 → [RefundOutcome.EXPIRED_FALLBACK]）。
     * - **取整残差**：归入余额 / 第三方项，保证 `Σ 分摊 == 退款额`。
     */
    fun allocate(
        pool: List<OrderDeduction>,
        amountMinor: Long,
        poolRemaining: Long,
    ): List<RefundAllocation> {
        val isFull = amountMinor >= poolRemaining
        val share = amountMinor.toDouble() / poolRemaining.toDouble()
        val out = mutableListOf<RefundAllocation>()
        var allocated = 0L

        // 整张型优先判定（券/权益是否整张返还取决于是否退满）
        for (deduction in pool.sortedBy { if (it.kind.isWholeResource) 0 else 1 }) {
            val remainingAmount = deduction.remainingAmountMinor
            if (remainingAmount <= 0L) continue
            val raw = if (isFull) {
                remainingAmount
            } else {
                floor(remainingAmount * share).toLong().coerceAtMost(remainingAmount)
            }
            if (raw <= 0L) continue

            out += when (deduction.kind) {
                DeductionKind.BALANCE, DeductionKind.THIRD_PARTY ->
                    RefundAllocation(deduction.id, deduction.kind, raw, 0, RefundOutcome.RETURNED)

                DeductionKind.POINTS -> {
                    val qty = if (isFull) {
                        deduction.remainingQuantity
                    } else {
                        floor(deduction.remainingQuantity * share).toInt().coerceAtMost(deduction.remainingQuantity)
                    }
                    if (!deduction.resourceConsumed && qty > 0) {
                        RefundAllocation(deduction.id, deduction.kind, raw, qty, RefundOutcome.RETURNED)
                    } else {
                        RefundAllocation(
                            deduction.id, deduction.kind, raw, 0,
                            RefundOutcome.USED_FALLBACK, fallbackAmountMinor = raw,
                        )
                    }
                }

                DeductionKind.COUPON, DeductionKind.ENTITLEMENT -> {
                    val returnWhole = isFull &&
                        !deduction.resourceExpired &&
                        !deduction.resourceConsumed &&
                        deduction.remainingQuantity > 0
                    if (returnWhole) {
                        RefundAllocation(deduction.id, deduction.kind, raw, deduction.remainingQuantity, RefundOutcome.RETURNED)
                    } else {
                        RefundAllocation(
                            deduction.id, deduction.kind, raw, 0,
                            if (deduction.resourceExpired) RefundOutcome.EXPIRED_FALLBACK else RefundOutcome.USED_FALLBACK,
                            fallbackAmountMinor = raw,
                        )
                    }
                }
            }
            allocated += raw
        }

        // 残差补齐（floor 造成的分位误差）：优先落到余额/第三方项，其次最后一项
        val residual = amountMinor - allocated
        if (residual != 0L && out.isNotEmpty()) {
            val index = out.indexOfFirst { it.kind.isMoneyLike }.takeIf { it >= 0 } ?: out.lastIndex
            val target = out[index]
            val newAmount = (target.amountMinor + residual).coerceAtLeast(0L)
            out[index] = target.copy(
                amountMinor = newAmount,
                fallbackAmountMinor = if (target.outcome == RefundOutcome.RETURNED) 0L else newAmount,
            )
        }
        return out
    }

    /** 订单状态 → 是否可退款；返回 null 表示允许。 */
    fun rejectCodeForStatus(status: OrderStatus): RefundRejectCode? = when (status) {
        OrderStatus.PENDING_PAYMENT -> RefundRejectCode.ORDER_NOT_PAID
        OrderStatus.CANCELLED -> RefundRejectCode.ORDER_CANCELLED
        OrderStatus.CLOSED -> RefundRejectCode.ORDER_CLOSED
        // 已全额退款：后续会被"剩余可退 = 0"拦下并给出更精确的提示，此处不重复判
        OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED,
        OrderStatus.REFUNDING, OrderStatus.REFUNDED,
        -> null
    }

    /**
     * 回调对账：比对"本地退款单状态"与"渠道回调状态"，给出处置动作。
     *
     * 重点：任一侧出现**终态冲突**（本地已生效但回调失败 / 本地已拒绝但回调成功）→
     * 一律 [ReconcileAction.MANUAL_REVIEW]，绝不自动改账。
     */
    fun reconcile(local: RefundRecord?, callback: RefundCallback, now: Long = 0L): ReconcileResult {
        if (local == null) {
            return ReconcileResult(
                ReconcileAction.UNKNOWN_REFUND,
                "回调指向的退款单在本机不存在，需人工核查",
                "本地=无，回调=${callback.refundNo}/${callback.status}"
            )
        }
        val diff = "本地=${local.status}/${local.amountMinor}分，回调=${callback.status}/${callback.amountMinor}分"

        return when (callback.status) {
            RefundCallbackStatus.SUCCESS -> when (local.status) {
                RefundStatus.APPLIED ->
                    ReconcileResult(ReconcileAction.MARK_APPLIED, "双方一致，保持已生效", diff)
                RefundStatus.REJECTED ->
                    ReconcileResult(ReconcileAction.MANUAL_REVIEW, "本地已拒绝但渠道回调成功，转人工对账", diff)
                RefundStatus.PENDING, RefundStatus.PROCESSING, RefundStatus.FAILED ->
                    if (callback.amountMinor != local.amountMinor) {
                        ReconcileResult(ReconcileAction.MANUAL_REVIEW, "回调成功但金额不一致，转人工对账", diff)
                    } else {
                        ReconcileResult(ReconcileAction.MARK_APPLIED, "渠道成功，本地补记为已生效", diff)
                    }
            }

            RefundCallbackStatus.FAIL -> when (local.status) {
                RefundStatus.APPLIED ->
                    ReconcileResult(ReconcileAction.MANUAL_REVIEW, "本地已生效但渠道失败，转人工对账", diff)
                RefundStatus.REJECTED ->
                    ReconcileResult(ReconcileAction.MARK_FAILED, "双方一致（已拒绝），无需变更", diff)
                RefundStatus.PENDING, RefundStatus.PROCESSING, RefundStatus.FAILED ->
                    if (local.attempt >= MAX_ATTEMPTS) {
                        ReconcileResult(ReconcileAction.MARK_FAILED, "渠道失败且重试已达上限，停止重试", diff)
                    } else {
                        ReconcileResult(ReconcileAction.MARK_FAILED, "渠道失败，可重试第 ${local.attempt + 1} 次", diff)
                    }
            }

            RefundCallbackStatus.PROCESSING -> when (local.status) {
                RefundStatus.APPLIED, RefundStatus.REJECTED ->
                    ReconcileResult(ReconcileAction.MANUAL_REVIEW, "渠道处理中但本地已终态，转人工对账", diff)
                RefundStatus.PENDING, RefundStatus.PROCESSING, RefundStatus.FAILED ->
                    ReconcileResult(ReconcileAction.KEEP_PENDING, "渠道处理中，保持等待下次回调", diff)
            }
        }
    }

}
