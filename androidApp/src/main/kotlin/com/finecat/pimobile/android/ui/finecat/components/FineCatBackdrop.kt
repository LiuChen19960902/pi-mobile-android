package com.finecat.pimobile.android.ui.finecat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors

/**
 * 空态 hero 高度（原型 `.empty { --hero-h: 236px }`）。首页布局要靠它把品牌行压到
 * 中线，所以对布局层公开。
 */
val HERO_HEIGHT: Dp = 236.dp

/** 同一尺寸的 px 表示，供画布内的几何换算用。 */
private const val HERO_HEIGHT_PX = 236f

/**
 * 空态首页底纹：方格纸（原型 `.empty.has-grid::before`）。
 *
 * 46 单位双向 1 单位细线，取 `grid` token；竖向遮罩自上而下渐隐——42% 之前保持
 * 不透明，42%→94% 渐到全透明，之后不再绘制（原型 `mask-image` 用的就是这段渐变，
 * 目的是"将现未现"而不是满屏网格）。
 *
 * 纯装饰：不参与语义树，不接收触摸。
 */
@Composable
fun FcGridBackdrop(modifier: Modifier = Modifier) {
    val gridColor = LocalFineCatColors.current.grid
    Canvas(modifier = modifier) {
        val step = GRID_STEP.toPx()
        val stroke = GRID_LINE.toPx()
        val fadeStart = size.height * GRID_FADE_START
        val fadeEnd = size.height * GRID_FADE_END

        // 横线：整条 alpha 只取决于自身所在的 y，直接一条条画。
        var y = 0f
        while (y <= size.height) {
            val a = gridFadeAlpha(y, fadeStart, fadeEnd)
            if (a > 0f) {
                drawLine(
                    color = gridColor.withAlpha(a),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = stroke,
                )
            }
            y += step
        }

        // 竖线：顶部保持不透明（0→fadeStart 一整段），之后分段逼近渐变
        // （无 gradient stroke 的等价画法）。
        var x = 0f
        while (x <= size.width) {
            if (fadeStart > 0f) {
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, fadeStart),
                    strokeWidth = stroke,
                )
            }
            var seg = 0
            while (seg < GRID_FADE_SEGMENTS) {
                val t0 = seg.toFloat() / GRID_FADE_SEGMENTS
                val t1 = (seg + 1).toFloat() / GRID_FADE_SEGMENTS
                val a = gridFadeAlpha(
                    y = fadeStart + (fadeEnd - fadeStart) * (t0 + t1) / 2f,
                    fadeStart = fadeStart,
                    fadeEnd = fadeEnd,
                )
                if (a > 0f) {
                    drawLine(
                        color = gridColor.withAlpha(a),
                        start = Offset(x, fadeStart + (fadeEnd - fadeStart) * t0),
                        end = Offset(x, fadeStart + (fadeEnd - fadeStart) * t1),
                        strokeWidth = stroke,
                    )
                }
                seg++
            }
            x += step
        }
    }
}

/**
 * 半调点阵拼出的 "Pi"（原型 `heroPi()` 的 SVG）。
 *
 * 几何直接照抄原型：300×236 画布，10 单位间距的点阵（圆心 2,2、半径 1.7），点阵被
 * 7 个矩形组成的 "Pi" 字形遮罩裁出，遮罩自身再叠一条自上而下的淡出渐变
 * （0% .95 → 55% .4 → 100% 0），所以是朦胧轮廓而不是实心图形。
 *
 * 纯装饰：不参与语义树，不接收触摸。
 */
