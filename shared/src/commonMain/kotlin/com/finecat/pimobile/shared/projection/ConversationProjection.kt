package com.finecat.pimobile.shared.projection

import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import com.finecat.pimobile.shared.protocol.SessionEvent
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationItemKind { USER, CONTEXT, ASSISTANT, REASONING, TOOL, JSON_TOOL, TOOL_RESULT, STATUS, SYSTEM }

@Serializable
data class ConversationProjectionLabels(
    val userMessage: String = "You",
    /** 已包含末尾分隔符，例如 `Context · `；事件 source 会在 KMP 内追加。 */
    val context: String = "Context · ",
    val streamingAssistant: String = "Pi",
    val streamingReasoning: String = "Think",
    val finalAssistant: String = "Pi",
    val finalReasoning: String = "Think",
    val assemblingTool: String = "Tool",
    val toolResultDone: String = "Tool result",
    val toolResultFailed: String = "Tool failed",
    val upstreamNotice: String = "模型未回复",
    val compactionDone: String = "上下文已压缩",
    val compactionNotice: String = "上下文压缩"
)

@Serializable
data class ConversationItem(
    val id: String,
    val kind: ConversationItemKind,
    val title: String,
    val text: String,
    val images: List<GatewayImageAttachment> = emptyList(),
    val isError: Boolean = false,
    val epochSeconds: Double,
    /** pi 的 toolCallId：tool/call 与 tool/result 精确配对用；旧数据/流式前为 null。 */
    val toolCallId: String? = null
)

/** 高频 token 流只发送有序操作，不重复发送已经累计的完整消息文本。 */
@Serializable
data class ConversationProjectionOperation(
    val kind: String,
    val item: ConversationItem? = null,
    val itemId: String? = null,
    val delta: String? = null,
    val epochSeconds: Double? = null
)

/** ConversationProjector 的不可变快照（快照/回滚用）。 */
data class ConversationProjectorSnapshot(
    val items: List<ConversationItem>,
    val streamIndexes: Map<String, Int>,
    val finalizedKeys: Set<String>,
    val lastErrorNoticeText: String?,
    val lastSequence: Int
)

