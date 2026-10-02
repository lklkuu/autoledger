package com.autoledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType

/**
 * 收支**语义色阶**（v1.1.6）：把「这笔钱是支出还是收入」从「金额符号」与「品牌主色」里独立出来。
 *
 * ## 为什么要独立成一层
 *
 * 改造前金额着色有两个问题：
 * 1. **判据错位**：流水行按 `amountMinor < 0` 判色，而不是按 `type` 判 ——
 *    于是历史脏数据（`EXPENSE` 却存了正数）会被染成收入色，且「只改类型不改金额」的编辑会留下
 *    `EXPENSE + 正数` 这种自相矛盾的行。改由 [txnTone] 判 type 后，这类行的显示**自愈**。
 * 2. **语义混用**：`Positive` 同时是「品牌主色/按钮」「成功态」「支出金额」，被 20+ 处主按钮使用；
 *    `Danger` 是 error（红 = 负面状态）而不是「支出色」。直接改这两个 token 会把按钮一起染掉。
 *    故本层**只新取值**（见 [LedgerPalette.ExpenseGreen] 等四个新 token），旧 token 一格不动。
 *
 * ## REFUND / TRANSFER 为什么是 NEUTRAL
 *
 * - **退款（REFUND）在本项目里是「冲抵支出」**，不是收入 —— 唯一真源见 `ExpenseMath` 文件头 KDoc：
 *   「净支出 = 毛支出 − 退款」。若把退款染成收入红，用户会以为「退款也算一笔收入」，
 *   看到"收入变多"却找不到对应入账，认知被带偏。退款用中性色，语义交给「退款」文案（见无障碍副标题）。
 * - **内部划转（TRANSFER）**是自转，不是收支（`TransferMath` 口径），同样中性。
 */
enum class LedgerTone { EXPENSE, INCOME, NEUTRAL }

/**
 * 语义色阶 → 十六进制色值（纯函数，可 JVM 单测，不依赖 Compose）。
 *
 * 色值取自 [LedgerPalette] 里新增的四个收支语义 token，此处写十六进制是为了让判定逻辑可单测。
 * 深浅两套都满足 WCAG AA 正文对比度（≥4.5:1），底色分别为浅色卡 `#FFFDF7` 与深色卡 `#16292B`。
 */
fun toneHex(tone: LedgerTone, dark: Boolean): String = when (tone) {
    LedgerTone.EXPENSE -> if (dark) "#5FC7AC" else "#116B5B"
    LedgerTone.INCOME -> if (dark) "#F2B8B5" else "#B3261E"
    LedgerTone.NEUTRAL -> if (dark) "#E4EFE9" else "#163B3D"
}

/**
 * 流水 → 语义色（v1.1.6 的核心改动点）。
 *
 * ⚠️ 判据是 [LedgerTransaction.type]，**不是金额符号**：符号可由编辑、导入、脏数据与符号约定
 * 决定，而 `type` 才是「这笔钱是支出还是收入」的业务事实。
 */
fun txnTone(txn: LedgerTransaction): LedgerTone = when (txn.type) {
    TxnType.EXPENSE -> LedgerTone.EXPENSE
    TxnType.INCOME -> LedgerTone.INCOME
    // 退款是冲抵项、划转不是收支 —— 理由见本文件 KDoc
    TxnType.REFUND, TxnType.TRANSFER -> LedgerTone.NEUTRAL
}

/** 语义色阶 → 实际颜色（跟随系统深浅色）。 */
@Composable
fun toneColor(tone: LedgerTone): Color = colorOf(toneHex(tone, isSystemInDarkTheme()))
