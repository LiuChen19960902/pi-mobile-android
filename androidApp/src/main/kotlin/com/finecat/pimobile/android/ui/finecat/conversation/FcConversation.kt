package com.finecat.pimobile.android.ui.finecat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.ConversationProcessGroup
import com.finecat.pimobile.android.ui.ConversationProcessSegment
import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.PiSpinner
import com.finecat.pimobile.android.ui.orderedSegments
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.android.ui.finecat.components.FineCatElevation
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 对话消息的视觉件（P2）：用户灰气泡、AI 无框正文、整组步骤折叠、Working、
 * 时间分隔。数据形状见 [FcStep]/[FcChatMessage]——P4 起由 ConversationProjection
 * 适配为稳定 key 的真实消息；本层不解析 wire。
 */

/** 步骤/思考条目。`arg` 等宽语义在原型用 <em> 表达；`detail` 是展开后的输出。 */
data class FcStep(
    val id: String,
    val isThought: Boolean = false,
    val label: String,
    val arg: String = "",
    val durationText: String? = null,
    val failed: Boolean = false,
    val detail: String? = null,
    val command: String? = null,
    val completed: Boolean = false,
    val isContext: Boolean = false,
    val toolName: String? = null,
)

/** 正在生成的尾部步骤组；列表弹卡跟随此组的真实运行状态更新。 */
internal fun fineCatWorkingGroupId(entries: List<ConversationDisplayEntry>, isStreaming: Boolean): String? =
    if (isStreaming) (entries.lastOrNull {
        fineCatEntryHasVisibleBody(it) || (it is ConversationDisplayEntry.Message && it.item.isError)
    } as? ConversationDisplayEntry.Process)?.id else null

/** 在回合排序前合并没有可见正文隔开的步骤，保留空消息的定位和时间戳。 */
internal fun mergeFineCatProcessEntries(entries: List<ConversationDisplayEntry>): List<ConversationDisplayEntry> {
    val result = mutableListOf<ConversationDisplayEntry>()
    var firstProcessIndex: Int? = null
    val processItems = mutableListOf<ConversationItem>()

    fun flush() {
        firstProcessIndex?.let { index ->
            val first = result[index] as ConversationDisplayEntry.Process
            result[index] = first.copy(group = first.group.copy(items = processItems.toList()))
        }
        firstProcessIndex = null
        processItems.clear()
    }

    entries.forEach { entry ->
        when (entry) {
            is ConversationDisplayEntry.Process -> {
                if (firstProcessIndex == null) {
                    firstProcessIndex = result.size
                    result += entry
                }
                processItems += entry.group.items
            }
            is ConversationDisplayEntry.Message -> {
                val item = entry.item
                val emptyAssistant = item.kind == ConversationItemKind.ASSISTANT &&
                    item.text.isBlank() && item.images.isEmpty() && !item.isError
                if (!emptyAssistant) flush()
                result += entry
            }
        }
    }
    flush()
    return result
}

/** 保留空正文的原条目/key，但不让它消耗消息间隔。 */
internal fun fineCatEntryHasVisibleBody(entry: ConversationDisplayEntry): Boolean =
    entry !is ConversationDisplayEntry.Message ||
        entry.item.kind != ConversationItemKind.ASSISTANT ||
        entry.item.text.isNotBlank() || entry.item.images.isNotEmpty()

/** 步骤是正文之间的紧凑入口，收起、展开或运行时都只给相邻内容4dp。 */
internal fun fineCatEntryGap(
    before: ConversationDisplayEntry?,
    after: ConversationDisplayEntry?,
    regularGap: Int,
): Int {
    if (before == null) return 0
    return if (before is ConversationDisplayEntry.Process || after is ConversationDisplayEntry.Process) 4 else regularGap
}

