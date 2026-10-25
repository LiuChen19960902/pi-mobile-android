package com.finecat.pimobile.android

import com.finecat.pimobile.shared.facade.SharedConversationBootstrap
import com.finecat.pimobile.shared.facade.SharedConversationPatch
import com.finecat.pimobile.shared.facade.SharedConversationStore
import com.finecat.pimobile.shared.facade.SharedHistoryBootstrap
import com.finecat.pimobile.shared.facade.SharedHistoryEffect
import com.finecat.pimobile.shared.facade.SharedHistoryPatch
import com.finecat.pimobile.shared.facade.SharedHistoryStore
import com.finecat.pimobile.shared.facade.SharedMviEvent
import com.finecat.pimobile.shared.facade.SharedMobileSnapshot
import com.finecat.pimobile.shared.facade.SharedMobileStore
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.sessionEventIdentity
import com.finecat.pimobile.shared.projection.TrajectoryNode
import com.finecat.pimobile.shared.projection.TrajectoryProjection
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayHistoryMessage
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.sync.HistorySessionState
import com.finecat.pimobile.shared.sync.HistorySyncConfiguration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val adapterJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}

/** pi 的历史消息条目原样透传 usage/timestamp/details 等渲染外字段，DTO 只消费
 *  渲染所需子集；严格解码会把整条消息丢掉，造成 entryIds 与 messages 数量错开
 *  （进会话转圈 bug 的根因），消息解码单独放宽未知键。 */
private val historyMessageJson = Json { ignoreUnknownKeys = true }
private const val MAX_UPSTREAM_ERROR_CHARACTERS = 240
private val SESSION_OUTCOME_EVENTS = setOf(
    "assistant/message", "user/message", "turn/start",
    "compact/start", "compact/end", "compact/done", "notice/compact"
)

