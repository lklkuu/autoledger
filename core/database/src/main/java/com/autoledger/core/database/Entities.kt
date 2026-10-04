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
        // 合并溯源反查（mergeGroupOf）：索引名 index_transactions_merged_into_id
        // 必须与 MIGRATION_5_6 里的 SQL 完全一致，否则 exportSchema 校验失败 ⇒ 退回清库。
        Index(value = ["merged_into_id"]),
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
    /**
     * 合并溯源：被吸收进哪条主记录（`null` = 未被合并）。v6 新增，可空列。
     *
     * 放在**最后**而不是紧跟 `refundId`：`ALTER TABLE ... ADD COLUMN` 只能加在末尾，
     * 声明顺序与迁移后的物理顺序保持一致，能让 `schemas/6.json` 与实库更易对照
     * （Room 的 TableInfo 校验其实不比对列顺序，但对照 schema 排查问题时少一层心智负担）。
     *
     * ⚠️ 列名必须是 snake_case 的 `merged_into_id`（与 `platform_id` 一致）：
     * `Index(value = ["merged_into_id"])` 引用的是**列名**而不是 Kotlin 属性名，
     * 少了 `@ColumnInfo` 会被 KSP 直接判为「index 引用了不存在的列」而编译失败。
     */
    @ColumnInfo(name = "merged_into_id") val mergedIntoId: String? = null,
)

/**
 * 用户自定义消费平台（v6 新增）。
 *
 * ## 为什么用 Room 表而不是 DataStore / 本地 JSON
 * - **进备份管线**：换机 / 导入备份后自定义平台必须还在，否则引用它的历史流水会显示「未知平台」；
 * - **软删除**：归档后历史流水的平台名仍可解析（见 [archived]），而不是变成孤儿 ID；
 * - 复用既有的 `database` 单例与迁移体系，不引入新依赖。
 *
 * 关键词组存**换行分隔的 String**（实体用 String、领域用 List）：
 * 沿用 [AccountEntity.identifierHints] 的既有权宜做法，避免为 4 个 List 字段引入 TypeConverter。
 */
@Entity(
    tableName = "user_platforms",
    // Room 生成名：index_user_platforms_archived（MIGRATION_5_6 里的 SQL 必须逐字符一致）
    indices = [Index(value = ["archived"])],
)
data class UserPlatformEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** 存 PlatformKind 枚举的 name()（ORDER / PAYMENT / BANK / OTHER）。 */
    val kind: String,
    /** 换行分隔。 */
    val strongKeywords: String,
    val mediumKeywords: String,
    val weakKeywords: String,
    /** 换行分隔。 */
    val packageNames: String,
    val sortOrder: Int,
    /** 软删除标记：true = 停用（不进识别候选，但历史流水仍显示原名）。 */
    val archived: Boolean,
    val createdAtMillis: Long,
    val schemaVersion: Int,
)

@Entity(tableName = "categories")
data class CategoryEntity(    @PrimaryKey val id: String,
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
    /**
     * 已下线字段（原「安全垫金额」）：列保留、恒写 0，不再参与任何业务逻辑。
     *
     * 保留而不是删除的理由：`app_settings` 的历史 schema（3/4/5.json）里它是 NOT NULL 列，
     * 而 minSdk 26 上 `ALTER TABLE ... DROP COLUMN` 依赖 SQLite 3.35+，在低版本系统上会直接报错；
     * 正确删法要「建新表 → 拷数据 → 删旧表 → 改名」，属于重型迁移，
     * 风险远大于留一条死列。详见 SettingsMapper.toEntity 的同名注释。
     */
    val goalCushionMinor: Long,
    val goalCurrentMinor: Long,
    val autoMerge: Boolean,
    // ---- AI 判定配置（v7 新增）----
    // ⚠️ 这里**只有非敏感的四项**。API 密钥绝不进 Room：
    // 进库即进 `listAllForBackup` 的备份范围，等于把密钥塞进导出文件。
    // 密钥走 core:crypto 的 AiKeyVault（Keystore 包裹 + SharedPreferences，不进备份）。
    val aiEnabled: Boolean,
    /** AiMode 的枚举名（FALLBACK / ALWAYS）；非法值读取侧回落 FALLBACK。 */
    val aiMode: String,
    val aiEndpoint: String,
    val aiModel: String,
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
