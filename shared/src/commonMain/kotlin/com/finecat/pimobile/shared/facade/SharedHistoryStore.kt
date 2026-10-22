package com.finecat.pimobile.shared.facade

import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import com.finecat.pimobile.shared.sync.HistoryAction
import com.finecat.pimobile.shared.sync.HistoryBatchKind
import com.finecat.pimobile.shared.sync.HistoryEventMerger
import com.finecat.pimobile.shared.sync.HistoryFailureCode
import com.finecat.pimobile.shared.sync.HistoryReducer
import com.finecat.pimobile.shared.sync.HistoryResult
import com.finecat.pimobile.shared.sync.HistorySessionState
import com.finecat.pimobile.shared.sync.HistoryState
import com.finecat.pimobile.shared.sync.HistorySyncConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedHistoryBootstrap(
    val schema: Int = 2,
    val state: HistoryState = HistoryState(),
    val eventsBySession: Map<String, List<SessionEvent>> = emptyMap()
)

@Serializable
data class SharedHistoryEventPatch(
    val kind: String,
    val record: SessionEvent? = null,
    val index: Int? = null,
    val replacementEvents: List<SessionEvent>? = null
)

@Serializable
data class SharedHistoryPatch(
    val schema: Int = 2,
    val sessionId: String,
    val session: HistorySessionState? = null,
    val pendingSessionId: String? = null,
    val pendingSessionChanged: Boolean = false,
    val eventPatch: SharedHistoryEventPatch? = null,
    val outcome: String = "none",
    val failureCode: String? = null,
    val completedEventCount: Int? = null,
    val completedByteCount: Int? = null,
    val completedHasMore: Boolean? = null
)

@Serializable
data class SharedHistoryEffect(
    val action: String,
    val sessionId: String,
    val beforeEntryId: String? = null,
    val resetLeaf: Boolean = false
)

/**
 * History 的唯一业务状态源。
 *
 * 平台只负责网络请求、超时定时器和 UI 发布；分页 cursor、加载状态、水位以及
 * history/live tail 的有序去重全部在这里完成。所有状态与 effect 先完成序列化，
 * 再原子提交并发布同一 MVI 事务。
 */
