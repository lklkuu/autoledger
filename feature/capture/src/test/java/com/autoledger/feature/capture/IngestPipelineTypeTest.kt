package com.autoledger.feature.capture

import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `resolveInitialType` 的纯 JVM 单测 —— 「退款类型显式下发」这条契约的**关键护栏**。
 *
 * 背景：退款解析结果的金额是**正数**（Direction.IN 取 abs），若初始类型只按金额正负推断，
 * 退款会先落成 INCOME。此前仅仅依赖 TransferDetector 靠"退款/退回"等关键词二次命中覆盖成
 * REFUND，是一条**隐式契约**：一旦出现不含这些词的退款文案（如"返现"），退款会被静默记成收入，
 * 且没有任何测试能发现。显式类型优先即修复该隐患。
 */
class IngestPipelineTypeTest {

    @Test
    fun `explicit refund type wins over a positive amount`() {
        // ★ 核心：正数金额不得把显式退款误导成 INCOME。
        assertEquals(TxnType.REFUND, resolveInitialType(TxnType.REFUND, 3_000L))
    }

    @Test
    fun `positive amount without an explicit type is income`() {
        assertEquals(TxnType.INCOME, resolveInitialType(null, 3_000L))
    }

    @Test
    fun `negative amount without an explicit type is expense`() {
        assertEquals(TxnType.EXPENSE, resolveInitialType(null, -3_000L))
    }

    @Test
    fun `missing amount falls back to expense`() {
        assertEquals(TxnType.EXPENSE, resolveInitialType(null, null))
    }

    @Test
    fun `explicit expense type wins over a negative amount`() {
        assertEquals(TxnType.EXPENSE, resolveInitialType(TxnType.EXPENSE, -3_000L))
    }
}