class ConversationProjector(
    private val labels: ConversationProjectionLabels = ConversationProjectionLabels()
) {
    private val mutableItems = mutableListOf<ConversationItem>()
    private val streamIndexes = mutableMapOf<String, Int>()
    private val finalizedKeys = mutableSetOf<String>()
    /** 同一轮重试可能同时出现原生 assistant error 与扩展 notice；相同文案只保留一条。 */
    private var lastErrorNoticeText: String? = null

    val items: List<ConversationItem> get() = mutableItems.toList()
    var lastSequence: Int = -1
        private set

    /** 快照：receiveEvent 先在投影器上试算 patch，验证可序列化后再提交。 */
    fun snapshot(): ConversationProjectorSnapshot = ConversationProjectorSnapshot(
        items = mutableItems.toList(),
        streamIndexes = streamIndexes.toMap(),
        finalizedKeys = finalizedKeys.toSet(),
        lastErrorNoticeText = lastErrorNoticeText,
        lastSequence = lastSequence
    )

    /** 回滚到快照：patch 序列化失败时用，保证镜像与投影器不发散。 */
    fun restore(snapshot: ConversationProjectorSnapshot) {
        mutableItems.clear()
        mutableItems.addAll(snapshot.items)
        streamIndexes.clear()
        streamIndexes.putAll(snapshot.streamIndexes)
        finalizedKeys.clear()
        finalizedKeys.addAll(snapshot.finalizedKeys)
        lastErrorNoticeText = snapshot.lastErrorNoticeText
        lastSequence = snapshot.lastSequence
    }

    fun reset() {
        mutableItems.clear()
        streamIndexes.clear()
        finalizedKeys.clear()
        lastErrorNoticeText = null
        lastSequence = -1
    }

    fun rebuild(events: List<SessionEvent>) {
        reset()
        fold(events)
    }

    fun fold(events: List<SessionEvent>) {
        foldWithOperations(events)
    }

    fun foldWithOperations(events: List<SessionEvent>): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        val explicitCalls = events.filter { it.event.type == "tool/call" && it.event.callId != null }
            .associate { it.event.callId!! to it.event }
        events.forEach { record ->
            fold(record, operations, explicitCalls)
            lastSequence = maxOf(lastSequence, record.seq)
        }
        return operations
    }

    private fun fold(
        record: SessionEvent,
        operations: MutableList<ConversationProjectionOperation>,
        explicitCalls: Map<String, GatewayEvent>
    ) {
        val event = record.event
        val key = "${event.turn ?: -1}-${event.step ?: -1}"
        val date = normalizeEpoch(record.time)
        when {
            event.type == "user/message" && (event.source == null || event.source == "user") -> {
                // 新一轮开始后允许再次显示同样的上游错误；重试期间没有新的
                // user/message，因此前一条错误仍可用于去重。
                lastErrorNoticeText = null
                if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty()) {
                    insert(ConversationItem(
                        id = eventId(record),
                        kind = ConversationItemKind.USER,
                        title = labels.userMessage,
                        text = event.text.orEmpty(),
                        images = event.images.orEmpty(),
                        epochSeconds = date
                    ), operations)
                }
            }
            event.type == "user/message" && !event.text.isNullOrEmpty() -> insert(ConversationItem(
                id = eventId(record),
                kind = ConversationItemKind.CONTEXT,
                title = labels.context + contextSourceName(event),
                text = event.text,
                images = event.images.orEmpty(),
                epochSeconds = date
            ), operations)
            event.type == "assistant/chunk" && event.chunkType == "text-delta" && key !in finalizedKeys ->
                appendStream("stream-text-$key", "text-$key", ConversationItemKind.ASSISTANT, labels.streamingAssistant, event.text.orEmpty(), date, operations)
            event.type == "assistant/chunk" && event.chunkType == "reasoning-delta" && key !in finalizedKeys ->
                appendStream("stream-reason-$key", "reason-$key", ConversationItemKind.REASONING, labels.streamingReasoning, event.text.orEmpty(), date, operations)
            event.type == "assistant/chunk" && event.chunkType == "tool-call-delta" && key !in finalizedKeys -> {
                val toolKey = event.tool?.id ?: key
                val name = event.tool?.name ?: labels.assemblingTool
                val kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                appendStream("stream-tool-$toolKey", "tool-$toolKey", kind, name, event.tool?.argumentsDelta.orEmpty(), date, operations)
            }
            event.type == "assistant/message" -> {
                finalizedKeys += key
                removeStream("text-$key", operations)
                removeStream("reason-$key", operations)
                // 组装态工具流同轮收尾：不摘则 toolCallId=null 的半截行残留，
                // 平台 FIFO 兜底会把结果挂错行。id 未知（无 id 的 delta）的走 turn 级 key。
                removeStream("tool-$key", operations)
                event.toolCalls?.forEach { toolCall -> removeStream("tool-${toolCall.id}", operations) }
                event.reasoning?.takeIf(String::isNotEmpty)?.let { reasoning ->
                    insert(ConversationItem("${eventId(record)}-reason", ConversationItemKind.REASONING, labels.finalReasoning, reasoning, epochSeconds = date), operations)
                }
                if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty()) {
                    insert(ConversationItem(eventId(record), ConversationItemKind.ASSISTANT, labels.finalAssistant, event.text.orEmpty(), event.images.orEmpty(), epochSeconds = date), operations)
                }
                // 历史负序列只有assistant内的toolCalls，没有实时tool_execution_start。
                // 参数原样恢复；同批显式调用优先，实时正序列继续只读tool/call。
                if (record.seq < 0) event.toolCalls?.distinctBy { it.id }?.forEach { call ->
                    if (call.id.isNotBlank() && mutableItems.none { it.toolCallId == call.id && it.kind in toolKinds }) {
                        val explicit = explicitCalls[call.id]
                        val name = explicit?.name ?: call.name
                        insert(ConversationItem(
                            id = "${eventId(record)}-tool-${call.id}",
                            kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL,
                            title = name, text = (explicit?.arguments ?: call.arguments)?.jsonDisplayText().orEmpty(),
                            // assistant时间不是工具开始时间，不据此编造执行耗时。
                            epochSeconds = 0.0, toolCallId = call.id,
                        ), operations)
                    }
                }
                if (event.isError == true) {
                    insertUpstreamError(record, event.errorMessage, operations, date)
                } else {
                    // 成功 assistant 结束当前错误连续段，后续同文错误重新可见。
                    lastErrorNoticeText = null
                }
            }
            event.type == "tool/call" -> {
                // 历史基线之后重放同一调用时，不再追加第二个命令行。
                if (event.callId != null && mutableItems.any { it.toolCallId == event.callId && it.kind in toolKinds }) return
                val name = event.name ?: "Tool Call"
                val kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                insert(
                    ConversationItem(
                        id = eventId(record), kind = kind, title = name,
                        text = event.arguments?.jsonDisplayText().orEmpty(), epochSeconds = date,
                        toolCallId = event.callId
                    ),
                    operations
                )
            }
            event.type == "tool/result" -> insert(ConversationItem(
                id = eventId(record),
                kind = ConversationItemKind.TOOL_RESULT,
                title = if (event.isError == true) labels.toolResultFailed else labels.toolResultDone,
                text = event.preview.orEmpty(),
                isError = event.isError == true,
                epochSeconds = date,
                toolCallId = event.callId
            ), operations)
            event.type == "notice/upstream" -> insertUpstreamError(record, event.text, operations, date)
            event.type == "compact/done" -> insert(ConversationItem(
                id = eventId(record),
                kind = ConversationItemKind.SYSTEM,
                title = labels.compactionDone,
                text = event.text.orEmpty(),
                epochSeconds = date
            ), operations)
            event.type == "notice/compact" -> insert(ConversationItem(
                id = eventId(record),
                kind = ConversationItemKind.SYSTEM,
                title = labels.compactionNotice,
                text = event.text.orEmpty(),
                isError = event.isError == true,
                epochSeconds = date
            ), operations)
        }
    }

    private fun insertUpstreamError(
        record: SessionEvent,
        errorMessage: String?,
        operations: MutableList<ConversationProjectionOperation>,
        date: Double
    ) {
        val text = errorMessage?.trim().takeIf { !it.isNullOrEmpty() } ?: "上游请求失败"
        if (text == lastErrorNoticeText) return
        lastErrorNoticeText = text
        insert(
            ConversationItem(
                id = "${eventId(record)}-error",
                kind = ConversationItemKind.SYSTEM,
                title = labels.upstreamNotice,
                text = text,
                isError = true,
                epochSeconds = date
            ),
            operations
        )
    }

    private fun insert(
        item: ConversationItem,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        mutableItems += item
        operations += ConversationProjectionOperation(kind = "insert", item = item)
    }

    private fun appendStream(
        id: String,
        key: String,
        kind: ConversationItemKind,
        title: String,
        delta: String,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        if (delta.isEmpty()) return
        val index = streamIndexes[key]
        if (index != null) {
            val old = mutableItems[index]
            mutableItems[index] = old.copy(text = old.text + delta, epochSeconds = date)
            operations += ConversationProjectionOperation(
                kind = "append-text",
                itemId = old.id,
                delta = delta,
                epochSeconds = date
            )
        } else {
            streamIndexes[key] = mutableItems.size
            insert(ConversationItem(id, kind, title, delta, epochSeconds = date), operations)
        }
    }

    private fun removeStream(
        key: String,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val index = streamIndexes.remove(key) ?: return
        val removed = mutableItems.removeAt(index)
        operations += ConversationProjectionOperation(kind = "remove", itemId = removed.id)
        streamIndexes.entries.forEach { entry -> if (entry.value > index) entry.setValue(entry.value - 1) }
    }

    companion object {
        private val toolKinds = setOf(ConversationItemKind.TOOL, ConversationItemKind.JSON_TOOL)

        fun firstIndexAfter(sequence: Int, events: List<SessionEvent>): Int {
            var low = 0
            var high = events.size
            while (low < high) {
                val middle = (low + high) / 2
                if (events[middle].seq <= sequence) low = middle + 1 else high = middle
            }
            return low
        }

        fun make(events: List<SessionEvent>, labels: ConversationProjectionLabels = ConversationProjectionLabels()): List<ConversationItem> =
            ConversationProjector(labels).apply { rebuild(events) }.items
    }
}

