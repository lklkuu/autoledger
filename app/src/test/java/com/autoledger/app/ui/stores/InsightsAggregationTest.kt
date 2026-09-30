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

    // ------------------------------------------------------------ recordCount（按月查看的空态判定）

    @Test
    fun `recordCount ignores internal transfers`() {
        // 只有内部划转的月份：金额正负抵消为 0，但确实"没有可展示的收支记录"。
        // 若用「金额是否全为 0」判空就会误判成有数据；recordCount 才是可靠依据。
        val facts = computeInsightsFacts(
            listOf(
                txn("a", TxnType.TRANSFER, -50_000L, counterparty = "自己"),
                txn("b", TxnType.TRANSFER, 50_000L, counterparty = "自己"),
            ),
            emptyMap(),
            days = 30,
            zone = zone,
        )
        assertEquals(0, facts.recordCount, "内部划转不计入 recordCount")
    }

    @Test
    fun `recordCount is zero for an empty month`() {
        val facts = computeInsightsFacts(emptyList(), emptyMap(), days = 30, zone = zone)
        assertEquals(0, facts.recordCount)
        assertTrue(facts.recent.isEmpty())
    }

    @Test
    fun `recordCount counts expenses and refunds but not transfers`() {
        val facts = computeInsightsFacts(
            listOf(
                txn("e", TxnType.EXPENSE, -10_000L),
                txn("r", TxnType.REFUND, 3_000L),
                txn("t", TxnType.TRANSFER, -5_000L),
            ),
            emptyMap(),
            days = 30,
            zone = zone,
        )
        assertEquals(2, facts.recordCount, "支出 + 退款 = 2；内部划转剔除")
    }
}
