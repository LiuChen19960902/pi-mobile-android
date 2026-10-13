package com.finecat.pimobile.shared.facade

import com.finecat.pimobile.shared.domain.SessionListAction
import com.finecat.pimobile.shared.domain.SessionListLabels
import com.finecat.pimobile.shared.domain.SessionListReducer
import com.finecat.pimobile.shared.domain.SessionListState
import com.finecat.pimobile.shared.domain.SessionSummary
import com.finecat.pimobile.shared.protocol.GatewaySessionSummary
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 平台层只消费的稳定会话值，不暴露 Reducer 内部可变实现。 */
@Serializable
data class SharedSessionSummarySnapshot(
    val id: String,
    val title: String,
    val lastActivityEpochSeconds: Double,
    val isRunning: Boolean,
    val hasUnread: Boolean
)

/** UI 发布和持久化适配器所需的完整会话列表快照。 */
@Serializable
data class SharedSessionListSnapshot(
    val sessions: List<SharedSessionSummarySnapshot> = emptyList(),
    val selectedSessionId: String? = null
)

/**
 * 所有 Kotlin 异常都在桥接边界内转换，禁止越过 facade 抛到调用方的 UI 栈。
 * 成功但状态未变化时 snapshotJson 为 null，避免流式事件重复复制完整会话列表。
 */
data class SharedSessionListResult(
    val snapshotJson: String?,
    val errorCode: String?,
    val errorMessage: String?
) {
    val isSuccess: Boolean get() = errorCode == null
}

/**
 * 会话列表的单一业务状态来源。
 *
 * 平台层负责串行提交、状态发布、本地持久化与网络 I/O；
 * 本类只持有领域状态并运行纯 Reducer。
 */
class SharedSessionListStore(
    newSessionTitle: String,
    remoteSessionPrefix: String,
    blankSessionPrefix: String
) {
    private val labels = SessionListLabels(
        newSessionTitle = newSessionTitle,
        remoteSessionPrefix = remoteSessionPrefix,
        blankSessionPrefix = blankSessionPrefix
    )
    private var state = SessionListState()
    private val events = SharedMviEventEmitter("session-list")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(state.toSnapshot()))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "session-list-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun snapshot(): SharedSessionListResult = try {
        snapshotResult(state)
    } catch (error: Throwable) {
        failure("snapshot", error)
    }

    fun restore(snapshotJson: String): SharedSessionListResult = mutate("restore", alwaysSnapshot = true) {
        val snapshot = wireJson.decodeFromString<SharedSessionListSnapshot>(snapshotJson)
        SessionListState(
            sessions = snapshot.sessions.map(SharedSessionSummarySnapshot::toDomain),
            selectedSessionId = snapshot.selectedSessionId
        )
    }

    fun selectSession(sessionId: String?): SharedSessionListResult = reduce(
        "select",
        SessionListAction.Select(sessionId)
    )

    fun receiveRemoteSessions(sessionsJson: String): SharedSessionListResult = mutate("remote-sessions") {
        val sessions = wireJson.decodeFromString<List<GatewaySessionSummary>>(sessionsJson)
        SessionListReducer.reduce(state, SessionListAction.RemoteSessionsReceived(sessions), labels)
    }

    fun messageSent(
        sessionId: String,
        insertedAtEpochSeconds: Double
    ): SharedSessionListResult = reduce(
        "message-sent",
        SessionListAction.MessageSent(sessionId, insertedAtEpochSeconds)
    )

    fun addKnownSession(
        sessionId: String,
        insertedAtEpochSeconds: Double
    ): SharedSessionListResult = reduce(
        "known-session",
        SessionListAction.KnownSessionAdded(sessionId, insertedAtEpochSeconds)
    )

    fun receiveEvent(
        eventJson: String,
        insertedAtEpochSeconds: Double
    ): SharedSessionListResult = mutate("event") {
        val event = wireJson.decodeFromString<SessionEvent>(eventJson)
        SessionListReducer.reduce(
            state,
            SessionListAction.EventReceived(event, insertedAtEpochSeconds),
            labels
        )
    }

    fun markRead(sessionId: String): SharedSessionListResult = reduce(
        "mark-read",
        SessionListAction.MarkRead(sessionId)
    )

    private fun reduce(operation: String, action: SessionListAction): SharedSessionListResult =
        mutate(operation) { SessionListReducer.reduce(state, action, labels) }

    private inline fun mutate(
        operation: String,
        alwaysSnapshot: Boolean = false,
        transform: () -> SessionListState
    ): SharedSessionListResult = try {
        val next = transform()
        val changed = next != state
        if (changed || alwaysSnapshot) {
            // 先完成可能失败的序列化，再提交状态，保证桥接写入具备原子性。
            val result = snapshotResult(next)
            state = next
            events.emitTransition(
                transactionId = "$operation:${events.currentSequence + 1}",
                statePayloadJson = result.snapshotJson
            )
            result
        } else {
            unchanged()
        }
    } catch (error: Throwable) {
        failure(operation, error)
    }

    private fun snapshotResult(value: SessionListState): SharedSessionListResult = SharedSessionListResult(
        snapshotJson = wireJson.encodeToString(value.toSnapshot()),
        errorCode = null,
        errorMessage = null
    )

    private fun failure(operation: String, error: Throwable): SharedSessionListResult {
        val code = "session-list-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        events.emitError("$code:${events.currentSequence + 1}", code, message)
        return SharedSessionListResult(
            snapshotJson = null,
            errorCode = code,
            errorMessage = message
        )
    }

    private fun unchanged(): SharedSessionListResult = SharedSessionListResult(
        snapshotJson = null,
        errorCode = null,
        errorMessage = null
    )
}

private fun SharedSessionSummarySnapshot.toDomain(): SessionSummary = SessionSummary(
    id = id,
    title = title,
    lastActivityEpochSeconds = lastActivityEpochSeconds,
    isRunning = isRunning,
    hasUnread = hasUnread
)

private fun SessionSummary.toSnapshot(): SharedSessionSummarySnapshot = SharedSessionSummarySnapshot(
    id = id,
    title = title,
    lastActivityEpochSeconds = lastActivityEpochSeconds,
    isRunning = isRunning,
    hasUnread = hasUnread
)

private fun SessionListState.toSnapshot(): SharedSessionListSnapshot = SharedSessionListSnapshot(
    sessions = sessions.map(SessionSummary::toSnapshot),
    selectedSessionId = selectedSessionId
)
