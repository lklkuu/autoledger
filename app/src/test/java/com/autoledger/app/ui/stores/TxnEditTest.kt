package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ------------------------------------------------------------ 合并写入（applyFullEdit）

    @Test
    fun `full edit writes merchant, platform, amount and date in one shot`() {
        // 回归护栏：拆成「改商户」+「改金额」两次写会互相覆盖（后写的整行覆盖先写的），
        // 用户改两个字段最后只剩一个生效。这里断言一次写入后所有字段都在。
        val edited = applyFullEdit(
            txn = txn(counterparty = "旧商户", fingerprint = "old"),
            counterparty = "  星巴克  ",
            note = "午餐",
            platformId = "meituan",
            amountMinor = 4_500L,
            occurredAtMillis = 1_650_000_000_000L,
            fingerprintOf = { "fp" },
        )
        assertEquals("星巴克", edited.counterparty, "商户要生效")
        assertEquals("午餐", edited.note)
        assertEquals("meituan", edited.platformId, "平台要生效")
        assertEquals(-4_500L, edited.amountMinor, "金额要生效且保持支出符号")
        assertEquals(1_650_000_000_000L, edited.occurredAtMillis, "日期要生效")
        assertEquals("fp", edited.fingerprint, "指纹要重算")
    }

    @Test
    fun `full edit with null amount or date leaves them untouched`() {
        val edited = applyFullEdit(txn(), "星巴克", null, "alipay", null, null) { "fp" }
        assertEquals(-1_350L, edited.amountMinor)
        assertEquals(1_700_000_000_000L, edited.occurredAtMillis)
    }

    @Test
    fun `full edit survives Long MIN_VALUE amount instead of flipping the sign`() {
        // abs(Long.MIN_VALUE) 会溢出成负数，不兜住的话支出会被写成收入。
        val extreme = txn().copy(amountMinor = Long.MIN_VALUE)
        val edited = applyFullEdit(extreme, "星巴克", null, "wechat", Long.MIN_VALUE, null) { "fp" }
        assertTrue(edited.amountMinor < 0, "支出必须仍是负数，实际 ${edited.amountMinor}")
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

    // ------------------------------------------------------------ 收支类型切换（v1.1.6）

    /** 指纹算法注入：把"是否重算"变成可观测 —— 真实算法含带符号金额，这里用可区分的假算法。 */
    private fun fp(t: LedgerTransaction): String = "fp:${t.type.name}:${t.amountMinor}"

    @Test
    fun `switching type flips the sign and always recomputes the fingerprint`() {
        val expense = txn().copy(type = TxnType.EXPENSE, amountMinor = -1_350L, fingerprint = "old")
        val toIncome = applyTypeSwitch(expense, TxnType.INCOME, ::fp)
        assertEquals(TxnType.INCOME, toIncome.type)
        assertEquals(1_350L, toIncome.amountMinor, "支出 → 收入：金额翻正")
        assertEquals("fp:INCOME:1350", toIncome.fingerprint, "指纹必须按新符号重算（否则跨渠道去重匹配不上）")

        val income = txn().copy(type = TxnType.INCOME, amountMinor = 1_350L, fingerprint = "old")
        val toExpense = applyTypeSwitch(income, TxnType.EXPENSE, ::fp)
        assertEquals(-1_350L, toExpense.amountMinor, "收入 → 支出：金额翻负")
        assertEquals("fp:EXPENSE:-1350", toExpense.fingerprint)

        // 两次切换必须都产生变化的指纹（不是只改类型不改指纹）
        assertTrue(expense.fingerprint != toIncome.fingerprint)
        assertTrue(income.fingerprint != toExpense.fingerprint)
    }

    @Test
    fun `full edit flips the sign when only the type changes`() {
        val expense = txn().copy(type = TxnType.EXPENSE, amountMinor = -1_350L)
        val edited = applyFullEdit(
            txn = expense,
            counterparty = "某商户",
            note = null,
            platformId = "",
            amountMinor = null, // 用户没动金额
            occurredAtMillis = null,
            type = TxnType.INCOME,
            fingerprintOf = ::fp,
        )
        assertEquals(TxnType.INCOME, edited.type)
        assertEquals(1_350L, edited.amountMinor, "只改类型不改金额时也必须翻正，否则留下 EXPENSE+正数 之外的错色脏行")
    }

    @Test
    fun `full edit without a type argument behaves exactly as before`() {
        // 回归护栏：老调用点（不传 type）必须与改造前逐位相同 —— 符号沿用原值、类型不变
        val expense = txn().copy(type = TxnType.EXPENSE, amountMinor = -1_350L)
        val edited = applyFullEdit(
            txn = expense,
            counterparty = " 某商户 ",
            note = " 备注 ",
            platformId = "wechat",
            amountMinor = 2_000L,
            occurredAtMillis = 1_700_000_100_000L,
            fingerprintOf = ::fp,
        )
        assertEquals(TxnType.EXPENSE, edited.type)
        assertEquals(-2_000L, edited.amountMinor, "不传 type ⇒ 沿用原符号（负）")
        assertEquals("某商户", edited.counterparty)
        assertEquals("备注", edited.note)
        assertEquals(1_700_000_100_000L, edited.occurredAtMillis)
    }

    @Test
    fun `refund keeps positive and transfer keeps its sign when the amount is edited`() {
        // 类型未变 ⇒ 沿用原符号。退款恒正、内部划转可正可负，绝不能被"顺手翻正/翻负"
        val refund = txn().copy(type = TxnType.REFUND, amountMinor = 5_000L)
        val editedRefund = applyFullEdit(
            txn = refund, counterparty = "", note = null, platformId = "",
            amountMinor = 6_000L, occurredAtMillis = null, fingerprintOf = ::fp,
        )
        assertEquals(6_000L, editedRefund.amountMinor, "退款改金额后仍为正")

        val transferOut = txn().copy(type = TxnType.TRANSFER, amountMinor = -5_000L)
        val editedTransfer = applyFullEdit(
            txn = transferOut, counterparty = "", note = null, platformId = "",
            amountMinor = 7_000L, occurredAtMillis = null, fingerprintOf = ::fp,
        )
        assertEquals(-7_000L, editedTransfer.amountMinor, "划出（负）改金额后仍为负")
    }

    @Test
    fun `type switch is blocked for merged refund transfer order refund link and merge chain`() {
        val plain = txn().copy(status = TxnStatus.CONFIRMED)
        assertTrue(canSwitchType(plain), "普通支出可切换")

        val blocked = listOf(
            plain.copy(status = TxnStatus.MERGED) to "已并入",
            plain.copy(type = TxnType.REFUND) to "退款",
            plain.copy(type = TxnType.TRANSFER) to "划转",
            plain.copy(orderId = "o1") to "已关联订单",
            plain.copy(refundId = "r1") to "已关联退款",
            plain.copy(transferGroupId = "g1") to "已配对划转",
        )
        blocked.forEach { (t, label) ->
            assertTrue(!canSwitchType(t), "$label 必须禁止切换类型")
            assertTrue(
                typeSwitchBlockReason(t) != null,
                "$label 必须给出原因（沿用「写明原因而不是默默忽略」的惯例）",
            )
        }
        // 合并链主记录：吸收条数 > 0 时禁止
        assertTrue(!canSwitchType(plain, absorbedCount = 1), "已合并 N 条的主记录不得改类型")
        assertTrue(canSwitchType(plain, absorbedCount = 0), "没有吸收记录时可切换")
    }

    @Test
    fun `nextType only flips between expense and income`() {
        assertEquals(TxnType.INCOME, nextType(txn().copy(type = TxnType.EXPENSE)))
        assertEquals(TxnType.EXPENSE, nextType(txn().copy(type = TxnType.INCOME)))
    }

    @Test
    fun `zero and Long MIN amounts do not blow up`() {
        val zero = txn().copy(amountMinor = 0L)
        assertEquals(0L, applyTypeSwitch(zero, TxnType.INCOME, ::fp).amountMinor)

        // Long.MIN_VALUE 取绝对值会溢出 ⇒ safeAbs 夹到 MAX_VALUE，落差 1 分，不崩
        val min = txn().copy(amountMinor = Long.MIN_VALUE)
        val flipped = applyTypeSwitch(min, TxnType.INCOME, ::fp)
        assertEquals(Long.MAX_VALUE, flipped.amountMinor, "MIN 饱和夹取，不得回绕成负数")

        val edited = applyFullEdit(
            txn = min, counterparty = "", note = null, platformId = "",
            amountMinor = Long.MIN_VALUE, occurredAtMillis = null, fingerprintOf = ::fp,
        )
        assertEquals(-Long.MAX_VALUE, edited.amountMinor, "支出方向：夹取后取负")
    }
}
