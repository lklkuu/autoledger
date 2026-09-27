package com.autoledger.app.ui.stores

import com.autoledger.core.model.Category
import com.autoledger.core.model.ExpenseMath
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType
import java.time.Instant
import java.time.ZoneId

/**
 * 「发现」页 Facts 的口径计算（纯函数，不依赖 Android / Room，可 JVM 单测）。
 *
 * 口径（统一走 ExpenseMath，避免漂移）：
 * - 先剔除内部划转(TRANSFER)，得到 [allMonth] → `spending`（支出 + 收入 + 退款）；
 * - 商户 / 工作日-周末按退款**负向**冲抵；
 * - 日均支出 = `ExpenseMath.netExpenseMinor(spending) / days`（**退款参与冲抵**）；
 * - 最近记录取 `spending` 最近 3 条（含收入与退款、不含内部划转）。
 *
 * 抽成纯函数是为了让「退款参与口径」这条修复能被单测钉死 —— 此前它埋在 InsightsStore.load 内部，
 * 出现 `listRange` 漏传 `includeTransfers=true` 时零测试能发现（退款被静默剔除、日均退化为毛额）。
 */
internal fun computeInsightsFacts(
    allMonth: List<LedgerTransaction>,
    categories: Map<String, Category>,
    days: Int,
    zone: ZoneId,
): InsightsStore.Facts {
    // 取全量后自行剔除内部划转；退款必须保留参与口径。
    val spending = allMonth.filter { it.type != TxnType.TRANSFER }
    val txns = spending.filter { it.type == TxnType.EXPENSE || it.type == TxnType.REFUND }
    val merchants = LinkedHashMap<String, Long>()
    var weekday = 0L
    var weekend = 0L
    txns.forEach { t ->
        // 退款记为负向，冲抵商户 / 工作日-周末口径
        val amount = if (t.type == TxnType.REFUND) -kotlin.math.abs(t.amountMinor) else kotlin.math.abs(t.amountMinor)
        if (t.counterparty.isNotBlank()) {
            merchants[t.counterparty] = (merchants[t.counterparty] ?: 0L) + amount
        }
        val day = Instant.ofEpochMilli(t.occurredAtMillis).atZone(zone).dayOfWeek
        if (day.value >= 6) weekend += amount else weekday += amount
    }
    return InsightsStore.Facts(
        largestTxn = spending.filter { it.type == TxnType.EXPENSE }.maxByOrNull { kotlin.math.abs(it.amountMinor) },
        topMerchant = merchants.maxByOrNull { it.value }?.toPair(),
        weekdayVsWeekend = weekday to weekend,
        avgDailyMinor = ExpenseMath.netExpenseMinor(spending).coerceAtLeast(0L) / days.coerceAtLeast(1),
        unclassifiedCount = spending.count { it.type == TxnType.EXPENSE && it.categoryId == null },
        recent = spending.sortedByDescending { it.occurredAtMillis }.take(3),
        categories = categories,
    )
}
