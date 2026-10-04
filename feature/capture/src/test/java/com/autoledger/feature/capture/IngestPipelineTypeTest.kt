package com.autoledger.feature.capture

import com.autoledger.core.model.Direction
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

    // ---------------- 方向提示贯通到判定链（本次修复的核心：金额缺失时不再记反） ----------------

    /** A1：金额缺失但方向已知为流入 ⇒ 收入。**本次修复的核心断言**。 */
    @Test
    fun `missing amount with an incoming direction is income`() {
        assertEquals(TxnType.INCOME, resolveInitialType(null, null, Direction.IN))
    }

    /** A2：金额缺失且方向为流出 ⇒ 支出。 */
    @Test
    fun `missing amount with an outgoing direction is expense`() {
        assertEquals(TxnType.EXPENSE, resolveInitialType(null, null, Direction.OUT))
    }

    /** A4：金额存在时**金额符号优先**，方向不得推翻金额（-500 分且方向为流入 ⇒ 仍是支出）。 */
    @Test
    fun `a present amount overrides the direction hint`() {
        assertEquals(
            TxnType.EXPENSE,
            resolveInitialType(null, -500L, Direction.IN),
            "金额存在时一律以金额符号为准，方向不得推翻金额",
        )
    }

    /** A5：显式类型仍**最高优先**，方向不得覆盖（REFUND 即便方向为流入仍是退款）。 */
    @Test
    fun `explicit type still wins over the direction hint`() {
        assertEquals(
            TxnType.REFUND,
            resolveInitialType(TxnType.REFUND, null, Direction.IN),
            "explicitType 优先级最高，方向只回答流入/流出",
        )
    }

    /** A4 补充：金额为 0 时按金额判成收入（等价于原 `amount >= 0 → INCOME` 分支）。 */
    @Test
    fun `a zero amount is income and the direction does not override it`() {
        assertEquals(TxnType.INCOME, resolveInitialType(null, 0L, Direction.OUT))
    }
}