@Composable
fun FcDottedPiMark(modifier: Modifier = Modifier) {
    val dotColor = LocalFineCatColors.current.dot
    Canvas(modifier = modifier.size(HERO_WIDTH.dp, HERO_HEIGHT)) {
        val radius = PI_DOT_RADIUS.dp.toPx()
        val scaleX = size.width / HERO_WIDTH
        val scaleY = size.height / HERO_HEIGHT_PX

        // 只遍历字形包围盒内的点阵格，避免对空白区域做无用判断。
        var gy = PI_BOX_TOP
        while (gy <= PI_BOX_BOTTOM) {
            val centerY = (gy + PI_DOT_OFFSET) * scaleY
            val maskAlpha = piMaskAlpha(centerY / size.height)
            if (maskAlpha > 0f) {
                var gx = PI_BOX_LEFT
                while (gx <= PI_BOX_RIGHT) {
                    if (inPiShape(gx, gy)) {
                        drawCircle(
                            color = dotColor.withAlpha(maskAlpha),
                            radius = radius,
                            center = Offset((gx + PI_DOT_OFFSET) * scaleX, centerY),
                        )
                    }
                    gx += PI_CELL_STEP
                }
            }
            gy += PI_CELL_STEP
        }
    }
}

/** 这个点阵格（[gx, gx+10] × [gy, gy+10]）是否与 "Pi" 任一矩形相交。 */
private fun inPiShape(gx: Int, gy: Int): Boolean {
    val x0 = gx.toFloat()
    val y0 = gy.toFloat()
    val x1 = x0 + PI_CELL
    val y1 = y0 + PI_CELL
    var i = 0
    while (i < PI_RECTS.size) {
        val rx = PI_RECTS[i].toFloat()
        val ry = PI_RECTS[i + 1].toFloat()
        if (x0 < rx + PI_RECTS[i + 2] && x1 > rx && y0 < ry + PI_RECTS[i + 3] && y1 > ry) return true
        i += 4
    }
    return false
}

/** 方格纸竖向渐隐：42% 之前不透明，94% 之后全透明。 */
private fun gridFadeAlpha(y: Float, fadeStart: Float, fadeEnd: Float): Float = when {
    fadeEnd <= fadeStart -> if (y <= fadeStart) 1f else 0f
    y <= fadeStart -> 1f
    y >= fadeEnd -> 0f
    else -> 1f - (y - fadeStart) / (fadeEnd - fadeStart)
}

/** 点阵遮罩渐变：0% → .95，55% → .4，100% → 0。 */
private fun piMaskAlpha(t: Float): Float = when {
    t >= 1f -> 0f
    t <= 0.55f -> 0.95f + (0.4f - 0.95f) * (t / 0.55f)
    else -> 0.4f * (1f - (t - 0.55f) / 0.45f)
}

private fun Color.withAlpha(factor: Float): Color =
    if (factor >= 1f) this else copy(alpha = alpha * factor)

private val GRID_STEP = 46.dp
private val GRID_LINE = 1.dp
private const val GRID_FADE_START = 0.42f
private const val GRID_FADE_END = 0.94f
private const val GRID_FADE_SEGMENTS = 12

private const val HERO_WIDTH = 300f
private const val PI_CELL = 10f
private const val PI_CELL_STEP = 10
private const val PI_DOT_OFFSET = 2f
private const val PI_DOT_RADIUS = 1.7f
private const val PI_BOX_LEFT = 20
private const val PI_BOX_TOP = 20
private const val PI_BOX_RIGHT = 240
private const val PI_BOX_BOTTOM = 210

/** 原型 `PI` 表：[x, y, width, height] × 7，拼出 "Pi"。 */
private val PI_RECTS = intArrayOf(
    31, 33, 136, 34,
    31, 67, 34, 34,
    133, 67, 34, 34,
    31, 101, 102, 34,
    31, 135, 34, 68,
    201, 33, 34, 34,
    201, 101, 34, 102,
)

@Preview(showBackground = true, widthDp = 390, heightDp = 600)
@Composable
private fun FcDottedPiMarkPreview() {
    FineCatTheme(darkTheme = false) {
        FcDottedPiMark()
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 600)
@Composable
private fun FcGridBackdropPreview() {
    FineCatTheme(darkTheme = false) {
        FcGridBackdrop()
    }
}
