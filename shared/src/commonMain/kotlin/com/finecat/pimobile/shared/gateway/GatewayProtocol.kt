package com.finecat.pimobile.shared.gateway

import com.finecat.pimobile.shared.protocol.GatewayTreeView
import com.finecat.pimobile.shared.protocol.GatewayModelSelection
import com.finecat.pimobile.shared.protocol.GatewayPairingPayload
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GatewayPairingPayloadException(message: String) : IllegalArgumentException(message)

/** 与 pi-mobile-gateway PROTOCOL.md §2 相同的 version 1、严格 Base64URL 和过期语义。 */
object GatewayPairingPayloadParser {
    fun parse(rawValue: String, nowEpochMilliseconds: Long): GatewayPairingPayload {
        val normalized = rawValue.trim()
        val decoded = decodeBase64Url(normalized)
            ?: throw GatewayPairingPayloadException("配对内容不是有效的 Base64URL 字符串")
        val payload = runCatching {
            wireJson.decodeFromString<GatewayPairingPayload>(decoded.decodeToString())
        }.getOrElse {
            throw GatewayPairingPayloadException("Base64URL 解码后的内容不是有效的配对 JSON")
        }
        if (payload.version != 1) {
            throw GatewayPairingPayloadException("不支持的配对协议版本 ${payload.version}")
        }
        requireWebSocketEndpoint(payload.publicUrl)
        if (
            payload.pairingCode.isEmpty() ||
            ',' in payload.pairingCode ||
            payload.pairingCode.any { it.isWhitespace() || it.isISOControl() }
        ) {
            throw GatewayPairingPayloadException("二维码中的一次性 pairingCode 无效")
        }
        if (payload.expiresAt <= nowEpochMilliseconds) {
            throw GatewayPairingPayloadException("二维码配对码已经过期")
        }
        return payload
    }
}

/**
 * 发件图片附件；wire 序列化与 pi `ImageContent` 一致（`{type:"image", data, mimeType}`），
 * 网关免转译直接透传。`name` 不上 wire：pi 不消费，避免私有字段滞留协议。
 */
data class GatewayOutgoingImage(
    val mimeType: String,
    val base64Data: String,
    /** 发送端生成的附件 id：随帧上行，回显时网关按序注入 image 块（display-only）。 */
    val attachmentId: String = ""
) {
    override fun toString(): String =
        "GatewayOutgoingImage(mimeType=$mimeType, base64Data=<redacted>)"
}

enum class GatewayRequestLanePolicy { COALESCE_LATEST, FIFO, REJECT_IF_BUSY }

/** pi 思考档位全集（pi-ai EXTENDED_THINKING_LEVELS 顺序）。 */
val PI_THINKING_LEVELS: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/** 手机表单支持的 API 协议全集（网关不做枚举限制，这里约束常见口径）。 */
val PROVIDER_API_CHOICES: List<String> =
    listOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai")

/** 新建模型的默认协议；pi 的解析顺序是 model.api > provider.api > 内置默认。 */
const val PROVIDER_DEFAULT_API = "openai-completions"

/** compat 手机可编辑的布尔键（与网关 COMPAT_BOOLEAN_KEYS 对齐）。 */
val PROVIDER_COMPAT_BOOLEAN_KEYS: Set<String> = setOf(
    "supportsStore",
    "supportsDeveloperRole",
    "supportsReasoningEffort",
    "supportsUsageInStreaming",
    "supportsFinishReason",
    "requiresToolResultName",
    "requiresAssistantAfterToolResult",
    "requiresThinkingAsText",
    "requiresReasoningContentOnAssistantMessages",
    "zaiToolStream",
    "supportsThinkingTokenBudget",
    "supportsOpenAIGrammarTools",
    "supportsStrictMode",
    "supportsStrictTools",
    "supportsTemperature",
    "supportsCacheControlOnTools",
    "supportsEagerToolInputStreaming",
    "supportsLongCacheRetention",
    "sendSessionAffinityHeaders",
    "supportsAdditionalTools",
    "supportsToolSearch",
    "supportsExplicitPromptCacheMode",
    "supportsToolReferences",
    "forceAdaptiveThinking",
    "allowEmptySignature"
)

/** compat 枚举键 → 合法取值（与网关 COMPAT_ENUMS 对齐）。 */
val PROVIDER_COMPAT_ENUMS: Map<String, List<String>> = mapOf(
    "maxTokensField" to listOf("max_completion_tokens", "max_tokens"),
    "thinkingFormat" to listOf(
        "openai",
        "openrouter",
        "deepseek",
        "together",
        "baseten",
        "zai",
        "qwen",
        "chat-template",
        "qwen-chat-template",
        "string-thinking",
        "ant-ling"
    ),
    "thinkingTokenBudgetField" to listOf("thinking_token_budget", "thinking_budget", "thinking_budget_tokens"),
    "cacheControlFormat" to listOf("anthropic"),
    "deferredToolsMode" to listOf("kimi"),
    "sessionAffinityFormat" to listOf("openai", "openai-nosession", "openrouter")
)

