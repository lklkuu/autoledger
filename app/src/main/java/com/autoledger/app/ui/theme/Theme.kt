package com.autoledger.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 参考仪表盘用 22px 做大卡片圆角、14px 做控件圆角，这里等比换算到 dp。 */
private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LedgerTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

/**
 * 当前 Compose 子树是否处于「夜间黑皮肤」。
 *
 * 为什么需要它：Material 配色由 [LedgerTheme] 的 `darkTheme` 参数决定，而语义色阶
 * [toneColor] 原先读的是 `isSystemInDarkTheme()` —— **两个真源**。当「App 夜间 + 手机浅色」
 * （或反之）时，底色走一套、语义色走另一套，文字对比度实测掉到 1.25:1，界面不可读。
 * 故把「用哪一个真源」收敛成这一个 CompositionLocal：**[LedgerTheme] 是唯一决策点**。
 */
val LocalLedgerDarkTheme = staticCompositionLocalOf { false }

@Composable
fun LedgerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLedgerDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkLedgerColors else LightLedgerColors,
            shapes = LedgerShapes,
            typography = LedgerTypography,
            content = content,
        )
    }
}

/** 0xAARRGGBB / #RRGGBB -> Color */
fun colorOf(hex: String, fallback: Color = LedgerPalette.Muted): Color = runCatching {
    val normalized = hex.removePrefix("#").let { if (it.length == 6) "FF$it" else it }
    Color(normalized.toLong(16).toInt())
}.getOrDefault(fallback)
