package com.autoledger.core.database.repository

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [LedgerTransaction.isConsumption] 的语义护栏。
 *
 * 背景（P1）：「忽略这笔」= 这笔不算账。此前所有「流水集合」口径都只排除 MERGED，
 * 导致被忽略的流水仍会出现在账本列表与支出统计里。本类与
 * `ExpenseMathTest#ignored records are excluded from both sides`、
 * `RoomDatabaseIntegrationTest#ignored rows disappear from every ledger view (real db)`
 * 共同锁住这条约定。
 *
 * 注：`isConsumption` 目前是供 finance 层复用的扩展，**当前尚无调用方**（dead code 风险）。
 * 一旦后续接线，本测试就是它的第一道防线；若最终确认不再需要，删除本类时应一并删除该扩展。
 */
class MappersTest {

    private fun txn(
        id: String = "t1",
        type: TxnType = TxnType.EXPENSE,
        status: TxnStatus = TxnStatus.CONFIRMED,
    ): LedgerTransaction = LedgerTransaction(
        id = id,
        amountMinor = -3_980L,
        occurredAtMillis = 1_700_000_000_000L,
        type = type,
        sourceId = "sms:inbox",
        sourceRef = id,
        status = status,
    )

    @Test
    fun `a confirmed expense counts as consumption`() {
        assertTrue(txn(status = TxnStatus.CONFIRMED).isConsumption())
        assertTrue(txn(status = TxnStatus.RAW).isConsumption(), "待确认的支出依然要计入账本")
    }

    @Test
    fun `merged and ignored expenses are not consumption`() {
        assertFalse(txn(status = TxnStatus.MERGED).isConsumption(), "被合并的不重复计")
        assertFalse(txn(status = TxnStatus.IGNORED).isConsumption(), "被用户忽略的不算账")
    }

    @Test
    fun `non expense types are never consumption`() {
        for (type in listOf(TxnType.INCOME, TxnType.TRANSFER, TxnType.REFUND)) {
            assertFalse(txn(type = type).isConsumption(), "$type 不是消费")
            assertFalse(
                txn(type = type, status = TxnStatus.IGNORED).isConsumption(),
                "$type 即便被忽略也不应被当成消费",
            )
        }
    }

    @Test
    fun `isConsumption agrees with ExpenseMath on the ignored rule`() {
        // 两条独立路径（Mappers / ExpenseMath）对 IGNORED 的判定必须一致，
        // 否则「忽略」只在其中一条链路上生效，账本与统计会打架。
        val cases = listOf(
            txn(status = TxnStatus.CONFIRMED),
            txn(status = TxnStatus.IGNORED),
            txn(status = TxnStatus.MERGED),
            txn(type = TxnType.INCOME, status = TxnStatus.IGNORED),
        )
        for (t in cases) {
            assertEquals(
                com.autoledger.core.model.ExpenseMath.countsAsExpense(t),
                t.isConsumption(),
                "口径不一致：${t.type}/${t.status}",
            )
        }
    }
}
