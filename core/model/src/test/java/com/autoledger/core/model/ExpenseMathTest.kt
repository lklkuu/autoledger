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
    fun `ignored records are excluded from both sides`() {
        // P1：「忽略这笔」= 这笔不算账。被忽略的流水必须从统计里消失，
        // 而不只是移出待确认队列。
        val list = listOf(
            expense("e1", 10_000),
            expense("e2", 99_000).copy(status = TxnStatus.IGNORED),
            refund("r1", 4_000),
            refund("r2", 88_000).copy(status = TxnStatus.IGNORED),
        )
        assertEquals(10_000L, ExpenseMath.grossExpenseMinor(list), "被忽略的支出不得计入毛支出")
        assertEquals(4_000L, ExpenseMath.refundMinor(list), "被忽略的退款不得计入退款")
        assertEquals(6_000L, ExpenseMath.netExpenseMinor(list))
    }

    @Test
    fun `duplicate records are excluded from expense refund and income`() {
        // 「疑似重复、默认不成立」：DUPLICATE 的流水既不计支出、也不计退款冲抵、更不计实际到账。
        // 它只是采集箱里等用户拍板的一条**提案**，一旦进统计就会和主记录重复计账 ——
        // 这正是用户报的「30 秒内的重复通知要合并、多余记录不要直接进账单」在统计口径上的落点。
        val expenseDup = expense("e-dup", 99_000).copy(status = TxnStatus.DUPLICATE)
        val refundDup = refund("r-dup", 88_000).copy(status = TxnStatus.DUPLICATE)
        val incomeDup = txn("i-dup", 77_000, TxnType.INCOME, status = TxnStatus.DUPLICATE)

        assertEquals(0L, ExpenseMath.grossExpenseMinor(listOf(expenseDup)), "疑似重复的支出不得计入毛支出")
        assertEquals(0L, ExpenseMath.refundMinor(listOf(refundDup)), "疑似重复的退款不得冲抵支出")
        assertEquals(0L, ExpenseMath.actualReceivedMinor(listOf(incomeDup)), "疑似重复的收入不得计入实际到账")
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

    // ------------------------------------------------------------ 实际到账口径（v8 改名）
    //
    // ⚠️ 本段断言的是「实际到账合计」= INCOME 流水合计，**只用于对账 / 明细展示**。
    // 收入汇总的唯一真源是 WageLedger（版本化的月度应计值），见 WageLedgerTest。
    // 这些用例保留是为了钉住「改名不改行为」以及 MERGED / IGNORED 的过滤语义。

    @Test
    fun `actual received sums absolute amounts and ignores everything else`() {
        val list = listOf(
            txn("i1", 9_000, TxnType.INCOME),
            txn("i2", 1_000, TxnType.INCOME),
            expense("e1", 5_000),
            refund("r1", 2_000),
            txn("t1", -5_000, TxnType.TRANSFER),
        )
        // 只计 INCOME；支出/退款/划转一律不计（退款是冲抵项、划转不是收支）
        assertEquals(10_000L, ExpenseMath.actualReceivedMinor(list))
    }

    @Test
    fun `actual received takes the absolute value regardless of the sign`() {
        // 收入的正负号是采集侧的历史约定（银行卡入账短信识别为负的情况），合计只取金额大小
        assertEquals(3_000L, ExpenseMath.actualReceivedMinor(listOf(txn("i1", 3_000, TxnType.INCOME))))
        assertEquals(3_000L, ExpenseMath.actualReceivedMinor(listOf(txn("i2", -3_000, TxnType.INCOME))))
    }

    @Test
    fun `actual received excludes merged and ignored rows`() {
        val list = listOf(
            txn("i1", 9_000, TxnType.INCOME),
            txn("i-merged", 5_000, TxnType.INCOME, status = TxnStatus.MERGED),
            txn("i-ignored", 7_000, TxnType.INCOME, status = TxnStatus.IGNORED),
        )
        assertEquals(9_000L, ExpenseMath.actualReceivedMinor(list), "被合并 / 被忽略的收入都不算账")
    }

    @Test
    fun `actual received and net expense never cancel each other`() {
        // 两条独立口径：退款只冲抵支出，绝不冲抵收入（否则「退款」会被当成"少赚了钱"）
        val list = listOf(
            txn("i1", 1_000_00, TxnType.INCOME),
            expense("e1", 1_350_00),
            refund("r1", 200_00),
        )
        assertEquals(1_000_00L, ExpenseMath.actualReceivedMinor(list))
        assertEquals(1_150_00L, ExpenseMath.netExpenseMinor(list), "退款只冲抵支出")
    }
}