/** 每协议 compat 键集（表单展示顺序：常用调优键置顶）；google-generative-ai 无 compat。 */
val PROVIDER_COMPAT_KEYS_BY_API: Map<String, List<String>> = mapOf(
    "openai-completions" to listOf(
        "supportsDeveloperRole",
        "supportsReasoningEffort",
        "thinkingFormat",
        "requiresReasoningContentOnAssistantMessages",
        "supportsStore",
        "maxTokensField",
        "requiresThinkingAsText",
        "requiresAssistantAfterToolResult",
        "requiresToolResultName",
        "thinkingTokenBudgetField",
        "supportsThinkingTokenBudget",
        "supportsUsageInStreaming",
        "supportsFinishReason",
        "supportsStrictMode",
        "supportsOpenAIGrammarTools",
        "sendSessionAffinityHeaders",
        "supportsLongCacheRetention",
        "zaiToolStream",
        "cacheControlFormat",
        "deferredToolsMode",
        "sessionAffinityFormat"
    ),
    "openai-responses" to listOf(
        "supportsDeveloperRole",
        "supportsStrictMode",
        "supportsOpenAIGrammarTools",
        "supportsAdditionalTools",
        "supportsToolSearch",
        "supportsExplicitPromptCacheMode",
        "sessionAffinityFormat",
        "supportsLongCacheRetention"
    ),
    "anthropic-messages" to listOf(
        "forceAdaptiveThinking",
        "allowEmptySignature",
        "supportsTemperature",
        "supportsStrictTools",
        "supportsCacheControlOnTools",
        "supportsEagerToolInputStreaming",
        "supportsToolReferences",
        "supportsLongCacheRetention",
        "sendSessionAffinityHeaders"
    )
)

/**
 * provider-put 的单模型 upsert；`thinkingLevelMap` 是 pi 档位 → provider
 * 实参的映射（value=null 表示该档位不支持），缺省档位沿用 provider 默认。
 * `compat`（非 null 即权威覆盖）：布尔键值写 "true"/"false"，枚举键写原值，
 * value=null 表示恢复默认（该键不写入）。
 */
data class ProviderModelUpsert(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val reasoning: Boolean = true,
    /** 支持图片输入（input 数组写 ["text","image"]，否则 ["text"]）。 */
    val supportsImages: Boolean = false,
    /** 模型级 wire 协议：pi 运行时按它分派流实现，所以每个模型必须自带。 */
    val api: String = PROVIDER_DEFAULT_API,
    val thinkingLevelMap: Map<String, String?> = emptyMap(),
    val compat: Map<String, String?>? = null,
    val baseUrl: String? = null,
    /** false omits the field; true + null explicitly restores provider inheritance. */
    val baseUrlEdited: Boolean = false,
) {
    override fun toString(): String = "ProviderModelUpsert(id=$id, api=$api, baseUrl=<redacted>)"
}

/** 网关 normalizeProviderUpsert 对 id/name/api 协议名共用的长度上限（MAXIMUM_ID_LENGTH）。 */
private const val MAXIMUM_PROVIDER_TEXT_LENGTH = 128

/** 表单提交前的本地校验；消息与网关校验口径一致。 */
fun requireValidProviderUpsert(
    id: String,
    baseUrl: String?,
    apiKey: String?,
    models: List<ProviderModelUpsert>,
    name: String? = null,
) {
    require(id.isNotBlank()) { "Provider ID 不能为空" }
    require("/" !in id) { "Provider ID 不能包含 /" }
    // 与网关 normalizeProviderUpsert 同口径（provider name 过长 → bad-request）。
    name?.let {
        require(it.length <= MAXIMUM_PROVIDER_TEXT_LENGTH) { "provider name 过长" }
    }
    require(!baseUrl.isNullOrBlank() || !apiKey.isNullOrBlank()) { "baseUrl 与 API Key 至少填写其一" }
    baseUrl?.takeIf { it.isNotBlank() }?.let {
        require(it.startsWith("http://") || it.startsWith("https://")) { "baseUrl 必须是 http(s) 地址" }
    }
    // 空 models 合法：网关/pi 允许仅凭据的 provider，模型可后补。
    models.forEach { model ->
        require(model.id.isNotBlank()) { "模型 ID 不能为空" }
        require(model.api.isNotBlank()) { "每个模型都要选协议（模型级 api 是 pi 的分派依据）" }
        if (model.baseUrlEdited) model.baseUrl?.let {
            require(it.startsWith("http://") || it.startsWith("https://")) { "${model.id}: Base URL 必须是 http(s) 地址" }
        }
        require(model.contextWindow == null || model.contextWindow > 0) { "上下文窗口必须是正整数" }
        require(model.maxTokens == null || model.maxTokens > 0) { "最大输出必须是正整数" }
        model.compat?.forEach { (key, value) ->
            when {
                key in PROVIDER_COMPAT_BOOLEAN_KEYS ->
                    require(value == null || value == "true" || value == "false") {
                        "compat.$key 只能是 true/false/默认"
                    }
                key in PROVIDER_COMPAT_ENUMS ->
                    require(value == null || value in PROVIDER_COMPAT_ENUMS.getValue(key)) {
                        "compat.$key 取值无效（${PROVIDER_COMPAT_ENUMS.getValue(key).joinToString("/")}）"
                    }
                else -> require(value == null) { "compat.$key 不支持在手机端设置" }
            }
        }
    }
}

