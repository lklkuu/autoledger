package com.autoledger.core.database.repository

import com.autoledger.core.database.OrderDeductionEntity
import com.autoledger.core.database.RefundAllocationEntity
import com.autoledger.core.database.RefundEntity
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.refund.OrderDeduction
import com.autoledger.core.model.refund.RefundAllocation
import com.autoledger.core.model.refund.RefundRecord

/** 订单 / 退款：实体 <-> 领域模型 映射。 */

internal fun OrderDeductionEntity.toDomain(): OrderDeduction = OrderDeduction(
    id = id,
    kind = kind,
    amountMinor = amountMinor,
    quantity = quantity,
    resourceId = resourceId,
    resourceExpired = resourceExpired,
    resourceConsumed = resourceConsumed,
    reversedAmountMinor = reversedAmountMinor,
    reversedQuantity = reversedQuantity,
)

internal fun RefundEntity.toDomain(): RefundRecord = RefundRecord(
    id = id,
    refundNo = refundNo,
    amountMinor = amountMinor,
    status = status,
    idempotencyKey = idempotencyKey,
    occurredAtMillis = occurredAtMillis,
    attempt = attempt,
)

/** 分摊明细：主键用 `refundId:deductionId`，与唯一索引 (refundId, deductionId) 同源，天然防重。 */
internal fun RefundAllocation.toEntity(refundId: String): RefundAllocationEntity = RefundAllocationEntity(
    id = "$refundId:$deductionId",
    refundId = refundId,
    deductionId = deductionId,
    kind = kind,
    amountMinor = amountMinor,
    quantity = quantity,
    outcome = outcome,
    fallbackAmountMinor = fallbackAmountMinor,
    resourceId = null,
    schemaVersion = LedgerSchema.CURRENT,
)
