package com.autoledger.app.ui.theme

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 收支语义色层的纯 JVM 护栏。
 *
 * 钉死两件事：
 * 1. **判据是 type 不是符号** —— 「`EXPENSE` 存了正数」的历史脏数据必须仍显示为支出绿
 *    （改造前按 `amountMinor < 0` 判色，这类行会被染成收入色）；
 * 2. **色值是对比度校验过的常量** —— 把「浅底用 #116B5B / 深底用 #5FC7AC，收入同理」的结论
 *    钉成断言，防止后人"顺手调回"对比度不达标的旧色（Danger #D95F5F 浅底仅 ≈3.56:1）。
 */
class ToneTest {

    private fun txn(type: TxnType, amountMinor: Long) = LedgerTransaction(
        id = "t1",
        amountMinor = amountMinor,
        occurredAtMillis = 0L,
        type = type,
        counterparty = "某商户",
        sourceId = "manual",
        sourceRef = "manual:t1",
        status = TxnStatus.CONFIRMED,
    )

    @Test
    fun `each transaction type maps to its documented tone`() {
        assertEquals(LedgerTone.EXPENSE, txnTone(txn(TxnType.EXPENSE, -100L)))
        assertEquals(LedgerTone.INCOME, txnTone(txn(TxnType.INCOME, 100L)))
        // 退款是冲抵项、划转不是收支 ⇒ 中性（染成收入红会让用户以为"退款也算收入"）
        assertEquals(LedgerTone.NEUTRAL, txnTone(txn(TxnType.REFUND, 100L)))
        assertEquals(LedgerTone.NEUTRAL, txnTone(txn(TxnType.TRANSFER, -100L)))
    }

    @Test
    fun `tone follows the type not the amount sign`() {
        // 符号与类型不一致的脏数据：仍按 type 着色（改造前会被判成收入色）
        assertEquals(LedgerTone.EXPENSE, txnTone(txn(TxnType.EXPENSE, 100L)), "EXPENSE + 正数仍是支出色")
        assertEquals(LedgerTone.INCOME, txnTone(txn(TxnType.INCOME, -100L)), "INCOME + 负数仍是收入色")
    }

    @Test
    fun `tone hex values are the contrast checked constants`() {
        assertEquals("#116B5B", toneHex(LedgerTone.EXPENSE, dark = false), "浅底支出绿")
        assertEquals("#B3261E", toneHex(LedgerTone.INCOME, dark = false), "浅底收入红（不用 Danger #D95F5F）")
        assertEquals("#5FC7AC", toneHex(LedgerTone.EXPENSE, dark = true), "深底支出绿（不用 PositiveStrong）")
        assertEquals("#F2B8B5", toneHex(LedgerTone.INCOME, dark = true), "深底收入红")
        assertEquals("#163B3D", toneHex(LedgerTone.NEUTRAL, dark = false), "浅底中性 = InkDeep")
        assertEquals("#E4EFE9", toneHex(LedgerTone.NEUTRAL, dark = true), "深底中性")
    }

    @Test
    fun `every tone has a distinct light and dark hex`() {
        val light = LedgerTone.entries.map { toneHex(it, dark = false) }
        val dark = LedgerTone.entries.map { toneHex(it, dark = true) }
        assertEquals(light.size, light.distinct().size, "浅色各档必须可区分")
        assertEquals(dark.size, dark.distinct().size, "深色各档必须可区分")
    }

    @Test
    fun `dark mode must use the night tokens never the light ones`() {
        // v1.1.6 QA 复验 P1：HeroTile / BreakdownTile 曾收裸 Color，调用点直接传浅色常量
        // （ExpenseGreen / IncomeRed），深色模式下对比度掉到 2.0~2.4:1 —— 比改造前更差。
        // 组件改为收 LedgerTone 并在内部调 toneColor 之后，"深色必须换 token"就成了可断言的契约。
        assertEquals("#5FC7AC", toneHex(LedgerTone.EXPENSE, dark = true), "深色支出绿必须用 Night token")
        assertEquals("#116B5B", toneHex(LedgerTone.EXPENSE, dark = false))
        assertEquals("#F2B8B5", toneHex(LedgerTone.INCOME, dark = true), "深色收入红必须用 Night token")
        assertEquals("#B3261E", toneHex(LedgerTone.INCOME, dark = false))

        // 非收支档同理：深色也不能回落成浅色常量
        assertEquals("#B6CCC6", toneHex(LedgerTone.MUTED, dark = true))
        assertEquals("#9FBDE3", toneHex(LedgerTone.INFO, dark = true))
        assertEquals("#6FD6B4", toneHex(LedgerTone.BRAND, dark = true), "深色品牌绿须与支出绿可区分")
        assertEquals("#E4EFE9", toneHex(LedgerTone.NEUTRAL, dark = true))

        // 逐档锁死"深色 ≠ 浅色"，防止将来有人把某个分支写成同一个值
        LedgerTone.entries.forEach { tone ->
            assertNotEquals(
                toneHex(tone, dark = false),
                toneHex(tone, dark = true),
                "${tone.name} 的深浅色相同 ⇒ 深色模式下必然看不清",
            )
        }
    }
}
