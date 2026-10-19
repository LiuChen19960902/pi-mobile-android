package com.finecat.pimobile.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 线上帧模型：pi-mobile-gateway 的 pi 原生协议（PROTOCOL.md §4）。
 *
 * - `event` 是 pi 事件 JSON（异构），由 PiEventNormalizer 归一为 [GatewayEvent]
 *   内部视图后再进入 domain/projection/UI。
 * - `messages`/`entryIds`/`oldestEntryId` 是 pi `history` 应答（条目游标分页）。
 * - `model`/`thinkingLevel`/`contextUsage` 是 `state`/`model-set` 帧的会话级模型状态。
 */
@Serializable
data class GatewayFrame(
    val kind: String,
    @SerialName("protocol") val protocolVersion: Int? = null,
    val capabilities: List<String>? = null,
    val authenticated: Boolean? = null,
    val token: String? = null,
    val device: GatewayDevice? = null,
    val port: Int? = null,
    val clients: Int? = null,
    val at: Double? = null,
    val pingId: String? = null,
    val sessionId: String? = null,
    val seq: Int? = null,
    val time: Double? = null,
    val event: JsonValue? = null,
    val code: String? = null,
    val message: String? = null,
    val mode: String? = null,
    val disposition: String? = null,
    val requestType: String? = null,
    val queueRequestId: String? = null,
    // sessions 应答：pi SessionInfo 投影
    val items: List<JsonValue>? = null,
    // history 应答：pi 渲染消息 + 条目游标
    val messages: List<JsonValue>? = null,
    val entryIds: List<String>? = null,
    val hasMore: Boolean? = null,
    val oldestEntryId: String? = null,
    // history 应答：文件视图最新叶子（getLeafId，不受手动叶子影响）——
    // 客户端拿活跃链尾与之比对，落后即提示"跳到最新"。
    val latestEntryId: String? = null,
    // history 请求：重连 catch-up 用（pi TUI 重开语义——释放手动叶子回文件尾）
    val resetLeaf: Boolean? = null,
    val bytes: Int? = null,
    val isStreaming: Boolean? = null,
    val isCompacting: Boolean? = null,
    // state/model-set 应答：pi 会话级模型状态
    val model: GatewayModelRef? = null,
    val thinkingLevel: String? = null,
    val queuedMessages: JsonValue? = null,
    val contextUsage: GatewayContextUsage? = null,
    // worktree-v1：写操作以 UUID 关联，源会话与目标会话分别寻址。
    val operationId: String? = null,
    val action: String? = null,
    val status: String? = null,
    val targetSessionId: String? = null,
    val repoRoot: String? = null,
    val branch: String? = null,
    val suggestDir: String? = null,
    val dirty: Boolean? = null,
    val worktrees: List<GatewayWorktree>? = null,
    val worktree: GatewayWorktree? = null,
    // directories 应答
    val path: String? = null,
    val home: String? = null,
    val crumbs: List<GatewayDirectoryItem>? = null,
    val entries: List<GatewayDirectoryItem>? = null,
    val truncated: Boolean? = null,
    // directory-create 应答
    val created: Boolean? = null,
    // models 应答：pi ModelRuntime.getAvailable() 按 provider 分组
    val groups: List<GatewayModelGroup>? = null,
    val scope: GatewayModelScope? = null,
    val scopeRequestId: String? = null,
    val providerTestId: String? = null,
    // providers 应答：pi models.json 的凭证盲投影
    val providers: List<GatewayProvider>? = null,
    val catalogProviders: List<GatewayCatalogProvider>? = null,
    val catalogError: String? = null,
    // provider-models-result 应答（走网关拉取上游模型清单）
    val ids: List<String>? = null,
    // provider-test-result 应答（provider 连接测试）
    val ok: Boolean? = null,
    val latencyMs: Long? = null,
    val httpStatus: Int? = null,
    val url: String? = null,
    val error: String? = null,
    // provider-test-result：失败归因（config = 需改配置；transient = 上游临时问题）
    val failureKind: String? = null,
    // provider-saved 应答
    val provider: GatewayProvider? = null,
    val admitted: Boolean? = null,
    // provider-saved/provider-deleted 应答：网关触发 pi 重读 models.json 是否成功
    val refreshed: Boolean? = null,
    // provider-deleted 应答：被删除的 provider id
    val id: String? = null,
    // session-named 应答：新会话名（客户端单一数据流刷新列表/标题）
    val name: String? = null,
    // tree 应答：会话分支树（pi getTree 投影，PROTOCOL §4.18）
    val leafId: String? = null,
    val nodes: List<GatewayTreeNode>? = null,
    val selectedId: String? = null,
    val view: GatewayTreeView? = null,
    // branch-switched 应答（PROTOCOL §4.19）。同名字段也是 message_end event 帧
    // 的帧顶层实时身份（PROTOCOL §4.5），与 history.entryIds 同一 pi 条目 id 空间。
    val entryId: String? = null,
    val alreadyAt: Boolean? = null,
    val editorText: String? = null,
    val queued: List<String>? = null,
    // entry-copy 应答（PROTOCOL §4.26，Pi Ctrl+X）：整条 entry 的可复制全文。
    // 超网关 512 KiB 上限时不带 text、只带 oversized=true + bytes，客户端必须
    // 明确报“过大未复制”，不能把截断内容当全文粘给用户。
    val text: String? = null,
    val oversized: Boolean? = null,
    // label-put 应答（PROTOCOL §4.27，Pi Shift+L）：写入后的标签值（null=清除）、
    // 新追加的 LabelEntry id 与写入后的运行时叶子（标签追加会推进叶子）。
    val label: String? = null,
    val labelEntryId: String? = null,
    // commands 应答：自定义命令清单（三源，PROTOCOL §4.21）
    val commands: List<GatewaySlashCommand>? = null,
    // 输入辅助（PROTOCOL §4.24–4.25）：queryId 隔离光标/目录/会话的迟到应答。
    val queryId: String? = null,
    val cwd: String? = null,
    val prefix: String? = null,
    val complete: Boolean? = null,
    val skillCommandsEnabled: Boolean? = null,
    val omitted: List<String>? = null,
    // attachment 应答（PROTOCOL §4.22）：三元寻址 + mediaType/bytes/dataBase64。
    // 仅 fetchAttachment 内部消费，帧本体不进 UI 事件队列（避免大 base64 驻留）。
    val imageIndex: Int? = null,
    val mediaType: String? = null,
    val dataBase64: String? = null,
    // 扩展状态行完整快照（`extension-status` 推送帧，及 subscribed/session-restarted
    // 应答的可选初始化字段）。空数组 = 清除该会话所有状态。
    val statuses: List<GatewayStatusEntry>? = null
) {
    override fun toString(): String =
        "GatewayFrame(kind=$kind, sessionId=$sessionId, seq=$seq, requestType=$requestType, " +
            "token=<redacted>, message=<redacted>)"
}

