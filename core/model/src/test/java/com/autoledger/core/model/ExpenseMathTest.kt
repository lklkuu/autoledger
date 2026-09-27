package com.autoledger.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** 退款冲抵口径 —— 纯 JVM 单测。 */
class ExpenseMathTest {

    private val t0 = 1_700_000_000_000L

    private fun txn(
        id: String,
        minor: Long,
        type: TxnType,
        categoryId: String? = "c1",
        counterparty: String = "某商户",
        sourceId: String = "notify",
        status: TxnStatus = TxnStatus.CONFIRMED,
    ) = LedgerTransaction(
        id = id, amountMinor = minor, occurredAtMillis = t0, type = type,
        counterparty = counterparty, sourceId = sourceId, sourceRef = "r:$id",
        categoryId = categoryId, status = status,
    )

    private fun expense(id: String, minor: Long, categoryId: String? = "c1") =
        txn(id, -minor, TxnType.EXPENSE, categoryId)

    private fun refund(id: String, minor: Long, categoryId: String? = "c1") =
        txn(id, minor, TxnType.REFUND, categoryId)

    @Test
    fun `gross refund and net are computed from expense and refund only`() {
        val list = listOf(
            expense("e1", 10_000),
            expense("e2", 5_000),
            refund("r1", 3_000),
            txn("i1", 8_000, TxnType.INCOME),
            txn("t1", -7_000, TxnType.TRANSFER),
        )
        assertEquals(15_000L, ExpenseMath.grossExpenseMinor(list))
        assertEquals(3_000L, ExpenseMath.refundMinor(list))
        assertEquals(12_000L, ExpenseMath.netExpenseMinor(list), "净支出 = 毛支出 − 退款")
    }

    @Test
    fun `merged records are excluded from both sides`() {
        val list = listOf(
            expense("e1", 10_000),
            expense("e2", 99_000).copy(status = TxnStatus.MERGED),
            refund("r1", 4_000),
            refund("r2", 88_000).copy(status = TxnStatus.MERGED),
        )
        assertEquals(10_000L, ExpenseMath.grossExpenseMinor(list))
        assertEquals(4_000L, ExpenseMath.refundMinor(list))
        assertEquals(6_000L, ExpenseMath.netExpenseMinor(list))
    }

    @Test
    fun `net can go negative when refunds exceed expenses`() {
        val list = listOf(expense("e1", 1_000), refund("r1", 3_000))
        assertEquals(-2_000L, ExpenseMath.netExpenseMinor(list), "异常数据应暴露为负数，而不是被夹成 0")
    }

    @Test
    fun `refund offsets its own category`() {
        val list = listOf(
            expense("e1", 10_000, "c1"),
            expense("e2", 6_000, "c2"),
            refund("r1", 4_000, "c1"),
        )
        val net = ExpenseMath.netExpenseByCategory(list)
        assertEquals(6_000L, net["c1"], "c1 的净支出应被退款冲抵")
        assertEquals(6_000L, net["c2"], "c2 不受影响")
    }

    @Test
    fun `refund without category falls into the null bucket`() {
        val list = listOf(expense("e1", 10_000, "c1"), refund("r1", 2_500, null))
        val net = ExpenseMath.netExpenseByCategory(list)
        assertEquals(10_000L, net["c1"])
        assertEquals(-2_500L, net[null], "无分类退款落在 null 桶，只冲抵总额")
        assertEquals(7_500L, net.values.sum())
    }

    @Test
    fun `netBy groups and offsets by an arbitrary key`() {
        val list = listOf(
            expense("e1", 5_000).copy(sourceId = "notify"),
            expense("e2", 2_000).copy(sourceId = "notify"),
            refund("r1", 1_000).copy(sourceId = "notify"),
            expense("e3", 4_000).copy(sourceId = "sms"),
            refund("r2", 9_000).copy(sourceId = "sms"),
        )
        val bySource = ExpenseMath.netBy(list) { it.sourceId }
        assertEquals(6_000L, bySource["notify"])
        // sms 净额为负（退款多于支出）→ 被过滤，避免出现负占比
        assertEquals(null, bySource["sms"])
    }

    @Test
    fun `netBy excludes income and transfer`() {
        val list = listOf(
            expense("e1", 3_000, "c1"),
            txn("i1", 9_000, TxnType.INCOME, "c1"),
            txn("t1", -9_000, TxnType.TRANSFER, "c1"),
        )
        val byCategory = ExpenseMath.netBy(list) { it.categoryId }
        assertEquals(3_000L, byCategory["c1"])
    }

    @Test
    fun `refund of a previous month does not resurrect a zero bucket`() {
        val list = listOf(expense("e1", 2_000, "c1"), refund("r1", 2_000, "c1"))
        val net = ExpenseMath.netExpenseByCategory(list)
        assertEquals(0L, net["c1"], "刚好退完 → 净额 0")
        assertEquals(0L, net.values.sum())
    }
}
