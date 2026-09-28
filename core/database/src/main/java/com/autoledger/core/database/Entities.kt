package com.autoledger.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autoledger.core.model.AccountKind
import com.autoledger.core.model.CategoryKind
import com.autoledger.core.model.Direction
import com.autoledger.core.model.RuleKind
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.refund.DeductionKind
import com.autoledger.core.model.refund.OrderStatus
import com.autoledger.core.model.refund.RefundOutcome
import com.autoledger.core.model.refund.RefundStatus

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["occurredAtMillis"]),
        Index(value = ["fingerprint"]),
        Index(value = ["status"]),
        Index(value = ["type"]),
        // 消费平台：支撑「按平台筛选 / 分组」（PlatformShareMetric 目前走内存聚合，
        // 但索引先建好，将来 DAO 侧加 WHERE platform_id IN (...) 无需再迁移）
        Index(value = ["platform_id"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    val bookedAtMillis: Long,
    val type: TxnType,
    val direction: Direction,
    val counterparty: String,
    /** 消费平台稳定 ID，见 PlatformCatalog；NOT NULL，由 Migration 的 DEFAULT 保证。 */
    @ColumnInfo(name = "platform_id") val platformId: String,
    @ColumnInfo(name = "platform_confidence") val platformConfidence: Float,
    /** 存 PlatformSource 枚举的 name()（AUTO / USER）。NOT NULL，由 Migration 的 DEFAULT 保证。 */
    @ColumnInfo(name = "platform_source") val platformSource: String,
    val note: String?,
    val sourceId: String,
    val sourceRef: String,
    val accountId: String?,
    val categoryId: String?,
    val transferGroupId: String?,
    val fingerprint: String,
    val status: TxnStatus,
    val confidence: Float,
    val rawTextSealed: String?,
    val extras: String?,
    val orderId: String?,
    val refundId: String?,
    val schemaVersion: Int,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val parentId: String?,
    val sortOrder: Int,
    val builtIn: Boolean,
    val kind: CategoryKind,
    val monthlyBudgetMinor: Long?,
    val archived: Boolean,
)

@Entity(tableName = "accounts", indices = [Index(value = ["kind"])])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: AccountKind,
    val institution: String?,
    val identifierHints: String,
    val archived: Boolean,
)

@Entity(
    tableName = "classifier_rules",
    indices = [Index(value = ["categoryId"]), Index(value = ["pattern"])],
)
data class ClassifierRuleEntity(
    @PrimaryKey val id: String,
    val kind: RuleKind,
    val pattern: String,
    val categoryId: String,
    val priority: Int,
    val learned: Boolean,
    val hitCount: Int,
    val createdAtMillis: Long,
)

/**
 * 云同步发件箱（工程要求 3：为将来的云同步预留接口）。
 *
 * 现在只写不发：任何本地变更都登记一条 op，将来接入第一个真正的
 * [com.autoledger.core.database.sync.CloudSyncClient] 实现后，直接消费这张表即可做增量同步，
 * 不需要改造写入路径。
 */
@Entity(tableName = "sync_outbox", indices = [Index(value = ["entityType", "entityId"])])
data class SyncOutboxEntity(
    @PrimaryKey val opId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val createdAtMillis: Long,
    val retryCount: Int = 0,
    val lastError: String? = null,
)

/**
 * 应用级设置（时薪参数 / 自由基金目标 / 行为开关），单行存储。
 * 金额以「分」存 Long，避免浮点误差。
 */
@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: String = "global",
    val wageSalaryMinor: Long,
    val wagePayMonths: Int,
    val wageWorkCostMinor: Long,
    val wageWorkDays: Double,
    val wageOfficeHours: Double,
    val wageCommuteMinutes: Int,
    val wageOvertimeHours: Double,
    val goalTargetMinor: Long,
    val goalCushionMinor: Long,
    val goalCurrentMinor: Long,
    val autoMerge: Boolean,
)

// ---------------------------------------------------------------- 订单 / 退款

/** 订单。`orderNo` 唯一，保证同一订单不会重复建单。 */
@Entity(tableName = "orders", indices = [Index(value = ["orderNo"], unique = true), Index(value = ["status"])])
data class OrderEntity(
    @PrimaryKey val id: String,
    val orderNo: String,
    val counterparty: String,
    val totalMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    val refundDeadlineMillis: Long?,
    val status: OrderStatus,
    /** 乐观锁版本号：并发退款靠它做 CAS */
    val version: Int,
    val sourceId: String,
    val sourceRef: String,
    val schemaVersion: Int,
)

/** 订单的抵扣构成（原订单"钱是怎么付的"）。 */
@Entity(tableName = "order_deductions", indices = [Index(value = ["orderId"])])
data class OrderDeductionEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val kind: DeductionKind,
    val amountMinor: Long,
    val quantity: Int,
    val resourceId: String?,
    val resourceExpired: Boolean,
    val resourceConsumed: Boolean,
    /** 已回退金额（累计） */
    val reversedAmountMinor: Long,
    /** 已回退数量（累计） */
    val reversedQuantity: Int,
    val schemaVersion: Int,
)

/** 退款单。`idempotencyKey` 唯一 —— 数据库层的幂等兜底。 */
@Entity(
    tableName = "refunds",
    indices = [Index(value = ["idempotencyKey"], unique = true), Index(value = ["orderId"])],
)
data class RefundEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val refundNo: String,
    val amountMinor: Long,
    val status: RefundStatus,
    val idempotencyKey: String,
    val occurredAtMillis: Long,
    val attempt: Int,
    val reason: String?,
    val schemaVersion: Int,
)

/** 退款分摊明细（一条 = 某个抵扣项被回退了多少）。 */
@Entity(
    tableName = "refund_allocations",
    indices = [Index(value = ["refundId", "deductionId"], unique = true)],
)
data class RefundAllocationEntity(
    @PrimaryKey val id: String,
    val refundId: String,
    val deductionId: String,
    val kind: DeductionKind,
    val amountMinor: Long,
    val quantity: Int,
    val outcome: RefundOutcome,
    val fallbackAmountMinor: Long,
    val resourceId: String?,
    val schemaVersion: Int,
)

/** 资源可用量账本（余额 / 积分 / 券 / 权益次数）。 */
@Entity(tableName = "resource_balances", indices = [Index(value = ["kind", "resourceId"], unique = true)])
data class ResourceBalanceEntity(
    @PrimaryKey val id: String,
    val kind: DeductionKind,
    val resourceId: String,
    val ownerAccountId: String?,
    val availableMinor: Long,
    val availableQuantity: Int,
    val expiresAtMillis: Long?,
)
