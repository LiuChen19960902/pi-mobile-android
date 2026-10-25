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
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewaySessionSummary
import com.finecat.pimobile.shared.protocol.GatewayStatusEntry
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import kotlin.jvm.JvmOverloads
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock

data class SharedMobileSnapshot(
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    /** 确认删除的摘要单独保留，当前页仍可查看缓存正文和草稿。 */
    val deletedSessions: Map<String, SessionSummary> = emptyMap(),
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
    private val deletedSessions = mutableMapOf<String, SessionSummary>()
    private val eventsBySession = mutableMapOf<String, List<SessionEvent>>()
    private var modelCatalog: GatewayModelCatalog? = null
    private var modelCatalogSessionId: String? = null
    private var bridgeMode = false
    private val offlineBridgeSessions = mutableSetOf<String>()
    private val sessionStates = mutableMapOf<String, GatewaySessionState>()
    /**
     * 桥接帧水位（R1）：每会话的实例身份、已投影正文的事件 seq、已应用状态 seq。
     * 两个水位分开记：拉式 `state` 只有状态，不能证明事件正文已投影（它会经由独立
     * 请求 socket 先于订阅上在途的正文事件回到手机）；分开后旧状态仍被拒绝，正文
     * 又不会被跳过。
     */
    private val bridgeWatermarks = mutableMapOf<String, BridgeWatermark>()

    private data class BridgeWatermark(
        val identity: Pair<String, String>,
        /** 已投影正文的事件 seq；只有事件本身和带正文的 history 补齐会推进。 */
        val eventSeq: Int?,
        /** 已应用状态 seq；拉式 `state` 与快照推进，用来判旧事件的状态字段是否过期。 */
        val stateSeq: Int?,
    )
    private val extensionStatuses = mutableMapOf<String, List<GatewayStatusEntry>>()
    private var lastFrameKind: String? = null
    private var lastError: String? = null

    fun snapshot(): SharedMobileSnapshot = makeSnapshot()

    fun selectSession(sessionId: String?): SharedMobileSnapshot {
        if ((bridgeMode || modelCatalogSessionId != null) && modelCatalogSessionId != sessionId) {
            modelCatalog = null
            modelCatalogSessionId = null
        }
        sessionListState = SessionListReducer.reduce(sessionListState, SessionListAction.Select(sessionId))
        sessionId?.let {
            sessionListState = SessionListReducer.reduce(sessionListState, SessionListAction.MarkRead(it))
        }
        return makeSnapshot()
    }

    /**
     * 桥接拉式快照是否仍具权威性。无身份/水位说明它不是实时来源（磁盘回退或旧网关），
     * 代际不同或水位更低说明它早于已消费的状态，两种情况都不覆盖运行态与统计。
     */
    private fun acceptBridgePullSnapshot(frame: GatewayFrame, sessionId: String): Boolean {
        if (!bridgeMode) return true
        val instanceId = frame.instanceId?.takeIf(String::isNotBlank)
        val epoch = frame.epoch?.takeIf(String::isNotBlank)
        val seq = frame.bridgeSeq
        if (instanceId == null || epoch == null || seq == null) return false
        val identity = instanceId to epoch
        val known = bridgeWatermarks[sessionId]
        if (known != null && known.identity != identity) return false
        val last = maxOf(known?.eventSeq ?: Int.MIN_VALUE, known?.stateSeq ?: Int.MIN_VALUE)
        if (last != Int.MIN_VALUE && seq < last) return false
        // history 带完整正文，能让正文水位跟着前进；state 只有状态，只推进状态水位。
        val contentCoversFrame = frame.kind == "history"
        bridgeWatermarks[sessionId] = BridgeWatermark(
            identity = identity,
            eventSeq = if (contentCoversFrame) maxOf(seq, known?.eventSeq ?: seq) else known?.eventSeq,
            stateSeq = maxOf(seq, known?.stateSeq ?: seq),
        )
        return true
    }

    /** 快照闸门结果：正文种子是否可用、状态字段是否仍权威。 */
    private data class SnapshotGate(val accepted: Boolean, val statusAuthoritative: Boolean)

    /**
     * 同代际快照的正文前缀与运行状态分开判：快照来自订阅 socket、state 来自独立
     * 查询 socket，跨 fd 不保证顺序，较早捕获的订阅快照可能晚于更高水位的 state
     * 到达。低于已应用 stateSeq 的快照仍可补正文前缀（partial/tools 种子），
     * 但不能降低状态水位、恢复旧 busy/compacting/pending 或改回旧 model/context；
     * 严格低于已投影正文水位的快照仍是迟到重放，整帧不接受。
     */
    private fun noteBridgeSnapshot(frame: GatewayFrame, sessionId: String): SnapshotGate {
        val instanceId = frame.instanceId?.takeIf(String::isNotBlank) ?: return SnapshotGate(true, true)
        val epoch = frame.epoch?.takeIf(String::isNotBlank) ?: return SnapshotGate(true, true)
        val identity = instanceId to epoch
        val previous = bridgeWatermarks[sessionId]
        val sameGeneration = previous?.takeIf { it.identity == identity }
        val knownEvent = sameGeneration?.eventSeq
        val knownState = sameGeneration?.stateSeq
        val seq = frame.bridgeSeq
        if (seq != null && knownEvent != null && seq < knownEvent) return SnapshotGate(false, false)
        bridgeWatermarks[sessionId] = BridgeWatermark(
            identity = identity,
            eventSeq = seq ?: knownEvent,
            stateSeq = maxOf(seq ?: knownState ?: Int.MIN_VALUE, knownState ?: Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE },
        )
        return SnapshotGate(true, seq == null || knownState == null || seq >= knownState)
    }

    /**
     * 实时事件只推进同代际的正文水位。
     * 返回 true 表示这条事件的状态字段仍具权威（未被更新的 `state` 覆盖）。
     */
    private fun noteBridgeEvent(frame: GatewayFrame, sessionId: String): Boolean {
        val instanceId = frame.instanceId?.takeIf(String::isNotBlank) ?: return true
        val epoch = frame.epoch?.takeIf(String::isNotBlank) ?: return true
        val identity = instanceId to epoch
        val known = bridgeWatermarks[sessionId]
        if (known != null && known.identity != identity) return false
        val seq = frame.bridgeSeq ?: return true
        if (known?.eventSeq != null && seq <= known.eventSeq) return false
        bridgeWatermarks[sessionId] = BridgeWatermark(
            identity = identity,
            eventSeq = seq,
            stateSeq = known?.stateSeq,
        )
        // 比已应用状态更旧的事件：正文仍投影（由 Runtime 负责），但状态字段作废。
        val stateSeq = known?.stateSeq ?: return true
        return seq > stateSeq
    }

    fun acceptFrame(json: String): SharedMobileSnapshot {
        try {
            val frame = GatewayWireDecoder.decode(json)
            if (frame.kind != "sessions" && frame.sessionId in deletedSessions) return makeSnapshot()
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
                    sessions.forEach { deletedSessions.remove(it.id) }
                    val visibleIds = sessions.mapTo(mutableSetOf()) { it.id }
                    val removed = frame.deletedSessionIds.orEmpty().toSet() - visibleIds
                    sessionListState.sessions.filter { it.id in removed }.forEach { deletedSessions[it.id] = it }
                    removed.forEach { sessionStates.remove(it); extensionStatuses.remove(it) }
                    sessionListState = sessionListState.copy(sessions = sessionListState.sessions.filterNot { it.id in removed })
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
                    val selectedBefore = sessionListState.selectedSessionId
                    sessionListState = SessionListReducer.reduce(
                        sessionListState,
                        SessionListAction.MessageSent(
                            sessionId = sessionId,
                            insertedAtEpochSeconds = frame.time
                                ?.let(::normalizeEpochSeconds)
                                ?: nowEpochSeconds()
                        )
                    )
                    if (frame.nativeResume == true) {
                        sessionListState = sessionListState.copy(selectedSessionId = selectedBefore)
                    }
                    if (frame.isStreaming != null && frame.isCompacting != null) {
                        // 提交ACK仍由上层释放发送等待；其旧快照不能倒退当前桥接状态。
                        if (bridgeMode && (frame.duplicate == true || sessionId in offlineBridgeSessions ||
                            !acceptBridgePullSnapshot(frame, sessionId))) return@let
                        val previous = sessionStates[sessionId]
                        val pending = frame.pendingMessages ?: if (frame.disposition == "submitted")
                            previous?.pendingMessages ?: false else false
                        val submittedMode = frame.mode?.takeIf {
                            frame.disposition == "submitted" && it in listOf("steer", "followUp")
                        }
                        sessionStates[sessionId] = GatewaySessionState(
                            sessionId = sessionId,
                            isStreaming = frame.isStreaming,
                            isCompacting = frame.isCompacting,
                            model = frame.model,
                            thinkingLevel = frame.thinkingLevel,
                            contextUsage = frame.contextUsage,
                            queuedMessages = queuedMessagesOf(frame.queuedMessages),
                            // submitted 不含队列真值时，不抹掉此前快照确认的 pending。
                            pendingMessages = pending,
                            submittedQueueModes = if (pending)
                                (previous?.submittedQueueModes.orEmpty() + listOfNotNull(submittedMode)).distinct()
                            else emptyList()
                        )
                    }
                }
                "models" -> {
                    // 原生目录按会话归属；迟到的另一个 CLI 目录不能进入当前选择器。
                    if ((!bridgeMode && frame.sessionId == null) ||
                        (bridgeMode && frame.nativeRead == true && frame.sessionId == sessionListState.selectedSessionId) ||
                        (frame.sessionId != null && frame.sessionId == sessionListState.selectedSessionId)) {
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
                    // 桥接的拉式快照（state/history）可能晚于更新的终态事件到达：
                    // 旧快照不覆盖运行态/压缩/统计，避免 Working 复活或旧 idle 清掉新任务。
                    if ((frame.kind == "state" || frame.kind == "history") &&
                        !acceptBridgePullSnapshot(frame, sessionId)
                    ) return@let
                    sessionStates[sessionId] = GatewaySessionState(
                        sessionId = sessionId,
                        isStreaming = frame.isStreaming == true,
                        isCompacting = frame.isCompacting == true,
                        model = frame.model,
                        thinkingLevel = frame.thinkingLevel,
                        contextUsage = frame.contextUsage,
                        queuedMessages = queuedMessagesOf(frame.queuedMessages),
                        pendingMessages = frame.pendingMessages == true,
                        submittedQueueModes = if (frame.pendingMessages == true)
                            sessionStates[sessionId]?.submittedQueueModes.orEmpty() else emptyList()
                    )
                }
                // 桥接快照（PROTOCOL §4.31）是扩展侧真值：busy 即运行中，
                // 收到即证明实例在线；pending 不伪造文本队列。
                "host-mode" -> {
                    val wasBridge = bridgeMode
                    bridgeMode = frame.mode == "bridge"
                    if (frame.changed == true || wasBridge != bridgeMode) {
                        offlineBridgeSessions.clear()
                        modelCatalog = null; modelCatalogSessionId = null
                        extensionStatuses.clear()
                    }
                    if (!bridgeMode) {
                        clearOnlineMarks()
                        // 水位只对桥接代际有意义：切回 SDK 后留着它，等再次切到桥接
                        // 时新实例的应答会因"代际不同"被全拒，直到新快照到达。
                        bridgeWatermarks.clear()
                        sessionStates.keys.toList().forEach { sessionId ->
                            sessionStates[sessionId] = sessionStates.getValue(sessionId).copy(
                                pendingMessages = false, submittedQueueModes = emptyList()
                            )
                        }
                    }
                }
                "bridge-snapshot", "bridge-offline" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    val previousIdentity = bridgeWatermarks[sessionId]?.identity
                    val gate = if (frame.kind == "bridge-snapshot") {
                        noteBridgeSnapshot(frame, sessionId)
                    } else {
                        SnapshotGate(true, true)
                    }
                    if (!gate.accepted) return@let
                    extensionStatuses.remove(sessionId)
                    val previous = sessionStates[sessionId] ?: GatewaySessionState(sessionId = sessionId)
                    val online = frame.kind == "bridge-snapshot"
                    if (!online) offlineBridgeSessions.add(sessionId)
                    else if (gate.statusAuthoritative) offlineBridgeSessions.remove(sessionId)
                    sessionStates[sessionId] = if (!online) {
                        // bridge-offline 没有 busy 字段：离线就是空闲，不能沿用旧运行标记；
                        // 压缩和服务器队列同样已失效，清掉后由独立恢复记录承载未确认提交。
                        previous.copy(
                            isStreaming = false,
                            isCompacting = false,
                            contextUsage = null,
                            model = frame.model ?: previous.model,
                            thinkingLevel = frame.thinkingLevel ?: previous.thinkingLevel,
                            queuedMessages = GatewayQueuedMessages(),
                            pendingMessages = false,
                            submittedQueueModes = emptyList()
                        )
                    } else if (gate.statusAuthoritative) {
                        previous.copy(
                            isStreaming = frame.isStreaming ?: frame.busy ?: previous.isStreaming,
                            isCompacting = frame.isCompacting == true,
                            contextUsage = frame.contextUsage,
                            model = frame.model ?: previous.model,
                            thinkingLevel = frame.thinkingLevel ?: previous.thinkingLevel,
                            // 桥接只有是否待执行的真值，不能沿用 SDK 的旧队列正文。
                            queuedMessages = GatewayQueuedMessages(),
                            pendingMessages = frame.pending ?: previous.pendingMessages,
                            submittedQueueModes = if (frame.pending != false &&
                                previousIdentity == (frame.instanceId to frame.epoch))
                                previous.submittedQueueModes else emptyList()
                        )
                    } else {
                        // 正文前缀照收（投影层负责种子），状态保持更新的 state 结论。
                        previous
                    }
                    sessionListState = SessionListReducer.reduce(
                        sessionListState,
                        SessionListAction.OnlineChanged(sessionId, online)
                    )
                    if (gate.statusAuthoritative) syncSessionActivity(sessionId)
                }
                "event" -> applyEventFrame(frame, emptyList())
                "aborted", "dequeued" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    if (frame.kind == "aborted" && frame.status == "submitted") return@let
                    // 新网关在 idle ACK 中带权威快照；旧网关裸 ACK 不证明队列已清空。
                    val previous = sessionStates[sessionId] ?: GatewaySessionState(sessionId = sessionId)
                    sessionStates[sessionId] = previous.copy(
                        isStreaming = frame.isStreaming ?: if (frame.kind == "aborted") false else previous.isStreaming,
                        isCompacting = frame.isCompacting ?: if (frame.kind == "aborted") false else previous.isCompacting,
                        contextUsage = frame.contextUsage ?: previous.contextUsage,
                        queuedMessages = if (frame.queuedMessages != null) queuedMessagesOf(frame.queuedMessages) else previous.queuedMessages,
                        pendingMessages = frame.pendingMessages
                            ?: if (frame.queuedMessages != null) false else previous.pendingMessages,
                        submittedQueueModes = if (frame.pendingMessages == true)
                            previous.submittedQueueModes else emptyList(),
                    )
                }
                "session-named" -> frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    val previous = sessionStates[sessionId] ?: GatewaySessionState(sessionId = sessionId)
                    sessionStates[sessionId] = previous.copy(
                        sessionId = sessionId,
                        isStreaming = frame.isStreaming ?: previous.isStreaming,
                        isCompacting = frame.isCompacting ?: previous.isCompacting,
                        model = frame.model ?: previous.model,
                        thinkingLevel = frame.thinkingLevel ?: previous.thinkingLevel,
                        contextUsage = frame.contextUsage ?: previous.contextUsage,
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
     * event 帧入口：`frame` 已由调用方解码，`records` 是同帧归一后的事件。
     *
     * raw 状态字段与归一事件（turn/compact/queue）按**同一个**"状态是否仍权威"
     * 结论消费：旧事件的正文照常合并，但不得把更新的 state 改回去。调用方不再
     * 需要把 raw 重新解析一遍，chunk 帧也不会多走一次全量归一。
     */
    fun acceptEventFrame(frame: GatewayFrame, records: List<SessionEvent> = emptyList()): SharedMobileSnapshot {
        if (frame.sessionId in deletedSessions) return makeSnapshot()
        lastFrameKind = frame.kind
        lastError = null
        applyEventFrame(frame, records)
        // 抽屉与 Working 使用同一份权威忙闲状态。
        if (frame.isStreaming != null && frame.isCompacting != null) {
            frame.sessionId?.let(::syncSessionActivity)
        }
        return makeSnapshot()
    }

    private fun applyEventFrame(frame: GatewayFrame, liveEvents: List<SessionEvent>) {
        // 桥接事件一律按 bridgeSeq 与 state 水位比较；只有携带 isStreaming/isCompacting
        // 的帧才额外落 raw 状态字段（queue_update 等帧不带这两个字段，但同样可能过期）。
        val bridgeSessionId = frame.instanceId?.let { frame.sessionId?.takeIf(String::isNotBlank) }
        val statusAuthoritative = if (bridgeSessionId != null) {
            val authoritative = noteBridgeEvent(frame, bridgeSessionId)
            // 旧事件（seq 早于已应用的 state）只补正文，不能把运行态/统计改回去。
            if (authoritative && frame.isStreaming != null && frame.isCompacting != null) {
                val previous = sessionStates[bridgeSessionId]
                    ?: GatewaySessionState(sessionId = bridgeSessionId)
                sessionStates[bridgeSessionId] = previous.copy(
                    isStreaming = frame.isStreaming,
                    isCompacting = frame.isCompacting,
                    contextUsage = frame.contextUsage ?: previous.contextUsage
                )
            }
            authoritative
        } else {
            true
        }
        liveEvents.forEach { record -> acceptEvent(record, statusAuthoritative) }
    }

    /**
     * pi 实时事件入口：只拿到归一事件、没有对应帧字段时的入口。
     *
     * @param statusAuthoritative 为 null 时按当前模式推断：SDK 模式事件自带状态；
     *   桥接模式的事件状态只由帧的 `bridgeSeq` 与 `state` 水位比较决定，裸事件
     *   无法证明自己没过期，因此默认不改运行/压缩/队列状态，正文照常合并。
     */
    @JvmOverloads
    fun acceptLiveEvents(records: List<SessionEvent>, statusAuthoritative: Boolean? = null): SharedMobileSnapshot {
        val authoritative = statusAuthoritative ?: !bridgeMode
        records.forEach { record -> acceptEvent(record, authoritative) }
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
        deletedSessions.clear()
        eventsBySession.clear()
        modelCatalog = null
        sessionStates.clear()
        bridgeWatermarks.clear()
        extensionStatuses.clear()
        lastFrameKind = null
        lastError = null
        return makeSnapshot()
    }

    /**
     * @param statusAuthoritative 该事件携带的状态是否仍权威。为 false（事件 seq 早于
     *   已应用的 state）时只合并正文与列表元数据，不翻转运行/压缩/队列状态。
     */
    private fun acceptEvent(record: SessionEvent, statusAuthoritative: Boolean) {
        if (record.sessionId in deletedSessions) return
        // 压缩状态事件（PiEventNormalizer 归一）：compact/start 置压缩中，
        // compact/end / notice/compact（失败可见行）收尾；start/end 不进对话流。
        when (record.event.type) {
            // turn/start|end 直接翻转 isStreaming（与 compact 的直接翻转对称）：
            // 轮末 state 应答丢失时（断连/lane 拒绝），事件流本身就是纠正信号
            // （agent_settled → 空闲），否则运行中发送键变停止键后会永久卡住。
            // 不 return：照常进 reducer/事件存储。
            "turn/start", "turn/end" -> if (statusAuthoritative) {
                val streaming = record.event.type == "turn/start"
                val previous = sessionStates[record.sessionId] ?: GatewaySessionState(sessionId = record.sessionId)
                sessionStates[record.sessionId] = previous.copy(
                    isStreaming = streaming,
                    // turn/end 只来自 agent_settled，公开契约保证没有待续队列。
                    pendingMessages = streaming && previous.pendingMessages,
                    queuedMessages = if (streaming) previous.queuedMessages else null,
                    submittedQueueModes = if (streaming) previous.submittedQueueModes else emptyList(),
                )
            }
            "compact/start" -> if (statusAuthoritative) {
                updateCompacting(record.sessionId, true)
            }
            "compact/end", "notice/compact" -> if (statusAuthoritative) {
                updateCompacting(record.sessionId, false)
            }
            // pi 原生 queue_update 归一：steer/followUp 消费后立即刷新快照，
            // 对齐 TUI 的实时清条（state 帧是请求-应答，轮内不会刷新）。
            // 旧事件的队列内容不代表更新 state 的结论，整条忽略（也不进对话流）。
            "queue/update" -> {
                if (!statusAuthoritative) return
                val queued = GatewayQueuedMessages(
                    steering = record.event.steering.orEmpty(),
                    followUp = record.event.followUp.orEmpty()
                )
                sessionStates[record.sessionId] = (sessionStates[record.sessionId]
                    ?: GatewaySessionState(sessionId = record.sessionId)).copy(
                    queuedMessages = if (queued.isEmpty) null else queued,
                    pendingMessages = false,
                    submittedQueueModes = emptyList(),
                )
                return
            }
        }
        sessionListState = SessionListReducer.reduce(
            sessionListState,
            SessionListAction.EventReceived(
                event = record,
                insertedAtEpochSeconds = normalizeEpochSeconds(record.time),
                statusAuthoritative = statusAuthoritative
            )
        )
        if (statusAuthoritative &&
            record.event.type in setOf("turn/start", "turn/end", "compact/start", "compact/end", "notice/compact")
        ) {
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
            deletedSessions = deletedSessions.toMap(),
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
