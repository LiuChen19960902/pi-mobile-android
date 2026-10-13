package com.finecat.pimobile.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

internal object PiColors {
    val Navy = Color(0xFF06172B)
    val NavyRaised = Color(0xFF0D2440)
    val Ocean = Color(0xFF2E6BE6)
    val Mist = Color(0xFFBFD6FF)
    val Ink = Color(0xFF0E131A)
    val Paper = Color(0xFFF9FAFC)
    val Purple = Color(0xFF7A54C7)
    val Orange = Color(0xFFF07D14)
    val Amber = Color(0xFFFFAD1F)
    val Success = Color(0xFF2EB85C)
}

private val LightColors = lightColorScheme(
    primary = PiColors.Ocean,
    secondary = PiColors.Purple,
    background = PiColors.Paper,
    surface = Color.White,
    onBackground = PiColors.Ink,
    onSurface = PiColors.Ink
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD3D7DE),
    onPrimary = Color(0xFF17191D),
    primaryContainer = Color(0xFF30343B),
    onPrimaryContainer = Color(0xFFE4E6EB),
    secondary = Color(0xFFB69BFF),
    background = Color(0xFF101010),
    surface = Color(0xFF1C1E22),
    surfaceVariant = Color(0xFF282A2F),
    surfaceContainerLowest = Color(0xFF101010),
    surfaceContainerLow = Color(0xFF16181B),
    surfaceContainer = Color(0xFF1C1E22),
    surfaceContainerHigh = Color(0xFF25272B),
    surfaceContainerHighest = Color(0xFF303237),
    onBackground = Color(0xFFF1F4FA),
    onSurface = Color(0xFFF1F4FA)
)

// 默认弹窗、底部面板也收小圆角，避免未显式设 shape 的组件回到 Material 大圆角。
private val PiShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

/**
 * 紧凑 Typography：只覆盖被 UI 直接消费的档位，其余沿用 M3 默认。
 * 正文/输入 bodyLarge 14sp、普通标题 headlineSmall/titleLarge 16sp、titleMedium 15sp；
 * 11–12sp 辅助档（bodySmall/label*）不再缩小。显式 fontSize 处已同步核对，
 * Markwon TextView 字号在 PiMarkdownText 内单独收紧。
 */
private val PiTypography = Typography(
    headlineSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
)

@Composable
internal fun PiTheme(appearanceMode: String = "system", content: @Composable () -> Unit) {
    val dark = effectivePiDark(appearanceMode, isSystemInDarkTheme())
    // 系统栏图标明暗唯一决定点在 PiProductApp（需按 workspace/会话页区分）；
    // 这里不再写 SideEffect，避免两处互覆（透明底由 MainActivity.enableEdgeToEdge 负责）。
    CompositionLocalProvider(LocalPiDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = PiTypography,
            shapes = PiShapes,
            content = content
        )
    }
}

/** app 外观覆盖后的生效深色值；所有 UI 从这里/LocalPiDark 读，别再直接读系统。 */
internal val LocalPiDark = compositionLocalOf { true }

internal fun effectivePiDark(appearanceMode: String, systemDark: Boolean): Boolean = when (appearanceMode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}
