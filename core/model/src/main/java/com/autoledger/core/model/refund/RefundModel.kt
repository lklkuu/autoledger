package com.autoledger.core.model.refund

/** 订单状态（与支付/履约流程对齐）。 */
enum class OrderStatus {
    /** 待支付：尚未付款，不存在"退钱" */
    PENDING_PAYMENT,
    /** 已支付 */
    PAID,
    /** 已发货 */
    SHIPPED,
    /** 已完成 */
    COMPLETED,
    /** 已取消 */
    CANCELLED,
    /** 退款中（部分退款） */
    REFUNDING,
    /** 已全额退款 */
    REFUNDED,
    /** 已关闭（超过售后期等） */
    CLOSED,
}

/** 订单的抵扣构成方式。 */
enum class DeductionKind {
    /** 余额（金额型，可原路退回余额） */
    BALANCE,
    /** 积分（数量型，可退积分；已消耗则折现） */
    POINTS,
    /** 优惠券（整张型，整单退才整张返还） */
    COUPON,
    /** 权益次数（整张/次数型） */
    ENTITLEMENT,
    /** 第三方支付（原路退回支付渠道） */
    THIRD_PARTY,
    ;

    val isMoneyLike: Boolean get() = this == BALANCE || this == THIRD_PARTY
    val isWholeResource: Boolean get() = this == COUPON || this == ENTITLEMENT
}

/** 单条抵扣的回退结果。 */
enum class RefundOutcome {
    /** 原路返还成功 */
    RETURNED,
    /** 资源已过期，无法原路 → 等额折现退余额 */
    EXPIRED_FALLBACK,
    /** 资源已被使用，无法原路 → 等额折现退余额 */
    USED_FALLBACK,
    /** 跳过（超额截断等场景，金额需明确记录） */
    SKIPPED,
}

/** 退款单状态。 */
enum class RefundStatus {
    /** 待处理 */
    PENDING,
    /** 处理中（已提交渠道） */
    PROCESSING,
    /** 已生效（回退已完成） */
    APPLIED,
    /** 失败（可重试） */
    FAILED,
    /** 被拒绝（不可重试，需人工） */
    REJECTED,
}

/** 支付渠道回调回来的状态。 */
enum class RefundCallbackStatus { SUCCESS, FAIL, PROCESSING }

/** 对账结论动作。 */
enum class ReconcileAction {
    /** 本地应置为已生效 */
    MARK_APPLIED,
    /** 本地应置为失败（可重试） */
    MARK_FAILED,
    /** 保持处理中，等待下次回调 */
    KEEP_PENDING,
    /** 状态冲突，转人工对账 */
    MANUAL_REVIEW,
    /** 回调指向未知退款单，需人工核查 */
    UNKNOWN_REFUND,
}

/**
 * 退款拒绝码 —— **错误码 + 提示文案**集中在此，避免散落各层。
 */
enum class RefundRejectCode(val message: String, val hint: String) {
    ORDER_NOT_FOUND("找不到对应的订单", "请确认订单号是否正确，或稍后重试"),
    ORDER_NOT_PAID("订单尚未支付，无法退款", "待支付订单请直接取消，无需退款"),
    ORDER_CANCELLED("订单已取消，请通过取消流程处理", "已取消订单如有已付款项，系统会自动原路退回"),
    ORDER_CLOSED("订单已关闭，不再支持退款", "如有疑问请联系客服人工处理"),
    AMOUNT_NOT_POSITIVE("退款金额必须大于 0", "请填写正确的退款金额"),
    AMOUNT_EXCEEDS_REMAINING("退款金额超过订单剩余可退金额", "请核对剩余可退金额后重新提交"),
    AMOUNT_EXCEEDS_TOTAL("退款金额超过订单总额", "请核对订单金额"),
    REFUND_WINDOW_EXPIRED("已超过该订单的可退款时限", "超时订单如需处理，请申请人工审核"),
    CONFLICT_VERSION("订单状态已变更，请重试", "并发退款冲突，请重新发起"),
    NO_DEDUCTION_LEFT("该订单已无可回退的抵扣项", "订单可能已全额退款"),
    RETRY_EXHAUSTED("退款重试已达上限", "请人工介入处理"),
}

