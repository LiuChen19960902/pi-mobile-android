package com.finecat.pimobile.shared.gateway

import com.finecat.pimobile.shared.platform.GatewayAttachmentCache
import com.finecat.pimobile.shared.platform.GatewayClock
import com.finecat.pimobile.shared.platform.GatewayConnectionSpec
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import com.finecat.pimobile.shared.platform.GatewayNetworkMonitor
import com.finecat.pimobile.shared.platform.GatewayNetworkState
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import com.finecat.pimobile.shared.platform.GatewayTransport
import com.finecat.pimobile.shared.platform.GatewayTransportFrame
import com.finecat.pimobile.shared.platform.GatewayTransportEvent
import com.finecat.pimobile.shared.platform.GatewayTransportState
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayModelSelection
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class GatewayConnectionState {
    DISCONNECTED,
    WAITING_FOR_NETWORK,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    FAILED,
    SUSPENDED
}

data class GatewayRuntimeState(
    /** 仅显式配对递增，隔离已入队但尚未被UI消费的旧网关帧。 */
    val gatewayRevision: Long = 0,
    val connection: GatewayConnectionState = GatewayConnectionState.DISCONNECTED,
    val endpoint: String? = null,
    val networkAvailable: Boolean = true,
    val serverPort: Int? = null,
    val clientCount: Int? = null,
    val protocolVersion: Int? = null,
    val capabilities: Set<String> = emptySet(),
    val reconnectAttempt: Int = 0,
    val activeTurnSessionIds: Set<String> = emptySet(),
    val hasUnassociatedTurn: Boolean = false,
    val lastError: String? = null
) {
    val shouldKeepAliveInBackground: Boolean
        get() = activeTurnSessionIds.isNotEmpty() || hasUnassociatedTurn
}

sealed interface GatewayRuntimeEvent {
    data class Frame(
        val rawJson: String,
        val frame: GatewayFrame,
        val correlatedSessionId: String?,
        /** pi event 帧经 PiEventNormalizer 归一后的内部视图事件（0..n 条）。 */
        val normalizedEvents: List<SessionEvent> = emptyList(),
        val gatewayRevision: Long = 0,
    ) : GatewayRuntimeEvent {
        override fun toString(): String =
            "Frame(kind=${frame.kind}, correlatedSessionId=$correlatedSessionId, rawJson=<redacted>)"
    }

    data class RequestQueued(val requestType: String, val responseKind: String) : GatewayRuntimeEvent
    data class RequestCancelled(
        val requestType: String,
        val targetSessionId: String?,
        val reason: String,
        val correlationId: String? = null
    ) : GatewayRuntimeEvent
    data class RequestTimedOut(
        val requestType: String,
        val targetSessionId: String?,
        val correlationId: String? = null
    ) : GatewayRuntimeEvent
    data class RequestRejected(
        val requestType: String,
        val reason: String,
        val targetSessionId: String? = null,
        val correlationId: String? = null,
        /** 已通过在途关联的服务端拒绝；本地 busy 等拒绝由发送返回值按自身 token 收尾。 */
        val serverResponse: Boolean = false,
    ) : GatewayRuntimeEvent
}