/** 行里只放摘要，弹卡始终保留完整命令、输出和思考；空输出与尚无结果分别表示。 */
internal fun fineCatSteps(group: ConversationProcessGroup): List<FcStep> = buildList {
    var thoughtIndex = 0
    group.orderedSegments().forEach { segment ->
        when (segment) {
            is ConversationProcessSegment.Reasoning -> add(FcStep(
                // 流式思考落定会更换item.id；同一稳定步骤组内的思考顺序不变。
                id = "${group.id}:thought:${thoughtIndex++}",
                isThought = true, label = "深度思考", detail = segment.item.text,
            ))
            is ConversationProcessSegment.Context -> add(FcStep(
                id = segment.id, label = "上下文", arg = segment.item.text.take(80),
                detail = segment.item.text, isContext = true,
            ))
            is ConversationProcessSegment.ToolRun -> segment.tools.forEach { tool ->
                val call = tool.call
                val result = tool.result
                val seconds = if (call != null && result != null && call.epochSeconds > 0 && result.epochSeconds > 0) {
                    (result.epochSeconds - call.epochSeconds).coerceAtLeast(0.0)
                } else 0.0
                val summary = listOfNotNull(call?.title, call?.text?.takeIf(String::isNotBlank)).joinToString(" · ")
                add(FcStep(
                    id = tool.id, label = "执行命令",
                    arg = if (summary.length > 80) summary.take(80) + "…" else summary,
                    durationText = if (seconds >= 0.1) "%.1fs".format(seconds) else null,
                    failed = call?.isError == true || result?.isError == true,
                    command = call?.text, detail = result?.text, completed = result != null,
                    toolName = call?.title?.takeIf(String::isNotBlank),
                ))
            }
        }
    }
}

/** 对话消息（fixture/测试形状，字段名对齐原型 msgBlock 的输入）。 */
data class FcChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String = "",
    val steps: List<FcStep> = emptyList(),
    /** 有值时在该条消息上方插入居中时间分隔行。 */
    val timeSeparator: String? = null,
    /** 纯图消息：占位色块数量（真实图片显示在 P4c）。 */
    val imageCount: Int = 0,
)

/**
 * 用户消息：HTML参考的黑底白字、15sp、最大85%宽，右下角小圆角。
 * 图片格：真实缩略图来自门面附件链路（attachmentThumbnails），加载中/失败态可见。
 */
