package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import kotlin.math.roundToInt

internal sealed interface ConversationDisplayEntry {
    val id: String
    val contentType: ConversationDisplayContentType

    data class Message(val item: ConversationItem) : ConversationDisplayEntry {
        override val id: String = item.id
        override val contentType: ConversationDisplayContentType = when (item.kind) {
            ConversationItemKind.USER -> ConversationDisplayContentType.USER
            ConversationItemKind.ASSISTANT -> ConversationDisplayContentType.ASSISTANT
            ConversationItemKind.STATUS -> ConversationDisplayContentType.STATUS
            else -> ConversationDisplayContentType.SYSTEM
        }
    }

    data class Process(val group: ConversationProcessGroup) : ConversationDisplayEntry {
        override val id: String = group.id
        override val contentType: ConversationDisplayContentType =
            ConversationDisplayContentType.PROCESS
    }
}

internal enum class ConversationDisplayContentType {
    USER,
    ASSISTANT,
    ASSISTANT_HEADER,
    ASSISTANT_MARKDOWN,
    PROCESS,
    STATUS,
    SYSTEM
}

internal sealed interface ConversationTimelineEntry {
    val id: String
    val contentType: ConversationDisplayContentType
    val images: List<GatewayImageAttachment>

    data class Display(val entry: ConversationDisplayEntry) : ConversationTimelineEntry {
        override val id: String = entry.id
        override val contentType: ConversationDisplayContentType = entry.contentType
        override val images: List<GatewayImageAttachment> =
            (entry as? ConversationDisplayEntry.Message)?.item?.images.orEmpty()
    }

    data class AssistantHeader(val item: ConversationItem) : ConversationTimelineEntry {
        override val id: String = "assistant:${item.id}:header"
        override val contentType = ConversationDisplayContentType.ASSISTANT_HEADER
        override val images = item.images
    }

    data class AssistantMarkdown(
        val messageId: String,
        val blockIndex: Int,
        val markdown: String
    ) : ConversationTimelineEntry {
        override val id: String = "assistant:$messageId:markdown:$blockIndex"
        override val contentType = ConversationDisplayContentType.ASSISTANT_MARKDOWN
        override val images = emptyList<GatewayImageAttachment>()
    }
}

internal fun makeConversationTimelineEntries(
    entries: List<ConversationDisplayEntry>
): List<ConversationTimelineEntry> = buildList {
    entries.forEach { entry ->
        val item = (entry as? ConversationDisplayEntry.Message)?.item
        if (
            item?.kind != ConversationItemKind.ASSISTANT ||
            item.text.isEmpty()
        ) {
            add(ConversationTimelineEntry.Display(entry))
            return@forEach
        }

        add(ConversationTimelineEntry.AssistantHeader(item))
        splitMarkdownForLazyLayout(item.text).forEachIndexed { index, markdown ->
            add(
                ConversationTimelineEntry.AssistantMarkdown(
                    messageId = item.id,
                    blockIndex = index,
                    markdown = markdown
                )
            )
        }
    }
}

/**
 * 只在 Markdown 块边界拆分，围栏代码、表格和列表项自身不会被截断。这样 LazyColumn
 * 无需在用户气泡出现的同一帧测量整篇长回复。
 */
internal fun splitMarkdownForLazyLayout(
    markdown: String,
    targetCharacters: Int = 320
): List<String> {
    if (markdown.length <= targetCharacters) return listOf(markdown)

    val logicalBlocks = mutableListOf<String>()
    val currentBlock = StringBuilder()
    var fenceMarker: String? = null

    fun flushBlock() {
        currentBlock.toString().trimEnd().takeIf(String::isNotEmpty)?.let(logicalBlocks::add)
        currentBlock.clear()
    }

    markdown.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        val openingFence = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (openingFence != null) {
            fenceMarker = if (fenceMarker == null) openingFence else {
                fenceMarker.takeUnless { marker -> marker == openingFence }
            }
        }

        if (line.isBlank() && fenceMarker == null) {
            flushBlock()
        } else {
            if (currentBlock.isNotEmpty()) currentBlock.append('\n')
            currentBlock.append(line)
        }
    }
    flushBlock()

    if (logicalBlocks.size <= 1) return listOf(markdown)
    val chunks = mutableListOf<String>()
    val chunk = StringBuilder()
    logicalBlocks.forEach { block ->
        val separatorSize = if (chunk.isEmpty()) 0 else 2
        if (chunk.isNotEmpty() && chunk.length + separatorSize + block.length > targetCharacters) {
            chunks += chunk.toString()
            chunk.clear()
        }
        if (chunk.isNotEmpty()) chunk.append("\n\n")
        chunk.append(block)
    }
    if (chunk.isNotEmpty()) chunks += chunk.toString()
    return chunks.ifEmpty { listOf(markdown) }
}

