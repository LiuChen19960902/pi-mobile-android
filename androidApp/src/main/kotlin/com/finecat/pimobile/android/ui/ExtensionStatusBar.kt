package com.finecat.pimobile.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.shared.protocol.GatewayStatusEntry

/**
 * 扩展状态行（Pi 扩展 `ctx.ui.setStatus(key, text)`）：只读、纯文本、不解析内容。
 * 网关不宣告 `extension-status` 时永远没有数据，这里也一并隐藏（旧网关降级）。
 * 状态只是显示提示，不影响忙闲与发送语义。
 *
 * 旧会话页与 FineCat 会话页共用；颜色由各自主题传入，避免状态区成为两套主题的例外。
 */
@Composable
internal fun ExtensionStatusBar(
    stateHolder: AndroidSharedStateHolder,
    sessionId: String?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if ("extension-status" !in stateHolder.gatewayState.capabilities) return
    val statuses = sessionId?.let { stateHolder.snapshot.extensionStatuses[it] }.orEmpty()
    val text = extensionStatusText(statuses) ?: return
    val style = TextStyle(
        fontSize = 10.sp,
        lineHeight = 12.5.sp,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 1.dp)
            .testTag("extension-status-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 装饰状态行，不解析扩展文字或改变忙闲；保留最小字宽，随系统字号自然增长。
        PiSpinner(color = color, fontSize = 10.sp, style = style,
            modifier = Modifier.widthIn(min = 12.dp).clearAndSetSemantics {
                testTag = "extension-status-spinner"
            })
        Text(
            text,
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = color,
            modifier = Modifier.weight(1f).testTag("extension-status"),
        )
    }
}

/** 状态区展示文本：网关最多 16 行，界面只显示前 4 条，避免挤占输入区。 */
internal fun extensionStatusText(statuses: List<GatewayStatusEntry>): String? =
    statuses.asSequence()
        .map { it.text.trim() }
        .filter { it.isNotBlank() }
        .take(4)
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }
