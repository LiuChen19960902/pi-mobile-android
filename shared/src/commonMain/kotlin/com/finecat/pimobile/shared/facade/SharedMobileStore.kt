package com.finecat.pimobile.shared.facade

import com.finecat.pimobile.shared.SharedModuleInfo
import com.finecat.pimobile.shared.domain.SessionListAction
import com.finecat.pimobile.shared.domain.SessionListReducer
import com.finecat.pimobile.shared.domain.SessionListState
import com.finecat.pimobile.shared.domain.SessionSummary
import com.finecat.pimobile.shared.domain.normalizeEpochSeconds
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationProjectionLabels
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.protocol.GatewayModelCatalog
import com.finecat.pimobile.shared.protocol.GatewayQueuedMessages
import com.finecat.pimobile.shared.protocol.GatewaySessionState
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewaySessionSummary
import com.finecat.pimobile.shared.protocol.GatewayStatusEntry
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock

data class SharedMobileSnapshot(
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val conversation: List<ConversationItem> = emptyList(),
    val modelCatalog: GatewayModelCatalog? = null,
    /** 每会话的 pi 会话级状态（model/thinkingLevel/contextUsage…），由 state/model-set 帧更新。 */
    val sessionStates: Map<String, GatewaySessionState> = emptyMap(),
    /** 每会话的扩展状态行（Pi `ctx.ui.setStatus` 快照），只用于显示，不代表任务状态。 */
    val extensionStatuses: Map<String, List<GatewayStatusEntry>> = emptyMap(),
    val selectedHistoryHasMore: Boolean = false,
    val selectedHistoryEarliestSequence: Int? = null,
    val selectedHistoryIsLoading: Boolean = false,
    val selectedHistoryIsLoadingOlder: Boolean = false,
    val selectedHistoryLoadedEventCount: Int = 0,
    val selectedHistoryTotalEventCount: Int? = null,
    /** 选中会话最近一次 history 加载失败码；与每帧重置的 lastError 不同，
     *  该状态持续到重新发起加载或成功落页，供加载遮罩的失败逃生口使用。 */
    val selectedHistoryError: String? = null,
    val lastFrameKind: String? = null,
    val lastError: String? = null,
    /** 历史协议游标；隐藏消息页可能没有可显示事件，不能用事件 seq 判断能否翻页。 */
    val selectedHistoryNextCursor: String? = null
)

/**
 * 跨平台复用的粗粒度业务入口（当前唯一消费方是 Android UI）。
 *
 * 平台层负责网络、持久化与线程切换；调用方必须在自己的串行 UI/store
 * 上下文中提交 intent。该类只做 wire decode、Reducer 和纯投影。
 */
