package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 修正流水（改商户名 / 备注）的纯 JVM 单测（用户反馈问题 5）。
 *
 * 覆盖 [applyTxnEdit] 的去空白归一与**指纹重算**：补齐此前缺失的商户名后，
 * 去重指纹必须重算，否则跨渠道去重会失效。
 */
class TxnEditTest {

    private fun txn(
        counterparty: String = "",
        note: String? = null,
        fingerprint: String = "old",
    ) = LedgerTransaction(
        id = "t1",
        amountMinor = -1_350L,
        occurredAtMillis = 1_700_000_000_000L,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        note = note,
        sourceId = "notify",
        sourceRef = "notify:t1",
        fingerprint = fingerprint,
        status = TxnStatus.CONFIRMED,
    )

    @Test
    fun `fills a missing counterparty and trims whitespace`() {
        val edited = applyTxnEdit(txn(counterparty = ""), "  星巴克  ", " 拿铁 ", fingerprintOf = { "fp" })
        assertEquals("星巴克", edited.counterparty)
        assertEquals("拿铁", edited.note)
        // 其余字段保持不变
        assertEquals("t1", edited.id)
        assertEquals(-1_350L, edited.amountMinor)
        assertEquals(TxnType.EXPENSE, edited.type)
    }

    @Test
    fun `blank note is normalized to null`() {
        val edited = applyTxnEdit(txn(counterparty = "星巴克", note = "旧备注"), "星巴克", "   ", fingerprintOf = { "fp" })
        assertNull(edited.note)
    }

    @Test
    fun `null note stays null`() {
        val edited = applyTxnEdit(txn(), "星巴克", null, fingerprintOf = { "fp" })
        assertNull(edited.note)
    }

    @Test
    fun `fingerprint is recomputed from the edited transaction`() {
        var seen: LedgerTransaction? = null
        val edited = applyTxnEdit(
            txn(counterparty = "", fingerprint = "stale"),
            "星巴克",
            null,
            fingerprintOf = { t -> seen = t; "fp:${t.counterparty}" },
        )
        assertEquals("fp:星巴克", edited.fingerprint, "补齐商户名后必须重算指纹")
        assertEquals("星巴克", seen?.counterparty, "指纹应基于编辑后的流水计算")
    }
}
