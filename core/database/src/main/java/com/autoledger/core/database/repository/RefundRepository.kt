package com.autoledger.core.database.repository

import androidx.room.withTransaction
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.OrderDeductionEntity
import com.autoledger.core.database.RefundEntity
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.refund.OrderRefundState
import com.autoledger.core.model.refund.RefundLedgerMapper
import com.autoledger.core.model.refund.RefundPlan
import com.autoledger.core.model.refund.RefundStatus

/**
 * 退款的持久化层：把 [RefundPlan] 在**单个事务**内落库。
 *
 * 一次退款涉及 5 处写入，必须原子：退款单 + 分摊明细 + 抵扣累计 + 订单状态/版本(CAS) + 账目流水。
 * 任一步失败整体回滚，绝不出现"退了一半"。
 */
class RefundRepository(
    private val db: LedgerDatabase,
    private val ledger: RoomLedgerRepository,
) {
    private val orderDao = db.orderDao()
    private val refundDao = db.refundDao()

    sealed interface ApplyResult {
        data class Applied(val refundId: String) : ApplyResult
        /** 幂等命中：同一退款请求已落库，未重复回退 */
        data class Duplicate(val refundId: String) : ApplyResult
    }

    /** 并发退款时乐观锁 CAS 失败（订单版本已被其它事务推进）。 */
    class ConcurrentRefundException(message: String) : RuntimeException(message)

    /** 组装引擎所需的订单聚合（订单 + 抵扣构成 + 既有退款 + 版本号）。 */
    suspend fun loadState(orderId: String): OrderRefundState? {
        val order = orderDao.findById(orderId) ?: return null
        return OrderRefundState(
            orderId = order.id,
            orderNo = order.orderNo,
            totalMinor = order.totalMinor,
            status = order.status,
            occurredAtMillis = order.occurredAtMillis,
            refundDeadlineMillis = order.refundDeadlineMillis,
            deductions = orderDao.deductionsOf(orderId).map { it.toDomain() },
            refunds = refundDao.listByOrder(orderId).map { it.toDomain() },
            version = order.version,
        )
    }

    suspend fun findByOrderNo(orderNo: String): OrderRefundState? =
        orderDao.findByNo(orderNo)?.let { loadState(it.id) }

    /** 全部订单（按时间倒序），供对账视图展示。 */
    suspend fun listOrders(): List<OrderRefundState> =
        orderDao.listAll().mapNotNull { loadState(it.id) }

    /** 登记/更新订单（订单来源可由采集侧或手动录入驱动）。 */
    suspend fun saveOrder(
        orderNo: String,
        counterparty: String,
        totalMinor: Long,
        status: com.autoledger.core.model.refund.OrderStatus,
        occurredAtMillis: Long,
        refundDeadlineMillis: Long?,
        sourceId: String,
        sourceRef: String,
        orderId: String = "order:$orderNo",
    ): String {
        val existing = orderDao.findByNo(orderNo)
        orderDao.upsert(
            com.autoledger.core.database.OrderEntity(
                id = existing?.id ?: orderId,
                orderNo = orderNo,
                counterparty = counterparty,
                totalMinor = totalMinor,
                currency = "CNY",
                occurredAtMillis = occurredAtMillis,
                refundDeadlineMillis = refundDeadlineMillis,
                status = existing?.status ?: status,
                version = existing?.version ?: 0,
                sourceId = sourceId,
                sourceRef = sourceRef,
                schemaVersion = LedgerSchema.CURRENT,
            )
        )
        return existing?.id ?: orderId
    }

    /** 设置订单的抵扣构成（覆盖式，仅用于首次录入/修正）。 */
    suspend fun saveDeductions(orderId: String, deductions: List<com.autoledger.core.model.refund.OrderDeduction>) {
        orderDao.upsertDeductions(
            deductions.map { d ->
                com.autoledger.core.database.OrderDeductionEntity(
                    id = d.id,
                    orderId = orderId,
                    kind = d.kind,
                    amountMinor = d.amountMinor,
                    quantity = d.quantity,
                    resourceId = d.resourceId,
                    resourceExpired = d.resourceExpired,
                    resourceConsumed = d.resourceConsumed,
                    reversedAmountMinor = d.reversedAmountMinor,
                    reversedQuantity = d.reversedQuantity,
                    schemaVersion = LedgerSchema.CURRENT,
                )
            }
        )
    }

    /**
     * 落库一次退款（单事务 + 幂等 + CAS）。
     *
     * @throws ConcurrentRefundException 并发冲突（订单版本已变）
     */
    suspend fun applyRefund(
        plan: RefundPlan,
        order: OrderRefundState,
        occurredAtMillis: Long,
        sourceId: String,
        sourceRef: String,
        fingerprint: String,
    ): ApplyResult = db.withTransaction {
        // 幂等兜底一：同一 key 已存在 → 返回既有，不重复回退
        refundDao.findByIdempotencyKey(plan.idempotencyKey)?.let {
            return@withTransaction ApplyResult.Duplicate(it.id)
        }

        try {
            // ① 退款单
            refundDao.insert(
                RefundEntity(
                    id = plan.refundId,
                    orderId = order.orderId,
                    refundNo = plan.refundNo,
                    amountMinor = plan.amountMinor,
                    status = RefundStatus.APPLIED,
                    idempotencyKey = plan.idempotencyKey,
                    occurredAtMillis = occurredAtMillis,
                    attempt = 1,
                    reason = null,
                    schemaVersion = LedgerSchema.CURRENT,
                )
            )
            // ② 分摊明细
            refundDao.upsertAllocations(plan.allocations.map { it.toEntity(plan.refundId) })

            // ③ 抵扣累计（读改写，单事务内安全）
            val updated = orderDao.deductionsOf(order.orderId).map { entity: OrderDeductionEntity ->
                val delta = plan.allocations.firstOrNull { it.deductionId == entity.id }
                if (delta == null) {
                    entity
                } else {
                    entity.copy(
                        reversedAmountMinor = entity.reversedAmountMinor + delta.amountMinor,
                        reversedQuantity = entity.reversedQuantity + delta.quantity,
                    )
                }
            }
            orderDao.upsertDeductions(updated)

            // ④ 订单状态 + 版本 CAS（0 行 = 并发冲突 → 整单回滚）
            val rows = orderDao.casStatus(
                id = order.orderId,
                status = plan.nextOrderStatus,
                newVersion = plan.newVersion,
                expectedVersion = order.version,
            )
            if (rows == 0) {
                throw ConcurrentRefundException("订单版本已变更（期望 v${order.version}），退款未落库")
            }

            // ⑤ 账目流水（type=REFUND、金额为正 → 现有统计会自动冲抵支出）
            ledger.upsert(
                RefundLedgerMapper.toLedgerTransaction(
                    plan = plan,
                    order = order,
                    occurredAtMillis = occurredAtMillis,
                    sourceId = sourceId,
                    sourceRef = sourceRef,
                    fingerprint = fingerprint,
                )
            )

            ApplyResult.Applied(plan.refundId)
        } catch (e: ConcurrentRefundException) {
            throw e // 交由事务回滚
        } catch (e: Throwable) {
            // 幂等兜底二：唯一键冲突（并发写入同一 key）→ 视为重复请求
            val existing = refundDao.findByIdempotencyKey(plan.idempotencyKey)
            if (existing != null) ApplyResult.Duplicate(existing.id) else throw e
        }
    }

    /** 回调失败：置失败并累计重试次数。 */
    suspend fun markFailed(refundId: String) {
        db.withTransaction {
            refundDao.updateStatus(refundId, RefundStatus.FAILED)
            refundDao.bumpAttempt(refundId)
        }
    }

    /** 回调成功：补记为已生效。 */
    suspend fun markApplied(refundId: String) {
        refundDao.updateStatus(refundId, RefundStatus.APPLIED)
    }

    suspend fun findRefundByNo(refundNo: String) = refundDao.findByNo(refundNo)?.toDomain()
    suspend fun refundsOf(orderId: String) = refundDao.listByOrder(orderId).map { it.toDomain() }
    suspend fun allocationsOf(refundId: String) = refundDao.allocationsOf(refundId)
}
