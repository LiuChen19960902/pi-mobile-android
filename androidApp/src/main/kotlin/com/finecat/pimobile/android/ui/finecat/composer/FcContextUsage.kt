package com.finecat.pimobile.android.ui.finecat.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.finecat.pimobile.android.ui.finecat.components.FcPopover
import com.finecat.pimobile.android.ui.finecat.components.FineCatElevation
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.protocol.GatewayContextUsage
import java.util.Locale
import kotlin.math.roundToInt

internal data class FcContextUsageDisplay(val progress: Float, val percent: String, val tokens: String, val window: String)

/** 只显示会话快照；SDK 压缩后返回的 null 不改写成零或旧轮次统计。 */
internal fun contextUsageDisplay(usage: GatewayContextUsage?): FcContextUsageDisplay {
    val percent = usage?.percent?.takeIf { it.isFinite() && it >= 0 }
    return FcContextUsageDisplay(
        progress = percent?.div(100)?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f,
        percent = percent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
        tokens = formatContextTokens(usage?.tokens?.takeIf { it >= 0 }),
        window = formatContextTokens(usage?.contextWindow?.takeIf { it > 0 }),
    )
}

private fun formatContextTokens(value: Long?): String = when {
    value == null -> "—"
    value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.0fK", value / 1_000.0)
    else -> value.toString()
}

@Composable
internal fun FcContextRing(usage: GatewayContextUsage?, onClick: (IntRect) -> Unit, onBoundsChange: (IntRect) -> Unit) {
    val colors = LocalFineCatColors.current
    val display = contextUsageDisplay(usage)
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier.size(48.dp)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                anchor = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                onBoundsChange(anchor)
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onClick(anchor) }
            .semantics { contentDescription = if (display.percent == "—") "上下文占用暂无统计" else "上下文占用 ${display.percent}" }
            .testTag("fc.contextRing"),
        contentAlignment = Alignment.Center,
    ) {
        // SVG viewBox=20、r=8、stroke=2.5；composer 图标实际为18dp。
        Canvas(Modifier.size(18.dp).scale(if (pressed) 0.94f else 1f)) {
            val radius = size.minDimension * 0.4f
            val stroke = size.minDimension * 0.125f
            drawCircle(colors.surface3, radius, style = Stroke(stroke))
            if (display.progress > 0f) {
                drawArc(
                    color = colors.ink, startAngle = -90f, sweepAngle = display.progress * 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** 根级浮层复用已有避让IME的定位；本轮只有UI，不持有compact回调。 */
@Composable
internal fun FcContextPopover(
    anchor: IntRect,
    usage: GatewayContextUsage?,
    isCompacting: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val radius = LocalFineCatRadius.current
    val type = LocalFineCatType.current
    val display = contextUsageDisplay(usage)
    FcPopover(
        expanded = true,
        onDismissRequest = onDismiss,
        anchorBoundsInWindow = anchor,
        modifier = Modifier.border(1.dp, colors.line, RoundedCornerShape(radius.inner)),
        focusable = false,
        elevation = FineCatElevation.Card,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp).testTag("fc.contextPopover"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(display.percent, style = type.title.copy(fontWeight = FontWeight.Bold), color = colors.text, modifier = Modifier.alignByBaseline())
                Text("${display.tokens} / ${display.window}", style = type.xs, color = colors.text3, modifier = Modifier.alignByBaseline())
            }
            Box(
                modifier = Modifier.fillMaxWidth()
                    .alpha(0.55f).background(colors.ink, RoundedCornerShape(999.dp))
                    .clickable(enabled = false, role = Role.Button) {}
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("fc.contextCompact"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isCompacting) "压缩中…" else "压缩上下文", style = type.sm, color = colors.onInk)
            }
        }
    }
}
