package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.platform.PlatformSource

/**
 * 流水可编辑性判定（纯函数，供账单页与记账页共用，避免两页口径不一致）。
 *
 * | 情形 | 可编辑范围 |
 * |---|---|
 * | `status == MERGED` | **全部禁止**：它已被并入另一条主流水，改它没有任何意义，只会制造"改了没反应"的困惑 |
 * | 已关联订单 / 退款 / 内部划转（`orderId` / `refundId` / `transferGroupId` 非空） | **禁止改金额与日期**：金额改动会破坏退款抵扣的对账，日期改动会破坏跨渠道配对；**商户/备注/分类/标签仍可改** |
 * | 其余 | 全部可改 |
 */
object TxnEditRules {

    /** 是否允许编辑这笔流水（任一字段）。 */
    fun canEdit(txn: LedgerTransaction): Boolean = txn.status != TxnStatus.MERGED

    /** 是否允许改金额 / 日期。 */
    fun canEditAmountAndDate(txn: LedgerTransaction): Boolean =
        txn.orderId == null && txn.refundId == null && txn.transferGroupId == null

    /** 不可编辑时给用户的说明；可编辑返回 null。 */
    fun blockReason(txn: LedgerTransaction): String? = when {
        !canEdit(txn) -> "该笔已并入其他流水，不可修改"
        !canEditAmountAndDate(txn) -> "已与订单 / 退款 / 内部划转关联，金额与日期不可修改"
        else -> null
    }
}

/**
 * 把「商户名 / 备注 / 消费平台」编辑写回一笔流水（纯函数，便于 JVM 单测）。
 *
 * - 商户名去首尾空白；备注去首尾空白，纯空白归一为 `null`；
 * - **消费平台：用户改过就记为权威值**（`platformSource = USER`、`platformConfidence = 1f`）。
 *   之后任何自动流程（重解析、合并继承、再次 ingest）都不得改写 —— 用户意图优先于自动识别。
 *   未改动时保持原值与原来源。
 * - **重算去重指纹**：商户名参与指纹（见 `LedgerDuplicateResolver.fingerprintOf`）。
 *   自动抓取的流水商户名常缺失（指纹退化为 `金额|blank|来源`），用户补上后必须重算，
 *   否则它与其它渠道的同笔记录对不上，跨渠道去重会失效。
 *
 * 注意：**消费平台不参与指纹**（见 `LedgerDuplicateResolver` 的注释）——
 * 同一笔消费的「通道通知」与「银行短信」平台可能不同（美团 vs 未知），
 * 平台一旦进指纹，一笔消费就会被拆成两条。故改平台**不会**改变指纹，这是刻意设计。
 *
 * @param fingerprintOf 注入的指纹算法，生产环境传 `duplicateResolver::fingerprintOf`
 */
internal fun applyTxnEdit(
    txn: LedgerTransaction,
    counterparty: String,
    note: String?,
    platformId: String = txn.platformId,
    fingerprintOf: (LedgerTransaction) -> String,
): LedgerTransaction {
    val userChangedPlatform = platformId != txn.platformId
    val edited = txn.copy(
        counterparty = counterparty.trim(),
        note = note?.trim()?.ifBlank { null },
        platformId = platformId,
        platformConfidence = if (userChangedPlatform) 1f else txn.platformConfidence,
        platformSource = if (userChangedPlatform) PlatformSource.USER else txn.platformSource,
    )
    return edited.copy(fingerprint = fingerprintOf(edited))
}

/**
 * 改金额 / 日期（纯函数，便于 JVM 单测）。
 *
 * **刻意不改符号与类型**：只替换金额的绝对值，支出改完仍是支出、收入改完仍是收入。
 * 「改个金额把一笔支出变成收入」属于惊吓型行为，符号/方向的调整应当由用户显式选择类型，
 * 而不是金额输入的副产品。
 *
 * **必须重算指纹**：`fingerprintOf` 的指纹材料含 `amountMinor`，
 * 不重算的话这笔会带着旧金额的指纹留在库里，跨渠道去重再也匹配不上。
 *
 * **分类与置信度保持不动**：金额变了，原分类确实可能不再精准，但
 * `confidence` 目前没有任何消费方会据它触发「请重新选择分类」（`status` 才是待确认的 gate），
 * 把它调低只是**悄悄改了一个没人读的字段**，反而制造"数据被改了但界面毫无反应"的假象。
 * 因此这里保持原值与原分类，把"要不要重选分类"交给用户显式决定。
 *
 * @param amountMinor 用户输入的**绝对值**（单位分）；符号沿用原值
 * @param occurredAtMillis 新的发生时间
 * @param bookedAtMillis 入账时间**保持不变**（它是"这条记录何时进的账"，不是消费发生时间）
 */
internal fun applyAmountAndDateEdit(
    txn: LedgerTransaction,
    amountMinor: Long,
    occurredAtMillis: Long,
    fingerprintOf: (LedgerTransaction) -> String,
): LedgerTransaction {
    val abs = kotlin.math.abs(amountMinor)
    val signed = if (txn.amountMinor < 0) -abs else abs
    val edited = txn.copy(
        amountMinor = signed,
        occurredAtMillis = occurredAtMillis,
        bookedAtMillis = txn.bookedAtMillis,
    )
    return edited.copy(fingerprint = fingerprintOf(edited))
}
