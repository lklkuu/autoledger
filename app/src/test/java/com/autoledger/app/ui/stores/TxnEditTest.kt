package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformSource
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

    // ------------------------------------------------------------ 消费平台（用户手选 = 权威值）

    @Test
    fun `changing the platform marks it USER and raises confidence`() {
        val original = txn(counterparty = "美团外卖").copy(
            platformId = "unknown",
            platformConfidence = 0f,
            platformSource = PlatformSource.AUTO,
        )
        val edited = applyTxnEdit(original, "美团外卖", null, platformId = "meituan", fingerprintOf = { "fp" })

        assertEquals("meituan", edited.platformId)
        assertEquals(PlatformSource.USER, edited.platformSource, "用户改过 ⇒ 权威标记，自动流程不得覆盖")
        assertEquals(1f, edited.platformConfidence)
    }

    @Test
    fun `keeping the platform leaves its source untouched`() {
        val original = txn(counterparty = "美团外卖").copy(
            platformId = "meituan",
            platformConfidence = 0.6f,
            platformSource = PlatformSource.AUTO,
        )
        val edited = applyTxnEdit(original, "美团外卖", null, platformId = "meituan", fingerprintOf = { "fp" })

        assertEquals(PlatformSource.AUTO, edited.platformSource, "没改平台就不该被标成 USER")
        assertEquals(0.6f, edited.platformConfidence)
    }

    // ------------------------------------------------------------ 金额 / 日期编辑

    @Test
    fun `amount edit recomputes the fingerprint`() {
        // 金额参与指纹材料，不重算的话这笔会带着旧金额的指纹留在库里，跨渠道去重再也匹配不上。
        var seen: LedgerTransaction? = null
        val edited = applyAmountAndDateEdit(
            txn(fingerprint = "stale").copy(amountMinor = -1_350L),
            amountMinor = 9_900L,
            occurredAtMillis = 1_700_000_000_000L,
            fingerprintOf = { t -> seen = t; "fp:${t.amountMinor}" },
        )
        assertEquals("fp:-9900", edited.fingerprint)
        assertEquals(-9_900L, seen?.amountMinor, "指纹必须基于编辑后的金额计算")
    }

    @Test
    fun `amount edit keeps the sign so an expense stays an expense`() {
        // 「改个金额把一笔支出变成收入」属于惊吓型行为 —— 方向由类型决定，不是金额输入的副产品。
        val expense = txn().copy(amountMinor = -1_350L, type = TxnType.EXPENSE)
        val edited = applyAmountAndDateEdit(expense, 9_900L, 1L, fingerprintOf = { "fp" })
        assertEquals(-9_900L, edited.amountMinor, "支出改完仍是负数")
        assertEquals(TxnType.EXPENSE, edited.type, "类型不随金额变化")
    }

    @Test
    fun `amount edit keeps the sign so income stays income`() {
        val income = txn().copy(amountMinor = 5_000L, type = TxnType.INCOME)
        val edited = applyAmountAndDateEdit(income, 8_000L, 1L, fingerprintOf = { "fp" })
        assertEquals(8_000L, edited.amountMinor, "收入改完仍是正数")
        assertEquals(TxnType.INCOME, edited.type)
    }

    @Test
    fun `date edit moves occurredAt but keeps bookedAt`() {
        // bookedAt 是「这条记录何时进的账」，不是消费发生时间，改日期不该动它。
        val original = txn().copy(occurredAtMillis = 1_700_000_000_000L, bookedAtMillis = 1_600_000_000_000L)
        val edited = applyAmountAndDateEdit(original, 1_350L, 1_800_000_000_000L, fingerprintOf = { "fp" })
        assertEquals(1_800_000_000_000L, edited.occurredAtMillis)
        assertEquals(1_600_000_000_000L, edited.bookedAtMillis)
    }

    @Test
    fun `amount edit leaves category and confidence alone`() {
        // 金额变了分类可能不再精准，但 confidence 没有任何消费方会据它触发「请重选分类」，
        // 悄悄调低只会制造「数据改了、界面没反应」的假象；重选与否交给用户显式决定。
        val original = txn().copy(categoryId = "food", confidence = 0.9f)
        val edited = applyAmountAndDateEdit(original, 2_000L, 1L, fingerprintOf = { "fp" })
        assertEquals("food", edited.categoryId)
        assertEquals(0.9f, edited.confidence)
    }

    // ------------------------------------------------------------ 可编辑性约束

    @Test
    fun `merged transactions cannot be edited at all`() {
        val merged = txn().copy(status = TxnStatus.MERGED)
        assertEquals(false, TxnEditRules.canEdit(merged))
        assertEquals("该笔已并入其他流水，不可修改", TxnEditRules.blockReason(merged))
    }

    @Test
    fun `transactions tied to an order refund or transfer keep amount and date locked`() {
        val linked = listOf(
            txn().copy(orderId = "o1"),
            txn().copy(refundId = "r1"),
            txn().copy(transferGroupId = "g1"),
        )
        linked.forEach { t ->
            assertEquals(true, TxnEditRules.canEdit(t), "商户/备注/分类/标签仍可改")
            assertEquals(false, TxnEditRules.canEditAmountAndDate(t), "金额与日期必须锁住，否则破坏抵扣对账与配对")
            assertEquals(
                "已与订单 / 退款 / 内部划转关联，金额与日期不可修改",
                TxnEditRules.blockReason(t),
            )
        }
    }

    @Test
    fun `an ordinary confirmed transaction is fully editable`() {
        val plain = txn().copy(status = TxnStatus.CONFIRMED)
        assertEquals(true, TxnEditRules.canEdit(plain))
        assertEquals(true, TxnEditRules.canEditAmountAndDate(plain))
        assertEquals(null, TxnEditRules.blockReason(plain))
    }
}
