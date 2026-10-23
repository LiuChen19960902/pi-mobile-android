package com.finecat.pimobile.shared.domain

import com.finecat.pimobile.shared.protocol.GatewaySessionSummary
import com.finecat.pimobile.shared.protocol.SessionEvent

data class SessionSummary(
    val id: String,
    val title: String,
    val lastActivityEpochSeconds: Double,
    val isRunning: Boolean,
    val hasUnread: Boolean,
    /** pi 会话的项目目录（新会话落点用）。 */
    val cwd: String? = null,
    /** 当前会话已确认的 assistant/压缩失败；未知结果不造失败。 */
    val hasFailure: Boolean = false
)

data class SessionListLabels(
    val newSessionTitle: String = "New session",
    val remoteSessionPrefix: String = "Remote session ",
    val blankSessionPrefix: String = "Blank session "
) {
    fun remoteTitle(id: String): String = remoteSessionPrefix + id.take(8)
    fun blankTitle(id: String): String = blankSessionPrefix + id.take(8)
}

data class SessionListState(
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null
)

sealed interface SessionListAction {
    data class Select(val sessionId: String?) : SessionListAction
    data class RemoteSessionsReceived(val sessions: List<GatewaySessionSummary>) : SessionListAction
    data class MessageSent(
        val sessionId: String,
        val insertedAtEpochSeconds: Double
    ) : SessionListAction
    data class KnownSessionAdded(
        val sessionId: String,
        val insertedAtEpochSeconds: Double
    ) : SessionListAction
    data class EventReceived(
        val event: SessionEvent,
        val insertedAtEpochSeconds: Double
    ) : SessionListAction
    data class MarkRead(val sessionId: String) : SessionListAction
    /** 来自会话快照/生命周期事件的 streaming || compacting，不等同于一轮聊天。 */
    data class ActivityChanged(val sessionId: String, val isRunning: Boolean) : SessionListAction

    /** 会话改名（session-named 帧）：列表标题即时更新，选择态不动。 */
    data class Renamed(val sessionId: String, val name: String) : SessionListAction
}

object SessionListReducer {
    fun reduce(
        state: SessionListState,
        action: SessionListAction,
        labels: SessionListLabels = SessionListLabels()
    ): SessionListState = when (action) {
        is SessionListAction.Select -> state.copy(selectedSessionId = action.sessionId)
        is SessionListAction.RemoteSessionsReceived -> mergeRemoteSessions(state, action.sessions, labels)
        is SessionListAction.MessageSent -> {
            val sessions = upsert(
                state.sessions,
                action.sessionId,
                labels.newSessionTitle,
                labels,
                action.insertedAtEpochSeconds
            )
            state.copy(
                sessions = sessions,
                selectedSessionId = state.selectedSessionId ?: action.sessionId
            )
        }
        is SessionListAction.KnownSessionAdded -> state.copy(
            sessions = upsert(
                state.sessions,
                action.sessionId,
                labels.remoteTitle(action.sessionId),
                labels,
                action.insertedAtEpochSeconds
            )
        )
        is SessionListAction.EventReceived -> applyEvent(
            state,
            action.event,
            labels,
            action.insertedAtEpochSeconds
        )
        is SessionListAction.MarkRead -> state.copy(
            sessions = state.sessions.map {
                if (it.id == action.sessionId) it.copy(hasUnread = false) else it
            }
        )
        is SessionListAction.ActivityChanged -> state.copy(
            sessions = state.sessions.map {
                if (it.id == action.sessionId) it.copy(
                    isRunning = action.isRunning,
                    hasUnread = it.hasUnread || (it.isRunning && !action.isRunning && state.selectedSessionId != it.id)
                ) else it
            }
        )
        is SessionListAction.Renamed -> state.copy(
            sessions = state.sessions.map {
                if (it.id == action.sessionId) it.copy(title = action.name) else it
            }
        )
    }