@Composable
fun FcUserBubble(
    message: FcChatMessage,
    modifier: Modifier = Modifier,
    attachments: List<com.finecat.pimobile.shared.protocol.GatewayImageAttachment> = emptyList(),
    thumbnails: Map<String, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
    imageStates: Map<String, com.finecat.pimobile.android.AttachmentLoadState> = emptyMap(),
    onRetryImage: (String) -> Unit = {},
    onOpenImage: (List<com.finecat.pimobile.shared.protocol.GatewayImageAttachment>, Int) -> Unit = { _, _ -> },
    onCopy: ((String) -> Unit)? = null,
    copied: Boolean = false,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val bubbleShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val bubbleMaxWidth = maxWidth * 0.85f
        // 从外层约束取上限；图片容器随内容收缩，不能用 fillMaxWidth 撑开气泡。
        val imageMaxWidth = (bubbleMaxWidth - 28.dp).coerceAtLeast(1.dp)
        Column(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth)
                .testTag("finecat.userBubble.${message.id}")
                .clip(bubbleShape)
                .background(Color(0xFF111111))
                .then(if (colors.isDark) Modifier.border(0.5.dp, colors.line2, bubbleShape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (attachments.isEmpty() && message.imageCount > 0) {
                // fixture/占位路径：无真实附件时按 imageCount 画占位格（debug demo）
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(message.imageCount) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surface3),
                        )
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).testTag("finecat.userImages.${message.id}"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        val state = imageStates[attachment.attachmentId]
                        val thumb = thumbnails[attachment.attachmentId]
                        val size = if (attachment.width > 0 && attachment.height > 0) {
                            fineCatImageSize(attachment.width, attachment.height, imageMaxWidth, 96.dp)
                        } else {
                            fineCatImageSize(thumb?.width ?: 0, thumb?.height ?: 0, imageMaxWidth, 96.dp)
                        }
                        val retryable = state == com.finecat.pimobile.android.AttachmentLoadState.FAILED ||
                            state == com.finecat.pimobile.android.AttachmentLoadState.DEFERRED
                        Box(
                            modifier = Modifier
                                .size(width = maxOf(size.width, 48.dp), height = maxOf(size.height, 48.dp))
                                .clickable(
                                    interactionSource = remember(attachment.attachmentId) { MutableInteractionSource() },
                                    indication = null, enabled = thumb != null || retryable, role = Role.Button,
                                ) {
                                    if (thumb != null) onOpenImage(attachments, index) else onRetryImage(attachment.attachmentId)
                                }
                                .semantics {
                                    contentDescription = if (thumb != null) "放大查看 ${attachment.name ?: "图片 ${index + 1}"}" else "图片 ${index + 1}，${if (retryable) "重试" else "加载中"}"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(colors.surface3),
                                contentAlignment = Alignment.Center,
                            ) {
                            if (thumb != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = thumb,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.matchParentSize(),
                                )
                            } else {
                                // FAILED 与 DEFERRED 都给重试（旧 UI 同口径）
                                val label = when (state) {
                                    com.finecat.pimobile.android.AttachmentLoadState.FAILED,
                                    com.finecat.pimobile.android.AttachmentLoadState.DEFERRED,
                                    -> "重试"
                                    com.finecat.pimobile.android.AttachmentLoadState.LOADING -> "…"
                                    else -> "图片"
                                }
                                Text(
                                    text = label,
                                    style = LocalFineCatType.current.xs2,
                                    color = if (retryable) colors.err else colors.text3,
                                )
                            }
                            }
                        }
                    }
                }
            }
            if (message.text.isNotEmpty()) {
                if (message.imageCount > 0 || attachments.isNotEmpty()) Spacer(modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                        Text(text = message.text, style = type.bubble.copy(lineHeight = 21.75.sp), color = Color.White,
                            modifier = Modifier.testTag("finecat.userText.${message.id}"))
                    }
                    if (onCopy != null && message.text.isNotBlank()) {
                        CompositionLocalProvider(LocalFineCatColors provides colors.copy(text = Color.White)) {
                        FcIconButton(
                            onClick = { onCopy(message.text) },
                            icon = if (copied) FineCatIcons.Check else FineCatIcons.Copy,
                            contentDescription = if (copied) "已复制" else "复制全文",
                            buttonSize = 24.dp,
                            iconSize = 15.dp,
                            modifier = Modifier.padding(start = 4.dp).testTag("finecat.copy.${message.id}"),
                        )
                        }
                    }
                }
            }
        }
    }
}