@Serializable data class GatewayDevice(val id: String, val name: String? = null, val createdAt: Double? = null)

/**
 * Pi 扩展通过公开 `ctx.ui.setStatus(key, text)` 发布的一条状态行。
 * 只是显示提示，不表示任务终态（不能推断 isStreaming）。
 */
@Serializable data class GatewayStatusEntry(val key: String, val text: String)

@Serializable
data class GatewayInputCompletion(
    val label: String,
    val path: String,
    val type: String,
    val insertText: String,
    /** UTF-16 offset，与 Compose TextRange 及 Pi JS 编辑器一致。 */
    val cursorOffset: Int,
    val consumeClosingQuote: Boolean = false,
)

fun GatewayFrame.inputCompletions(): List<GatewayInputCompletion> =
    if (kind != "input-completions") emptyList() else items.orEmpty().mapNotNull {
        runCatching { wireJson.decodeFromJsonElement(GatewayInputCompletion.serializer(), it.toJsonElement()) }.getOrNull()
    }

/**
 * pi 会话分支树节点（网关 getTree 投影，PROTOCOL §4.18）。preview 单行摘要
 * ≤120 字符，message 类只输出正文（角色看 role，不带 `user: ` 前缀）；
 * 被放弃分支也在树里。
 */
@Serializable
data class GatewayTreeView(
    val filter: String = "default",
    val search: String = "",
    val foldedIds: List<String> = emptyList(),
)

@Serializable
data class GatewayTreeNode(
    val id: String,
    /** 最近**可见**祖先的 id（不可见中间条目不会切断树）；根为 null。 */
    val parentId: String? = null,
    /** 树行前缀：缩进 + 穿透竖线 + 分叉字符（pi tree-selector 规则，每级 3 字符）。 */
    val prefix: String = "",
    /** 是否在当前活跃路径上（渲染 `• ` 标记，pi 也是只标行、不标线）。 */
    val onActivePath: Boolean = false,
    val type: String,
    /** message 类型的 role（user/assistant/toolResult/bashExecution…）。 */
    val role: String? = null,
    val preview: String,
    val displayText: String? = null,
    val foldable: Boolean = false,
    val folded: Boolean = false,
    val label: String? = null,
    /** ISO 时间（网关透传 entry.timestamp）。 */
    val timestamp: String? = null,
) {
    val isUser: Boolean get() = role == "user"
}

