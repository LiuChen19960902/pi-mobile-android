package com.finecat.pimobile.android

import com.finecat.pimobile.shared.facade.SharedMobileSnapshot
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.projection.TrajectoryNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Projection mutation、结果发布和对应 UI 清理共用一个有序提交边界。 */
internal class AndroidProjectionActor(
    private val projection: AndroidGatewayProjection,
    private val uiDispatcher: CoroutineDispatcher,
    private val publish: (snapshot: SharedMobileSnapshot, coalesceWithDisplayFrame: Boolean) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val mutationLock = Mutex()
    private var pendingStreamingFrame: PendingStreamingFrame? = null
    /** 合并后的流式帧负载（raw/frame 取自最后一条，normalizedEvents 为合成事件流）。 */
    private var pendingStreamingRaw: String? = null
    private var pendingStreamingFrameWire: GatewayFrame? = null
    private var lastStreamingFlushNanos = 0L

    val initialSnapshot: SharedMobileSnapshot = projection.snapshot()

    suspend fun acceptFrame(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        normalizedEvents: List<SessionEvent> = emptyList(),
        afterPublish: () -> Unit = {}
    ) = mutate(
        afterPublish = afterPublish,
        coalesceWithDisplayFrame = frame.kind == "event" &&
            normalizedEvents.any { it.event.type == "assistant/chunk" }
    ) { projection.acceptFrame(rawJson, frame, correlatedSessionId, normalizedEvents) }

    /**
     * 将同一步骤的微小文本 token 合成一次增量投影。Runtime 仍无损处理每个协议帧；这里只
     * 降低 history/conversation MVI、JSON patch 和 Main dispatcher 的提交频率。
     */
    suspend fun acceptStreamingFrame(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        normalizedEvents: List<SessionEvent>
    ): Boolean = mutationLock.withLock {
        if (normalizedEvents.none { it.event.type == "assistant/chunk" }) {
            flushPendingStreamingFrameLocked()
            publishMutationLocked(
                projection.acceptFrame(rawJson, frame, correlatedSessionId, normalizedEvents),
                coalesceWithDisplayFrame = false
            )
            return@withLock true
        }

        var flushed = false
        val current = pendingStreamingFrame
        pendingStreamingFrame = if (current == null) {
            PendingStreamingFrame(normalizedEvents, correlatedSessionId, count = 1)
        } else if (current.canMerge(normalizedEvents, correlatedSessionId)) {
            current.merge(normalizedEvents)
        } else {
            flushPendingStreamingFrameLocked()
            flushed = true
            PendingStreamingFrame(normalizedEvents, correlatedSessionId, count = 1)
        }
        // raw/frame 取最后一条：归一化事件已合并，flush 时只需正确的 kind 与 sessionId。
        pendingStreamingRaw = rawJson
        pendingStreamingFrameWire = frame

        val now = nowNanos()
        if (
            lastStreamingFlushNanos == 0L ||
            requireNotNull(pendingStreamingFrame).count >= MAXIMUM_STREAMING_BATCH_SIZE ||
            now - lastStreamingFlushNanos >= STREAMING_PROJECTION_INTERVAL_NANOS
        ) {
            flushPendingStreamingFrameLocked()
            lastStreamingFlushNanos = now
            flushed = true
        }
        flushed
    }

    suspend fun selectSession(sessionId: String?, afterPublish: () -> Unit = {}) =
        mutate(afterPublish) { projection.selectSession(sessionId) }

    suspend fun loadHistory(
        sessionId: String,
        older: Boolean,
        preserveLive: Boolean = false,
        afterPublish: () -> Unit = {}
    ) = mutate(afterPublish) { projection.loadHistory(sessionId, older, preserveLive) }

    suspend fun catchUpSelectedHistoryAfterReconnect() = mutate {
        projection.catchUpSelectedHistoryAfterReconnect()
    }

    suspend fun loadFixture(afterPublish: () -> Unit = {}) =
        mutate(afterPublish = afterPublish, mutation = projection::loadFixture)

    suspend fun reset(afterPublish: () -> Unit = {}) =
        mutate(afterPublish = afterPublish, mutation = projection::reset)

    suspend fun historyRejected(sessionId: String, code: String) = mutate {
        projection.historyRejected(sessionId, code)
        projection.snapshot()
    }

    suspend fun historyTimedOut(sessionId: String) = mutate {
        projection.historyTimedOut(sessionId)
        projection.snapshot()
    }

    suspend fun historyCancelled(sessionId: String) = mutate {
        projection.historyCancelled(sessionId)
        projection.snapshot()
    }

    suspend fun trajectory(sessionId: String?): List<TrajectoryNode> = mutationLock.withLock {
        flushPendingStreamingFrameLocked()
        projection.trajectory(sessionId)
    }

    fun acceptFrameImmediate(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        normalizedEvents: List<SessionEvent> = emptyList()
    ) {
        publish(projection.acceptFrame(rawJson, frame, correlatedSessionId, normalizedEvents), false)
    }

    fun loadHistoryImmediate(sessionId: String, older: Boolean, preserveLive: Boolean = false) {
        publish(projection.loadHistory(sessionId, older, preserveLive), false)
    }

    fun selectSessionImmediate(sessionId: String?, afterPublish: () -> Unit = {}) {
        publish(projection.selectSession(sessionId), false)
        afterPublish()
    }

    fun loadFixtureImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.loadFixture(), false)
        afterPublish()
    }

    fun resetImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.reset(), false)
        afterPublish()
    }

    fun close() {
        pendingStreamingFrame = null
        projection.close()
    }

    private suspend fun mutate(
        afterPublish: () -> Unit = {},
        coalesceWithDisplayFrame: Boolean = false,
        mutation: () -> SharedMobileSnapshot
    ) {
        mutationLock.withLock {
            flushPendingStreamingFrameLocked()
            val next = mutation()
            publishMutationLocked(next, coalesceWithDisplayFrame, afterPublish)
        }
    }

    private suspend fun flushPendingStreamingFrameLocked() {
        val pending = pendingStreamingFrame ?: return
        pendingStreamingFrame = null
        val raw = pendingStreamingRaw
        val wire = pendingStreamingFrameWire
        pendingStreamingRaw = null
        pendingStreamingFrameWire = null
        publishMutationLocked(
            projection.acceptFrame(
                raw.orEmpty(),
                wire ?: GatewayFrame(kind = "event"),
                pending.correlatedSessionId,
                pending.normalizedEvents
            ),
            coalesceWithDisplayFrame = true
        )
    }

    private suspend fun publishMutationLocked(
        next: SharedMobileSnapshot,
        coalesceWithDisplayFrame: Boolean,
        afterPublish: () -> Unit = {}
    ) {
        withContext(uiDispatcher) {
            publish(next, coalesceWithDisplayFrame)
            afterPublish()
        }
    }

    private data class PendingStreamingFrame(
        val normalizedEvents: List<SessionEvent>,
        val correlatedSessionId: String?,
        val count: Int
    ) {
        /** 同会话 + 同流式 key（turn/step/chunkType 一致）才合并。 */
        fun canMerge(nextEvents: List<SessionEvent>, nextCorrelatedSessionId: String?): Boolean {
            if (correlatedSessionId != nextCorrelatedSessionId) return false
            if (normalizedEvents.size != nextEvents.size) return false
            return normalizedEvents.zip(nextEvents).all { (left, right) ->
                left.event.type == "assistant/chunk" && right.event.type == "assistant/chunk" &&
                    left.sessionId == right.sessionId &&
                    left.event.turn == right.event.turn &&
                    left.event.step == right.event.step &&
                    left.event.chunkType == right.event.chunkType &&
                    // 同一 turn 内可能有多个 toolCall 的 delta：id 不同不能合并，
                    // 否则后一个 toolCall 的增量会被并进前一个。
                    left.event.tool?.id == right.event.tool?.id
            }
        }

        fun merge(nextEvents: List<SessionEvent>): PendingStreamingFrame {
            val merged = normalizedEvents.mapIndexed { index, left ->
                val right = nextEvents.getOrNull(index) ?: return@mapIndexed left
                if (left.event.type != "assistant/chunk" || left.event.chunkType != right.event.chunkType) {
                    return@mapIndexed left
                }
                val leftText = left.event.text.orEmpty()
                val rightText = right.event.text.orEmpty()
                left.copy(
                    event = left.event.copy(
                        text = leftText + rightText,
                        // tool-call-delta 的载荷在 argumentsDelta 而不在 text：只拼
                        // text 会把第一帧之后的工具参数增量全部丢掉。
                        tool = left.event.tool?.let { tool ->
                            tool.copy(
                                argumentsDelta = tool.argumentsDelta.orEmpty() +
                                    right.event.tool?.argumentsDelta.orEmpty()
                            )
                        }
                    )
                )
            }
            return copy(normalizedEvents = merged, count = count + 1)
        }
    }

    companion object {
        private const val MAXIMUM_STREAMING_BATCH_SIZE = 32
        private const val STREAMING_PROJECTION_INTERVAL_NANOS = 50_000_000L
    }
}