internal data class ConversationProcessGroup(
    val id: String,
    val items: List<ConversationItem>
) {
    val contexts: List<ConversationItem> = items.filter { it.kind == ConversationItemKind.CONTEXT }

    val reasonings: List<ConversationItem> = items.filter { it.kind == ConversationItemKind.REASONING }

    val notices: List<ConversationItem> = items.filter { it.kind == ConversationItemKind.NOTICE }

    val tools: List<ConversationProcessTool> = pairProcessTools(items)

    val durationSeconds: Double
        get() {
            // epochSeconds == 0 表示时间未知（缺 timestamp 的历史消息、旧网关）。
            // 只对有时间戳的项取跨度，否则 now - 0 会算出“耗时 29821798 分钟”这种垃圾值。
            val known = items.map(ConversationItem::epochSeconds).filter { it > 0.0 }
            if (known.size < 2) return 0.0
            return (known.max() - known.min()).coerceAtLeast(0.0)
        }

    val title: String
        get() {
            if (contexts.isNotEmpty() && tools.isEmpty() && notices.isEmpty()) {
                return if (contexts.size == 1) "上下文" else "${contexts.size} 项上下文"
            }
            val base = when {
                durationSeconds >= 60.0 -> {
                    val totalSeconds = durationSeconds.toInt()
                    "耗时 ${totalSeconds / 60} 分钟 ${totalSeconds % 60} 秒"
                }
                durationSeconds >= 1.0 -> "耗时 ${durationSeconds.roundToInt()} 秒"
                else -> "思考过程"
            }
            val details = buildList {
                if (contexts.isNotEmpty()) add("${contexts.size} 项上下文")
                if (reasonings.isNotEmpty()) add("${reasonings.size} 段思考")
                if (tools.isNotEmpty()) add("${tools.size} 次工具调用")
                if (notices.isNotEmpty()) add("${notices.size} 条通知")
            }
            return if (details.isEmpty()) base else "$base · ${details.joinToString(" · ")}"
        }
}

internal data class ConversationProcessTool(
    val id: String,
    val call: ConversationItem?,
    val result: ConversationItem?
)

/** 折叠组详情的按序渲染段：保持原有事件顺序逐段展示，不按类别重排。 */
internal sealed interface ConversationProcessSegment {
    /** 段的稳定标识（ProcessDisclosure remember key 用）；取首成员 id。 */
    val id: String

    data class Reasoning(val item: ConversationItem) : ConversationProcessSegment {
        override val id: String get() = item.id
    }

    data class Context(val item: ConversationItem) : ConversationProcessSegment {
        override val id: String get() = item.id
    }

    data class Notice(val item: ConversationItem) : ConversationProcessSegment {
        override val id: String get() = item.id
    }

    /** 连续的工具调用/结果（think、context 出现即分段），段内完成配对。 */
    data class ToolRun(val tools: List<ConversationProcessTool>) : ConversationProcessSegment {
        override val id: String get() = tools.first().id
    }
}

/**
 * 思考、上下文和通知保持原序，连续工具项成段。通知可插在 call/result 之间，
 * 因此配对查找全组未完成调用（精确 ID 优先、FIFO 兜底），不能把结果拆成孤儿项。
 */
