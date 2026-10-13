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
private val SESSION_OUTCOME_EVENTS = setOf("assistant/message", "user/message", "turn/start")

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
    /** history 帧 latestEntryId（最新叶子：通常为文件视图、与 getLeafId 同源；
     *  网关并入 live 未落盘尾部时为 live 叶子/活跃链尾，非文件水位）与活跃
     *  链尾条目 id，供“活跃链落后于最新”的跳最新出口比对（1.5.9）。 */
    private val latestLeafEntryIds = mutableMapOf<String, String>()
    private val activeTailEntryIds = mutableMapOf<String, String>()
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
    private val conversationLastSequences = mutableMapOf<String, Int>()
    private var controlSnapshot = mobileStore.snapshot()
    private var lastFrameKind: String? = null
    private var lastError: String? = null
    private val historyEnvelope = MviEnvelopeValidator("history")
    private val conversationEnvelope = MviEnvelopeValidator("conversation")
    private val historySubscription = historyStore.subscribe(::acceptHistoryMviEvent)
    private val conversationSubscription = conversationStore.subscribe(::acceptConversationMviEvent)

    fun snapshot(): SharedMobileSnapshot {
        return controlSnapshot.copy(
            sessions = controlSnapshot.sessions.map { session ->
                session.copy(hasFailure = !session.isRunning && session.id in failedSessionIds)
            },
            conversation = controlSnapshot.selectedSessionId?.let(conversationItems::get).orEmpty(),
            selectedHistoryHasMore = controlSnapshot.selectedSessionId?.let(historyHasMore::get) == true,
            selectedHistoryEarliestSequence = controlSnapshot.selectedSessionId
                ?.let(historyEvents::get)?.firstOrNull()?.seq,
            selectedHistoryNextCursor = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.nextCursor,
            selectedHistoryIsLoading = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoading == true,
            selectedHistoryIsLoadingOlder = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoadingOlder == true,
            selectedHistoryLoadedEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.loaded ?: 0,
            selectedHistoryTotalEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.total,
            selectedHistoryError = controlSnapshot.selectedSessionId?.let(historyFailures::get),
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
        if (!older) {
            if (historySessionStates[sessionId]?.isLoading == true) {
                pendingLatestReloads[sessionId] = PendingLatestReload(resetLeaf, preserveLive, preserveFailure)
                return
            }
            pendingLatestReloads.remove(sessionId)
        }
        val local = historyEvents[sessionId].orEmpty()
        if (!older && !preserveLive) {
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
        if (!preserveFailure) failedSessionIds.remove(sessionId)
        historyStore.clearSession(sessionId)
        conversationStore.clearSession(sessionId)
        historyEvents.remove(sessionId)
        historySyntheticSeqs.remove(sessionId)
        historyOldestEntryIds.remove(sessionId)
        historyHasMore.remove(sessionId)
        historyFailures.remove(sessionId)
        historySessionStates.remove(sessionId)
        latestLeafEntryIds.remove(sessionId)
        activeTailEntryIds.remove(sessionId)
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
        lastFrameKind = frame.kind
        lastError = null
        when (frame.kind) {
            "history" -> {
                acceptHistory(frame, correlatedSessionId)
                // history 帧的会话级 state（isStreaming/isCompacting/model/queued）
                // 同样要进 mobileStore：重连 catch-up 时它就是唯一的状态纠正来源。
                controlSnapshot = mobileStore.acceptFrame(rawJson)
            }
            "event" -> acceptLive(frame, normalizedEvents)
            "paired", "hello" -> Unit
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
        return snapshot()
    }

    fun reset(): SharedMobileSnapshot {
        historyEvents.keys.toList().forEach {
            historyStore.clearSession(it)
            conversationStore.clearSession(it)
        }
        historyEvents.clear()
        failedSessionIds.clear()
        historyLastSequences.clear()
        historyHasMore.clear()
        historySessionStates.clear()
        latestLeafEntryIds.clear()
        activeTailEntryIds.clear()
        historySyntheticSeqs.clear()
        historyOldestEntryIds.clear()
        historyStartedSessions.clear()
        historyFailures.clear()
        pendingLatestReloads.clear()
        historyPendingSessionId = null
        conversationItems.clear()
        conversationLastSequences.clear()
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

    fun historyTimedOut(sessionId: String) {
        historyStore.timedOut(sessionId)
        flushPendingLatestReload(sessionId)
    }

    fun historyCancelled(sessionId: String) {
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
        return latest != tail
    }

    internal fun latestEntryId(sessionId: String): String? = latestLeafEntryIds[sessionId]

    private fun acceptHistory(frame: GatewayFrame, correlatedSessionId: String?) {
        val sessionId = correlatedSessionId?.takeIf(String::isNotBlank)
        if (sessionId == null) {
            failHistoryLoad(null, "history-correlation-missing")
            return
        }
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
        historyFailures.remove(sessionId)
        historyHasMore[sessionId] = frame.hasMore == true
        updateFailureOutcome(sessionId)
        conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()))
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
                "custom" -> when (message.customType) {
                    // 历史重建还原实时路径的"上游错误可见"提示（与 PiEventNormalizer
                    // 的 entry_appended 归一一致）：只显示首次，避免重试刷屏。
                    // 该 customType 由 empty-stop-guard 一类扩展写入（非 pi 原生）。
                    //
                    // 扩展会写两种形态：带 data 的 custom entry（真实提示）与
                    // display=false 的 custom_message（注入给模型的隐藏续跑指令）。
                    // 后者必须跳过——pi 契约里 display=false 表示完全隐藏（TUI 同样
                    // 不渲染），否则翻历史会多出一条假的"上游没有返回可见正文"错误。
                    "empty-stop-retry" -> {
                        if (!message.isDisplayable) {
                            null
                        } else {
                            val attempt = message.details?.get("attempt")?.doubleValue ?: 1.0
                            if (attempt > 1.0) {
                                null
                            } else {
                                val reason = message.details?.get("reason")?.stringValue?.trim().orEmpty()
                                GatewayEvent(
                                    type = "notice/upstream",
                                    text = reason.take(240).ifEmpty { "上游没有返回可见正文" },
                                    isError = true
                                )
                            }
                        }
                    }
                    // pi 历史重建里的压缩痕迹：对齐 TUI 的 `[compaction: Nk tokens]` 行，
                    // 也与实时侧 entry_appended(compaction) 的归一一致。
                    "compaction" -> {
                        val tokensBefore = message.details?.get("tokensBefore")?.doubleValue
                        val tokens = tokensBefore?.let { (it / 1000).toInt().coerceAtLeast(1) }
                        GatewayEvent(type = "compact/done", text = tokens?.let { "压缩前 ${it}k tokens" })
                    }
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

    private fun acceptLive(frame: GatewayFrame, normalizedEvents: List<SessionEvent>) {
        if (normalizedEvents.isEmpty()) return
        val sessionId = frame.sessionId
        if (sessionId.isNullOrBlank()) {
            lastError = "live-event-invalid"
            return
        }
        // 元数据事件（user/message、assistant/message、tool/*、turn/*）更新会话列表；
        // chunk 已由 ConversationStore 增量处理，禁止进入全量投影路径。
        controlSnapshot = mobileStore.acceptLiveEvents(normalizedEvents)
        normalizedEvents.forEach { record ->
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
                conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()))
                return
            }
            val conversationResult = conversationStore.receiveEvent(recordJson)
            if (!conversationResult.accepted) {
                conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()))
            }
        }
    }

    /** 只用当前分支的已合并消息；旧页、工具报错和网络错误不能覆写最新结果。 */
    private fun updateFailureOutcome(sessionId: String) {
        val outcome = historyEvents[sessionId]?.lastOrNull { it.event.type in SESSION_OUTCOME_EVENTS }?.event
        if (outcome?.type == "assistant/message" && outcome.isError == true) failedSessionIds.add(sessionId)
        else failedSessionIds.remove(sessionId)
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
            runCatching { onHistoryPageRequested(effect.sessionId, effect.beforeEntryId, effect.resetLeaf) }
                .onFailure { lastError = "history-effect-failed" }
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