/** 居中时间分隔行（.tsep），有值才插一行，平时不显示时间戳。 */
@Composable
fun FcTimeSeparator(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    Text(
        text = text,
        style = type.xs,
        color = colors.text3,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.s5, bottom = spacing.s6)
            .semantics { },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** 摘要只取已知参数，完整原文仍留在详情；流式半截JSON不作为已解析参数。 */
internal fun fineCatStepSummary(step: FcStep): Pair<String, String> {
    fun singleLine(text: String) = text.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" ")
    if (step.isThought) return "思考" to singleLine(step.detail.orEmpty()).take(120)
    if (step.isContext) return step.label to singleLine(step.arg).take(120)
    val args = step.command?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    fun argument(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        (args?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
    val verb = when (step.toolName?.lowercase()) {
        "read" -> "读取"
        "write" -> "写入"
        "edit" -> "修改"
        "ls" -> "列出"
        "find" -> "查找"
        "grep" -> "搜索"
        "bash", "run_code" -> "执行"
        else -> "使用"
    }
    val action = when {
        step.failed -> "${verb}失败"
        step.completed -> "${verb}了"
        else -> "正在$verb"
    }
    val path = argument("path", "filePath", "file_path")
    val target = when (step.toolName?.lowercase()) {
        "read", "write", "edit", "ls" -> path.orEmpty()
        "find", "grep" -> listOfNotNull(argument("pattern", "query"), path).joinToString(" · ")
        "bash", "run_code" -> argument("command", "code") ?: step.command?.takeIf { args == null && !it.trimStart().startsWith("{") }.orEmpty()
        else -> listOfNotNull(step.toolName, path).joinToString(" · ")
    }
    return action to singleLine(target).take(160)
}

/** 运行中或历史结果缺失时不声称操作已全部完成。 */
internal fun fineCatStepsSummaryPrefix(steps: List<FcStep>, isRunning: Boolean): String = when {
    isRunning -> "正在执行 "
    steps.any { !it.isThought && !it.isContext && !it.completed && !it.failed } -> ""
    else -> "完成了 "
}

/** HTML参考的低权重步骤行；无状态色块，失败项加深，点击仍可看完整详情。 */
@Composable
fun FcStepRow(
    step: FcStep,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val muted = if (colors.isDark) colors.text3 else Color(0xFF999999)
    val objectColor = if (colors.isDark) colors.text2 else Color(0xFF666666)
    val emphasis = if (colors.isDark) colors.text else Color(0xFF111111)
    val summary = remember(step) { fineCatStepSummary(step) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f,
        tween(250, easing = FastOutSlowInEasing), label = "StepChevron")
    Row(
        modifier = modifier.fillMaxWidth().testTag("finecat.step.${step.id}")
            .semantics {
                stateDescription = if (expanded) "已展开" else "已折叠"
                if (!step.isThought && !step.isContext) contentDescription = when {
                    step.failed -> "失败"
                    step.completed -> "成功"
                    else -> "等待结果"
                }
            }
            .clickable(interactionSource = remember(step.id) { MutableInteractionSource() },
                indication = null, role = Role.Button, onClick = onOpen)
            .defaultMinSize(minHeight = 28.dp).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            buildAnnotatedString {
                append(summary.first)
                if (summary.second.isNotEmpty()) {
                    append(" ")
                    withStyle(SpanStyle(color = if (step.failed) emphasis else objectColor)) { append(summary.second) }
                }
            },
            style = type.sm.copy(fontSize = 13.sp, lineHeight = 18.2.sp,
                fontWeight = if (step.failed) FontWeight.Medium else FontWeight.Normal),
            color = if (step.failed) emphasis else muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(FineCatIcons.Right, contentDescription = null,
            tint = if (colors.isDark) colors.text3 else Color(0xFFCCCCCC),
            modifier = Modifier.size(11.dp).rotate(rotation))
    }
}

/** 原行下面展开完整内容；单项详情最多240dp，命令/结果共用一个内部滚动区域。 */
@Composable
private fun FcStepDetail(step: FcStep, modifier: Modifier = Modifier) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    // 内部先正常滚动，再消耗边缘剩余位移/速度；不把同一手势续接到会话。
    val scrollBoundary = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                Offset(0f, available.y)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
                Velocity(0f, available.y)
        }
    }
    Column(
        modifier.fillMaxWidth()
            .heightIn(max = 240.dp)
            .then(if (step.isThought) Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface2)
                .drawBehind {
                    drawLine(colors.line2,
                        Offset(1.dp.toPx(), 8.dp.toPx()),
                        Offset(1.dp.toPx(), size.height - 8.dp.toPx()),
                        strokeWidth = 2.dp.toPx())
                }
            else Modifier)
            .testTag("finecat.stepDetailBody")
            .nestedScroll(scrollBoundary)
            .verticalScroll(rememberScrollState())
            .then(if (step.isThought) Modifier
                .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        step.durationText?.let { Text(it, style = type.xs, color = colors.text3) }
        if (step.isThought || step.isContext) {
            SelectionContainer(
                modifier = if (step.isThought) Modifier
                    .fillMaxWidth()
                    .testTag("finecat.thoughtDetail")
                else Modifier,
            ) {
                Text(step.detail.orEmpty(),
                    style = if (step.isThought) type.xs.copy(lineHeight = type.xs.fontSize * 1.7f) else type.body,
                    color = colors.text2,
                    modifier = Modifier.testTag("finecat.stepDetailText"))
            }
        } else {
            Text("命令", style = type.xs.copy(fontWeight = FontWeight.Medium), color = colors.text3)
            SelectionContainer {
                FcCodeBlock(
                    step.command?.takeIf(String::isNotEmpty) ?: "（历史记录未包含命令或参数）",
                    modifier = Modifier.fillMaxWidth().testTag("finecat.stepDetailCommand"),
                    constrainHeight = false,
                )
            }
            Text(
                if (step.failed) "结果 · 失败" else "结果",
                style = type.xs.copy(fontWeight = FontWeight.Medium),
                color = if (step.failed) colors.err else colors.text3,
            )
            SelectionContainer {
                FcCodeBlock(
                    step.detail?.takeIf(String::isNotEmpty) ?: if (step.completed) "（无输出）" else "（暂无输出）",
                    modifier = Modifier.fillMaxWidth().testTag("finecat.stepDetailOutput"),
                    constrainHeight = false,
                )
            }
        }
    }
}

