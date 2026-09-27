package com.autoledger.core.database.repository

import androidx.room.Room
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.OrderDeductionEntity
import com.autoledger.core.database.OrderEntity
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.refund.DeductionKind
import com.autoledger.core.model.refund.OrderStatus
import com.autoledger.core.model.refund.RefundAllocation
import com.autoledger.core.model.refund.RefundOutcome
import com.autoledger.core.model.refund.RefundPlan
import com.autoledger.core.model.refund.RefundStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 退款落库 —— **真实 Room 库**集成测试（Robolectric，无需真机）。
 *
 * 验证：单事务原子落库（5 处写入）、幂等命中、乐观锁 CAS 冲突、
 * 抵扣累计更新、账目流水生成，以及**嵌套事务**（applyRefund 内再调 repository.upsert）确实可用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RefundRepositoryIntegrationTest {

    private fun open(): LedgerDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), LedgerDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val now = 1_700_000_000_000L

    /** 造一个 100 元订单：余额 60 + 积分 20（200 分）。 */
    private suspend fun seed(db: LedgerDatabase) {
        db.orderDao().upsert(
            OrderEntity(
                id = "o1", orderNo = "NO1", counterparty = "某商户", totalMinor = 10_000L,
                currency = "CNY", occurredAtMillis = now - 1000L, refundDeadlineMillis = null,
                status = OrderStatus.PAID, version = 1, sourceId = "notify", sourceRef = "r1",
                schemaVersion = LedgerSchema.CURRENT,
            )
        )
        db.orderDao().upsertDeductions(
            listOf(
                OrderDeductionEntity("d_balance", "o1", DeductionKind.BALANCE, 6_000L, 0, null, false, false, 0L, 0, LedgerSchema.CURRENT),
                OrderDeductionEntity("d_points", "o1", DeductionKind.POINTS, 2_000L, 200, null, false, false, 0L, 0, LedgerSchema.CURRENT),
            )
        )
    }

    private fun fullPlan() = RefundPlan(
        refundId = "rf1", refundNo = "R1", amountMinor = 10_000L,
        allocations = listOf(
            RefundAllocation("d_balance", DeductionKind.BALANCE, 6_000L, 0, RefundOutcome.RETURNED),
            RefundAllocation("d_points", DeductionKind.POINTS, 2_000L, 200, RefundOutcome.RETURNED),
        ),
        nextOrderStatus = OrderStatus.REFUNDED, newVersion = 2, idempotencyKey = "k1",
    )

    @Test
    fun `applyRefund writes all five places atomically`() = runBlocking {
        val db = open()
        try {
            seed(db)
            val repo = RefundRepository(db, RoomLedgerRepository(db))
            val state = repo.loadState("o1")!!
            assertEquals(10_000L, state.totalMinor)
            assertEquals(2, state.deductions.size)
            assertEquals(1, state.version)

            val result = repo.applyRefund(fullPlan(), state, now, "notify", "ref1", "fp1")
            assertIs<RefundRepository.ApplyResult.Applied>(result)

            // ① 退款单
            val refund = repo.findRefundByNo("R1")!!
            assertEquals(RefundStatus.APPLIED, refund.status)
            assertEquals(10_000L, refund.amountMinor)
            // ② 分摊明细
            assertEquals(2, repo.allocationsOf("rf1").size)
            // ③ 抵扣累计
            val deductions = db.orderDao().deductionsOf("o1").associateBy { it.id }
            assertEquals(6_000L, deductions.getValue("d_balance").reversedAmountMinor)
            assertEquals(200, deductions.getValue("d_points").reversedQuantity)
            // ④ 订单状态 + 版本
            val order = db.orderDao().findById("o1")!!
            assertEquals(OrderStatus.REFUNDED, order.status)
            assertEquals(2, order.version)
            // ⑤ 账目流水（REFUND、金额为正）
            val txns = db.transactionDao().listAll().filter { it.type == TxnType.REFUND }
            assertEquals(1, txns.size)
            assertEquals(10_000L, txns.first().amountMinor)
            assertEquals("o1", txns.first().orderId)
            assertEquals("rf1", txns.first().refundId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `second apply with the same idempotency key is not applied twice`() = runBlocking {
        val db = open()
        try {
            seed(db)
            val repo = RefundRepository(db, RoomLedgerRepository(db))
            val state = repo.loadState("o1")!!
            assertIs<RefundRepository.ApplyResult.Applied>(repo.applyRefund(fullPlan(), state, now, "notify", "ref1", "fp1"))

            // 同一 key 再落一次 → 幂等命中
            val again = repo.applyRefund(fullPlan(), state, now, "notify", "ref1", "fp1")
            assertIs<RefundRepository.ApplyResult.Duplicate>(again)

            // 抵扣累计没有被重复叠加
            assertEquals(6_000L, db.orderDao().deductionsOf("o1").first { it.id == "d_balance" }.reversedAmountMinor)
            assertEquals(1, db.transactionDao().listAll().count { it.type == TxnType.REFUND })
        } finally {
            db.close()
        }
    }

    @Test
    fun `stale version causes a CAS conflict and rolls back everything`() = runBlocking {
        val db = open()
        try {
            seed(db)
            val repo = RefundRepository(db, RoomLedgerRepository(db))
            val stale = repo.loadState("o1")!!   // version = 1
            // 模拟并发：另一笔先把版本推进到 2
            db.orderDao().casStatus("o1", OrderStatus.REFUNDING, 2, 1)

            var threw = false
            try {
                repo.applyRefund(fullPlan(), stale, now, "notify", "ref1", "fp1")
            } catch (e: RefundRepository.ConcurrentRefundException) {
                threw = true
            }
            assertTrue(threw, "陈旧版本必须抛并发冲突")

            // 整体回滚：没有退款单、没有流水
            assertFalse(repo.refundsOf("o1").isNotEmpty(), "冲突时不应留下退款单")
            assertEquals(0, db.transactionDao().listAll().count { it.type == TxnType.REFUND })
        } finally {
            db.close()
        }
    }

    @Test
    fun `partial refund keeps the order refunding and accumulates reversals`() = runBlocking {
        val db = open()
        try {
            seed(db)
            val repo = RefundRepository(db, RoomLedgerRepository(db))
            val state = repo.loadState("o1")!!
            // 部分退 5000：余额 3000 + 积分 1000/100
            val partial = RefundPlan(
                refundId = "rf_p", refundNo = "RP", amountMinor = 5_000L,
                allocations = listOf(
                    RefundAllocation("d_balance", DeductionKind.BALANCE, 3_000L, 0, RefundOutcome.RETURNED),
                    RefundAllocation("d_points", DeductionKind.POINTS, 1_000L, 100, RefundOutcome.RETURNED),
                ),
                nextOrderStatus = OrderStatus.REFUNDING, newVersion = 2, idempotencyKey = "kp",
            )
            assertIs<RefundRepository.ApplyResult.Applied>(repo.applyRefund(partial, state, now, "notify", "rp", "fpp"))

            val order = db.orderDao().findById("o1")!!
            assertEquals(OrderStatus.REFUNDING, order.status, "部分退款后订单应为退款中")
            val deductions = db.orderDao().deductionsOf("o1").associateBy { it.id }
            assertEquals(3_000L, deductions.getValue("d_balance").reversedAmountMinor)
            assertEquals(100, deductions.getValue("d_points").reversedQuantity)
            // 再读一次聚合：剩余可退应减少（供下一笔退款使用）
            val next = repo.loadState("o1")!!
            assertEquals(3_000L, next.deductions.first { it.id == "d_balance" }.remainingAmountMinor)
        } finally {
            db.close()
        }
    }
}