/**
 * 自定义命令（网关 `commands` 帧三源聚合，PROTOCOL §4.21）。name 不带 `/`，
 * skill 按 pi RPC 口径带 `skill:` 前缀；TUI 内建命令不在此列。
 */
@Serializable
data class GatewaySlashCommand(
    val name: String,
    val description: String? = null,
    /** extension | prompt | skill。 */
    val source: String,
    val scope: String? = null,
    val origin: String? = null,
    /** 仅 source=prompt 有（模板 argument-hint）。 */
    val argumentHint: String? = null,
    /** 仅 source=skill 有（模型不可自主调用，仅显式触发）。 */
    val disableModelInvocation: Boolean? = null,
) {
    val isSkill: Boolean get() = source == "skill"
}

/** pi 配对载荷：严格无 padding Base64URL + JSON，version 1（pi-mobile-gateway PROTOCOL.md §2）。 */
@Serializable
data class GatewayPairingPayload(
    val version: Int,
    val publicUrl: String,
    val pairingCode: String,
    val expiresAt: Double
) {
    override fun toString(): String =
        "GatewayPairingPayload(version=$version, publicUrl=<redacted>, " +
            "pairingCode=<redacted>, expiresAt=$expiresAt)"
}

/** pi `sessions` 应答项（pi SessionInfo 投影，modified 为 ISO-8601 字符串）。 */
@Serializable
data class GatewaySessionSummary(
    val id: String,
    val path: String? = null,
    val cwd: String? = null,
    val name: String? = null,
    val created: String? = null,
    val modified: String? = null,
    val messageCount: Int? = null,
    val firstMessage: String? = null,
    val running: Boolean = false
) {
    /** 会话标题：优先 pi 会话名，其次首条消息截断（空会话为 "(no messages)" 时视为 blank）。 */
    val displayTitle: String?
        get() = name?.takeIf(String::isNotBlank)
            ?: firstMessage?.takeIf { it.isNotBlank() && it != "(no messages)" }?.take(40)

    val blank: Boolean get() = messageCount == 0 || firstMessage == "(no messages)"
}

/** pi 渲染后的历史消息（content 为异构块数组，由投影层解析）。 */
@Serializable
data class GatewayHistoryMessage(
    val role: String,
    val content: JsonValue? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** assistant 失败消息的终止原因；Pi 原生错误重建依赖这两个字段。 */
    val stopReason: String? = null,
    val errorMessage: String? = null,
    @SerialName("customType") val customType: String? = null,
    val entryType: String? = null,
    val display: Boolean? = null,
    /** pi 消息自带的 epoch 毫秒（网关原样透传）；缺省或非正表示时间未知。 */
    val timestamp: Double? = null,
    /** assistant 消息的请求模型（pi message.provider/model 原样透传；轨迹详情标题用）。 */
    val provider: String? = null,
    val model: String? = null,
    /** 历史来源附带元数据；不据插件自选customType推断业务语义。 */
    val details: JsonValue? = null
) {
    val isDisplayable: Boolean get() = display == true
}

// ---------------------------------------------------------------------------
// 内部事件视图（domain/projection/UI 消费的稳定形状；pi 事件经
// PiEventNormalizer 归一到此，词汇与既有下游保持一致）
// ---------------------------------------------------------------------------

@Serializable data class ToolDelta(val id: String? = null, val name: String? = null, val argumentsDelta: String? = null)
@Serializable data class ToolCall(val id: String, val name: String, val arguments: JsonValue? = null)
@Serializable data class FinishInfo(val kind: String? = null)

