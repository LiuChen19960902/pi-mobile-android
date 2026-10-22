package com.finecat.pimobile.shared.facade

import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationProjectionLabels
import com.finecat.pimobile.shared.projection.ConversationProjectionOperation
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.projection.sessionEventIdentity
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedConversationBootstrap(val schema: Int = 1)

/**
 * 高频 Conversation 专用 patch。live token 只发送有序 operation；历史基线才使用
 * replacementItems，禁止复用低频 SessionControl 的字典快照协议。
 */
@Serializable
data class SharedConversationPatch(
    val schema: Int = 1,
    val sessionId: String,
    val operations: List<ConversationProjectionOperation> = emptyList(),
    val replacesAll: Boolean = false,
    val replacementItems: List<ConversationItem>? = null,
    val lastSequence: Int = -1,
    /** 快照前缀接流：只追加当前流，不推进水位，后续真实事件仍按原 seq 进入。 */
    val seedsStream: Boolean = false
)

/** KMP 持有逐 session projector；平台只 dispatch 原始事件并订阅增量 UI patch。 */
class SharedConversationStore(
    private val labels: ConversationProjectionLabels = ConversationProjectionLabels()
) {
    private val projectors = mutableMapOf<String, ConversationProjector>()
    private val events = SharedMviEventEmitter("conversation")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(SharedConversationBootstrap()))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "conversation-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun receiveEvent(eventJson: String): SharedMviDispatchResult = dispatch("event") {
        val record = wireJson.decodeFromString<SessionEvent>(eventJson)
        require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
        val projector = projectors.getOrPut(record.sessionId) { ConversationProjector(labels) }
        require(record.seq > projector.lastSequence) {
            "event sequence ${record.seq} is not newer than ${projector.lastSequence}; replace baseline first"
        }
        // 先试算再提交：patch 序列化失败则回滚，镜像与投影器不发散
        //（同层 SessionList/History store 的既定约定）。
        val snapshot = projector.snapshot()
        val operations = projector.foldWithOperations(listOf(record))
        if (operations.isEmpty()) return@dispatch null
        val patch = SharedConversationPatch(
            sessionId = record.sessionId,
            operations = operations,
            lastSequence = projector.lastSequence
        )
        try {
            wireJson.encodeToString(patch)
        } catch (error: Throwable) {
            projector.restore(snapshot)
            throw error
        }
        patch
    }

    /**
     * 把 bridge 快照里的“当前流式中段”接进已有投影，不推进 live 水位。
     *
     * 快照没有 pi 的 message_start，后续真实 delta 会从同一 turn/step 继续；
     * 若把前缀只放在临时 overlay，delta 就会另起一个流。这里把前缀按当前
     * lastSequence 折进投影，下一帧真实事件（seq 更大）仍能追加到同一 key。
     * 只接受 assistant/chunk：其他事件有正式历史/实时入口，不能借这里绕过水位。
     */
    fun seedStream(sessionId: String, eventsJson: String): SharedMviDispatchResult = dispatch("seed") {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val records = wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
        require(records.all { it.sessionId == sessionId }) { "seeded stream contains another session" }
        require(records.all { it.event.type == "assistant/chunk" }) { "seeded stream accepts assistant chunks only" }
        val projector = projectors.getOrPut(sessionId) { ConversationProjector(labels) }
        val sequence = projector.lastSequence
        val snapshot = projector.snapshot()
        val operations = projector.foldWithOperations(records.map { it.copy(seq = sequence) })
        if (operations.isEmpty()) return@dispatch null
        val patch = SharedConversationPatch(
            sessionId = sessionId,
            operations = operations,
            lastSequence = projector.lastSequence,
            seedsStream = true
        )
        try {
            wireJson.encodeToString(patch)
        } catch (error: Throwable) {
            projector.restore(snapshot)
            throw error
        }
        patch
    }

    /** 只丢弃指定流式 key；用于 bridge 重放快照前缀，不清其它 live 条目。 */
    fun clearStreams(sessionId: String, keys: List<String>): SharedMviDispatchResult = dispatch("clear-streams") {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        if (keys.isEmpty()) return@dispatch null
        val projector = projectors[sessionId] ?: return@dispatch null
        val snapshot = projector.snapshot()
        val operations = projector.discardStreams(keys)
        if (operations.isEmpty()) return@dispatch null
        val patch = SharedConversationPatch(
            sessionId = sessionId,
            operations = operations,
            lastSequence = projector.lastSequence,
            seedsStream = true
        )
        try {
            wireJson.encodeToString(patch)
        } catch (error: Throwable) {
            projector.restore(snapshot)
            throw error
        }
        patch
    }

    fun replaceSession(sessionId: String, eventsJson: String, minimumSequence: Int? = null): SharedMviDispatchResult =
        dispatch("replace") {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            val records = wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
            require(records.all { it.sessionId == sessionId }) { "baseline contains another session" }
            // 同身份（entryId+type+callId）只投影最后一条：重叠窗口或重复投递时
            // 旧副本会撞出重复 item id（整批会被拒），先收敛；entryId 未知的记录
            // 仍按 seq 去重（与原行为一致）。
            val normalized = records.associateBy { record ->
                sessionEventIdentity(record) ?: "seq:${record.seq}"
            }.values.sortedBy(SessionEvent::seq)
            val projector = ConversationProjector(labels).apply {
                rebuild(normalized)
                // bridge 可删掉旧chunk，但已消费水位不能随剩余历史尾号回退。
                if (minimumSequence != null && minimumSequence > lastSequence) {
                    restore(snapshot().copy(lastSequence = minimumSequence))
                }
            }
            projectors[sessionId] = projector
            SharedConversationPatch(
                sessionId = sessionId,
                replacesAll = true,
                replacementItems = projector.items,
                lastSequence = projector.lastSequence
            )
        }

    fun clearSession(sessionId: String): SharedMviDispatchResult = dispatch("clear") {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        projectors.remove(sessionId)
        SharedConversationPatch(
            sessionId = sessionId,
            replacesAll = true,
            replacementItems = emptyList(),
            lastSequence = -1
        )
    }

    private inline fun dispatch(
        operation: String,
        block: () -> SharedConversationPatch?
    ): SharedMviDispatchResult = try {
        val patch = block()
        if (patch == null) {
            SharedMviDispatchResult(true, null, null)
        } else {
            val event = events.emitTransition(
                transactionId = "conversation-$operation:${events.currentSequence + 1}",
                statePayloadJson = wireJson.encodeToString(patch)
            )
            SharedMviDispatchResult(true, event.transactionId, event.sequence)
        }
    } catch (error: Throwable) {
        val code = "conversation-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        val event = events.emitError("$code:${events.currentSequence + 1}", code, message)
        SharedMviDispatchResult(false, event.transactionId, event.sequence, code, message)
    }
}
