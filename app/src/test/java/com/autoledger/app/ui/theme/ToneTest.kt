package com.autoledger.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        // v1.1.11：浅色侧三档原先低于 WCAG AA 4.5:1，此处锁死提亮后的新常量，防后人"顺手调回"。
        assertEquals("#127A66", toneHex(LedgerTone.BRAND, dark = false), "浅底品牌绿（原 #16856F 低于 4.5:1）")
        assertEquals("#5A706E", toneHex(LedgerTone.MUTED, dark = false), "浅底次要灰绿（原 #708786 低于 4.5:1）")
        assertEquals("#45739F", toneHex(LedgerTone.INFO, dark = false), "浅底信息蓝（原 #5C88B8 低于 4.5:1）")
    }

    /**
     * WCAG 2.x 相对亮度对比度。
     *
     * `Color.red/green/blue` 已是 0..1 的 sRGB 分量（Compose 内部按 sRGB 存储），
     * 所以这里直接按 WCAG 公式做线性化，不需要再做 /255。
     */
    private fun contrastRatio(a: Color, b: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }

        fun luminance(c: Color): Double =
            0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * **对比度契约**：每个语义档位在它**实际被画上去的那个 surface** 上都必须 ≥4.5:1。
     *
     * 为什么上面那些断言不够：它们只证明「深浅两套值不同」，**证明不了屏幕上用的是哪一套**。
     * 2026-10-11 的线上缺陷正是这类接线错误 —— 色值全对，但夜间皮肤下语义色走了
     * `isSystemInDarkTheme()` 的浅色分支，深色值被画在浅底 / 浅色值被画在深底，
     * 实测最坏 1.16:1，界面不可读，而当时全部单测是绿的。
     *
     * 这条把「色值」与「它所在的 surface」绑在一起：接线再错位，总有一侧跌破 4.5:1。
     * `colorOf` 解析失败会回落到 `LedgerPalette.Muted`(#708786)，浅底上仅 3.75:1 ⇒ 同样会被抓出来。
     */
    @Test
    fun `every tone clears WCAG AA against the surface it is actually drawn on`() {
        LedgerTone.entries.forEach { tone ->
            val lightHex = toneHex(tone, dark = false)
            val lightRatio = contrastRatio(colorOf(lightHex), LightLedgerColors.surface)
            assertTrue(
                lightRatio >= 4.5,
                "${tone.name} 浅色值 $lightHex 对 LightLedgerColors.surface 只有 " +
                    "${"%.2f".format(lightRatio)}:1，低于 WCAG AA 4.5:1",
            )

            val darkHex = toneHex(tone, dark = true)
            val darkRatio = contrastRatio(colorOf(darkHex), DarkLedgerColors.surface)
            assertTrue(
                darkRatio >= 4.5,
                "${tone.name} 深色值 $darkHex 对 DarkLedgerColors.surface 只有 " +
                    "${"%.2f".format(darkRatio)}:1，低于 WCAG AA 4.5:1",
            )
        }
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