data class GatewayRequest(
    val requestType: String,
    val responseKind: String,
    val targetSessionId: String? = null,
    val correlationId: String? = null,
    val lanePolicy: GatewayRequestLanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST,
    val payload: String,
    /** 桥接写目标实例；只用于回执关联，不额外写入 payload。 */
    val targetInstanceId: String? = null,
    /** 桥接写目标代际；只用于回执关联，不额外写入 payload。 */
    val targetEpoch: String? = null,
    /** 首页创建原生会话，身份必须先由新会话快照建立。 */
    val createsNativeSession: Boolean = false,
    /** 已有离线会话按需启动，仍绑定原SID，身份从启动后的快照建立。 */
    val resumesNativeSession: Boolean = false,
    /** 原生模式无运行器的只读查询，回执必须显式回显 nativeRead。 */
    val nativeReadOnly: Boolean = false,
    /** 本地自动补拉标记：只影响错误展示，不进入 wire，也不改变重试/确认语义。 */
    val automaticRefresh: Boolean = false
) {
    override fun toString(): String =
        "GatewayRequest(requestType=$requestType, responseKind=$responseKind, " +
            "targetSessionId=$targetSessionId, correlationId=<redacted>, " +
            "lanePolicy=$lanePolicy, payload=<redacted>)"
}

/**
 * pi-mobile-gateway 业务帧构造（PROTOCOL.md §4）。消息发送固定 `followUp`：
 * pi 在会话空闲时立即执行、流式时排队，单一模式覆盖"随时可发"的聊天语义。
 */
