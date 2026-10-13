package com.finecat.pimobile.android.ui.finecat.composer

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.isActive

/** 输入框和模型浮层共用8秒往返；只在溢出时移动，按住暂停。 */
@Composable
internal fun FcMarqueeLabel(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    pressed: Boolean,
    modifier: Modifier = Modifier,
) {
    var textWidth by remember(text) { mutableStateOf(0) }
    var viewportWidth by remember { mutableStateOf(0) }
    val overflow = (textWidth - viewportWidth).coerceAtLeast(0)
    var phase by remember(text) { mutableFloatStateOf(0f) }
    LaunchedEffect(overflow, pressed) {
        if (overflow == 0) phase = 0f else if (!pressed) {
            var previousFrame: Long? = null
            while (isActive) {
                val frame = withInfiniteAnimationFrameMillis { it }
                previousFrame?.let { phase = (phase + (frame - it) / 8000f) % 1f }
                previousFrame = frame
            }
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { viewportWidth = it.width }) {
        Text(text = text, style = style, color = color, maxLines = 1,
            onTextLayout = { textWidth = it.size.width },
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer {
                val position = when {
                    phase <= 0.16f -> 0f
                    phase < 0.46f -> (phase - 0.16f) / 0.30f
                    phase <= 0.80f -> 1f
                    else -> (1f - phase) / 0.20f
                }
                translationX = -overflow * position
            })
    }
}
