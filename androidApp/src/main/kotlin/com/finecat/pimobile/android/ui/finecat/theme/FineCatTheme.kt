package com.finecat.pimobile.android.ui.finecat.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FineCat 明暗主题的颜色、字号与间距定义，供组件按语义取值。
 *
 * 语义规则：`ink` 是主操作实心色，`brand` 只做强调（当前选中/焦点/来源词），
 * 两者不能混用；`ok` 只表达事实性正常，不表达"进行中"。
 */
@Immutable
data class FineCatColors(
    val bg: Color,
    val bgDeep: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val surfaceFloat: Color,
    val overlay: Color,
    val overlayDeep: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val textInv: Color,
    val line: Color,
    val line2: Color,
    val brand: Color,
    val brandSoft: Color,
    val brandLine: Color,
    val dot: Color,
    val grid: Color,
    val ink: Color,
    val ink2: Color,
    val onInk: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val err: Color,
    val errSoft: Color,
    val roleAi: Color,
    val roleMe: Color,
    /** 阴影基色：浅色用墨色低透明层，深色下更黑更实（tokens.md §5）。 */
    val shadow: Color,
    val isDark: Boolean,
)

/** CSS 的 rgba() 精确落到 Color alpha，不做取整。 */
private fun rgba(rgb: Long, alpha: Float): Color = Color(0xFF000000L or rgb).copy(alpha = alpha)

fun lightFineCatColors() = FineCatColors(
    bg = Color(0xFFFAFAFA),
    bgDeep = Color(0xFFEEEEEC),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF0F0EE),
    surface3 = Color(0xFFE7E7E4),
    surfaceFloat = Color(0xFFF8F8F6),
    overlay = rgba(0x1C1C1E, 0.34f),
    overlayDeep = rgba(0x0C0C0D, 0.72f),
    text = Color(0xFF1A1A1C),
    text2 = Color(0xFF63636A),
    text3 = Color(0xFF7F7F88),
    textInv = Color(0xFFFFFFFF),
    line = Color(0xFFE4E4E1),
    line2 = Color(0xFFD5D5D1),
    brand = Color(0xFF2F5FE0),
    brandSoft = rgba(0x2F5FE0, 0.10f),
    brandLine = rgba(0x2F5FE0, 0.28f),
    dot = rgba(0x2F5FE0, 0.13f),
    grid = rgba(0x2F5FE0, 0.045f),
    ink = Color(0xFF1A1A1C),
    ink2 = Color(0xFF3A3A3E),
    onInk = Color(0xFFFFFFFF),
    ok = Color(0xFF1A9A6C),
    okSoft = rgba(0x1A9A6C, 0.12f),
    warn = Color(0xFFB8730F),
    warnSoft = rgba(0xB8730F, 0.12f),
    err = Color(0xFFCC3F3F),
    errSoft = rgba(0xCC3F3F, 0.10f),
    roleAi = Color(0xFF6B5BC4),
    roleMe = Color(0xFF2F5FE0),
    shadow = Color(0xFF1A1A1C),
    isDark = false,
)

fun darkFineCatColors() = FineCatColors(
    bg = Color(0xFF0C0C0E),
    bgDeep = Color(0xFF08080A),
    surface = Color(0xFF17171A),
    surface2 = Color(0xFF1F1F23),
    surface3 = Color(0xFF28282D),
    surfaceFloat = Color(0xFF1C1C20),
    overlay = rgba(0x000000, 0.60f),
    overlayDeep = rgba(0x000000, 0.78f),
    text = Color(0xFFF2F2F3),
    text2 = Color(0xFFABABB3),
    text3 = Color(0xFF8B8B95),
    textInv = Color(0xFF0C0C0E),
    line = Color(0xFF26262B),
    line2 = Color(0xFF333339),
    brand = Color(0xFF6F97FF),
    brandSoft = rgba(0x6F97FF, 0.14f),
    brandLine = rgba(0x6F97FF, 0.34f),
    dot = rgba(0x6F97FF, 0.14f),
    grid = rgba(0x6F97FF, 0.05f),
    ink = Color(0xFFF2F2F3),
    ink2 = Color(0xFFD6D6DA),
    onInk = Color(0xFF131316),
    ok = Color(0xFF35C48C),
    okSoft = rgba(0x35C48C, 0.14f),
    warn = Color(0xFFE0983F),
    warnSoft = rgba(0xE0983F, 0.14f),
    err = Color(0xFFF06A6A),
    errSoft = rgba(0xF06A6A, 0.14f),
    roleAi = Color(0xFFA294F0),
    roleMe = Color(0xFF6F97FF),
    shadow = Color(0xFF000000),
    isDark = true,
)

