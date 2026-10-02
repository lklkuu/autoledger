package com.autoledger.core.model

import kotlin.math.abs

/**
 * **退款冲抵口径（唯一真源）**。
 *
 * 不建订单的前提下，退款就是一笔独立的 `REFUND` 流水；它对账单的影响是
 * **冲抵支出**，而不是计入收入：
 *
 * - 毛支出 `grossExpenseMinor` = Σ|EXPENSE|
 * - 退款   `refundMinor`       = Σ|REFUND|
 * - 净支出 `netExpenseMinor`   = 毛支出 − 退款
 *
 * 约定：
 * - 只统计 `status` 既不是 MERGED（被合并的不重复计）也不是 IGNORED（用户主动「忽略这笔」= 不算账）的记录。
 *   被忽略的流水必须从**所有**账本视图与统计中消失，而不只是移出待确认队列。
 * - `TRANSFER` / `INCOME` 一律不参与（内部划转不是消费、收入不是冲抵）。
 * - 净额**不下限为 0**（负数代表"退款多于支出"，属数据异常，应当暴露出来；展示层再决定是否夹取）。
 * - 退款按其 `categoryId` 冲抵**同一分类**；无分类的退款落在 `null` 桶里，只冲抵总额。
 *
 * 所有聚合（统计卡片 / 预算 / 首页 / 账单）都必须走这里，避免口径漂移。
 */
object ExpenseMath {

    fun countsAsExpense(txn: LedgerTransaction): Boolean =
        txn.type == TxnType.EXPENSE && txn.status != TxnStatus.MERGED && txn.status != TxnStatus.IGNORED

    fun countsAsRefund(txn: LedgerTransaction): Boolean =
        txn.type == TxnType.REFUND && txn.status != TxnStatus.MERGED && txn.status != TxnStatus.IGNORED

    /** 毛支出（未扣退款的支出合计）。 */
    fun grossExpenseMinor(txns: List<LedgerTransaction>): Long =
        txns.filter(::countsAsExpense).sumOf { abs(it.amountMinor) }

    /** 退款合计（正数）。 */
    fun refundMinor(txns: List<LedgerTransaction>): Long =
        txns.filter(::countsAsRefund).sumOf { abs(it.amountMinor) }

    /** 净支出 = 毛支出 − 退款。 */
    fun netExpenseMinor(txns: List<LedgerTransaction>): Long =
        grossExpenseMinor(txns) - refundMinor(txns)

    /**
     * **收入合计（正数）**。
     *
     * 为什么要单列：收入此前**没有单一真源** —— 账单页月汇总 / 年度汇总 / 逐月趋势表三处各写一遍
     * `filter { type == INCOME }.sumOf { abs(amountMinor) }`，改口径要改三处、漏一处就漂移。
     *
     * 口径与支出侧对齐：只计 `INCOME` 且排除 MERGED / IGNORED；**INCOME 不参与支出冲抵**
     * （见文件头 KDoc：退款才是冲抵项，内部划转与收入都不是），故不复用 netExpenseMinor 的减法。
     * 金额取绝对值：收入的正负号是采集侧的历史约定（银行卡入账短信识别为正），
     * 这里只要金额大小，不让符号影响合计。
     */
    fun incomeMinor(txns: List<LedgerTransaction>): Long =
        txns.filter { it.type == TxnType.INCOME && it.status != TxnStatus.MERGED && it.status != TxnStatus.IGNORED }
            .sumOf { abs(it.amountMinor) }

    /**
     * 按任意维度分组后的净额：支出记 `+`、退款记 `−`。
     * 用于分类结构 / 商户排行 / 渠道分布 / 月度趋势这类"按维度看支出"的聚合。
     *
     * @return 只包含净额 > 0 的桶（负桶代表该维度退款多于支出，属异常，已被过滤掉以免出现负占比）
     */
    fun <K> netBy(txns: List<LedgerTransaction>, keyOf: (LedgerTransaction) -> K?): Map<K?, Long> {
        val buckets = LinkedHashMap<K?, Long>()
        txns.forEach { txn ->
            val signed = when {
                countsAsExpense(txn) -> abs(txn.amountMinor)
                countsAsRefund(txn) -> -abs(txn.amountMinor)
                else -> return@forEach
            }
            val key = keyOf(txn)
            buckets[key] = (buckets[key] ?: 0L) + signed
        }
        return buckets.filterValues { it > 0L }
    }

    /**
     * 按分类的净支出（含 `null` 未分类桶），**保留**负值。
     * 预算执行需要看到真实的净额（退款后应减少该分类的已花）。
     */
    fun netExpenseByCategory(txns: List<LedgerTransaction>): Map<String?, Long> {
        val buckets = LinkedHashMap<String?, Long>()
        txns.forEach { txn ->
            val signed = when {
                countsAsExpense(txn) -> abs(txn.amountMinor)
                countsAsRefund(txn) -> -abs(txn.amountMinor)
                else -> return@forEach
            }
            buckets[txn.categoryId] = (buckets[txn.categoryId] ?: 0L) + signed
        }
        return buckets
    }
}
