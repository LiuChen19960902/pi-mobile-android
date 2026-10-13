package com.finecat.pimobile.android.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.exposureAdjustment
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * Backdrop 只录制背景层，玻璃控件不会被再次采样，避免递归渲染和重影。
 *
 * API 31+ 使用真实背景采样与模糊，API 33+ 额外启用 AGSL lens 折射；
 * API 24～30 保持与原设计一致的半透明玻璃降级。
 */
@Composable
internal fun PiLiquidGlassHost(
    modifier: Modifier = Modifier,
    background: @Composable (Modifier) -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    val frameSignal = remember { mutableLongStateOf(0L) }
    CompositionLocalProvider(
        LocalPiBackdrop provides backdrop,
        LocalPiGlassFrameSignal provides frameSignal
    ) {
        Box(modifier) {
            background(
                Modifier
                    .drawWithContent {
                        frameSignal.longValue
                        drawContent()
                    }
                    .layerBackdrop(backdrop)
            )
            content()
        }
    }
}

internal enum class PiGlassStyle(
    val blurRadiusDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val surfaceAlpha: Float,
    val borderAlpha: Float,
    val shadowRadiusDp: Float,
    val saturation: Float,
    val navyTintAlpha: Float = 0f
) {
    CONTROL(4f, 11f, 18f, 0.050f, 0.25f, 15f, saturation = 0.92f),
    CARD(5f, 14f, 23f, 0.005f, 0.20f, 20f, saturation = 0.72f),
    ACTION(5f, 16f, 26f, 0.012f, 0.26f, 22f, saturation = 0.74f, navyTintAlpha = 0.06f),
    FIELD(5f, 10f, 16f, 0.060f, 0.10f, 14f, saturation = 0.72f),
    SUBTLE(4f, 8f, 13f, 0.040f, 0.10f, 12f, saturation = 0.72f)
}

@Composable
internal fun Modifier.piLiquidGlass(
    shape: Shape,
    style: PiGlassStyle = PiGlassStyle.CARD
): Modifier {
    val isDark = LocalPiDark.current
    val backdrop = LocalPiBackdrop.current
    val frameSignal = LocalPiGlassFrameSignal.current
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return clip(shape)
            .background(Color.White.copy(alpha = style.surfaceAlpha), shape)
            .border(0.8.dp, Color.White.copy(alpha = style.borderAlpha), shape)
    }
    val frameInvalidatingModifier = drawWithContent {
        frameSignal?.longValue
        drawContent()
    }

    return frameInvalidatingModifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            colorControls(brightness = 0.005f, contrast = 1.03f, saturation = style.saturation)
            exposureAdjustment(0.015f)
            blur(style.blurRadiusDp.dp.toPx())
            lens(
                refractionHeight = style.refractionHeightDp.dp.toPx(),
                refractionAmount = style.refractionAmountDp.dp.toPx(),
                depthEffect = true,
                chromaticAberration = true
            )
        },
        highlight = {
            Highlight.Default.copy(
                width = 0.90.dp,
                blurRadius = 0.75.dp,
                alpha = style.borderAlpha.coerceAtLeast(0.10f)
            )
        },
        shadow = {
            Shadow(
                radius = style.shadowRadiusDp.dp,
                color = Color.Black.copy(alpha = 0.09f)
            )
        },
        innerShadow = {
            InnerShadow.Default.copy(
                radius = 4.dp,
                alpha = style.borderAlpha * 0.22f
            )
        },
        onDrawSurface = {
            if (isDark && style.navyTintAlpha > 0f) {
                drawRect(Color(0xFF031B3D).copy(alpha = style.navyTintAlpha), blendMode = BlendMode.SrcOver)
            }
            drawRect(Color.White.copy(alpha = style.surfaceAlpha), blendMode = BlendMode.SrcOver)
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = style.borderAlpha * 0.20f),
                    0.34f to Color.White.copy(alpha = style.surfaceAlpha * 0.08f),
                    0.68f to Color.Transparent,
                    1f to Color.White.copy(alpha = style.surfaceAlpha * 0.03f)
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = style.borderAlpha * 0.12f),
                    0.22f to Color.White.copy(alpha = style.surfaceAlpha * 0.06f),
                    0.58f to Color.Transparent,
                    1f to Color.White.copy(alpha = style.borderAlpha * 0.02f)
                )
            )
        }
    )
}

/** 普通面板使用中性玻璃底；深色独立弹窗用不透明石墨色，靠明度与细边框区分层级。 */
@Composable
internal fun piGlassTint(strong: Boolean = false): Color =
    if (LocalPiDark.current) Color(0xFF1C1E22).copy(alpha = if (strong) 1f else 0.90f)
    else Color.White.copy(alpha = if (strong) 0.94f else 0.76f)

/** 细边沿只负责材质，不改变已有组件的圆角、间距或触控区域。 */
@Composable
internal fun piGlassOutline(): BorderStroke = BorderStroke(
    0.7.dp,
    if (LocalPiDark.current) Color.White.copy(alpha = 0.10f)
    else PiColors.Ocean.copy(alpha = 0.12f)
)

private val LocalPiBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalPiGlassFrameSignal = staticCompositionLocalOf<MutableLongState?> { null }
