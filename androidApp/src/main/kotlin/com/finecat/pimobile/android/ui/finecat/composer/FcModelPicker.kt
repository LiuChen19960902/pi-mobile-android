package com.finecat.pimobile.android.ui.finecat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.protocol.GatewayModelCatalog
import com.finecat.pimobile.shared.protocol.GatewayModelRef
import kotlinx.coroutines.launch

/** 全宽底部模型面板；选择即时生效，列表独立滚动、思考档位留在底部。 */
@Composable
internal fun FcModelPicker(
    catalog: GatewayModelCatalog?,
    current: GatewayModelRef?,
    currentThinking: String?,
    onSelect: (String, String, String?) -> Unit,
    onDismiss: () -> Unit,
    emptyText: String = "暂无可用模型",
    onRetry: (() -> Unit)? = null,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val groups = catalog?.groups.orEmpty().filter { it.models.isNotEmpty() }
    val currentModel = groups.firstOrNull { it.provider == current?.provider }
        ?.models?.firstOrNull { it.id == current?.modelId }
    val initialSelectionRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var initialSelectionShown by remember { mutableStateOf(false) }
    FcSheet(visible = true, onDismiss = onDismiss, bottomPadding = 0.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            val footerMaxHeight = maxHeight * 0.4f
            Column(Modifier.fillMaxSize().testTag("finecat.modelSheet")
                .semantics { paneTitle = "选择模型" }) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp)
                    .testTag("finecat.modelHeader")) {
                    Text("选择模型", style = type.title.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp, vertical = 14.dp))
                    Box(Modifier.align(Alignment.CenterEnd).size(48.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() },
                            indication = null, role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = "关闭模型选择" }
                        .testTag("finecat.modelSheetClose"), contentAlignment = Alignment.Center) {
                        Icon(FineCatIcons.X, contentDescription = null,
                            tint = colors.ink, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp).testTag("finecat.modelList")) {
                    if (groups.isEmpty()) {
                        Text(emptyText, style = type.sm, color = colors.text3,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
                        if (onRetry != null) androidx.compose.material3.TextButton(
                            onClick = onRetry, modifier = Modifier.padding(horizontal = 16.dp),
                        ) { Text("重试") }
                    }
                    groups.forEach { group ->
                        group.models.forEach { model ->
                            key(group.provider, model.id) {
                                val isCurrent = current?.provider == group.provider && current.modelId == model.id
                                val label = model.name.ifBlank { model.id }
                                val interaction = remember { MutableInteractionSource() }
                                val pressed by interaction.collectIsPressedAsState()
                                val labelStyle = type.body.copy(fontSize = 17.sp, lineHeight = 24.sp,
                                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal)
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                                        // 当前行可按住读长名，但不以null重发模型、重置档位。
                                        if (!isCurrent) onSelect(group.provider, model.id, null)
                                    }
                                    .semantics { selected = isCurrent }
                                    .testTag("finecat.model.${group.provider}.${model.id}")
                                    .then(if (isCurrent) Modifier
                                        .bringIntoViewRequester(initialSelectionRequester)
                                        .onGloballyPositioned {
                                            // 首次定位等真实布局完成；后续选择/刷新不抢用户滚动。
                                            if (!initialSelectionShown) {
                                                initialSelectionShown = true
                                                scope.launch { initialSelectionRequester.bringIntoView() }
                                            }
                                        } else Modifier)
                                    .padding(horizontal = 24.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (isCurrent) {
                                            FcMarqueeLabel(AnnotatedString(label), style = labelStyle,
                                                color = colors.text, pressed = pressed,
                                                modifier = Modifier.fillMaxWidth()
                                                    .testTag("finecat.modelLabel.${group.provider}.${model.id}"))
                                        } else {
                                            Text(label, style = labelStyle, color = colors.text,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.testTag("finecat.modelLabel.${group.provider}.${model.id}"))
                                        }
                                        Text(group.provider, style = type.xs, color = colors.text3,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                                .testTag("finecat.modelProvider.${group.provider}.${model.id}"))
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                        if (isCurrent) Icon(FineCatIcons.Check, contentDescription = null,
                                            tint = colors.ink, modifier = Modifier.size(20.dp)
                                                .testTag("finecat.modelCheck.${group.provider}.${model.id}"))
                                    }
                                }
                            }
                        }
                    }
                }
                if (current != null && currentModel?.thinkingLevels?.isNotEmpty() == true) {
                    // 大字号/短窗口限制底栏占比；其余空间留给独立滚动的模型列表。
                    Column(Modifier.fillMaxWidth().heightIn(max = footerMaxHeight)
                        .verticalScroll(rememberScrollState()).testTag("finecat.modelFooter")) {
                        Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(colors.line))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.heightIn(min = 48.dp).padding(end = 4.dp), contentAlignment = Alignment.Center) {
                                Text("思考", style = type.xs, color = colors.text3, maxLines = 1)
                            }
                            currentModel.thinkingLevels.forEach { level ->
                                val levelOn = currentThinking == level
                                val interaction = remember { MutableInteractionSource() }
                                Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                                        onSelect(current.provider, current.modelId, level)
                                    }
                                    .semantics { selected = levelOn }.testTag("finecat.thinking.$level"),
                                    contentAlignment = Alignment.Center) {
                                    Box(Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(8.dp))
                                        .then(if (levelOn) Modifier.background(colors.ink) else Modifier)
                                        .testTag("finecat.thinkingVisual.$level")
                                        .padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                                        Text(if (level == "medium") "mid" else level,
                                            style = type.xs.copy(fontWeight = if (levelOn) FontWeight.Medium else FontWeight.Normal),
                                            color = if (levelOn) colors.onInk else colors.text3,
                                            maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
