package com.autoledger.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 奶油薄荷手账色板 —— 逐项取自参考仪表盘的 CSS 变量，保证两端观感一致。
 */
object LedgerPalette {
    val InkDeep = Color(0xFF163B3D)
    val Ink = Color(0xFF31595A)
    val Muted = Color(0xFF708786)
    val Positive = Color(0xFF16856F)
    val PositiveStrong = Color(0xFF116B5B)
    val PositivePale = Color(0xFFDFF4EA)
    val Danger = Color(0xFFD95F5F)
    val DangerPale = Color(0xFFFFE7E1)
    val Warning = Color(0xFF9A6A18)
    val WarningPale = Color(0xFFFFF2C7)
    val Paper = Color(0xFFF8F5ED)
    val PaperNight = Color(0xFF101E20)
    val Surface = Color(0xFFFFFDF7)
    val SurfaceSoft = Color(0xFFEDF7F0)
    val SurfaceRaised = Color(0xFFFFFAF0)
    val SurfaceNight = Color(0xFF16292B)
    val Line = Color(0xFFD7DED5)
    val LineStrong = Color(0xFFB9CDC5)
    val Blue = Color(0xFF5C88B8)
    val Sun = Color(0xFFF6C95F)
    val Purple = Color(0xFF9B6AD0)

    // ---------------------------------------------------------------- 收支语义色（v1.1.6 新增）
    //
    // 为什么是「加法不是改法」：[Positive] 同时承担「品牌主色/按钮」「成功态」「支出金额」三种语义，
    // 被 20+ 处主按钮使用；[Danger] 是 error 语义（红=负面状态），也不是「支出色」。
    // 直接改这两个 token 会把全站按钮一起染成支出色/收入色。
    // 故**一格不动**上面所有旧 token，只新增下面四个专用于收支语义的色值。
    //
    // 取值依据是对比度（底色不是纯白：浅色卡 #FFFDF7 / 深色卡 #16292B）：
    // - 支出绿浅色 #116B5B 在 #FFFDF7 上 ≈ 5.9:1（≥4.5:1，WCAG AA 正文级）；
    //   深色用 #5FC7AC（在 #16292B 上 ≈ 7.4:1）——旧的 PositiveStrong #116B5B 在深底上仅 2.4:1，不可用。
    // - 收入红浅色用 #B3261E（≈ 6.4:1）——**不用** Danger #D95F5F（浅底上仅 ≈3.56:1，低于 4.5:1）；
    //   深色用 #F2B8B5。
    val ExpenseGreen = Color(0xFF116B5B)
    val ExpenseGreenNight = Color(0xFF5FC7AC)
    val IncomeRed = Color(0xFFB3261E)
    val IncomeRedNight = Color(0xFFF2B8B5)
}

val LightLedgerColors = lightColorScheme(
    primary = LedgerPalette.Positive,
    onPrimary = LedgerPalette.Surface,
    primaryContainer = LedgerPalette.PositivePale,
    onPrimaryContainer = LedgerPalette.PositiveStrong,
    secondary = LedgerPalette.Blue,
    onSecondary = LedgerPalette.Surface,
    error = LedgerPalette.Danger,
    onError = LedgerPalette.Surface,
    background = LedgerPalette.Paper,
    onBackground = LedgerPalette.InkDeep,
    surface = LedgerPalette.Surface,
    onSurface = LedgerPalette.InkDeep,
    surfaceVariant = LedgerPalette.SurfaceSoft,
    onSurfaceVariant = LedgerPalette.Ink,
    outline = LedgerPalette.Line,
    outlineVariant = LedgerPalette.LineStrong,
)

val DarkLedgerColors = darkColorScheme(
    primary = Color(0xFF5FC7AC),
    onPrimary = Color(0xFF08201D),
    primaryContainer = Color(0xFF123933),
    onPrimaryContainer = Color(0xFFB7E8D8),
    secondary = Color(0xFF9FBDE3),
    background = LedgerPalette.PaperNight,
    onBackground = Color(0xFFE4EFE9),
    surface = LedgerPalette.SurfaceNight,
    onSurface = Color(0xFFE4EFE9),
    surfaceVariant = Color(0xFF1E3638),
    onSurfaceVariant = Color(0xFFB6CCC6),
    outline = Color(0xFF2F4B4D),
)