object GatewayRequests {
    /** 只读配置/会话文件；不会为浏览而启动原生Pi。 */
    fun forNativeRead(request: GatewayRequest, id: String): GatewayRequest {
        require(request.requestType in setOf("models", "tree", "entry-copy"))
        require(id.isNotBlank() && id.length <= 128 && request.targetInstanceId == null && request.targetEpoch == null)
        val original = wireJson.parseToJsonElement(request.payload) as JsonObject
        val body = buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put(if (request.requestType == "models") "queryId" else "operationId", id)
            put("nativeRead", true)
        }
        return request.copy(correlationId = id, nativeReadOnly = true, payload = wireJson.encodeToString(body))
    }

    /** 明确写动作才启动原SID；成功仍由快照建立身份后验证ACK。 */
    fun forNativeResume(request: GatewayRequest, operationId: String): GatewayRequest {
        require(request.requestType in setOf("set-model", "label-put", "switch-branch", "name"))
        require(request.targetSessionId != null && operationId.isNotBlank() && operationId.length <= 128 && request.targetInstanceId == null && request.targetEpoch == null)
        val original = wireJson.parseToJsonElement(request.payload) as JsonObject
        val body = buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put("operationId", operationId); put("nativeResume", true)
        }
        return request.copy(correlationId = operationId, resumesNativeSession = true, payload = wireJson.encodeToString(body))
    }

    /** 同一套 Tree/改名请求绑定到原生实例；SDK 请求体和旧关联语义保持不变。 */
    fun forBridgeSession(request: GatewayRequest, operationId: String, instanceId: String, epoch: String): GatewayRequest {
        require(request.requestType in setOf("tree", "entry-copy", "label-put", "switch-branch", "name", "worktree-continue"))
        require(operationId.isNotBlank() && operationId.length <= 128 && instanceId.isNotBlank() && epoch.isNotBlank())
        val original = wireJson.parseToJsonElement(request.payload) as JsonObject
        val bound = buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put("operationId", operationId); put("instanceId", instanceId); put("epoch", epoch)
        }
        return request.copy(correlationId = operationId, targetInstanceId = instanceId, targetEpoch = epoch,
            payload = wireJson.encodeToString(bound))
    }

    /** 原生输入查询沿用 queryId，不能与写操作的 operationId 混用。 */
    fun forBridgeInput(request: GatewayRequest, queryId: String, instanceId: String, epoch: String): GatewayRequest {
        require(request.requestType in setOf("input-resources", "input-completions", "commands", "worktree-list"))
        require(request.targetSessionId != null && queryId.isNotBlank() && queryId.length <= 128)
        require(instanceId.isNotBlank() && epoch.isNotBlank())
        val original = wireJson.parseToJsonElement(request.payload) as JsonObject
        val bound = buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put("queryId", queryId); put("instanceId", instanceId); put("epoch", epoch)
        }
        return request.copy(correlationId = queryId, targetInstanceId = instanceId, targetEpoch = epoch,
            payload = wireJson.encodeToString(bound))
    }

    fun ping(pingId: String? = null): GatewayRequest =
        request("ping", "pong", correlationId = pingId, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY) {
            pingId?.let { put("pingId", it) }
        }

    fun worktreeList(queryId: String, cwd: String? = null, sessionId: String? = null): GatewayRequest = request(
        "worktree-list", "worktree-list", targetSessionId = sessionId, correlationId = queryId,
    ) {
        put("queryId", queryId)
        cwd?.let { put("cwd", it) }
        sessionId?.let { put("sessionId", it) }
    }

    fun worktreeCreate(operationId: String, cwd: String, branch: String, base: String, targetPath: String): GatewayRequest = request(
        "worktree-create", "worktree-operation", correlationId = operationId, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY,
    ) {
        put("operationId", operationId); put("cwd", cwd); put("branch", branch); put("base", base); put("targetPath", targetPath)
    }

    fun worktreeContinue(operationId: String, sessionId: String, targetPath: String, trustProject: Boolean = false, retry: Boolean = false): GatewayRequest = request(
        "worktree-continue", "worktree-operation", targetSessionId = sessionId, correlationId = operationId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY,
    ) {
        put("operationId", operationId); put("sessionId", sessionId); put("targetPath", targetPath)
        if (trustProject) put("trustProject", true)
        if (retry) put("retry", true)
    }

    fun worktreeTrust(operationId: String, targetPath: String, trustProject: Boolean = false): GatewayRequest = request(
        "worktree-trust", "worktree-trust", correlationId = operationId, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY,
    ) { put("operationId", operationId); put("targetPath", targetPath); if (trustProject) put("trustProject", true) }

    fun worktreeStatus(operationId: String): GatewayRequest = request(
        "worktree-status", "worktree-operation", correlationId = operationId, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY,
    ) { put("operationId", operationId) }

    /** 目录浏览（网关自实现能力，与 pi 无关）。 */
    fun directories(path: String? = null): GatewayRequest = request(
        "directories",
        "directories"
    ) {
        path?.takeIf(String::isNotBlank)?.let { put("path", it) }
    }

    /** 可用模型目录（只含已配置认证的模型，PROTOCOL.md §4.13）。 */
    fun models(sessionId: String? = null, queryId: String? = null, instanceId: String? = null, epoch: String? = null): GatewayRequest =
        request("models", "models", targetSessionId = sessionId, correlationId = queryId,
            targetInstanceId = instanceId, targetEpoch = epoch) {
            sessionId?.let { put("sessionId", it) }; queryId?.let { put("queryId", it) }
            instanceId?.let { put("instanceId", it) }; epoch?.let { put("epoch", it) }
        }

    fun modelScope(): GatewayRequest = request("model-scope", "model-scope", lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY)

    fun modelScopePut(
        scopeRequestId: String,
        expectedPatterns: List<String>,
        models: List<GatewayModelSelection>,
        preserveUnmatched: Boolean,
        reset: Boolean,
    ): GatewayRequest = request(
        "model-scope-put", "model-scope-saved", correlationId = scopeRequestId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY,
    ) {
        put("scopeRequestId", scopeRequestId)
        put("expectedPatterns", buildJsonArray { expectedPatterns.forEach { add(JsonPrimitive(it)) } })
        put("models", buildJsonArray {
            models.forEach { model -> add(buildJsonObject {
                put("provider", model.provider)
                put("modelId", model.modelId)
                model.thinkingLevel?.let { put("thinkingLevel", it) }
            }) }
        })
        put("preserveUnmatched", preserveUnmatched)
        put("reset", reset)
    }

    fun sessions(knownSessions: Map<String, String> = emptyMap()): GatewayRequest =
        request("sessions", "sessions") {
            if (knownSessions.isNotEmpty()) put("knownSessions", buildJsonArray {
                knownSessions.forEach { (id, path) -> add(buildJsonObject {
                    put("id", id)
                    put("path", path)
                }) }
            })
        }

    /** 历史图片按需取回（PROTOCOL §4.22）：三元寻址，单 lane REJECT_IF_BUSY
     *  保证同一时刻至多一个在途请求（Runtime 的 fetchAttachment 驱动）。 */
    fun attachment(sessionId: String, entryId: String, imageIndex: Int): GatewayRequest =
        request(
            "attachment",
            "attachment",
            targetSessionId = sessionId,
            lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
        ) {
            put("sessionId", sessionId)
            put("entryId", entryId)
            put("imageIndex", imageIndex)
        }

    fun subscribe(sessionId: String): GatewayRequest = request(
        "subscribe",
        "subscribed",
        sessionId
    ) { put("sessionId", sessionId) }

    fun unsubscribe(sessionId: String): GatewayRequest = request(
        "unsubscribe",
        "unsubscribed",
        sessionId
    ) { put("sessionId", sessionId) }

    fun history(
        sessionId: String,
        beforeEntryId: String? = null,
        limit: Int = 60,
        resetLeaf: Boolean = false,
        turnLimit: Int? = null
    ): GatewayRequest = request(
        "history",
        "history",
        sessionId
    ) {
        put("sessionId", sessionId)
        beforeEntryId?.takeIf(String::isNotBlank)?.let { put("beforeEntryId", it) }
        put("limit", limit)
        turnLimit?.let { put("turnLimit", it) }
        // 重连 catch-up（pi TUI 重开语义——释放手动叶子回文件尾）；翻页绝不带。
        if (resetLeaf) put("resetLeaf", true)
    }

    fun state(sessionId: String): GatewayRequest = request(
        "state",
        "state",
        sessionId
    ) { put("sessionId", sessionId) }

    /** 宿主连接模式（PROTOCOL §4.30）；应答 host-mode 带当前模式。 */
    fun hostMode(): GatewayRequest = request("host-mode", "host-mode")

    /** 切换 sdk/bridge；有运行中或待消费队列时网关回 mode-switch-blocked。 */
    fun hostModePut(mode: String): GatewayRequest = request(
        "host-mode-put",
        "host-mode",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("mode", mode)
    }

    fun abort(
        sessionId: String,
        operationId: String? = null,
        instanceId: String? = null,
        epoch: String? = null
    ): GatewayRequest = request(
        "abort",
        "aborted",
        sessionId,
        correlationId = operationId?.takeIf { instanceId != null && epoch != null },
        targetInstanceId = instanceId,
        targetEpoch = epoch,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("restoreQueued", true)
        operationId?.takeIf(String::isNotBlank)?.let { put("operationId", it) }
        instanceId?.takeIf(String::isNotBlank)?.let { put("instanceId", it) }
        epoch?.takeIf(String::isNotBlank)?.let { put("epoch", it) }
    }

    /** queueRequestId由调用方生成，须非空白且不超过128字符；UI使用UUID。 */
    fun dequeue(sessionId: String, queueRequestId: String): GatewayRequest = request(
        "dequeue", "dequeued", sessionId,
        correlationId = queueRequestId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY,
    ) {
        require(queueRequestId.isNotBlank() && queueRequestId.length <= 128)
        put("sessionId", sessionId)
        put("queueRequestId", queueRequestId)
    }

    /** 查询桥接写操作是否已被原生实例受理/淘汰；status=unknown 不等于未执行。 */
    fun bridgeStart(sessionId: String, operationId: String): GatewayRequest = request(
        "bridge-start",
        "bridge-started",
        sessionId,
        correlationId = operationId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        require(operationId.isNotBlank() && operationId.length <= 128)
        put("sessionId", sessionId)
        put("operationId", operationId)
    }

    fun operationStatus(
        sessionId: String,
        operationId: String,
        instanceId: String? = null,
        epoch: String? = null
    ): GatewayRequest = request(
        "operation-status",
        "operation-status",
        sessionId,
        correlationId = operationId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        require(operationId.isNotBlank() && operationId.length <= 128)
        put("sessionId", sessionId)
        put("operationId", operationId)
        // 收据只在原 instance/epoch 上有效；旧界面迟到查询不能落给新实例。
        instanceId?.takeIf(String::isNotBlank)?.let { put("instanceId", it) }
        epoch?.takeIf(String::isNotBlank)?.let { put("epoch", it) }
    }

    fun message(
        text: String,
        images: List<GatewayOutgoingImage>,
        sessionId: String?,
        cwd: String? = null,
        /** 新会话的模型预选：随帧携带，pi 在首轮前应用（避免默认模型先跑一轮）。 */
        model: GatewayModelSelection? = null,
        /** pi streamingBehavior：空闲时任意（直接跑）；忙时 steer=注入本轮、followUp=排队下轮。 */
        mode: String = "followUp",
        /** 稳定操作 ID：断线重试复用同一个，网关/原生实例据此去重。 */
        operationId: String? = null,
        /** 桥接写入目标实例/代际；旧网关忽略，新网关据此拒绝旧界面的迟到写入。 */
        instanceId: String? = null,
        epoch: String? = null,
        nativeNew: Boolean = false,
        nativeResume: Boolean = false
    ): GatewayRequest = request(
        "message",
        "sent",
        sessionId,
        correlationId = operationId?.takeIf { nativeNew || nativeResume || (instanceId != null && epoch != null) },
        targetInstanceId = instanceId,
        targetEpoch = epoch,
        createsNativeSession = nativeNew,
        resumesNativeSession = nativeResume,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        require(!nativeNew || (sessionId == null && !cwd.isNullOrBlank() && !operationId.isNullOrBlank() &&
            instanceId == null && epoch == null))
        require(!nativeResume || (!sessionId.isNullOrBlank() && cwd == null && model == null && !nativeNew &&
            !operationId.isNullOrBlank() && instanceId == null && epoch == null))
        sessionId?.takeIf(String::isNotBlank)?.let { put("sessionId", it) }
        cwd?.takeIf(String::isNotBlank)?.let { put("cwd", it) }
        operationId?.takeIf(String::isNotBlank)?.let { put("operationId", it) }
        instanceId?.takeIf(String::isNotBlank)?.let { put("instanceId", it) }
        epoch?.takeIf(String::isNotBlank)?.let { put("epoch", it) }
        if (nativeNew) put("nativeNew", true)
        if (nativeResume) put("nativeResume", true)
        put("text", text)
        put("mode", mode)
        if (model != null) {
            put("model", buildJsonObject {
                put("provider", model.provider)
                put("modelId", model.modelId)
                model.thinkingLevel?.takeIf(String::isNotBlank)?.let { put("thinkingLevel", it) }
            })
        }
        if (images.isNotEmpty()) {
            put("images", buildJsonArray {
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image")
                        put("data", image.base64Data)
                        put("mimeType", image.mimeType)
                        image.attachmentId.takeIf(String::isNotBlank)?.let { put("attachmentId", it) }
                    })
                }
            })
        }
    }

    fun createDirectory(path: String, name: String): GatewayRequest = request(
        "directory-create",
        "directory-create",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("path", path)
        put("name", name)
    }

    /** 切换会话模型（会话级，pi `setModel(model, {persist:false})`，PROTOCOL.md §4.14）。 */
    fun refreshNativeModel(sessionId: String, operationId: String, instanceId: String, epoch: String): GatewayRequest = request(
        "refresh-model", "model-refreshed", targetSessionId = sessionId, correlationId = operationId,
        targetInstanceId = instanceId, targetEpoch = epoch, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId); put("operationId", operationId); put("instanceId", instanceId); put("epoch", epoch)
    }

    fun setModel(
        sessionId: String,
        provider: String,
        modelId: String,
        thinkingLevel: String?,
        operationId: String? = null,
        instanceId: String? = null,
        epoch: String? = null
    ): GatewayRequest = request(
        "set-model",
        "model-set",
        sessionId,
        correlationId = operationId,
        targetInstanceId = instanceId,
        targetEpoch = epoch,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("provider", provider)
        put("modelId", modelId)
        thinkingLevel?.takeIf(String::isNotBlank)?.let { put("thinkingLevel", it) }
        operationId?.let { put("operationId", it) }; instanceId?.let { put("instanceId", it) }; epoch?.let { put("epoch", it) }
    }

    /** 会话改名（pi `setSessionName`，PROTOCOL.md §4.15）。 */
    fun setSessionName(sessionId: String, name: String): GatewayRequest = request(
        "name",
        "session-named",
        sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("name", name)
    }

    /** 会话分支树（pi `sessionManager.getTree()` 投影，PROTOCOL.md §4.18）。 */
    fun tree(sessionId: String, view: GatewayTreeView? = null): GatewayRequest = request(
        "tree",
        "tree",
        sessionId
    ) {
        put("sessionId", sessionId)
        view?.let { options ->
            put("view", buildJsonObject {
                put("filter", options.filter)
                put("search", options.search)
                put("foldedIds", JsonArray(options.foldedIds.map(::JsonPrimitive)))
            })
        }
    }

    /** 切换分支（pi `navigateTree(entryId, {summarize:false})`，PROTOCOL.md §4.19）。
     *  响应 `branch-switched` 带 alreadyAt/editorText/queued 与 state 快照。 */
    fun switchBranch(sessionId: String, entryId: String): GatewayRequest = request(
        "switch-branch",
        "branch-switched",
        sessionId
    ) {
        put("sessionId", sessionId)
        put("entryId", entryId)
    }

    /** 整条 entry 的可复制全文（pi TUI Ctrl+X，PROTOCOL.md §4.26）。
     *  响应 `entry-copy` 带 text，或超限时只带 oversized+bytes（不截断充全文）；
     *  无可复制文本回 `entry-copy-empty` 错误帧。离散用户动作：忙时明确拒绝，
     *  不静默合并掉上一次未完成的取文。 */
    fun entryCopy(sessionId: String, entryId: String): GatewayRequest = request(
        "entry-copy",
        "entry-copy",
        sessionId,
        correlationId = entryId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("entryId", entryId)
    }

    /** 标签写（pi TUI Shift+L → `appendLabelChange`，PROTOCOL.md §4.27）。
     *  `label` 为 null / 空白 = 清除标签（Pi LabelInput 语义）；网关只追加
     *  LabelEntry，targetId 指向 entryId，最后记录生效，并会推进运行时叶子。
     *  需网关侧 live 会话（先 subscribe），否则 `session-not-found`。 */
    fun labelPut(sessionId: String, entryId: String, label: String?): GatewayRequest = request(
        "label-put",
        "label-put",
        sessionId,
        correlationId = entryId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("entryId", entryId)
        // 写操作不得静默丢：忙时由 Runtime 回 request-busy，客户端提示重试。
        if (label == null) put("label", JsonNull) else put("label", label)
    }

    /** 自定义命令清单（三源聚合，PROTOCOL §4.21）。
     *  响应 `commands` 带 commands 数组；执行复用 message 原文透传。
     *  清单会话级：restart 后须重拉（见 §4.21）。 */
    fun commands(sessionId: String): GatewayRequest = request(
        "commands",
        "commands",
        sessionId
    ) { put("sessionId", sessionId) }

    fun inputResources(queryId: String, sessionId: String? = null, cwd: String? = null): GatewayRequest =
        inputAssist("input-resources", queryId, sessionId, cwd)

    fun inputCompletions(queryId: String, token: String, sessionId: String? = null, cwd: String? = null): GatewayRequest {
        require(token.length <= 2048)
        return inputAssist("input-completions", queryId, sessionId, cwd, token)
    }

    private fun inputAssist(type: String, queryId: String, sessionId: String?, cwd: String?, token: String? = null): GatewayRequest {
        require(queryId.isNotBlank() && queryId.length <= 128)
        require((sessionId != null) xor (cwd != null))
        require(sessionId == null || sessionId.isNotBlank())
        require(cwd == null || cwd.startsWith('/'))
        return request(type, type, sessionId, correlationId = queryId) {
            put("queryId", queryId)
            sessionId?.let { put("sessionId", it) }
            cwd?.let { put("cwd", it) }
            token?.let { put("token", it) }
        }
    }

    /** 重载配置（重建会话，对齐 pi 重开语义，PROTOCOL.md §4.20）。
     *  响应 `session-restarted` 带 state 快照；运行中被网关拒绝（session-restart-busy）。 */
    fun restartSession(sessionId: String): GatewayRequest = request(
        "restart-session",
        "session-restarted",
        sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
    }

    /** 手动压缩上下文（pi `session.compact()`，PROTOCOL.md §4.16）。
     *  触发即回 `compact-started`；完成/失败经事件流 compaction_end 透传。 */
    fun compact(
        sessionId: String,
        operationId: String? = null,
        instanceId: String? = null,
        epoch: String? = null
    ): GatewayRequest = request(
        "compact",
        "compact-started",
        sessionId,
        correlationId = operationId?.takeIf { instanceId != null && epoch != null },
        targetInstanceId = instanceId,
        targetEpoch = epoch,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        operationId?.let { put("operationId", it) }
        instanceId?.let { put("instanceId", it) }
        epoch?.let { put("epoch", it) }
    }

    // -- provider 管理（pi models.json，PROTOCOL.md §4.17） ---------------------

    /** 读取 pi models.json 的全部 provider（凭证盲）。 */
    fun providers(): GatewayRequest = request(
        "providers",
        "providers",
        // 列表类刷新：重连重放与回前台会重复发，只保留最新一份（REJECT_IF_BUSY 会把
        // 一次重复刷新提升成用户可见的整屏错误）。
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    )

    /**
     * 新增/整体替换一个 provider（PROTOCOL.md §4.17）。表单契约：
     * 协议 `api`、`baseUrl`、`apiKey`、模型 `id`，可选 `contextWindow`、
     * `maxTokens`、`reasoning` 与思考强度映射（pi 档位 → provider 实参，
     * `null` 表示该档位不支持）。
     */
    /**
     * provider 连接测试：用 pi 的方式真发一条最小对话请求，把“能不能用”折算成结论。
     * 字段缺省时网关回退 models.json 已存值（编辑场景不要求重填 apiKey）；
     * 不填 id 则只测不落状态（表单预览）。
     */
    /**
     * 拉取上游 `/models` 清单。**走网关**而不是客户端直连：手机是凭证盲的，
     * 保存过的 provider 其 apiKey 只在宿主 models.json 里，编辑时表单为空——
     * 直连必然 401（用户实测：测试连接能过、拉取模型报 401）。网关按"表单值
     * 优先、缺省回退已存值"处理，用户不必为拉一次列表重新输入 Key。
     */
    fun providerModels(
        id: String? = null,
        baseUrl: String? = null,
        apiKey: String? = null,
        api: String? = null
    ): GatewayRequest = request(
        "provider-models",
        "provider-models-result",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        id?.takeIf(String::isNotBlank)?.let { put("id", it) }
        baseUrl?.takeIf(String::isNotBlank)?.let { put("baseUrl", it) }
        apiKey?.takeIf(String::isNotBlank)?.let { put("apiKey", it) }
        // 与 provider-test 同理：新建时网关没有已存值可回退，协议必传，否则
        // anthropic/google 的合法配置会按 openai 口径拉取而失败。
        api?.takeIf(String::isNotBlank)?.let { put("api", it) }
    }

    fun providerTest(
        id: String? = null,
        baseUrl: String? = null,
        apiKey: String? = null,
        model: String? = null,
        api: String? = null,
        modelBaseUrl: String? = null,
        modelBaseUrlSpecified: Boolean = false,
        providerTestId: String? = null,
    ): GatewayRequest = request(
        "provider-test",
        "provider-test-result",
        correlationId = providerTestId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        providerTestId?.let { put("providerTestId", it) }
        id?.takeIf(String::isNotBlank)?.let { put("id", it) }
        baseUrl?.takeIf(String::isNotBlank)?.let { put("baseUrl", it) }
        apiKey?.takeIf(String::isNotBlank)?.let { put("apiKey", it) }
        model?.takeIf(String::isNotBlank)?.let { put("model", it) }
        // 协议必传：新建 provider 时网关没有已存值可回退，缺省会按 openai 端点测试，
        // 导致 anthropic/google 协议的合法配置永远测不过（硬门槛下无法保存）。
        api?.takeIf(String::isNotBlank)?.let { put("api", it) }
        if (modelBaseUrlSpecified) put("modelBaseUrl", modelBaseUrl?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun providerPut(
        id: String,
        baseUrl: String?,
        apiKey: String?,
        models: List<ProviderModelUpsert>,
        name: String? = null,
    ): GatewayRequest = request(
        "provider-put",
        "provider-saved",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("id", id)
        // provider 级显示名：网关语义是 absent = 保留原值，所以空白不发送
        // （清空已有名字做不到，与 apiKey 同一限制）。
        name?.takeIf(String::isNotBlank)?.let { put("name", it) }
        baseUrl?.takeIf(String::isNotBlank)?.let { put("baseUrl", it) }
        apiKey?.takeIf(String::isNotBlank)?.let { put("apiKey", it) }
        if (models.isNotEmpty()) {
            put("models", buildJsonArray {
                models.forEach { model ->
                    add(buildJsonObject {
                        put("id", model.id)
                        model.name?.takeIf(String::isNotBlank)?.let { put("name", it) }
                        put("api", model.api)
                        if (model.baseUrlEdited) put("baseUrl", model.baseUrl?.let(::JsonPrimitive) ?: JsonNull)
                        put("input", buildJsonArray {
                            add(JsonPrimitive("text"))
                            if (model.supportsImages) add(JsonPrimitive("image"))
                        })
                        model.contextWindow?.let { put("contextWindow", it) }
                        model.maxTokens?.let { put("maxTokens", it) }
                        put("reasoning", model.reasoning)
                        if (model.thinkingLevelMap.isNotEmpty()) {
                            put("thinkingLevelMap", buildJsonObject {
                                model.thinkingLevelMap.forEach { (level, target) ->
                                    if (target == null) put(level, JsonNull) else put(level, target)
                                }
                            })
                        }
                        model.compat?.let { compat ->
                            put("compat", buildJsonObject {
                                compat.forEach { (key, value) ->
                                    when {
                                        value == null -> {} // 默认：不写入该键
                                        key in PROVIDER_COMPAT_BOOLEAN_KEYS ->
                                            put(key, JsonPrimitive(value == "true"))
                                        else -> put(key, JsonPrimitive(value))
                                    }
                                }
                            })
                        }
                    })
                }
            })
        }
    }

    /** 删除一个 provider（不从 enabledModels 摘除）。 */
    fun providerDelete(id: String): GatewayRequest = request(
        "provider-delete",
        "provider-deleted",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("id", id)
    }

    private fun request(
        type: String,
        responseKind: String,
        targetSessionId: String? = null,
        correlationId: String? = null,
        targetInstanceId: String? = null,
        targetEpoch: String? = null,
        lanePolicy: GatewayRequestLanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST,
        createsNativeSession: Boolean = false,
        resumesNativeSession: Boolean = false,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}
    ): GatewayRequest {
        val json = buildJsonObject {
            put("type", type)
            content()
        }
        return GatewayRequest(
            requestType = type,
            responseKind = responseKind,
            targetSessionId = targetSessionId,
            correlationId = correlationId,
            lanePolicy = lanePolicy,
            payload = wireJson.encodeToString(json),
            targetInstanceId = targetInstanceId,
            targetEpoch = targetEpoch,
            createsNativeSession = createsNativeSession,
            resumesNativeSession = resumesNativeSession
        )
    }
}

