package com.finecat.pimobile.android.ui.finecat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

data class InputAssistRow(val label: String, val description: String? = null, val file: Boolean = false,
    val directory: Boolean = false)

/** 零高度锚点上的独立非聚焦 Popup，不挤压会话、不抢 IME。 */
@Composable
fun FcInputAssistPanel(
    width: Dp,
    availableHeight: Dp,
    token: InputAssistToken,
    rows: List<InputAssistRow>,
    selectedIndex: Int,
    notice: String?,
    previewIncomplete: Boolean,
    onAccept: (Int) -> Unit,
    onParent: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val shape = RoundedCornerShape(12.dp)
    val showPreview = previewIncomplete && !token.isFile && availableHeight >= 98.dp
    val visibleRows = ((availableHeight.value - 36f - if (showPreview) 24f else 0f) / 38f).toInt().coerceIn(0, 5)
    if (availableHeight < 36.dp) return
    val listState = rememberLazyListState()
    LaunchedEffect(token.text, selectedIndex, rows.size) {
        if (selectedIndex in rows.indices) listState.scrollToItem(selectedIndex)
    }
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                IntOffset(anchorBounds.left, (anchorBounds.top - popupContentSize.height).coerceAtLeast(0))
        },
        properties = PopupProperties(focusable = false, dismissOnBackPress = false,
            dismissOnClickOutside = false, clippingEnabled = true),
    ) {
        Column(Modifier.width(width)
            .dropShadow(shape, Shadow(radius = 12.dp, spread = 0.dp,
                color = colors.shadow.copy(alpha = if (colors.isDark) 0.28f else 0.10f), offset = DpOffset(0.dp, 3.dp)))
            .clip(shape).background(colors.surface2).testTag("finecat.inputAssist")) {
            val path = inputAssistPath(token.text)
            Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (token.isFile && path.directory.isNotEmpty()) {
                    Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = onParent).testTag("finecat.inputAssist.parent"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(FineCatIcons.Back, "上一级", tint = colors.text3, modifier = Modifier.size(14.dp))
                        val lastStart = path.directory.trimEnd('/').lastIndexOf('/') + 1
                        val breadcrumb = buildAnnotatedString {
                            withStyle(SpanStyle(color = colors.text3)) { append("@" + path.directory.take(lastStart)) }
                            append(path.directory.drop(lastStart))
                        }
                        Text(breadcrumb, style = type.xs.copy(fontFamily = FontFamily.Monospace),
                            color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (path.filter.isNotEmpty()) Text(" ${path.filter}", style = type.xs.copy(fontFamily = FontFamily.Monospace),
                            color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                        Icon(FineCatIcons.Right, null, tint = colors.text3, modifier = Modifier.size(12.dp))
                    }
                } else Text(token.text, style = type.xs.copy(fontFamily = FontFamily.Monospace), color = colors.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(rows.size.toString(), style = type.xs2, color = colors.text3)
            }
            if (rows.isEmpty() && visibleRows > 0) {
                Text(notice ?: "没有匹配", style = type.xs, color = colors.text3,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onRetry)
                        .padding(horizontal = 16.dp, vertical = 12.dp).testTag("finecat.inputAssist.empty"))
            } else if (visibleRows > 0) LazyColumn(Modifier.fillMaxWidth().heightIn(max = (38 * visibleRows).dp), state = listState) {
                itemsIndexed(rows) { index, row ->
                    val selected = index == selectedIndex
                    val tint = if (selected) colors.brand else if (row.file) colors.text2 else colors.text
                    Row(Modifier.fillMaxWidth().height(38.dp)
                        .semantics { this.selected = selected }
                        .clickable(role = Role.Button, onClick = { onAccept(index) })
                        .padding(start = if (row.file) 28.dp else 16.dp, end = 16.dp)
                        .testTag("finecat.inputAssist.row.$index"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (row.directory) FineCatIcons.Folder else if (row.file) FineCatIcons.File else FineCatIcons.Book,
                            null, tint = if (selected) colors.brand else colors.text3, modifier = Modifier.size(15.dp))
                        Text(row.label, style = type.sm.copy(fontFamily = FontFamily.Monospace,
                            fontWeight = if (selected) FontWeight(550) else FontWeight.Normal),
                            color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        row.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = type.xs, color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 100.dp))
                        }
                        Icon(FineCatIcons.Right, null, tint = colors.text3, modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (showPreview) Box(Modifier.height(24.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("预览 · 会话创建后刷新", style = type.xs2, color = colors.text3)
            }
        }
    }
}