/**
 * 字阶。15（气泡/composer）和 17（AI 正文）是原型的刻意中间字号，
 * 收进 type token（tokens.md §2），不要顺手圆整成 14/16/18。
 */
@Immutable
data class FineCatType(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val sm: TextStyle,
    val xs: TextStyle,
    val xs2: TextStyle,
    val bubble: TextStyle,
    val prose: TextStyle,
    val mono: FontFamily,
)

private val fineCatMono = FontFamily.Monospace

fun fineCatType(): FineCatType {
    fun style(size: Int, lineHeightFactor: Float, weight: androidx.compose.ui.text.font.FontWeight) =
        TextStyle(
            fontSize = size.sp, lineHeight = (size * lineHeightFactor).sp, fontWeight = weight,
            // 显式使用 CSS 默认的 0 字距；不依赖外层 Material 默认值。
            letterSpacing = 0.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            // CSS line-height 包含首末行留白；不继承 Material 的首末行裁剪。
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    return FineCatType(
        display = style(26, 1.25f, androidx.compose.ui.text.font.FontWeight.Normal),
        title = style(19, 1.25f, androidx.compose.ui.text.font.FontWeight.Normal),
        body = style(16, 1.55f, androidx.compose.ui.text.font.FontWeight.Normal),
        sm = style(14, 1.55f, androidx.compose.ui.text.font.FontWeight.Normal),
        xs = style(12, 1.55f, androidx.compose.ui.text.font.FontWeight.Normal),
        xs2 = style(11, 1.55f, androidx.compose.ui.text.font.FontWeight.Normal),
        bubble = style(15, 1.55f, androidx.compose.ui.text.font.FontWeight.Normal),
        prose = style(17, 1.62f, androidx.compose.ui.text.font.FontWeight.Normal),
        mono = fineCatMono,
    )
}

/** 间距（4 的倍数体系）与页面 gutter，CSS px → dp。 */
@Immutable
data class FineCatSpacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s8: Dp = 32.dp,
    val s10: Dp = 40.dp,
    val s14: Dp = 56.dp,
    val gutter: Dp = 20.dp,
)

/** 圆角。pill 在使用处用 CircleShape / 50% 表达，不单独给 999dp。 */
@Immutable
data class FineCatRadius(
    val card: Dp = 16.dp,
    val inner: Dp = 12.dp,
    val chip: Dp = 8.dp,
    val bubble: Dp = 16.dp,
    val sheetTop: Dp = 20.dp,
    /** 两行内容配 pill 会顶出圆角，composer 展开态用 18（components.md §2）。 */
    val composerExpanded: Dp = 18.dp,
    val shot: Dp = 10.dp,
    val seg: Dp = 9.dp,
    val segItem: Dp = 7.dp,
    val tag: Dp = 6.dp,
)

/** 动效时长与缓动，来自 styles.css --t-fast/base/sheet。 */
@Immutable
data class FineCatMotion(
    val fast: Int = 120,
    val base: Int = 220,
    val sheet: Int = 340,
    val fastEasing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f),
    val baseEasing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0.24f, 1f),
)

val LocalFineCatColors = staticCompositionLocalOf { lightFineCatColors() }
val LocalFineCatType = staticCompositionLocalOf { fineCatType() }
val LocalFineCatSpacing = staticCompositionLocalOf { FineCatSpacing() }
val LocalFineCatRadius = staticCompositionLocalOf { FineCatRadius() }
val LocalFineCatMotion = staticCompositionLocalOf { FineCatMotion() }

/**
 * Material3 槽位只是容器（tokens.md §6）：brand → primary，ink 走自定义 token，
 * 不塞进 primary，否则所有 Material 组件都会变蓝。新组件优先直接读
 * `LocalFineCatColors` 等自定义 token，不依赖 Material 语义。
 */
private fun FineCatColors.toMaterialColorScheme(): ColorScheme {
    val onBrand = if (isDark) Color(0xFF131316) else Color(0xFFFFFFFF)
    val onError = if (isDark) Color(0xFF131316) else Color(0xFFFFFFFF)
    return if (isDark) {
        darkColorScheme(
            primary = brand, onPrimary = onBrand, primaryContainer = brandSoft, onPrimaryContainer = brand,
            secondary = ink, onSecondary = onInk, secondaryContainer = surface2, onSecondaryContainer = text,
            tertiary = roleAi, onTertiary = textInv,
            background = bg, onBackground = text,
            surface = surface, onSurface = text,
            surfaceVariant = surface2, onSurfaceVariant = text2,
            surfaceContainerLowest = surface, surfaceContainerLow = surface,
            surfaceContainer = surface2, surfaceContainerHigh = surface3, surfaceContainerHighest = surface3,
            outline = line2, outlineVariant = line,
            error = err, onError = onError, errorContainer = errSoft, onErrorContainer = err,
            scrim = overlay,
        )
    } else {
        lightColorScheme(
            primary = brand, onPrimary = onBrand, primaryContainer = brandSoft, onPrimaryContainer = brand,
            secondary = ink, onSecondary = onInk, secondaryContainer = surface2, onSecondaryContainer = text,
            tertiary = roleAi, onTertiary = textInv,
            background = bg, onBackground = text,
            surface = surface, onSurface = text,
            surfaceVariant = surface2, onSurfaceVariant = text2,
            surfaceContainerLowest = surface, surfaceContainerLow = surface,
            surfaceContainer = surface2, surfaceContainerHigh = surface3, surfaceContainerHighest = surface3,
            outline = line2, outlineVariant = line,
            error = err, onError = onError, errorContainer = errSoft, onErrorContainer = err,
            scrim = overlay,
        )
    }
}

private fun fineCatShapes(r: FineCatRadius) = Shapes(
    extraSmall = RoundedCornerShape(r.chip),
    small = RoundedCornerShape(r.chip),
    medium = RoundedCornerShape(r.inner),
    large = RoundedCornerShape(r.card),
    extraLarge = RoundedCornerShape(r.sheetTop),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FineCatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) darkFineCatColors() else lightFineCatColors()
    val type = fineCatType()
    val spacing = FineCatSpacing()
    val radius = FineCatRadius()
    val motion = FineCatMotion()
    val rippleConfiguration = remember {
        val defaults = RippleDefaults.RippleAlpha
        RippleConfiguration(
            rippleAlpha = RippleAlpha(
                draggedAlpha = defaults.draggedAlpha,
                focusedAlpha = defaults.focusedAlpha,
                hoveredAlpha = defaults.hoveredAlpha,
                pressedAlpha = 0f,
            ),
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalFineCatColors provides colors,
        LocalFineCatType provides type,
        LocalFineCatSpacing provides spacing,
        LocalFineCatRadius provides radius,
        LocalFineCatMotion provides motion,
        // 不在触控矩形上叠加按压灰底；保留键盘焦点、悬停与拖动提示。
        LocalRippleConfiguration provides rippleConfiguration,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            typography = MaterialTheme.typography,
            shapes = fineCatShapes(radius),
            content = content,
        )
    }
}