/** 所有可变状态与平台 I/O 均经过同一串行边界。 */
class GatewayRuntime(
    private val transport: GatewayTransport,
    private val preferences: GatewayPreferences,
    private val credentials: GatewayCredentialStore,
    private val attachmentCache: GatewayAttachmentCache,
    private val networkMonitor: GatewayNetworkMonitor,
    private val clock: GatewayClock,
    private val scope: CoroutineScope,
    private val requestTimeoutMilliseconds: Long = DEFAULT_REQUEST_TIMEOUT_MILLISECONDS,
    private val recoveryWindowMilliseconds: Long = DEFAULT_RECOVERY_WINDOW_MILLISECONDS,
    /** 非末位候选地址的握手预算：超了就当这个地址不可达，立刻换下一个。 */
    private val endpointProbeTimeoutMilliseconds: Long = DEFAULT_ENDPOINT_PROBE_TIMEOUT_MILLISECONDS,
    /** 末位候选地址的握手预算：给冷启动较慢的 tailnet 留充量，避免误判。 */
    private val finalHandshakeTimeoutMilliseconds: Long = DEFAULT_FINAL_HANDSHAKE_TIMEOUT_MILLISECONDS,
    private val frameDecoder: (String) -> GatewayFrame = GatewayWireDecoder::decode
) {
    private val serialization = Mutex()
    private val mutableState = MutableStateFlow(
        GatewayRuntimeState(networkAvailable = networkMonitor.state.value == GatewayNetworkState.AVAILABLE)
    )
    private val eventQueue = BoundedGatewayRuntimeEventQueue(
        maximumEvents = MAXIMUM_RUNTIME_EVENT_COUNT,
        maximumBytes = MAXIMUM_RUNTIME_EVENT_BYTES
    )
    private val pendingLanes = mutableMapOf<String, PendingLane>()
    /** 超大turn退回已有entry分页；游标可能在轮内，同会话后续不能切回turn分页。 */
    private val entryHistorySessionIds = mutableSetOf<String>()
    // 配对凭据是网关身份；无凭据时按地址隔离。仅内存比较，不记录到日志/偏好。
    private var entryHistoryGatewayKey: Pair<String?, String?>? = null
    /** §4.22 在途取图（单 slot，与 pendingLanes["attachment"] 同生命周期）。 */
    private var pendingAttachmentFetch: PendingAttachmentFetch? = null
    /** 每会话一个 pi 事件归一器（合成的 turn 计数依赖会话内顺序）。 */
    private val eventNormalizers = mutableMapOf<String, PiEventNormalizer>()
    private val deferredRequests = ArrayDeque<GatewayRequest>()
    // 新协议计数仅代表等待sent的输入；实际运行与压缩由事件/快照独立记录。
    // 旧网关仍保留原来的输入→settled兼容计数。
    private val activeTurnCountsBySession = mutableMapOf<String, Int>()
    private val observedRunningSessions = mutableSetOf<String>()
    private val observedCompactingSessions = mutableSetOf<String>()
    private val hasPromptDisposition: Boolean
        get() = "prompt-disposition-v1" in mutableState.value.capabilities
    private var unassociatedTurnCount = 0
    private var desiredConnection = false
    private var reconnectBlocked = false
    private var pairingCode: String? = null
    private var endpoint: String? = null
    /** 本轮连接要依次尝试的地址（顺序即优先级），[endpointCursor] 指向当前在试的那个。 */
    private var endpointOrder: List<String> = emptyList()
    private var endpointCursor = 0
    private var handshakeJob: Job? = null
    private var subscribedSessionId: String? = null
    private var previousSubscribedSessionId: String? = null
    private var isInBackground = false
    private var connectionGeneration = 0L
    private var requestGeneration = 0L
    private var reconnectJob: Job? = null
    private var recoveryDeadlineJob: Job? = null

    val state: StateFlow<GatewayRuntimeState> = mutableState.asStateFlow()
    val events: Flow<GatewayRuntimeEvent> = eventQueue.flow

    init {
        collectSafely(transport.events, ERROR_INCOMING_FLOW) { event ->
            when (event) {
                is GatewayTransportEvent.Frame -> handleIncomingFrameLocked(event.value)
                is GatewayTransportEvent.State -> handleTransportStateLocked(event.value)
            }
        }
        collectSafely(networkMonitor.state, ERROR_NETWORK_FLOW, ::handleNetworkLocked)
    }

    suspend fun connect(endpoint: String) = serialized {
        requireWebSocketEndpoint(endpoint)
        val existing = preferences.load()
        // 用户显式指定的地址排到候选首位，其余备用地址保持原有相对顺序。
        val order = listOf(endpoint) + roundOrderLocked(existing).filter { it != endpoint }
        preferences.updateFields { it.copy(endpoint = endpoint, endpointCandidates = order) }
        beginConnectionRoundLocked(order, pairingCode = null)
    }

    suspend fun pair(encodedPayload: String) = serialized {
        val payload = GatewayPairingPayloadParser.parse(encodedPayload, clock.nowEpochMilliseconds())
        val existing = preferences.load()
        val configured = roundOrderLocked(existing)
        // 显式配对以载荷指定的网关为目标，旧入口保留原相对顺序供后续连接使用。
        val order = (listOf(payload.publicUrl) + configured).distinct()
        preferences.updateFields { it.copy(endpoint = payload.publicUrl, endpointCandidates = order) }
        connectionGeneration += 1
        runCatching { transport.close() }
        clearConnectionWorkLocked()
        subscribedSessionId = null
        previousSubscribedSessionId = null
        mutableState.value = GatewayRuntimeState(
            gatewayRevision = mutableState.value.gatewayRevision + 1,
            endpoint = payload.publicUrl,
            networkAvailable = networkAvailableLocked(),
        )
        // 一次性配对码只交给其签发地址，不能因探测超时而投给历史入口。
        beginConnectionRoundLocked(listOf(payload.publicUrl), pairingCode = payload.pairingCode)
    }

    /**
     * 更新备用地址列表（同一网关的多个入口）。列表顺序即尝试顺序；
     * 已连上的连接不会因此中断，新顺序从下一轮重试开始生效。
     */
    suspend fun setEndpointCandidates(candidates: List<String>) = serialized {
        val normalized = candidates.map(String::trim).filter(String::isNotEmpty).distinct()
        normalized.forEach { requireWebSocketEndpoint(it) }
        val existing = preferences.load()
        val nextEndpoint = normalized.firstOrNull() ?: existing.endpoint
        preferences.updateFields { it.copy(endpoint = nextEndpoint, endpointCandidates = normalized) }
        endpointOrder = normalized.ifEmpty { listOf(nextEndpoint) }
        endpointCursor = 0
        if (desiredConnection) {
            // 改地址算新的连接意图：解除上一轮的鉴权/不可达封锁。
            reconnectBlocked = false
            if (mutableState.value.connection != GatewayConnectionState.CONNECTED) {
                scheduleReconnectLocked(immediate = true)
            }
        }
    }

    /** 新的连接意图：重置尝试轮次，从列表首个地址开始。 */
    private suspend fun beginConnectionRoundLocked(order: List<String>, pairingCode: String?) {
        this.pairingCode = pairingCode
        desiredConnection = true
        reconnectBlocked = false
        endpointOrder = order
        endpointCursor = 0
        handshakeJob?.cancel()
        handshakeJob = null
        reconnectJob?.cancel()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        openTransportLocked(preserveTurns = false)
    }

    /** 候选列表为空时回退到单一 endpoint，保证不会空转。 */
    private fun roundOrderLocked(snapshot: GatewayPreferencesSnapshot): List<String> =
        snapshot.endpointCandidates.map(String::trim).filter(String::isNotEmpty).distinct()
            .ifEmpty {
                // 升级路径：旧版本只有 endpoint。内置默认地址（从没配对过的全新安装）
                // 不能当作“用户配好的地址”，否则配对待会先撞一个连不上的回环地址。
                listOf(snapshot.endpoint.trim()).filter { it.isNotEmpty() && it != LEGACY_DEFAULT_ENDPOINT }
            }

    suspend fun connectStoredIfPaired(): Boolean = serialized {
        val stored = preferences.load()
        if (
            desiredConnection && endpoint == stored.endpoint &&
            mutableState.value.connection in IDEMPOTENT_CONNECTION_STATES
        ) {
            return@serialized true
        }
        val token = runCatching { credentials.loadToken() }.getOrElse {
            failOpenLocked(ERROR_CREDENTIAL_ACCESS)
            return@serialized false
        }
        if (token.isNullOrBlank()) return@serialized false
        val order = roundOrderLocked(stored).ifEmpty { listOf(stored.endpoint.trim()) }
        order.forEach { requireWebSocketEndpoint(it) }
        beginConnectionRoundLocked(order, pairingCode = null)
        true
    }

    suspend fun disconnect() = serialized {
        desiredConnection = false
        reconnectBlocked = false
        pairingCode = null
        endpointCursor = 0
        handshakeJob?.cancel()
        handshakeJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        connectionGeneration += 1
        clearConnectionWorkLocked()
        runCatching { transport.close() }
        mutableState.value = mutableState.value.copy(
            connection = GatewayConnectionState.DISCONNECTED,
            reconnectAttempt = 0,
            lastError = null
        )
    }

    suspend fun applicationDidEnterBackground() = serialized {
        isInBackground = true
        if (!mutableState.value.shouldKeepAliveInBackground) {
            connectionGeneration += 1
            cancelPendingLocked(ERROR_BACKGROUND_SUSPENDED)
            runCatching { transport.close() }
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.SUSPENDED)
        }
    }

    fun applicationDidBecomeActive() {
        scope.launch {
            serialized {
                isInBackground = false
                if (canReconnectLocked() && mutableState.value.connection !in setOf(
                        GatewayConnectionState.CONNECTING,
                        GatewayConnectionState.AUTHENTICATING,
                        GatewayConnectionState.CONNECTED
                    )) {
                    // 回前台是一个自然的重新探测点：从列表首个地址重走一轮。
                    endpointCursor = 0
                    scheduleReconnectLocked(immediate = true)
                }
            }
        }
    }

    suspend fun sendRequest(request: GatewayRequest): Boolean = serialized { sendRequestLocked(request) }

    suspend fun requestSessions(): Boolean = sendRequest(GatewayRequests.sessions())

    suspend fun requestHistory(
        sessionId: String,
        beforeEntryId: String? = null,
        limit: Int = 60,
        resetLeaf: Boolean = false
    ): Boolean = sendRequest(
        GatewayRequests.history(sessionId, beforeEntryId, limit, resetLeaf,
            turnLimit = 10.takeIf { "history-turns-v1" in mutableState.value.capabilities })
    )

    suspend fun requestState(sessionId: String): Boolean =
        sendRequest(GatewayRequests.state(sessionId))

    /** 重载配置（重建会话，PROTOCOL.md §4.20）：应答 session-restarted 后
     *  客户端以 resetLeaf 重拉 history 完成视图重建（与重连 catch-up 同通道）。 */
    suspend fun restartSession(sessionId: String): Boolean =
        sendRequest(GatewayRequests.restartSession(sessionId))

    /** 会话级 key 优先，miss 后回退发送端写入的全局 key（新会话发送时 sessionId 未知）。 */
    suspend fun readCachedAttachment(sessionId: String, attachmentId: String): ByteArray? =
        readCachedAttachmentBytes(gatewayAttachmentCacheKey(sessionId, attachmentId), attachmentId)

    /** 发送端本地缓存：发送前写入，回显消息按 attachmentId 命中渲染。 */
    suspend fun writeLocalAttachment(attachmentId: String, bytes: ByteArray): Boolean =
        try {
            attachmentCache.write(attachmentId, bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }

    /**
     * 历史图片按需取回（PROTOCOL §4.22）：三元寻址 (sessionId, entryId,
     * imageIndex)。[cacheAttachmentId] 只做内部缓存键，不上 wire。
     *
     * 生命周期：缓存命中直接返回（不发请求）；miss 且 hello 宣告 attachment
     * 才发请求。等待回复不持 serialization 锁；成功/服务端错误/超时/断连/
     * 发送失败/调用方取消都以结果（bytes 或 null）终态并释放 lane（单 lane
     * REJECT_IF_BUSY，同一时刻至多一个在途请求；互斥判定先于 slot 变更，
     * busy 请求不得影响在途等待）。成功先写会话级缓存（写失败按失败计）
     * 再返回字节。响应按 session+entryId+index 严格校验，错/迟到响应不得
     * 认领当前请求；attachment 帧本体不进 UI 事件队列（避免大 base64 驻留），
     * 事件流只保留小元数据终态；调用方取消属纯内存小清理，不发事件。超时
     * 只结束自己的 lane/slot，不回收整条连接（避免误伤在途 message/history）。
     */
    suspend fun fetchAttachment(
        sessionId: String,
        entryId: String,
        imageIndex: Int,
        cacheAttachmentId: String
    ): ByteArray? {
        if (
            sessionId.isBlank() || entryId.isBlank() || cacheAttachmentId.isBlank() || imageIndex < 0
        ) {
            return null
        }
        val sessionKey = gatewayAttachmentCacheKey(sessionId, cacheAttachmentId)
        readCachedAttachmentBytes(sessionKey, cacheAttachmentId)?.let { return it }
        if (mutableState.value.connection != GatewayConnectionState.CONNECTED) return null
        if ("attachment" !in mutableState.value.capabilities) return null

        val request = GatewayRequests.attachment(sessionId, entryId, imageIndex)
        val deferred = CompletableDeferred<ByteArray?>()
        return try {
            val sent = serialized {
                // 互斥判定必须先于 slot 变更：lane busy 时按 REJECT_IF_BUSY 拒绝本次
                // 调用，不得触碰在途等待（否则在途 A 被提前作废、其回复无人认领、
                // lane 挂到超时，B 也失败）。
                if (pendingLanes["attachment"] != null) return@serialized sendRequestLocked(request)
                // 防御：lane 空闲却仍有旧 slot（终态路径漏清，理论不应发生）时作废旧
                // 等待，不让它的 caller 挂死。
                pendingAttachmentFetch?.deferred?.complete(null)
                pendingAttachmentFetch = PendingAttachmentFetch(deferred, sessionId, entryId, imageIndex)
                val ok = sendRequestLocked(request)
                if (!ok) pendingAttachmentFetch = null
                ok
            }
            if (!sent) return null
            val bytes = deferred.await() ?: return null
            val written = try {
                attachmentCache.write(sessionKey, bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (written) bytes else null
        } catch (cancellation: CancellationException) {
            // 调用方取消（send 或 await 阶段均可发生）：协程已取消，普通 serialized
            // 等锁会被取消打断而漏清理，故在不可取消区等串行锁，保证最终执行。
            // 只清理仍属于自己的 slot/lane——回复已到、后续请求接管后不得误杀；
            // 调用方取消对 UI 无意义，不发事件。块内纯内存操作 + 取消自己的
            // timeout，无阻塞点。
            withContext(NonCancellable) {
                serialized {
                    if (pendingAttachmentFetch?.deferred === deferred) {
                        pendingAttachmentFetch = null
                    }
                    pendingLanes["attachment"]?.let { lane ->
                        if (lane.active.request === request) {
                            lane.active.timeoutJob.cancel()
                            pendingLanes.remove("attachment")
                        }
                    }
                }
            }
            throw cancellation
        }
    }

    /** 缓存读取（会话级优先再裸键，与 readCachedAttachment 同序），供 fetch 前 miss 判定。
     *  读失败按 miss；调用方取消不吞（吞掉会继续执行并发新请求）。 */
    private suspend fun readCachedAttachmentBytes(vararg keys: String): ByteArray? {
        for (key in keys) {
            try {
                attachmentCache.read(key)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 单键读失败按 miss 继续。
            }
        }
        return null
    }

    suspend fun subscribe(sessionId: String?): Boolean = serialized {
        val nextSubscription = sessionId?.takeIf(String::isNotBlank)
        subscribedSessionId = nextSubscription
        val request = if (nextSubscription == null) {
            val previous = previousSubscribedSessionId
            previousSubscribedSessionId = null
            if (previous == null) true else sendRequestLocked(GatewayRequests.unsubscribe(previous))
        } else {
            previousSubscribedSessionId = nextSubscription
            sendRequestLocked(GatewayRequests.subscribe(nextSubscription))
        }
        request
    }

    suspend fun abort(sessionId: String): Boolean =
        sendRequest(GatewayRequests.abort(sessionId))

    suspend fun sendMessage(
        text: String,
        images: List<GatewayOutgoingImage>,
        sessionId: String?,
        cwd: String? = null,
        /** 新会话的模型预选：随 message 帧携带，pi 在首轮前应用。 */
        model: GatewayModelSelection? = null,
        /** pi streamingBehavior：忙时 steer=注入本轮、followUp=排队下轮。 */
        mode: String = "followUp"
    ): Boolean = serialized {
        if (text.isBlank() && images.isEmpty()) return@serialized false
        if (!outgoingImagesAreWithinLimits(images)) {
            rejectLocked("message", ERROR_IMAGE_LIMITS)
            return@serialized false
        }
        if (sessionId != null && pendingLanes["dequeued"]?.active?.request?.targetSessionId == sessionId) {
            rejectLocked("message", ERROR_REQUEST_BUSY, sessionId)
            return@serialized false
        }
        markTurnLocked(sessionId)
        val sent = sendRequestLocked(
            GatewayRequests.message(text.trim(), images, sessionId, cwd, model, mode)
        )
        if (!sent) releaseTurnLocked(sessionId)
        sent
    }

    private suspend fun sendRequestLocked(request: GatewayRequest): Boolean {
        if (request.requestType == "dequeue" &&
            pendingLanes["sent"]?.active?.request?.targetSessionId == request.targetSessionId &&
            pendingLanes["sent"] != null) {
            rejectLocked("dequeue", ERROR_REQUEST_BUSY, request.targetSessionId, request.correlationId)
            return false
        }
        if (mutableState.value.connection != GatewayConnectionState.CONNECTED) {
            rejectLocked(
                request.requestType,
                ERROR_NOT_CONNECTED,
                request.targetSessionId,
                request.correlationId
            )
            return false
        }
        // 光标补全必须立即替换在途查询，让网关取消旧 fd；普通请求仍沿用原队列策略。
        if (request.requestType in INPUT_ASSIST_REQUEST_TYPES) {
            cancelLaneLocked(request.responseKind, ERROR_COALESCED)
        }
        val lane = pendingLanes[request.responseKind]
        if (lane != null) {
            when (request.lanePolicy) {
                GatewayRequestLanePolicy.REJECT_IF_BUSY -> {
                    rejectLocked(
                        request.requestType,
                        ERROR_REQUEST_BUSY,
                        request.targetSessionId,
                        request.correlationId
                    )
                    return false
                }
                GatewayRequestLanePolicy.FIFO -> lane.queued.addLast(request)
                GatewayRequestLanePolicy.COALESCE_LATEST -> {
                    while (lane.queued.isNotEmpty()) {
                        emitCancelledLocked(lane.queued.removeFirst(), ERROR_COALESCED)
                    }
                    lane.queued.addLast(request)
                }
            }
            emitEventLocked(GatewayRuntimeEvent.RequestQueued(request.requestType, request.responseKind))
            return true
        }
        return sendAsActiveLocked(request)
    }

    private fun historyPagingRequest(request: GatewayRequest): GatewayRequest {
        if (request.requestType != "history" || request.targetSessionId !in entryHistorySessionIds) return request
        val payload = wireJson.parseToJsonElement(request.payload).jsonObject
        return if ("turnLimit" !in payload) request
        else request.copy(payload = JsonObject(payload - "turnLimit").toString())
    }

    private suspend fun sendAsActiveLocked(original: GatewayRequest): Boolean {
        // 在真正发送时处理，覆盖先前已排队及重连重放的history请求。
        val request = historyPagingRequest(original)
        val pendingId = ++requestGeneration
        val transportGeneration = connectionGeneration
        val timeoutJob = scope.launch {
            delay(requestTimeoutMilliseconds)
            serialized { handleRequestTimeoutLocked(request.responseKind, pendingId, transportGeneration) }
        }
        pendingLanes[request.responseKind] = PendingLane(
            active = PendingRequest(request, transportGeneration, pendingId, timeoutJob),
            queued = ArrayDeque()
        )
        return runCatching { transport.send(request.payload) }.fold(
            onSuccess = { true },
            onFailure = {
                pendingLanes.remove(request.responseKind)?.active?.timeoutJob?.cancel()
                rejectLocked(
                    request.requestType,
                    ERROR_SEND_FAILED,
                    request.targetSessionId,
                    request.correlationId
                )
                false
            }
        )
    }

    private suspend fun completeLaneLocked(responseKind: String) {
        val lane = pendingLanes.remove(responseKind) ?: return
        lane.active.timeoutJob.cancel()
        if (responseKind == "attachment") releasePendingAttachmentFetchLocked()
        val queued = lane.queued.removeFirstOrNull() ?: return
        // 正常响应已经确定 active 请求结束；下一请求必须沿用当前 socket 串行发送。
        // generation 回收只留给 timeout/transport failure，否则 hello 后的重复快照会形成重连闭环。
        if (mutableState.value.connection == GatewayConnectionState.CONNECTED) {
            if (sendAsActiveLocked(queued)) {
                val nextLane = pendingLanes[responseKind]
                while (lane.queued.isNotEmpty()) {
                    nextLane?.queued?.addLast(lane.queued.removeFirst())
                }
            } else {
                while (lane.queued.isNotEmpty()) deferredRequests.addLast(lane.queued.removeFirst())
                recycleConnectionForDeferredLocked()
            }
        } else {
            deferredRequests.addLast(queued)
            while (lane.queued.isNotEmpty()) deferredRequests.addLast(lane.queued.removeFirst())
        }
    }

    private suspend fun openTransportLocked(preserveTurns: Boolean) {
        if (!networkAvailableLocked()) {
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.WAITING_FOR_NETWORK)
            return
        }
        if (isInBackground && !mutableState.value.shouldKeepAliveInBackground) {
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.SUSPENDED)
            return
        }
        if (endpointOrder.isEmpty()) endpointOrder = roundOrderLocked(preferences.load())
        val target = endpointOrder.getOrNull(endpointCursor) ?: return
        val immutablePairingCode = pairingCode
        val credentialSnapshot = runCatching {
            credentials.loadOrCreateDeviceId() to
                if (immutablePairingCode == null) credentials.loadToken() else null
        }.getOrElse {
            failOpenLocked(ERROR_CREDENTIAL_ACCESS)
            return
        }
        val gatewayKey = credentialSnapshot.second?.let { it to null } ?: (null to target)
        if (immutablePairingCode != null || entryHistoryGatewayKey != gatewayKey) {
            entryHistorySessionIds.clear()
            entryHistoryGatewayKey = gatewayKey
        }
        val spec = GatewayConnectionSpec(
            generation = ++connectionGeneration,
            endpoint = target,
            deviceId = credentialSnapshot.first,
            bearerToken = credentialSnapshot.second,
            pairingCode = immutablePairingCode
        )
        cancelPendingLocked(ERROR_CONNECTION_REPLACED)
        if (!preserveTurns) {
            while (deferredRequests.isNotEmpty()) {
                emitCancelledLocked(deferredRequests.removeFirst(), ERROR_CONNECTION_REPLACED)
            }
            clearTurnsLocked()
        }
        mutableState.value = mutableState.value.copy(
            connection = GatewayConnectionState.CONNECTING,
            endpoint = target,
            lastError = null
        )
        endpoint = target
        runCatching { transport.open(spec) }.onFailure { failOpenLocked(ERROR_OPEN_FAILED); return }
        armHandshakeWatchdogLocked()
    }

    /**
     * 地址不可达时（出门后试局域网地址会空发包到黑洞）不能依赖平台 socket 的默认超时，
     * 必须在探测预算内主动放弃并推进到下一个候选地址。
     */
    private fun armHandshakeWatchdogLocked() {
        handshakeJob?.cancel()
        val budget = if (endpointCursor + 1 < endpointOrder.size) {
            endpointProbeTimeoutMilliseconds
        } else {
            finalHandshakeTimeoutMilliseconds
        }
        if (budget <= 0L) return
        val generation = connectionGeneration
        handshakeJob = scope.launch {
            delay(budget)
            serialized { handleHandshakeTimeoutLocked(generation) }
        }
    }

    private suspend fun handleHandshakeTimeoutLocked(generation: Long) {
        handshakeJob = null
        if (generation != connectionGeneration || !desiredConnection) return
        if (mutableState.value.connection == GatewayConnectionState.CONNECTED) return
        connectionGeneration += 1
        runCatching { transport.close() }
        cancelPendingLocked(ERROR_ENDPOINT_UNREACHABLE)
        if (advanceOrRestartRoundLocked()) return
        // 整轮走完：带退避重下一轮（下轮从首个地址重新探测）。
        mutableState.value = mutableState.value.copy(
            connection = if (canReconnectLocked()) GatewayConnectionState.CONNECTING else GatewayConnectionState.FAILED,
            lastError = ERROR_ENDPOINT_UNREACHABLE
        )
        if (canReconnectLocked()) scheduleReconnectLocked(immediate = false)
    }

    /**
     * 还有备用地址就立即换下一个试（不计入退避次数）；整轮走完则回到队首，
     * 返回 false 交给调用方的退避重连。地址不会因连接成功而重排，否则出门连上
     * tailnet 后就再也不会重探局域网地址。
     *
     * 备用入口是同一轮连接恢复的一部分（出门 LAN→Tailscale）：切换时保留活动回合与
     * deferred 请求（与回收重用同一 preserveTurns 路径），并在仍有待保护回合时从这里
     * 就武装有界恢复窗口——否则首次切换提前返回既不清窗口也不武装，备用入口握手
     * 黑洞就会绕过窗口无限等待，备用也失败时还因 turn 已丢无法继续恢复。
     */
    private suspend fun advanceOrRestartRoundLocked(): Boolean {
        if (endpointCursor + 1 >= endpointOrder.size) {
            endpointCursor = 0
            return false
        }
        endpointCursor += 1
        mutableState.value = mutableState.value.copy(
            connection = GatewayConnectionState.CONNECTING,
            lastError = null
        )
        openTransportLocked(preserveTurns = true)
        if (mutableState.value.shouldKeepAliveInBackground) startRecoveryDeadlineLocked()
        return true
    }

    private suspend fun handleTransportStateLocked(transportState: GatewayTransportState) {
        if (transportState.generation != 0L && transportState.generation != connectionGeneration) return
        when (transportState) {
            is GatewayTransportState.Closed -> Unit
            is GatewayTransportState.Opening -> mutableState.value = mutableState.value.copy(
                connection = GatewayConnectionState.CONNECTING
            )
            is GatewayTransportState.Open -> mutableState.value = mutableState.value.copy(
                connection = GatewayConnectionState.AUTHENTICATING
            )
            is GatewayTransportState.Failed -> handleTransportFailureLocked(transportState)
        }
    }

    private suspend fun handleTransportFailureLocked(failure: GatewayTransportState.Failed) {
        // failure 与 frame 共用有序通道；处理 failure 时立即作废代次，随后到达的旧帧只能被拒绝。
        connectionGeneration += 1
        handshakeJob?.cancel()
        handshakeJob = null
        val credentialRejected = failure.httpStatus == 401 || failure.closeCode == 4003
        // 备用入口可能已指向别的宿主；它的401不能证明首入口的凭据无效。
        // 保留凭据，按已有退避回到首入口；首入口401与明确撤销4003仍停止重连。
        val retryPrimary = failure.httpStatus == 401 && pairingCode == null && endpointCursor > 0
        if (retryPrimary) endpointCursor = 0
        // 一次性配对码无效/已被用过 ≠ 长期凭据失效：作废 pairingCode，改回 token 继续，
        // 不能因此把 App 永久钉死在 authentication-required。
        val pairingRejected = credentialRejected && pairingCode != null
        if (pairingRejected) {
            pairingCode = null
            if (runCatching { credentials.loadToken() }.getOrNull() == null) reconnectBlocked = true
        } else if (!retryPrimary) {
            if (failure.httpStatus == 401) runCatching { credentials.deleteToken() }
            if (credentialRejected || !failure.recoverable) reconnectBlocked = true
        }
        val error = when {
            retryPrimary -> ERROR_TRANSPORT_FAILED
            pairingRejected -> ERROR_PAIRING_REJECTED
            else -> failure.stableErrorCode()
        }
        cancelPendingLocked(error)
        // 切备用地址在这里提前 return；回合保留与恢复窗口武装都在 advance 内部完成。
        if (!credentialRejected && advanceOrRestartRoundLocked()) return
        val shouldReconnect = canReconnectLocked()
        if (!shouldReconnect) clearTurnsLocked() else startRecoveryDeadlineLocked()
        mutableState.value = mutableState.value.copy(
            connection = if (shouldReconnect) GatewayConnectionState.CONNECTING else GatewayConnectionState.FAILED,
            lastError = error
        )
        if (shouldReconnect) scheduleReconnectLocked(immediate = pairingRejected)
    }

    private suspend fun handleIncomingFrameLocked(transportFrame: GatewayTransportFrame) {
        if (transportFrame.generation != connectionGeneration) {
            rejectLocked("transport", ERROR_STALE_FRAME)
            return
        }
        val frame = runCatching { frameDecoder(transportFrame.text) }.getOrElse {
            rejectLocked("decode", ERROR_DECODE_FAILED)
            return
        }
        if (frame.kind == "paired") {
            val token = frame.token
            if (token.isNullOrBlank()) {
                rejectLocked("pair", ERROR_PAIR_TOKEN_MISSING)
                return
            }
            if (runCatching { credentials.saveToken(token) }.isFailure) {
                rejectLocked("pair", ERROR_CREDENTIAL_ACCESS)
                return
            }
            entryHistoryGatewayKey = token to null
            pairingCode = null
            endpointOrder = roundOrderLocked(preferences.load())
            endpointCursor = 0
        }
        if (frame.kind == "hello") {
            handshakeJob?.cancel()
            handshakeJob = null
            recoveryDeadlineJob?.cancel()
            recoveryDeadlineJob = null
            mutableState.value = mutableState.value.copy(
                connection = GatewayConnectionState.CONNECTED,
                serverPort = frame.port,
                clientCount = frame.clients,
                protocolVersion = frame.protocolVersion ?: 1,
                capabilities = frame.capabilities.orEmpty().toSet(),
                reconnectAttempt = 0,
                lastError = null
            )
            if (!hasPromptDisposition) {
                observedRunningSessions.clear()
                observedCompactingSessions.clear()
                publishTurnStateLocked()
            }
            replayAfterHelloLocked()
        }
        // attachment 应答/错误帧由 runtime 自行消费（严格三元校验 + 终态释放），
        // 不进 correlateLocked 主流程，也不作为 Frame 事件入 UI 队列。
        if (frame.kind == "attachment") {
            handleAttachmentFrameLocked(frame)
            return
        }
        if (frame.kind == "error" && frame.requestType == "attachment") {
            handleAttachmentErrorFrameLocked(frame)
            return
        }

        val correlation = correlateLocked(frame)
        if (!correlation.accepted) return
        if (frame.kind == "error") {
            rejectLocked(
                frame.requestType ?: "gateway",
                frame.code ?: ERROR_GATEWAY_REQUEST,
                correlation.sessionId,
                responseCorrelation(frame),
                serverResponse = true,
            )
            suspendBackgroundConnectionIfIdleLocked()
            return
        }
        if (frame.kind == "sent" && unassociatedTurnCount > 0 && !frame.sessionId.isNullOrBlank()) {
            unassociatedTurnCount -= 1
            activeTurnCountsBySession[frame.sessionId] =
                activeTurnCountsBySession.getOrElse(frame.sessionId) { 0 } + 1
            publishTurnStateLocked()
        }
        if (frame.kind == "sent" && hasPromptDisposition && frame.isStreaming != null && frame.isCompacting != null) {
            // ACK只释放这次输入的乐观等待，不以handled推断整个会话已结束。
            releaseTurnLocked(frame.sessionId)
            updateObservedActivity(frame.sessionId, frame.isStreaming, frame.isCompacting)
        }
        val activitySnapshot = hasPromptDisposition && frame.isStreaming != null && frame.isCompacting != null &&
            frame.kind in setOf("history", "state", "model-set", "branch-switched", "session-restarted", "compact-started", "compact-finished", "session-named")
        if (activitySnapshot) {
            // 重连期间run可能已经落定；权威补拉不能只修UI而留下后台保活假运行。
            updateObservedActivity(frame.sessionId, frame.isStreaming == true, frame.isCompacting == true)
        }
        val nativeType = if (frame.kind == "event") frame.event?.get("type")?.stringValue else null
        val endedLastBackgroundTurn = nativeType == "agent_settled"
        if (hasPromptDisposition) frame.sessionId?.let { sessionId ->
            when (nativeType) {
                "agent_start" -> observedRunningSessions.add(sessionId)
                "agent_settled" -> { observedRunningSessions.remove(sessionId); observedCompactingSessions.remove(sessionId) }
                "compaction_start" -> observedCompactingSessions.add(sessionId)
                "compaction_end" -> observedCompactingSessions.remove(sessionId)
            }
            publishTurnStateLocked()
        }
        // 旧网关没有输入处置快照，沿用既有计数；新协议settled不能消费尚未ACK的输入。
        if (endedLastBackgroundTurn && !hasPromptDisposition) releaseTurnLocked(frame.sessionId)
        // aborted 已与当前请求关联且证明会话 idle；取消的排队消息不会逐条发 settled。
        if (frame.kind == "aborted" && frame.queued != null && frame.isStreaming == false && frame.isCompacting == false) {
            correlation.sessionId?.let {
                activeTurnCountsBySession.remove(it)
                observedRunningSessions.remove(it)
                observedCompactingSessions.remove(it)
            }
            publishTurnStateLocked()
        }
        // dequeue回执在网关清队列的同步段发出；保留当前run，取消的排队项不再settled。
        if (frame.kind == "dequeued" && frame.queued != null && frame.queuedMessages != null &&
            frame.isStreaming != null && frame.isCompacting != null) {
            correlation.sessionId?.let { sessionId ->
                if (hasPromptDisposition) updateObservedActivity(sessionId, frame.isStreaming, frame.isCompacting)
                else if (frame.isStreaming || frame.isCompacting) activeTurnCountsBySession[sessionId] = 1
                else activeTurnCountsBySession.remove(sessionId)
            }
            publishTurnStateLocked()
        }
        val safeFrame = when (frame.kind) {
            "paired" -> frame.copy(token = null)
            else -> frame
        }
        val rawWasCleaned = frame.kind == "paired"
        val safeRaw = if (rawWasCleaned) wireJson.encodeToString(safeFrame) else transportFrame.text
        val estimatedBytes = if (rawWasCleaned) {
            safeRaw.length.toLong() * 2 + FRAME_OBJECT_OVERHEAD_BYTES
        } else {
            transportFrame.byteCount.toLong() * FRAME_RETENTION_MULTIPLIER + FRAME_OBJECT_OVERHEAD_BYTES
        }
        val normalizedEvents = if (frame.kind == "event") {
            val sessionId = frame.sessionId
            val seq = frame.seq
            val time = frame.time
            val payload = frame.event
            if (sessionId.isNullOrBlank() || seq == null || time == null || payload == null) {
                emptyList()
            } else {
                runCatching {
                    val normalizer = eventNormalizers.getOrPut(sessionId) { PiEventNormalizer(sessionId) }
                    normalizer.normalize(seq, time, payload, frame.entryId)
                }.getOrElse { emptyList() }
            }
        } else {
            emptyList()
        }
        emitEventLocked(
            GatewayRuntimeEvent.Frame(safeRaw, safeFrame, correlation.sessionId, normalizedEvents,
                gatewayRevision = mutableState.value.gatewayRevision),
            estimatedBytes
        )
        if (activitySnapshot || endedLastBackgroundTurn || nativeType == "compaction_end" || frame.kind in setOf("sent", "aborted", "dequeued")) suspendBackgroundConnectionIfIdleLocked()
    }

    private suspend fun correlateLocked(frame: GatewayFrame): Correlation {
        if (frame.kind == "compact-finished") {
            if (frame.sessionId.isNullOrBlank() || frame.isStreaming == null || frame.isCompacting == null) {
                rejectLocked("transport", "invalid-compact-state", frame.sessionId)
                return Correlation(false, null)
            }
            return Correlation(true, frame.sessionId)
        }
        if (frame.kind in UNCORRELATED_KINDS) {
            if (frame.kind == "pong") {
                val pending = pendingLanes["pong"]?.active?.request ?: return Correlation(false, null)
                if (pending.correlationId != null && frame.pingId != pending.correlationId) {
                    return Correlation(false, null)
                }
                completeLaneLocked(frame.kind)
            }
            return Correlation(true, frame.sessionId)
        }
        if (frame.kind == "error") {
            val requestType = frame.requestType
            if (requestType == null) {
                cancelPendingLocked(ERROR_GATEWAY_REQUEST, releaseMessageTurns = true)
                return Correlation(true, frame.sessionId)
            }
            val entry = pendingLanes.entries.firstOrNull { it.value.active.request.requestType == requestType }
            if (entry == null) {
                rejectLocked("gateway", ERROR_NO_ACTIVE_REQUEST, frame.sessionId)
                return Correlation(false, frame.sessionId)
            }
            val request = entry.value.active.request
            if (!sessionMatches(request, frame.sessionId)) {
                rejectLocked(
                    "transport",
                    ERROR_SESSION_MISMATCH,
                    frame.sessionId,
                    responseCorrelation(frame)
                )
                return Correlation(false, null)
            }
            if (request.correlationId != null) {
                val explicitCorrelation = explicitResponseCorrelation(request, frame)
                if (explicitCorrelation == null) {
                cancelLaneLocked(entry.key, ERROR_GATEWAY_REQUEST)
                    return Correlation(false, request.targetSessionId)
                }
                if (request.correlationId != explicitCorrelation) {
                    rejectLocked(
                        "transport",
                        ERROR_SESSION_MISMATCH,
                        frame.sessionId,
                        explicitCorrelation
                    )
                    return Correlation(false, null)
                }
            }
            if (requestType == "history" && frame.code == "history-turn-too-large" &&
                request.targetSessionId != null && frame.sessionId == request.targetSessionId &&
                entry.value.active.generation == connectionGeneration &&
                "turnLimit" in wireJson.parseToJsonElement(request.payload).jsonObject) {
                entryHistorySessionIds.add(request.targetSessionId)
                val lane = entry.value
                if (lane.queued.lastOrNull()?.targetSessionId?.let { it != request.targetSessionId } == true) {
                    // 用户已切去另一个会话：先运行最新请求，不复活旧会话的加载。
                    emitCancelledLocked(request, ERROR_COALESCED)
                    completeLaneLocked(entry.key)
                } else {
                    lane.active.timeoutJob.cancel()
                    if (sendAsActiveLocked(request)) {
                        pendingLanes[entry.key]?.queued?.addAll(lane.queued)
                    } else if (lane.queued.isNotEmpty()) {
                        deferredRequests.addAll(lane.queued)
                        recycleConnectionForDeferredLocked()
                    }
                }
                // 中间失败由一次entry请求接续，不发布会取消投影loading的拒绝事件。
                return Correlation(false, request.targetSessionId)
            }
            if (requestType == "message") releaseTurnLocked(request.targetSessionId)
            completeLaneLocked(entry.key)
            return Correlation(true, frame.sessionId ?: request.targetSessionId)
        }
        // sent 按协议必须带真实会话。缺失时不能消费当前发送或绑定新会话。
        if (frame.kind == "sent" && frame.sessionId.isNullOrBlank()) {
            rejectLocked("transport", ERROR_SESSION_MISMATCH)
            return Correlation(false, null)
        }
        if (frame.kind == "sent" && hasPromptDisposition && (frame.isStreaming == null || frame.isCompacting == null)) {
            // 无效帧不消费输入lane，后续合法ACK仍可认领；不直接拆连接。
            rejectLocked("transport", "invalid-message-ack", frame.sessionId)
            return Correlation(false, null)
        }
        val lane = pendingLanes[frame.kind]
        if (lane == null) {
            if (frame.kind in RESPONSE_KINDS_REQUIRING_ACTIVE_REQUEST) {
                rejectLocked(
                    frame.kind,
                    ERROR_NO_ACTIVE_REQUEST,
                    frame.sessionId,
                    responseCorrelation(frame)
                )
                return Correlation(false, null)
            }
            return Correlation(true, frame.sessionId)
        }
        if (
            lane.active.generation != connectionGeneration ||
            !sessionMatches(lane.active.request, frame.sessionId) ||
            !responseCorrelationMatches(lane.active.request, frame)
        ) {
            rejectLocked(
                "transport",
                ERROR_SESSION_MISMATCH,
                frame.sessionId,
                responseCorrelation(frame)
            )
            return Correlation(false, null)
        }
        val sessionId = frame.sessionId ?: lane.active.request.targetSessionId
        completeLaneLocked(frame.kind)
        return Correlation(true, sessionId)
    }

    private fun sessionMatches(request: GatewayRequest, explicitSessionId: String?): Boolean =
        explicitSessionId == null || request.targetSessionId == null || explicitSessionId == request.targetSessionId

    /**
     * attachment 成功应答（PROTOCOL §4.22）：session+entryId+imageIndex 三元严格
     * 匹配在途请求才认领；载荷过 mime/长度/base64/声明字节校验，违规即失败终态。
     * 帧（含大 base64）不进 UI 事件队列，消费即释放。错配帧（迟到/别的请求）
     * 静默丢弃，不影响当前请求。
     */
    private suspend fun handleAttachmentFrameLocked(frame: GatewayFrame) {
        val fetch = pendingAttachmentFetch ?: return
        val lane = pendingLanes["attachment"] ?: return
        if (!attachmentFrameMatches(lane, fetch, frame)) return
        val bytes = validateAttachmentReply(frame)
        pendingAttachmentFetch = null
        fetch.deferred.complete(bytes)
        completeLaneLocked("attachment")
    }

    /**
     * attachment 错误帧（§4.22：requestType=attachment 且完整三元回显）：三元
     * 匹配在途请求才视为本请求的服务端错误（真实终态，lane 释放、等待返回
     * null）；错配的错误帧（迟到/别的请求）静默丢弃，不影响当前请求。
     */
    private suspend fun handleAttachmentErrorFrameLocked(frame: GatewayFrame) {
        val fetch = pendingAttachmentFetch ?: return
        val lane = pendingLanes["attachment"] ?: return
        if (!attachmentFrameMatches(lane, fetch, frame)) return
        cancelLaneLocked("attachment", ERROR_GATEWAY_REQUEST)
    }

    private fun attachmentFrameMatches(
        lane: PendingLane,
        fetch: PendingAttachmentFetch,
        frame: GatewayFrame
    ): Boolean =
        lane.active.generation == connectionGeneration &&
            lane.active.request.targetSessionId == fetch.sessionId &&
            frame.sessionId == fetch.sessionId &&
            frame.entryId == fetch.entryId &&
            frame.imageIndex == fetch.imageIndex

    /** 载荷校验（对齐网关 §4.22 预算）：有界 image/ mime、base64 长度与可解码、
     * 解码字节 ≤ 4 MiB、声明 bytes 与实际相等。违规返回 null（失败终态）。 */
    private fun validateAttachmentReply(frame: GatewayFrame): ByteArray? {
        val mediaType = frame.mediaType.orEmpty()
        if (
            mediaType.isEmpty() ||
            mediaType.length > ATTACHMENT_MEDIA_TYPE_MAX_CHARS ||
            !ATTACHMENT_MEDIA_TYPE_PATTERN.matches(mediaType)
        ) {
            return null
        }
        val dataBase64 = frame.dataBase64.orEmpty()
        if (dataBase64.isEmpty() || dataBase64.length > ATTACHMENT_MAX_BASE64_CHARS) return null
        val bytes = decodeBase64(dataBase64) ?: return null
        if (bytes.size > ATTACHMENT_MAX_RAW_BYTES) return null
        if (frame.bytes == null || frame.bytes != bytes.size) return null
        return bytes
    }

    private fun responseCorrelationMatches(request: GatewayRequest, frame: GatewayFrame): Boolean = when (request.requestType) {
        "worktree-list" -> frame.queryId == request.correlationId && frame.sessionId == request.targetSessionId
        "worktree-create", "worktree-continue", "worktree-status", "worktree-trust" -> frame.operationId == request.correlationId
        "entry-copy", "label-put" -> frame.entryId == request.correlationId && frame.sessionId == request.targetSessionId
        "dequeue" -> frame.queueRequestId == request.correlationId && frame.sessionId == request.targetSessionId &&
            frame.queued != null && frame.queuedMessages != null && frame.isStreaming != null && frame.isCompacting != null
        "model-scope-put" -> frame.scopeRequestId == request.correlationId
        "provider-test" -> request.correlationId == null || frame.providerTestId == request.correlationId
        in INPUT_ASSIST_REQUEST_TYPES -> frame.queryId == request.correlationId && frame.sessionId == request.targetSessionId
        else -> true
    }

    private fun responseCorrelation(frame: GatewayFrame): String? =
        frame.operationId ?: frame.queueRequestId ?: frame.queryId ?: frame.scopeRequestId ?: frame.providerTestId

    private fun explicitResponseCorrelation(request: GatewayRequest, frame: GatewayFrame): String? = when (request.requestType) {
        "worktree-list" -> frame.queryId
        "worktree-create", "worktree-continue", "worktree-status", "worktree-trust" -> frame.operationId
        "entry-copy", "label-put" -> frame.entryId
        "dequeue" -> frame.queueRequestId
        "model-scope-put" -> frame.scopeRequestId
        "provider-test" -> frame.providerTestId
        in INPUT_ASSIST_REQUEST_TYPES -> frame.queryId
        else -> null
    }

    private fun outgoingImagesAreWithinLimits(images: List<GatewayOutgoingImage>): Boolean {
        if (images.size > MAXIMUM_OUTGOING_IMAGE_COUNT) return false
        var totalCharacters = 0L
        images.forEach { image ->
            if (!image.mimeType.startsWith("image/") || image.base64Data.length > MAXIMUM_OUTGOING_IMAGE_CHARACTERS) {
                return false
            }
            totalCharacters += image.base64Data.length
            if (totalCharacters > MAXIMUM_OUTGOING_TOTAL_BASE64_CHARACTERS) return false
        }
        return true
    }

    private suspend fun handleNetworkLocked(network: GatewayNetworkState) {
        val available = network == GatewayNetworkState.AVAILABLE
        val wasAvailable = mutableState.value.networkAvailable
        mutableState.value = mutableState.value.copy(networkAvailable = available)
        if (!available) {
            reconnectJob?.cancel()
            connectionGeneration += 1
            runCatching { transport.close() }
            cancelPendingLocked(ERROR_NETWORK_LOST)
            startRecoveryDeadlineLocked()
            if (desiredConnection && !reconnectBlocked) {
                mutableState.value = mutableState.value.copy(
                    connection = GatewayConnectionState.WAITING_FOR_NETWORK,
                    lastError = null
                )
            }
        } else if (!wasAvailable && canReconnectLocked()) {
            // 网络切换（蜂窝↔家用 Wi-Fi）后重走一轮，回家时才能重新命上局域网地址。
            endpointCursor = 0
            scheduleReconnectLocked(immediate = true)
        }
    }

    private suspend fun handleCollectorFailureLocked(code: String) {
        connectionGeneration += 1
        clearConnectionWorkLocked()
        mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.FAILED, lastError = code)
        rejectLocked("transport", code)
    }

    private suspend fun failOpenLocked(code: String) {
        connectionGeneration += 1
        runCatching { transport.close() }
        clearConnectionWorkLocked()
        mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.FAILED, lastError = code)
    }

    private suspend fun clearConnectionWorkLocked() {
        eventNormalizers.clear()
        cancelPendingLocked(ERROR_CONNECTION_CLOSED)
        while (deferredRequests.isNotEmpty()) {
            emitCancelledLocked(deferredRequests.removeFirst(), ERROR_CONNECTION_CLOSED)
        }
        clearTurnsLocked()
    }

    private suspend fun cancelPendingLocked(reason: String, releaseMessageTurns: Boolean = false) {
        pendingLanes.values.forEach { lane ->
            lane.active.timeoutJob.cancel()
            if (releaseMessageTurns && lane.active.request.requestType == "message") {
                releaseTurnLocked(lane.active.request.targetSessionId)
            }
            emitCancelledLocked(lane.active.request, reason)
            while (lane.queued.isNotEmpty()) emitCancelledLocked(lane.queued.removeFirst(), reason)
        }
        pendingLanes.clear()
        // 断连/回收/后台挂起时 attachment 等待者同步终态，避免残留挂起。
        releasePendingAttachmentFetchLocked()
    }

    private suspend fun cancelLaneLocked(responseKind: String, reason: String) {
        val lane = pendingLanes.remove(responseKind) ?: return
        lane.active.timeoutJob.cancel()
        if (lane.active.request.requestType == "message") {
            releaseTurnLocked(lane.active.request.targetSessionId)
        }
        emitCancelledLocked(lane.active.request, reason)
        while (lane.queued.isNotEmpty()) emitCancelledLocked(lane.queued.removeFirst(), reason)
        if (responseKind == "attachment") releasePendingAttachmentFetchLocked()
    }

    /** attachment 等待者统一失败终态：deferred 置 null（幂等），slot 清空。 */
    private fun releasePendingAttachmentFetchLocked() {
        val fetch = pendingAttachmentFetch ?: return
        pendingAttachmentFetch = null
        fetch.deferred.complete(null)
    }

    private fun clearTurnsLocked() {
        observedRunningSessions.clear()
        observedCompactingSessions.clear()
        activeTurnCountsBySession.clear()
        unassociatedTurnCount = 0
        publishTurnStateLocked()
    }

    private suspend fun handleRequestTimeoutLocked(
        responseKind: String,
        pendingId: Long,
        transportGeneration: Long
    ) {
        val lane = pendingLanes[responseKind] ?: return
        if (lane.active.id != pendingId || lane.active.generation != transportGeneration) return
        pendingLanes.remove(responseKind)
        // 当前正在执行的 timeout job 不得取消自身，否则下一处挂起点会中止终态发布。
        if (lane.active.request.requestType == "message") {
            releaseTurnLocked(lane.active.request.targetSessionId)
        }
        while (lane.queued.isNotEmpty()) deferredRequests.addLast(lane.queued.removeFirst())
        if (responseKind == "attachment" || lane.active.request.requestType in INPUT_ASSIST_REQUEST_TYPES ||
            (lane.active.request.requestType == "provider-test" && lane.active.request.correlationId != null)
        ) {
            // §4.22 超时只结束自己：attachment 是只读请求且响应严格三元校验，迟到的
            // 错图帧本就可忽略，同一 Pi 条目的图不可变，不需要靠整连接回收消除无
            // 关联小请求的歧义；连接回收会连带误取消在途 message/history 与 turn
            // （4 MiB 图弱网下可达 30s 超时，误伤面大）。也不做后台挂起检查：那会
            // 清掉其它 lane，超出“只结束自己”的范围。先完成等待者终态再发事件，
            // 避免事件队列背压推迟 caller 恢复。
            // 输入辅助也有严格 queryId，超时只结束该查询，不能切断正在生成的回复。
            // 带独立标识的 Provider 探针同理；无标识的旧请求仍回收连接消除歧义。
            if (responseKind == "attachment") releasePendingAttachmentFetchLocked()
            emitEventLocked(
                GatewayRuntimeEvent.RequestTimedOut(
                    lane.active.request.requestType,
                    lane.active.request.targetSessionId,
                    lane.active.request.correlationId
                )
            )
            return
        }
        emitEventLocked(
            GatewayRuntimeEvent.RequestTimedOut(
                lane.active.request.requestType,
                lane.active.request.targetSessionId,
                lane.active.request.correlationId
            )
        )
        suspendBackgroundConnectionIfIdleLocked()
        if (mutableState.value.connection != GatewayConnectionState.SUSPENDED) {
            recycleConnectionForDeferredLocked()
        }
    }

    private suspend fun recycleConnectionForDeferredLocked() {
        connectionGeneration += 1
        // 连接已拆：没等到 agent_settled 的 message turn 在此释放，否则会话永久“运行中”。
        // 消息若实际已达网关，后续 agent_settled 会再释一次（计数钳制，双释无害）。
        // 已知时序窗口（计数制无法区分归属）：旧消息未 ACK 即 recycle、旧 agent_settled
        // 在新消息发出后才到达时，会提前吃掉新消息的 turn，会话短暂显示空闲直到新 settled。
        // 需三重巧合（30s 超时+ACK 丢失+同会话快速续发），接受为残余风险，不做代次跟踪。
        cancelPendingLocked(ERROR_CONNECTION_RECYCLED, releaseMessageTurns = true)
        runCatching { transport.close() }
        if (canReconnectLocked()) {
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.CONNECTING)
            openTransportLocked(preserveTurns = true)
        }
    }

    private fun startRecoveryDeadlineLocked() {
        if (!mutableState.value.shouldKeepAliveInBackground || recoveryDeadlineJob?.isActive == true) return
        recoveryDeadlineJob = scope.launch {
            delay(recoveryWindowMilliseconds)
            serialized {
                if (mutableState.value.connection != GatewayConnectionState.CONNECTED) {
                    reconnectJob?.cancel()
                    reconnectBlocked = true
                    connectionGeneration += 1
                    cancelPendingLocked(ERROR_RECOVERY_TIMEOUT)
                    while (deferredRequests.isNotEmpty()) {
                        emitCancelledLocked(deferredRequests.removeFirst(), ERROR_RECOVERY_TIMEOUT)
                    }
                    runCatching { transport.close() }
                    clearTurnsLocked()
                    mutableState.value = mutableState.value.copy(
                        connection = GatewayConnectionState.FAILED,
                        lastError = ERROR_RECOVERY_TIMEOUT
                    )
                }
                recoveryDeadlineJob = null
            }
        }
    }

    private suspend fun emitCancelledLocked(request: GatewayRequest, reason: String) {
        emitEventLocked(
            GatewayRuntimeEvent.RequestCancelled(
                request.requestType,
                request.targetSessionId,
                reason,
                request.correlationId
            )
        )
    }

    private fun markTurnLocked(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            unassociatedTurnCount += 1
        } else {
            activeTurnCountsBySession[sessionId] = activeTurnCountsBySession.getOrElse(sessionId) { 0 } + 1
        }
        publishTurnStateLocked()
    }

    private fun releaseTurnLocked(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            unassociatedTurnCount = (unassociatedTurnCount - 1).coerceAtLeast(0)
        } else {
            val remaining = activeTurnCountsBySession.getOrElse(sessionId) { 0 } - 1
            if (remaining > 0) activeTurnCountsBySession[sessionId] = remaining
            else activeTurnCountsBySession.remove(sessionId)
        }
        publishTurnStateLocked()
    }

    private fun updateObservedActivity(sessionId: String?, streaming: Boolean, compacting: Boolean) {
        if (sessionId == null) return
        if (streaming) observedRunningSessions.add(sessionId) else observedRunningSessions.remove(sessionId)
        if (compacting) observedCompactingSessions.add(sessionId) else observedCompactingSessions.remove(sessionId)
        publishTurnStateLocked()
    }

    private fun publishTurnStateLocked() {
        mutableState.value = mutableState.value.copy(
            activeTurnSessionIds = activeTurnCountsBySession.keys + observedRunningSessions + observedCompactingSessions,
            hasUnassociatedTurn = unassociatedTurnCount > 0
        )
    }

    private fun networkAvailableLocked(): Boolean =
        mutableState.value.networkAvailable && networkMonitor.state.value == GatewayNetworkState.AVAILABLE

    private fun canReconnectLocked(): Boolean =
        desiredConnection && !reconnectBlocked && networkAvailableLocked() &&
            (!isInBackground || mutableState.value.shouldKeepAliveInBackground)

    private fun scheduleReconnectLocked(immediate: Boolean) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (!immediate) {
                val attempt = serialized {
                    val next = (mutableState.value.reconnectAttempt + 1).coerceAtMost(6)
                    mutableState.value = mutableState.value.copy(reconnectAttempt = next)
                    next
                }
                clock.delay((2_000L shl (attempt - 1)).coerceAtMost(30_000L))
            }
            serialized { if (canReconnectLocked()) openTransportLocked(preserveTurns = true) }
        }
    }

    private suspend fun rejectLocked(
        requestType: String,
        code: String,
        targetSessionId: String? = null,
        correlationId: String? = null,
        serverResponse: Boolean = false,
    ) {
        emitEventLocked(
            GatewayRuntimeEvent.RequestRejected(
                requestType,
                code,
                targetSessionId,
                correlationId,
                serverResponse,
            )
        )
    }

    private suspend fun replayAfterHelloLocked() {
        val replay = buildList {
            while (deferredRequests.isNotEmpty()) add(deferredRequests.removeFirst())
        }
        val replayKinds = replay.mapTo(mutableSetOf(), GatewayRequest::responseKind)
        if ("sessions" !in replayKinds) sendRequestLocked(GatewayRequests.sessions())
        if ("subscribed" !in replayKinds) {
            subscribedSessionId?.let { sendRequestLocked(GatewayRequests.subscribe(it)) }
        }
        replay.forEach { sendRequestLocked(it) }
    }

    private suspend fun suspendBackgroundConnectionIfIdleLocked() {
        if (!isInBackground || mutableState.value.shouldKeepAliveInBackground) return
        connectionGeneration += 1
        reconnectJob?.cancel()
        reconnectJob = null
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        cancelPendingLocked(ERROR_BACKGROUND_SUSPENDED)
        while (deferredRequests.isNotEmpty()) {
            emitCancelledLocked(deferredRequests.removeFirst(), ERROR_BACKGROUND_SUSPENDED)
        }
        runCatching { transport.close() }
        mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.SUSPENDED)
    }

    private suspend fun emitEventLocked(event: GatewayRuntimeEvent, estimatedBytes: Long = 256L) {
        eventQueue.emit(event, estimatedBytes)
    }

    private fun <T> collectSafely(flow: Flow<T>, code: String, handler: suspend (T) -> Unit) {
        scope.launch {
            try {
                flow.collect { value ->
                    try {
                        serialized { handler(value) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        serialized { handleCollectorFailureLocked(code) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                serialized { handleCollectorFailureLocked(code) }
            }
        }
    }

    private suspend fun <T> serialized(block: suspend () -> T): T {
        serialization.lock()
        return try {
            block()
        } finally {
            serialization.unlock()
        }
    }

    private data class PendingRequest(
        val request: GatewayRequest,
        val generation: Long,
        val id: Long,
        val timeoutJob: Job
    )
    private data class PendingLane(
        val active: PendingRequest,
        val queued: ArrayDeque<GatewayRequest>
    )

    /** §4.22 在途取图：单一 slot（lane REJECT_IF_BUSY 保证互斥），deferred 承载终态。 */
    private data class PendingAttachmentFetch(
        val deferred: CompletableDeferred<ByteArray?>,
        val sessionId: String,
        val entryId: String,
        val imageIndex: Int
    )
    private data class Correlation(val accepted: Boolean, val sessionId: String?)

    private fun GatewayTransportState.Failed.stableErrorCode(): String = when {
        httpStatus == 401 -> ERROR_AUTH_REQUIRED
        httpStatus == 503 -> ERROR_GATEWAY_UNAVAILABLE
        closeCode == 4003 -> ERROR_AUTH_REQUIRED
        closeCode == 4004 -> ERROR_GATEWAY_DISABLED
        else -> reason?.takeIf { it in STABLE_TRANSPORT_CODES } ?: ERROR_TRANSPORT_FAILED
    }

    companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MILLISECONDS = 30_000L
        private const val DEFAULT_RECOVERY_WINDOW_MILLISECONDS = 60_000L
        private const val DEFAULT_ENDPOINT_PROBE_TIMEOUT_MILLISECONDS = 2_500L
        private const val DEFAULT_FINAL_HANDSHAKE_TIMEOUT_MILLISECONDS = 10_000L
        /** 与 AndroidGatewayPreferences.DEFAULT_ENDPOINT 同值；用于识别“从没配过”的默认值。 */
        private const val LEGACY_DEFAULT_ENDPOINT = "ws://127.0.0.1:3080/ws/mobile"
        private const val MAXIMUM_ATTACHMENT_BYTES = 8 * 1_024 * 1_024
        private const val MAXIMUM_OUTGOING_IMAGE_COUNT = 4
        private const val MAXIMUM_OUTGOING_IMAGE_CHARACTERS = 4_893_356
        private const val MAXIMUM_OUTGOING_TOTAL_BASE64_CHARACTERS = 16L * 1_024 * 1_024
        private const val MAXIMUM_RUNTIME_EVENT_COUNT = 8
        private const val MAXIMUM_RUNTIME_EVENT_BYTES = 48L * 1_024 * 1_024
        private const val FRAME_RETENTION_MULTIPLIER = 3L
        private const val FRAME_OBJECT_OVERHEAD_BYTES = 4_096L
        private const val ERROR_NOT_CONNECTED = "not-connected"
        private const val ERROR_SEND_FAILED = "send-failed"
        private const val ERROR_OPEN_FAILED = "open-failed"
        private const val ERROR_DECODE_FAILED = "decode-failed"
        private const val ERROR_STALE_FRAME = "stale-frame"
        private const val ERROR_NO_ACTIVE_REQUEST = "no-active-request"
        private const val ERROR_SESSION_MISMATCH = "session-mismatch"
        private const val ERROR_PAIR_TOKEN_MISSING = "pair-token-missing"
        private const val ERROR_CREDENTIAL_ACCESS = "credential-access-failed"
        private const val ERROR_ATTACHMENT_INVALID = "attachment-invalid"
        private const val ERROR_ATTACHMENT_CACHE_WRITE = "attachment-cache-write-failed"
        private const val ERROR_INCOMING_FLOW = "incoming-flow-failed"
        private const val ERROR_NETWORK_FLOW = "network-flow-failed"
        private const val ERROR_AUTH_REQUIRED = "authentication-required"
        private const val ERROR_PAIRING_REJECTED = "pairing-code-invalid"
        private const val ERROR_ENDPOINT_UNREACHABLE = "endpoint-unreachable"
        private const val ERROR_GATEWAY_UNAVAILABLE = "gateway-unavailable"
        private const val ERROR_GATEWAY_DISABLED = "gateway-disabled"
        private const val ERROR_TRANSPORT_FAILED = "transport-failed"
        private const val ERROR_GATEWAY_REQUEST = "gateway-request-failed"
        private const val ERROR_IMAGE_LIMITS = "image-limits-exceeded"
        private const val ERROR_REQUEST_BUSY = "request-busy"
        private const val ERROR_CALLER_CANCELLED = "caller-cancelled"

        /** §4.22 载荷预算（对齐网关 ATTACHMENT_MAX_*）。 */
        private const val ATTACHMENT_MAX_RAW_BYTES = 4 * 1024 * 1024
        private const val ATTACHMENT_MAX_BASE64_CHARS = 5_592_408
        private const val ATTACHMENT_MEDIA_TYPE_MAX_CHARS = 100
        private val ATTACHMENT_MEDIA_TYPE_PATTERN = Regex("image/[a-z0-9.+-]+$", RegexOption.IGNORE_CASE)
        private const val ERROR_COALESCED = "request-coalesced"
        private const val ERROR_CONNECTION_REPLACED = "connection-replaced"
        private const val ERROR_CONNECTION_CLOSED = "connection-closed"
        private const val ERROR_CONNECTION_RECYCLED = "connection-recycled"
        private const val ERROR_BACKGROUND_SUSPENDED = "background-suspended"
        private const val ERROR_NETWORK_LOST = "network-lost"
        private const val ERROR_RECOVERY_TIMEOUT = "recovery-timeout"
        private val STABLE_TRANSPORT_CODES = setOf(
            "incoming-overflow",
            "incoming-message-too-large",
            "websocket-failure"
        )
        private val UNCORRELATED_KINDS = setOf(
            "event", "hello", "paired", "pong",
            // extension-status 是服务端主动推送的扩展状态全量快照，没有请求关联。
            // 显式声明无关联，避免以后收紧响应关联时把它误判成 no-active-request。
            "extension-status"
        )
        private val RESPONSE_KINDS_REQUIRING_ACTIVE_REQUEST = setOf(
            "history", "sent", "subscribed", "unsubscribed", "state", "aborted", "dequeued", "sessions", "model-scope-saved",
            "input-resources", "input-completions", "entry-copy", "label-put", "provider-test-result"
        )
        private val INPUT_ASSIST_REQUEST_TYPES = setOf("input-resources", "input-completions")
        private val IDEMPOTENT_CONNECTION_STATES = setOf(
            GatewayConnectionState.CONNECTING,
            GatewayConnectionState.AUTHENTICATING,
            GatewayConnectionState.CONNECTED,
            GatewayConnectionState.WAITING_FOR_NETWORK
        )
    }
}

/** 附件缓存键（网关历史图片出站预算裁剪后，客户端仍可能缓存完整附件）。 */
fun gatewayAttachmentCacheKey(sessionId: String, attachmentId: String): String =
    "${sessionId.length}:$sessionId:$attachmentId"
