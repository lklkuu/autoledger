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