class SharedHistoryStore(
    private val configuration: HistorySyncConfiguration = HistorySyncConfiguration()
) {
    private var state = HistoryState()
    private var eventsBySession: Map<String, List<SessionEvent>> = emptyMap()
    private val events = SharedMviEventEmitter("history")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(
            observer,
            wireJson.encodeToString(SharedHistoryBootstrap(state = state, eventsBySession = eventsBySession))
        )
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "history-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun start(
        sessionId: String,
        older: Boolean,
        hasLocalEvents: Boolean,
        earliestEntryId: String?,
        resetLeaf: Boolean = false
    ): SharedMviDispatchResult = reduce(
        "start",
        sessionId,
        HistoryAction.Start(sessionId, older, hasLocalEvents, earliestEntryId, resetLeaf)
    )

    fun processingStarted(
        sessionId: String,
        rawEventCount: Int,
        hasMore: Boolean
    ): SharedMviDispatchResult = reduce(
        "processing",
        sessionId,
        HistoryAction.ProcessingStarted(sessionId, rawEventCount, hasMore)
    )

    fun pageReceived(
        sessionId: String,
        eventsJson: String,
        byteCount: Int,
        hasMore: Boolean,
        nextCursor: String?,
        earliestEntryId: String?,
        remoteActivityTimestamp: Double?
    ): SharedMviDispatchResult = dispatch("page", sessionId) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val page = wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
        require(page.all { it.sessionId == sessionId }) { "history page contains another session" }
        require(remoteActivityTimestamp == null || remoteActivityTimestamp.isFinite()) {
            "remoteActivityTimestamp must be finite"
        }
        val oldEvents = eventsBySession[sessionId].orEmpty()
        val session = state.sessions[sessionId]
        val firstLatestPage = session != null &&
            session.isLoading && session.batchKind == HistoryBatchKind.LATEST && session.pageCount == 0
        val replacesHistoryWindow = firstLatestPage && page.isNotEmpty()
        val firstIdentity = page.firstOrNull()?.entryIdentity()
        val overlapIndex = if (replacesHistoryWindow && firstIdentity != null)
            oldEvents.indexOfFirst { it.entryIdentity() == firstIdentity } else -1
        // Only a proven overlap can retain the older prefix. Branch switches/rewrites or
        // gaps still replace the window; no speculative stitching across missing history.
        val olderPrefix = if (overlapIndex > 0) oldEvents.take(overlapIndex).filter { it.seq < 0 } else emptyList()
        val bufferedForMerge = if (replacesHistoryWindow) oldEvents.filter { it.seq > 0 } else oldEvents
        val refreshed = mergeHistoryPage(page, bufferedForMerge)
        val merged = if (olderPrefix.isNotEmpty()) {
            val historical = olderPrefix + refreshed.filter { it.seq < 0 }
            historical.mapIndexed { index, event -> event.copy(seq = index - historical.size) } +
                refreshed.filter { it.seq > 0 }
        } else refreshed
        val keepOlderCursor = olderPrefix.isNotEmpty()
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.PageCommitted(
                sessionId = sessionId,
                eventCount = page.size,
                byteCount = byteCount,
                hasMore = if (keepOlderCursor) session!!.hasMore else hasMore,
                nextCursor = if (keepOlderCursor) session!!.nextCursor else nextCursor,
                earliestEntryId = if (keepOlderCursor) olderPrefix.first().entryId else earliestEntryId,
                remoteActivityTimestamp = remoteActivityTimestamp
            ),
            configuration
        )
        Transition(
            state = reduction.state,
            eventsBySession = if (merged == oldEvents) eventsBySession else eventsBySession + (sessionId to merged),
            eventPatch = if (merged == oldEvents) null else SharedHistoryEventPatch(
                kind = "replace",
                replacementEvents = merged
            ),
            result = reduction.result
        )
    }

    /** bridge 快照替换活动流时，同步移除可被后续 history 重建重放的旧 chunk。 */
    fun discardStreamChunks(sessionId: String, keys: List<String>): SharedMviDispatchResult = dispatch("discard-streams", sessionId) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val old = eventsBySession[sessionId].orEmpty()
        val remaining = old.filterNot { record ->
            val event = record.event
            val prefix = when (event.chunkType) {
                "text-delta" -> "text"
                "reasoning-delta" -> "reason"
                else -> null
            }
            event.type == "assistant/chunk" && prefix != null &&
                "$prefix-${event.turn ?: -1}-${event.step ?: -1}" in keys
        }
        Transition(
            state = state,
            eventsBySession = if (remaining == old) eventsBySession else eventsBySession + (sessionId to remaining),
            eventPatch = if (remaining == old) null else SharedHistoryEventPatch(kind = "discard-streams", replacementEvents = remaining),
            result = HistoryResult.None
        )
    }

    fun liveEventReceived(eventJson: String): SharedMviDispatchResult = dispatch("live", null) {
        val record = wireJson.decodeFromString<SessionEvent>(eventJson)
        require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(record.time.isFinite()) { "event time must be finite" }
        val oldEvents = eventsBySession[record.sessionId].orEmpty()
        // 有 entryId 的 live 正式消息与缓冲中同身份的历史合成记录（负 seq）是
        // 同一条目（PROTOCOL §4.5）：对消合成记录再入列，实时正式消息胜出，
        // 否则消息显示两次。此时必须改发 replace patch（append/upsert 无法表达
        // 删除）。身份未知（entryId 缺失/空白）时保守保留：不与任何缓冲记录
        // 对消，旧网关 history/live 重叠允许暂时重复（兼容边界）。
        var droppedSyntheticDuplicate = false
        val mergedSource = if (record.seq > 0) {
            val identity = record.entryIdentity()
            val dropIndex = if (identity == null) {
                -1
            } else {
                oldEvents.indexOfLast { candidate ->
                    candidate.seq <= 0 && candidate.entryIdentity() == identity
                }
            }
            if (dropIndex >= 0) {
                droppedSyntheticDuplicate = true
                oldEvents.filterIndexed { index, _ -> index != dropIndex }
            } else {
                oldEvents
            }
        } else {
            oldEvents
        }
        val merged = HistoryEventMerger.merge(record, mergedSource)
        val index = merged.events.binarySearchBy(record.seq) { it.seq }
        check(index >= 0) { "merged event missing" }
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.LiveEventReceived(record.sessionId, record.time),
            configuration
        )
        Transition(
            state = reduction.state,
            eventsBySession = eventsBySession + (record.sessionId to merged.events),
            eventPatch = if (droppedSyntheticDuplicate) {
                SharedHistoryEventPatch(kind = "replace", replacementEvents = merged.events)
            } else {
                SharedHistoryEventPatch(
                    kind = if (merged.replacedOrInsertedOutOfOrder) "upsert" else "append",
                    record = record,
                    index = index
                )
            },
            result = reduction.result,
            sessionId = record.sessionId
        )
    }

    fun timedOut(sessionId: String): SharedMviDispatchResult =
        reduce("timeout", sessionId, HistoryAction.TimedOut(sessionId))

    fun cancelled(sessionId: String): SharedMviDispatchResult =
        reduce("cancel", sessionId, HistoryAction.Cancelled(sessionId))

    fun clearSession(sessionId: String): SharedMviDispatchResult = dispatch("clear", sessionId) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val nextSessions = state.sessions - sessionId
        val nextState = state.copy(
            sessions = nextSessions,
            pendingSessionId = state.pendingSessionId?.takeUnless { it == sessionId }
        )
        val hadEvents = sessionId in eventsBySession
        Transition(
            state = nextState,
            eventsBySession = eventsBySession - sessionId,
            eventPatch = if (hadEvents) SharedHistoryEventPatch(
                kind = "replace",
                replacementEvents = emptyList()
            ) else null,
            result = HistoryResult.None
        )
    }

    private fun reduce(
        operation: String,
        sessionId: String,
        action: HistoryAction
    ): SharedMviDispatchResult = dispatch(operation, sessionId) {
        val reduction = HistoryReducer.reduce(state, action, configuration)
        Transition(state = reduction.state, eventsBySession = eventsBySession, result = reduction.result)
    }

    private inline fun dispatch(
        operation: String,
        fallbackSessionId: String?,
        block: () -> Transition
    ): SharedMviDispatchResult = try {
        val transition = block()
        val sessionId = transition.sessionId ?: fallbackSessionId
        require(!sessionId.isNullOrBlank()) { "sessionId must not be blank" }
        if (transition.state == state && transition.eventsBySession == eventsBySession && transition.result == HistoryResult.None) {
            return SharedMviDispatchResult(true, null, null)
        }
        val patch = SharedHistoryPatch(
            sessionId = sessionId,
            session = transition.state.sessions[sessionId],
            pendingSessionId = transition.state.pendingSessionId,
            pendingSessionChanged = transition.state.pendingSessionId != state.pendingSessionId,
            eventPatch = transition.eventPatch,
            outcome = transition.result.outcome(),
            failureCode = (transition.result as? HistoryResult.Failed)?.code?.name,
            completedEventCount = (transition.result as? HistoryResult.Completed)?.eventCount,
            completedByteCount = (transition.result as? HistoryResult.Completed)?.byteCount,
            completedHasMore = (transition.result as? HistoryResult.Completed)?.hasMore
        )
        val effect = transition.result.effect(sessionId)
        val statePayload = wireJson.encodeToString(patch)
        val effectsPayload = wireJson.encodeToString(effect?.let(::listOf).orEmpty())
        state = transition.state
        eventsBySession = transition.eventsBySession
        val event = events.emitTransition(
            transactionId = "history-$operation:${events.currentSequence + 1}",
            statePayloadJson = statePayload,
            effectsJson = effectsPayload
        )
        SharedMviDispatchResult(true, event.transactionId, event.sequence)
    } catch (error: Throwable) {
        val code = "history-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        val event = events.emitError("$code:${events.currentSequence + 1}", code, message)
        SharedMviDispatchResult(false, event.transactionId, event.sequence, code, message)
    }

    private data class Transition(
        val state: HistoryState,
        val eventsBySession: Map<String, List<SessionEvent>>,
        val eventPatch: SharedHistoryEventPatch? = null,
        val result: HistoryResult,
        val sessionId: String? = null
    )

    private fun mergeHistoryPage(
        page: List<SessionEvent>,
        buffered: List<SessionEvent>
    ): List<SessionEvent> {
        val records = linkedMapOf<Int, SessionEvent>()
        // history 页（负空间合成 seq）与缓冲（live 正 seq + 既有历史负 seq）按
        // 条目身份对消（PROTOCOL §4.5）：同 entryId 是同一条目的确定性证据，
        // 双侧都有身份才对消，实时正式消息胜出、重复历史页/重拉不产生双份。
        // 身份未知（entryId 缺失/空白）的记录保守保留：不比较文本/载荷，不吞
        // 同文独立历史；旧网关 history/live 重叠允许暂时重复（兼容边界）。
        val bufferedIdentities = buffered.mapNotNull { it.entryIdentity() }.toSet()
        val pageIdentities = mutableSetOf<EventIdentity>()
        page.forEach { record ->
            val identity = record.entryIdentity()
            if (identity != null) {
                if (identity in bufferedIdentities || !pageIdentities.add(identity)) return@forEach
            }
            records[record.seq] = record
        }
        // 实时 lane 胜出，避免晚到 history 覆盖已收到的最终/增量事件。
        buffered.forEach { records[it.seq] = it }
        return records.values.sortedBy(SessionEvent::seq)
    }

    /** 条目身份（PROTOCOL §4.5）：sessionId + 非空 entryId + 归一 type + callId。
     *  同一条目可能先后发多个事件，同键归并；entryId 缺失 → null，不参与身份对消。 */
    private data class EventIdentity(
        val sessionId: String,
        val entryId: String,
        val type: String,
        val callId: String?
    )

    private fun SessionEvent.entryIdentity(): EventIdentity? {
        val id = entryId?.takeIf(String::isNotBlank) ?: return null
        return EventIdentity(sessionId, id, event.type, event.callId)
    }

    private fun HistoryResult.effect(sessionId: String): SharedHistoryEffect? = when (this) {
        is HistoryResult.RequestPage -> SharedHistoryEffect("request-page", sessionId, beforeEntryId, resetLeaf)
        else -> null
    }

    private fun HistoryResult.outcome(): String = when (this) {
        HistoryResult.None -> "none"
        is HistoryResult.RequestPage -> "request-page"
        HistoryResult.Stopped -> "stopped"
        is HistoryResult.Completed -> "completed"
        is HistoryResult.Failed -> "failed"
    }
}