internal fun ConversationProcessGroup.orderedSegments(): List<ConversationProcessSegment> {
    val segments = mutableListOf<ConversationProcessSegment>()

    fun appendRunTool(item: ConversationItem) {
        val run = segments.lastOrNull() as? ConversationProcessSegment.ToolRun
        if (run == null) {
            segments += ConversationProcessSegment.ToolRun(
                listOf(ConversationProcessTool(id = item.id, call = item, result = null))
            )
        } else {
            segments[segments.lastIndex] =
                run.copy(tools = run.tools + ConversationProcessTool(id = item.id, call = item, result = null))
        }
    }

    fun attachResult(item: ConversationItem) {
        val exact = if (item.toolCallId == null) -1 else segments.indexOfFirst { segment ->
            segment is ConversationProcessSegment.ToolRun && segment.tools.any {
                it.result == null && it.call?.toolCallId == item.toolCallId
            }
        }
        val index = if (exact >= 0) exact else segments.indexOfFirst { segment ->
            segment is ConversationProcessSegment.ToolRun && segment.tools.any {
                it.result == null && (item.toolCallId == null || it.call?.toolCallId == null)
            }
        }
        val run = segments.getOrNull(index) as? ConversationProcessSegment.ToolRun
        val updated = run?.let { pairToolResult(it.tools, item) }
        if (run != null && updated != null) {
            segments[index] = run.copy(tools = updated)
        } else {
            segments += ConversationProcessSegment.ToolRun(
                listOf(ConversationProcessTool(id = item.id, call = null, result = item))
            )
        }
    }

    items.forEach { item ->
        when (item.kind) {
            ConversationItemKind.REASONING ->
                segments += ConversationProcessSegment.Reasoning(item)

            ConversationItemKind.CONTEXT ->
                segments += ConversationProcessSegment.Context(item)

            ConversationItemKind.NOTICE ->
                segments += ConversationProcessSegment.Notice(item)

            ConversationItemKind.TOOL,
            ConversationItemKind.JSON_TOOL -> appendRunTool(item)

            ConversationItemKind.TOOL_RESULT -> attachResult(item)

            else -> Unit
        }
    }
    return segments
}

/** 把 result 挂到未配对的调用上（精确 toolCallId 优先，FIFO 兜底）；无未配对调用返回 null。 */
private fun pairToolResult(
    tools: List<ConversationProcessTool>,
    result: ConversationItem
): List<ConversationProcessTool>? {
    val callId = result.toolCallId
    val index = callId?.let { id ->
        tools.indexOfFirst { it.result == null && it.call?.toolCallId == id }
    }?.takeIf { it >= 0 }
        // 已知ID但缺少对应start时保留孤儿结果，不能把嵌套结果误挂给父调用。
        ?: tools.indexOfFirst { it.result == null && (callId == null || it.call?.toolCallId == null) }.takeIf { it >= 0 }
        ?: return null
    val updated = tools.toMutableList()
    updated[index] = updated[index].copy(result = result)
    return updated
}

/**
 * 把相邻助手正文与步骤组成展示回合，供稳定 key、间距和时间分隔使用。
 * 分组只改变布局边界，不改变投影顺序：正文之后发生的工具/思考仍在正文之后，
 * 正文之前的思考也保留原位。通知、USER/STATUS/SYSTEM 保留原分组边界。
 */
internal fun groupAssistantRounds(
    entries: List<ConversationDisplayEntry>
): List<List<ConversationDisplayEntry>> {
    val rounds = mutableListOf<MutableList<ConversationDisplayEntry>>()

    fun isUserOrSystem(e: ConversationDisplayEntry): Boolean =
        e is ConversationDisplayEntry.Message &&
            (e.item.kind == ConversationItemKind.USER || e.item.kind == ConversationItemKind.SYSTEM)

    fun isAssistant(e: ConversationDisplayEntry): Boolean =
        e is ConversationDisplayEntry.Message && e.item.kind == ConversationItemKind.ASSISTANT

    entries.forEach { entry ->
        val last = rounds.lastOrNull()
        val joins = when (entry) {
            is ConversationDisplayEntry.Process -> last != null && !last.any(::isUserOrSystem) &&
                entry.group.notices.isEmpty() &&
                (last.all { it is ConversationDisplayEntry.Process } || isAssistant(last.last()))
            is ConversationDisplayEntry.Message ->
                isAssistant(entry) && last != null && last.all { it is ConversationDisplayEntry.Process }
            else -> false
        }
        if (joins) {
            rounds[rounds.lastIndex] = (last!! + entry).toMutableList()
        } else {
            rounds += mutableListOf(entry)
        }
    }
    return rounds.map { it.toList() }
}

