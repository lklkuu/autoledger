package com.autoledger.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
    // v1.1.11 起夜间背景改纯黑 #000000，本 token 暂不再被 DarkLedgerColors 使用（保留以备复用）。
    val PaperNight = Color(0xFF101E20)
    val Surface = Color(0xFFFFFDF7)
    val SurfaceSoft = Color(0xFFEDF7F0)
    val SurfaceRaised = Color(0xFFFFFAF0)
    // v1.1.11 起夜间卡片面改纯黑 #000000，本 token 暂不再被 DarkLedgerColors 使用（保留以备复用）。
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
    // 取值依据是对比度（底色不是纯白：浅色卡 #FFFDF7；深色底 2026-10-11 起改**纯黑 #000000**，
    // 括注里原本写的 #16292B 是旧夜间卡底，值未变但对比度已随底色提高）：
    // - 支出绿浅色 #116B5B 在 #FFFDF7 上 6.30:1（≥4.5:1，WCAG AA 正文级）；
    //   深色用 #5FC7AC（在纯黑上 10.25:1）——旧的 PositiveStrong #116B5B 在深底上仅 2.4:1，不可用。
    // - 收入红浅色用 #B3261E（6.43:1）——**不用** Danger #D95F5F（浅底上仅 3.56:1，低于 4.5:1）；
    //   深色用 #F2B8B5（12.30:1）。
    val ExpenseGreen = Color(0xFF116B5B)
    val ExpenseGreenNight = Color(0xFF5FC7AC)
    val IncomeRed = Color(0xFFB3261E)
    val IncomeRedNight = Color(0xFFF2B8B5)

    // ---------------------------------------------------------------- 夜间版（v1.1.11 新增）
    //
    // 为什么需要：上面所有 token 都是**浅色值**，裸用在深色底上对比度会掉到 1.6~4.5:1。
    // 每个都配一套夜间值，由 [ledgerTokens] 按 [LocalLedgerDarkTheme] 分发，
    // 这样**浅色模式逐位不变**，只有夜间皮肤拿到下面这套。
    // 取值依据是对纯黑 `#000000` 的 WCAG 对比度（括号内为实测值）：
    val PositiveNight = Color(0xFF6FD6B4)        // 11.95:1（原 #16856F 仅 4.62:1）
    val PositiveStrongNight = Color(0xFF5FC7AC)  // 10.25:1（原 #116B5B 仅 3.28:1）
    val PositivePaleNight = Color(0xFF17453D)    // 容器底 1.96:1（原 #DFF4EA 是浅色亮块）
    val MutedNight = Color(0xFFB6CCC6)           // 12.44:1（原 #708786 5.50:1）
    val BlueNight = Color(0xFF9FBDE3)            // 10.86:1（原 #5C88B8 5.67:1）
    val WarningNight = Color(0xFFE0B25C)         // 10.69:1（原 #9A6A18 仅 4.45:1 ← 原来根本没有夜间版）
    val DangerNight = Color(0xFFF2B8B5)          // 12.30:1（原 #D95F5F 5.75:1）
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
    // 纯黑背景下把容器底从 #123933 提到 #17453D：对纯黑 1.96:1，容器本身可辨；
    // 其上的 onPrimaryContainer 仍有 7.95:1。
    primaryContainer = Color(0xFF17453D),
    onPrimaryContainer = Color(0xFFB7E8D8),
    secondary = Color(0xFF9FBDE3),
    // v1.1.11：用户要求夜间背景改纯黑。页面底与卡片面都取 #000000，
    // 卡片靠 outline 描边分层（不再靠"卡片比背景亮一点"）。
    background = Color(0xFF000000),
    onBackground = Color(0xFFE4EFE9),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE4EFE9),
    // Chip / 输入框 / 下拉底：纯黑上 1.52:1，保持品牌青绿倾向。
    surfaceVariant = Color(0xFF1E3030),
    onSurfaceVariant = Color(0xFFB6CCC6),
    // ⚠️ 原值 #2F4B4D 对卡底只有 1.61:1 —— 这是用户抱怨「控件看不清」的**直接主因**
    // （卡片描边、输入框边框、分割线全部看不见）。提到 3.73:1 才满足 WCAG AA 非文本对比度。
    outline = Color(0xFF4A6E69),
    // 装饰性分割线，2.19:1 —— 可见即可，不要求 3:1。
    outlineVariant = Color(0xFF2E4A47),

    // ---- M3 容器层级与状态色
    //
    // ⚠️ 为什么必须显式给：`darkColorScheme()` 只为**显式传入**的参数取值，其余落到 M3 默认
    // ——那是一整套紫灰（surfaceContainer #211F26、secondaryContainer #4A4458…），与本项目的
    // 青绿 + 纯黑主题冲突。实测截图中底部导航「今日」的选中 pill 就是 M3 默认的紫灰 #484254。
    // 取值已按对比度校验：各层级 vs 纯黑 1.15~1.88:1（可辨即可），其上的前景色见下。
    // ⚠️ 浅色侧 `LightLedgerColors` 仍沿用 M3 默认 secondaryContainer（淡紫）；夜间侧已改为品牌青绿。
    //    若将来要统一，改 `LightLedgerColors` 需另做观感确认（本次原则「浅色模式逐位不变」，故不动）。
    //
    // 实测对比度依据（脚本校验）：
    //   各 container 底 vs 纯黑：Lowest 1.00 / Low 1.08 / 1.15 / High 1.25 / Highest 1.39 / secondaryContainer 1.88
    //   onSecondaryContainer #B6CCC6 on #234040 = 6.62:1
    //   onSurfaceVariant #B6CCC6 on #1E2829 = 8.95:1；on #101617 = 10.83:1
    //   onSurface #E4EFE9 on #1E2829 = 12.81:1
    //   inverseOnSurface #163B3D on #E4EFE9 = 10.32:1
    //   onError #3A0A08 on error #F2B8B5 = 10.02:1；onErrorContainer #FFDAD6 on #5C1A16 = 10.07:1
    //   全量回归：12 个夜间语义色 × 7 个容器底 = 84 组合，最差 5.24:1（EXPENSE 深 on primaryContainer）
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0E0F),
    surfaceContainer = Color(0xFF101617),
    surfaceContainerHigh = Color(0xFF171F20),
    surfaceContainerHighest = Color(0xFF1E2829),
    // NavigationBar / FilterChip 的**选中态**底：比未选中的 surfaceVariant(#1E3030,1.52:1) 再亮一档，
    // 让「选中」一眼可辨。其上的 onSecondaryContainer 有 6.62:1。
    secondaryContainer = Color(0xFF234040),
    onSecondaryContainer = Color(0xFFB6CCC6),
    // Snackbar 用取反色面（亮底暗字），10.32:1。
    inverseSurface = Color(0xFFE4EFE9),
    inverseOnSurface = Color(0xFF163B3D),
    // error 是「错误状态」，与语义上的「收入红」是两回事，这里用与 INCOME 深色值同族的粉红，
    // 但容器色独立给（10.02:1 / 10.07:1）。
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF3A0A08),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 语义调色板（跟随 [LocalLedgerDarkTheme]）。
 *
 * 为什么要有这层：`LedgerPalette.*` 是**浅色值常量**，直接用在夜间深底上对比度会掉到
 * 1.6~4.5:1（实测 outline 1.61:1、PositiveStrong 2.36:1）。这里给每个 token 配一套夜间值，
 * 调用点统一走 `ledgerTokens().xxx`。
 *
 * 为什么不用 `MaterialTheme.colorScheme.primary` 之类替代：`Positive` 与 `PositiveStrong`
 * 是两个**不同的绿**（品牌强调 / 深强调），映射到同一个 primary 会丢失区分度。
 *
 * `@Immutable` 是必要的：不加的话 Compose 会把它当 unstable 类型，凡是读取 token 的
 * Composable 都会被标记为「可能随时变化」，在父级重组时失去跳过能力。
 */
@Immutable
class LedgerTokens(
    val positive: Color,
    val positiveStrong: Color,
    val positivePale: Color,
    val muted: Color,
    val blue: Color,
    val warning: Color,
    val danger: Color,
)

private val LightTokens = LedgerTokens(
    positive = LedgerPalette.Positive,
    positiveStrong = LedgerPalette.PositiveStrong,
    positivePale = LedgerPalette.PositivePale,
    muted = LedgerPalette.Muted,
    blue = LedgerPalette.Blue,
    warning = LedgerPalette.Warning,
    danger = LedgerPalette.Danger,
)

private val NightTokens = LedgerTokens(
    positive = LedgerPalette.PositiveNight,
    positiveStrong = LedgerPalette.PositiveStrongNight,
    positivePale = LedgerPalette.PositivePaleNight,
    muted = LedgerPalette.MutedNight,
    blue = LedgerPalette.BlueNight,
    warning = LedgerPalette.WarningNight,
    danger = LedgerPalette.DangerNight,
)

/** 当前主题下的语义调色板。浅色走 [LightTokens]，夜间皮肤走 [NightTokens]。 */
@Composable
fun ledgerTokens(): LedgerTokens =
    if (LocalLedgerDarkTheme.current) NightTokens else LightTokens