@Serializable
data class GatewayEvent(
    val type: String,
    val turn: Int? = null,
    val step: Int? = null,
    val text: String? = null,
    val source: String? = null,
    val chunkType: String? = null,
    val tool: ToolDelta? = null,
    val usage: JsonValue? = null,
    val finish: FinishInfo? = null,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val images: List<GatewayImageAttachment>? = null,
    val callId: String? = null,
    val name: String? = null,
    val arguments: JsonValue? = null,
    /** assistant/message 事件的请求模型（pi message.provider 原样透传；轨迹 REQUEST
     *  节点标题用，协议里该字段只存在于 pi 的 assistant 消息对象上）。 */
    val provider: String? = null,
    val model: String? = null,
    val isError: Boolean? = null,
    /** pi assistant message 的 stopReason=error 对应的上游错误正文。 */
    val errorMessage: String? = null,
    val preview: String? = null,
    val reason: String? = null,
    val rootCallId: String? = null,
    val parentCallId: String? = null,
    val subCallId: String? = null,
    val steering: List<String>? = null,
    val followUp: List<String>? = null,
    val raw: JsonValue? = null
) {
    override fun toString(): String =
        "GatewayEvent(type=$type, turn=$turn, step=$step, text=<redacted>, tool=<redacted>, " +
            "reasoning=<redacted>, toolCalls=<redacted>, arguments=<redacted>, " +
            "preview=<redacted>, reason=<redacted>, raw=<redacted>)"
}

@Serializable
data class SessionEvent(
    val sessionId: String,
    val seq: Int,
    val time: Double,
    val event: GatewayEvent,
    /** pi 条目身份（PROTOCOL §4.5）：实时侧为 message_end 帧顶层 entryId，
     *  历史侧为 history.entryIds 平行同源。null = 身份未知（旧网关/匹配失败），
     *  history/live 对消必须保守保留，禁止按内容指纹推断。 */
    val entryId: String? = null
) {
    override fun toString(): String =
        "SessionEvent(sessionId=$sessionId, seq=$seq, time=$time, entryId=$entryId, event=$event)"
}

/**
 * pi 图片块。历史/事件里 base64 超出网关出站预算时被裁为
 * `{dataOmitted:true, dataBytes}` 占位；未超预算时 data 原样随帧到达，
 * 存进 [dataBase64] 供渲染端直接解码（不依赖本地附件缓存命中）。
 */
@Serializable
data class GatewayImageAttachment(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Int,
    /** pi 的 image 块不携带尺寸；发送端已知时回填，否则 0 由渲染端兜底。 */
    val width: Int = 0,
    val height: Int = 0,
    val name: String? = null,
    /** 网关未裁剪时随帧携带的完整 base64（pi 未落盘旧图/跨设备为 null）。 */
    val dataBase64: String? = null,
    /** pi 条目 id（实时=message_end 帧顶层，历史=entryIds[i]）；未知为 null。
     * 供 §4.22 attachment 按需取回寻址，不替代 attachmentId。 */
    val entryId: String? = null,
    /** 该 user 消息 content 中 image 块的 0-based 序号（§4.22 寻址三元之一）。 */
    val imageIndex: Int? = null
) {
    override fun toString(): String =
        "GatewayImageAttachment(attachmentId=$attachmentId, mediaType=$mediaType, bytes=$bytes, " +
            "width=$width, height=$height, name=<redacted>, dataBase64=<redacted>, entryId=$entryId, imageIndex=$imageIndex)"
}

// ---------------------------------------------------------------------------
// 模型目录与会话模型状态（pi 原生形状；模型切换是会话级，见 PROTOCOL.md §4.13/§4.14）
// ---------------------------------------------------------------------------

/** pi 模型引用：provider + 模型 id。 */
@Serializable data class GatewayModelRef(val provider: String, val modelId: String)

/** 会话当前模型与推理档位（pi `model_change`/`thinking_level_change` 条目投影）。 */
@Serializable data class GatewayModelSelection(val provider: String, val modelId: String, val thinkingLevel: String? = null)

/** pi Model 投影；`thinkingLevels` 是该模型实际可用的推理档位。 */
@Serializable data class GatewayModelItem(
    val id: String,
    val name: String,
    val contextWindow: Long = 0,
    val maxTokens: Long = 0,
    val reasoning: Boolean = false,
    val input: List<String> = emptyList(),
    val thinkingLevels: List<String> = emptyList()
)

/** 模型目录按 provider 分组（provider 与组内模型均按 id 排序）。 */
@Serializable data class GatewayModelGroup(val provider: String, val models: List<GatewayModelItem> = emptyList())

/** `models` 应答：只含已配置认证的模型（pi `ModelRuntime.getAvailable()`）。 */
@Serializable data class GatewayModelCatalog(val groups: List<GatewayModelGroup> = emptyList())

// ---------------------------------------------------------------------------
// provider 管理（pi ~/.pi/agent/models.json 读写，PROTOCOL.md §4.15）
// ---------------------------------------------------------------------------