/**
 * 回合在 LazyColumn 的稳定 key：回合含正文时用正文消息 id——流式中工具项并入
 * 不改变 key，item 身份（滚动锚点、可见附件上报）从正文出现起
 * 稳定；纯步骤组或单条消息回合用 "round:" + 首项 id 兜底。
 */
internal fun displayRoundKey(round: List<ConversationDisplayEntry>): String =
    round.firstOrNull { entry ->
        entry is ConversationDisplayEntry.Message &&
            entry.item.kind == ConversationItemKind.ASSISTANT
    }?.id ?: ("round:" + (round.firstOrNull()?.id ?: "empty"))

internal fun makeConversationDisplayEntries(
    items: List<ConversationItem>
): List<ConversationDisplayEntry> {
    val result = mutableListOf<ConversationDisplayEntry>()
    val processItems = mutableListOf<ConversationItem>()

    // 思考与工具同组（用户授权的紧凑方案）：连续的 thinking/工具事件合成一个
    // 外层折叠组，FineCat默认收起、可点击展开；正文（user/assistant）不受影响。
    //
    // 组 id 锚定**前驱消息 id + 同前驱下的组序数**，不锚定组成员：流式成员的 id
    // 在 finalize 时从 `stream-<turn>-<step>` 换成最终事件 id（投影先摘流式行再
    // append 最终行，两种 id 无共享子串），锚成员会让已展开组随键更换而收起；
    // 锚前驱则跨 finalize 稳定。history prepending 会改会话最老组的前驱（null →
    // 新消息）或扩大 `process::0` 组，对应组键随之更新，原展开记录不能跟随该组；
    // branch 切换时分支点前的消息共享前缀，同前驱同序数即同一批组，选择自然保留。
    var lastMessageId: String? = null
    var ordinal = 0

    fun flushProcess() {
        if (processItems.isEmpty()) return
        result += ConversationDisplayEntry.Process(
            ConversationProcessGroup(
                id = "process:${lastMessageId ?: ""}:$ordinal",
                items = processItems.toList()
            )
        )
        processItems.clear()
        ordinal += 1
    }

    items.forEach { item ->
        when (item.kind) {
            ConversationItemKind.REASONING,
            ConversationItemKind.CONTEXT,
            ConversationItemKind.TOOL,
            ConversationItemKind.JSON_TOOL,
            ConversationItemKind.NOTICE,
            ConversationItemKind.TOOL_RESULT -> processItems += item

            ConversationItemKind.USER,
            ConversationItemKind.ASSISTANT,
            ConversationItemKind.STATUS,
            ConversationItemKind.SYSTEM -> {
                flushProcess()
                lastMessageId = item.id
                ordinal = 0
                result += ConversationDisplayEntry.Message(item)
            }
        }
    }
    flushProcess()
    return result
}

private fun pairProcessTools(
    items: List<ConversationItem>
): List<ConversationProcessTool> {
    val tools = mutableListOf<ConversationProcessTool>()
    items.forEach { item ->
        when (item.kind) {
            ConversationItemKind.TOOL,
            ConversationItemKind.JSON_TOOL -> tools += ConversationProcessTool(
                id = item.id,
                call = item,
                result = null
            )

            ConversationItemKind.TOOL_RESULT -> {
                // 精确优先：toolCallId 命中未配对的调用则直接挂结果（并行工具
                // 乱序完成时不张冠李戴）；无 callId 的旧数据回退 FIFO。
                val updated = pairToolResult(tools, item)
                if (updated != null) {
                    tools.clear()
                    tools += updated
                } else {
                    tools += ConversationProcessTool(
                        id = item.id,
                        call = null,
                        result = item
                    )
                }
            }

            else -> Unit
        }
    }
    return tools
}
