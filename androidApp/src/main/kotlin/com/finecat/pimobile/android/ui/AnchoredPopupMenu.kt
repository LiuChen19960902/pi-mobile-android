package com.finecat.pimobile.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** 卡片本身就是定位边界；阴影不增加布局尺寸。anchorBounds 使用宿主窗口坐标。 */
@Composable
internal fun AnchoredPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: IntRect,
    width: Dp,
    horizontalOffset: Dp = 0.dp,
    anchorGap: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded || anchorBounds.width == 0 || anchorBounds.height == 0) return
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    // safeDrawing 包含系统栏、刘海和 IME；与 positionInWindow 使用同一窗口原点。
    val insets = WindowInsets.safeDrawing
    val margin = with(density) { 8.dp.roundToPx() }
    val left = (insets.getLeft(density, direction) + margin).coerceIn(0, windowSize.width)
    val top = (insets.getTop(density) + margin).coerceIn(0, windowSize.height)
    val safeBounds = IntRect(
        left, top,
        (windowSize.width - insets.getRight(density, direction) - margin).coerceAtLeast(left),
        (windowSize.height - insets.getBottom(density) - margin).coerceAtLeast(top)
    )
    val gap = with(density) { anchorGap.roundToPx() }
    val offset = with(density) { horizontalOffset.roundToPx() }
    val availableHeight = anchoredMenuAvailableHeight(anchorBounds, safeBounds, gap)
    if (safeBounds.width == 0 || availableHeight == 0) return
    val positionProvider = remember(safeBounds, offset, gap) {
        AnchoredMenuPositionProvider(safeBounds, offset, gap)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        // 由上面的实际安全区约束卡片，避免 Android 再按 visible display frame 移动窗口。
        properties = PopupProperties(focusable = true, clippingEnabled = false)
    ) {
        Surface(
            modifier = Modifier
                .width(with(density) { width.coerceAtMost(safeBounds.width.toDp()) })
                .heightIn(max = with(density) { availableHeight.toDp().coerceAtMost(560.dp) }),
            shape = RoundedCornerShape(12.dp),
            color = piGlassTint(strong = true),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = piGlassOutline(),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
                content = content
            )
        }
    }
}

internal fun anchoredMenuAvailableHeight(anchor: IntRect, safe: IntRect, gap: Int): Int {
    val above = (minOf(anchor.top - gap, safe.bottom) - safe.top).coerceAtLeast(0)
    val below = (safe.bottom - maxOf(anchor.bottom + gap, safe.top)).coerceAtLeast(0)
    return maxOf(above, below)
}

internal class AnchoredMenuPositionProvider(
    private val safeBounds: IntRect,
    private val horizontalOffset: Int,
    private val gap: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // 不用 Popup 传入的可见区域尺寸推导窗口原点，避免全屏宿主与系统栏产生坐标差。
        val maxX = (safeBounds.right - popupContentSize.width).coerceAtLeast(safeBounds.left)
        val maxY = (safeBounds.bottom - popupContentSize.height).coerceAtLeast(safeBounds.top)
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width + horizontalOffset
        } else {
            anchorBounds.left - horizontalOffset
        }
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val y = when {
            above >= safeBounds.top && above <= maxY -> above
            below >= safeBounds.top && below <= maxY -> below
            anchorBounds.top - safeBounds.top >= safeBounds.bottom - anchorBounds.bottom -> above
            else -> below
        }
        return IntOffset(x.coerceIn(safeBounds.left, maxX), y.coerceIn(safeBounds.top, maxY))
    }
}
