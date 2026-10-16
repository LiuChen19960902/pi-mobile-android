package com.finecat.pimobile.shared.protocol

/**
 * pi 事件 JSON → 内部 [GatewayEvent] 视图归一器。
 *
 * 输入是 pi-mobile-gateway `event` 帧的 `event` 字段（pi AgentSessionEvent
 * 的 JSON 投影，见 pi-mobile-gateway/PROTOCOL.md §4.5），输出是
 * domain/projection/UI 消费的 pi 事件流：
 *
 *   message_end(role=user)            → user/message
 *   message_start/update(text_delta)  → assistant/chunk(text-delta)
 *   message_update(thinking_delta)    → assistant/chunk(reasoning-delta)
 *   message_update(toolcall_*)        → assistant/chunk(tool-call-delta)
 *   message_end(role=assistant)       → assistant/message（含 reasoning/toolCalls/上游错误）
 *   message_end(role=toolResult)      → tool/result
 *   tool_execution_start              → tool/call
 *   agent_start / agent_settled       → turn/start / turn/end
 *   message_end(role=custom)          → notice/custom（尊重display，仅通用文本）
 *   tool_execution_end(nested)        → tool/result（无独立持久化结果的嵌套调用）
 *   entry_appended(compaction)        → compact/done（对齐 TUI 的 [compaction: Nk tokens] 行）
 *   compaction_start / compaction_end → compact/start / compact|notice（状态收尾与失败可见）
 *   session_info_changed              → session/title
 *
 * 归一器按会话持有状态（合成的 turn 计数、工具名表），因此**每个会话独占
 * 一个实例**、事件必须按到达顺序喂入（GatewayRuntime 已保证同一连接内有序）。
 * 未识别的事件类型返回空列表（投影层安全忽略）。
 */
class PiEventNormalizer(private val sessionId: String) {

    /** assistant 消息流计数：message_start(assistant) 递增，作为流式 key。 */
    private var assistantStreamIndex = 0

    /** toolCallId → 最近一次 tool_execution_start 的工具名（result 关联用）。 */
    private val toolNamesByCallId = mutableMapOf<String, String>()
    private val nestedParentsByCallId = mutableMapOf<String, String>()
    private val completedNestedCalls = mutableSetOf<String>()
    private val completedToolMessages = mutableSetOf<String>()

    fun normalize(seq: Int, time: Double, event: JsonValue, entryId: String? = null): List<SessionEvent> {
        val type = event["type"]?.stringValue ?: return emptyList()
        val events = when (type) {
            "message_start" -> onMessageStart(seq, time, event, entryId)
            "message_update" -> onMessageUpdate(seq, time, event)
            "message_end" -> onMessageEnd(seq, time, event, entryId)
            "tool_execution_start" -> listOfNotNull(onToolExecutionStart(seq, time, event))
            "tool_execution_end" -> onToolExecutionEnd(seq, time, event)
            "agent_start" -> listOf(view(seq, time, "turn/start"))
            "agent_settled" -> listOf(view(seq, time, "turn/end"))
            "compaction_start" -> listOf(view(seq, time, "compact/start", reason = event["reason"]?.stringValue))
            "compaction_end" -> onCompactionEnd(seq, time, event)
            "entry_appended" -> onEntryAppended(seq, time, event)
            "session_info_changed" -> onSessionInfoChanged(seq, time, event)
            // pi 原生 queue_update（agent-session.js _emitQueueUpdate）：steer/followUp
            // 在 message_start(user) 时出队并立刻广播最新队列。TUI 靠它实时清吸附条。
            "queue_update" -> listOf(
                view(
                    seq,
                    time,
                    "queue/update",
                    steering = event["steering"]?.arrayValue.orEmpty().mapNotNull { it.stringValue },
                    followUp = event["followUp"]?.arrayValue.orEmpty().mapNotNull { it.stringValue }
                )
            )
            else -> emptyList()
        }
        // 帧顶层 entryId（PROTOCOL §4.5）属于整个 event 帧（网关只在 message_end
        // 上加盖，一帧至多一条归一事件），随归一结果透传给身份去重；null = 身份未知。
        // 同一 entryId 也用于 image 引用寻址（§4.22 attachment 取图）。
        return events.map { record -> record.copy(sessionId = sessionId, entryId = entryId ?: record.entryId) }
    }

