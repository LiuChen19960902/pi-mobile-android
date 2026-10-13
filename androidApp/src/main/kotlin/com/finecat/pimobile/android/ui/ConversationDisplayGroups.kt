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
            if (contexts.isNotEmpty() && tools.isEmpty()) {
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

    /** 连续的工具调用/结果（think、context 出现即分段），段内完成配对。 */
    data class ToolRun(val tools: List<ConversationProcessTool>) : ConversationProcessSegment {
        override val id: String get() = tools.first().id
    }
}

/**
 * 组内按原事件顺序切分渲染段：REASONING/CONTEXT 单项成段，连续工具项聚成
 * ToolRun 并在段内配对（toolCallId 精确优先，FIFO 兜底，孤儿 result 单行）。
 * pi 事件序下 result 紧随其 call（中间只有会被 finalize 摘除的流式行），段内
 * 配对与全组配对等价；跨段的孤儿 result 防御性落在本段末尾为无 call 行，
 * 不前挂、不丢弃。
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
        val run = segments.lastOrNull() as? ConversationProcessSegment.ToolRun
        val updated = run?.let { pairToolResult(it.tools, item) }
        if (run != null && updated != null) {
            segments[segments.lastIndex] = run.copy(tools = updated)
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
        ?: tools.indexOfFirst { it.result == null }.takeIf { it >= 0 }
        ?: return null
    val updated = tools.toMutableList()
    updated[index] = updated[index].copy(result = result)
    return updated
}

// Agent 卡片（pi-subagents）的未完成下标 → entry 下标，用于 FIFO 配对 result。
// pi-subagents 的 Agent 调用与普通工具同进折叠区（结果入流）；运行中的
// 动效由 runningAgentTools() 单独提取，常驻输入框上方（kimi swarm 的
// aboveEditor widget 语义：运行中不随对话滚走，完成后消失）。

/**
 * 助手回合分组（F02，原型 msgBlock 结构 app.js:187-192）：时间分隔在回合外顶部，
 * 回合 = `.msg ai` [步骤组 --margin 12dp-- 正文+复制]，消息（回合）间 = `.msg`
 * margin-bottom 24dp。
 *
 * 归并规则（对照投影数据序：正文 ASSISTANT 先于其工具项 TOOL，原型视觉是
 * 步骤组在上、正文在下，组内输出按此排序）：
 * - 工具步骤项并入上一回合（末项为正文、或纯步骤组等待正文）；
 * - 助手正文并入上一「纯步骤组」（组成回合）；否则自成回合；
 * - USER/STATUS/SYSTEM 结束回合。
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
    // 组内视觉序：步骤组在前、正文在后（原型 .msg 内 steps → prose）
    return rounds.map { round ->
        round.sortedBy { if (it is ConversationDisplayEntry.Message) 1 else 0 }
    }
}

/**
 * 回合在 LazyColumn 的稳定 key：回合含正文时用正文消息 id——流式中工具项并入、
 * 组内排序调整都不会改变 key，item 身份（滚动锚点、可见附件上报）从正文出现起
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

/**
 * 当前仍在运行的 Agent 调用（toolCallId 有 call、无 result）：常驻输入框
 * 上方条的数据源。只关心 Agent（title == "Agent"）；普通工具的 result
 * 按 callId 精确摘除，不受影响。
 *
 * `Agent` 工具**不是 pi 原生**（pi 内置工具只有 bash/read/write/edit/ls/grep/
 * find/powershell），它来自 pi-subagents 一类扩展：未安装此类扩展时本函数恒返回空
 * 列表、条不显示（静默失效，不产生假条）。这类扩展又是**异步派发**——toolCall 后
 * 立即返回句柄（本机实测 toolCall→toolResult 中位数 18ms），因此"有 call 无 result"
 * 只在同步等待型调用上短暂成立，条的可见性取决于扩展实现，不是稳定契约。
 */
internal fun runningAgentTools(
    items: List<ConversationItem>,
    nowEpochSeconds: Double = System.currentTimeMillis() / 1000.0
): List<ConversationProcessTool> {
    val awaiting = LinkedHashMap<String, ConversationItem>()
    items.forEach { item ->
        when (item.kind) {
            ConversationItemKind.TOOL, ConversationItemKind.JSON_TOOL -> {
                val cid = item.toolCallId
                if (item.title == "Agent" && cid != null) awaiting[cid] = item
            }
            ConversationItemKind.TOOL_RESULT -> {
                val cid = item.toolCallId
                if (cid != null) awaiting.remove(cid)
            }
            else -> Unit
        }
    }
    // 兜底：pi 进程被 kill -9 等崩溃会留下无 result 的 Agent toolCall，重开
    // App 后按"有 call 无 result"判定会永久钉住假"运行中"条（秒数无限
    // 增长）。超过 30 分钟（swarm 任务远短于此）的残留调用不再显示。
    return awaiting.values
        .filter { nowEpochSeconds - it.epochSeconds < 1800.0 }
        .map { ConversationProcessTool(id = it.id, call = it, result = null) }
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
