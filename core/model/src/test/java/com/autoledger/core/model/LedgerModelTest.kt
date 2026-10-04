package com.autoledger.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 领域模型默认值与 SPI 兜底值。
 *
 * 这些默认值决定了「一条流水从采集进来那一刻的身份」：
 * 方向、状态、结构版本号、置信度。改错一个，待确认队列与统计口径就全乱了。
 */
class LedgerModelTest {

    private fun txn(
        amountMinor: Long,
        type: TxnType = TxnType.EXPENSE,
    ) = LedgerTransaction(
        id = "t1",
        amountMinor = amountMinor,
        occurredAtMillis = 1_700_000_000_000L,
        type = type,
        sourceId = "notify",
        sourceRef = "0|com.tencent.mm|1",
    )

    @Test
    fun `direction is driven by type not by amount sign`() {
        // EXPENSE / INCOME 的方向由类型决定，金额符号不参与 —— 两者冲突时以类型为准。
        assertEquals(Direction.OUT, txn(-100).direction)
        assertEquals(Direction.OUT, txn(100, TxnType.EXPENSE).direction)
        assertEquals(Direction.IN, txn(100, TxnType.INCOME).direction)
        assertEquals(Direction.IN, txn(-100, TxnType.INCOME).direction)
    }

    @Test
    fun `zero amount no longer forces inflow`() {
        // 回归护栏：金额解析失败时采集端用 0 占位（IngestPipeline 的 `amountMinor = amount ?: 0L`），
        // 而旧实现 `if (amountMinor < 0) OUT else IN` 在 0 时恒判 IN —— 一笔支出会被记成流入。
        assertEquals(Direction.OUT, txn(0, TxnType.EXPENSE).direction)
        assertEquals(Direction.IN, txn(0, TxnType.INCOME).direction)
    }

    @Test
    fun `transfer and refund still follow the amount sign`() {
        // 这两类没有固定方向：转出/转入、退款入账/冲正都可能，故仍看金额符号。
        assertEquals(Direction.OUT, txn(-100, TxnType.TRANSFER).direction)
        assertEquals(Direction.IN, txn(100, TxnType.TRANSFER).direction)
        assertEquals(Direction.OUT, txn(-100, TxnType.REFUND).direction)
        assertEquals(Direction.IN, txn(100, TxnType.REFUND).direction)
    }

    @Test
    fun `bookedAt defaults to occurredAt`() {
        val t = txn(-100)
        assertEquals(t.occurredAtMillis, t.bookedAtMillis)
    }

    @Test
    fun `defaults for status confidence and schema`() {
        val t = txn(-100)
        assertEquals(TxnStatus.CONFIRMED, t.status)
        assertEquals(1f, t.confidence)
        assertEquals(LedgerSchema.CURRENT, t.schemaVersion)
        assertEquals(Money.DEFAULT_CURRENCY, t.currency)
        assertNull(t.rawTextSealed)
        assertNull(t.categoryId)
        assertNull(t.transferGroupId)
        assertEquals("", t.fingerprint)
    }

    @Test
    fun `schema versions are wired to the same constant`() {
        assertEquals(LedgerSchema.BACKUP_VERSION, LedgerSchema.CURRENT)
        // v5 = 消费平台字段（platform_id / platform_confidence / platform_source）落地。
        // v6 = 自定义消费平台表（user_platforms）+ 合并溯源列（transactions.merged_into_id）。
        // v7 = AI 判定配置四列（app_settings.aiEnabled / aiMode / aiEndpoint / aiModel）。
        // 改这个值必须同时提供显式 Migration（见 core:database 的 MIGRATION_5_6 / MIGRATION_6_7），
        // 否则 Room 找不到迁移路径 ⇒ 退回 destructive fallback ⇒ **全库清空**。
        assertEquals(7, LedgerSchema.DATABASE_VERSION)
        assertEquals("ledgerbak", LedgerSchema.BACKUP_EXTENSION)
    }

    @Test
    fun `unresolved classification carries zero confidence`() {
        val r = ClassificationResult.unresolved()
        assertNull(r.categoryId)
        assertEquals(0f, r.confidence)
        assertEquals("未命中规则", r.reason)
    }

    @Test
    fun `none transfer verdict carries zero confidence and NONE kind`() {
        val v = TransferVerdict.none()
        assertEquals(TransferKind.NONE, v.kind)
        assertEquals(0f, v.confidence)
        assertNull(v.matchedAccountId)
    }

    @Test
    fun `raw envelope keeps optional hints nullable`() {
        val e = RawEnvelope(
            envelopeId = "e1",
            sourceId = "sms",
            sourceRef = "sms:42",
            occurredAtMillis = 1L,
            rawText = "body",
        )
        assertNull(e.counterpartyHint)
        assertNull(e.amountHint)
        assertNull(e.packageName)
    }

    @Test
    fun `account identifier hints default empty`() {
        val a = Account(id = "a1", name = "招行", kind = AccountKind.BANK_CARD)
        assertTrue(a.identifierHints.isEmpty())
        assertFalse(a.archived)
    }

    @Test
    fun `transfer kinds excluding NONE are non consumption`() {
        val consumptionBlocking = TransferKind.entries.filter { it != TransferKind.NONE }
        assertEquals(5, consumptionBlocking.size)
        assertTrue(consumptionBlocking.contains(TransferKind.REFUND))
    }

    @Test
    fun `classifier rule learned flag defaults false with zero priority`() {
        val r = ClassifierRule(id = "r1", kind = RuleKind.KEYWORD, pattern = "咖啡", categoryId = "cat_food")
        assertFalse(r.learned)
        assertEquals(0, r.priority)
        assertEquals(0, r.hitCount)
    }
}