/** Android 的 KMP MVI 适配器：history/live 水位与流式 patch 均由现有共享 Store 决定。 */
internal class AndroidGatewayProjection(
    private val mobileStore: SharedMobileStore = SharedMobileStore(),
    private val historyStore: SharedHistoryStore = SharedHistoryStore(
        HistorySyncConfiguration(pagesPerBatch = 1)
    ),
    private val conversationStore: SharedConversationStore = SharedConversationStore(),
    private val onHistoryPageRequested: (sessionId: String, beforeEntryId: String?, resetLeaf: Boolean) -> Unit = { _, _, _ -> }
) {
    private val historyEvents = mutableMapOf<String, List<SessionEvent>>()
    /** 已由合并后的消息确认的失败；选择会话重载期间保留，显式换分支/重置时失效。 */
    private val failedSessionIds = mutableSetOf<String>()
    /** 压缩失败不落Pi消息文件；旧历史重载不能用上次成功回答覆盖这次已知失败。 */
    private val failedCompactionTimes = mutableMapOf<String, Double>()
    /** history 帧 latestEntryId（最新叶子：通常为文件视图、与 getLeafId 同源；
     *  网关并入 live 未落盘尾部时为 live 叶子/活跃链尾，非文件水位）与活跃
     *  链尾条目 id，供“活跃链落后于最新”的跳最新出口比对（1.5.9）。 */
    private val latestLeafEntryIds = mutableMapOf<String, String>()
    private val activeTailEntryIds = mutableMapOf<String, String>()
    /** 本端写入的、不会投影成 wire message 的叶子 entry id（label-put 追加的 LabelEntry）：
     *  history 的 `latestEntryId` 是原始文件叶子，而 `entryIds` 只收可渲染消息，
     *  写标签后两者必然不等，`isBehindLatest` 会误报“跳到最新”（点了还会把
     *  switch-branch 打到 LabelEntry 上）。这些 id 是我们自己写的，可以确知其
     *  不渲染，遇它不算落后；后续真有新消息时文件叶子会变成新消息 id，判定自然恢复。
     *  只覆盖本端写入：其他客户端产生的尾部簿记条目（改会话名/切模型）仍会误报，
     *  整类归一属 §4.6 语义变更，见 TODO HIST03（隔离工作区原误编号HIST02）。 */
    private val nonRenderingLeafIds = mutableMapOf<String, MutableSet<String>>()
    private val historyLastSequences = mutableMapOf<String, Int>()
    private val historyHasMore = mutableMapOf<String, Boolean>()
    private val historySessionStates = mutableMapOf<String, HistorySessionState>()
    private var historyPendingSessionId: String? = null
    /** pi 历史消息的合成 seq 计数器（负空间，newest=-1 向更旧递减；live 事件用 pi seq ≥1）。 */
    private val historySyntheticSeqs = mutableMapOf<String, Int>()
    /** 已发起过 history 引导的会话（sent 帧补拉只对从未引导过的会话生效）。 */
    private val historyStartedSessions = mutableSetOf<String>()
    /** 每会话当前已知最旧条目 id（加载更旧页的游标）。 */
    private val historyOldestEntryIds = mutableMapOf<String, String>()
    /** 每会话最近一次 history 加载失败码；重新发起加载或成功落页时清除。 */
    private val historyFailures = mutableMapOf<String, String>()
    // entryId→seq 索引已删除：只写无人读（全仓零引用），会话清理漏删即线性增长。
    // 如需该索引，重建时必须同步进 clearSessionBuffers。
    private val conversationItems = mutableMapOf<String, List<ConversationItem>>()
    /** 最新页重拉会先清缓冲；宿主删除随后才被确认时仍能保留用户刚看过的正文。 */
    private val historyReloadBackups = mutableMapOf<String, List<ConversationItem>>()
    private val restoringDeletedSessions = mutableSetOf<String>()
    private val conversationLastSequences = mutableMapOf<String, Int>()
    /** 桥接实例代际：instanceId+epoch。变化时先丢弃旧代际实时投影，等新快照重建。 */
    private val bridgeGenerations = mutableMapOf<String, String>()
    /** 每代际最后消费的 bridgeSeq；同代际倒退说明是迟到帧。 */
    private val bridgeLastSequences = mutableMapOf<String, Int>()
    /** wire seq 每次订阅归零；本地已接收事件保持单调，避免覆盖旧记录。 */
    private val bridgeProjectionSequences = mutableMapOf<String, Int>()
    /** normalizer 每份快照归零，投影流键分配新范围，避免与已完成消息的键碰撞。 */
    private val bridgeTurnOffsets = mutableMapOf<String, Int>()
    private val bridgeLatestTurns = mutableMapOf<String, Int>()
    /** 快照里的进行中正文/活动工具；事件或历史一到就被权威内容替换。 */
    private val bridgeOverlays = mutableMapOf<String, BridgeOverlay>()
    /** 当前快照流在本地投影里的 turn/step；输入坐标经快照偏移后落在同一键。 */
    private val bridgeStreamPositions = mutableMapOf<String, Pair<Int, Int>>()
    /** 已种进 ConversationStore 的快照流 key；新快照/历史重建后要能精确替换。 */
    private val bridgeSeededStreamKeys = mutableMapOf<String, List<String>>()
    /** 快照之后已到达但还没写进历史的 delta；历史重建后用它补回完整前缀。 */
    private val bridgeStreamSuffixes = mutableMapOf<String, Pair<String, String>>()
    /** 最近一次快照的 partial；历史重建会用同一前缀重新种流，不能只依赖一次性条目。 */
    private val bridgeSnapshotPartials = mutableMapOf<String, JsonValue>()
    /** 最新历史请求发出时的代际/本地前缀对象；迟到idle不能清替换后的新快照。 */
    private val bridgeHistoryCheckpoints = mutableMapOf<String, Pair<String, JsonValue>>()
    private var controlSnapshot = mobileStore.snapshot()
    private var lastFrameKind: String? = null
    private var lastError: String? = null
    private val historyEnvelope = MviEnvelopeValidator("history")
    private val conversationEnvelope = MviEnvelopeValidator("conversation")
    private val historySubscription = historyStore.subscribe(::acceptHistoryMviEvent)
    private val conversationSubscription = conversationStore.subscribe(::acceptConversationMviEvent)

    fun snapshot(): SharedMobileSnapshot {
        val deleted = controlSnapshot.selectedSessionId in controlSnapshot.deletedSessions
        return controlSnapshot.copy(
            sessions = controlSnapshot.sessions.map { session ->
                session.copy(hasFailure = !session.isRunning && session.id in failedSessionIds)
            },
            conversation = controlSnapshot.selectedSessionId?.let {
                if (deleted || it in restoringDeletedSessions) historyReloadBackups[it].orEmpty() else conversationFor(it)
            }.orEmpty(),
            selectedHistoryHasMore = !deleted && controlSnapshot.selectedSessionId?.let(historyHasMore::get) == true,
            selectedHistoryEarliestSequence = controlSnapshot.selectedSessionId
                ?.let(historyEvents::get)?.firstOrNull()?.seq,
            selectedHistoryNextCursor = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.nextCursor,
            selectedHistoryIsLoading = !deleted && controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoading == true,
            selectedHistoryIsLoadingOlder = !deleted && controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoadingOlder == true,
            selectedHistoryLoadedEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.loaded ?: 0,
            selectedHistoryTotalEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.total,
            selectedHistoryError = if (deleted) null else controlSnapshot.selectedSessionId?.let(historyFailures::get),
            lastFrameKind = lastFrameKind ?: controlSnapshot.lastFrameKind,
            lastError = lastError ?: controlSnapshot.lastError
        )
    }

    fun selectSession(sessionId: String?): SharedMobileSnapshot {
        controlSnapshot = mobileStore.selectSession(sessionId)
        if (sessionId == null) return snapshot()
        startHistory(sessionId, older = false, preserveFailure = true)
        return snapshot()
    }

    /**
     * 取历史页。[preserveLive] 为真时不先清空缓冲：turn/end 自动重拉属于“补齐尾部”，
     * 清空会让界面瞬时只剩加载态、丢掉正在看的内容；实时（正 seq）记录由 store 按条目
     * 身份与新页对消，旧的最新窗口则在第一页到达时整体替换。
     */
    fun loadHistory(sessionId: String, older: Boolean, preserveLive: Boolean = false): SharedMobileSnapshot {
        startHistory(sessionId, older, preserveLive = preserveLive)
        return snapshot()
    }

    /**
     * 重连后对选中会话补拉最新页：pi 的 seq 只在句柄生命周期内单调，所以按 history
     * 重建本地缓冲（[startHistory] 的 latest 非 preserveLive 分支负责清空）。
     * 该会话还有批次在途时不先清屏，[startHistory] 会把意图归并到批次落地后再发。
     */
    fun catchUpSelectedHistoryAfterReconnect(): SharedMobileSnapshot {
        controlSnapshot.selectedSessionId?.let { sessionId ->
            // resetLeaf 让网关释放手动叶子、活跃链锚回文件尾——断连期间主线新消息
            // 随本次重拉回到客户端。
            startHistory(sessionId, older = false, resetLeaf = true)
        }
        return snapshot()
    }

    private data class PendingLatestReload(val resetLeaf: Boolean, val preserveLive: Boolean, val preserveFailure: Boolean)

    /**
     * “待取最新页”的会话 → 参数。同一会话只留最后一次意图：[HistoryReducer.start] 对
     * 已在途的批次直接返回 unchanged，如果不记下来，调用方就再也没机会重发（会话停在旧窗口）。
     */
    private val pendingLatestReloads = mutableMapOf<String, PendingLatestReload>()

    private fun startHistory(
        sessionId: String,
        older: Boolean,
        resetLeaf: Boolean = false,
        preserveLive: Boolean = false,
        preserveFailure: Boolean = false
    ) {
        if (sessionId in controlSnapshot.deletedSessions) return
        if (!older) {
            if (historySessionStates[sessionId]?.isLoading == true) {
                pendingLatestReloads[sessionId] = PendingLatestReload(resetLeaf, preserveLive, preserveFailure)
                return
            }
            pendingLatestReloads.remove(sessionId)
        }
        val local = historyEvents[sessionId].orEmpty()
        if (!older && !preserveLive) {
            conversationFor(sessionId).takeIf { it.isNotEmpty() }?.let { historyReloadBackups[sessionId] = it }
            // 全新加载 = pi 语义下的重新同步：先清空，避免合成 seq 与旧缓冲重复。
            clearSessionBuffers(sessionId, preserveFailure)
        }
        // preserveLive 重拉不清空、也不回卷合成 seq 计数器：新页继续在更负的方向落位，
        // 由 store 侧“LATEST 第一页整窗替换旧最新窗口”丢掉旧负 seq 记录（此处不可能有
        // 在途批次，上面已归并），回卷反而会在重拉失败后留下计数器与窗口错位。
        // hasLocalEvents 对 preserveLive 如实上报：这一页失败时 reducer 的 fail 会按
        // hasLocalEvents 保留游标，抹掉 nextCursor 会让 loadOlderHistory 永久早退（ Finding 1）。
        historyStartedSessions += sessionId
        historyFailures.remove(sessionId)
        historyStore.start(
            sessionId = sessionId,
            older = older,
            hasLocalEvents = if (older || preserveLive) local.isNotEmpty() else false,
            earliestEntryId = if (older) historyOldestEntryIds[sessionId] else null,
            resetLeaf = resetLeaf
        )
    }

    /** 当前批次结束后补发待取的“最新页”；会话已切走则丢弃该意图。 */
    private fun flushPendingLatestReload(sessionId: String) {
        val pending = pendingLatestReloads.remove(sessionId) ?: return
        if (controlSnapshot.selectedSessionId != sessionId) return
        startHistory(
            sessionId,
            older = false,
            resetLeaf = pending.resetLeaf,
            preserveLive = pending.preserveLive,
            preserveFailure = pending.preserveFailure
        )
    }

    private fun clearSessionBuffers(sessionId: String, preserveFailure: Boolean = false) {
        if (!preserveFailure) {
            failedSessionIds.remove(sessionId)
            failedCompactionTimes.remove(sessionId)
        }
        historyStore.clearSession(sessionId)
        conversationStore.clearSession(sessionId)
        historyEvents.remove(sessionId)
        bridgeProjectionSequences.remove(sessionId)
        bridgeHistoryCheckpoints.remove(sessionId)
        historySyntheticSeqs.remove(sessionId)
        historyOldestEntryIds.remove(sessionId)
        historyHasMore.remove(sessionId)
        historyFailures.remove(sessionId)
        historySessionStates.remove(sessionId)
        latestLeafEntryIds.remove(sessionId)
        activeTailEntryIds.remove(sessionId)
        nonRenderingLeafIds.remove(sessionId)
        conversationItems.remove(sessionId)
        conversationLastSequences.remove(sessionId)
        mobileStore.clearSessionEvents(sessionId)
    }

    fun acceptFrame(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        normalizedEvents: List<SessionEvent> = emptyList()
    ): SharedMobileSnapshot {
        if (frame.kind == "paired") return reset()
        if (frame.kind != "sessions" && (frame.sessionId ?: correlatedSessionId) in controlSnapshot.deletedSessions) return snapshot()
        val previouslyDeleted = controlSnapshot.deletedSessions.keys
        lastFrameKind = frame.kind
        lastError = null
        when (frame.kind) {
            "history" -> {
                acceptHistory(frame, correlatedSessionId)
                // history 帧的会话级 state（isStreaming/isCompacting/model/queued）
                // 同样要进 mobileStore：重连 catch-up 时它就是唯一的状态纠正来源。
                controlSnapshot = mobileStore.acceptFrame(rawJson)
            }
            "event" -> if (acceptBridgeEvent(frame)) {
                // 归一一次，raw 状态字段与 normalized 事件在同一次 acceptFrame 里按
                // 同一个"状态是否权威"结论消费：旧事件的 turn/compact/queue 不能
                // 绕过门控把更新的 state 改回去，正文仍照常合并。
                val events = bridgeProjectionEvents(frame, normalizedEvents)
                controlSnapshot = mobileStore.acceptEventFrame(frame, events)
                acceptLive(frame, events)
            }
            "bridge-snapshot" -> {
                acceptBridgeSnapshot(frame)
                controlSnapshot = mobileStore.acceptFrame(rawJson)
            }
            "bridge-offline" -> {
                frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    bridgeOverlays.remove(sessionId)
                    // offline 没有后继快照：已种的前缀/后缀和位置全部作废，
                    // 否则重连补拉历史时会把死实例的 partial 再种回来。
                    val keys = buildSet {
                        bridgeSeededStreamKeys.remove(sessionId)?.let(::addAll)
                        bridgeStreamPositions[sessionId]?.let { (turn, step) ->
                            add("text-$turn-$step"); add("reason-$turn-$step")
                        }
                    }.toList()
                    conversationStore.clearStreams(sessionId, keys)
                    historyStore.discardStreamChunks(sessionId, keys)
                    bridgeStreamPositions.remove(sessionId)
                    bridgeStreamSuffixes.remove(sessionId)
                    bridgeSnapshotPartials.remove(sessionId)
                }
                controlSnapshot = mobileStore.acceptFrame(rawJson)
            }
            "hello" -> Unit
            "sent" -> {
                controlSnapshot = mobileStore.acceptFrame(rawJson)
                // pi：新会话的真实 sessionId 只在 sent 帧里揭晓，而 startHistory 的常规
                // 触发点（selectSession/重连补偿）都覆盖不到“从 null 选中的新会话”。
                // 该会话若从未引导过 history，此处必须补拉一次，否则 history/会话
                // store 永不初始化，会话视图会一直停在“正在同步会话内容”占位。
                frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    if (historyStartedSessions.add(sessionId)) {
                        // 首次引导可能晚于实时事件；保留已收到的气泡，交给历史页合并。
                        // 显式刷新与重连仍清空重建。WebSocket 本身不会重排已发出的帧。
                        startHistory(sessionId, older = false, preserveLive = true)
                    }
                }
            }
            else -> controlSnapshot = mobileStore.acceptFrame(rawJson)
        }
        if (frame.kind == "sessions") {
            (previouslyDeleted - controlSnapshot.deletedSessions.keys).forEach { id ->
                if (id == controlSnapshot.selectedSessionId) {
                    restoringDeletedSessions += id
                    startHistory(id, older = false, preserveLive = true)
                } else historyReloadBackups.remove(id)
            }
            controlSnapshot.deletedSessions.keys.forEach { id ->
                if (id !in previouslyDeleted) {
                    conversationFor(id).takeIf { it.isNotEmpty() }?.let { historyReloadBackups[id] = it }
                    historyStore.cancelled(id)
                }
                // 取消加载态，不清正文缓冲；后续迟到的历史/事件也不能复活删除项。
                historySessionStates.remove(id)
                historyFailures.remove(id)
                pendingLatestReloads.remove(id)
                if (historyPendingSessionId == id) historyPendingSessionId = null
            }
        }
        return snapshot()
    }

    fun reset(): SharedMobileSnapshot {
        historyEvents.keys.toList().forEach {
            historyStore.clearSession(it)
            conversationStore.clearSession(it)
        }
        historyEvents.clear()
        failedSessionIds.clear()
        failedCompactionTimes.clear()
        historyLastSequences.clear()
        historyHasMore.clear()
        historySessionStates.clear()
        latestLeafEntryIds.clear()
        activeTailEntryIds.clear()
        nonRenderingLeafIds.clear()
        historySyntheticSeqs.clear()
        historyOldestEntryIds.clear()
        historyStartedSessions.clear()
        historyFailures.clear()
        pendingLatestReloads.clear()
        historyPendingSessionId = null
        conversationItems.clear()
        historyReloadBackups.clear()
        restoringDeletedSessions.clear()
        conversationLastSequences.clear()
        bridgeGenerations.clear()
        bridgeLastSequences.clear()
        bridgeProjectionSequences.clear()
        bridgeTurnOffsets.clear()
        bridgeLatestTurns.clear()
        bridgeStreamPositions.clear()
        bridgeSeededStreamKeys.clear()
        bridgeStreamSuffixes.clear()
        bridgeSnapshotPartials.clear()
        bridgeHistoryCheckpoints.clear()
        bridgeOverlays.clear()
        controlSnapshot = mobileStore.reset()
        lastFrameKind = null
        lastError = null
        return snapshot()
    }

    fun loadFixture(): SharedMobileSnapshot {
        reset()
        controlSnapshot = mobileStore.loadManualTestFixture()
        controlSnapshot.selectedSessionId?.let { sessionId ->
            conversationItems[sessionId] = controlSnapshot.conversation
        }
        lastFrameKind = controlSnapshot.lastFrameKind
        return snapshot()
    }

    fun close() {
        historySubscription.cancel()
        conversationSubscription.cancel()
    }

    fun historyRejected(sessionId: String, code: String) {
        failHistoryLoad(sessionId, code)
    }

    fun historyTimedOut(sessionId: String) {
        bridgeHistoryCheckpoints.remove(sessionId)
        historyStore.timedOut(sessionId)
        flushPendingLatestReload(sessionId)
    }

    fun historyCancelled(sessionId: String) {
        bridgeHistoryCheckpoints.remove(sessionId)
        historyStore.cancelled(sessionId)
        flushPendingLatestReload(sessionId)
    }

    fun trajectory(sessionId: String?): List<TrajectoryNode> =
        sessionId?.let(historyEvents::get)?.let(TrajectoryProjection::make).orEmpty()

    internal fun acceptHistoryMviEventForTest(event: SharedMviEvent) = acceptHistoryMviEvent(event)

    internal fun acceptConversationMviEventForTest(event: SharedMviEvent) =
        acceptConversationMviEvent(event)

    internal fun isBehindLatest(sessionId: String): Boolean {
        val latest = latestLeafEntryIds[sessionId] ?: return false
        val tail = activeTailEntryIds[sessionId] ?: return false
        if (latest == tail) return false
        // 文件叶子只是本端写出的不渲染条目（标签）：没有“更新的消息”可跳，不算落后。
        if (latest in nonRenderingLeafIds[sessionId].orEmpty()) return false
        return true
    }

    /** 记录本端写入的不渲染叶子（label-put 的 LabelEntry），见字段注释。
     *  线程前置条件：只能在 Main（projection actor 的 uiDispatcher/mutationLock
     *  上下文，即 afterFramePublish 路径）调用；生产路径下 projection 的其余
     *  变更都经 actor 串行，不要在 graph != null 时从其它线程直调（审查B风险4）。 */
    internal fun markNonRenderingLeaf(sessionId: String, entryId: String) {
        if (entryId.isBlank()) return
        nonRenderingLeafIds.getOrPut(sessionId) { mutableSetOf() }.add(entryId)
    }

    internal fun latestEntryId(sessionId: String): String? = latestLeafEntryIds[sessionId]

    private fun acceptHistory(frame: GatewayFrame, correlatedSessionId: String?) {
        val sessionId = correlatedSessionId?.takeIf(String::isNotBlank)
        if (sessionId == null) {
            failHistoryLoad(null, "history-correlation-missing")
            return
        }
        val bridgeCheckpoint = bridgeHistoryCheckpoints.remove(sessionId)
        val messages = frame.messages.orEmpty().mapNotNull { item ->
            runCatching {
                historyMessageJson.decodeFromJsonElement(GatewayHistoryMessage.serializer(), item.toJsonElement())
            }.getOrNull()
        }
        val entryIds = frame.entryIds.orEmpty()
        if (entryIds.isNotEmpty() && entryIds.size != messages.size) {
            failHistoryLoad(sessionId, "history-entry-mismatch")
            return
        }
        val normalized = normalizeHistoryMessages(sessionId, messages, entryIds)
        entryIds.firstOrNull()?.let { historyOldestEntryIds[sessionId] = it }
        // 活跃链尾只在"页尾 == latestEntryId"时更新：首页（从链尾往回切）满足，
        // 翻页（更旧一页）不满足——避免旧页把活跃链尾写退。
        frame.latestEntryId?.takeIf(String::isNotBlank)?.let { latest ->
            latestLeafEntryIds[sessionId] = latest
            entryIds.lastOrNull()?.takeIf { it == latest }?.let { activeTailEntryIds[sessionId] = it }
        }
        historyStore.processingStarted(sessionId, normalized.size, frame.hasMore == true)
        val result = historyStore.pageReceived(
            sessionId = sessionId,
            eventsJson = adapterJson.encodeToString(normalized),
            byteCount = frame.bytes ?: 0,
            hasMore = frame.hasMore == true,
            nextCursor = frame.oldestEntryId,
            earliestEntryId = entryIds.firstOrNull(),
            remoteActivityTimestamp = frame.time
        )
        if (!result.accepted) {
            failHistoryLoad(sessionId, result.errorCode ?: "history-page-failed")
            return
        }
        // 后台可能错过终态。只有本次最新历史确认空闲且仍持有请求时的同一
        // 缓存前缀，才能退场；同轮delta不算新快照，替换后的前缀不能被迟到历史清掉。
        if (frame.isStreaming == false && frame.isCompacting == false && bridgeCheckpoint != null &&
            bridgeCheckpoint.first == bridgeGenerations[sessionId] &&
            bridgeCheckpoint.second === bridgeSnapshotPartials[sessionId]
        ) {
            val keys = buildSet {
                bridgeSeededStreamKeys.remove(sessionId)?.let(::addAll)
                bridgeStreamPositions.remove(sessionId)?.let { (turn, step) ->
                    add("text-$turn-$step"); add("reason-$turn-$step")
                }
            }.toList()
            conversationStore.clearStreams(sessionId, keys)
            historyStore.discardStreamChunks(sessionId, keys)
            bridgeSnapshotPartials.remove(sessionId)
            bridgeStreamSuffixes.remove(sessionId)
            bridgeOverlays.remove(sessionId)
        }
        historyFailures.remove(sessionId)
        historyHasMore[sessionId] = frame.hasMore == true
        updateFailureOutcome(sessionId, fromHistory = true)
        conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()), minimumSequence = bridgeProjectionSequences[sessionId])
        // 不能用 remove 后再 seed：历史重建可能已把 live delta 带回来，
        // seed 需要拿着旧 key 先摘掉它，才能按“快照前缀+live 后缀”重排。
        val partial = bridgeSnapshotPartials[sessionId]
        if (partial != null) seedBridgeStream(sessionId, partial)
        else bridgeSeededStreamKeys.remove(sessionId)
        historyReloadBackups.remove(sessionId)
        restoringDeletedSessions.remove(sessionId)
        flushPendingLatestReload(sessionId)
    }

    /** history 加载失败的统一收尾：早退路径不走 pageReceived，必须显式取消，
     *  否则 isLoading 永远不清、加载遮罩永久转圈。correlation 丢失时帧不带
     *  会话，回退到当前选中会话——投影只对选中会话发起 history 加载。 */
    private fun failHistoryLoad(sessionId: String?, code: String) {
        lastError = code
        val resolved = sessionId?.takeIf(String::isNotBlank)
            ?: controlSnapshot.selectedSessionId?.takeIf(String::isNotBlank)
            ?: return
        historyFailures[resolved] = code
        bridgeHistoryCheckpoints.remove(resolved)
        historyStore.cancelled(resolved)
        // 失败也会空出批次名额；不补发待取意图就会停在“无在途、无新页”的死窗口。
        flushPendingLatestReload(resolved)
    }

    /** pi 渲染消息 → 内部视图事件；seq 用每会话负空间计数器（newest = -1 递减）。 */
    private fun normalizeHistoryMessages(
        sessionId: String,
        messages: List<GatewayHistoryMessage>,
        entryIds: List<String>
    ): List<SessionEvent> {
        // 负空间 seq：同一页内 newest = -1 递减，更旧的页继续向负方向延伸，
        // 保证按 seq 升序排列后仍是时间顺序（pi messages 为 oldest→newest）。
        var counter = historySyntheticSeqs[sessionId] ?: 0
        val base = counter - messages.size
        val records = mutableListOf<SessionEvent>()
        messages.forEachIndexed { index, message ->
            // pi 消息自带 timestamp（epoch 毫秒）；缺失时记 0 表示“时间未知”，
            // 下游不得拿它参与差值计算（否则 now - 0 会渲染成“耗时几千万分钟”）。
            val time = historyEpochSeconds(message.timestamp)
            val entryId = entryIds.getOrNull(index)
            val view = when (message.role) {
                "user" -> {
                    val text = PiEventNormalizer.textOf(message.content)
                    // 历史补拉无 attachmentId（文件里没有）：占位 id 稳定派生自
                    // entryId+序号（同旧公式 hist-<entryId>-<序号>），渲染为“图片未同步”；
                    // 有 data 的本地缓存可命中。entryId 与序号随块带出，供 §4.22 取图。
                    val images = PiEventNormalizer.imageAttachmentsOf(
                        message.content,
                        fallbackPrefix = if (!entryId.isNullOrEmpty()) "hist" else "hist-msg",
                        entryId = entryId
                    )
                    if (text.isEmpty() && images.isEmpty()) null
                    else GatewayEvent(type = "user/message", text = text, source = "user", images = images)
                }
                "assistant" -> {
                    val isUpstreamError = message.stopReason == "error"
                    val errorMessage = if (isUpstreamError) {
                        message.errorMessage?.trim()
                            ?.take(MAX_UPSTREAM_ERROR_CHARACTERS)
                            ?.takeIf(String::isNotEmpty)
                    } else {
                        null
                    }
                    val toolCalls = PiEventNormalizer.toolCallBlocks(message.content)
                    GatewayEvent(
                        type = "assistant/message",
                        text = PiEventNormalizer.textOf(message.content),
                        reasoning = PiEventNormalizer.thinkingOf(message.content).ifEmpty { null },
                        toolCalls = toolCalls.ifEmpty { null },
                        provider = message.provider,
                        model = message.model,
                        isError = if (isUpstreamError) true else null,
                        errorMessage = errorMessage
                    )
                }
                "toolResult" -> GatewayEvent(
                    type = "tool/result",
                    callId = message.toolCallId,
                    name = message.toolName,
                    preview = PiEventNormalizer.textOf(message.content)
                )
                "custom" -> if (!message.isDisplayable) null else when (message.entryType) {
                    "compaction" -> {
                        val tokensBefore = message.details?.get("tokensBefore")?.doubleValue
                        val tokens = tokensBefore?.let { (it / 1000).toInt().coerceAtLeast(1) }
                        GatewayEvent(type = "compact/done", text = tokens?.let { "压缩前 ${it}k tokens" })
                    }
                    "wire_oversized" -> PiEventNormalizer.textOf(message.content)
                        .takeIf(String::isNotBlank)?.let { GatewayEvent(type = "notice/omitted", text = it) }
                    null, "custom_message" -> PiEventNormalizer.textOf(message.content)
                        .takeIf(String::isNotBlank)?.let { GatewayEvent(type = "notice/custom", text = it) }
                    else -> null
                }
                else -> null
            } ?: return@forEachIndexed
            // history.entryIds 与 messages 平行同源（PROTOCOL §4.6）：随归一事件
            // 透传，供 SharedHistoryStore 按（sessionId,entryId,type,callId）身份对消。
            records += SessionEvent(sessionId, base + index, time, view, entryId = entryId?.takeIf(String::isNotBlank))
        }
        historySyntheticSeqs[sessionId] = base
        return records
    }

    /** pi 的 `message.timestamp` 是 epoch 毫秒；归一到秒，缺省/非正归 0（时间未知）。 */
    private fun historyEpochSeconds(timestamp: Double?): Double = when {
        timestamp == null || timestamp <= 0.0 -> 0.0
        timestamp > 100_000_000_000.0 -> timestamp / 1_000
        else -> timestamp
    }

    /** 返回 false 表示同一代际的迟到/重复 bridgeSeq，整帧丢弃。 */
    private fun acceptBridgeEvent(frame: GatewayFrame): Boolean {
        val sessionId = frame.sessionId?.takeIf(String::isNotBlank) ?: return true
        val generation = bridgeGeneration(frame)
        if (generation != null) {
            val previous = bridgeGenerations[sessionId]
            if (previous != null && previous != generation) {
                // 新代际的事件不能先把旧序号/快照翻回去；等 bridge-snapshot 建立
                // 新代际后再接收。历史文件仍是同一会话，保留已加载历史。
                return false
            }
            if (previous == null) bridgeGenerations[sessionId] = generation
        }
        val seq = frame.bridgeSeq
        if (seq != null) {
            val last = bridgeLastSequences[sessionId]
            if (last != null && seq <= last) return false
            bridgeLastSequences[sessionId] = seq
        }
        return true
    }

    private fun acceptBridgeSnapshot(frame: GatewayFrame) {
        val sessionId = frame.sessionId?.takeIf(String::isNotBlank) ?: return
        val generation = bridgeGeneration(frame)
        val previousGeneration = bridgeGenerations[sessionId]
        val previousSeq = bridgeLastSequences[sessionId]
        val seq = frame.bridgeSeq
        // 同代际更低水位快照是迟到重放，不能清掉投影位置和更长的正文；等水位是
        // 同一实例重连后的合法完整快照，仍需按快照重置。Runtime 已先挡一层，这里独立防直接调用。
        if (generation != null && generation == previousGeneration &&
            seq != null && previousSeq != null && seq < previousSeq
        ) return
        // 快照是当前实时段的权威前缀；先清掉旧种子流和旧 turn/step 的活动流，
        // 否则重连后等水位快照把位置重置到 0/0，旧 turn 的残留会和新的 delta 并存。
        val oldKeys = buildSet {
            bridgeSeededStreamKeys[sessionId]?.let(::addAll)
            bridgeStreamPositions[sessionId]?.let { (turn, step) ->
                add("text-$turn-$step")
                add("reason-$turn-$step")
            }
        }
        if (oldKeys.isNotEmpty()) {
            conversationStore.clearStreams(sessionId, oldKeys.toList())
            historyStore.discardStreamChunks(sessionId, oldKeys.toList())
        }
        if (generation != null) {
            val previous = bridgeGenerations[sessionId]
            if (previous != null && previous != generation) {
                // 只替换实时段：历史来自同一会话文件，保留已加载气泡，避免
                // /reload 后整页历史消失；后续分页仍按 entryId 去重。
                bridgeLastSequences.remove(sessionId)
                bridgeOverlays.remove(sessionId)
                bridgeStreamPositions.remove(sessionId)
                bridgeSeededStreamKeys.remove(sessionId)
                bridgeStreamSuffixes.remove(sessionId)
                bridgeSnapshotPartials.remove(sessionId)
                // 旧代际的实时段（含已种进 ConversationStore 的前缀）不能跟着新实例
                // 继续显示；历史来自同一会话文件，保留已加载基线，只丢 live 段。
                conversationStore.replaceSession(
                    sessionId,
                    adapterJson.encodeToString(historyEvents[sessionId].orEmpty()),
                    minimumSequence = bridgeProjectionSequences[sessionId]
                )
            }
            bridgeGenerations[sessionId] = generation
        }
        bridgeLastSequences[sessionId] = frame.bridgeSeq ?: bridgeLastSequences[sessionId] ?: 0
        bridgeSeededStreamKeys.remove(sessionId)
        // normalizer 输入坐标每份快照归零，本地分配新范围，避免与已完成流键碰撞。
        val base = (bridgeLatestTurns[sessionId]
            ?: historyEvents[sessionId].orEmpty().mapNotNull { it.event.turn }.maxOrNull()
            ?: -1) + 1
        bridgeTurnOffsets[sessionId] = base
        bridgeLatestTurns[sessionId] = base
        bridgeStreamPositions[sessionId] = base to 0
        bridgeStreamSuffixes[sessionId] = "" to ""
        bridgeSnapshotPartials.remove(sessionId)
        frame.partial?.let { bridgeSnapshotPartials[sessionId] = it }
        val seeded = seedBridgeStream(sessionId, frame.partial)
        val items = bridgeOverlayItems(sessionId, frame.partial, frame.tools.orEmpty()).filter { item ->
            !(seeded && (item.kind == ConversationItemKind.ASSISTANT || item.kind == ConversationItemKind.REASONING))
        }
        if (items.isEmpty()) bridgeOverlays.remove(sessionId) else bridgeOverlays[sessionId] = BridgeOverlay(items)
    }

    /**
     * 快照里的正文/思考是当前流的完整前缀，而重连后的 delta 没有 message_start。
     * 先把它种进 ConversationStore 的同一 turn/step，后续 delta 才会追加而不是另起一段。
     * 返回 false 时保留原 overlay，不能因为投影拒绝就让内容消失。
     */
    private fun seedBridgeStream(sessionId: String, partial: JsonValue?): Boolean {
        val blocks = partial?.get("content")?.arrayValue ?: return false
        val suffix = bridgeStreamSuffixes[sessionId] ?: ("" to "")
        val text = blocks.mapNotNull { block ->
            block["text"]?.stringValue?.takeIf { block["type"]?.stringValue == "text" }
        }.joinToString("") + suffix.second
        val reasoning = blocks.mapNotNull { block ->
            block["thinking"]?.stringValue?.takeIf { block["type"]?.stringValue == "thinking" }
        }.joinToString("") + suffix.first
        val (turn, step) = bridgeStreamPositions[sessionId] ?: (0 to 0)
        // 同代际重放/历史重建：先摘掉上一份快照流，避免前缀重复拼接。
        bridgeSeededStreamKeys[sessionId]?.let { conversationStore.clearStreams(sessionId, it) }
        bridgeSeededStreamKeys.remove(sessionId)
        val keys = buildList {
            if (reasoning.isNotEmpty()) add("reason-$turn-$step")
            if (text.isNotEmpty()) add("text-$turn-$step")
        }
        val records = buildList {
            if (reasoning.isNotEmpty()) add(
                SessionEvent(
                    sessionId = sessionId,
                    seq = 0,
                    time = 0.0,
                    event = GatewayEvent(
                        type = "assistant/chunk",
                        turn = turn,
                        step = step,
                        chunkType = "reasoning-delta",
                        text = reasoning
                    )
                )
            )
            if (text.isNotEmpty()) add(
                SessionEvent(
                    sessionId = sessionId,
                    seq = 0,
                    time = 0.0,
                    event = GatewayEvent(
                        type = "assistant/chunk",
                        turn = turn,
                        step = step,
                        chunkType = "text-delta",
                        text = text
                    )
                )
            )
        }
        if (records.isEmpty()) return false
        val accepted = conversationStore.seedStream(sessionId, adapterJson.encodeToString(records)).accepted
        if (accepted) bridgeSeededStreamKeys[sessionId] = keys
        return accepted
    }

    private fun bridgeGeneration(frame: GatewayFrame): String? {
        val instanceId = frame.instanceId?.takeIf(String::isNotBlank) ?: return null
        val epoch = frame.epoch?.takeIf(String::isNotBlank) ?: return null
        return "$instanceId\u0000$epoch"
    }

    private fun conversationFor(sessionId: String): List<ConversationItem> {
        val base = conversationItems[sessionId].orEmpty()
        val overlay = bridgeOverlays[sessionId] ?: return base
        val existingToolIds = base.filter { it.kind == ConversationItemKind.TOOL || it.kind == ConversationItemKind.JSON_TOOL }
            .mapNotNull { it.toolCallId }.toSet()
        val existingAssistantTexts = base.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text }.toSet()
        // 历史或事件已经给出本轮正式回复时，快照里的部分正文只是同一轮的旧切片；
        // 按“最后一条 user 之后是否已有 assistant”去重，避免 message_end 与 partial 双气泡。
        val lastUserIndex = base.indexOfLast { it.kind == ConversationItemKind.USER }
        val liveTurnHasAssistant = lastUserIndex >= 0 &&
            base.drop(lastUserIndex + 1).any { it.kind == ConversationItemKind.ASSISTANT }
        return base + overlay.items.filter { item ->
            when (item.kind) {
                ConversationItemKind.ASSISTANT ->
                    !liveTurnHasAssistant && item.text !in existingAssistantTexts
                ConversationItemKind.REASONING ->
                    !liveTurnHasAssistant && item.text !in existingAssistantTexts
                else -> item.toolCallId == null || item.toolCallId !in existingToolIds
            }
        }
    }

    /** 把扩展快照的完整 partial/tools 变成可展示条目；只做读取投影，不伪造完成状态。 */
    private fun bridgeOverlayItems(
        sessionId: String,
        partial: JsonValue?,
        tools: List<JsonValue>
    ): List<ConversationItem> {
        val items = mutableListOf<ConversationItem>()
        val blocks = partial?.get("content")?.arrayValue
        val text = blocks.orEmpty().mapNotNull { block ->
            block["text"]?.stringValue?.takeIf { block["type"]?.stringValue == "text" }
        }.joinToString("")
        val reasoning = blocks.orEmpty().mapNotNull { block ->
            block["thinking"]?.stringValue?.takeIf { block["type"]?.stringValue == "thinking" }
        }.joinToString("")
        if (reasoning.isNotBlank()) {
            items += ConversationItem(
                id = "bridge-snapshot-$sessionId-reasoning",
                kind = ConversationItemKind.REASONING,
                title = "Think",
                text = reasoning,
                epochSeconds = 0.0
            )
        }
        if (text.isNotBlank()) {
            items += ConversationItem(
                id = "bridge-snapshot-$sessionId-assistant",
                kind = ConversationItemKind.ASSISTANT,
                title = "Pi",
                text = text,
                epochSeconds = 0.0
            )
        }
        tools.forEachIndexed { index, tool ->
            val callId = tool["toolCallId"]?.stringValue ?: "snapshot-$index"
            val name = tool["toolName"]?.stringValue ?: tool["name"]?.stringValue ?: "Tool"
            val args = tool["args"] ?: tool["input"]
            items += ConversationItem(
                id = "bridge-snapshot-$sessionId-tool-$callId",
                kind = ConversationItemKind.TOOL,
                title = name,
                text = args?.jsonDisplayText().orEmpty(),
                epochSeconds = 0.0,
                toolCallId = callId
            )
        }
        return items
    }

    private data class BridgeOverlay(val items: List<ConversationItem>)

    /** bridgeSeq 已验序；订阅内 seq 和 turn 只作输入坐标，不能覆写会话已有记录。 */
    private fun bridgeProjectionEvents(frame: GatewayFrame, records: List<SessionEvent>): List<SessionEvent> {
        val sessionId = frame.sessionId ?: return records
        if (bridgeGeneration(frame) == null) {
            bridgeProjectionSequences.remove(sessionId)
            return records
        }
        return records.map { record ->
            val nextSeq = maxOf(bridgeProjectionSequences[sessionId] ?: 0,
                historyEvents[sessionId].orEmpty().lastOrNull()?.seq ?: 0) + 1
            bridgeProjectionSequences[sessionId] = nextSeq
            val turn = record.event.turn?.let { (bridgeTurnOffsets[sessionId] ?: 0) + it }
            if (turn != null) bridgeLatestTurns[sessionId] = maxOf(bridgeLatestTurns[sessionId] ?: 0, turn)
            record.copy(seq = nextSeq, event = if (turn == null) record.event else record.event.copy(turn = turn))
        }
    }

    private fun acceptLive(frame: GatewayFrame, normalizedEvents: List<SessionEvent>) {
        if (normalizedEvents.isEmpty()) return
        val sessionId = frame.sessionId
        // 只有新 user 轮或运行结束才让快照段退场；工具/思考事件到达时
        // assistant partial 没有等价权威条目，过早清空会让正文闪没。
        if (!sessionId.isNullOrBlank() && normalizedEvents.any {
                it.event.type == "user/message" || it.event.type == "turn/end" ||
                    it.event.type == "assistant/message"
            }
        ) {
            bridgeOverlays.remove(sessionId)
            bridgeStreamSuffixes.remove(sessionId)
            bridgeSeededStreamKeys.remove(sessionId)?.let { keys ->
                conversationStore.clearStreams(sessionId, keys)
                historyStore.discardStreamChunks(sessionId, keys)
            }
            bridgeSnapshotPartials.remove(sessionId)
        }
        if (sessionId.isNullOrBlank()) {
            lastError = "live-event-invalid"
            return
        }
        // 会话列表与状态已由上面的 acceptFrame(raw, events) 消费；这里只做对话/历史
        // 投影，避免 normalized 事件被应用两次（第二次会绕过状态门控）。
        normalizedEvents.forEach { record ->
            record.event.takeIf { it.type == "assistant/chunk" }?.let { event ->
                event.turn?.let { turn ->
                    bridgeStreamPositions[sessionId] = turn to (event.step ?: 0)
                }
                val delta = event.text.orEmpty()
                val suffix = bridgeStreamSuffixes[sessionId] ?: ("" to "")
                when (event.chunkType) {
                    "text-delta" -> bridgeStreamSuffixes[sessionId] = suffix.first to suffix.second + delta
                    "reasoning-delta" -> bridgeStreamSuffixes[sessionId] = suffix.first + delta to suffix.second
                }
            }
            val recordJson = adapterJson.encodeToString(record)
            // 与缓冲中同身份历史合成记录（负 seq）冲突的 live 正式记录：store 侧会丢掉
            // 合成记录改发 replace，conversation 侧也必须整窗重建——item id 已由
            // entryId 派生，增量 fold 会先撞出重复 item id 被拒，再回退整窗重建（多一道错误位）。
            val supersedesSynthetic = record.seq > 0 && sessionEventIdentity(record)?.let { identity ->
                historyEvents[sessionId].orEmpty().any {
                    it.seq <= 0 && sessionEventIdentity(it) == identity
                }
            } ?: false
            val historyResult = historyStore.liveEventReceived(recordJson)
            if (!historyResult.accepted) {
                lastError = historyResult.errorCode ?: "history-live-failed"
                return
            }
            if (record.event.type in SESSION_OUTCOME_EVENTS) updateFailureOutcome(sessionId)
            if (supersedesSynthetic) {
                conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()), minimumSequence = bridgeProjectionSequences[sessionId])
                return
            }
            val conversationResult = conversationStore.receiveEvent(recordJson)
            if (!conversationResult.accepted) {
                conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()), minimumSequence = bridgeProjectionSequences[sessionId])
            }
        }
    }

    /** 只用当前分支的已合并消息；旧页、工具报错和网络错误不能覆写最新结果。 */
    private fun updateFailureOutcome(sessionId: String, fromHistory: Boolean = false) {
        val knownFailureTime = failedCompactionTimes[sessionId]
        val outcomes = historyEvents[sessionId].orEmpty().filter { it.event.type in SESSION_OUTCOME_EVENTS }
        // 旧live seq可能排在新拉到的历史之后；已知压缩失败按同一宿主时间对账。
        val record = if (fromHistory && knownFailureTime != null) {
            outcomes.maxByOrNull { historyEpochSeconds(it.time) }
        } else outcomes.lastOrNull()
        if (fromHistory && knownFailureTime != null && historyEpochSeconds(record?.time) <= knownFailureTime) return
        val outcome = record?.event
        val failed = outcome?.isError == true && (outcome.type == "assistant/message" ||
            (outcome.type == "notice/compact" && outcome.reason !in setOf("aborted", "declined")))
        if (failed) failedSessionIds.add(sessionId)
        else failedSessionIds.remove(sessionId)
        if (failed && outcome?.type == "notice/compact") {
            failedCompactionTimes[sessionId] = historyEpochSeconds(record?.time)
        } else {
            failedCompactionTimes.remove(sessionId)
        }
    }

    private fun acceptHistoryMviEvent(event: SharedMviEvent) {
        if (!historyEnvelope.validate(event)) {
            lastError = "history-envelope-invalid"
            return
        }
        val plan = runCatching { planHistoryEvent(event) }.getOrElse {
            historyEnvelope.reject()
            lastError = "history-adapter-failed"
            return
        }
        historyEvents.clear()
        historyEvents.putAll(plan.eventsBySession)
        historyLastSequences.clear()
        historyLastSequences.putAll(plan.lastSequencesBySession)
        historySessionStates.clear()
        historySessionStates.putAll(plan.sessionStatesBySession)
        historyPendingSessionId = plan.pendingSessionId
        historyEnvelope.commit(event)
        if (event.kind == "error") lastError = event.errorCode ?: "history-store-error"
        plan.effects.forEach { effect ->
            bridgeHistoryCheckpoints.remove(effect.sessionId)
            val generation = bridgeGenerations[effect.sessionId]
            val partial = bridgeSnapshotPartials[effect.sessionId]
            if (effect.beforeEntryId == null && generation != null && partial?.objectValue != null) {
                bridgeHistoryCheckpoints[effect.sessionId] = generation to partial
            }
            runCatching { onHistoryPageRequested(effect.sessionId, effect.beforeEntryId, effect.resetLeaf) }
                .onFailure {
                    bridgeHistoryCheckpoints.remove(effect.sessionId)
                    lastError = "history-effect-failed"
                }
        }
    }

    private fun planHistoryEvent(event: SharedMviEvent): HistoryPlan {
        if (event.kind == "error") {
            require(event.statePayloadJson == null && decodeHistoryEffects(event).isEmpty())
            require(!event.errorCode.isNullOrBlank())
            return HistoryPlan(
                historyEvents.toMap(),
                historyLastSequences.toMap(),
                historySessionStates.toMap(),
                historyPendingSessionId,
                emptyList()
            )
        }
        if (event.kind == "snapshot") {
            val bootstrap = adapterJson.decodeFromString<SharedHistoryBootstrap>(
                requireNotNull(event.statePayloadJson)
            )
            require(bootstrap.schema == 2 && decodeHistoryEffects(event).isEmpty())
            bootstrap.eventsBySession.forEach { (sessionId, records) ->
                require(sessionId.isNotBlank() && records.all { it.sessionId == sessionId })
                require(records.zipWithNext().all { (left, right) -> left.seq < right.seq })
            }
            return HistoryPlan(
                bootstrap.eventsBySession,
                bootstrap.eventsBySession.mapValues { it.value.lastOrNull()?.seq ?: -1 },
                bootstrap.state.sessions,
                bootstrap.state.pendingSessionId,
                emptyList()
            )
        }
        require(event.kind == "transition")
        val patch = adapterJson.decodeFromString<SharedHistoryPatch>(
            requireNotNull(event.statePayloadJson)
        )
        require(patch.schema == 2 && patch.sessionId.isNotBlank())
        require(patch.outcome in HISTORY_OUTCOMES)
        val effects = decodeHistoryEffects(event)
        require(effects.all { it.action == "request-page" && it.sessionId == patch.sessionId })
        require((patch.outcome == "request-page") == (effects.size == 1))
        val next = historyEvents.toMutableMap()
        val nextSequences = historyLastSequences.toMutableMap()
        val nextSessionStates = historySessionStates.toMutableMap()
        patch.session?.let { nextSessionStates[patch.sessionId] = it }
        val nextPendingSessionId = if (patch.pendingSessionChanged) {
            patch.pendingSessionId
        } else {
            historyPendingSessionId
        }
        patch.eventPatch?.let { eventPatch ->
            val old = next[patch.sessionId].orEmpty()
            next[patch.sessionId] = when (eventPatch.kind) {
                "replace" -> {
                    val replacement = requireNotNull(eventPatch.replacementEvents)
                    require(eventPatch.record == null)
                    require(replacement.all { it.sessionId == patch.sessionId })
                    require(replacement.zipWithNext().all { (left, right) -> left.seq < right.seq })
                    // 全隐藏页尚无事件尾；首个可见旧页的负 seq 可以小于 -1。
                    // preserveLive 重拉会整窗替换最新窗口（store 侧丢弃旧负 seq 记录），
                    // 尾号允许下移；只要缓冲里有实时（正 seq）记录，合并尾号仍是实时尾号。
                    val previousTail = old.lastOrNull()?.seq
                    val replacementTail = replacement.lastOrNull()?.seq ?: -1
                    require(
                        replacement.isEmpty() || previousTail == null ||
                            replacementTail >= previousTail ||
                            (previousTail < 0 && replacement.all { it.seq < 0 })
                    )
                    if (replacement.isEmpty()) nextSequences.remove(patch.sessionId)
                    else nextSequences[patch.sessionId] = replacementTail
                    replacement
                }
                "discard-streams" -> {
                    val replacement = requireNotNull(eventPatch.replacementEvents)
                    require(eventPatch.record == null && eventPatch.index == null)
                    val oldBySeq = old.associateBy(SessionEvent::seq)
                    require(replacement.zipWithNext().all { (left, right) -> left.seq < right.seq })
                    require(replacement.all { oldBySeq[it.seq] == it })
                    val retained = replacement.mapTo(mutableSetOf()) { it.seq }
                    require(old.filter { it.seq !in retained }.all { record ->
                        record.event.type == "assistant/chunk" &&
                            record.event.chunkType in setOf("text-delta", "reasoning-delta")
                    })
                    // 只删流式载荷，不回退已消费水位；下一帧仍须晚于旧水位。
                    replacement
                }
                "append", "upsert" -> {
                    val record = requireNotNull(eventPatch.record)
                    require(eventPatch.replacementEvents == null && record.sessionId == patch.sessionId)
                    val previousTail = nextSequences[patch.sessionId] ?: old.lastOrNull()?.seq ?: -1
                    if (eventPatch.kind == "append") {
                        require(record.seq > previousTail && eventPatch.index == old.size)
                    } else {
                        require(record.seq <= previousTail && eventPatch.index != null)
                    }
                    val merged = old.associateBy(SessionEvent::seq).toMutableMap().apply {
                        put(record.seq, record)
                    }.values.sortedBy(SessionEvent::seq)
                    eventPatch.index?.let { require(it == merged.indexOfFirst { item -> item.seq == record.seq }) }
                    nextSequences[patch.sessionId] = maxOf(previousTail, record.seq)
                    merged
                }
                else -> error("unknown history patch kind")
            }
        }
        return HistoryPlan(
            next,
            nextSequences,
            nextSessionStates,
            nextPendingSessionId,
            effects
        )
    }

    private fun decodeHistoryEffects(event: SharedMviEvent): List<SharedHistoryEffect> =
        adapterJson.decodeFromString(event.effectsJson)

    private fun acceptConversationMviEvent(event: SharedMviEvent) {
        if (!conversationEnvelope.validate(event)) {
            lastError = "conversation-envelope-invalid"
            return
        }
        val plan = runCatching { planConversationEvent(event) }.getOrElse {
            conversationEnvelope.reject()
            lastError = "conversation-adapter-failed"
            return
        }
        conversationItems.clear()
        conversationItems.putAll(plan.itemsBySession)
        conversationLastSequences.clear()
        conversationLastSequences.putAll(plan.lastSequencesBySession)
        conversationEnvelope.commit(event)
        if (event.kind == "error") lastError = event.errorCode ?: "conversation-store-error"
    }

    private fun planConversationEvent(event: SharedMviEvent): ConversationPlan {
        require(adapterJson.decodeFromString<List<SharedHistoryEffect>>(event.effectsJson).isEmpty())
        if (event.kind == "error") {
            require(event.statePayloadJson == null && !event.errorCode.isNullOrBlank())
            return ConversationPlan(conversationItems.toMap(), conversationLastSequences.toMap())
        }
        if (event.kind == "snapshot") {
            val bootstrap = adapterJson.decodeFromString<SharedConversationBootstrap>(
                requireNotNull(event.statePayloadJson)
            )
            require(bootstrap.schema == 1)
            return ConversationPlan(conversationItems.toMap(), conversationLastSequences.toMap())
        }
        require(event.kind == "transition")
        val patch = adapterJson.decodeFromString<SharedConversationPatch>(
            requireNotNull(event.statePayloadJson)
        )
        require(patch.schema == 1 && patch.sessionId.isNotBlank() && patch.lastSequence >= -1)
        val next = conversationItems.toMutableMap()
        val nextSequences = conversationLastSequences.toMutableMap()
        val previousSequence = nextSequences[patch.sessionId] ?: -1
        if (patch.seedsStream) {
            require(!patch.replacesAll && patch.replacementItems == null)
            require(patch.operations.isNotEmpty() && patch.lastSequence <= previousSequence)
            val items = next[patch.sessionId].orEmpty().toMutableList()
            patch.operations.forEach { operation ->
                when (operation.kind) {
                    "insert" -> {
                        val item = requireNotNull(operation.item)
                        require(operation.itemId == null && operation.delta == null)
                        require(items.none { it.id == item.id })
                        items += item
                    }
                    "append-text" -> {
                        require(operation.item == null && !operation.itemId.isNullOrBlank() && operation.delta != null)
                        val index = items.indexOfFirst { it.id == operation.itemId }
                        require(index >= 0)
                        items[index] = items[index].copy(
                            text = items[index].text + operation.delta,
                            epochSeconds = operation.epochSeconds ?: items[index].epochSeconds
                        )
                    }
                    "remove" -> {
                        require(operation.item == null && !operation.itemId.isNullOrBlank())
                        require(operation.delta == null)
                        items.removeAll { it.id == operation.itemId }
                    }
                    else -> error("seeded stream only appends or removes")
                }
            }
            next[patch.sessionId] = items
            return ConversationPlan(next, nextSequences)
        }
        if (patch.replacesAll) {
            val replacement = requireNotNull(patch.replacementItems)
            require(patch.operations.isEmpty())
            require(replacement.map(ConversationItem::id).distinct().size == replacement.size)
            val isExplicitClear = replacement.isEmpty() && patch.lastSequence == -1
            require(isExplicitClear || patch.lastSequence >= previousSequence)
            next[patch.sessionId] = replacement
            if (isExplicitClear) nextSequences.remove(patch.sessionId)
            else nextSequences[patch.sessionId] = patch.lastSequence
            return ConversationPlan(next, nextSequences)
        }
        require(patch.replacementItems == null)
        require(patch.operations.isNotEmpty() && patch.lastSequence > previousSequence)
        val items = next[patch.sessionId].orEmpty().toMutableList()
        patch.operations.forEach { operation ->
            when (operation.kind) {
                "insert" -> {
                    val item = requireNotNull(operation.item)
                    require(operation.itemId == null && operation.delta == null)
                    require(items.none { it.id == item.id })
                    items += item
                }
                "append-text" -> {
                    require(operation.item == null && !operation.itemId.isNullOrBlank() && operation.delta != null)
                    val index = items.indexOfFirst { it.id == operation.itemId }
                    require(index >= 0)
                    items[index] = items[index].copy(
                        text = items[index].text + operation.delta,
                        epochSeconds = operation.epochSeconds ?: items[index].epochSeconds
                    )
                }
                "remove" -> {
                    require(operation.item == null && !operation.itemId.isNullOrBlank() && operation.delta == null)
                    require(items.removeAll { it.id == operation.itemId })
                }
                else -> error("unknown conversation operation")
            }
        }
        next[patch.sessionId] = items
        nextSequences[patch.sessionId] = patch.lastSequence
        return ConversationPlan(next, nextSequences)
    }

    private data class HistoryPlan(
        val eventsBySession: Map<String, List<SessionEvent>>,
        val lastSequencesBySession: Map<String, Int>,
        val sessionStatesBySession: Map<String, HistorySessionState>,
        val pendingSessionId: String?,
        val effects: List<SharedHistoryEffect>
    )

    private data class ConversationPlan(
        val itemsBySession: Map<String, List<ConversationItem>>,
        val lastSequencesBySession: Map<String, Int>
    )

    companion object {
        private val HISTORY_OUTCOMES = setOf(
            "none", "request-page", "stopped", "completed", "failed"
        )
    }
}