class SharedMobileStore(
    private val nowEpochSeconds: () -> Double = {
        Clock.System.now().toEpochMilliseconds().toDouble() / 1_000.0
    }
) {
    private var sessionListState = SessionListState()
    private val eventsBySession = mutableMapOf<String, List<SessionEvent>>()
    private var modelCatalog: GatewayModelCatalog? = null
    private var modelCatalogSessionId: String? = null
    private var bridgeMode = false
    private val sessionStates = mutableMapOf<String, GatewaySessionState>()
    private val extensionStatuses = mutableMapOf<String, List<GatewayStatusEntry>>()
    private var lastFrameKind: String? = null
    private var lastError: String? = null

    fun snapshot(): SharedMobileSnapshot = makeSnapshot()

    fun selectSession(sessionId: String?): SharedMobileSnapshot {
        if (modelCatalogSessionId != null && modelCatalogSessionId != sessionId) {
            modelCatalog = null
            modelCatalogSessionId = null
        }
        sessionListState = SessionListReducer.reduce(sessionListState, SessionListAction.Select(sessionId))
        sessionId?.let {
            sessionListState = SessionListReducer.reduce(sessionListState, SessionListAction.MarkRead(it))
        }
        return makeSnapshot()
    }

    fun acceptFrame(json: String): SharedMobileSnapshot {
        try {
            val frame = GatewayWireDecoder.decode(json)
            lastFrameKind = frame.kind
            lastError = null
            // 扩展状态是完整快照：任何带 statuses 的帧（extension-status 推送、
            // subscribed/session-restarted 应答）都整表替换该会话的状态行。
            // 无 sessionId 时无法归属，忽略；extension-status 缺 sessionId 属违规帧。
            frame.statuses?.takeUnless { bridgeMode }?.let { statuses ->
                frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    replaceExtensionStatuses(sessionId, statuses)
                }
            }
            when (frame.kind) {
                "sessions" -> {
                    val bridgeMode = frame.mode == "bridge"
                    val sessions = frame.items.orEmpty().mapNotNull { item ->
                        runCatching {
                            wireJson.decodeFromJsonElement(GatewaySessionSummary.serializer(), item.toJsonElement())
                        }.getOrNull()
                    }
                    sessionListState = SessionListReducer.reduce(
                        sessionListState,
                        SessionListAction.RemoteSessionsReceived(sessions)
                    )
                    if (bridgeMode) {
                        // 桥接列表是权威：不在在线集合里的旧标记必须清掉，
                        // 避免实例退出后仍显示“在线”。
                        val onlineIds = sessions.filter { it.online == true }.mapTo(mutableSetOf()) { it.id }
                        sessionListState.sessions.filter { it.id !in onlineIds && it.isOnline == true }
                            .forEach { stale ->
                                sessionListState = SessionListReducer.reduce(
                                    sessionListState,
                                    SessionListAction.OnlineChanged(stale.id, false)
                                )
                            }
                    } else {
                        // sdk 模式的 sessions 应答不带 online；切回 sdk 后不能
                        // 继续显示上一轮桥接留下的“在线/离线”。
                        clearOnlineMarks()
                    }
                }
                "sent" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    sessionListState = SessionListReducer.reduce(
                        sessionListState,
                        SessionListAction.MessageSent(
                            sessionId = sessionId,
                            insertedAtEpochSeconds = frame.time
                                ?.let(::normalizeEpochSeconds)
                                ?: nowEpochSeconds()
                        )
                    )
                    if (frame.isStreaming != null && frame.isCompacting != null) {
                        sessionStates[sessionId] = GatewaySessionState(
                            sessionId = sessionId,
                            isStreaming = frame.isStreaming,
                            isCompacting = frame.isCompacting,
                            model = frame.model,
                            thinkingLevel = frame.thinkingLevel,
                            contextUsage = frame.contextUsage,
                            queuedMessages = queuedMessagesOf(frame.queuedMessages),
                            // submitted 不含队列真值时，不抹掉此前快照确认的 pending。
                            pendingMessages = frame.pendingMessages ?: if (frame.disposition == "submitted")
                                sessionStates[sessionId]?.pendingMessages ?: false else false
                        )
                    }
                }
                "models" -> {
                    // 原生目录按会话归属；迟到的另一个 CLI 目录不能进入当前选择器。
                    if ((!bridgeMode && frame.sessionId == null) || (frame.sessionId != null && frame.sessionId == sessionListState.selectedSessionId)) {
                        modelCatalog = GatewayModelCatalog(frame.groups.orEmpty())
                        modelCatalogSessionId = frame.sessionId
                    }
                }
                // state/model-set 应答就是会话级状态快照；无 sessionId 的 model-set 无法归属，忽略。
                // session-named（改名）/ compact-started（压缩受理）也回同一快照形状；
                // session-named 另外刷新列表标题（网关应答带 name，单一数据流）。
                // history 应答也带全量 state（网关 page.state）：断连期间 turn/start|end
                // 事件丢失，重连 catch-up 拉回的 history 帧是 isStreaming 的唯一纠正
                // 机会——拉式 state 只在手动刷新/轮末来，长断连后 Working 会陈旧。
                // session-restarted（重载配置）同样以 state 快照形状下发。
                "history", "state", "model-set", "model-refreshed", "compact-started", "compact-finished", "branch-switched", "session-restarted" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    sessionStates[sessionId] = GatewaySessionState(
                        sessionId = sessionId,
                        isStreaming = frame.isStreaming == true,
                        isCompacting = frame.isCompacting == true,
                        model = frame.model,
                        thinkingLevel = frame.thinkingLevel,
                        contextUsage = frame.contextUsage,
                        queuedMessages = queuedMessagesOf(frame.queuedMessages),
                        pendingMessages = frame.pendingMessages == true
                    )
                }
                // 桥接快照（PROTOCOL §4.31）是扩展侧真值：busy 即运行中，
                // 收到即证明实例在线；pending 不伪造文本队列。
                "host-mode" -> {
                    val wasBridge = bridgeMode
                    bridgeMode = frame.mode == "bridge"
                    if (frame.changed == true || wasBridge != bridgeMode) {
                        modelCatalog = null; modelCatalogSessionId = null
                        extensionStatuses.clear()
                    }
                    if (!bridgeMode) {
                    clearOnlineMarks()
                    sessionStates.keys.toList().forEach { sessionId ->
                        sessionStates[sessionId] = sessionStates.getValue(sessionId).copy(pendingMessages = false)
                    }
                }
                }
                "bridge-snapshot", "bridge-offline" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    extensionStatuses.remove(sessionId)
                    val previous = sessionStates[sessionId] ?: GatewaySessionState(sessionId = sessionId)
                    val online = frame.kind == "bridge-snapshot"
                    sessionStates[sessionId] = previous.copy(
                        // bridge-offline 没有 busy 字段：离线就是空闲，不能沿用旧运行标记；
                        // 压缩和服务器队列同样已失效，清掉后由独立恢复记录承载未确认提交。
                        isStreaming = if (online) frame.isStreaming ?: frame.busy ?: previous.isStreaming else false,
                        isCompacting = online && frame.isCompacting == true,
                        contextUsage = if (online) frame.contextUsage else null,
                        model = frame.model ?: previous.model,
                        thinkingLevel = frame.thinkingLevel ?: previous.thinkingLevel,
                        // 桥接只有是否待执行的真值，不能沿用 SDK 的旧队列正文。
                        queuedMessages = GatewayQueuedMessages(),
                        pendingMessages = online && (frame.pending ?: previous.pendingMessages)
                    )
                    sessionListState = SessionListReducer.reduce(
                        sessionListState,
                        SessionListAction.OnlineChanged(sessionId, online)
                    )
                    syncSessionActivity(sessionId)
                }
                "event" -> if (frame.instanceId != null && frame.isStreaming != null && frame.isCompacting != null) {
                    frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                        val previous = sessionStates[sessionId] ?: GatewaySessionState(sessionId = sessionId)
                        sessionStates[sessionId] = previous.copy(
                            isStreaming = frame.isStreaming,
                            isCompacting = frame.isCompacting,
                            contextUsage = frame.contextUsage ?: previous.contextUsage
                        )
                    }
                }
                "aborted", "dequeued" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    // 新网关在 idle ACK 中带权威快照；旧网关裸 ACK 不证明队列已清空。
                    val previous = sessionStates[sessionId] ?: GatewaySessionState(sessionId = sessionId)
                    sessionStates[sessionId] = previous.copy(
                        isStreaming = frame.isStreaming ?: if (frame.kind == "aborted") false else previous.isStreaming,
                        isCompacting = frame.isCompacting ?: if (frame.kind == "aborted") false else previous.isCompacting,
                        contextUsage = frame.contextUsage ?: previous.contextUsage,
                        queuedMessages = if (frame.queuedMessages != null) queuedMessagesOf(frame.queuedMessages) else previous.queuedMessages,
                        pendingMessages = frame.pendingMessages
                            ?: if (frame.queuedMessages != null) false else previous.pendingMessages,
                    )
                }
                "session-named" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    sessionStates[sessionId] = GatewaySessionState(
                        sessionId = sessionId,
                        isStreaming = frame.isStreaming == true,
                        isCompacting = frame.isCompacting == true,
                        model = frame.model,
                        thinkingLevel = frame.thinkingLevel,
                        contextUsage = frame.contextUsage
                    )
                    frame.name?.takeIf(String::isNotBlank)?.let { name ->
                        sessionListState = SessionListReducer.reduce(
                            sessionListState,
                            SessionListAction.Renamed(sessionId, name)
                        )
                    }
                }
                "history" -> Unit // pi history 由 AndroidGatewayProjection 直接接管
            }
            // 抽屉与Working使用同一份权威忙闲状态；快照只对账，不声明压缩成功。
            if (frame.isStreaming != null && frame.isCompacting != null) {
                frame.sessionId?.let(::syncSessionActivity)
            }
        } catch (error: Throwable) {
            lastError = error.message ?: error::class.simpleName ?: "decode-error"
        }
        return makeSnapshot()
    }

    /**
     * pi 实时事件入口：GatewayRuntime 已将 pi 事件 JSON 归一为内部视图，
     * 这里只更新会话列表元数据并合并事件流。
     */
    fun acceptLiveEvents(records: List<SessionEvent>): SharedMobileSnapshot {
        records.forEach { record -> acceptEvent(record) }
        return makeSnapshot()
    }

    fun clearSessionEvents(sessionId: String) {
        eventsBySession.remove(sessionId)
    }

    fun loadManualTestFixture(): SharedMobileSnapshot {
        reset()
        acceptFrame("""{"kind":"sessions","items":[{"id":"android-demo","cwd":"/tmp/pi-demo","modified":"2026-09-01T07:36:16.694Z","messageCount":2,"firstMessage":"请验证 Android 是否使用共享 Reducer","running":false}]}""")
        selectSession("android-demo")
        acceptLiveEvents(listOf(
            SessionEvent("android-demo", 1, 1786937352.0, GatewayEvent(type = "user/message", text = "请验证 Android 是否使用共享 Reducer", source = "user")),
            SessionEvent("android-demo", 2, 1786937353.0, GatewayEvent(type = "assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "共享协议解码、")),
            SessionEvent("android-demo", 3, 1786937354.0, GatewayEvent(type = "assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "Reducer 与投影已接入。"))
        ))
        return makeSnapshot()
    }

    fun reset(): SharedMobileSnapshot {
        sessionListState = SessionListState()
        eventsBySession.clear()
        modelCatalog = null
        sessionStates.clear()
        extensionStatuses.clear()
        lastFrameKind = null
        lastError = null
        return makeSnapshot()
    }

    private fun acceptEvent(record: SessionEvent) {
        // 压缩状态事件（PiEventNormalizer 归一）：compact/start 置压缩中，
        // compact/end / notice/compact（失败可见行）收尾；start/end 不进对话流。
        when (record.event.type) {
            // turn/start|end 直接翻转 isStreaming（与 compact 的直接翻转对称）：
            // 轮末 state 应答丢失时（断连/lane 拒绝），事件流本身就是纠正信号
            // （agent_settled → 空闲），否则运行中发送键变停止键后会永久卡住。
            // 不 return：照常进 reducer/事件存储。
            "turn/start", "turn/end" -> {
                val streaming = record.event.type == "turn/start"
                val previous = sessionStates[record.sessionId] ?: GatewaySessionState(sessionId = record.sessionId)
                sessionStates[record.sessionId] = previous.copy(
                    isStreaming = streaming,
                    // turn/end 只来自 agent_settled，公开契约保证没有待续队列。
                    pendingMessages = streaming && previous.pendingMessages,
                    queuedMessages = if (streaming) previous.queuedMessages else null,
                )
            }
            "compact/start" -> {
                updateCompacting(record.sessionId, true)
            }
            "compact/end", "notice/compact" -> {
                updateCompacting(record.sessionId, false)
            }
            // pi 原生 queue_update 归一：steer/followUp 消费后立即刷新快照，
            // 对齐 TUI 的实时清条（state 帧是请求-应答，轮内不会刷新）。
            "queue/update" -> {
                val queued = GatewayQueuedMessages(
                    steering = record.event.steering.orEmpty(),
                    followUp = record.event.followUp.orEmpty()
                )
                sessionStates[record.sessionId] = (sessionStates[record.sessionId]
                    ?: GatewaySessionState(sessionId = record.sessionId)).copy(
                    queuedMessages = if (queued.isEmpty) null else queued,
                    pendingMessages = false,
                )
                return
            }
        }
        sessionListState = SessionListReducer.reduce(
            sessionListState,
            SessionListAction.EventReceived(record, normalizeEpochSeconds(record.time))
        )
        if (record.event.type in setOf("turn/start", "turn/end", "compact/start", "compact/end", "notice/compact")) {
            syncSessionActivity(record.sessionId)
        }
        if (record.event.type == "compact/start" || record.event.type == "compact/end") return
        val existing = eventsBySession[record.sessionId].orEmpty()
        val records = existing.associateBy(SessionEvent::seq).toMutableMap().apply { put(record.seq, record) }
        eventsBySession[record.sessionId] = records.values.sortedBy(SessionEvent::seq)
    }

    private fun updateCompacting(sessionId: String, value: Boolean) {
        sessionStates[sessionId] = (sessionStates[sessionId]
            ?: GatewaySessionState(sessionId = sessionId)).copy(isCompacting = value)
    }

    /** 清掉所有桥接在线标记（切回 sdk 模式或 sdk 列表应答后调用）。 */
    private fun clearOnlineMarks() {
        sessionListState.sessions.filter { it.isOnline != null }.forEach { stale ->
            sessionListState = SessionListReducer.reduce(
                sessionListState,
                SessionListAction.OnlineChanged(stale.id, null)
            )
        }
    }

    private fun syncSessionActivity(sessionId: String) {
        val state = sessionStates[sessionId] ?: return
        sessionListState = SessionListReducer.reduce(
            sessionListState,
            SessionListAction.ActivityChanged(sessionId, state.isStreaming || state.isCompacting)
        )
    }

    /** state 帧的 queuedMessages（JsonValue）→ 结构化排队消息；异常形状安全降级为空。 */
    private fun queuedMessagesOf(value: JsonValue?): GatewayQueuedMessages? {
        val steering = value?.get("steering")?.arrayValue.orEmpty().mapNotNull { it.stringValue }
        val followUp = value?.get("followUp")?.arrayValue.orEmpty().mapNotNull { it.stringValue }
        if (steering.isEmpty() && followUp.isEmpty()) return null
        return GatewayQueuedMessages(steering = steering, followUp = followUp)
    }

    /** 替换该会话的扩展状态完整列表：丢弃空白文本、按 key 去重（后者覆盖前者、
     *  位置不变），空列表即清除该会话条目（对齐 Pi 的 setStatus(key, undefined)）。 */
    private fun replaceExtensionStatuses(sessionId: String, statuses: List<GatewayStatusEntry>) {
        val entries = mutableMapOf<String, GatewayStatusEntry>()
        statuses.forEach { entry ->
            if (entry.key.isBlank() || entry.text.isBlank()) return@forEach
            entries[entry.key] = entry
        }
        if (entries.isEmpty()) extensionStatuses.remove(sessionId)
        else extensionStatuses[sessionId] = entries.values.toList()
    }

    private fun makeSnapshot(): SharedMobileSnapshot {
        val selected = sessionListState.selectedSessionId
        return SharedMobileSnapshot(
            sessions = sessionListState.sessions,
            selectedSessionId = selected,
            conversation = selected?.let { ConversationProjector.make(eventsBySession[it].orEmpty()) }.orEmpty(),
            modelCatalog = modelCatalog,
            sessionStates = sessionStates.toMap(),
            extensionStatuses = extensionStatuses.toMap(),
            lastFrameKind = lastFrameKind,
            lastError = lastError
        )
    }
}

/** 对外稳定门面，避免平台 UI 直接依赖内部 Reducer。 */
class SharedMobileFacade {
    fun moduleSummary(): String = SharedModuleInfo.summary()

    fun decodeFrameKind(json: String): String? =
        runCatching { GatewayWireDecoder.decode(json).kind }.getOrNull()

    fun makeStore(): SharedMobileStore = SharedMobileStore()

    fun makeSessionListStore(
        newSessionTitle: String,
        remoteSessionPrefix: String,
        blankSessionPrefix: String
    ): SharedSessionListStore = SharedSessionListStore(
        newSessionTitle = newSessionTitle,
        remoteSessionPrefix = remoteSessionPrefix,
        blankSessionPrefix = blankSessionPrefix
    )

    fun makeTrajectoryStore(): SharedTrajectoryStore = SharedTrajectoryStore()

    fun makeHistoryStore(): SharedHistoryStore = SharedHistoryStore()

    fun makeConversationStore(
        userMessage: String,
        context: String,
        streamingAssistant: String,
        streamingReasoning: String,
        finalAssistant: String,
        finalReasoning: String,
        assemblingTool: String,
        toolResultDone: String,
        toolResultFailed: String
    ): SharedConversationStore = SharedConversationStore(
        ConversationProjectionLabels(
            userMessage = userMessage,
            context = context,
            streamingAssistant = streamingAssistant,
            streamingReasoning = streamingReasoning,
            finalAssistant = finalAssistant,
            finalReasoning = finalReasoning,
            assemblingTool = assemblingTool,
            toolResultDone = toolResultDone,
            toolResultFailed = toolResultFailed
        )
    )

}
