package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「发现」页 Facts 口径的纯 JVM 护栏（T10）。
 *
 * 钉死 T2 修复的关键点：**退款必须参与口径**（日均被冲抵、最近记录含退款、商户被负向冲抵），
 * 且内部划转必须被剔除。此前这些计算埋在 InsightsStore.load 内、零覆盖。
 */
class InsightsAggregationTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun txn(
        id: String,
        type: TxnType,
        amountMinor: Long,
        occurredAt: Long = 1_700_000_000_000L,
        counterparty: String = "某商户",
        categoryId: String? = null,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = type,
        counterparty = counterparty,
        sourceId = "notify",
        sourceRef = "notify:$id",
        status = TxnStatus.CONFIRMED,
        categoryId = categoryId,
    )

    @Test
    fun `refund offsets the daily average`() {
        val expenseOnly = listOf(txn("e", TxnType.EXPENSE, -10_000L))
        val withRefund = expenseOnly + txn("r", TxnType.REFUND, 3_000L)

        val base = computeInsightsFacts(expenseOnly, emptyMap(), days = 1, zone = zone)
        val refunded = computeInsightsFacts(withRefund, emptyMap(), days = 1, zone = zone)

        assertEquals(10_000L, base.avgDailyMinor, "无退款时日均 = 毛支出")
        assertEquals(7_000L, refunded.avgDailyMinor, "有退款时日均必须被冲抵（100 − 30）")
        assertTrue(refunded.avgDailyMinor < base.avgDailyMinor)
    }

    @Test
    fun `recent includes refunds but excludes internal transfers`() {
        val facts = computeInsightsFacts(
            listOf(
                txn("e", TxnType.EXPENSE, -10_000L, occurredAt = 3),
                txn("r", TxnType.REFUND, 3_000L, occurredAt = 2),
                txn("t", TxnType.TRANSFER, -5_000L, occurredAt = 1),
            ),
            emptyMap(),
            days = 1,
            zone = zone,
        )
        val ids = facts.recent.map { it.id }
        assertTrue("r" in ids, "最近记录应包含退款")
        assertTrue("t" !in ids, "最近记录不得包含内部划转")
    }

    @Test
    fun `top merchant is offset by refunds`() {
        val facts = computeInsightsFacts(
            listOf(
                txn("e", TxnType.EXPENSE, -10_000L, counterparty = "星巴克"),
                txn("r", TxnType.REFUND, 3_000L, counterparty = "星巴克"),
            ),
            emptyMap(),
            days = 1,
            zone = zone,
        )
        assertEquals("星巴克" to 7_000L, facts.topMerchant, "同商户支出 100 − 退款 30 = 70")
    }
}