internal fun requireWebSocketEndpoint(endpoint: String) {
    if (endpoint.any { it.isWhitespace() || it.isISOControl() }) {
        throw GatewayPairingPayloadException("publicUrl 不是有效的 ws:// 或 wss:// 地址")
    }
    val separator = endpoint.indexOf("://")
    if (separator <= 0) {
        throw GatewayPairingPayloadException("publicUrl 不是有效的 ws:// 或 wss:// 地址")
    }
    val scheme = endpoint.take(separator).lowercase()
    val authority = endpoint.drop(separator + 3).substringBefore('/').substringBefore('?')
    if (scheme !in setOf("ws", "wss") || authority.isBlank() || '@' in authority) {
        throw GatewayPairingPayloadException("publicUrl 不是有效的 ws:// 或 wss:// 地址")
    }
    // 明文 ws 不出私网（与网关 net-guards 同约束）：客户端同步执行，
    // 公网地址必须 wss。全局 cleartext 开关是 LAN 明文必需，不能关。
    if (scheme == "ws" && !isPrivateWebSocketHost(authority.substringBefore(':'))) {
        throw GatewayPairingPayloadException("公网地址必须使用 wss://（明文 ws:// 仅限回环与私网）")
    }
}

/** 回环/私网判定（网关 net-guards 的客户端侧子集）：IPv4 私网段、tailnet CGNAT、
 * IPv6 回环/链路本地/ULA、localhost、单标签主机名（LAN 约定）。 */
