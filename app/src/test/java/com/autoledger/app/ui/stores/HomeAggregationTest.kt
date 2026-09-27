package com.autoledger.app.ui.stores

import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.LedgerTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R4 回归：`observeSince` 只有左边界，HomeStore 必须把集合裁剪成「本月 + 非内部划转」。
 *
 * 注意：**退款(REFUND)必须保留**。汇总里的「付款总额 / 退款总额 / 净支出」统一由
 * [com.autoledger.core.model.ExpenseMath] 计算；若在这里把 REFUND 一并过滤，退款将永远为 0。
 *
 * 这两个断言（跨月排除 + 右边界包含）正是之前 Flow 路径多算/漏算的两个失效点，
 * 抽成纯函数后可以脱离 Android/Room 直接验证。
 */
class HomeAggregationTest {

    // 用固定时刻构造一个月窗口，避免依赖运行时的"当前时间"。
    private val monthStart = 1_700_000_000_000L        // 任取一个月初锚点
    private val monthEnd = monthStart + 20L * 24 * 3600 * 1000 // 月内某一天作为右边界
    private val month = TimeRange(monthStart, monthEnd)

    private fun txn(
        id: String,
        occurredAt: Long,
        type: TxnType = TxnType.EXPENSE,
        amountMinor: Long = -1000L,
        status: TxnStatus = TxnStatus.CONFIRMED,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = type,
        counterparty = "某商户",
        sourceId = "notify",
        sourceRef = "notify:$id",
        status = status,
    )

    @Test
    fun includesBothBoundaries_leftClosedRightClosed() {
        val result = clipToMonthSpending(
            listOf(
                txn("start", occurredAt = monthStart),      // 左边界（含）
                txn("end", occurredAt = monthEnd),          // 右边界（含）
                txn("mid", occurredAt = monthStart + 1000), // 月中
            ),
            month,
        )
        assertEquals(listOf("start", "end", "mid"), result.map { it.id })
    }

    @Test
    fun excludesNextMonth_andPreviousMonth() {
        val result = clipToMonthSpending(
            listOf(
                txn("prev", occurredAt = monthStart - 1),      // 上个月（越界）
                txn("inside", occurredAt = monthStart + 5),
                txn("next", occurredAt = monthEnd + 1),        // 下个月（越界）
                txn("future", occurredAt = monthEnd + 86_400_000), // 未来日期（预授权/跨时区）
            ),
            month,
        )
        assertEquals(listOf("inside"), result.map { it.id })
    }

    @Test
    fun refundsAreAlsoBoundedByMonthWindow() {
        // 退款同样受月份窗口约束：上月末的退款被裁掉、本月的保留、下月的排除——
        // 与 excludesNextMonth_andPreviousMonth 一起构成"退款也受时间边界约束"的护栏。
        val result = clipToMonthSpending(
            listOf(
                txn("prevRefund", occurredAt = monthStart - 1, type = TxnType.REFUND, amountMinor = 5_000L),
                txn("thisRefund", occurredAt = monthStart + 5, type = TxnType.REFUND, amountMinor = 5_000L),
                txn("nextRefund", occurredAt = monthEnd + 1, type = TxnType.REFUND, amountMinor = 5_000L),
            ),
            month,
        )
        assertEquals(listOf("thisRefund"), result.map { it.id })
    }

    @Test
    fun excludesTransfers_butKeepsRefunds_forNetCalculation() {
        // 退款必须保留：汇总里的「退款总额 / 净支出」由 ExpenseMath 统一口径计算，
        // 若在这里把 REFUND 过滤掉，monthRefundMinor 会恒为 0、退款永远显示不出来。
        val result = clipToMonthSpending(
            listOf(
                txn("expense", occurredAt = monthStart + 10, type = TxnType.EXPENSE),
                txn("income", occurredAt = monthStart + 20, type = TxnType.INCOME, amountMinor = 500L),
                txn("transfer", occurredAt = monthStart + 30, type = TxnType.TRANSFER),
                txn("refund", occurredAt = monthStart + 40, type = TxnType.REFUND),
            ),
            month,
        )
        assertEquals(listOf("expense", "income", "refund"), result.map { it.id })
    }

    @Test
    fun keepsRawStatus_soPendingReviewCanSeeBacklog() {
        // RAW 也要保留在集合里（待确认卡片依赖它），本函数只做时间/类型裁剪，不动 status。
        val result = clipToMonthSpending(
            listOf(txn("raw", occurredAt = monthStart + 5, status = TxnStatus.RAW)),
            month,
        )
        assertTrue(result.any { it.status == TxnStatus.RAW })
    }
}