    private fun onMessageStart(seq: Int, time: Double, event: JsonValue, entryId: String?): List<SessionEvent> {
        val message = event["message"] ?: return emptyList()
        return when (message["role"]?.stringValue) {
            // pi 对每条用户 prompt 依次发 message_start 与 message_end（内容相同），
            // 两者都映射会产出两条 user/message（seq 不同、气泡重复）。用户消息
            // 统一由 message_end 产出，start 仅用于 assistant 的流式索引。
            "user" -> emptyList()
            "assistant" -> {
                assistantStreamIndex += 1
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun onMessageUpdate(seq: Int, time: Double, event: JsonValue): List<SessionEvent> {
        val delta = event["assistantMessageEvent"] ?: return emptyList()
        val turn = assistantStreamIndex
        return when (delta["type"]?.stringValue) {
            "text_delta" -> listOf(
                view(seq, time, "assistant/chunk", turn = turn, step = 0, chunkType = "text-delta", text = delta["delta"]?.stringValue)
            )
            "thinking_delta" -> listOf(
                view(seq, time, "assistant/chunk", turn = turn, step = 0, chunkType = "reasoning-delta", text = delta["delta"]?.stringValue)
            )
            "toolcall_start", "toolcall_delta" -> listOf(
                view(
                    seq, time, "assistant/chunk", turn = turn, step = 0, chunkType = "tool-call-delta",
                    tool = ToolDelta(id = delta["id"]?.stringValue, name = delta["toolName"]?.stringValue, argumentsDelta = delta["delta"]?.stringValue)
                )
            )
            else -> emptyList()
        }
    }

    private fun onMessageEnd(seq: Int, time: Double, event: JsonValue, entryId: String?): List<SessionEvent> {
        val message = event["message"] ?: return emptyList()
        return when (message["role"]?.stringValue) {
            "assistant" -> {
                val stopReason = message["stopReason"]?.stringValue
                val isUpstreamError = stopReason == "error"
                val errorMessage = if (isUpstreamError) {
                    message["errorMessage"]?.stringValue?.trim()
                        ?.take(MAX_NOTICE_CHARACTERS)
                        ?.takeIf(String::isNotEmpty)
                } else {
                    null
                }
                val toolCalls = toolCallBlocks(message["content"])
                listOf(
                    view(
                        seq, time, "assistant/message", turn = assistantStreamIndex, step = 0,
                        text = textOf(message["content"]),
                        reasoning = thinkingOf(message["content"]),
                        toolCalls = toolCalls.ifEmpty { null },
                        usage = message["usage"],
                        provider = message["provider"]?.stringValue,
                        model = message["model"]?.stringValue,
                        isError = if (isUpstreamError) true else null,
                        errorMessage = errorMessage
                    )
                )
            }
            "custom" -> customMessage(seq, time, message)
            "toolResult" -> {
                val callId = message["toolCallId"]?.stringValue
                if (callId in completedNestedCalls) return emptyList()
                val parent = callId?.let { nestedParentsByCallId.remove(it) }
                if (callId != null) {
                    rememberCompletedCall(completedToolMessages, callId)
                    if (parent != null) rememberCompletedCall(completedNestedCalls, callId)
                }
                // 结果关联后即释放（remove 返回旧值正好当名字用）：
                // 连接生命周期内上千次工具调用不再单调增长。
                val registeredName = callId?.let { toolNamesByCallId.remove(it) }
                listOf(
                    view(
                        seq, time, "tool/result",
                        callId = callId,
                        parentCallId = parent,
                        name = registeredName ?: message["toolName"]?.stringValue,
                        isError = message["isError"]?.booleanValue ?: false,
                        preview = textOf(message["content"])
                    )
                )
            }
            "user" -> {
                // 运行中注入的 steer/followUp 用户消息也走 message_end。
                val text = textOf(message["content"])
                val images = imageAttachmentsOf(message["content"], "live", entryId)
                if (text.isEmpty() && images.isEmpty()) {
                    emptyList()
                } else {
                    listOf(view(seq, time, "user/message", text = text, source = "user", images = images))
                }
            }
            else -> emptyList()
        }
    }

    private fun onToolExecutionStart(seq: Int, time: Double, event: JsonValue): SessionEvent? {
        val callId = event["toolCallId"]?.stringValue ?: return null
        val name = event["toolName"]?.stringValue
        if (name != null) toolNamesByCallId[callId] = name
        completedNestedCalls.remove(callId)
        completedToolMessages.remove(callId)
        val parent = event["parentToolCallId"]?.stringValue?.takeIf(String::isNotBlank)
        if (parent != null) nestedParentsByCallId[callId] = parent
        // orphan（start 后无 result，如中断）由上限兜底：只保最近的。
        while (toolNamesByCallId.size > MAX_TOOL_NAMES) {
            val oldest = toolNamesByCallId.keys.firstOrNull() ?: break
            toolNamesByCallId.remove(oldest)
            nestedParentsByCallId.remove(oldest)
        }
        while (nestedParentsByCallId.size > MAX_TOOL_NAMES) nestedParentsByCallId.remove(nestedParentsByCallId.keys.first())
        return view(seq, time, "tool/call", callId = callId, name = name, arguments = event["args"], parentCallId = parent)
    }

    private fun onToolExecutionEnd(seq: Int, time: Double, event: JsonValue): List<SessionEvent> {
        val callId = event["toolCallId"]?.stringValue ?: return emptyList()
        val parent = event["parentToolCallId"]?.stringValue?.takeIf(String::isNotBlank)
            ?: nestedParentsByCallId[callId] ?: return emptyList()
        // 顶层结果留给权威toolResult消息；仅嵌套调用依赖end收尾。
        if (callId in completedNestedCalls || callId in completedToolMessages) return emptyList()
        rememberCompletedCall(completedNestedCalls, callId)
        nestedParentsByCallId.remove(callId)
        val name = toolNamesByCallId.remove(callId) ?: event["toolName"]?.stringValue
        return listOf(view(seq, time, "tool/result", callId = callId, name = name, parentCallId = parent,
            preview = textOf(event["result"]?.get("content")), isError = event["isError"]?.booleanValue ?: false))
    }

    // 有界缓存只处理近期重复；真实SDK的嵌套end没有独立toolResult。
    private fun rememberCompletedCall(completed: MutableSet<String>, callId: String) {
        completed.add(callId)
        while (completed.size > MAX_TOOL_NAMES) completed.remove(completed.first())
    }

    private fun customMessage(seq: Int, time: Double, message: JsonValue): List<SessionEvent> {
        if (message["display"]?.booleanValue != true) return emptyList()
        val text = textOf(message["content"]).takeIf(String::isNotBlank) ?: return emptyList()
        return listOf(view(seq, time, "notice/custom", text = text))
    }

    /**
     * 压缩结束：completed 只做状态收尾（压缩完成的可见痕迹是 compaction entry
     * 本身，见 onEntryAppended）；failed/declined 则归一成 notice/compact，
     * 上屏可见（store 同样以该类型收尾状态）。
     */
    private fun onCompactionEnd(seq: Int, time: Double, event: JsonValue): List<SessionEvent> {
        // Pi 原生字段是 reason/result/aborted/willRetry/errorMessage；保留旧
        // status/error 形状的兼容读取，避免旧网关或历史 fixture 的失败提示回退为成功。
        val status = event["status"]?.stringValue
        val errorText = event["errorMessage"]?.stringValue?.trim()?.takeIf(String::isNotEmpty)
            ?: event["error"]?.let { error ->
                error.stringValue ?: error["message"]?.stringValue
            }?.trim()?.takeIf(String::isNotEmpty)
        val aborted = event["aborted"]?.booleanValue == true
        val failed = status == "failed" || status == "declined" || !errorText.isNullOrEmpty() || aborted
        if (!failed) return listOf(view(seq, time, "compact/end"))

        val nativeError = errorText?.removePrefix("Compaction failed: ")
        val message = when {
            status == "declined" -> "上下文压缩未执行"
            aborted && errorText.isNullOrEmpty() -> "上下文压缩已中止"
            nativeError == "Nothing to compact (session too small)" -> "暂无可压缩内容"
            nativeError == "Already compacted" -> "暂无需再次压缩"
            errorText.isNullOrEmpty() -> "上下文压缩失败"
            else -> "上下文压缩失败，请重试"
        }
        return listOf(
            view(seq, time, "notice/compact", text = message.take(MAX_NOTICE_CHARACTERS), isError = true,
                reason = when {
                    aborted -> "aborted"
                    status == "declined" -> "declined"
                    else -> "failed"
                })
        )
    }

    private fun onEntryAppended(seq: Int, time: Double, event: JsonValue): List<SessionEvent> {
        val entry = event["entry"] ?: return emptyList()
        if (entry["type"]?.stringValue == "compaction") {
            // 对齐 pi TUI：对话流里的压缩痕迹是 `[compaction: Nk tokens]` 一行。
            val tokensBefore = entry["tokensBefore"]?.doubleValue
            val tokens = tokensBefore?.let { (it / 1000).toInt().coerceAtLeast(1) }
            return listOf(
                view(seq, time, "compact/done", text = tokens?.let { "压缩前 ${it}k tokens" })
            )
        }
        if (entry["type"]?.stringValue == "custom_message") {
            return customMessage(seq, time, entry).map { it.copy(entryId = entry["id"]?.stringValue) }
        }
        return emptyList()
    }

    private fun onSessionInfoChanged(seq: Int, time: Double, event: JsonValue): List<SessionEvent> {
        val info = event["sessionInfo"] ?: event["info"] ?: return emptyList()
        val title = info["name"]?.stringValue?.takeIf(String::isNotBlank) ?: return emptyList()
        return listOf(view(seq, time, "session/title", text = title))
    }

    private fun view(
        seq: Int,
        time: Double,
        type: String,
        turn: Int? = null,
        step: Int? = null,
        text: String? = null,
        source: String? = null,
        chunkType: String? = null,
        tool: ToolDelta? = null,
        usage: JsonValue? = null,
        reasoning: String? = null,
        toolCalls: List<ToolCall>? = null,
        images: List<GatewayImageAttachment>? = null,
        callId: String? = null,
        name: String? = null,
        arguments: JsonValue? = null,
        provider: String? = null,
        model: String? = null,
        isError: Boolean? = null,
        errorMessage: String? = null,
        preview: String? = null,
        reason: String? = null,
        steering: List<String>? = null,
        followUp: List<String>? = null,
        parentCallId: String? = null
    ): SessionEvent = SessionEvent(
        sessionId = sessionId,
        seq = seq,
        time = time,
        event = GatewayEvent(
            type = type, turn = turn, step = step, text = text, source = source, chunkType = chunkType,
            tool = tool, usage = usage, reasoning = reasoning, toolCalls = toolCalls, images = images,
            callId = callId, name = name, arguments = arguments, provider = provider, model = model,
            isError = isError,
            errorMessage = errorMessage, preview = preview, reason = reason, steering = steering, followUp = followUp, parentCallId = parentCallId
        )
    )

    companion object {
        /** tool 名表上限：正常路径结果即删，此处只兜 start 后无 result 的 orphan。 */
        private const val MAX_TOOL_NAMES = 512

        /** 有 entryId 时的统一占位前缀：即 history 侧旧公式前缀，缓存键跨 live/history 复用。 */
        private const val STABLE_FALLBACK_PREFIX = "hist"

        /** 上游 reason 带原始 JSON 响应，截断后再上屏。 */
        private const val MAX_NOTICE_CHARACTERS = 240

        /** 拼接 content 块数组中的 text 块；pi 的 user 消息也可能直接是字符串。 */
        fun textOf(content: JsonValue?): String = when {
            content == null -> ""
            content.stringValue != null -> content.stringValue.orEmpty()
            else -> content.arrayValue.orEmpty()
                .filter { it["type"]?.stringValue == "text" }
                .mapNotNull { it["text"]?.stringValue }
                .joinToString("")
        }

        /** 拼接 thinking 块（pi 的思考文本）。 */
        fun thinkingOf(content: JsonValue?): String = content?.arrayValue.orEmpty()
            .filter { it["type"]?.stringValue == "thinking" }
            .mapNotNull { it["thinking"]?.stringValue }
            .joinToString("")

        /**
         * 提取 user 消息 content 里的 image 块。网关出站预算会把 base64 裁成
         * `{dataOmitted:true, dataBytes}`；attachmentId 是网关按发送帧注入的
         * display-only 字段（回显命中发送端本地缓存），缺省时按 [entryId] +
         * image 序号合成稳定占位 id。live 与 history 是同一 Pi entry 的两次
         * 提取，占位 id 必须一致切换/重连后本地缓存才能复用：统一沿用 history
         * 旧公式 `hist-<entryId>-<序号>`（兼容既有历史缓存键），来源前缀不再
         * 参与；entryId 也未知时退回旧“来源前缀+序号”形态。entryId 与序号
         * 同时随块带出，供 §4.22 取图。
         */
        fun imageAttachmentsOf(
            content: JsonValue?,
            fallbackPrefix: String,
            entryId: String? = null
        ): List<GatewayImageAttachment> {
            if (content == null) return emptyList()
            var imageIndex = 0
            return content.arrayValue.orEmpty().mapNotNull { block ->
                if (block["type"]?.stringValue != "image") return@mapNotNull null
                val index = imageIndex
                imageIndex++
                val attachmentId = block["attachmentId"]?.stringValue?.takeIf(String::isNotBlank)
                    ?: if (entryId.isNullOrBlank()) "$fallbackPrefix-$index"
                    else "$STABLE_FALLBACK_PREFIX-$entryId-$index"
                GatewayImageAttachment(
                    attachmentId = attachmentId,
                    mediaType = block["mimeType"]?.stringValue ?: "image/png",
                    bytes = block["dataBytes"]?.doubleValue?.toInt() ?: 0,
                    dataBase64 = block["data"]?.stringValue,
                    entryId = entryId?.takeIf(String::isNotBlank),
                    imageIndex = index
                )
            }
        }

        /** 提取 toolCall 块为内部 [ToolCall]（pi 历史与 message_end 均为 toolCallId/toolName/input 形状）。 */
        fun toolCallBlocks(content: JsonValue?): List<ToolCall> = content?.arrayValue.orEmpty()
            .mapNotNull { block ->
                if (block["type"]?.stringValue != "toolCall") return@mapNotNull null
                val id = block["toolCallId"]?.stringValue
                    ?: block["id"]?.stringValue
                    ?: return@mapNotNull null
                val name = block["toolName"]?.stringValue
                    ?: block["name"]?.stringValue
                    ?: return@mapNotNull null
                ToolCall(id, name, block["input"] ?: block["arguments"])
            }
    }
}