internal fun isPrivateWebSocketHost(host: String): Boolean {
    val name = host.trim().trimEnd('.').lowercase()
    if (name.isEmpty()) return false
    if (name == "localhost") return true
    if ('.' !in name && ':' !in name) return true
    if (name.startsWith("[") && name.endsWith("]")) {
        val inner = name.drop(1).dropLast(1)
        if (inner == "::1") return true
        if (inner.startsWith("fe80:") || inner.startsWith("fc") || inner.startsWith("fd")) return true
        return false
    }
    val parts = name.split('.')
    if (parts.size == 4) {
        val octets = parts.map { it.toIntOrNull() }
        if (octets.all { it != null && it in 0..255 }) {
            val (a, b) = octets[0]!! to octets[1]!!
            if (a == 127 || a == 10) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a == 169 && b == 254) return true
            if (a == 100 && b in 64..127) return true
            return false
        }
    }
    return false
}

internal fun decodeBase64(value: String): ByteArray? = decodeBase64Alphabet(value, urlSafe = false)

private fun decodeBase64Url(value: String): ByteArray? {
    if (
        value.isEmpty() ||
        '=' in value ||
        value.length % 4 == 1 ||
        value.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }
    ) {
        return null
    }
    return decodeBase64Alphabet(value, urlSafe = true)
}

private fun decodeBase64Alphabet(value: String, urlSafe: Boolean): ByteArray? {
    val alphabet = if (urlSafe) {
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    } else {
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
    val clean = if (urlSafe) value else value.trimEnd('=')
    if (clean.length % 4 == 1 || clean.any { alphabet.indexOf(it) < 0 }) return null
    val output = ByteArray((clean.length * 6) / 8)
    var accumulator = 0
    var bitCount = 0
    var outputIndex = 0
    clean.forEach { character ->
        accumulator = (accumulator shl 6) or alphabet.indexOf(character)
        bitCount += 6
        if (bitCount >= 8) {
            bitCount -= 8
            output[outputIndex++] = ((accumulator shr bitCount) and 0xff).toByte()
        }
    }
    return output.copyOf(outputIndex)
}