data class ConversationHistoryRebase(
    val events: List<SessionEvent>,
    val projector: ConversationProjector
) {
    fun appendingLiveTail(latest: List<SessionEvent>): ConversationHistoryRebase {
        val start = ConversationProjector.firstIndexAfter(projector.lastSequence, latest)
        if (start >= latest.size) return this
        val tail = latest.subList(start, latest.size)
        projector.fold(tail)
        return copy(events = events + tail)
    }

    companion object {
        fun build(history: List<SessionEvent>, current: List<SessionEvent>): ConversationHistoryRebase {
            val records = history.associateBy(SessionEvent::seq).toMutableMap().apply {
                current.forEach { put(it.seq, it) }
            }
            val merged = records.values.sortedBy(SessionEvent::seq)
            return ConversationHistoryRebase(merged, ConversationProjector().apply { rebuild(merged) })
        }
    }
}

private fun contextSourceName(event: GatewayEvent): String =
    event.raw?.get("source")?.get("plugin")?.stringValue?.takeIf(String::isNotEmpty)
        ?: event.source
        ?: "Context"

/**
 * pi 条目身份（entryId + 视图类型 + callId）；entryId 缺失或空白（旧网关、无条目帧
 * 如 assistant/chunk、tool/call）时返回 null。
 *
 * 公开给上层去重/对消使用：历史窗口的 seq 是客户端合成的，整窗重拉会重新稠密分配，
 * 同一条目的 seq 会变；只有 entryId 是稳定的，所以 item 身份必须能从此函数得到。
 */
fun sessionEventIdentity(record: SessionEvent): String? {
    val entryId = record.entryId?.takeIf(String::isNotBlank) ?: return null
    return "${record.event.type}#${record.event.callId ?: "-"}#$entryId"
}

/**
 * item id 的基底：有 entryId 时由条目身份派生（整窗重拉/翻页后保持不变，
 * 滚动 key 与折叠态不会因合成 seq 重分配而整片丢失），否则退回原来的 seq 形式。
 * -reason / -error 后缀仍由调用方追加。seq 分配本身不动（严格递增的 patch 序不变）。
 */
private fun eventId(record: SessionEvent): String {
    val identity = sessionEventIdentity(record) ?: return "${record.sessionId}-${record.seq}"
    return "${record.sessionId}#$identity"
}

private fun normalizeEpoch(value: Double): Double = if (value > 10_000_000_000) value / 1_000 else value
