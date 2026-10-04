package com.autoledger.feature.capture

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType

/**
 * 收支类型二次判定端口（依赖倒置：调用方定义端口，实现方在别的模块）。
 *
 * 为什么端口定义在 `feature:capture`（调用方）而不是 AI 模块：
 * 依赖方向必须是「调用方 → 抽象」。实现方（app 层适配器 → `feature:ai`）反向注入，
 * 因此 **feature 之间零横向依赖**（`feature:ai` 并不认识 `feature:capture` 的任何类型）。
 *
 * 契约对实现方的硬性要求（实现在 `feature:ai`，此处只是把契约写死）：
 * 1. **只在本地规则判不出时才可能被调用**（是否真调用由实现方按模式自行决定）；
 * 2. 采集端已确定类型（`explicitType != null`）时**根本不会被调用**；
 * 3. 任何异常 / 超时 / 低置信 / 非法输出，一律返回 `null` 表示「不采纳」，
 *    **绝不抛给调用方** —— 记一笔账不该因为外部服务不可用而失败。
 */
interface TypeRefiner {

    /**
     * @return 采纳的收支类型；返回 `null` = 不采纳，调用方继续用本地规则结论。
     */
    suspend fun refine(request: TypeRefineRequest): TxnType?
}

/** 一次「请外部判定收支类型」的请求。 */
data class TypeRefineRequest(
    /** 通知原文。仅在 AI 开启且配置齐全时才会随请求离开设备。 */
    val text: String,
    /** 本地解析出的金额（分）；null = 本地没解析出金额。 */
    val amountMinor: Long?,
    /** 本地规则的结论，作为 AI 的参考与回落目标。 */
    val localGuess: TxnType,
    /**
     * 采集端已知的收支方向；与 [amountMinor] 共同构成「本地是否判不出」的判据。
     * 非 null 时本地已能得出结论，实现方不应再为此发出网络请求。
     */
    val directionHint: Direction?,
)

/**
 * 在 [resolveInitialType] 之后、可选的 AI 二次判定。
 *
 * 抽成顶层 `internal` 函数是为了能脱离 Android / Room / 网络直接做 JVM 单测
 * （与 [resolveInitialType] 同样的取法）。
 *
 * **安全性质（顺序即保障）**：本函数在「划转 / 退款二次覆盖」**之前**调用，
 * 因此 AI 就算把一笔划转误判成收入，随后的本地划转识别仍会把它改回 TRANSFER。
 */
internal suspend fun resolveInitialTypeWithRefiner(
    explicitType: TxnType?,
    amount: Long?,
    directionHint: Direction?,
    rawText: String,
    typeRefiner: TypeRefiner?,
): TxnType {
    val local = resolveInitialType(explicitType, amount, directionHint)
    // 采集端已确定类型（退款等）时，AI 永不参与 —— 这是硬约束，不交给实现方自觉。
    if (explicitType != null) return local
    val refined = typeRefiner?.refine(
        TypeRefineRequest(
            text = rawText,
            amountMinor = amount,
            localGuess = local,
            directionHint = directionHint,
        ),
    )
    return refined ?: local
}
