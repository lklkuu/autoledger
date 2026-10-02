package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
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
 * 切换目标类型：只支持「支出 ⇄ 收入」二值互转。
 *
 * 其余类型（退款 / 内部划转）没有可切换的目标 —— 它们不是收支，
 * 改成支出/收入等于伪造事实（详见 [typeSwitchBlockReason]）。
 */
fun nextType(txn: LedgerTransaction): TxnType =
    if (txn.type == TxnType.EXPENSE) TxnType.INCOME else TxnType.EXPENSE

/**
 * 能否切换收支类型（比 [TxnEditRules.canEdit] 更严）；不能时给用户原因，可切换返回 null。
 *
 * 判据比"能否编辑"更严的原因：改类型会**翻转金额符号**，而金额是退款抵扣对账、
 * 合并链继承、指纹匹配三处的共同基准，动它等于动三处账。
 *
 * ⚠️ 实现纪律（两条，改动时务必一起看）：
 * 1. **必须用 `when { }` 逐条早返回**，禁止 `&&` / `||` 混写 ——
 *    混写会因运算符优先级把"退款/划转"这类类型判据吞进错误的分支，出现"以为拦住了其实没拦"。
 * 2. **判据与顺序必须与 [canSwitchType] 完全一致** —— 实际上 [canSwitchType] 就是本函数的 `== null`，
 *    两个函数天然一致；改这里不必改那里，反之亦然。
 */
fun typeSwitchBlockReason(txn: LedgerTransaction, absorbedCount: Int = 0): String? = when {
    txn.status == TxnStatus.MERGED -> "该笔已并入其他流水，不可修改"
    txn.type != TxnType.EXPENSE && txn.type != TxnType.INCOME ->
        "退款 / 内部划转不参与收支，不能改成收入或支出"
    txn.orderId != null -> "已关联订单，改收支类型会破坏退款抵扣对账"
    txn.refundId != null -> "已关联退款，不可改收支类型"
    txn.transferGroupId != null -> "已配对为内部划转，不可改收支类型"
    absorbedCount > 0 -> "这条已合并了 $absorbedCount 条记录，改类型会让合并链两侧口径不一致"
    else -> null
}

/** 能否切换收支类型（判据与顺序完全来自 [typeSwitchBlockReason]，不另立一套）。 */
fun canSwitchType(txn: LedgerTransaction, absorbedCount: Int = 0): Boolean =
    typeSwitchBlockReason(txn, absorbedCount) == null

/**
 * 切换收支类型（纯函数，便于 JVM 单测）。
 *
 * **符号随类型翻转**：`EXPENSE` 记负、`INCOME` 记正。金额取 `safeAbs`，所以用户输入
 * `100` 或 `-100` 结果一致（弹窗传进来的是绝对值）。
 *
 * **必须重算指纹**：[LedgerDuplicateResolver.fingerprintOf] 的指纹材料含**带符号**金额
 * （`金额|商户|…`），不重算的话这笔会带着旧金额的旧符号留在库里，跨渠道去重再也匹配不上。
 *
 * **分类保持不动**（`categoryId` 原样保留）：收入行的分类**不参与任何 `ExpenseMath` 口径**
 * （见 ExpenseMath：只认 EXPENSE / REFUND / INCOME 的金额聚合，分类只用于支出的预算与冲抵归桶），
 * 留着只是用户自己写的备注。⚠️ 若将来有人加"按分类统计收入"，必须先决定收入要不要用分类，
 * 不能默认沿用支出那套 —— 否则收入也会挤占分类预算。
 */
internal fun applyTypeSwitch(
    txn: LedgerTransaction,
    newType: TxnType,
    fingerprintOf: (LedgerTransaction) -> String,
): LedgerTransaction {
    val abs = safeAbs(txn.amountMinor)
    val signed = if (newType == TxnType.EXPENSE) -abs else abs
    val edited = txn.copy(type = newType, amountMinor = signed)
    return edited.copy(fingerprint = fingerprintOf(edited))
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
    val abs = safeAbs(amountMinor)
    val signed = if (txn.amountMinor < 0) -abs else abs
    val edited = txn.copy(
        amountMinor = signed,
        occurredAtMillis = occurredAtMillis,
        bookedAtMillis = txn.bookedAtMillis,
    )
    return edited.copy(fingerprint = fingerprintOf(edited))
}

/**
 * 一次算完**全部**可编辑字段（商户 / 备注 / 平台 / 金额 / 日期），供单次 upsert 使用。
 *
 * 存在的理由：若拆成「改商户」与「改金额」两次独立写入，两者都基于同一个旧副本各自 copy，
 * 后一次 upsert 会**整行覆盖**前一次的结果 —— 用户改了商户和金额，最后只剩一个字段生效。
 * 合并成一次计算 + 一次写入，才不会出现这种静默丢字段。
 *
 * @param amountMinor 用户输入的绝对值（分）；null 表示不改金额
 * @param occurredAtMillis 新的发生时间；null 表示不改日期
 * @param type 目标收支类型（默认不变）。v1.1.6 起符号基准由「原符号」改为「目标类型」：
 *   只改类型不改金额时也会把符号翻到与类型一致，避免留下 `EXPENSE + 正数` 这种错色脏行。
 *   **但不能无条件覆盖** —— 类型未变时必须沿用原符号（REFUND 恒正、TRANSFER 可正可负），
 *   所以用三分支 `when` 而非 `if (type == EXPENSE) -abs else abs`。
 */
internal fun applyFullEdit(
    txn: LedgerTransaction,
    counterparty: String,
    note: String?,
    platformId: String,
    amountMinor: Long?,
    occurredAtMillis: Long?,
    type: TxnType = txn.type,
    fingerprintOf: (LedgerTransaction) -> String,
): LedgerTransaction {
    val userChangedPlatform = platformId != txn.platformId
    var edited = txn.copy(
        counterparty = counterparty.trim(),
        note = note?.trim()?.ifBlank { null },
        platformId = platformId,
        platformConfidence = if (userChangedPlatform) 1f else txn.platformConfidence,
        platformSource = if (userChangedPlatform) PlatformSource.USER else txn.platformSource,
    )
    if (amountMinor != null || occurredAtMillis != null || type != txn.type) {
        val abs = safeAbs(amountMinor ?: txn.amountMinor)
        val signed = when {
            // 类型未变：沿用原符号（退款恒正、内部划转可正可负，绝不能"顺手翻正"）
            type == txn.type -> if (txn.amountMinor < 0) -abs else abs
            // 目标为支出：翻负；目标为收入（其余分支只可能是 INCOME）：翻正
            type == TxnType.EXPENSE -> -abs
            else -> abs
        }
        edited = edited.copy(
            type = type,
            amountMinor = signed,
            occurredAtMillis = occurredAtMillis ?: txn.occurredAtMillis,
            bookedAtMillis = txn.bookedAtMillis,
        )
    }
    return edited.copy(fingerprint = fingerprintOf(edited))
}

/** [kotlin.math.abs] 对 Long.MIN_VALUE 会溢出成负数，这里兜住。 */
private fun safeAbs(value: Long): Long =
    if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)
