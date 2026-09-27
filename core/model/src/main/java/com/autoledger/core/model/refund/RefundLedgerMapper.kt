package com.autoledger.core.model.refund

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType

/**
 * 退款方案 → 账目流水的映射（纯函数，放在 core:model，供 core:database 与 feature:refund 共用）。
 *
 * 与现有账目保持一致：`type = REFUND`、**金额为正**（资金流入）。
 * 现有统计已把 REFUND 排除在支出之外，所以退款会自然冲抵支出，无需改统计逻辑。
 */
object RefundLedgerMapper {

    fun toLedgerTransaction(
        plan: RefundPlan,
        order: OrderRefundState,
        occurredAtMillis: Long,
        sourceId: String,
        sourceRef: String,
        fingerprint: String,
        counterparty: String = order.orderNo,
    ): LedgerTransaction = LedgerTransaction(
        id = "refund:${plan.refundId}",
        amountMinor = plan.amountMinor,
        occurredAtMillis = occurredAtMillis,
        type = TxnType.REFUND,
        counterparty = counterparty,
        note = "订单退款 ${plan.refundNo}",
        sourceId = sourceId,
        sourceRef = sourceRef,
        orderId = order.orderId,
        refundId = plan.refundId,
        fingerprint = fingerprint,
        status = TxnStatus.CONFIRMED,
    )
}