/** 输出代码块（.code）：等宽 12sp、lh 1.7、surface-2 底、max 高 168 可滚。 */
@Composable
fun FcCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    constrainHeight: Boolean = true,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val radius = LocalFineCatRadius.current
    val spacing = LocalFineCatSpacing.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(radius.chip))
            .background(colors.surface2)
            .padding(horizontal = spacing.s4, vertical = spacing.s3),
    ) {
        Text(
            text = text,
            style = type.xs.copy(fontFamily = type.mono, lineHeight = type.xs.fontSize * 1.7f),
            color = colors.text2,
            modifier = Modifier
                .then(if (constrainHeight) Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()) else Modifier),
        )
    }
}

/** HTML参考的摘要及内联列表；步骤列表随主对话滚动，单项详情独立封顶。 */
@Composable
fun FcStepsBlock(
    messageId: String,
    steps: List<FcStep>,
    onOpenGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    expandedStepId: String? = null,
    onOpenStep: (String) -> Unit = {},
    isRunning: Boolean = false,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val muted = if (colors.isDark) colors.text3 else Color(0xFF999999)
    val emphasis = if (colors.isDark) colors.text else Color(0xFF111111)
    val lineColor = if (colors.isDark) colors.line2 else Color(0xFFEEEEEE)
    val rotation by animateFloatAsState(if (expanded) 90f else 0f,
        tween(250, easing = FastOutSlowInEasing), label = "StepsChevron")
    val viewConfiguration = LocalViewConfiguration.current
    val compactConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize(48.dp, 28.dp)
        }
    }
    Column(modifier.fillMaxWidth().testTag("finecat.steps.$messageId")) {
        CompositionLocalProvider(LocalViewConfiguration provides compactConfiguration) {
            Row(
                Modifier.fillMaxWidth().testTag("finecat.stepsHeader.$messageId")
                    .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                    .clickable(interactionSource = remember(messageId) { MutableInteractionSource() },
                        indication = null, role = Role.Button) { onOpenGroup(messageId) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(FineCatIcons.Right, contentDescription = null,
                    tint = if (colors.isDark) colors.text3 else Color(0xFFCCCCCC),
                    modifier = Modifier.size(11.dp).rotate(rotation))
                Text(buildAnnotatedString {
                    append(fineCatStepsSummaryPrefix(steps, isRunning))
                    withStyle(SpanStyle(color = emphasis, fontWeight = FontWeight.Medium)) { append("${steps.size} 个操作") }
                    val failures = steps.count { it.failed }
                    if (failures > 0) {
                        append(" · ")
                        withStyle(SpanStyle(color = emphasis, fontWeight = FontWeight.Medium)) { append("$failures 个失败") }
                    }
                }, style = type.sm.copy(fontSize = 13.sp, lineHeight = 18.2.sp), color = muted,
                    modifier = Modifier.weight(1f))
            }
        }
        AnimatedVisibility(visible = expanded,
            enter = expandVertically(tween(320, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(320, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
        ) {
            Column(Modifier.fillMaxWidth().testTag("finecat.stepsList.$messageId")
                .drawBehind { drawLine(lineColor, Offset(4.dp.toPx(), 0f), Offset(4.dp.toPx(), size.height), 1.5.dp.toPx()) }
                .padding(start = 18.dp, bottom = 4.dp)) {
                steps.forEach { step ->
                    androidx.compose.runtime.key(step.id) {
                        Column(Modifier.fillMaxWidth().animateContentSize(tween(250, easing = FastOutSlowInEasing))) {
                            CompositionLocalProvider(LocalViewConfiguration provides compactConfiguration) {
                                FcStepRow(step, onOpen = { onOpenStep(step.id) }, expanded = expandedStepId == step.id)
                            }
                            if (expandedStepId == step.id) FcStepDetail(step,
                                Modifier.padding(top = 4.dp, bottom = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 助手消息（原型 `.bubble.ai` + `.icopy`）：Markdown 正文贴在画布上、不套气泡，
 * 末尾一个复制全文按钮。短段落按内容宽度排版，让按钮紧跟文字；正文走生产 Markdown 渲染，
 * 复制语义回调由调用方实现，
 * 本层不直接触碰剪贴板。
 *
 * 复制反馈（原型 app.js copy：图标变勾 1200ms 后恢复）：copied=true 时图标换
 * Check、语义描述变「已复制」；定时翻转由调用方驱动（测试用受控时钟验证）。
 * 底部间距归组装层单一来源（原型 .steps/.msg margin 分层），本组件不再自加
 * bottom padding（F02 前 16dp + 组装层 24dp 叠加成 40dp）。
 */
@Composable
fun FcAiMessage(
    message: FcChatMessage,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    copied: Boolean = false,
) {
    val colors = LocalFineCatColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("finecat.ai.${message.id}"),
    ) {
        FcMarkdownText(
            markdown = message.text,
            modifier = Modifier.fillMaxWidth().testTag("finecat.prose.${message.id}"),
        )
        // 复制放正文下方，避免整段文字因同排按钮额外损失28dp宽度。
        if (message.text.isNotBlank()) FcIconButton(
            onClick = { onCopy(message.text) },
            icon = if (copied) FineCatIcons.Check else FineCatIcons.Copy,
            contentDescription = if (copied) "已复制" else "复制全文",
            buttonSize = 24.dp,
            iconSize = 15.dp,
            modifier = Modifier
                .align(Alignment.End)
                .testTag("finecat.copy.${message.id}"),
        )
    }
}

/** 运行指示：Pi盲文点阵 + 文案；结束由业务状态决定，不靠动画计时。 */
@Composable
fun FcWorking(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    Row(
        modifier = modifier.padding(top = spacing.s1, bottom = spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        PiSpinner(color = colors.text3, fontSize = type.sm.fontSize,
            modifier = Modifier.widthIn(min = 16.dp).testTag("finecat.workingSpinner"))
        Text(
            text = text,
            style = type.sm,
            color = colors.text3,
            modifier = Modifier.testTag("finecat.workingText"),
        )
    }
}

/** 悬浮"跳最新"（.jump）：40×40 圆，surface 底 + sh-2；显隐由调用方滚动状态决定。 */
@Composable
fun FcJumpButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    Box(
        modifier = modifier
            .size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(FineCatElevation.Raised, CircleShape, ambientColor = colors.shadow, spotColor = colors.shadow)
                .background(colors.surface, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = "跳到最新" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FineCatIcons.ArrowDown,
                contentDescription = null,
                tint = colors.text,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