internal class MviEnvelopeValidator(private val expectedDomain: String) {
    private var initialized = false
    private var failed = false
    private var lastSequence = 0L
    private val recentTransactions = ArrayDeque<String>()
    private val transactionSet = mutableSetOf<String>()
    internal val retainedTransactionCountForTest: Int get() = transactionSet.size

    fun validate(event: SharedMviEvent): Boolean {
        if (failed) return false
        val validBase = event.schema == 2 && event.domain == expectedDomain &&
            event.transactionId.isNotBlank() && event.transactionId !in transactionSet &&
            event.metadataJson == null
        val validSequence = when (event.kind) {
            "snapshot" -> !initialized
            "transition", "error" -> initialized && event.sequence == lastSequence + 1
            else -> false
        }
        if (!validBase || !validSequence) {
            failed = true
            return false
        }
        return true
    }

    fun commit(event: SharedMviEvent) {
        check(!failed)
        initialized = true
        lastSequence = event.sequence
        recentTransactions.addLast(event.transactionId)
        transactionSet += event.transactionId
        if (recentTransactions.size > MAXIMUM_RECENT_TRANSACTIONS) {
            transactionSet -= recentTransactions.removeFirst()
        }
    }

    fun reject() {
        failed = true
    }

    companion object {
        private const val MAXIMUM_RECENT_TRANSACTIONS = 64
    }
}