/** 订单的一条抵扣构成（原订单"钱是怎么付的"）。 */
data class OrderDeduction(
    val id: String,
    val kind: DeductionKind,
    /** 该方式的抵扣金额（分）；券/权益记其面值/折算价值 */
    val amountMinor: Long,
    /** 数量（积分个数 / 权益次数 / 券张数）；金额型为 0 */
    val quantity: Int = 0,
    val resourceId: String? = null,
    /** 券/权益是否已过期 */
    val resourceExpired: Boolean = false,
    /** 积分/次数是否已被消耗 */
    val resourceConsumed: Boolean = false,
    /** 已回退金额（累计） */
    val reversedAmountMinor: Long = 0L,
    /** 已回退数量（累计） */
    val reversedQuantity: Int = 0,
) {
    val remainingAmountMinor: Long get() = (amountMinor - reversedAmountMinor).coerceAtLeast(0L)
    val remainingQuantity: Int get() = (quantity - reversedQuantity).coerceAtLeast(0)
}

/** 已存在的退款单（用于幂等与额度计算）。 */
data class RefundRecord(
    val id: String,
    val refundNo: String,
    val amountMinor: Long,
    val status: RefundStatus,
    val idempotencyKey: String,
    val occurredAtMillis: Long,
    /** 已尝试次数（失败重试用） */
    val attempt: Int = 1,
)

/** 退款评估所需的订单快照（聚合根视图）。 */
data class OrderRefundState(
    val orderId: String,
    val orderNo: String,
    val totalMinor: Long,
    val status: OrderStatus,
    val occurredAtMillis: Long,
    /** 可退款截止时间；null = 不限时 */
    val refundDeadlineMillis: Long? = null,
    val deductions: List<OrderDeduction>,
    val refunds: List<RefundRecord> = emptyList(),
    /** 乐观锁版本号，用于并发退款的竞态控制 */
    val version: Int = 0,
)

/** 退款请求。 */
data class RefundRequest(
    val refundNo: String,
    val amountMinor: Long,
    /** 幂等键（建议 hash(渠道+退款单号+金额)） */
    val idempotencyKey: String,
    val occurredAtMillis: Long,
    /** 乐观锁期望版本；不传则不校验（仅供单线程/单测场景） */
    val expectedVersion: Int? = null,
    /** 是否允许超时订单强制退款（人工审核后放行） */
    val allowExpired: Boolean = false,
    /** 重试场景下的尝试次数 */
    val attempt: Int = 1,
)

/** 一条回退明细。 */
data class RefundAllocation(
    val deductionId: String,
    val kind: DeductionKind,
    /** 对账金额（分）；Σ 必须等于退款额 */
    val amountMinor: Long,
    /** 返还数量（积分/次数/券张数） */
    val quantity: Int,
    val outcome: RefundOutcome,
    /** 折现金额：无法原路回退时退到余额的等额（分） */
    val fallbackAmountMinor: Long = 0L,
)

/** 退款方案（引擎产出，调用方据此落库）。 */
data class RefundPlan(
    val refundId: String,
    val refundNo: String,
    val amountMinor: Long,
    val allocations: List<RefundAllocation>,
    /** 落库后订单应迁移到的状态 */
    val nextOrderStatus: OrderStatus,
    /** 落库后订单的新版本号 */
    val newVersion: Int,
    val idempotencyKey: String,
)

/** 拒绝结果。 */
data class RefundRejection(val code: RefundRejectCode, val detail: String = "") {
    val message: String get() = code.message + if (detail.isBlank()) "" else "（$detail）"
    val hint: String get() = code.hint
}

/** 评估结果：三态 —— 可执行 / 幂等命中 / 被拒绝。 */
sealed interface RefundResult {
    data class Planned(val plan: RefundPlan) : RefundResult
    /** 幂等命中：重复请求，返回既有退款单，不再重复回退 */
    data class Duplicate(val existing: RefundRecord, val message: String) : RefundResult
    data class Rejected(val rejection: RefundRejection) : RefundResult
}

/** 支付渠道回调。 */
data class RefundCallback(
    val refundNo: String,
    val idempotencyKey: String,
    val status: RefundCallbackStatus,
    val amountMinor: Long,
    val reason: String? = null,
)

/** 对账结论。 */
data class ReconcileResult(
    val action: ReconcileAction,
    val message: String,
    /** 本地状态与回调状态的差异描述（用于日志/人工核查） */
    val diff: String,
)