/** pi models.json 单模型条目的线上投影（凭证盲，供 App 表单回显）。 */
@Serializable
data class GatewayProviderModel(
    val id: String,
    val name: String? = null,
    val api: String? = null,
    val reasoning: Boolean = false,
    val input: List<String> = emptyList(),
    val thinkingLevelMap: Map<String, String?>? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    /** pi compat 手机可编辑子集（布尔/字符串枚举；宿主侧嵌套键不上手机）。 */
    val compat: JsonValue? = null,
    /** provider-model-base-url-v1: null inherits the provider URL. */
    val baseUrl: String? = null,
) {
    override fun toString(): String = "GatewayProviderModel(id=$id, api=$api, baseUrl=<redacted>)"
}

/** Full available catalog plus Pi's saved global model scope; contains no credentials. */
@Serializable
data class GatewayModelScope(
    val patterns: List<String> = emptyList(),
    val groups: List<GatewayModelGroup> = emptyList(),
    val selected: List<GatewayModelSelection> = emptyList(),
    val unmatchedPatterns: List<String> = emptyList(),
)

/** Pi generated catalog is separate from the writable provider model. */
@Serializable
data class GatewayCatalogProvider(
    val id: String,
    val source: String = "models-store",
    val readOnly: Boolean = true,
    val models: List<GatewayCatalogModel> = emptyList(),
)

@Serializable
data class GatewayCatalogModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
)

/** models.json 单 provider 条目的线上投影；apiKey 只给 [GatewayProvider.apiKeySet]。 */
@Serializable
data class GatewayProvider(
    val id: String,
    val name: String? = null,
    val baseUrl: String? = null,
    val apiKeySet: Boolean = false,
    val api: String? = null,
    val models: List<GatewayProviderModel> = emptyList(),
    /** 激活状态（provider-test 结论投影）：false = 测过且配置失败，仅作诊断提示；模型菜单按 Pi 的可用范围。
     *  缺省 true（旧网关无此字段 / 从未测试）——无记录一律视为激活。 */
    val active: Boolean = true,
    /** 最近一次连接测试结论；null = 从未测试。 */
    val status: GatewayProviderStatus? = null
) {
    override fun toString(): String =
        "GatewayProvider(id=$id, name=$name, baseUrl=$baseUrl, apiKeySet=$apiKeySet, " +
            "api=$api, models=${models.size}个, active=$active)"
}

/** provider 连接测试结论（网关 `provider-status.json` 投影）。 */
@Serializable data class GatewayProviderStatus(
    val ok: Boolean = false,
    val testedAt: String? = null,
    /** 可直接展示失败原因（如“该地址返回的是网页而不是 API 响应…”）。 */
    val error: String? = null
)

/** `providers` 应答。 */
@Serializable data class GatewayProviderList(val providers: List<GatewayProvider> = emptyList())

/** `provider-saved` 应答；`admitted=false` 表示 enabledModels 追加失败，
 * `refreshed=false` 表示网关触发 pi 重读 models.json 未完全成功（两者都不影响保存本身）。
 * 注：实际帧解析走 `GatewayFrame`（`GatewayWireDecoder`），此类供协议文档与测试引用。 */
@Serializable data class GatewayProviderSaved(
    val provider: GatewayProvider,
    val admitted: Boolean = false,
    val refreshed: Boolean = true
)


/** pi `state.contextUsage` 投影。 */
@Serializable data class GatewayContextUsage(
    val percent: Double? = null,
    val contextWindow: Long? = null,
    val tokens: Long? = null
)

/** `state`/`model-set` 帧的会话级状态快照（成功应答即一次 state）。 */
/** pi 的排队消息快照（state 帧实时携带，steer/followUp 消费后变空）。 */
@Serializable
data class GatewayQueuedMessages(
    val steering: List<String> = emptyList(),
    val followUp: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = steering.isEmpty() && followUp.isEmpty()
}

@Serializable data class GatewaySessionState(
    val sessionId: String? = null,
    val isStreaming: Boolean = false,
    val isCompacting: Boolean = false,
    val model: GatewayModelRef? = null,
    val thinkingLevel: String? = null,
    val contextUsage: GatewayContextUsage? = null,
    val queuedMessages: GatewayQueuedMessages? = null
)

@Serializable data class GatewayDirectoryItem(val name: String, val path: String, val hidden: Boolean)

@Serializable
data class GatewayWorktree(
    val path: String,
    val branch: String? = null,
    val commit: String = "",
    val main: Boolean = false,
    val missing: Boolean = false,
) {
    override fun toString(): String = "GatewayWorktree(path=<redacted>, branch=<redacted>, main=$main, missing=$missing)"
}
