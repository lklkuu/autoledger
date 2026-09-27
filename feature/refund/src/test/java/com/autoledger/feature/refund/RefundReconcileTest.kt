package com.autoledger.feature.refund

import kotlin.test.Test
import com.autoledger.core.model.refund.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 退款回调与状态不一致的对账处理 —— 纯 JVM 单测。 */
class RefundReconcileTest {

    private fun local(status: RefundStatus, amount: Long = 5_000L, attempt: Int = 1) =
        RefundRecord("r1", "R1", amount, status, "k1", 1_700_000_000_000L, attempt)

    private fun callback(status: RefundCallbackStatus, amount: Long = 5_000L) =
        RefundCallback("R1", "k1", status, amount)

    @Test
    fun `callback for an unknown refund needs manual checks`() {
        val r = RefundEngine.reconcile(null, callback(RefundCallbackStatus.SUCCESS))
        assertEquals(ReconcileAction.UNKNOWN_REFUND, r.action)
        assertTrue(r.message.contains("不存在"))
    }

    @Test
    fun `success on an already applied refund stays consistent`() {
        val r = RefundEngine.reconcile(local(RefundStatus.APPLIED), callback(RefundCallbackStatus.SUCCESS))
        assertEquals(ReconcileAction.MARK_APPLIED, r.action)
        assertTrue(r.diff.contains("APPLIED"))
    }

    @Test
    fun `success on a pending refund is补记为已生效`() {
        val r = RefundEngine.reconcile(local(RefundStatus.PENDING), callback(RefundCallbackStatus.SUCCESS))
        assertEquals(ReconcileAction.MARK_APPLIED, r.action)
    }

    @Test
    fun `success with mismatched amount goes to manual review`() {
        val r = RefundEngine.reconcile(local(RefundStatus.PENDING), callback(RefundCallbackStatus.SUCCESS, amount = 4_000L))
        assertEquals(ReconcileAction.MANUAL_REVIEW, r.action)
        assertTrue(r.message.contains("金额不一致"))
    }

    @Test
    fun `success while locally rejected goes to manual review`() {
        val r = RefundEngine.reconcile(local(RefundStatus.REJECTED), callback(RefundCallbackStatus.SUCCESS))
        assertEquals(ReconcileAction.MANUAL_REVIEW, r.action)
    }

    @Test
    fun `failure on an applied refund goes to manual review`() {
        val r = RefundEngine.reconcile(local(RefundStatus.APPLIED), callback(RefundCallbackStatus.FAIL))
        assertEquals(ReconcileAction.MANUAL_REVIEW, r.action)
    }

    @Test
    fun `failure below the retry limit keeps retrying`() {
        val r = RefundEngine.reconcile(local(RefundStatus.PROCESSING, attempt = 1), callback(RefundCallbackStatus.FAIL))
        assertEquals(ReconcileAction.MARK_FAILED, r.action)
        assertTrue(r.message.contains("可重试"))
    }

    @Test
    fun `failure at the retry limit stops retrying`() {
        val r = RefundEngine.reconcile(
            local(RefundStatus.FAILED, attempt = RefundEngine.MAX_ATTEMPTS),
            callback(RefundCallbackStatus.FAIL),
        )
        assertEquals(ReconcileAction.MARK_FAILED, r.action)
        assertTrue(r.message.contains("上限"))
    }

    @Test
    fun `processing on a terminal local state goes to manual review`() {
        val r = RefundEngine.reconcile(local(RefundStatus.APPLIED), callback(RefundCallbackStatus.PROCESSING))
        assertEquals(ReconcileAction.MANUAL_REVIEW, r.action)
    }

    @Test
    fun `processing keeps waiting when locally pending`() {
        val r = RefundEngine.reconcile(local(RefundStatus.PENDING), callback(RefundCallbackStatus.PROCESSING))
        assertEquals(ReconcileAction.KEEP_PENDING, r.action)
    }
}