    private fun mergeRemoteSessions(
        state: SessionListState,
        remote: List<GatewaySessionSummary>,
        labels: SessionListLabels
    ): SessionListState {
        val sessions = state.sessions.associateBy(SessionSummary::id).toMutableMap()
        remote.forEach { item ->
            val fallback = item.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotEmpty)
            val title = item.displayTitle ?: fallback ?: labels.remoteTitle(item.id)
            val timestamp = item.modified?.let(::isoToEpochSeconds)
                ?: item.created?.let(::isoToEpochSeconds)
                ?: 0.0
            val existing = sessions[item.id]
            // 新老同一标题规则：真名（displayTitle，含改名收敛）照收；
            // 无真名时：占位标题（blank/new/remote 前缀）按新会话规则重算
            //（仍空会话保持 blank，不掉成目录名）；有意义标题不被占位刷新覆盖。
            val isPlaceholder = existing == null ||
                existing.title == labels.newSessionTitle ||
                existing.title.startsWith(labels.remoteSessionPrefix) ||
                existing.title.startsWith(labels.blankSessionPrefix)
            val resolvedTitle = item.displayTitle
                ?: if (isPlaceholder) (if (item.blank) labels.blankTitle(item.id) else title) else existing?.title ?: title
            sessions[item.id] = existing?.copy(
                title = resolvedTitle,
                lastActivityEpochSeconds = timestamp,
                isRunning = item.running,
                cwd = item.cwd ?: existing.cwd
            ) ?: SessionSummary(
                id = item.id,
                title = if (item.blank) labels.blankTitle(item.id) else title,
                lastActivityEpochSeconds = timestamp,
                isRunning = item.running,
                hasUnread = false,
                cwd = item.cwd
            )
        }
        return state.copy(sessions = sessions.values
            .sortedByDescending(SessionSummary::lastActivityEpochSeconds))
    }

    private fun applyEvent(
        state: SessionListState,
        record: SessionEvent,
        labels: SessionListLabels,
        insertedAtEpochSeconds: Double
    ): SessionListState {
        val event = record.event
        val title = when (event.type) {
            "user/message" -> event.text?.take(28)
            "session/title" -> event.text
            else -> null
        }
        var sessions = upsert(state.sessions, record.sessionId, title, labels, insertedAtEpochSeconds)
        val updatesMetadata = event.type in setOf(
            "user/message", "session/title", "turn/start", "turn/end", "assistant/message",
            "compact/start", "compact/end", "compact/done", "notice/compact"
        )
        if (!updatesMetadata) return state.copy(sessions = sessions)
        sessions = sessions.map { session ->
            if (session.id != record.sessionId) return@map session
            session.copy(
                lastActivityEpochSeconds = normalizeEpochSeconds(record.time),
                isRunning = when (event.type) {
                    "turn/start" -> true
                    "turn/end" -> false
                    else -> session.isRunning
                },
                hasUnread = if (event.type == "compact/start") session.hasUnread
                    else state.selectedSessionId != record.sessionId
            )
        }.sortedByDescending(SessionSummary::lastActivityEpochSeconds)
        return state.copy(sessions = sessions)
    }

    private fun upsert(
        sessions: List<SessionSummary>,
        id: String,
        title: String?,
        labels: SessionListLabels,
        insertedAtEpochSeconds: Double
    ): List<SessionSummary> {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) {
            return listOf(
                SessionSummary(
                    id,
                    title ?: labels.remoteTitle(id),
                    insertedAtEpochSeconds,
                    false,
                    false
                )
            ) + sessions
        }
        val existing = sessions[index]
        // 与 mergeRemoteSessions 的占位判定同口径：blank 会话首条消息可接管标题。
        val canReplace = existing.title == labels.newSessionTitle ||
            existing.title.startsWith(labels.remoteSessionPrefix) ||
            existing.title.startsWith(labels.blankSessionPrefix)
        if (title.isNullOrEmpty() || !canReplace) return sessions
        return sessions.toMutableList().apply { this[index] = existing.copy(title = title) }
    }
}

internal fun normalizeEpochSeconds(value: Double): Double =
    if (value > 10_000_000_000) value / 1_000 else value

/**
 * 解析 pi 会话元数据的 ISO-8601 时间戳（JS `Date.toISOString()` 产出形状：
 * `yyyy-MM-ddTHH:mm:ss(.sss)?(Z|±HH:MM)`）为 epoch 秒。commonMain 无
 * java.time/kotlinx-datetime，故手写民用日期算法。
 */
internal fun isoToEpochSeconds(value: String): Double? {
    val match = ISO_TIMESTAMP.matchEntire(value.trim()) ?: return null
    val (year, month, day, hour, minute, second, fraction, offset) = match.destructured
    val millis = when {
        fraction.isEmpty() -> 0L
        else -> {
            val padded = (fraction + "000").take(3)
            padded.toLongOrNull() ?: return null
        }
    }
    val offsetSeconds = when (offset) {
        "Z", "z", "" -> 0L
        else -> {
            val sign = if (offset[0] == '-') -1L else 1L
            val digits = offset.drop(1).replace(":", "")
            val hours = digits.take(2).toLongOrNull() ?: return null
            val minutes = digits.drop(2).toLongOrNull() ?: 0L
            sign * (hours * 3_600 + minutes * 60)
        }
    }
    val days = daysFromCivil(year.toIntOrNull() ?: return null, month.toIntOrNull() ?: return null, day.toIntOrNull() ?: return null)
    val secondsOfDay = (hour.toIntOrNull() ?: return null) * 3_600L +
        (minute.toIntOrNull() ?: return null) * 60L +
        (second.toIntOrNull() ?: return null)
    val epochMillis = (days * 86_400_000L) + secondsOfDay * 1_000L + millis - offsetSeconds * 1_000L
    return epochMillis / 1_000.0
}

private val ISO_TIMESTAMP = Regex(
    """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?"""
)

/** 天数自 1970-01-01（Howard Hinnant 的 days_from_civil 算法）。 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}
