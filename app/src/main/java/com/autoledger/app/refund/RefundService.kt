package com.autoledger.app.refund

import com.autoledger.core.database.repository.RefundRepository
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.refund.RefundRejectCode
import com.autoledger.core.model.refund.RefundRequest
import com.autoledger.core.model.refund.RefundResult
import com.autoledger.feature.refund.RefundEngine
import java.util.UUID

/**
 * 退款编排（app 层）：把「引擎算账 + 仓库落库」串成一次调用，并统一产出用户可读的结果。
 *
 * 错误码 → 文案的转换只在这里做一次，UI 直接展示 [Outcome.message] / [Outcome.hint]。
 */
class RefundService(private val repository: RefundRepository) {

    /** 面向 UI 的退款结果：成功与否 + 主文案 + 下一步提示。 */
    data class Outcome(val ok: Boolean, val message: String, val hint: String)

    /**
     * 发起一次退款。
     *
     * @param idempotencyKey 幂等键；相同键重复调用不会重复回退
     */
    suspend fun requestRefund(
        orderId: String,
        amountMinor: Long,
        refundNo: String,
        idempotencyKey: String = "${CaptureSourceIds.MANUAL}:$refundNo",
        now: Long = System.currentTimeMillis(),
        allowExpired: Boolean = false,
        sourceId: String = CaptureSourceIds.MANUAL,
    ): Outcome {
        val state = runCatching { repository.loadState(orderId) }.getOrNull()
            ?: return Outcome(
                ok = false,
                message = RefundRejectCode.ORDER_NOT_FOUND.message,
                hint = RefundRejectCode.ORDER_NOT_FOUND.hint,
            )

        val request = RefundRequest(
            refundNo = refundNo,
            amountMinor = amountMinor,
            idempotencyKey = idempotencyKey,
            occurredAtMillis = now,
            expectedVersion = state.version, // 带入当前版本 → 并发冲突可被 CAS 拦下
            allowExpired = allowExpired,
        )

        val result = RefundEngine.evaluate(state, request, now, "rf_${UUID.randomUUID()}")
        return when (result) {
            is RefundResult.Duplicate -> Outcome(
                ok = true,
                message = "该退款请求已受理",
                hint = "同一请求不会重复回退，无需再次提交",
            )

            is RefundResult.Rejected -> Outcome(
                ok = false,
                message = result.rejection.message,
                hint = result.rejection.hint,
            )

            is RefundResult.Planned -> runCatching {
                repository.applyRefund(
                    plan = result.plan,
                    order = state,
                    occurredAtMillis = now,
                    sourceId = sourceId,
                    sourceRef = refundNo,
                    fingerprint = "refund:${result.plan.refundId}",
                )
            }.fold(
                onSuccess = { applied ->
                    when (applied) {
                        is RefundRepository.ApplyResult.Applied -> Outcome(
                            ok = true,
                            message = "退款成功，已回退 ${result.plan.amountMinor} 分",
                            hint = "订单状态已更新为 ${result.plan.nextOrderStatus}",
                        )
                        is RefundRepository.ApplyResult.Duplicate -> Outcome(
                            ok = true,
                            message = "该退款请求已受理",
                            hint = "同一请求不会重复回退",
                        )
                    }
                },
                onFailure = { e ->
                    if (e is RefundRepository.ConcurrentRefundException) {
                        Outcome(
                            ok = false,
                            message = RefundRejectCode.CONFLICT_VERSION.message,
                            hint = RefundRejectCode.CONFLICT_VERSION.hint,
                        )
                    } else {
                        Outcome(false, "退款失败：${e.message}", "请稍后重试或联系人工处理")
                    }
                },
            )
        }
    }
}
