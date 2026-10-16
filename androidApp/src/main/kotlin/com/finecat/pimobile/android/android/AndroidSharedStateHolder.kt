package com.finecat.pimobile.android

import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot

import com.finecat.pimobile.shared.gateway.GatewayErrorText

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.finecat.pimobile.android.platform.AndroidGatewayPreferences
import com.finecat.pimobile.android.platform.GatewayDiagnosticAction
import com.finecat.pimobile.android.platform.AndroidImagePreprocessor
import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.android.platform.BoundedLruCache
import com.finecat.pimobile.android.platform.RecentPhotosProvider
import com.finecat.pimobile.android.ui.finecat.ProviderEditorDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import com.finecat.pimobile.shared.facade.SharedMobileSnapshot
import com.finecat.pimobile.shared.facade.SharedMobileStore
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayPairingPayloadException
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import com.finecat.pimobile.shared.gateway.GatewayRuntimeState
import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.gateway.gatewayAttachmentCacheKey
import com.finecat.pimobile.shared.gateway.ProviderModelUpsert
import com.finecat.pimobile.shared.gateway.requireValidProviderUpsert
import com.finecat.pimobile.shared.protocol.GatewayDirectoryItem
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayModelSelection
import com.finecat.pimobile.shared.protocol.GatewaySlashCommand
import com.finecat.pimobile.shared.protocol.GatewayTreeNode
import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.projection.TrajectoryNode
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.SessionEvent
import java.util.UUID
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Stable
class AndroidSharedStateHolder(
    store: SharedMobileStore = SharedMobileStore(),
    private val graph: AndroidAppGraph? = null,
    /** 测试注入口：默认取 graph.recentPhotos；另配 scope 以便单测控制调度。 */
    private val recentPhotosProvider: RecentPhotosProvider? = null,
    private val recentPhotosScopeOverride: CoroutineScope? = null,
    /** 测试注入口（F2'）：无 graph 的 JVM 测试用这个 scope 挂压缩启动 watch，
     *  使按会话独立与重挂语义可断言；生产用 graph.gatewayScope。 */
    private val compactWatchScopeForTest: CoroutineScope? = null,
) {
    private val scope = graph?.let { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var runtimeCollection: Job? = null
    private val projection = AndroidGatewayProjection(store) { sessionId, beforeEntryId, resetLeaf ->
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.requestHistory(
                    sessionId = sessionId,
                    beforeEntryId = beforeEntryId,
                    limit = HISTORY_PAGE_MESSAGE_LIMIT,
                    resetLeaf = resetLeaf
                )
            }
        }
    }
    private val projectionActor = AndroidProjectionActor(
        projection = projection,
        uiDispatcher = if (graph == null) Dispatchers.Unconfined else Dispatchers.Main.immediate,
        publish = ::publishProjectionSnapshot
    )
    private var pendingStreamingSnapshot: SharedMobileSnapshot? = null
    private var streamingSnapshotPublishJob: Job? = null
    private val attachmentQueue = ArrayDeque<AttachmentRequest>()
    private var activeAttachment: AttachmentRequest? = null
    private var visibleAttachmentKeys: Set<String> = emptySet()
    private var thumbnailTargetWidthPixels = DEFAULT_THUMBNAIL_TARGET_PIXELS
    private var thumbnailTargetHeightPixels = DEFAULT_THUMBNAIL_TARGET_PIXELS
    private var lastObservedConnection = GatewayConnectionState.DISCONNECTED
    private var pendingDirectoryCreationParentPath: String? = null
    /** providerResult 的自动清除任务（showProviderResult 重置）。 */
    private var appGraphResultAutoClearJob: Job? = null
    private var providerTestBatchActive = false
    private var providerTestJob: Job? = null
    private var pendingProviderProbe: CompletableDeferred<ProviderProbeReply>? = null
    private var providerProbeId: String? = null
    /** 手动注入的 event 帧按会话持有归一器，与 GatewayRuntime 的实时路径同语义。 */
    private val wirePayloadNormalizers = mutableMapOf<String, PiEventNormalizer>()
    private var inputGeneration = 0L
    private var nextInputGeneration = 0L
    private fun advanceInputGeneration() { inputGeneration = ++nextInputGeneration }
    /** 草稿仅保存在当前进程；null 是当前新建页，重新新建时换成空稿。 */
    private data class ComposerDraft(
        val text: String, val images: List<AndroidPreparedImage>, val uris: List<String>,
        val preparations: Map<String, Long>, val generation: Long,
        val restoredSend: Pair<String, Long>?,
    )
    private val sessionDrafts = mutableMapOf<String?, ComposerDraft>()
    private var composerSessionId: String? = null
    private var composerNavigationEpoch = 0L
    internal data class BranchDraftConfirmation(
        val sessionId: String, val entryId: String, val generation: Long, val navigationEpoch: Long,
    )
    internal var branchDraftConfirmation: BranchDraftConfirmation? by mutableStateOf(null)
        private set
    private data class BranchNavigation(
        val sessionId: String, val entryId: String, val generation: Long,
        val navigationEpoch: Long, val replaceDraft: Boolean,
    )
    private var branchNavigation: BranchNavigation? = null
    private var branchRestoredDraft: Pair<String, Long>? = null
    private var pendingSelectedSessionId: String? = null
    private val sessionControlsRefresh = SessionControlsRefresh()
    private val thumbnailCache = BoundedLruCache<String, ImageBitmap>(MAXIMUM_THUMBNAIL_BYTES) {
        it.width.toLong() * it.height * 4
    }
    private val attachmentStateCache = BoundedLruCache<String, AttachmentLoadState>(MAXIMUM_STATUS_COUNT) { 1 }

    /** 新建对话页代数：prepareNewSession 递增，未绑定 sessionId 的发送预览按
     *  代过滤显示（重开新会话页不串上一次未回显的预览，回显匹配不受影响）。 */
    var newSessionEpoch: Long by mutableStateOf(0L)
        private set
    /**
     * 从某个会话页点「+」回到空白首页时定格的目录，作为新会话 cwd 的回退值。
     * 只是本地导航状态：不发消息、不建 Pi 会话（会话仍由首条 message 帧创建），
     * 对应 pi CLI「在已有会话的目录里再开一个空白会话」的服务化映射。
     */
    private var pendingNewSessionCwd: String? by mutableStateOf(null)
    /** 带图发送的本地预览条（仅 UI 层；正式回显到达前对话尾部的占位气泡）。 */
    internal var outgoingPreviews: List<OutgoingImagePreview> by mutableStateOf(emptyList())
        private set
    private val outgoingPreviewTracker = OutgoingImagePreviewTracker()
    /** 实发 attachmentId 生成器：每次实发新分配（选图的 UUID 会被重复发送复用）。 */
    internal var nextOutgoingAttachmentId: () -> String = { "img-" + UUID.randomUUID().toString() }

    var snapshot: SharedMobileSnapshot by mutableStateOf(projectionActor.initialSnapshot)
        private set
    var wirePayload: String by mutableStateOf(DEFAULT_WIRE_PAYLOAD)
    var gatewayState: GatewayRuntimeState by mutableStateOf(GatewayRuntimeState())
        private set
    /** 正式入口默认使用 FineCat；debug 启动参数可临时切回旧界面作对照。 */
    var debugUseFineCatUi: Boolean by mutableStateOf(true)
    var endpoint: String by mutableStateOf(AndroidGatewayPreferences.DEFAULT_ENDPOINT)
    /** 同一网关的多个入口，按尝试顺序（例：在家局域网优先、在外 tailnet 兜底）。 */
    var endpointCandidates: List<String> by mutableStateOf(emptyList<String>())
        private set
    var pairingPayload: String by mutableStateOf("")
    var gatewayUiEpoch: Long by mutableLongStateOf(0L)
        private set
    /** 本次App进程中的Provider列表视图顺序；不写Pi配置，也不改变模型scope。 */
    private val providerModelViewOrders = mutableMapOf<String, List<String>>()
    /** 首入口与Provider隔离；仅内存，关闭离线编辑卡后可继续，重配时清除。 */
    internal val providerEditorDrafts = mutableMapOf<Pair<String, String>, ProviderEditorDraft>()

    internal fun providerModelViewOrder(providerId: String): List<String> =
        providerModelViewOrders[providerId].orEmpty()

    internal fun updateProviderModelViewOrder(providerId: String, order: List<String>) {
        if (providerId.isNotBlank()) {
            providerModelViewOrders[providerId] = order.filter(String::isNotBlank).distinct()
        }
    }
    /** 新建会话使用的目录选择（替代旧 workspace 选择），null 时回退最近会话 cwd。 */
    var selectedDirectoryPath: String? by mutableStateOf(null)
        private set
    /**
     * 空白首页「工作区」行显示的目录：与实发 cwd 走同一解析口径（见
     * [resolveNewSessionCwd]），避免「显示一个目录、消息投到另一个目录」。
     */
    val newSessionDirectoryPath: String?
        get() = resolveNewSessionCwd(
            selectedDirectoryPath,
            pendingNewSessionCwd,
            snapshot.sessions.firstOrNull()?.cwd
        )
    /** FineCat 的目录行只展示显式选择或从当前会话沿用的目录，不猜最近会话。 */
    val fineCatNewSessionDirectoryPath: String?
        get() = selectedDirectoryPath ?: pendingNewSessionCwd
    /** 首页大标题自定义文案；null 显示内置默认。 */
    var heroTitle: String? by mutableStateOf(null)
        private set
    /** 首页副标题标语自定义文案；null 只显示版本号。 */
    var heroTagline: String? by mutableStateOf(null)
        private set
    /** 外观模式："system" | "light" | "dark"；"system" 跟随系统深浅。 */
    var appearanceMode: String by mutableStateOf("system")
        private set
    /**
     * 首页"假删除"的会话黑名单：只在手机端列表隐藏，不动宿主机的 session 文件。
     * 由 [hideSession] / [restoreHiddenSession] / [restoreAllHiddenSessions] 维护。
     */
    var hiddenSessionIds: List<String> by mutableStateOf(emptyList())
        private set
    /**
     * 首页目录组级“假删除”黑名单：整个目录下的会话都在列表隐藏，不动宿主机的
     * session 文件。存完整 cwd，与 SessionGroup.cwd 口径一致。
     */
    var hiddenDirectoryPaths: List<String> by mutableStateOf(emptyList())
        private set
    var pinnedSessionIds: List<String> by mutableStateOf(emptyList())
        private set
    /** 新建对话页的模型预选：会话尚未创建（pi 模型是会话级），随首个 message 帧
     *  携带（网关先 setModel 再 prompt）；发送成功后消费，选中已有会话即作废。 */
    var pendingModelSelection: GatewayModelSelection? by mutableStateOf(null)
        private set
    private var pingStarted: kotlin.time.TimeMark? = null
    private var activePingId: String? = null
    /** 从发送请求到收到对应 pong 的单调时钟往返耗时。 */
    var pingResult: String? by mutableStateOf(null)
        private set
    var directoryPath: String? by mutableStateOf(null)
    private var requestedDirectoryPath: String? = null
    /** 目录面板内错误（null=无）：面板内展示+重试，不只靠全局弹窗。 */
    var directoryError: String? by mutableStateOf(null)
        private set
    var directoryHome: String? by mutableStateOf(null)
        private set
    var directoryCrumbs: List<GatewayDirectoryItem> by mutableStateOf(emptyList())
        private set
    var directoryEntries: List<GatewayDirectoryItem> by mutableStateOf(emptyList())
        private set
    var directoryIsLoading: Boolean by mutableStateOf(false)
        private set
    var directoryCreationIsLoading: Boolean by mutableStateOf(false)
        private set
    var createdDirectoryPathToReveal: String? by mutableStateOf(null)
        private set
    var historyPagingSessionIds: Set<String> by mutableStateOf(emptySet())
        private set

    var trajectoryNodes: List<TrajectoryNode> by mutableStateOf(emptyList())
        private set
    private var trajectoryIsActive = false
    private var messageDraftState: String by mutableStateOf("")
    var messageDraft: String
        get() = messageDraftState
        set(value) {
            if (messageDraftState != value) {
                messageDraftState = value
                advanceInputGeneration()
            }
        }
    private var preparedImagesState: List<AndroidPreparedImage> by mutableStateOf(emptyList())
    var preparedImages: List<AndroidPreparedImage>
        get() = preparedImagesState
        private set(value) {
            if (preparedImagesState != value) {
                preparedImagesState = value
                advanceInputGeneration()
            }
        }
    /** 与 preparedImages 同序的来源 uri（P4c：托盘勾选态/按 uri 移除的真源）。 */
    var preparedUris: List<String> by mutableStateOf(emptyList())
        private set
    private var imagePreparationSequence = 0L
    private var imagePreparations: Map<String, Long> by mutableStateOf(emptyMap())
    private val imagePreparationJobs = mutableMapOf<Long, Job>()
    val preparingPhotoUris: Set<String>
        get() = imagePreparations.keys
    var attachmentThumbnails: Map<String, ImageBitmap> by mutableStateOf(emptyMap())
        private set
    var attachmentStates: Map<String, AttachmentLoadState> by mutableStateOf(emptyMap())
        private set
    var modelCatalogIsLoading by mutableStateOf(false)
        private set
    var modelCatalogError: String? by mutableStateOf(null)
        private set

    private var platformErrorValue: String? by mutableStateOf(null)
    var platformError: String?
        get() = platformErrorValue
        private set(value) {
            platformErrorValue = value
            platformErrorAction = if (GatewayErrorText.isConnectionFailure(value)) PlatformErrorAction.RECONNECT
                else PlatformErrorAction.DISMISS_ONLY
        }
    /** 每次新错误重置动作，避免上一次目录重试/重连动作串到本地失败。 */
    var platformErrorAction: PlatformErrorAction by mutableStateOf(PlatformErrorAction.DISMISS_ONLY)
        private set

    /** 全局错误弹窗确认键动作。 */
    enum class PlatformErrorAction { RECONNECT, RETRY_DIRECTORY, DISMISS_ONLY }

    /** 活跃链落后于主线最新（树页点过节点切走/断连期间主线前进）——
     *  对话流尾部显示"跳到最新"出口（1.5.9）。 */
    internal fun isBehindLatest(sessionId: String?): Boolean =
        sessionId?.takeIf(String::isNotBlank)?.let { projection.isBehindLatest(it) } == true

    internal fun latestEntryId(sessionId: String?): String? =
        sessionId?.takeIf(String::isNotBlank)?.let { projection.latestEntryId(it) }

    /** 出口动作：branch-switch 回主线叶子（网关清手动叶子 + navigateTree +
     *  branch-switched + history 重拉），对齐树页点节点的既有路径。 */
    fun jumpToLatest(sessionId: String?) {
        val target = latestEntryId(sessionId) ?: return
        switchBranchTo(target)
    }

    // ---- 手动压缩上下文（CTX02）：真实 compact 生命周期与本地等待记账 ----

    /** 一次本地请求的身份；重置时同时取消尚未发送的任务与提示计时器。 */
    private class PendingCompact {
        var watch: Job? = null
        var send: Job? = null
    }

    private val compactPendingSessions: SnapshotStateMap<String, PendingCompact> = mutableStateMapOf()
    private val compactStartupWatchMillis = 8_000L
    private val compactSeenEvents = mutableSetOf<String>()

    fun canStartManualCompact(): Boolean {
        val sessionId = snapshot.selectedSessionId ?: return false
        return supportsRequest("compact") &&
            (gatewayState.connection == GatewayConnectionState.CONNECTED || compactGateForceOpenForTest) &&
            sessionId !in compactPendingSessions &&
            snapshot.sessionStates[sessionId]?.isCompacting != true &&
            !selectedSessionRecalling() && !selectedSessionStopping()
    }

    internal var compactGateForceOpenForTest: Boolean = false
    internal val compactRequestsForTest = mutableListOf<String>()

    private fun finishCompactPending(sessionId: String?, notice: String? = null) {
        val pending = compactPendingSessions.remove(sessionId ?: return) ?: return
        pending.watch?.cancel()
        pending.send?.cancel()
        if (notice != null && snapshot.selectedSessionId == sessionId) showTransientNotice(notice)
    }

    /** Runtime 的三种请求终态共用同一处理，不把后台会话提示弹到当前页。 */
    internal fun finishManualCompactRequest(requestType: String, sessionId: String?, notice: String) {
        if (requestType == "compact") finishCompactPending(sessionId, notice)
    }

    private fun clearPendingManualCompacts() {
        compactPendingSessions.values.forEach { pending ->
            pending.watch?.cancel()
            pending.send?.cancel()
        }
        compactPendingSessions.clear()
    }

    /** 8秒只是启动提示时间，不能据此推断请求未执行或安全地再次触发。
     *  等待由实际开始/结束、明确拒绝、断连请求终态或重置收尾。 */
    private fun watchCompactStartup(scope: CoroutineScope, sessionId: String, pending: PendingCompact) {
        if (compactPendingSessions[sessionId] !== pending) return
        pending.watch?.cancel()
        pending.watch = scope.launch {
            delay(compactStartupWatchMillis)
            withContext(Dispatchers.Main.immediate) {
                if (compactPendingSessions[sessionId] === pending && snapshot.selectedSessionId == sessionId) {
                    showTransientNotice("压缩仍在启动，请稍候")
                }
            }
        }
    }

    internal fun compactWatchJobForTest(sessionId: String): Job? = compactPendingSessions[sessionId]?.watch

    internal fun rearmCompactWatchForTest(sessionId: String) {
        val pending = compactPendingSessions[sessionId] ?: return
        compactWatchScopeForTest?.let { watchCompactStartup(it, sessionId, pending) }
    }

    private fun observeCompactingStarted(sessionId: String?) {
        if (sessionId != null && snapshot.sessionStates[sessionId]?.isCompacting == true) {
            finishCompactPending(sessionId)
        }
    }

    /** 压缩收尾（compact/end、notice/compact）后补拉一次会话 state：SDK 压缩后
     *  统计可能为 null（圆环显示 —）；只在事件归属当前选中会话时补拉，避免挤掉
     *  其他会话排队中的 state 请求（M4）；重复/迟到事件由 [compactSeenEvents] 吸收
     *  （键带 seq，同会话第二轮压缩不受首轮残留键影响，R1）。 */
    private fun requestStateAfterCompaction(
        appGraph: AndroidAppGraph?,
        sessionId: String?,
    ) {
        if (sessionId.isNullOrBlank()) return
        if (sessionId != snapshot.selectedSessionId) return
        // 外层事件键已带 seq 去重（R1）：同帧多路径到达由外层拦截，这里直接发请求。
        if (appGraph == null) {
            // 测试缝（R2）：仅无 graph 的 JVM 测试记录补拉意图（R3-2：生产不写）。
            stateRequestsAfterCompactionForTest.add(sessionId)
            return
        }
        appGraph.gatewayScope.launch { appGraph.gatewayRuntime.requestState(sessionId) }
    }

    /** 测试缝（R2）：记录收尾后实际发出的 state 补拉目标（含无 graph 测试路径）。 */
    internal val stateRequestsAfterCompactionForTest = mutableListOf<String>()

    /** 非阻断提示条：lane 挡回、未连接重试这类“预期内失败”不该弹模态窗。 */
    var transientNotice: String? by mutableStateOf(null)
        private set
    private var transientNoticeAutoClearJob: Job? = null

    /** 会话分支树（PROTOCOL §4.18）：tree 帧应答缓存，TreePage 渲染。
     *  Triple.first=所属 sessionId（缓存归属校验键；曾误存 leafId 导致与
     *  sessionId 比较永不相等，树页永久转圈且每次进 tab 重拉，b372405 引入）。 */
    var sessionTree: Triple<String, String?, List<GatewayTreeNode>>? by mutableStateOf(null)
        private set
    /** 分支切换成功（非 alreadyAt）的一次性信号：树页点击后 ConversationScreen
     *  据此自动切回对话 tab，对齐 pi TUI 的 showTreeSelector"选中即返回对话"。 */
    var branchSwitchedTick: Int by mutableIntStateOf(0)
        private set
    private var treeLoading by mutableStateOf(false)

    /** 树请求在飞（测试取证用只读视图：注入应答帧前需确认在途，避免 no-active-request 拒收）。 */
    val treeRequestInFlight: Boolean get() = treeLoading
    /** 旧网关不支持 tree 时只提示一次：切页/手动刷新会反复调用，不能每次弹。 */
    private var treeUnsupportedNoticed = false
    /** 树加载失败原因（null=无失败）：树页据此渲染错误态+重试。 */
    var treeError: String? by mutableStateOf(null)
        private set

    /** 自定义命令清单（PROTOCOL §4.21）：commands 帧应答按 sessionId 缓存。
     *  会话级：restart 后重拉（session-restarted 信号处清缓存）；重连后重拉
     *  一次（连接恢复处）。旧网关无此能力时保持 null，联想入口隐藏。 */
    var slashCommands: Pair<String, List<GatewaySlashCommand>>? by mutableStateOf(null)
        private set
    private var commandsLoading = false
    val inputAssist = InputAssistState()

    fun setInputAssistContext(sessionId: String?, cwd: String?) {
        val next = InputAssistContext(sessionId, if (sessionId == null) cwd else null)
        if (inputAssist.context == next) return
        inputAssist.reset(next)
        refreshInputResources()
    }

    fun refreshInputResources() {
        if (gatewayState.connection != GatewayConnectionState.CONNECTED ||
            "input-resources" !in gatewayState.capabilities
        ) return
        val request = inputAssist.resourcesRequest() ?: return
        graph?.gatewayScope?.launch { graph.gatewayRuntime.sendRequest(request) }
    }

    fun requestInputCompletions(token: String) {
        if (gatewayState.connection != GatewayConnectionState.CONNECTED ||
            "input-completions" !in gatewayState.capabilities
        ) return
        val request = inputAssist.completionsRequest(token) ?: return
        graph?.gatewayScope?.launch { graph.gatewayRuntime.sendRequest(request) }
    }

    var successfulMessageSendCount: Long by mutableLongStateOf(0L)
        private set

    private data class PendingSessionName(val sessionId: String, val name: String, val result: (String?) -> Unit)
    private var pendingSessionName: PendingSessionName? = null

    private fun finishSessionName(error: String?) {
        val pending = pendingSessionName ?: return
        pendingSessionName = null
        pending.result(error)
    }

    private fun failSessionName(requestType: String, sessionId: String?, detail: String) {
        if (requestType == "name" && pendingSessionName?.sessionId == sessionId) finishSessionName(detail)
    }

    init {
        graph?.let { appGraph ->
            val holderScope = requireNotNull(scope)
            runtimeCollection = appGraph.gatewayScope.launch {
                launch {
                    // 黑名单只在首次发射时加载：之后以内存为准（hide/restore 乐观更新 +
                    // 异步落盘）。否则磁盘回灌会把连续两次操作的前一次结果闪回列表。
                    (appGraph.preferences as? AndroidGatewayPreferences)?.let { preferences ->
                        launch {
                            preferences.readFailed.collect { failed ->
                                if (failed) withContext(Dispatchers.Main.immediate) { notifyUser("本地设置读取失败，请重试") }
                            }
                        }
                    }
                    var hiddenSessionsLoaded = false
                    appGraph.preferences.snapshots.collect { value ->
                        withContext(Dispatchers.Main.immediate) {
                            endpoint = value.endpoint
                            endpointCandidates = value.endpointCandidates
                            heroTitle = value.heroTitle
                            heroTagline = value.heroTagline
                            appearanceMode = value.appearanceMode
                            pinnedSessionIds = value.pinnedSessionIds
                            if (!hiddenSessionsLoaded) {
                                hiddenSessionIds = value.hiddenSessionIds
                                hiddenDirectoryPaths = value.hiddenDirectoryPaths
                                hiddenSessionsLoaded = true
                            }
                        }
                    }
                }
                launch {
                    appGraph.gatewayRuntime.state.collect { state ->
                        appGraph.diagnostics.runtimeState(state)
                        withContext(Dispatchers.Main.immediate) {
                            val didReconnect = state.connection == GatewayConnectionState.CONNECTED &&
                                lastObservedConnection != GatewayConnectionState.CONNECTED
                            gatewayState = state
                            if (state.connection != GatewayConnectionState.CONNECTED) {
                                inputAssist.reset()
                                finishPing("未连接")
                                catalogProviders = emptyList()
                                catalogError = null
                            } else if (didReconnect) {
                                pingResult = null
                            }
                            if (lastObservedConnection == GatewayConnectionState.CONNECTED &&
                                state.connection != GatewayConnectionState.CONNECTED
                            ) sessionControlsRefresh.reset()
                            if (state.connection != GatewayConnectionState.CONNECTED &&
                                state.connection != GatewayConnectionState.CONNECTING &&
                                state.connection != GatewayConnectionState.AUTHENTICATING
                            ) {
                                clearWorkspaceRequestLoading()
                            }
                            // 重连不重派 attachment：fetch 在断连/超时/错误下已返回 null
                            // 驱动旧 job 收尾（FAILED 终态，手动 retry 才重取）；这里若
                            // 把 active 塞回队首重派，旧 job 按同 cacheKey 收尾会误清
                            // 重派的 active，产生重复请求与乱序。history catch-up 不受影响。
                            lastObservedConnection = state.connection
                        }
                    }
                }
                launch {
                    appGraph.gatewayRuntime.events.collect { event ->
                        appGraph.diagnostics.runtimeEvent(event)
                        when (event) {
                            is GatewayRuntimeEvent.Frame -> {
                                if (event.gatewayRevision != appGraph.gatewayRuntime.state.value.gatewayRevision) return@collect
                                if (event.frame.kind == "session-named") {
                                    withContext(Dispatchers.Main.immediate) {
                                        val pending = pendingSessionName
                                        if (pending != null && event.frame.sessionId == pending.sessionId) {
                                            finishSessionName(if (event.frame.name == pending.name) null
                                                else "改名结果未确认，请重新打开核对")
                                        }
                                    }
                                }
                                val isStreamingChunk = event.frame.kind == "event" &&
                                    event.normalizedEvents.any { it.event.type == "assistant/chunk" } &&
                                    event.normalizedEvents.all { it.event.type == "assistant/chunk" }
                                val streamingProjectionFlushed = if (isStreamingChunk) {
                                    projectionActor.acceptStreamingFrame(
                                        event.rawJson,
                                        event.frame,
                                        event.correlatedSessionId,
                                        event.normalizedEvents
                                    )
                                } else {
                                    projectionActor.acceptFrame(
                                        event.rawJson,
                                        event.frame,
                                        event.correlatedSessionId,
                                        event.normalizedEvents
                                    ) {
                                        afterFramePublish(
                                            appGraph,
                                            event.frame,
                                            event.correlatedSessionId,
                                            event.normalizedEvents
                                        )
                                    }
                                    false
                                }
                                if (event.frame.kind == "hello") {
                                    withContext(Dispatchers.Main.immediate) {
                                        gatewayState = appGraph.gatewayRuntime.state.value
                                        inputAssist.reset()
                                        refreshInputResources()
                                        if (supportsRequest("providers")) loadProviders()
                                        refreshModelCatalog()
                                        slashCommands = null
                                        commandsLoading = false
                                        snapshot.selectedSessionId?.let { refreshSlashCommands(it) }
                                    }
                                    projectionActor.catchUpSelectedHistoryAfterReconnect()
                                }
                                if (!isStreamingChunk) {
                                    if (event.frame.kind == "sent") {
                                        event.frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                                            handleSentSession(appGraph, sessionId)
                                        }
                                    }
                                    // sent 帧的预览收敛统一在 handleWorkspaceFrame
                                    //（acceptFrame 的 afterPublish 会调用它，与 wire 注入同路径，
                                    // 均在 Main 执行）；user 回显同样走 afterPublish（见上）。
                                    // turn/end 后排队消息可能已被消费（steer 本轮消化、
                                    // followUp 转正式消息）：拉一次 state 刷新吸附显示。
                                    if (event.frame.kind == "event" &&
                                        event.normalizedEvents.any { it.event.type == "turn/end" }
                                    ) {
                                        event.frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                                            // 不在 collector 里直调：requestState 要拿 runtime 锁，
                                            // 生产者持锁等队列空间时即死锁。fire-and-forget 只管刷新。
                                            launch { appGraph.gatewayRuntime.requestState(sessionId) }
                                        }
                                    }
                                }
                                if (trajectoryIsActive && (!isStreamingChunk || streamingProjectionFlushed)) {
                                    publishTrajectory()
                                }
                            }
                            is GatewayRuntimeEvent.RequestQueued -> Unit
                            is GatewayRuntimeEvent.RequestCancelled -> {
                                withContext(Dispatchers.Main.immediate) {
                                    finishBranchDraftRequest(event.requestType, event.targetSessionId)
                                    if (event.requestType == "abort") finishStoppingSession(event.targetSessionId)
                                    if (event.requestType == "dequeue") finishQueueRecall(event.targetSessionId, event.correlationId)
                                    if (event.requestType == "compact") {
                                        finishManualCompactRequest(event.requestType, event.targetSessionId, "压缩请求未确认，请重连后核对")
                                    }
                                    handleWorkspaceRequestCancelled(event)
                                    finishModelCatalogFailure(event.requestType, "模型加载已中断，请重试")
                                    inputAssist.fail(event.requestType, event.correlationId, "加载已中断")
                                    failSessionName(event.requestType, event.targetSessionId, "保存未确认，请重新打开核对")
                                    if (event.requestType == "ping" && pingStarted != null &&
                                        (event.correlationId == null || event.correlationId == activePingId)) finishPing(
                                        if (gatewayState.connection == GatewayConnectionState.CONNECTED) "测速未完成" else "未连接"
                                    )
                                    if (event.requestType == "history") {
                                        event.targetSessionId?.let {
                                            historyPagingSessionIds = historyPagingSessionIds - it
                                        }
                                    }
                                    if (event.requestType == "tree") {
                                        // 与 RequestTimedOut 同形：连接关闭/替换、后台挂起等取消
                                        // 在飞 tree 请求时必须复位 loading，否则树页永久卡「加载
                                        // 中」且新 UI 无重试入口（审查 M4）。
                                        treeLoading = false
                                        treeError = "加载被中断"
                                    }
                                    // 树页动作（复制/标签）被取消：必须终态，否则动作卡按钮
                                    // 永久停在“处理中”且回调泄漏。
                                    failTreeAction(event.requestType, event.targetSessionId, "操作已中断，请重试")
                                    // provider 系（含 provider-test）被取消时不能把 loading 卡住，
                                    // 否则“测试中…”永久 disable 且后续点击被静默丢弃。
                                    clearProviderRequestLoading(event.requestType, "连接测试已取消，请重试")
                                    finishModelScopeFailure(event.requestType, event.correlationId,
                                        if (event.requestType == "model-scope") "加载已中断，请重新加载"
                                        else "保存未确认，请重新打开核对")
                                    if (event.requestType in PROVIDER_REQUEST_TYPES) {
                                        // 经 showProviderResult 统一管理清除任务：直接赋值会
                                        // 绕过旧 timer 的取消，旧成功提示的 4s 清除会把本终态
                                        // 提前抹掉，且本终态自身永不清除。
                                        val detail = providerUnconfirmedResultText(event.requestType, "已取消")
                                        showProviderResult(detail)
                                        // 只有在途 provider-put 的取消才是保存的终态；读请求
                                        // （providers / provider-test / provider-models）与
                                        // provider-delete 的取消只写瞬时提示，不得冒充保存结果。
                                        if (event.requestType == "provider-put") {
                                            publishSaveTerminal(ProviderSaveTerminal.Status.Unconfirmed, detail)
                                        }
                                    }
                                }
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyCancelled(it) }
                                // attachment 取回由 fetchAttachment 的终态驱动（返回 null 即失败），
                                // 不再依赖 RequestCancelled 事件的空关联 hack。
                                // 请求超时/断连取消：只意味未确认（请求可能已达网关），
                                // 不判死失败，迟到回显仍收走。
                                if (event.requestType == "message") {
                                    withContext(Dispatchers.Main.immediate) {
                                        handleOutgoingSendUnconfirmed()
                                    }
                                }
                            }
                            is GatewayRuntimeEvent.RequestTimedOut -> {
                                withContext(Dispatchers.Main.immediate) {
                                    finishBranchDraftRequest(event.requestType, event.targetSessionId)
                                    if (event.requestType == "abort") finishStoppingSession(event.targetSessionId)
                                    if (event.requestType == "dequeue") finishQueueRecall(event.targetSessionId, event.correlationId)
                                    if (event.requestType == "compact") {
                                        finishManualCompactRequest(event.requestType, event.targetSessionId, "压缩请求未确认，请重连后核对")
                                    }
                                    if (event.requestType in WORKSPACE_REQUEST_TYPES) directoryError =
                                        if (event.requestType == "directories") "请求超时，请重试"
                                        else "创建未确认，请重新打开核对"
                                    finishModelCatalogFailure(event.requestType, "请求超时，请重试")
                                    inputAssist.fail(event.requestType, event.correlationId, "请求超时，请重试")
                                    failSessionName(event.requestType, event.targetSessionId, "保存未确认，请重新打开核对")
                                    if (event.requestType == "ping" && pingStarted != null &&
                                        (event.correlationId == null || event.correlationId == activePingId)) finishPing("测速超时")
                                    clearWorkspaceRequestLoading(event.requestType)
                                    // 超时同样要释放 provider 系的 loading（lane 超时会以
                                    // RequestTimedOut 结束在途请求）。
                                    clearProviderRequestLoading(event.requestType, "连接测试超时，请重试")
                                    finishModelScopeFailure(event.requestType, event.correlationId,
                                        if (event.requestType == "model-scope") "请求超时，请重试"
                                        else "保存未确认，请重新打开核对")
                                    if (event.requestType in PROVIDER_REQUEST_TYPES) {
                                        // 同 RequestCancelled：走 showProviderResult 管理 4s 清除。
                                        val detail = providerUnconfirmedResultText(event.requestType, "超时")
                                        showProviderResult(detail)
                                        // 同上：超时的保存只表示「没确认」，不表示没落盘。
                                        if (event.requestType == "provider-put") {
                                            publishSaveTerminal(ProviderSaveTerminal.Status.Unconfirmed, detail)
                                        }
                                    }
                                    if (event.requestType == "tree") {
                                        treeLoading = false
                                        treeError = "加载超时"
                                    }
                                    failTreeAction(event.requestType, event.targetSessionId, "请求超时，请重试")
                                    // 清单失败静默：联想入口隐藏，不打断对话。
                                    if (event.requestType == "commands") {
                                        commandsLoading = false
                                    }
                                    if (event.requestType == "history") {
                                        event.targetSessionId?.let {
                                            historyPagingSessionIds = historyPagingSessionIds - it
                                        }
                                    }
                                }
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyTimedOut(it) }
                                // attachment 超时终态由 fetchAttachment 返回 null 收敛。
                                // 请求超时：预览转未确认（不判死失败/不自动重发，草稿可手动恢复）。
                                if (event.requestType == "message") {
                                    withContext(Dispatchers.Main.immediate) {
                                        handleOutgoingSendUnconfirmed()
                                    }
                                }
                            }
                            is GatewayRuntimeEvent.RequestRejected -> {
                                withContext(Dispatchers.Main.immediate) {
                                    finishBranchDraftRequest(event.requestType, event.targetSessionId)
                                    if (event.requestType == "abort") finishStoppingSession(event.targetSessionId)
                                    if (event.requestType == "dequeue") finishQueueRecall(event.targetSessionId, event.correlationId)
                                    if (event.requestType == "compact") {
                                        // 压缩请求被拒/启动失败（如未选模型）：收尾本地等待并给
                                        // 瞬态提示，不接全局错误面（压缩是可重试的常规动作）。
                                        finishManualCompactRequest(
                                            event.requestType, event.targetSessionId,
                                            GatewayErrorText.request("compact", event.reason)
                                        )
                                        return@withContext
                                    }
                                    if (event.requestType == "models") {
                                        finishModelCatalogFailure("models", GatewayErrorText.request("models", event.reason))
                                        return@withContext
                                    }
                                    if (event.requestType in setOf("input-resources", "input-completions")) {
                                        inputAssist.fail(event.requestType, event.correlationId, GatewayErrorText.request(event.requestType, event.reason))
                                        return@withContext
                                    }
                                    failSessionName(event.requestType, event.targetSessionId, GatewayErrorText.request("session-name", event.reason))
                                    clearWorkspaceRequestLoading(event.requestType)
                                    if (event.requestType == "model-scope" || event.requestType == "model-scope-put") {
                                        finishModelScopeFailure(event.requestType, event.correlationId,
                                            GatewayErrorText.request(event.requestType, event.reason))
                                        return@withContext
                                    }
                                    if (event.requestType in PROVIDER_REQUEST_TYPES) {
                                        // Runtime 将网关 error 转为 RequestRejected，不再发布原帧。
                                        // 这里必须结束 loading 并给表单回执；只展示稳定错误码。
                                        clearProviderRequestLoading(event.requestType, GatewayErrorText.request(event.requestType, event.reason))
                                        // 同上：走 showProviderResult 管理 4s 清除；reason 是
                                        // Runtime 稳定错误码，不回显原始 upstream message。
                                        val detail = GatewayErrorText.request(event.requestType, event.reason)
                                        showProviderResult(detail)
                                        // 只有 provider-put 的拒绝才是保存的终态（真失败）。
                                        if (event.requestType == "provider-put") {
                                            publishSaveTerminal(ProviderSaveTerminal.Status.Rejected, detail)
                                        }
                                        return@withContext
                                    }
                                    if (event.requestType == "tree") {
                                        treeLoading = false
                                        treeError = GatewayErrorText.request("tree", event.reason)
                                    }
                                    // 树页动作的服务端拒绝（entry-not-found / entry-copy-empty /
                                    // session-not-found / bad-request）：只给动作卡回执，不进全局
                                    // 错误面（该面带重连动作，对这类例行拒绝是误导）。
                                    if (event.requestType == "entry-copy" || event.requestType == "label-put") {
                                        failTreeAction(event.requestType, event.targetSessionId,
                                            GatewayErrorText.request(event.requestType, event.reason))
                                        return@withContext
                                    }
                                    if (event.requestType == "commands") {
                                        // 联想清单失败真静默：不进通用错误面。
                                        commandsLoading = false
                                        return@withContext
                                    }
                                    if (isBenignRejection(event.reason)) {
                                        showTransientNotice(benignRejectionText(event.requestType, event.reason))
                                    } else if (event.requestType == "switch-branch") {
                                        // 服务端拒绝切换分支（协议 4.19：bad-request=运行/压缩中
                                        // 被拒，session-not-found=会话不可用）：请求级失败按瞬态
                                        // 提示呈现并保持原叶，不接全局错误面（该面带重连动作，
                                        // 对例行拒绝是误导；F06 实测曾弹出全局对话框）。reason 是
                                        // Runtime 稳定错误码，不回显服务端 message（与 provider 表
                                        // 单同原则）。白名单只收协议声明过的请求级拒绝；
                                        // send-failed 等连接级原因仍落全局错误面（审查 m5）。
                                        val text = switchBranchRejectionText(event.reason)
                                        if (text != null) showTransientNotice(text)
                                        else {
                                            platformError = GatewayErrorText.request(event.requestType, event.reason)
                                            if (GatewayErrorText.isConnectionFailure(event.reason)) platformErrorAction = PlatformErrorAction.RECONNECT
                                        }
                                    } else if (event.requestType in WORKSPACE_REQUEST_TYPES) {
                                        // 目录读取/新建失败（DEC-F09-CWD）：面板内错误行+重试，
                                        // 全局弹窗的确认键是「重试」当前目录而不是重连。原
                                        // handleWorkspaceFrame 的 error 分支收不到这类失败——
                                        // Runtime 不发布 error 原帧（F10 实测）。
                                        directoryError = GatewayErrorText.request(event.requestType, event.reason)
                                        platformError = GatewayErrorText.request(event.requestType, event.reason)
                                        platformErrorAction = if (event.requestType == "directories") PlatformErrorAction.RETRY_DIRECTORY else PlatformErrorAction.DISMISS_ONLY
                                    } else if (event.requestType != "history" && event.requestType != "tree") {
                                        platformError = GatewayErrorText.request(event.requestType, event.reason)
                                        if (GatewayErrorText.isConnectionFailure(event.reason)) platformErrorAction = PlatformErrorAction.RECONNECT
                                    }
                                    handleOutgoingMessageRejection(event)
                                    if (event.requestType == "history") {
                                        event.targetSessionId?.let {
                                            historyPagingSessionIds = historyPagingSessionIds - it
                                        }
                                    }
                                }
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyRejected(it, event.reason) }
                                // attachment 拒绝终态由 fetchAttachment 返回 null 收敛。
                                // 同步拒绝（request-busy/未连接/发送异常）不走这里：
                                // runtime.sendMessage 返回 false 后由回写按 token 权威收敛，
                                // 避免 request-busy 误伤仍在途的队头请求。
                            }
                        }
                    }
                }
                launch {
                    // 测试隔离开关：置位时跳过遗留配对端点的真实自动连接（默认 false，
                    // 正式路径不受影响——见 companion 内注释）。
                    if (skipStoredConnectForTest) return@launch
                    runCatching { appGraph.gatewayRuntime.connectStoredIfPaired() }
                        .onFailure {
                            withContext(Dispatchers.Main.immediate) {
                                showPlatformError("stored-connect-failed")
                            }
                        }
                }
            }
        }
    }

    fun loadFixture() {
        val appGraph = graph
        if (appGraph == null) projectionActor.loadFixtureImmediate(::clearAttachmentUiState)
        else appGraph.gatewayScope.launch { projectionActor.loadFixture(::clearAttachmentUiState) }
    }

    internal suspend fun loadFixtureAndAwaitForTest() {
        projectionActor.loadFixture(::clearAttachmentUiState)
    }

    fun submitWirePayload() {
        val payload = wirePayload
        val appGraph = graph
        if (appGraph == null) {
            runCatching { GatewayWireDecoder.decode(payload) }
                .onSuccess { frame ->
                    val normalizedEvents = normalizedWirePayloadEvents(frame)
                    projectionActor.acceptFrameImmediate(payload, frame, frame.sessionId, normalizedEvents)
                    // 与生产 collector 共用同一 postFrame 收尾：event 帧的回显收敛
                    // 不再要求测试手工调 handleOutgoingEcho 作弊。
                    afterFramePublish(null, frame, frame.sessionId, normalizedEvents)
                }
                .onFailure { showPlatformError("decode-failed") }
            return
        }
        appGraph.gatewayScope.launch {
            runCatching { GatewayWireDecoder.decode(payload) }
                .onSuccess { frame ->
                    val normalizedEvents = normalizedWirePayloadEvents(frame)
                    projectionActor.acceptFrame(
                        payload,
                        frame,
                        frame.sessionId,
                        normalizedEvents
                    ) {
                        afterFramePublish(appGraph, frame, frame.sessionId, normalizedEvents)
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main.immediate) { showPlatformError("decode-failed") }
                }
        }
    }

    /** 调试面板注入的 pi event 帧也要过归一器，否则下游投影收到空事件列表。 */
    private fun normalizedWirePayloadEvents(frame: GatewayFrame): List<SessionEvent> {
        if (frame.kind != "event") return emptyList()
        val sessionId = frame.sessionId?.takeIf(String::isNotBlank) ?: return emptyList()
        val event = frame.event ?: return emptyList()
        val normalizer = wirePayloadNormalizers.getOrPut(sessionId) { PiEventNormalizer(sessionId) }
        return normalizer.normalize(frame.seq ?: 0, frame.time ?: 0.0, event, frame.entryId)
    }

    private fun currentComposerDraft() = ComposerDraft(
        messageDraft, preparedImages, preparedUris, imagePreparations, inputGeneration, autoRestoredDraft,
    )

    private fun isUneditedBranchDraft() = branchRestoredDraft == (composerSessionId to inputGeneration) &&
        preparedImages.isEmpty() && preparedUris.isEmpty() && imagePreparations.isEmpty()

    private fun showComposerDraft(owner: String?, draft: ComposerDraft?) {
        composerSessionId = owner
        // 导航恢复同一修订；之后的编辑始终从全局单调序列领取新修订，迟到发送可精确清原稿。
        messageDraftState = draft?.text.orEmpty()
        preparedImagesState = draft?.images.orEmpty()
        preparedUris = draft?.uris.orEmpty()
        imagePreparations = draft?.preparations.orEmpty()
        inputGeneration = draft?.generation ?: ++nextInputGeneration
        autoRestoredDraft = draft?.restoredSend
        branchRestoredDraft = null
    }

    private fun prepareComposerForNavigation(targetSessionId: String?, newPage: Boolean = false) {
        composerNavigationEpoch += 1
        branchNavigation = null
        branchDraftConfirmation = null
        if (!newPage && composerSessionId == targetSessionId) return
        val current = currentComposerDraft()
        if (!isUneditedBranchDraft() && (current.text.isNotEmpty() || current.images.isNotEmpty() ||
                current.preparations.isNotEmpty())) sessionDrafts[composerSessionId] = current
        else sessionDrafts.remove(composerSessionId)
        if (newPage) sessionDrafts.remove(null)?.preparations?.values?.forEach { imagePreparationJobs.remove(it)?.cancel() }
        showComposerDraft(targetSessionId, sessionDrafts.remove(targetSessionId))
        closePhotosTray()
    }

    fun selectSession(sessionId: String) {
        prepareComposerForNavigation(sessionId)
        if (sessionId != snapshot.selectedSessionId) clearTreeActions()
        sessionControlsRefresh.reset()
        graph?.diagnostics?.intent(GatewayDiagnosticAction.SELECT_SESSION, hasSession = true)
        pendingSelectedSessionId = sessionId
        pendingModelSelection = null
        // 进入已有会话即消费掉「退出新建时沿用的目录」：它只对紧接的那一页空白页有效。
        pendingNewSessionCwd = null
        val afterPublish = {
            if (pendingSelectedSessionId == sessionId) pendingSelectedSessionId = null
            visibleAttachmentKeys = emptySet()
            pruneAttachmentStateForSession()
            // 切换后“可见上下文”变了，重算一次终态预览的保留集。
            refreshOutgoingPreviews()
            restoreStoppedQueueDraft(sessionId)
        }
        val appGraph = graph
        if (appGraph == null) {
            projectionActor.selectSessionImmediate(sessionId, afterPublish)
        } else {
            appGraph.gatewayScope.launch {
                projectionActor.selectSession(sessionId, afterPublish)
                if (trajectoryIsActive) publishTrajectory()
                if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                    appGraph.gatewayRuntime.subscribe(sessionId)
                    // 会话级清单：切会话即重拉（归属键防串）。
                    slashCommands = null
                    commandsLoading = false
                    refreshSlashCommands(sessionId)
                }
            }
        }
    }

    /**
     * 切到空白新会话页（本地导航）。[cwd] 非空表示从某个已有会话退出新建，
     * 沿用它所在的工作区，直到用户显式改选目录；为空（已在空白页再点「+」）
     * 则保留既有沿用值，不把刚定格的上下文退回成“猜最近会话目录”。
     */
    fun prepareNewSession(cwd: String? = null) {
        prepareComposerForNavigation(null, newPage = true)
        clearTreeActions()
        sessionControlsRefresh.reset()
        pendingSelectedSessionId = null
        pendingModelSelection = null
        if (cwd != null) {
            // 从已有会话新建是最新的目录意图，不能被更早的首页选择覆盖。
            selectedDirectoryPath = null
            pendingNewSessionCwd = cwd
        }
        // 未绑定 sessionId 的发送预览只属于发起时的那个新建对话页：重开新会话
        // 页换代后旧预览不再显示（回显匹配与移除不受显示过滤影响）。
        newSessionEpoch += 1
        val afterPublish = {
            visibleAttachmentKeys = emptySet()
            pruneAttachmentStateForSession()
            // 换代后旧未绑定预览不再可见，按新可见集回收一次。
            refreshOutgoingPreviews()
        }
        val appGraph = graph
        if (appGraph == null) projectionActor.selectSessionImmediate(null, afterPublish)
        else appGraph.gatewayScope.launch {
            projectionActor.selectSession(null, afterPublish)
            if (trajectoryIsActive) publishTrajectory()
            if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                appGraph.gatewayRuntime.subscribe(null)
                // 新建页的模型菜单依赖模型目录；home 阶段没拉到就补一次。
                if (snapshot.modelCatalog == null && supportsRequest("models")) {
                    appGraph.gatewayRuntime.sendRequest(GatewayRequests.models())
                }
            }
        }
    }

    fun beginDirectoryBrowsing(path: String? = null) {
        directoryPath = null
        directoryHome = null
        directoryCrumbs = emptyList()
        directoryEntries = emptyList()
        createdDirectoryPathToReveal = null
        browseDirectories(path)
    }

    fun browseDirectories(path: String? = null) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 Pi 网关"
            return
        }
        if (!supportsRequest("directories")) {
            platformError = "当前网关不支持目录浏览"
            return
        }
        directoryIsLoading = true
        directoryError = null
        requestedDirectoryPath = path
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.directories(path))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                directoryIsLoading = false
                platformError = "目录请求正在处理中，请稍后重试"
            }
        }
    }

    fun retryDirectoryBrowsing() {
        val path = requestedDirectoryPath
        clearPlatformError()
        browseDirectories(path)
    }

    fun createDirectory(parentPath: String, name: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 Pi 网关"
            return
        }
        if (!supportsRequest("directory-create")) {
            platformError = "当前网关不支持新建文件夹"
            return
        }
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            platformError = "文件夹名称不能为空"
            return
        }
        pendingDirectoryCreationParentPath = parentPath
        directoryCreationIsLoading = true
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(
                GatewayRequests.createDirectory(parentPath, normalizedName)
            )
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                pendingDirectoryCreationParentPath = null
                directoryCreationIsLoading = false
                platformError = "文件夹创建请求正在处理中，请稍后重试"
            }
        }
    }

    fun acknowledgeCreatedDirectoryReveal(path: String) {
        if (createdDirectoryPathToReveal == path) createdDirectoryPathToReveal = null
    }

    fun selectDirectory(path: String) {
        selectedDirectoryPath = path
    }

    /** 目录浏览位置即选择；回根同时清掉沿用值，防止显示未选却仍发送到旧目录。 */
    fun selectFineCatDirectory(path: String?) {
        selectedDirectoryPath = path?.takeIf { it.isNotBlank() && it != "/" }
        pendingNewSessionCwd = null
    }

    private suspend fun saveLocalPreference(
        preferences: GatewayPreferences,
        transform: (GatewayPreferencesSnapshot) -> GatewayPreferencesSnapshot,
    ) {
        try {
            preferences.updateFields(transform)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            withContext(Dispatchers.Main.immediate) { notifyUser("设置保存失败，请重试") }
        }
    }

    /** 保存外观模式；切换即时生效（组合内直接读 [appearanceMode]）。 */
    fun updateAppearanceMode(mode: String) {
        val appGraph = graph ?: return
        val normalized = when (mode) { "light", "dark" -> mode; else -> "system" }
        appearanceMode = normalized
        appGraph.gatewayScope.launch {
            saveLocalPreference(appGraph.preferences) { it.copy(appearanceMode = normalized) }
        }
    }

    /** 保存首页大标题；空白视为恢复内置默认（持久化为 null）。 */
    fun updateHeroTitle(title: String) {
        val appGraph = graph ?: return
        val value = title.trim().takeIf { it.isNotEmpty() }
        heroTitle = value
        appGraph.gatewayScope.launch {
            saveLocalPreference(appGraph.preferences) { it.copy(heroTitle = value) }
        }
    }

    /** 保存首页副标题标语；空白视为不显示（持久化为 null，副标题只剩版本号）。 */
    fun updateHeroTagline(tagline: String) {
        val appGraph = graph ?: return
        val value = tagline.trim().takeIf { it.isNotEmpty() }
        heroTagline = value
        appGraph.gatewayScope.launch {
            saveLocalPreference(appGraph.preferences) { it.copy(heroTagline = value) }
        }
    }

    /** FineCat编辑卡把两行文案一起落盘；收到结果前不丢弃草稿。 */
    fun saveHeroCopy(title: String, tagline: String, onResult: (String?) -> Unit) {
        val appGraph = graph
        if (appGraph == null) { onResult("应用尚未就绪，请重试"); return }
        // null表示尚未自定义；空字符串表示负责人明确删除，不恢复默认。
        val titleValue = title.trim()
        val taglineValue = tagline.trim()
        appGraph.gatewayScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = runCatching {
                appGraph.preferences.updateFields { it.copy(heroTitle = titleValue, heroTagline = taglineValue) }
            }.exceptionOrNull()
            withContext(Dispatchers.Main.immediate + NonCancellable) {
                if (failure == null) { heroTitle = titleValue; heroTagline = taglineValue }
                onResult(when (failure) {
                    null -> null
                    is CancellationException -> "保存未确认，请重新打开核对"
                    else -> "保存失败，请重试"
                })
            }
            if (failure is CancellationException) throw failure
        }
    }

    /** 只写手机偏好；置顶状态随持久化流更新，写失败不在界面留下假成功。 */
    fun togglePinnedSession(sessionId: String) {
        val appGraph = graph ?: return
        if (sessionId.isBlank()) return
        val pin = sessionId !in pinnedSessionIds
        appGraph.gatewayScope.launch {
            val failure = runCatching {
                appGraph.preferences.updateFields { current ->
                    current.copy(pinnedSessionIds = if (pin) (current.pinnedSessionIds + sessionId).distinct()
                        else current.pinnedSessionIds - sessionId)
                }
            }.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) throw failure
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    notifyUser("保存置顶失败，请重试")
                }
            }
        }
    }

    /**
     * 从首页列表隐藏会话（假删除）：只写本地黑名单，不删宿主机的 session 文件。
     * 恢复入口：[restoreHiddenSession]（单个）/ [restoreAllHiddenSessions]（全部）。
     */
    fun hideSession(sessionId: String) {
        val appGraph = graph ?: return
        if (sessionId.isBlank() || sessionId in hiddenSessionIds) return
        hiddenSessionIds = hiddenSessionIds + sessionId
        appGraph.gatewayScope.launch {
            // 事务内基于磁盘当前值合并，避免用调用时的快照覆盖并发写入。
            saveLocalPreference(appGraph.preferences) { current ->
                current.copy(hiddenSessionIds = (current.hiddenSessionIds + sessionId).distinct())
            }
        }
    }

    fun restoreHiddenSession(sessionId: String) {
        val appGraph = graph ?: return
        if (sessionId !in hiddenSessionIds) return
        hiddenSessionIds = hiddenSessionIds - sessionId
        appGraph.gatewayScope.launch {
            saveLocalPreference(appGraph.preferences) { current ->
                current.copy(hiddenSessionIds = current.hiddenSessionIds - sessionId)
            }
        }
    }

    fun restoreAllHiddenSessions() {
        val appGraph = graph ?: return
        if (hiddenSessionIds.isEmpty() && hiddenDirectoryPaths.isEmpty()) return
        hiddenSessionIds = emptyList()
        hiddenDirectoryPaths = emptyList()
        appGraph.gatewayScope.launch {
            saveLocalPreference(appGraph.preferences) {
                it.copy(hiddenSessionIds = emptyList(), hiddenDirectoryPaths = emptyList())
            }
        }
    }

    /**
     * 隐藏整个目录组（组标题左滑）：存完整 cwd，组内所有会话在列表隐藏。
     * 目录本身在宿主机不受影响，恢复入口与会话级相同（恢复对话框内分区展示）。
     */
    fun hideDirectory(directoryPath: String) {
        val appGraph = graph ?: return
        if (directoryPath.isBlank() || directoryPath in hiddenDirectoryPaths) return
        hiddenDirectoryPaths = hiddenDirectoryPaths + directoryPath
        appGraph.gatewayScope.launch {
            // 事务内合并，同 hideSession 的防并发考量。
            saveLocalPreference(appGraph.preferences) { current ->
                current.copy(
                    hiddenDirectoryPaths =
                        (current.hiddenDirectoryPaths + directoryPath).distinct()
                )
            }
        }
    }

    fun restoreHiddenDirectory(directoryPath: String) {
        val appGraph = graph ?: return
        if (directoryPath !in hiddenDirectoryPaths) return
        hiddenDirectoryPaths = hiddenDirectoryPaths - directoryPath
        appGraph.gatewayScope.launch {
            saveLocalPreference(appGraph.preferences) { current ->
                current.copy(hiddenDirectoryPaths = current.hiddenDirectoryPaths - directoryPath)
            }
        }
    }

    fun clearSelectedDirectory() {
        selectedDirectoryPath = null
    }

    /** Pi models-store.json 缓存的只读白名单；不用于编辑配置。 */
    var catalogProviders: List<com.finecat.pimobile.shared.protocol.GatewayCatalogProvider> by mutableStateOf(emptyList())
        private set
    var catalogError: String? by mutableStateOf(null)
        private set
    var modelScope: com.finecat.pimobile.shared.protocol.GatewayModelScope? by mutableStateOf(null)
        private set
    var modelScopeIsLoading: Boolean by mutableStateOf(false)
        private set
    var modelScopeLoadRevision: Long by mutableStateOf(0)
        private set
    var modelScopeIsSaving: Boolean by mutableStateOf(false)
        private set
    var modelScopeError: String? by mutableStateOf(null)
        private set
    var modelScopeSavedRequestId: String? by mutableStateOf(null)
        private set
    private var activeScopeRequestId: String? = null
    /** 设置页 provider 管理的凭证盲列表（Pi models.json 投影）。 */
    var providers: List<GatewayProvider> by mutableStateOf(emptyList())
        private set
    /** provider 列表/保存/删除的进行中标记（按操作区分，允许并行不同操作）。 */
    var providerListIsLoading: Boolean by mutableStateOf(false)
        private set
    var providerSaveIsLoading: Boolean by mutableStateOf(false)
        private set
    /** provider 连接测试进行中（表单按钮发起；保存不再自动测）。 */
    var providerTestIsLoading: Boolean by mutableStateOf(false)
        private set
    /** 最近一次连接测试的展示态（表单内联提示）；null = 尚未测试。 */
    var providerTestResult: ProviderTestOutcome? by mutableStateOf(null)
    var providerTestProgress: String? by mutableStateOf(null)
        private set
    var providerModelsIsLoading: Boolean by mutableStateOf(false)
        private set
    var providerModelsResult: ProviderModelsOutcome? by mutableStateOf(null)
        private set
    /** provider 操作的瞬时反馈文案（成功/失败），数秒后自动清除。 */
    var providerResult: String? by mutableStateOf(null)
        private set

    /**
     * `provider-put` 的**业务终态**，与 [providerResult]（瞬时提示）严格分开。
     *
     * 为什么必须分开：提示是单一全局槽位，任何 provider 系请求的终态都会写它
     * （列表刷新、连接测试、拉取模型、删除），而且带 4s 自动清除计时器。保存表单
     * 若拿它判成败，读请求的取消通知会在同一帧把保存成功覆盖掉——实际已保存却
     * 显示「请求已取消」且表单不收（2026-10-02 真实根入口下复现）。
     *
     * 本状态只由**本次在途 provider-put** 的终态写入，且**不受自动清除影响**：
     * 成功只认 `provider-saved` 帧，失败/未确认只认 `requestType == "provider-put"`
     * 的 rejected/cancelled/timedOut。读请求与 `provider-delete` 永远不写这里。
     */
    var providerSaveTerminal: ProviderSaveTerminal? by mutableStateOf(null)
        private set

    /**
     * 当前在途 `provider-put` 的本地标记。保存是单发的（[saveProvider] 开头就挡
     * 并发），所以一个 slot 就够；它随 [providerSaveTerminal] 一起发布，构成
     * 「这条终态解掉的就是我发的那次请求」这一**因果归属**，而不是靠 id 猜。
     *
     * 注意：网关的 `provider-saved` 帧不携带 `requestType`，`correlationId` 在
     * provider 系实际为 null，所以归属只能靠这个在途标记 + 帧内回显的 provider，
     * 不能指望线上有可用的请求 id。
     */
    private var inFlightSaveToken: String? = null
    private var saveTokenCounter: Long = 0

    /**
     * 发布一次 provider-put 的业务终态。`token` 取当前在途标记。
     *
     * **不在这里清标记**：标记表示「这条终态解掉的是哪一次保存」，只有下一次
     * [saveProvider] 才会替换它。提前清掉会让同一请求的**后续**终态（如网关补发的
     * 第二条 `provider-saved`）变成无归属，从而被表单忽略——真回执反而收不掉卡。
     */
    private fun publishSaveTerminal(
        status: ProviderSaveTerminal.Status,
        detail: String,
        echoed: GatewayProvider? = null,
    ) {
        providerSaveTerminal = ProviderSaveTerminal(
            providerId = echoed?.id ?: inFlightSaveId,
            status = status,
            detail = detail,
            echoed = echoed,
            token = inFlightSaveToken,
        )
    }

    /** 在途保存的目标 id（发布失败类终态时没有回显 provider，只能用它）。 */
    private var inFlightSaveId: String? = null

    fun refreshProductState() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.requestSessions()
            // 旧网关不宣告 models 时不发必败请求（supportsRequest 对空 capabilities 放行）。
            if (supportsRequest("models")) {
                appGraph.gatewayRuntime.sendRequest(GatewayRequests.models())
            }
            if (supportsRequest("providers")) {
                loadProviders()
            }
        }
    }

    /**
     * 打开模型菜单时重拉一次目录。models.json 可能被外部（桌面 pi / 编辑器）改过，
     * 而手机端只在少数时机请求 `models` 帧（进设置组合、新建页无缓存、provider
     * 保存/测试/删除后），菜单本身就只读缓存、没有 TTL——外部改了模型显示名会
     * 一直看到旧值（provider 页因为每次进设置都重拉，所以那边同步正常，用户实测
     * 就是这个不对称）。网关读前有 (mtime,size) 门控，文件没变只花一次 statSync。
     */
    fun refreshModelCatalog() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED || !supportsRequest("models")) return
        if (modelCatalogIsLoading) return
        modelCatalogIsLoading = true
        modelCatalogError = null
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.models())
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                finishModelCatalogFailure("models", GatewayErrorText.request("models", "send-failed"))
            }
        }
    }

    private fun finishModelCatalogFailure(requestType: String, message: String) {
        if (requestType != "models") return
        modelCatalogIsLoading = false
        modelCatalogError = message
    }

    fun loadModelScope() {
        val appGraph = graph ?: return
        if (modelScopeIsLoading || modelScopeIsSaving) return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED ||
            "model-scope-v1" !in gatewayState.capabilities
        ) {
            modelScopeError = "请连接支持模型范围编辑的新网关"
            return
        }
        modelScopeIsLoading = true
        modelScopeError = null
        appGraph.gatewayScope.launch {
            if (!appGraph.gatewayRuntime.sendRequest(GatewayRequests.modelScope())) {
                withContext(Dispatchers.Main.immediate) {
                    finishModelScopeFailure("model-scope", null, "请求未受理，请重试")
                }
            }
        }
    }

    fun saveModelScope(
        expectedPatterns: List<String>, models: List<GatewayModelSelection>,
        preserveUnmatched: Boolean, reset: Boolean = false,
    ): String? {
        val appGraph = graph ?: return null
        if (modelScopeIsSaving || modelScopeIsLoading) return null
        if (gatewayState.connection != GatewayConnectionState.CONNECTED ||
            "model-scope-v1" !in gatewayState.capabilities || (!reset && models.isEmpty())
        ) {
            modelScopeError = "请连接新网关并选择至少一个模型；恢复默认请使用单独按钮"
            return null
        }
        val token = java.util.UUID.randomUUID().toString()
        activeScopeRequestId = token
        modelScopeSavedRequestId = null
        modelScopeIsSaving = true
        modelScopeError = null
        appGraph.gatewayScope.launch {
            if (!appGraph.gatewayRuntime.sendRequest(
                    GatewayRequests.modelScopePut(token, expectedPatterns, models, preserveUnmatched, reset),
                )) {
                withContext(Dispatchers.Main.immediate) {
                    finishModelScopeFailure("model-scope-put", token, "请求未受理，请重试")
                }
            }
        }
        return token
    }

    private fun finishModelScopeFailure(requestType: String?, token: String?, detail: String) {
        if (requestType == "model-scope" && modelScopeIsLoading) {
            modelScopeIsLoading = false
            modelScopeError = detail
        } else if (requestType == "model-scope-put" && modelScopeIsSaving && token == activeScopeRequestId) {
            modelScopeIsSaving = false
            modelScopeError = detail
            activeScopeRequestId = null
        }
    }

    fun loadProviders() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        if (providerListIsLoading || !supportsRequest("providers")) return
        providerListIsLoading = true
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.providers())
            if (!accepted) withContext(Dispatchers.Main.immediate) { providerListIsLoading = false }
        }
    }

    /**
     * 表单提交：本地校验通过后发 provider-put。结果走两条独立通道——瞬时提示
     * [providerResult]（任何 provider 系请求都会写它）与保存业务终态
     * [providerSaveTerminal]（只由本次在途 provider-put 写，不受自动清除影响）。
     *
     * 返回本次请求的在途标记（供调用方归属终态）；**null = 没发出去**，调用方
     * 不必再挂等待标记。旧 UI 忽略返回值即可。
     *
     * [name] 是 provider 级显示名（可空）：缺省 null 保持旧调用方行为，网关对
     * 缺省字段的语义是保留原值，因此清空名字不可能通过本接口做到。
     */
    fun saveProvider(
        id: String,
        baseUrl: String?,
        apiKey: String?,
        models: List<ProviderModelUpsert>,
        name: String? = null,
    ): String? {
        val appGraph = graph ?: return null
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 Pi 网关"
            return null
        }
        if (providerSaveIsLoading) return null
        val trimmedName = name?.trim()?.takeIf(String::isNotEmpty)
        val message = runCatching {
            requireValidProviderUpsert(id, baseUrl, apiKey, models, trimmedName)
            null
        }.getOrElse { it.message ?: "表单校验失败" }
        if (message != null) {
            showProviderResult(message)
            // 本地没过：请求没发出去，也就没有在途保存可归属；只发瞬时提示，
            // 让表单自己的校验错误位负责（否则表单会一直等一个永不到来的终态）。
            return null
        }
        providerSaveIsLoading = true
        val token = "save-${++saveTokenCounter}"
        inFlightSaveToken = token
        inFlightSaveId = id.trim().takeIf(String::isNotBlank)
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(
                GatewayRequests.providerPut(
                    id = id.trim(),
                    baseUrl = baseUrl?.trim(),
                    apiKey = apiKey?.trim(),
                    models = models,
                    name = trimmedName,
                )
            )
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                providerSaveIsLoading = false
                val detail = "请求正在处理中，请稍后重试"
                showProviderResult(detail)
                // 发送被挡（如 lane 忙）：这次保存有归属地失败，表单应停等。
                publishSaveTerminal(ProviderSaveTerminal.Status.Rejected, detail)
            }
        }
        return token
    }

    fun deleteProvider(id: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.providerDelete(id))
        }
    }

    /**
     * 连接测试：用 pi 的方式真发一条最小对话请求（网关代发，网络环境与 pi 一致）。
     * `id` 非空时缺失字段由网关回退到 models.json 已存值——编辑场景不要求重填 apiKey。
     */
    fun testProvider(
        id: String? = null,
        baseUrl: String? = null,
        apiKey: String? = null,
        model: String? = null,
        api: String? = null
    ) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 Pi 网关"
            return
        }
        if (providerTestIsLoading) return
        providerTestIsLoading = true
        providerTestResult = null
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(
                GatewayRequests.providerTest(id, baseUrl, apiKey, model, api)
            )
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                providerTestIsLoading = false
                showProviderResult("请求正在处理中，请稍后重试")
            }
        }
    }

    /** FineCat批量测试只发既有单模型请求；草稿及凭据定格在点击时刻。 */
    internal fun testProviderModels(
        id: String?,
        baseUrl: String?,
        apiKey: String?,
        targets: List<ProviderTestTarget>,
    ) {
        val appGraph = graph ?: return
        val uiScope = scope ?: return
        if (providerTestIsLoading || targets.isEmpty()) return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            providerTestResult = ProviderTestOutcome(false, "请先连接 Pi 网关", id)
            return
        }
        val capturedTargets = targets.toList()
        val ownerEpoch = gatewayUiEpoch
        providerTestBatchActive = true
        providerTestIsLoading = true
        providerTestResult = null
        providerTestJob = uiScope.launch {
            try {
                val outcome = testProviderModelsUntilSuccess(
                    targets = capturedTargets,
                    onAttempt = { index, total, target ->
                        if (ownerEpoch != gatewayUiEpoch) throw CancellationException("gateway-replaced")
                        providerTestProgress = "测试 $index/$total · ${target.modelId ?: "默认模型"}"
                    },
                    probe = { target ->
                        val reply = CompletableDeferred<ProviderProbeReply>()
                        pendingProviderProbe = reply
                        providerProbeId = id
                        try {
                            // Runtime的30s lane超时及断连取消是主终态；这里给接线
                            // 自身补45s上限，避免异常事件丢失使表单永久等待。
                            withTimeoutOrNull(45_000) {
                                val accepted = withContext(Dispatchers.IO) {
                                    appGraph.gatewayRuntime.sendRequest(
                                        GatewayRequests.providerTest(
                                            id, baseUrl, apiKey, target.modelId, target.api,
                                            target.modelBaseUrl, target.modelBaseUrlSpecified,
                                        )
                                    )
                                }
                                if (accepted) reply.await() else ProviderProbeReply(
                                    ProviderTestOutcome(false, "请求未受理，请稍后重试", id), interrupted = true,
                                )
                            } ?: ProviderProbeReply(
                                ProviderTestOutcome(false, "连接测试超时，请重试", id), interrupted = true,
                            )
                        } finally {
                            if (pendingProviderProbe === reply) pendingProviderProbe = null
                        }
                    },
                )
                if (ownerEpoch != gatewayUiEpoch) return@launch
                providerTestResult = outcome
                if (supportsRequest("providers")) loadProviders()
                if (supportsRequest("models")) appGraph.gatewayScope.launch {
                    appGraph.gatewayRuntime.sendRequest(GatewayRequests.models())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 不把网络/平台异常原文放进长期显示的表单结果。
                if (ownerEpoch == gatewayUiEpoch) {
                    providerTestResult = ProviderTestOutcome(false, "连接测试未完成，请重试", id)
                }
            } finally {
                if (ownerEpoch == gatewayUiEpoch) {
                    pendingProviderProbe = null
                    providerProbeId = null
                    providerTestProgress = null
                    providerTestBatchActive = false
                    providerTestIsLoading = false
                }
            }
        }
    }

    private fun clearProviderRequestLoading(
        requestType: String? = null,
        testDetail: String = "连接测试被中断，请重新测试",
    ) {
        if (requestType == null || requestType == "providers") providerListIsLoading = false
        if (requestType == null || requestType == "provider-put") providerSaveIsLoading = false
        if (requestType == null || requestType == "provider-test") {
            if (providerTestBatchActive) pendingProviderProbe?.complete(
                ProviderProbeReply(ProviderTestOutcome(false, testDetail, providerProbeId), interrupted = true),
            ) else providerTestIsLoading = false
        }
        // 拉取上游 /models 恰是最容易慢/挂的请求：漏了这支会让 spinner 永久转、
        // 后续点击被 `if (providerModelsIsLoading) return` 静默丢弃（provider-test
        // 当初踩过同型的卡死，见 RequestCancelled 分支的注释）。
        if (requestType == null || requestType == "provider-models") providerModelsIsLoading = false
    }

    private fun showProviderResult(message: String) {
        providerResult = message
        appGraphResultAutoClearJob?.cancel()
        appGraphResultAutoClearJob = scope?.launch {
            delay(4_000)
            providerResult = null
        }
    }

    /** lane 挡回、瞬时未连接属于可自愈的预期内失败：给一条提示即可，不要弹"重新连接"模态窗。 */
    private fun isBenignRejection(reason: String?): Boolean =
        reason == "request-busy" || reason == "request-coalesced" || reason == "not-connected"

    /** switch-branch 服务端拒绝（稳定码）→ 用户文案；非请求级拒绝返回 null 落全局错误面。 */
    private fun switchBranchRejectionText(code: String?): String? = when (code) {
        "bad-request" -> SWITCH_REJECT_NOTICE
        "session-not-found" -> "会话已不可用，切换分支失败"
        "internal" -> "节点跳转失败，请重试"
        else -> null
    }

    private fun benignRejectionText(requestType: String?, reason: String?): String = when {
        requestType == "message" && reason == "not-connected" -> "网关未连接，草稿已保留"
        requestType == "message" -> "消息处理中，请稍后发送"
        reason == "not-connected" -> "网关未连接，正在重连"
        else -> "请求处理中，请稍后重试"
    }

    /**
     * 给用户看一句短暂提示（3.5s 自动消失）。UI 层在“动作完成了但无其它可见变化”时用，
     * 例如抽屉里长按组标题隐藏整个目录——列表整组消失后需要一句确认（审查 N4）。
     * 文案由调用方负责，不得包含密钥/凭据。
     */
    fun notifyUser(message: String) = showTransientNotice(message)

    private fun showTransientNotice(message: String) {
        transientNotice = message
        transientNoticeAutoClearJob?.cancel()
        transientNoticeAutoClearJob = scope?.launch {
            delay(3_500)
            transientNotice = null
        }
    }

    /**
     * 拉取上游模型清单。**走网关**：手机是凭证盲的，编辑已有 provider 时表单里
     * 没有 apiKey，直连上游必然 401（用户实测"测试连接能过、拉取模型报 401"——
     * 前者走网关、后者直连）。网关按"表单值优先、缺省回退 models.json 已存值"
     * 处理，用户不必为拉一次列表重新输入 Key。
     */
    /** 消费一次拉取结果（UI 读完即清，避免弹窗重开时重放旧文案）。 */
    fun consumeProviderModelsResult() {
        providerModelsResult = null
    }

    fun pullProviderModels(
        id: String? = null,
        baseUrl: String? = null,
        apiKey: String? = null,
        api: String? = null
    ) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 Pi 网关"
            return
        }
        if (providerModelsIsLoading) return
        providerModelsIsLoading = true
        providerModelsResult = null
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(
                GatewayRequests.providerModels(id, baseUrl, apiKey, api)
            )
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                providerModelsIsLoading = false
                providerModelsResult = ProviderModelsOutcome(
                    ok = false,
                    error = "请求正在处理中，请稍后重试",
                    providerId = id
                )
            }
        }
    }

    fun clearTransientNotice() {
        transientNoticeAutoClearJob?.cancel()
        transientNotice = null
    }

    /** 能力门控：capabilities 为空视为旧版网关（全放行）；非空则按
     * hello.capabilities 宣告决定，避免发出网关不认识的帧被回
     * unknown-command 触发全局弹窗。 */
    /** UI 层能力查询（顶栏改名/长按改名入口的可用性判断用）。 */
    fun supportsRequest(kind: String): Boolean {
        val capabilities = gatewayState.capabilities
        return capabilities.isEmpty() || kind in capabilities
    }

    fun pingGateway() {
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            pingResult = "未连接"
            return
        }
        val appGraph = graph ?: return
        if (pingStarted != null) return
        val id = java.util.UUID.randomUUID().toString()
        activePingId = id
        pingStarted = kotlin.time.TimeSource.Monotonic.markNow()
        pingResult = "测速中…"
        val wireId = id.takeIf { "ping-id" in gatewayState.capabilities }
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.ping(wireId))
            withContext(Dispatchers.Main.immediate) {
                if (!accepted && activePingId == id) finishPing("测速未能发送")
            }
        }
    }

    private fun finishPing(result: String) {
        pingStarted = null
        activePingId = null
        pingResult = result
    }

    fun reloadSelectedHistory() {
        val sessionId = snapshot.selectedSessionId ?: return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        // older 分页请求在途时重载会作废该请求：history lane 是 COALESCE_LATEST，
        // 重载排在 older 后面，但 clearSessionBuffers 已重置合成 seq 空间，迟到的
        // older 应答会以重置后的 seq 落进新 LATEST 批次，排在最新页之后（乱序，
        // 旧内容看起来"翻了页也没出现"）。先拒绝重载，等在途请求落地后再试。
        if (snapshot.selectedHistoryIsLoading) return
        appGraph.gatewayScope.launch {
            projectionActor.loadHistory(sessionId, older = false)
        }
    }

    fun loadOlderHistory() {
        val sessionId = snapshot.selectedSessionId ?: return
        snapshot.selectedHistoryNextCursor ?: return
        if (
            !snapshot.selectedHistoryHasMore ||
            snapshot.selectedHistoryIsLoading ||
            sessionId in historyPagingSessionIds
        ) return
        val appGraph = graph ?: return
        historyPagingSessionIds = historyPagingSessionIds + sessionId
        appGraph.gatewayScope.launch {
            projectionActor.loadHistory(sessionId, older = true)
        }
    }

    fun setTrajectoryActive(active: Boolean) {
        trajectoryIsActive = active
        if (active) graph?.gatewayScope?.launch { publishTrajectory() }
    }

    private suspend fun publishTrajectory() {
        val nodes = projectionActor.trajectory(snapshot.selectedSessionId)
        withContext(Dispatchers.Main.immediate) {
            if (trajectoryIsActive) trajectoryNodes = nodes
        }
    }

    /** 输入框斜杠命令路由结果。 */
    enum class SlashCommandOutcome { NOT_A_COMMAND, CONSUMED }

    /**
     * pi 内置斜杠命令的移动端路由。pi 的 18 个内置命令由 CLI TUI 编辑器拦截，
     * SDK `session.prompt()` 层不认识它们（会当文本发给模型）；mobile 在输入框
     * 消费有 UI 对等物或值得补的命令，其余已知命令提示暂不支持；未知 `/xxx`
     * 仍按文本发送，与 pi SDK 行为一致。
     */
    fun consumeSlashCommand(draft: String): SlashCommandOutcome {
        val trimmed = draft.trim()
        if (!trimmed.startsWith("/")) return SlashCommandOutcome.NOT_A_COMMAND
        val space = trimmed.indexOf(' ')
        val command = (if (space < 0) trimmed else trimmed.take(space)).lowercase()
        val argument = if (space < 0) "" else trimmed.substring(space + 1).trim()
        when (command) {
            "/name" -> {
                showTransientNotice("点顶部标题或长按会话列表即可改名")
                messageDraft = ""
                return SlashCommandOutcome.CONSUMED
            }
            "/compact" -> {
                // C07：/compact 引导直达真实动作（CTX02 接线）。压缩会中止当前
                // 回答（浮层按钮上方有说明行）；不满足条件时按原因分支给文案（M2），
                // 只有圆环路径确实可用时才指过去。
                if (snapshot.selectedSessionId == null) {
                    showTransientNotice("请先选择或新建一个会话，再压缩上下文")
                } else if (canStartManualCompact()) {
                    startManualCompact()
                } else if (selectedSessionCompacting() ||
                    snapshot.selectedSessionId in compactPendingSessions
                ) {
                    showTransientNotice("正在压缩上下文，请稍候")
                } else if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
                    showTransientNotice("尚未连接网关，请连接后再压缩")
                } else if (!supportsRequest("compact")) {
                    showTransientNotice("网关不支持手动压缩，请升级网关")
                } else if (selectedSessionRecalling() || selectedSessionStopping()) {
                    showTransientNotice("正在撤回或停止，请稍后再压缩")
                } else {
                    showTransientNotice("请点上下文占用圆环，从详情里压缩")
                }
                messageDraft = ""
                return SlashCommandOutcome.CONSUMED
            }
            "/model", "/thinking" -> {
                showTransientNotice("用输入框旁的模型名/档位标签切换")
                messageDraft = ""
                return SlashCommandOutcome.CONSUMED
            }
            "/copy" -> {
                showTransientNotice("每条消息自带复制按钮")
                messageDraft = ""
                return SlashCommandOutcome.CONSUMED
            }
            "/tree" -> {
                // pi /tree 是树选择器；手机端对应会话页顶部的树视图（§4.18/4.19）。
                showTransientNotice("点会话页顶部的「树」分段即可查看/切换分支")
                messageDraft = ""
                return SlashCommandOutcome.CONSUMED
            }
            "/fork", "/clone", "/export", "/import", "/share", "/session",
            "/scoped-models", "/trust", "/changelog", "/hotkeys", "/login", "/settings" -> {
                val hint = if (command == "/settings") "打开会话抽屉，点击设置图标" else "$command 暂不支持"
                showTransientNotice(hint)
                messageDraft = ""
                return SlashCommandOutcome.CONSUMED
            }
            else -> return SlashCommandOutcome.NOT_A_COMMAND
        }
    }

    /** 拉取自定义命令清单（PROTOCOL §4.21；会话选中/重连/restart 后刷新）。
     *  旧网关无此能力时静默跳过（slashCommands 保持 null，联想入口隐藏）。 */
    fun refreshSlashCommands(sessionId: String) {
        if (commandsLoading) return
        if (!supportsRequest("commands")) return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        commandsLoading = true
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.commands(sessionId))
        }
    }

    /** 拉取会话分支树（PROTOCOL §4.18；树页进入/切换后刷新）。 */
    var treeViewOptions: com.finecat.pimobile.shared.protocol.GatewayTreeView? by mutableStateOf(null)
        private set
    var treeSelectedId: String? by mutableStateOf(null)
        private set
    private var treeViewSessionId: String? = null

    fun loadSessionTree(view: com.finecat.pimobile.shared.protocol.GatewayTreeView? = null) {
        val sessionId = snapshot.selectedSessionId ?: return
        if (treeLoading) return
        if (!supportsRequest("tree")) {
            treeError = "网关不支持会话树，请升级网关"
            if (!treeUnsupportedNoticed) {
                treeUnsupportedNoticed = true
                showTransientNotice("网关不支持会话树，请升级网关")
            }
            return
        }
        val appGraph = graph ?: return
        treeLoading = true
        treeError = null
        treeViewOptions = view.takeIf { "tree-view-v1" in gatewayState.capabilities }
        treeViewSessionId = sessionId
        val requestedView = treeViewOptions
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.tree(sessionId, requestedView))
        }
    }

    /** 浏览节点不改草稿；明确跳转且会覆盖手写/附件时先确认。 */
    fun switchBranchTo(entryId: String) {
        val sessionId = snapshot.selectedSessionId?.takeIf { it == composerSessionId } ?: return
        if (branchNavigation != null) { showTransientNotice("正在跳转，请稍候"); return }
        val target = sessionTree?.takeIf { it.first == sessionId }?.third?.firstOrNull { it.id == entryId }
        // Pi只有user/custom_message返回editorText；assistant仅移动leaf，不改编辑器。
        val restoresEditor = target == null || target.type == "custom_message" ||
            (target.type == "message" && target.role == "user")
        if (!restoresEditor) {
            sendBranchNavigation(sessionId, entryId, replaceDraft = false)
            return
        }
        if (!canRestoreOutgoingDraft && !isUneditedBranchDraft()) {
            branchDraftConfirmation = BranchDraftConfirmation(sessionId, entryId, inputGeneration, composerNavigationEpoch)
            return
        }
        sendBranchNavigation(sessionId, entryId, replaceDraft = true)
    }

    internal fun dismissBranchDraftConfirmation() { branchDraftConfirmation = null }

    internal fun confirmBranchDraftNavigation(useNodeDraft: Boolean) {
        val confirmation = branchDraftConfirmation ?: return
        branchDraftConfirmation = null
        if (confirmation.sessionId != composerSessionId || confirmation.generation != inputGeneration ||
            confirmation.navigationEpoch != composerNavigationEpoch) {
            showTransientNotice("草稿已变化，请重新跳转")
            return
        }
        sendBranchNavigation(confirmation.sessionId, confirmation.entryId, useNodeDraft)
    }

    private fun sendBranchNavigation(sessionId: String, entryId: String, replaceDraft: Boolean) {
        val navigation = BranchNavigation(sessionId, entryId, inputGeneration, composerNavigationEpoch, replaceDraft)
        branchNavigation = navigation
        val appGraph = graph ?: return
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.switchBranch(sessionId, entryId))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                if (branchNavigation === navigation) branchNavigation = null
            }
        }
    }

    // -- 树页动作：整条 entry 复制 / 标签写（PROTOCOL §4.26–§4.27）-------------

    /** 复制结果：成功只给**整条 entry 的全文**；失败给可直接展示的稳定文案。
     *  网关超 512 KiB 时不回文本只回 oversized+bytes，这里如实转成失败文案，
     *  绝不把 preview 或截断内容当全文塞进剪贴板。 */
    sealed interface TreeCopyOutcome {
        data class Copied(val text: String) : TreeCopyOutcome
        data class Failed(val message: String) : TreeCopyOutcome
    }

    /** 标签写结果：Saved 带写入后的标签（null=已清除）；Failed 带可展示文案。 */
    sealed interface TreeLabelOutcome {
        data class Saved(val entryId: String, val label: String?) : TreeLabelOutcome
        data class Failed(val entryId: String, val message: String) : TreeLabelOutcome
    }

    private class PendingTreeCopy(val sessionId: String, val entryId: String) {
        var send: Job? = null
    }
    private class PendingTreeLabel(val sessionId: String, val entryId: String) {
        var send: Job? = null
    }

    // 快照状态：动作卡按 treeCopyInFlight/treeLabelInFlight 禁用按钮，
    // 置位/清除本身必须触发重组（普通字段不会让读取点失效，审查B major）。
    private var pendingTreeCopy: PendingTreeCopy? by mutableStateOf(null)
    private var pendingTreeLabel: PendingTreeLabel? by mutableStateOf(null)

    /**
     * 最近一次动作结果（UI 消费后调 consume* 清空）。
     *
     * 用发布状态而不是回调：写系统剪贴板必须在主线程，而帧收尾
     * （afterFramePublish）不保证在 Main 上；快照状态写入线程安全，由 UI 的
     * LaunchedEffect 在主线程消费。
     */
    var treeCopyOutcome: TreeCopyOutcome? by mutableStateOf(null)
        private set
    var treeLabelOutcome: TreeLabelOutcome? by mutableStateOf(null)
        private set

    fun consumeTreeCopyOutcome() {
        treeCopyOutcome = null
    }

    fun consumeTreeLabelOutcome() {
        treeLabelOutcome = null
    }

    /** 动作在途（复制/标签各一份）：动作卡据此禁用按钮，不重复提交。 */
    val treeCopyInFlight: Boolean get() = pendingTreeCopy != null
    val treeLabelInFlight: Boolean get() = pendingTreeLabel != null

    /** 树页动作是否可用：网关必须明确宣告 `tree-actions-v1`（复制+标签成对上线）。
     *  与 tree-view-v1 同口径，不沿用 supportsRequest 的“能力表为空即支持”放宽：
     *  旧网关上直接隐藏入口，而不是点了再报 unknown-command。 */
    val treeActionsSupported: Boolean
        get() = "tree-actions-v1" in gatewayState.capabilities

    internal fun beginTreeCopyRequest(sessionId: String, entryId: String) {
        check(pendingTreeCopy == null)
        treeCopyOutcome = null
        pendingTreeCopy = PendingTreeCopy(sessionId, entryId)
    }

    internal fun beginTreeLabelRequest(sessionId: String, entryId: String) {
        check(pendingTreeLabel == null)
        treeLabelOutcome = null
        pendingTreeLabel = PendingTreeLabel(sessionId, entryId)
    }

    /** 复制当前选中 entry 的可复制全文（Pi Ctrl+X）。
     *  迟到/错会话/错条目的应答不认领当前请求。 */
    fun copyTreeEntry(entryId: String) {
        val sessionId = snapshot.selectedSessionId
        val appGraph = graph
        when {
            pendingTreeCopy != null -> treeCopyOutcome = TreeCopyOutcome.Failed("上一次复制还没结束")
            entryId.isBlank() -> treeCopyOutcome = TreeCopyOutcome.Failed("没有选中的条目")
            sessionId == null -> treeCopyOutcome = TreeCopyOutcome.Failed("当前没有选中的会话")
            appGraph == null || gatewayState.connection != GatewayConnectionState.CONNECTED ->
                treeCopyOutcome = TreeCopyOutcome.Failed("请先连接 Pi 网关")
            !treeActionsSupported -> treeCopyOutcome = TreeCopyOutcome.Failed("网关不支持复制条目全文，请升级网关")
            else -> {
                beginTreeCopyRequest(sessionId, entryId)
                val pending = checkNotNull(pendingTreeCopy)
                val epoch = gatewayUiEpoch
                val send = appGraph.gatewayScope.launch(start = CoroutineStart.LAZY) {
                    val current = withContext(Dispatchers.Main.immediate) {
                        epoch == gatewayUiEpoch && pendingTreeCopy === pending && snapshot.selectedSessionId == sessionId
                    }
                    if (!current) return@launch
                    val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.entryCopy(sessionId, entryId))
                    if (!accepted) {
                        withContext(Dispatchers.Main.immediate) {
                            if (epoch == gatewayUiEpoch && pendingTreeCopy === pending) {
                                finishTreeCopy(sessionId, entryId, TreeCopyOutcome.Failed("复制请求未发出，请重试"))
                            }
                        }
                    }
                }
                pending.send = send
                send.start()
            }
        }
    }

    /** 写标签（Pi Shift+L）：`label` 为 null/空白即清除。成功后重拉会话树——追加
     *  LabelEntry 会推进叶子，树投影的当前行/选择必须由网关重算，客户端不自己猜。 */
    fun setTreeEntryLabel(entryId: String, label: String?) {
        val sessionId = snapshot.selectedSessionId
        val appGraph = graph
        val normalized = label?.trim()?.takeIf(String::isNotEmpty)
        when {
            pendingTreeLabel != null -> treeLabelOutcome =
                TreeLabelOutcome.Failed(entryId, "上一次标签保存还没结束")
            entryId.isBlank() -> treeLabelOutcome = TreeLabelOutcome.Failed(entryId, "没有选中的条目")
            sessionId == null -> treeLabelOutcome = TreeLabelOutcome.Failed(entryId, "当前没有选中的会话")
            appGraph == null || gatewayState.connection != GatewayConnectionState.CONNECTED ->
                treeLabelOutcome = TreeLabelOutcome.Failed(entryId, "请先连接 Pi 网关")
            !treeActionsSupported -> treeLabelOutcome =
                TreeLabelOutcome.Failed(entryId, "网关不支持标签，请升级网关")
            // 与网关 LABEL_MAX_CHARS 同口径：超长直接本地拒绝，不发一个必被
            // bad-request 挡回的请求，也不静默截断成另一个标签。
            (normalized?.length ?: 0) > TREE_LABEL_MAX_CHARS ->
                treeLabelOutcome = TreeLabelOutcome.Failed(entryId, "标签最多 $TREE_LABEL_MAX_CHARS 个字符")
            else -> {
                beginTreeLabelRequest(sessionId, entryId)
                val pending = checkNotNull(pendingTreeLabel)
                val epoch = gatewayUiEpoch
                val send = appGraph.gatewayScope.launch(start = CoroutineStart.LAZY) {
                    val current = withContext(Dispatchers.Main.immediate) {
                        epoch == gatewayUiEpoch && pendingTreeLabel === pending && snapshot.selectedSessionId == sessionId
                    }
                    if (!current) return@launch
                    val accepted = appGraph.gatewayRuntime.sendRequest(
                        GatewayRequests.labelPut(sessionId, entryId, normalized)
                    )
                    if (!accepted) {
                        withContext(Dispatchers.Main.immediate) {
                            if (epoch == gatewayUiEpoch && pendingTreeLabel === pending) {
                                finishTreeLabel(sessionId, entryId, "标签请求未发出，请重试")
                            }
                        }
                    }
                }
                pending.send = send
                send.start()
            }
        }
    }

    /** 结算在途复制：只有会话+条目都对得上才认领（迟到/错目标应答丢弃）。 */
    private fun finishTreeCopy(sessionId: String?, entryId: String?, outcome: TreeCopyOutcome): Boolean {
        val pending = pendingTreeCopy ?: return false
        if (pending.sessionId != sessionId || pending.entryId != entryId) return false
        pendingTreeCopy = null
        if (snapshot.selectedSessionId == pending.sessionId) treeCopyOutcome = outcome
        return true
    }

    /** 结算在途标签写（失败/中断）：message 是可直接展示的文案。 */
    private fun finishTreeLabel(sessionId: String?, entryId: String?, message: String): Boolean {
        val pending = settleTreeLabel(sessionId, entryId) ?: return false
        if (snapshot.selectedSessionId == pending.sessionId) treeLabelOutcome = TreeLabelOutcome.Failed(pending.entryId, message)
        return true
    }

    /** 结算在途标签写（成功）：`label` 取应答帧的权威值（null = 已清除），
     *  不用客户端输入框的文本冒充网关结果。 */
    private fun finishTreeLabelSaved(sessionId: String?, entryId: String?, label: String?): Boolean {
        val pending = settleTreeLabel(sessionId, entryId) ?: return false
        if (snapshot.selectedSessionId == pending.sessionId) treeLabelOutcome = TreeLabelOutcome.Saved(pending.entryId, label)
        return true
    }

    /** 只有会话+条目都对得上才认领在途标签写（迟到/错目标应答丢弃）。 */
    private fun settleTreeLabel(sessionId: String?, entryId: String?): PendingTreeLabel? {
        val pending = pendingTreeLabel ?: return null
        if (pending.sessionId != sessionId || pending.entryId != entryId) return null
        pendingTreeLabel = null
        return pending
    }

    /** 请求级失败（Runtime 把网关 error 帧转 RequestRejected，不再发布原帧）：
     *  事件只带 requestType/targetSessionId，因此按会话结算该类型的唯一在途请求。 */
    internal fun failTreeAction(requestType: String?, targetSessionId: String?, message: String) {
        if (requestType != "entry-copy" && requestType != "label-put") return
        val matches = { pendingSessionId: String -> targetSessionId == null || pendingSessionId == targetSessionId }
        if (requestType == "entry-copy") {
            pendingTreeCopy?.takeIf { matches(it.sessionId) }?.let {
                finishTreeCopy(it.sessionId, it.entryId, TreeCopyOutcome.Failed(message))
            }
        } else {
            pendingTreeLabel?.takeIf { matches(it.sessionId) }?.let {
                finishTreeLabel(it.sessionId, it.entryId, message)
            }
        }
    }

    /** 导航或改配后丢弃旧动作发布态，不把旧回执或剪贴板内容带到新页面。 */
    private fun clearTreeActions() {
        pendingTreeCopy?.send?.cancel()
        pendingTreeLabel?.send?.cancel()
        pendingTreeCopy = null
        pendingTreeLabel = null
        treeCopyOutcome = null
        treeLabelOutcome = null
    }

    /** 会话改名（顶部栏点击 / 会话列表长按共用）。
     *  网关 session-named 应答帧带 name，SharedMobileStore 刷新列表与标题。 */
    fun renameSession(sessionId: String, name: String) {
        if (name.isBlank()) return
        if (!supportsRequest("name")) {
            showTransientNotice("网关不支持会话改名，请升级网关")
            return
        }
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.sendRequest(
                    GatewayRequests.setSessionName(sessionId, name.take(200))
                )
            }
        }
    }

    /** FineCat保存/外点确认：本次目标会话与名称回执相符后才关闭编辑框。 */
    fun saveSessionName(sessionId: String, name: String, onResult: (String?) -> Unit) {
        val appGraph = graph
        when {
            pendingSessionName != null -> { onResult("改名仍在保存，请稍候"); return }
            appGraph == null || gatewayState.connection != GatewayConnectionState.CONNECTED -> {
                onResult("请先连接 Pi 网关"); return
            }
            !supportsRequest("name") -> { onResult("网关不支持会话改名，请升级网关"); return }
            name.isBlank() -> { onResult("请输入会话名"); return }
        }
        val pending = PendingSessionName(sessionId, name.trim().take(200), onResult)
        pendingSessionName = pending
        appGraph.gatewayScope.launch {
            if (!appGraph.gatewayRuntime.sendRequest(GatewayRequests.setSessionName(sessionId, pending.name))) {
                withContext(Dispatchers.Main.immediate) {
                    if (pendingSessionName === pending) finishSessionName("改名请求未受理，请重试")
                }
            }
        }
    }

    /** 圆环主入口。compact-started只表示受理，结束仍以Pi事件和最终state为准。
     *  请求身份保护迟到续体；压缩不清除当前草稿或附件。 */
    fun startManualCompact() {
        val sessionId = snapshot.selectedSessionId
        if (sessionId == null) {
            showTransientNotice("当前没有选中的会话")
            return
        }
        if (!supportsRequest("compact")) {
            showTransientNotice("网关不支持手动压缩，请升级网关")
            return
        }
        if (gatewayState.connection != GatewayConnectionState.CONNECTED && !compactGateForceOpenForTest) {
            showTransientNotice("尚未连接网关，请连接后重试")
            return
        }
        if (sessionId in compactPendingSessions) {
            showTransientNotice("压缩请求已发出，请稍候")
            return
        }
        if (selectedSessionCompacting()) {
            showTransientNotice("正在压缩上下文，请稍候")
            return
        }
        if (selectedSessionRecalling() || selectedSessionStopping()) {
            showTransientNotice("正在撤回或停止，请稍候")
            return
        }
        val pending = PendingCompact()
        compactPendingSessions[sessionId] = pending
        val appGraph = graph ?: run {
            compactRequestsForTest.add(sessionId)
            compactWatchScopeForTest?.let { watchCompactStartup(it, sessionId, pending) }
            return
        }
        val epoch = gatewayUiEpoch
        pending.send = appGraph.gatewayScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val current = withContext(Dispatchers.Main.immediate) {
                gatewayUiEpoch == epoch && compactPendingSessions[sessionId] === pending
            }
            if (!current) return@launch
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.compact(sessionId))
            withContext(Dispatchers.Main.immediate) {
                if (gatewayUiEpoch != epoch || compactPendingSessions[sessionId] !== pending) return@withContext
                if (!accepted) {
                    finishCompactPending(sessionId, "压缩请求未发出，请稍候重试")
                } else if (snapshot.sessionStates[sessionId]?.isCompacting == true) {
                    finishCompactPending(sessionId)
                } else {
                    watchCompactStartup(appGraph.gatewayScope, sessionId, pending)
                }
            }
        }
        pending.send?.start()
    }

    fun selectModel(provider: String, modelId: String, thinkingLevel: String?) {
        if (!supportsRequest("set-model")) return
        val sessionId = snapshot.selectedSessionId
        if (sessionId == null) {
            // 新建对话尚无会话可设：先记预选，随首个 message 帧一起发出（网关先
            // setModel 再 prompt），发送成功后消费。
            pendingModelSelection = GatewayModelSelection(provider, modelId, thinkingLevel)
            return
        }
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                val accepted = appGraph.gatewayRuntime.sendRequest(
                    GatewayRequests.setModel(sessionId, provider, modelId, thinkingLevel)
                )
                if (accepted) refreshSessionControls()
            }
        }
    }

    fun refreshSessionControls() {
        val sessionId = snapshot.selectedSessionId ?: return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            requestSessionControls(appGraph, sessionId)
        }
    }

    private suspend fun handleSentSession(appGraph: AndroidAppGraph, sessionId: String) {
        appGraph.gatewayRuntime.subscribe(sessionId)
        appGraph.gatewayRuntime.requestSessions()
        requestSessionControls(appGraph, sessionId)
    }

    private suspend fun requestSessionControls(appGraph: AndroidAppGraph, sessionId: String) {
        // pi 网关：模型/档位/上下文占用统一由 state 帧返回，无需额外的 sessionControl 流。
        appGraph.gatewayRuntime.requestState(sessionId)
    }

    fun reset() {
        prepareComposerForNavigation(null, newPage = true)
        clearTreeActions()
        sessionControlsRefresh.reset()
        advanceInputGeneration()
        pendingSelectedSessionId = null
        pendingModelSelection = null
        wirePayloadNormalizers.clear()
        outgoingPreviewTracker.clearAll()
        pendingExistingSessionTexts.clear()
        pendingComposerSends.clear()
        outgoingDraftRevisions.clear()
        stoppingSessionId = null
        pendingQueueRecall = null
        stoppedQueueDrafts.clear()
        clearPendingManualCompacts()
        compactSeenEvents.clear()
        compactRequestsForTest.clear()
        stateRequestsAfterCompactionForTest.clear()
        refreshOutgoingPreviews()
        val appGraph = graph
        if (appGraph == null) projectionActor.resetImmediate(::clearAttachmentUiState)
        else appGraph.gatewayScope.launch { projectionActor.reset(::clearAttachmentUiState) }
    }

    /** 只在收到真正的paired回执后丢弃旧网关派生状态；普通断线保留草稿。 */
    private fun clearPairedGatewayUiState() {
        gatewayUiEpoch += 1
        sessionDrafts.clear()
        imagePreparationJobs.values.forEach { it.cancel() }
        imagePreparationJobs.clear()
        imagePreparations = emptyMap()
        composerSessionId = null
        composerNavigationEpoch += 1
        branchNavigation = null
        branchDraftConfirmation = null
        advanceInputGeneration()
        modelCatalogIsLoading = false
        modelCatalogError = null
        pingStarted = null
        activePingId = null
        pingResult = null
        finishSessionName("网关已切换，请重新确认会话标题")
        sessionControlsRefresh.reset()
        providerTestJob?.cancel()
        providerTestJob = null
        providerTestBatchActive = false
        pendingProviderProbe = null
        providerProbeId = null
        clearProviderRequestLoading()
        providerTestResult = null
        providerTestProgress = null
        providerModelsResult = null
        providerSaveTerminal = null
        inFlightSaveToken = null
        providerResult = null
        appGraphResultAutoClearJob?.cancel()
        providers = emptyList()
        catalogProviders = emptyList()
        catalogError = null
        modelScope = null
        modelScopeIsLoading = false
        modelScopeIsSaving = false
        modelScopeError = null
        modelScopeSavedRequestId = null
        activeScopeRequestId = null
        providerEditorDrafts.clear()
        providerModelViewOrders.clear()
        pendingSelectedSessionId = null
        pendingModelSelection = null
        selectedDirectoryPath = null
        pendingNewSessionCwd = null
        requestedDirectoryPath = null
        directoryPath = null
        directoryHome = null
        directoryEntries = emptyList()
        directoryCrumbs = emptyList()
        directoryError = null
        createdDirectoryPathToReveal = null
        clearWorkspaceRequestLoading()
        sessionTree = null
        treeSelectedId = null
        treeLoading = false
        treeError = null
        treeViewSessionId = null
        treeViewOptions = null
        clearTreeActions()
        slashCommands = null
        commandsLoading = false
        inputAssist.reset(InputAssistContext())
        historyPagingSessionIds = emptySet()
        trajectoryNodes = emptyList()
        branchRestoredDraft = null
        wirePayloadNormalizers.clear()
        outgoingPreviewTracker.clearAll()
        pendingExistingSessionTexts.clear()
        pendingComposerSends.clear()
        outgoingDraftRevisions.clear()
        stoppingSessionId = null
        pendingQueueRecall = null
        stoppedQueueDrafts.clear()
        clearPendingManualCompacts()
        compactSeenEvents.clear()
        compactRequestsForTest.clear()
        stateRequestsAfterCompactionForTest.clear()
        refreshOutgoingPreviews()
        clearAttachmentUiState()
        platformError = null
        platformErrorAction = PlatformErrorAction.DISMISS_ONLY
    }

    private fun clearAttachmentUiState() {
        attachmentQueue.clear()
        activeAttachment = null
        visibleAttachmentKeys = emptySet()
        thumbnailCache.retainKeys(emptySet())
        attachmentStateCache.retainKeys(emptySet())
        attachmentThumbnails = emptyMap()
        attachmentStates = emptyMap()
    }

    fun connect() {
        val appGraph = graph ?: return
        appGraph.diagnostics.intent(GatewayDiagnosticAction.CONNECT)
        platformError = null
        val target = endpoint.trim()
        appGraph.gatewayScope.launch {
            val failed = runCatching { appGraph.gatewayRuntime.connect(target) }.isFailure
            if (failed) withContext(Dispatchers.Main.immediate) { showPlatformError("connect-failed") }
        }
    }

    fun pair() {
        pair(pairingPayload)
    }

    fun pair(
        payload: String,
        reportFailureGlobally: Boolean = true,
        onFailure: (String) -> Unit = {}
    ) {
        val appGraph = graph ?: return
        appGraph.diagnostics.intent(GatewayDiagnosticAction.PAIR)
        platformError = null
        appGraph.gatewayScope.launch {
            val result = runCatching { appGraph.gatewayRuntime.pair(payload) }
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    providerEditorDrafts.clear()
                    if (pairingPayload == payload) pairingPayload = ""
                    // 扫码/粘贴成功后给显式反馈：不然只有首页状态点隐式变绿，
                    // 用户会以为“扫了没反应”。连接结果仍由状态行呈现。
                    showTransientNotice("已提交配对，正在连接网关…")
                } else {
                    val message = if (result.exceptionOrNull() is GatewayPairingPayloadException) {
                        "配对信息无效"
                    } else {
                        "无法提交配对信息，请稍后重试。"
                    }
                    if (reportFailureGlobally) platformError = message
                    onFailure(message)
                }
            }
        }
    }

    /**
     * 把一个地址追加为入口（顺序即尝试优先级）。[candidate] 缺省取当前地址
     * （旧调用方语义）；UI 显式传值以保证「校验与提交同值」——holder.endpoint
     * 会被 prefs 快照回灌改写，不能既当输入缓冲又当提交源。
     */
    fun addEndpointCandidate(candidate: String = endpoint.trim(), onResult: ((String?) -> Unit)? = null) {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            if (onResult == null) platformError = "地址为空"
            onResult?.invoke("地址为空")
            return
        }
        if (trimmed in endpointCandidates) {
            if (onResult == null) platformError = "该地址已在列表里"
            onResult?.invoke("该地址已在列表里")
            return
        }
        submitEndpointCandidates(endpointCandidates + trimmed, onResult)
    }

    fun moveEndpointCandidateUp(index: Int) {
        if (index <= 0 || index !in endpointCandidates.indices) return
        val next = endpointCandidates.toMutableList().apply { add(index - 1, removeAt(index)) }
        submitEndpointCandidates(next)
    }

    fun removeEndpointCandidate(index: Int) {
        if (index !in endpointCandidates.indices) return
        submitEndpointCandidates(endpointCandidates.toMutableList().apply { removeAt(index) })
    }

    private fun submitEndpointCandidates(next: List<String>, onResult: ((String?) -> Unit)? = null) {
        val appGraph = graph
        if (appGraph == null) { onResult?.invoke("应用尚未就绪，请重试"); return }
        if (onResult == null) platformError = null
        appGraph.gatewayScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = runCatching { appGraph.gatewayRuntime.setEndpointCandidates(next) }.exceptionOrNull()
            withContext(Dispatchers.Main.immediate + NonCancellable) {
                val message = if (failure is CancellationException) "保存未确认，请重新打开核对"
                    else failure?.let { if (it is IllegalArgumentException) "网关地址无效" else "网关入口保存失败，请重试" }
                if (onResult == null && message != null) platformError = message
                onResult?.invoke(message)
            }
            if (failure is CancellationException) throw failure
        }
    }

    fun disconnect() {
        graph?.let { appGraph ->
            appGraph.diagnostics.intent(GatewayDiagnosticAction.DISCONNECT)
            appGraph.gatewayScope.launch { appGraph.gatewayRuntime.disconnect() }
        }
    }

    fun refreshSessions() {
        graph?.let { appGraph ->
            appGraph.diagnostics.intent(GatewayDiagnosticAction.REFRESH_SESSIONS)
            appGraph.gatewayScope.launch { appGraph.gatewayRuntime.requestSessions() }
        }
    }

    fun prepareImage(uri: Uri) {
        val appGraph = graph ?: return
        val key = uri.toString()
        val preparation = beginImagePreparation(key) ?: return
        appGraph.diagnostics.intent(GatewayDiagnosticAction.PREPARE_IMAGE)
        platformError = null
        val job = scope!!.launch {
            try {
                val image = appGraph.imagePreprocessor.prepare(uri)
                if (completeImagePreparation(key, preparation, image)) {
                    cacheAttachmentForEcho(appGraph, image)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (imagePreparations[key] == preparation) showPlatformError("image-preprocess-failed")
            } finally {
                finishImagePreparation(key, preparation)
                if (imagePreparationJobs[preparation] === coroutineContext[Job]) imagePreparationJobs.remove(preparation)
            }
        }
        if (imagePreparations[key] == preparation) imagePreparationJobs[preparation] = job
    }

    internal fun beginImagePreparation(key: String): Long? {
        if (key in preparedUris || key in imagePreparations) return null
        val token = ++imagePreparationSequence
        imagePreparations = imagePreparations + (key to token)
        advanceInputGeneration()
        return token
    }

    private fun imageFitsDraft(images: List<AndroidPreparedImage>, image: AndroidPreparedImage): Boolean =
        images.size + 1 <= AndroidImagePreprocessor.MAXIMUM_IMAGE_COUNT &&
            images.sumOf(AndroidPreparedImage::byteCount) + image.byteCount <= AndroidImagePreprocessor.MAXIMUM_TOTAL_BYTES &&
            images.sumOf { it.outgoing.base64Data.length } + image.outgoing.base64Data.length <= AndroidImagePreprocessor.MAXIMUM_TOTAL_BASE64_CHARACTERS

    internal fun completeImagePreparation(key: String, token: Long, image: AndroidPreparedImage): Boolean {
        if (imagePreparations[key] == token) {
            imagePreparations = imagePreparations - key
            if (key in preparedUris) return false
            if (!imageFitsDraft(preparedImages, image)) {
                showPlatformError("image-selection-limit-exceeded")
                return false
            }
            preparedImages = preparedImages + image
            preparedUris = preparedUris + key
            return true
        }
        // A选图后切B，完成结果只进入A保存的稿；同URI在B重选仍由独立token区分。
        val saved = sessionDrafts.entries.firstOrNull { it.value.preparations[key] == token } ?: return false
        val draft = saved.value
        val remaining = draft.preparations - key
        if (key in draft.uris || !imageFitsDraft(draft.images, image)) {
            sessionDrafts[saved.key] = draft.copy(preparations = remaining)
            return false
        }
        sessionDrafts[saved.key] = draft.copy(images = draft.images + image, uris = draft.uris + key,
            preparations = remaining, generation = ++nextInputGeneration)
        return true
    }

    private fun finishImagePreparation(key: String, token: Long) {
        if (imagePreparations[key] == token) imagePreparations = imagePreparations - key
        else sessionDrafts.entries.firstOrNull { it.value.preparations[key] == token }?.let {
            sessionDrafts[it.key] = it.value.copy(preparations = it.value.preparations - key)
        }
    }

    internal fun cancelImagePreparation(key: String) {
        val token = imagePreparations[key] ?: return
        imagePreparations = imagePreparations - key
        imagePreparationJobs.remove(token)?.cancel()
        advanceInputGeneration()
    }

    fun togglePreparedPhoto(uri: Uri) {
        val key = uri.toString()
        when {
            key in imagePreparations -> cancelImagePreparation(key)
            key in preparedUris -> {
                val indices = preparedUris.indices.filter { preparedUris[it] == key }.toSet()
                preparedImages = preparedImages.filterIndexed { index, _ -> index !in indices }
                preparedUris = preparedUris.filterIndexed { index, _ -> index !in indices }
            }
            else -> prepareImage(uri)
        }
    }

    private fun cacheAttachmentForEcho(appGraph: AndroidAppGraph, image: AndroidPreparedImage) {
        appGraph.gatewayScope.launch {
            val bytes = runCatching { Base64.decode(image.outgoing.base64Data, Base64.NO_WRAP) }.getOrNull()
            val written = bytes != null &&
                appGraph.gatewayRuntime.writeLocalAttachment(image.outgoing.attachmentId, bytes)
            appGraph.diagnostics.attachmentCacheWrite(image.outgoing.attachmentId, bytes?.size ?: 0, written)
        }
    }

    fun removePreparedImage(index: Int) {
        preparedImages = preparedImages.filterIndexed { itemIndex, _ -> itemIndex != index }
        preparedUris = preparedUris.filterIndexed { itemIndex, _ -> itemIndex != index }
    }

    // ---- 相册最近照片托盘 ----

    var photosTrayOpen: Boolean by mutableStateOf(false)
        private set

    /** 最近相册照片（新→旧）；打开托盘时重查，失败时为空列表。 */
    var recentPhotos: List<Uri> by mutableStateOf(emptyList())
        private set

    /** 缩略图按 uri 字符串索引，逐张就位。 */
    var recentPhotoThumbnails: Map<String, ImageBitmap> by mutableStateOf(emptyMap())
        private set

    private var recentPhotosJob: Job? = null

    private val recentPhotosLoader: RecentPhotosProvider? =
        recentPhotosProvider ?: graph?.recentPhotos

    /** 托盘查询复用主作用域；测试可注入 override 控制调度。 */
    private val recentPhotosScope: CoroutineScope? =
        recentPhotosScopeOverride ?: scope

    fun togglePhotosTray() {
        if (photosTrayOpen) {
            closePhotosTray()
            return
        }
        photosTrayOpen = true
        refreshRecentPhotos()
    }

    fun closePhotosTray() {
        photosTrayOpen = false
        recentPhotosJob?.cancel()
        recentPhotosJob = null
    }

    /** 每次打开都重查：截图/新照片要立刻出现在最前。 */
    private fun refreshRecentPhotos() {
        val loader = recentPhotosLoader ?: return
        val loaderScope = recentPhotosScope ?: return
        recentPhotosJob?.cancel()
        recentPhotosJob = loaderScope.launch {
            recentPhotos = try {
                loader.recentUris(RECENT_PHOTOS_LIMIT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                emptyList()
            }
            recentPhotoThumbnails = emptyMap()
            recentPhotos.forEach { uri ->
                val bitmap = try {
                    loader.thumbnail(uri, RECENT_PHOTO_THUMBNAIL_EDGE)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    null
                } ?: return@forEach
                recentPhotoThumbnails = recentPhotoThumbnails + (uri.toString() to bitmap)
            }
        }
    }

    fun sendMessage() {
        val appGraph = graph ?: return
        if (selectedSessionRecalling()) {
            showTransientNotice("正在撤回，请稍候")
            return
        }
        if (selectedSessionStopping()) {
            showTransientNotice("正在停止，请稍候")
            return
        }
        if (imagePreparations.isNotEmpty()) {
            showTransientNotice("图片正在处理中，请稍候再发送")
            return
        }
        // pi 原生（agent-session prompt 的首行守卫）：压缩进行中提交 prompt 直接拒绝
        //（"Cannot submit a prompt while compaction is in progress."）。移动端对齐
        // 拒绝语义但不把错误透传成弹窗：这里不发帧，草稿保留，输入框上方提示条解释。
        // 诊断：拦截走独立 action（不与真实发起混算转化率），imageCount 取草稿实况。
        if (selectedSessionCompacting()) {
            val draft = captureMessageSubmission()
            appGraph.diagnostics.intent(
                GatewayDiagnosticAction.SEND_MESSAGE_BLOCKED,
                hasSession = draft.sessionId != null,
                imageCount = draft.images.size
            )
            return
        }
        val submission = captureMessageSubmission()
        appGraph.diagnostics.intent(
            GatewayDiagnosticAction.SEND_MESSAGE,
            hasSession = submission.sessionId != null,
            imageCount = submission.images.size
        )
        // pi 语义：isStreaming 时 prompt 必须显式 streamingBehavior。移动端对齐：
        // 忙时点发送 → 草稿吸附到输入框上方，由用户选择 注入本轮/排队下轮。
        if (pendingComposerSends.isNotEmpty()) { showTransientNotice("上一条消息正在确认，草稿已保留"); return }
        if (selectedSessionBusy()) {
            capturePendingDispatch(submission)
            return
        }
        if (sendInFlight || dispatchInFlight != null || pendingComposerSends.isNotEmpty()) return
        sendInFlight = true
        // 仅空闲普通发送建立本地预览；忙时吸附派发（dispatchPending）保持原
        // 吸附条语义，不把排队消息伪装成已执行。
        appGraphSend(submission, mode = "followUp", clearInputOnSuccess = true, createPreview = true)
    }

    private fun appGraphSend(
        submission: MessageSubmission,
        mode: String,
        clearInputOnSuccess: Boolean,
        /** 仅空闲普通发送为带图提交建本地预览；吸附/排队路径不建。 */
        createPreview: Boolean,
        /** 发送被本地 Runtime 结束后回写（Main 线程）：接受=true、拒绝/发送失败=false。 */
        onSettled: (sent: Boolean) -> Unit = {}
    ) {
        val appGraph = graph ?: return
        // 这里是一切实发（新发/吸附派发）的唯一出口：记时刻给停止键防误触用。
        markPromptSent()
        // 本地预览与实发 id 在 Main 同步建立：先于 gatewayScope 排队、附件缓存写盘
        // 与任何网络/模型动作，点击即可见（对齐 Pi 原生 TUI 的 message_start(user) 展示）。
        // 实发图片复制一份并分配新 attachmentId：预览/发送/缓存用副本，原
        // submission.images 快照保持不动（清草稿比较仍与输入区对象相等）。
        val outgoing = beginOutgoingSend(submission, createPreview)
        rememberNewSessionTextForReject(submission, outgoing, createPreview, clearInputOnSuccess)
        appGraph.gatewayScope.launch {
            // 发送前把图片字节写入本地附件缓存：回显消息按 attachmentId 命中渲染
            //（网关出站预算裁掉 base64，wire 上只有占位）。新会话此刻没有
            // sessionId，用全局 attachmentId 作键（读取侧会话键 miss 后回退）。
            outgoing.images.forEach { image ->
                val bytes = runCatching { Base64.decode(image.outgoing.base64Data, Base64.NO_WRAP) }.getOrNull()
                val written = bytes != null &&
                    appGraph.gatewayRuntime.writeLocalAttachment(image.outgoing.attachmentId, bytes)
                appGraph.diagnostics.attachmentCacheWrite(image.outgoing.attachmentId, bytes?.size ?: 0, written)
            }
            // cwd / 模型预选用点击时的不可变快照（见 captureMessageSubmission）：
            // 缓存图片、排队等挂起期间用户换目录/换模型，不能改变这条消息的归宿。
            // 新会话的模型预选随帧携带：pi 在首轮前应用（网关 message 分支先
            // setModel 再 prompt），不再事后补 set-model。
            val sent = appGraph.gatewayRuntime.sendMessage(
                text = submission.draft,
                images = outgoing.images.map(AndroidPreparedImage::outgoing),
                sessionId = submission.sessionId,
                cwd = submission.cwd,
                model = submission.model,
                mode = mode
            )
            withContext(Dispatchers.Main.immediate) {
                settleOutgoingSendLocally(submission, outgoing, sent, clearInputOnSuccess)
                onSettled(sent)
            }
        }
    }

    /**
     * 发送回写（Main 线程，生产由 [appGraphSend] 的协程回调、测试作为边界
     * 直调）：本地结局按 token 权威收敛——拒绝/失败（sent=false）立即置 FAILED
     * 并移出等待队列；受理则留给 sent/timeout/error 帧按队列继续收敛。
     */
    internal fun settleOutgoingSendLocally(
        submission: MessageSubmission,
        outgoing: PreparedOutgoingSend,
        sent: Boolean,
        clearInputOnSuccess: Boolean
    ) {
        sendInFlight = false
        // 本地写入socket不等于网关受理；只有匹配sent回执才能清本次草稿。
        if (!clearInputOnSuccess || !sent) pendingComposerSends.remove(outgoing.sendToken)
        if (rejectedBeforeSettleToken == outgoing.sendToken) rejectedBeforeSettleToken = null
        if (!sent) {
            pendingExistingSessionTexts.remove(outgoing.sendToken)
            // 本地失败：草稿本就没清，不需要回填记录
            if (pendingNewSessionText?.token == outgoing.sendToken) pendingNewSessionText = null
            outgoingPreviewTracker.settleLocalFailure(outgoing.sendToken)?.let { failed ->
                refreshOutgoingPreviews()
                restoreOutgoingDraftPayload(failed)
            }
        }
    }

    internal fun applyMessageSendResult(sent: Boolean) {
        applyMessageSendResult(captureMessageSubmission(), sent)
    }

    internal fun captureMessageSubmissionForTest(): MessageSubmission = captureMessageSubmission()

    internal fun applyMessageSendResultForTest(submission: MessageSubmission, sent: Boolean, outgoingSendToken: String? = null) {
        applyMessageSendResult(submission, sent, outgoingSendToken)
    }

    /** 一次实发的载荷：原 submission 快照不动（清草稿比较用），实发图片是
     *  新 attachmentId 副本（预览/缓存/发送帧共用同一组 id，回显精确匹配）。
     *  sendToken 是请求身份（所有实发都分配，无预览也进等待队列）；
     *  images 为空=纯文本无预览。 */
    internal data class PreparedOutgoingSend(
        val sendToken: String,
        val submission: MessageSubmission,
        val images: List<AndroidPreparedImage>
    )

    /**
     * 实发前的 Main 同步步骤（生产由 [appGraphSend] 在 launch 前调用，测试
     * 直接驱动同一方法）：分配本次实发的请求 token，并为带图的普通发送建立
     * 本地预览（实发新 attachmentId）。预览不写共享 history/持久化会话；
     * 纯文本或吸附派发不建预览，但同样占用等待队列（请求身份，防纯文本
     * 在途时图请求的 sent 帧被串绑）。
     */
    internal fun prepareOutgoingSend(
        submission: MessageSubmission,
        createPreview: Boolean,
        sendToken: String
    ): PreparedOutgoingSend {
        if (!createPreview || submission.images.isEmpty()) {
            return PreparedOutgoingSend(sendToken, submission, submission.images)
        }
        val sentImages = submission.images.map { image ->
            image.copy(outgoing = image.outgoing.copy(attachmentId = nextOutgoingAttachmentId()))
        }
        outgoingPreviewTracker.begin(
            token = sendToken,
            sessionId = submission.sessionId,
            newSessionEpoch = newSessionEpoch,
            draftText = submission.draft,
            images = sentImages.map { image ->
                GatewayImageAttachment(
                    attachmentId = image.outgoing.attachmentId,
                    mediaType = image.outgoing.mimeType,
                    bytes = image.byteCount,
                    width = image.width,
                    height = image.height,
                    dataBase64 = image.outgoing.base64Data
                )
            },
            restoreImages = submission.images,
            restoreUris = submission.uris
        )
        outgoingDraftRevisions[sendToken] = submission
        refreshOutgoingPreviews()
        return PreparedOutgoingSend(sendToken, submission, sentImages)
    }

    private fun refreshOutgoingPreviews() {
        // 终态预览的载荷上限回收（每条都持整组 base64）；可见上下文最近两条不被误删。
        outgoingPreviewTracker.pruneRestorable(snapshot.selectedSessionId, newSessionEpoch)
        outgoingPreviews = outgoingPreviewTracker.previews
        outgoingDraftRevisions.keys.retainAll(outgoingPreviews.mapTo(mutableSetOf()) { it.sendToken })
    }

    /**
     * 实发同步段（生产 [appGraphSend] launch 前调用，测试注入发送边界后直调）：
     * 请求身份 token 总是分配并入等待队列（含纯文本/吸附派发，无预览），
     * 带图普通发送再建立预览；响应帧按实发顺序收敛，不串绑。
     */
    internal fun beginOutgoingSend(
        submission: MessageSubmission,
        createPreview: Boolean
    ): PreparedOutgoingSend {
        val token = outgoingPreviewTracker.newSendToken()
        val outgoing = prepareOutgoingSend(submission, createPreview, token)
        outgoingPreviewTracker.trackAwaiting(token, submission.sessionId)
        if (createPreview) pendingComposerSends[token] = submission
        // 既有会话纯文本没有占位气泡；只在发送等待期保留原稿，明确拒绝后
        // 复用失败恢复条。正常 sent 立即丢弃，不靠文字猜测正式回显身份。
        if (submission.images.isEmpty() && !submission.sessionId.isNullOrBlank()) {
            pendingExistingSessionTexts[token] = PendingExistingSessionText(submission, newSessionEpoch)
        }
        return outgoing
    }

    /**
     * sent 帧（网关 preflight 受理，PROTOCOL §4.2）：按在途请求队列的队头
     * token 确认预览并尽量绑定真实 sessionId（新会话在此得知归属）；预览
     * 已被 echo 先行移除时仍消费队头（防旧 sent 串绑后续请求），且此刻
     * 明确SDK受理时移除本次预览；旧网关无disposition才保留回显等待。
     */
    internal fun recordOutgoingSent(frameSessionId: String?, sdkAccepted: Boolean = false) {
        val head = outgoingPreviewTracker.headAwaitingToken()
        val changedPreview = outgoingPreviewTracker.consumeSent(frameSessionId, elapsedRealtimeMs(), sdkAccepted)
        // 队头被受理后只清它对应的那一版，用户后写的文字或其它会话不受影响。
        if (head != null && head != outgoingPreviewTracker.headAwaitingToken()) {
            pendingComposerSends.remove(head)?.let { applyMessageSendResult(it, sent = true, outgoingSendToken = head) }
            pendingExistingSessionTexts.remove(head)
            if (pendingNewSessionText?.token == head) pendingNewSessionText = null
        }
        if (changedPreview != null) {
            refreshOutgoingPreviews()
            if (changedPreview.state == OutgoingImagePreviewState.CONFIRMED) scheduleOutgoingUnconfirmedWatch()
        }
    }

    /**
     * 正式回显（user message_end 归一视图）：整组实发 attachmentId + 会话
     * 归属精确匹配命中即移除预览（不限状态：失败/未确认的预览也会被迟到
     * 的正式回显收走，避免双气泡），禁止文本/内容猜身份。生产由 events
     * collector 对每个 event 帧调用；测试作为发送边界注入同一真实方法。
     */
    internal fun handleOutgoingEcho(frameSessionId: String?, events: List<SessionEvent>) {
        var changed = false
        events.forEach { record ->
            if (record.event.type != "user/message") return@forEach
            val ids = record.event.images.orEmpty().map { it.attachmentId }
            if (ids.isEmpty()) return@forEach
            val removed = outgoingPreviewTracker.noteEcho(frameSessionId, ids) ?: return@forEach
            changed = true
            clearAutoRestoredDraftIfMatching(removed)
        }
        if (changed) refreshOutgoingPreviews()
    }

    private fun restoreBranchDraft(frame: GatewayFrame, hasQueuedText: Boolean) {
        val sessionId = frame.sessionId?.takeIf { it == composerSessionId && it == snapshot.selectedSessionId } ?: return
        val navigation = branchNavigation
        if (navigation != null && (navigation.sessionId != sessionId || navigation.entryId != frame.entryId)) return
        branchNavigation = null
        val isUneditedRestore = isUneditedBranchDraft()
        // 无请求的原始注入帧仅供旧测试/旧投影路径；生产必须属于本次明确导航。
        if (graph != null && navigation == null) return
        if (navigation != null && (!navigation.replaceDraft || navigation.generation != inputGeneration ||
                navigation.navigationEpoch != composerNavigationEpoch)) return
        if (navigation == null && frame.alreadyAt == true && (!isUneditedRestore ||
                frame.entryId.isNullOrBlank() || frame.entryId != frame.leafId)) return
        if (hasQueuedText || (navigation == null && !canRestoreOutgoingDraft && !isUneditedRestore)) return
        // 非user节点不返回editorText；历史与tree仅刷新视图，不再推断最近user回填。
        val editorText = frame.editorText ?: return
        commitBranchDraft(sessionId, editorText)
    }

    internal fun finishBranchDraftRequest(requestType: String?, sessionId: String?) {
        if (sessionId == null) return
        if (requestType == "switch-branch" && branchNavigation?.sessionId == sessionId) branchNavigation = null
    }

    private fun commitBranchDraft(sessionId: String, text: String) {
        if (composerSessionId != sessionId) return
        imagePreparations.values.forEach { imagePreparationJobs.remove(it)?.cancel() }
        imagePreparations = emptyMap()
        preparedImages = emptyList()
        preparedUris = emptyList()
        messageDraft = text
        advanceInputGeneration()
        autoRestoredDraft = null
        branchRestoredDraft = sessionId to inputGeneration
        closePhotosTray()
    }

    /**
     * history 帧的正式 USER 图片回显：归一事件恒空，从 frame.messages 原始
     * content 数组读整组 attachmentId（上游保留该可选字段时命中；SDK1.0.4
     * 图片归一可能移除，已知SDK受理不再依赖它）。与event同语义：精确匹配才清预览（不限
     * 状态），不按文本；entryIds 不是图片 ID。外部端发的图无 attachmentId，
     * 归一时回退合成占位 id（hist-*），永远不与实发 id 相等，不会误认领。
     */
    private fun handleHistoryEcho(frameSessionId: String?, frame: GatewayFrame) {
        if (outgoingPreviewTracker.previews.isEmpty()) return
        val echoes = frame.messages.orEmpty().mapIndexedNotNull { index, message ->
            if (message["role"]?.stringValue != "user") return@mapIndexedNotNull null
            val images = PiEventNormalizer.imageAttachmentsOf(message["content"], "hist-$index")
            if (images.isEmpty()) return@mapIndexedNotNull null
            SessionEvent(
                sessionId = frameSessionId.orEmpty(),
                seq = 0,
                time = 0.0,
                event = GatewayEvent(type = "user/message", source = "user", images = images)
            )
        }
        if (echoes.isNotEmpty()) handleOutgoingEcho(frameSessionId, echoes)
    }

    /** 命中迟到回显时，只在输入区正是回填的那一代（token+代次精确匹配）时
     *  清空——用户已重新编辑（代次已变）则不动，禁止靠内容比对判断。 */
    private fun clearAutoRestoredDraftIfMatching(preview: OutgoingImagePreview) {
        outgoingDraftRevisions.remove(preview.sendToken)?.let {
            applyMessageSendResult(it, sent = true, outgoingSendToken = preview.sendToken)
        }
        sessionDrafts.entries.firstOrNull {
            it.value.restoredSend == (preview.sendToken to it.value.generation)
        }?.let { sessionDrafts.remove(it.key) }
        val (token, generation) = autoRestoredDraft ?: return
        if (token != preview.sendToken || generation != inputGeneration ||
            !preview.isVisibleFor(composerSessionId, newSessionEpoch)) return
        autoRestoredDraft = null
        messageDraft = ""
        preparedImages = emptyList()
        preparedUris = emptyList()
    }

    /**
     * 请求超时/断连取消：只意味着未确认——请求可能已送达网关，不能显示
     * "肯定发送失败"。预览转 UNCONFIRMED（手动恢复入口，不自动重发），
     * 迟到的正式回显仍会收走它。
     */
    internal fun handleOutgoingSendUnconfirmed() {
        // 未确认≠失败（请求可能已达网关）：不自动回填纯文本草稿，避免重复发送
        val head = outgoingPreviewTracker.headAwaitingToken()
        val pendingText = head?.let { pendingExistingSessionTexts.remove(it) }
        if (head != null) pendingComposerSends.remove(head)
        if (head != null && pendingNewSessionText?.token == head) pendingNewSessionText = null
        val preview = outgoingPreviewTracker.consumeFailure(asUnconfirmed = true)
        if (head != null && pendingText != null) {
            outgoingPreviewTracker.begin(
                token = head,
                sessionId = pendingText.submission.sessionId,
                newSessionEpoch = pendingText.newSessionEpoch,
                draftText = pendingText.submission.draft,
                images = emptyList(),
                restoreImages = emptyList(),
                state = OutgoingImagePreviewState.UNCONFIRMED,
            )
        }
        if (preview != null || pendingText != null) {
            refreshOutgoingPreviews()
        }
    }

    /**
     * 服务端 error 帧（prompt-rejected 等，明确 preflight 拒绝）：预览转
     * FAILED（保留手动恢复入口），不自动重发。回填范围按负责人收窄（F10）：
     * 只回填新会话首条纯文本（[pendingNewSessionText]，DEC-F09-CWD「创建失败
     * 保留草稿」）；已有会话带图消息被拒**不**自动回填草稿——该扩展未经批准，
     * 自动回填仅保留在本地失败路径（settleLocalFailure）。生产入口是
     * Runtime 已关联的服务端 RequestRejected(message)，不按错误码白名单丢失新拒绝码。
     */
    internal fun handleOutgoingServerReject() {
        val head = outgoingPreviewTracker.headAwaitingToken() ?: return
        pendingComposerSends.remove(head)
        // 拒绝先于本地回写到达：回写不得再清空草稿（此刻草稿仍在输入框里）
        if (sendInFlight) rejectedBeforeSettleToken = head
        outgoingPreviewTracker.consumeFailure(asUnconfirmed = false)
        pendingExistingSessionTexts.remove(head)?.let { pending ->
            val submission = pending.submission
            // 拒绝先于本地回写且原稿仍在输入框时，不另建重复的恢复条。
            val stillInEditor = sendInFlight && inputGeneration == submission.generation &&
                (pendingSelectedSessionId ?: snapshot.selectedSessionId) == submission.sessionId &&
                messageDraft == submission.draft && preparedImages.isEmpty()
            if (!stillInEditor) outgoingPreviewTracker.begin(
                token = head,
                sessionId = submission.sessionId,
                newSessionEpoch = pending.newSessionEpoch,
                draftText = submission.draft,
                images = emptyList(),
                restoreImages = emptyList(),
                state = OutgoingImagePreviewState.FAILED,
            )
        }
        refreshOutgoingPreviews()
        restorePendingNewSessionText(head)
    }

    /** 来源由 Runtime 在关联成功后标记，避免本地 busy 拒绝误消费另一请求队头。 */
    internal fun handleOutgoingMessageRejection(event: GatewayRuntimeEvent.RequestRejected) {
        if (event.requestType == "message" && event.serverResponse) handleOutgoingServerReject()
    }

    private data class PendingExistingSessionText(val submission: MessageSubmission, val newSessionEpoch: Long)
    private val pendingExistingSessionTexts = mutableMapOf<String, PendingExistingSessionText>()
    private val pendingComposerSends = mutableStateMapOf<String, MessageSubmission>()
    private val outgoingDraftRevisions = mutableMapOf<String, MessageSubmission>()

    /**
     * 新会话首条纯文本没有预览可承载草稿：单独记一份，服务端明确拒绝时回填
     *（DEC-F09-CWD「创建失败保留草稿」）；受理/超时/本地失败即丢弃。生产由
     * [appGraphSend] 在 [beginOutgoingSend] 之后调用，测试在同一发送边界直调。
     */
    internal fun rememberNewSessionTextForReject(
        submission: MessageSubmission,
        outgoing: PreparedOutgoingSend,
        createPreview: Boolean,
        clearInputOnSuccess: Boolean,
    ) {
        if (createPreview && clearInputOnSuccess && submission.sessionId.isNullOrBlank() && outgoing.images.isEmpty()) {
            pendingNewSessionText =
                PendingNewSessionText(outgoing.sendToken, submission.draft, submission.model, newSessionEpoch)
        }
    }

    /**
     * 测试缝：模拟“已点发送、本地回写尚未回到 Main”的在途窗口（生产由 sendMessage 置位）。
     * 置位后必须经 [settleOutgoingSendLocally] 收尾（它复位 sendInFlight）；否则同一 holder
     * 上的 sendMessage/dispatchPending 会静默 no-op，后续断言可能因“什么都没发生”而误过。
     */
    internal fun setSendInFlightForTest(inFlight: Boolean) {
        sendInFlight = inFlight
    }

    /** 新会话首条纯文本被拒：仍停在同一新建页、输入区为空时回填原文与模型预选。 */
    private fun restorePendingNewSessionText(token: String) {
        val pending = pendingNewSessionText?.takeIf { it.token == token } ?: return
        pendingNewSessionText = null
        if (snapshot.selectedSessionId != null || pendingSelectedSessionId != null) return
        if (newSessionEpoch != pending.newSessionEpoch) return
        if (messageDraft.isNotBlank() || preparedImages.isNotEmpty()) return
        messageDraft = pending.draft
        if (pendingModelSelection == null) pendingModelSelection = pending.model
        advanceInputGeneration()
    }

    private data class PendingNewSessionText(
        val token: String,
        val draft: String,
        val model: GatewayModelSelection?,
        val newSessionEpoch: Long,
    )

    private var pendingNewSessionText: PendingNewSessionText? = null
    private var rejectedBeforeSettleToken: String? = null

    /** 旧网关无SDK disposition时，CONFIRMED仍无回显 → UNCONFIRMED（不判失败/不重发）。 */
    internal fun markStaleOutgoingPreviews(nowMs: Long) {
        if (outgoingPreviewTracker.markStaleUnconfirmed(nowMs, OUTGOING_UNCONFIRMED_AFTER_MS)) {
            refreshOutgoingPreviews()
        }
    }

    /** 仅旧网关的CONFIRMED回显等待计时；明确SDK受理不创建此观察。 */
    private fun scheduleOutgoingUnconfirmedWatch() {
        val holderScope = scope ?: return
        holderScope.launch {
            delay(OUTGOING_UNCONFIRMED_AFTER_MS)
            markStaleOutgoingPreviews(elapsedRealtimeMs())
        }
    }

    /** 手动恢复草稿（失败/未确认预览条上的按钮）：预览当前页面可见且输入区为空
     *  才消费执行，不覆盖发送后新编辑的草稿，也不把载荷校验失败时丢掉；恢复
     *  不触发重发，由用户再点发送。 */
    fun restoreOutgoingPreviewDraft(token: String) {
        val preview = outgoingPreviewTracker.peekRestorable(token) ?: return
        // 归属校验：预览对当前页面可见（会话一致，或未绑定时同一新建页代）。
        if (!preview.isVisibleFor(snapshot.selectedSessionId, newSessionEpoch)) return
        if (!canRestoreOutgoingDraft) return
        val restored = outgoingPreviewTracker.restoreDraft(token) ?: return
        refreshOutgoingPreviews()
        messageDraft = restored.draftText
        preparedImages = restored.restoreImages
        preparedUris = restored.restoreUris
        advanceInputGeneration()
        autoRestoredDraft = token to inputGeneration
    }

    internal val canRestoreOutgoingDraft: Boolean
        get() = messageDraft.isBlank() && preparedImages.isEmpty() &&
            preparedUris.isEmpty() && imagePreparations.isEmpty()

    /** 载荷回填（仅本地失败路径 [settleLocalFailure] 使用；服务端拒绝按 F10 收窄
     *  不自动回填，只保留 FAILED 预览的手动恢复）：只在预览仍对当前页面可见且
     *  输入区为空时执行——失败收敛可能晚于用户切换会话，不得把旧会话失败稿塞进
     *  新会话；记录 token+代次供迟到回显精确清除该代（禁止靠内容比对清新草稿）。 */
    private fun restoreOutgoingDraftPayload(preview: OutgoingImagePreview) {
        if (!preview.isVisibleFor(snapshot.selectedSessionId, newSessionEpoch)) return
        if (!canRestoreOutgoingDraft) return
        messageDraft = preview.draftText
        preparedImages = preview.restoreImages
        preparedUris = preview.restoreUris
        advanceInputGeneration()
        autoRestoredDraft = preview.sendToken to inputGeneration
    }

    /** 最近一次预览载荷回填的（token, inputGeneration）记录：迟到回显只清该代。 */
    private var autoRestoredDraft: Pair<String, Long>? = null

    internal val pendingDispatchInFlightForTest: MessageSubmission?
        get() = dispatchInFlight

    internal fun capturePendingDispatchForTest(submission: MessageSubmission) =
        capturePendingDispatch(submission)

    /** 直接驱动派发结果回写（生产由 appGraphSend 的协程回调触发）。 */
    internal fun settlePendingDispatchForTest(submission: MessageSubmission, sent: Boolean) {
        settlePendingDispatch(submission, sent)
    }

    private fun captureMessageSubmission(): MessageSubmission {
        val sessionId = pendingSelectedSessionId ?: snapshot.selectedSessionId
        // cwd / 新会话模型预选与文本图片一并定格在点击时刻：发送协程可能晚于这些
        // 状态变化才执行（gatewayScope 排队、图片缓存写盘），期间用户换目录或换
        // 模型，不能把原消息投到后来的上下文。
        val cwd = if (sessionId.isNullOrBlank()) newSessionDirectoryPath else null
        val model = if (sessionId == null) pendingModelSelection else null
        return MessageSubmission(
            generation = inputGeneration,
            sessionId = sessionId,
            draft = messageDraft,
            images = preparedImages.toList(),
            uris = preparedUris.toList(),
            cwd = cwd,
            model = model
        )
    }

    private fun applyMessageSendResult(submission: MessageSubmission, sent: Boolean, outgoingSendToken: String? = null) {
        if (outgoingSendToken != null && outgoingSendToken == rejectedBeforeSettleToken) {
            // 服务端已先行拒绝这次发送：保留输入区（草稿/图/模型预选）原样
            rejectedBeforeSettleToken = null
            return
        }
        if (!sent) return
        val currentSessionId = composerSessionId
        val saved = sessionDrafts[submission.sessionId]
        if (saved != null && saved.generation == submission.generation && saved.text == submission.draft &&
            saved.images == submission.images && saved.uris == submission.uris) {
            sessionDrafts.remove(submission.sessionId)
        }
        // sent 帧先到时 SharedMobileStore 会把新会话自动选中（selectedSessionId 从
        // null 变真实 id）：用本次实发预览的绑定归属区分"同次发送的自动绑定"与
        // 用户换会话——绑定一致照常清草稿，换会话/另起预览则保留新编辑。
        val sessionMatches =
            currentSessionId == submission.sessionId ||
                (
                    submission.sessionId.isNullOrBlank() &&
                        outgoingSendToken != null &&
                        currentSessionId != null &&
                        outgoingPreviewTracker.boundSessionId(outgoingSendToken) == currentSessionId
                    )
        if (
            inputGeneration != submission.generation ||
            messageDraft != submission.draft ||
            preparedImages != submission.images ||
            !sessionMatches
        ) {
            return
        }
        messageDraft = ""
        preparedImages = emptyList()
        preparedUris = emptyList()
        closePhotosTray()
        // 模型预选已随 message 帧被 pi 应用，消费掉——只消费点击时捕获的那次选择
        // （引用相等：selectModel 每次都建新实例）。发送途中用户改选（哪怕选回
        // 同值）都是新意图，属于下一条消息，旧的成功回调不能清掉它。
        if (submission.model != null && pendingModelSelection === submission.model) {
            pendingModelSelection = null
        }
        successfulMessageSendCount += 1
    }

    internal fun setPreparedImagesForTest(
        images: List<AndroidPreparedImage>,
        uris: List<String> = emptyList()
    ) {
        preparedImages = images
        preparedUris = uris
    }

    // ---- 忙时吸附派发（pi：streaming 时 prompt 必须显式 steer/followUp）----

    /** AI 回复中点发送时暂存的草稿快照；null 表示无吸附。UI 在输入框上方展示。 */
    internal var pendingDispatch: MessageSubmission? by mutableStateOf(null)
        private set

    /** 当前会话是否在等 AI 回复（pi 的 isStreaming；决定发送走吸附还是直发）。 */
    internal fun selectedSessionCompacting(): Boolean =
        (pendingSelectedSessionId ?: snapshot.selectedSessionId)?.let { sessionId ->
            snapshot.sessionStates[sessionId]?.isCompacting == true
        } == true

    private fun selectedSessionBusy(): Boolean =
        (pendingSelectedSessionId ?: snapshot.selectedSessionId)?.let { sessionId ->
            snapshot.sessionStates[sessionId]?.isStreaming == true
        } == true

    fun cancelPendingDispatch() {
        takeBackPendingDispatch(notifyWhenPreparing = true)
    }

    private fun takeBackPendingDispatch(notifyWhenPreparing: Boolean) {
        val pending = pendingDispatch ?: return
        if (dispatchInFlight === pending) return
        if (imagePreparations.isNotEmpty()) {
            if (notifyWhenPreparing) showTransientNotice("图片正在处理中，请稍候再取回")
            return
        }
        // 只在归属会话取回草稿；切换会话后保留待派发条，避免丢失或错填当前输入框。
        val currentSessionId = pendingSelectedSessionId ?: snapshot.selectedSessionId
        if (pending.sessionId != currentSessionId) return
        val current = captureMessageSubmission()
        val hasNewDraft = current.draft.isNotBlank() || current.images.isNotEmpty()
        // 新稿与待发稿交换，避免覆盖，也不把两份附件合并到超过发送上限。
        pendingDispatch = if (hasNewDraft) current else null
        messageDraft = pending.draft
        preparedImages = pending.images
        preparedUris = pending.uris
        advanceInputGeneration()
        if (hasNewDraft) showTransientNotice("已取回待发送内容，新草稿保留在待发送栏")
    }

    /** 吸附派发的在途快照：发送被本地 Runtime 接受前，同一份内容不二次派发。 */
    private var dispatchInFlight: MessageSubmission? by mutableStateOf(null)

    internal val pendingDispatchIsSending: Boolean
        get() = dispatchInFlight != null

    fun dispatchPending(mode: String) {
        if (sendInFlight || dispatchInFlight != null) return
        val pending = pendingDispatch ?: return
        if (pendingQueueRecall != null && pendingQueueRecall?.first == pending.sessionId) return
        if (stoppingSessionId != null && stoppingSessionId == pending.sessionId) return
        dispatchInFlight = pending
        graph?.diagnostics?.intent(
            GatewayDiagnosticAction.SEND_MESSAGE,
            hasSession = pending.sessionId != null,
            imageCount = pending.images.size
        )
        appGraphSend(pending, mode, clearInputOnSuccess = false, createPreview = false) { sent ->
            settlePendingDispatch(pending, sent)
        }
    }

    /** Main 上处理本地发送结果：成功才移除原条目，失败保留供重试；不改用户新输入。 */
    private fun settlePendingDispatch(submission: MessageSubmission, sent: Boolean) {
        if (dispatchInFlight !== submission) return
        dispatchInFlight = null
        if (sent && pendingDispatch === submission) pendingDispatch = null
    }

    private var stoppingSessionId: String? by mutableStateOf(null)
    private var pendingQueueRecall: Pair<String, String>? by mutableStateOf(null)
    private val stoppedQueueDrafts = mutableMapOf<String, String>()

    private fun selectedSessionRecalling(): Boolean = pendingQueueRecall?.first != null &&
        pendingQueueRecall?.first == (pendingSelectedSessionId ?: snapshot.selectedSessionId)

    val canRecallQueuedMessages: Boolean
        get() = gatewayState.connection == GatewayConnectionState.CONNECTED &&
            "dequeue" in gatewayState.capabilities && pendingQueueRecall == null && stoppingSessionId == null &&
            !sendInFlight && dispatchInFlight == null &&
            snapshot.selectedSessionId?.let { snapshot.sessionStates[it]?.queuedMessages?.isEmpty == false } == true

    internal fun beginQueueRecall(sessionId: String, requestId: String): Boolean {
        if (pendingQueueRecall != null || stoppingSessionId != null || sendInFlight || dispatchInFlight != null) return false
        pendingQueueRecall = sessionId to requestId
        return true
    }

    internal fun finishQueueRecall(sessionId: String?, requestId: String?) {
        val pending = pendingQueueRecall ?: return
        if (pending.first == sessionId && (requestId == null || pending.second == requestId)) pendingQueueRecall = null
    }

    fun recallQueuedMessages() {
        val appGraph = graph ?: return
        if (!canRecallQueuedMessages) return
        val sessionId = snapshot.selectedSessionId ?: return
        val requestId = UUID.randomUUID().toString()
        if (!beginQueueRecall(sessionId, requestId)) return
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.dequeue(sessionId, requestId))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                finishQueueRecall(sessionId, requestId)
                showTransientNotice("撤回请求未发出，请重试")
            }
        }
    }

    private fun handleRecalledQueue(frame: GatewayFrame) {
        val sessionId = frame.sessionId ?: return
        if (pendingQueueRecall != (sessionId to frame.queueRequestId)) return
        finishQueueRecall(sessionId, frame.queueRequestId)
        val returned = frame.queued ?: return
        if (returned.isEmpty()) showTransientNotice("没有可撤回的消息")
        restoreReturnedQueueText(sessionId, returned)
    }

    private fun selectedSessionStopping(): Boolean = stoppingSessionId != null &&
        stoppingSessionId == (pendingSelectedSessionId ?: snapshot.selectedSessionId)

    /** 停止等待期不再向同一会话提交，避免旧停止回执覆盖新的运行。 */
    internal fun beginStoppingSession(sessionId: String): Boolean {
        if (stoppingSessionId != null || pendingQueueRecall?.first == sessionId) return false
        stoppingSessionId = sessionId
        return true
    }

    internal fun finishStoppingSession(sessionId: String?) {
        if (stoppingSessionId == sessionId) stoppingSessionId = null
    }

    private fun restoreStoppedQueueDraft(sessionId: String) {
        if ((pendingSelectedSessionId ?: snapshot.selectedSessionId) != sessionId) return
        val text = stoppedQueueDrafts.remove(sessionId) ?: return
        // Pi TUI Esc 将取回的文本放在当前编辑内容前；已有附件及处理中图片保持原样。
        messageDraft = listOf(text, messageDraft).filter(String::isNotBlank).joinToString("\n\n")
        advanceInputGeneration()
    }

    private fun handleStoppedQueue(frame: GatewayFrame) {
        val sessionId = frame.sessionId ?: return
        if (stoppingSessionId != sessionId) return
        finishStoppingSession(sessionId)
        val returned = frame.queued ?: return // 旧网关不证明已撤队列，不从快照猜恢复。
        restoreReturnedQueueText(sessionId, returned)
    }

    private fun restoreReturnedQueueText(sessionId: String, returned: List<String>) {
        val text = returned.filter(String::isNotBlank).joinToString("\n\n")
        if (text.isBlank()) return
        stoppedQueueDrafts[sessionId] = listOfNotNull(stoppedQueueDrafts[sessionId], text).joinToString("\n\n")
        restoreStoppedQueueDraft(sessionId)
    }

    /** Pi CLI Esc 的 clearQueue + abort；已派发文本仅凭网关确认取回，不靠旧快照。 */
    fun abortCurrent() {
        val appGraph = graph ?: return
        // 同位按钮防误触：发送后极短窗口内到达的"停止"多半是连点的第二下
        // （见 stopCooldownMs），忽略它。语义上等价于 pi 里草稿为空时按 Enter
        // 无事发生（此时草稿本就为空，本来也没有可发送的内容）。
        if (stopInCooldown()) return
        if (selectedSessionRecalling()) {
            showTransientNotice("正在撤回，请稍候")
            return
        }
        // 与 Composer 的 canAbort 同口径（snapshot.selectedSessionId）：停止针对
        // 当前显示的会话，不用 pending 切换目标，避免切换在途时 abort 发错会话。
        val sessionId = snapshot.selectedSessionId ?: return
        if (!beginStoppingSession(sessionId)) return
        takeBackPendingDispatch(notifyWhenPreparing = false)
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.abort(sessionId))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                finishStoppingSession(sessionId)
                platformError = "停止请求未发出，请重试"
            }
        }
    }

    /** 忙时点发送：暂存为吸附条并清空输入区（文本移入吸附，对齐 pi 的取走语义）。 */
    private fun capturePendingDispatch(submission: MessageSubmission) {
        // 发送协程可能正在缓存图片或等 Runtime 锁：保留旧条目，也保留新编辑的内容。
        if (dispatchInFlight != null) return
        // 已有吸附时旧内容放回编辑框，新内容顶替（简化：只保一条待派发）。
        // 无旧吸附才清输入区：旧内容刚放回就清等于直接丢弃（A 文本+图片消失只剩 B）。
        val previous = pendingDispatch
        // 跨会话不交换草稿：原条目继续保留，当前文字/图片仍留在当前编辑器。
        if (previous != null && previous.sessionId != submission.sessionId) return
        pendingDispatch = submission
        if (previous == null) {
            messageDraft = ""
            preparedImages = emptyList()
            preparedUris = emptyList()
        } else {
            messageDraft = previous.draft
            preparedImages = previous.images
            preparedUris = previous.uris
        }
        closePhotosTray()
        advanceInputGeneration()
    }

    internal fun failAttachmentForTest(attachmentId: String) {
        val key = cacheKeyForCurrentSession(attachmentId) ?: return
        removeQueuedAttachment(key)
        markAttachmentFailed(key)
    }

    internal fun queuedAttachmentIdsForTest(): List<String> =
        attachmentQueue.map(AttachmentRequest::attachmentId)

    /** 排队取图请求的完整寻址（attachmentId + §4.22 三元引用），供可见串行/切会话测试。 */
    internal data class QueuedAttachmentForTest(
        val attachmentId: String,
        val sessionId: String,
        val entryId: String?,
        val imageIndex: Int?
    )

    internal fun queuedAttachmentRequestsForTest(): List<QueuedAttachmentForTest> =
        attachmentQueue.map {
            QueuedAttachmentForTest(it.attachmentId, it.sessionId, it.entryId, it.imageIndex)
        }

    internal fun commitVisibleThumbnailForTest(
        sessionId: String,
        attachmentId: String,
        bitmap: ImageBitmap
    ) {
        val key = gatewayAttachmentCacheKey(sessionId, attachmentId)
        visibleAttachmentKeys += key
        commitThumbnail(key, bitmap)
    }

    internal val thumbnailCacheWeightForTest: Long
        get() = thumbnailCache.currentWeight()

    fun clearPlatformError() {
        platformError = null
        platformErrorAction = PlatformErrorAction.DISMISS_ONLY
    }

    fun showPlatformError(message: String) {
        platformError = GatewayErrorText.local(message)
        if (GatewayErrorText.isConnectionFailure(message)) platformErrorAction = PlatformErrorAction.RECONNECT
    }

    fun updateVisibleAttachments(attachmentIds: Set<String>) {
        val selectedSessionId = snapshot.selectedSessionId ?: return
        val sessionIds = currentSessionAttachmentIds()
        visibleAttachmentKeys = (attachmentIds intersect sessionIds).mapTo(mutableSetOf()) {
            gatewayAttachmentCacheKey(selectedSessionId, it)
        }
        pruneAttachmentStateForSession()
        visibleAttachmentKeys.forEach { cacheKey ->
            val attachmentId = attachmentIdFromCacheKey(cacheKey)
            val thumbnail = thumbnailCache.get(cacheKey)
            val state = attachmentStateCache.get(cacheKey)
            if (thumbnail == null && state == AttachmentLoadState.LOADED) {
                attachmentStateCache.put(cacheKey, AttachmentLoadState.DEFERRED)
            }
            if (
                thumbnail == null &&
                attachmentStateCache.get(cacheKey) !in setOf(
                    AttachmentLoadState.LOADING,
                    AttachmentLoadState.FAILED,
                    AttachmentLoadState.DEFERRED
                ) &&
                activeAttachment?.cacheKey != cacheKey &&
                attachmentQueue.none { it.cacheKey == cacheKey }
            ) {
                attachmentStateCache.put(cacheKey, AttachmentLoadState.LOADING)
                // §4.22 取图引用（entryId+序号）在 Main 入队时从当前消息快照捕获，
                // 挂起后不再读新选择（换会话后旧结果只进缓存不发布）。
                val reference = attachmentReference(attachmentId)
                attachmentQueue.addLast(
                    AttachmentRequest(
                        selectedSessionId,
                        attachmentId,
                        cacheKey,
                        entryId = reference?.entryId,
                        imageIndex = reference?.imageIndex
                    )
                )
            }
        }
        publishAttachmentState()
        drainAttachmentQueue()
    }

    fun retryAttachment(attachmentId: String) {
        val key = cacheKeyForCurrentSession(attachmentId) ?: return
        if (key !in visibleAttachmentKeys) return
        graph?.diagnostics?.intent(GatewayDiagnosticAction.RETRY_ATTACHMENT, hasSession = true)
        attachmentStateCache.put(key, AttachmentLoadState.IDLE)
        updateVisibleAttachments(visibleAttachmentKeys.mapTo(mutableSetOf(), ::attachmentIdFromCacheKey))
    }

    /** 全屏查看：先读会话级缓存；miss 且消息带完整 §4.22 引用时按需取回原图。
     * 与缩略图同一份 runtime 缓存（会话级键），失败返回 null 不循环重试。 */
    suspend fun readFullAttachment(sessionId: String, attachment: GatewayImageAttachment): ByteArray? {
        val appGraph = graph ?: return null
        appGraph.gatewayRuntime.readCachedAttachment(sessionId, attachment.attachmentId)?.let { return it }
        val entryId = attachment.entryId ?: return null
        val imageIndex = attachment.imageIndex ?: return null
        return appGraph.gatewayRuntime.fetchAttachment(sessionId, entryId, imageIndex, attachment.attachmentId)
    }

    fun updateThumbnailTargetSize(widthPixels: Int, heightPixels: Int) {
        if (widthPixels > 0) thumbnailTargetWidthPixels = widthPixels
        if (heightPixels > 0) thumbnailTargetHeightPixels = heightPixels
    }

    fun close() {
        clearTreeActions()
        streamingSnapshotPublishJob?.cancel()
        projectionActor.close()
        runtimeCollection?.cancel()
        scope?.cancel()
    }

    val canSend: Boolean
        get() = gatewayState.connection == GatewayConnectionState.CONNECTED &&
            !selectedSessionStopping() && !selectedSessionRecalling() &&
            (messageDraft.isNotBlank() || preparedImages.isNotEmpty()) && !sendInFlight && dispatchInFlight == null && pendingComposerSends.isEmpty()

    fun sendComposerDraft(requireDirectory: Boolean = false) {
        if (consumeSlashCommand(messageDraft) != SlashCommandOutcome.NOT_A_COMMAND) return
        if (messageDraft.isBlank() && preparedImages.isEmpty() && imagePreparations.isEmpty()) return
        if (requireDirectory && snapshot.selectedSessionId == null && fineCatNewSessionDirectoryPath == null) {
            showTransientNotice("先在输入框选择工作目录，草稿已保留")
            return
        }
        when {
            selectedSessionRecalling() -> showTransientNotice("正在撤回，请稍候")
            selectedSessionStopping() -> showTransientNotice("正在停止，请稍候")
            gatewayState.connection != GatewayConnectionState.CONNECTED -> showTransientNotice("尚未连接网关，草稿已保留，请连接后重试")
            imagePreparations.isNotEmpty() -> showTransientNotice("图片正在处理中，请稍候再发送")
            sendInFlight || dispatchInFlight != null || pendingComposerSends.isNotEmpty() -> showTransientNotice("上一条消息正在提交，草稿已保留")
            else -> sendMessage()
        }
    }

    /** 发送在途标记：草稿等回包才清，快速双击只发一次（发完在 Main 清）。 */
    private var sendInFlight by mutableStateOf(false)

    /**
     * 发送后禁止停止的窗口。发送键与停止键同位（主流 APP 形态：同一个按钮
     * 随状态换形态），run 一开始按钮就从 ↑ 变 ■，发送后连点的第二下会落到
     * 停止上，把刚发出的消息自己中止——实测发图后 87ms 被 abort，图已进 pi
     * 但用户收不到任何回复（停不下来也不报错）。pi TUI 的 Enter/Esc 是不同
     * 键，不存在这个风险；触摸屏同位切换需要这层防抖。500ms 覆盖双击/连点
     * 间隔，不影响真停止（用户真要点停止时早过窗口了）。
     */
    private val stopCooldownMs = 500L

    /** 单调时钟（纯 JVM 单测里 SystemClock 未 mock，需可注入；与 stopCooldownMs 配合）。 */
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    /** 最近一次真正发起 prompt 的时刻（elapsedRealtime），见 stopCooldownMs。 */
    private var lastPromptSentAtMs = 0L

    /** 记录一次实发（appGraphSend 调用；测试可用它构造冷却窗口）。 */
    internal fun markPromptSent() {
        lastPromptSentAtMs = elapsedRealtimeMs()
    }

    /** 停止键是否落在发送后的防误触窗口内（见 stopCooldownMs）。 */
    internal fun stopInCooldown(nowMs: Long = elapsedRealtimeMs()): Boolean =
        nowMs - lastPromptSentAtMs < stopCooldownMs

    /**
     * KMP 继续无损消费每个 token；Compose 只按稳定显示节奏接收最新快照。
     * 仅 assistant/chunk 可以合并；final、history、切换 session 等结构变化立即提交。
     */
    private fun publishProjectionSnapshot(
        next: SharedMobileSnapshot,
        coalesceWithDisplayFrame: Boolean
    ) {
        val holderScope = scope
        if (holderScope == null || !coalesceWithDisplayFrame) {
            pendingStreamingSnapshot = null
            streamingSnapshotPublishJob?.cancel()
            streamingSnapshotPublishJob = null
            if (next.selectedSessionId != snapshot.selectedSessionId && pendingSelectedSessionId == null &&
                composerSessionId == snapshot.selectedSessionId) {
                if (composerSessionId == null && next.selectedSessionId != null) composerSessionId = next.selectedSessionId
            }
            snapshot = next
            return
        }

        pendingStreamingSnapshot = next
        if (streamingSnapshotPublishJob?.isActive == true) return
        streamingSnapshotPublishJob = holderScope.launch {
            delay(STREAMING_SNAPSHOT_INTERVAL_MILLISECONDS)
            pendingStreamingSnapshot?.let { snapshot = it }
            pendingStreamingSnapshot = null
            streamingSnapshotPublishJob = null
        }
    }

    /**
     * 帧经投影发布后的统一收尾（Main、同一次发布内执行）：附件状态修剪、
     * 工作区/provider 应答帧、history 分页收尾与发送预览收敛。生产 events
     * collector 与 submitWirePayload 注入路径共用，防止两条路径漂移。
     */
    private fun afterFramePublish(
        appGraph: AndroidAppGraph?,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        normalizedEvents: List<SessionEvent>
    ) {
        if (frame.kind == "error" && frame.sessionId == composerSessionId &&
            frame.requestType in setOf("switch-branch", "history", "tree")) {
            finishBranchDraftRequest(frame.requestType, frame.sessionId)
        }
        if (frame.kind == "aborted") handleStoppedQueue(frame)
        if (frame.kind == "dequeued") handleRecalledQueue(frame)
        // compact-finished只更新权威状态，不认领当前等待：上一轮通知可能晚于下一轮点击。
        // 新网关受理快照isCompacting=true已交接本地等待；断连请求终态负责取消未ACK等待。
        if (frame.kind == "compact-started") {
            // 受理≠完成：启动提示超时不解锁；由实际状态/事件接管，
            // 避免受理到启动之间被重复触发；同时给受理反馈（启动失败静默路径兜底）。
            // 迟到 ACK（会话已换）只按 frame.sessionId 归属处理，不碰当前会话。
            val ackSessionId = frame.sessionId
            if (ackSessionId != null && ackSessionId in compactPendingSessions) {
                // 提示只在用户仍看该会话时弹（R5）；状态处理仍按归属。
                if (snapshot.selectedSessionId == ackSessionId) {
                    showTransientNotice("已请求压缩上下文")
                }
                observeCompactingStarted(ackSessionId)
                compactPendingSessions[ackSessionId]?.let { pending ->
                    val watchScope = appGraph?.gatewayScope ?: compactWatchScopeForTest
                    watchScope?.let { watchCompactStartup(it, ackSessionId, pending) }
                }
            }
        }
        if (frame.kind == "event") {
            // 压缩生命周期（compaction_start/end）：开始释放重复触发闸，收尾后补拉
            // 一次 state 刷新 contextUsage（压缩后 SDK 统计可能为 null → 圆环显示 —）。
            // 去重键带 seq：同一会话第二轮压缩不受首轮残留键影响（M3）；不轮询。
            val compactPhase = normalizedEvents.firstOrNull { record ->
                record.event.type == "compact/start" ||
                    record.event.type == "compact/end" ||
                    record.event.type == "notice/compact"
            }
            if (compactPhase != null) {
                if (compactPhase.event.type == "compact/start") {
                    observeCompactingStarted(compactPhase.sessionId)
                } else if (compactSeenEvents.add(
                        "${compactPhase.event.type}:${compactPhase.sessionId}:${compactPhase.seq}"
                    )) {
                    finishCompactPending(compactPhase.sessionId)
                    requestStateAfterCompaction(appGraph, compactPhase.sessionId)
                }
            }
        }
        // 任意携带 isCompacting 的快照部可能携带压缩开始的权威信号（compact-started
        // 本身就是 state 快照形状）；这里统一释放闸门。
        frame.sessionId?.let { observeCompactingStarted(it) }
        if (frame.kind == "error" && frame.requestType == "ping" && pingStarted != null) {
            finishPing("测速失败")
        }
        if (frame.kind == "pong" && pingStarted != null &&
            ("ping-id" !in gatewayState.capabilities || frame.pingId == activePingId)) {
            finishPing("${pingStarted!!.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)} ms")
        }
        pruneAttachmentStateForSession()
        handleWorkspaceFrame(frame)
        sessionControlsRefresh.afterFrame(frame, snapshot.selectedSessionId)?.let { sessionId ->
            // 不能在投影发布/事件消费中等待 runtime 锁：生产者可能正等消费队列腾位。
            appGraph?.gatewayScope?.launch { appGraph.gatewayRuntime.requestState(sessionId) }
        }
        if (appGraph != null) handleProviderFrame(appGraph, frame, frame.requestType)
        when (frame.kind) {
            "model-scope" -> if (modelScopeIsLoading) {
                modelScopeIsLoading = false
                modelScope = frame.scope
                if (frame.scope != null) modelScopeLoadRevision += 1
                modelScopeError = if (frame.scope == null) "模型范围回执不完整，请重试" else null
            }
            "model-scope-saved" -> if (modelScopeIsSaving && frame.scopeRequestId == activeScopeRequestId) {
                modelScopeIsSaving = false
                if (frame.scope != null) {
                    modelScope = frame.scope
                    modelScopeSavedRequestId = activeScopeRequestId
                    modelScopeError = null
                    refreshModelCatalog()
                } else modelScopeError = "保存回执不完整，请重新加载核对"
                activeScopeRequestId = null
            }
        }
        if (frame.kind == "history") {
            correlatedSessionId?.let { sessionId ->
                historyPagingSessionIds = historyPagingSessionIds - sessionId
            }
            // history 归一事件恒空：正式 USER 图片回显只能从 frame.messages 原始
            // content 数组读整组 attachmentId，否则 history 恢复后预览残留，
            // 与正式消息双气泡。
            handleHistoryEcho(correlatedSessionId, frame)
        }
        // 正式回显（user message_end）：整组实发 attachmentId 命中即移除本地预览。
        // afterPublish 在 Main 执行，与同一次快照发布同帧，避免“先正式消息后
        // 预览移除”的双气泡闪现；不能在 gateway collector 线程读写非线程安全的 Tracker。
        if (frame.kind == "event") {
            handleOutgoingEcho(correlatedSessionId, normalizedEvents)
            // 回合落定后重拉首页 history：run 结束时 pi 的 agent.prompt 已返回，
            // 本轮 message_end 的 appendMessage 均已同步落盘（agent_settled 最后
            // 发出）。强退重开的手机此前 history 可能取到 user 落盘前的旧快照
            // （消息“消失”，等下一次手动进会话才回来）；此时重拉一次即可补齐。
            // 仅当前选中会话触发；不断连检查——turn/end 帧到达即连接证据
            // （gatewayState 快照可能滞后于事件流），断连竞态下请求由 history
            // lane 超时兜底。
            //
            // preserveLive：不再“先清后取”（旧实现会把正在看的对话瞬时抹成加载态、
            // 连带丢滚动位置）。新页整窗替换旧的最新窗口，并按条目身份与实时
            // （正 seq）记录对消；请求失败时旧视图仍在，只记会话级失败码。恰逢在途
            // history 时由投影归并“待取最新页”意图，当前批次落地后补发（仍选中才发）。
            if (
                normalizedEvents.any { it.event.type == "turn/end" } &&
                correlatedSessionId != null &&
                correlatedSessionId == snapshot.selectedSessionId
            ) {
                val sessionId = correlatedSessionId
                val appGraph = graph
                if (appGraph == null) {
                    projectionActor.loadHistoryImmediate(sessionId, older = false, preserveLive = true)
                } else {
                    appGraph.gatewayScope.launch {
                        projectionActor.loadHistory(sessionId, older = false, preserveLive = true)
                    }
                }
            }
        }
    }

    private fun handleWorkspaceFrame(frame: GatewayFrame) {
        if (frame.kind == "models") {
            modelCatalogIsLoading = false
            modelCatalogError = null
        }
        if (frame.kind == "input-resources" || frame.kind == "input-completions") {
            inputAssist.receive(frame)
            return
        }
        if (frame.kind == "error" && frame.requestType in setOf("input-resources", "input-completions")) {
            inputAssist.fail(frame.requestType!!, frame.queryId, "加载失败，点击重试")
            return
        }
        if (frame.kind == "session-restarted" && frame.sessionId == inputAssist.context.sessionId) {
            inputAssist.reset()
            refreshInputResources()
        }
        when (frame.kind) {
            "paired" -> clearPairedGatewayUiState()
            // commands 帧缓存（PROTOCOL §4.21）：按 sessionId 缓存全量三源；
            // 联想层只出确定可用的模板/skill，扩展命令不进联想（RPC 下无法
            // 静态判定 handler 是否依赖 TUI，避免“点联想→静默无反应”被当
            // bug）；手动全名输入仍照常透传，与 pi 原生一致。
            "commands" -> {
                commandsLoading = false
                val ownerId = frame.sessionId
                val list = frame.commands
                if (ownerId != null && list != null && ownerId == snapshot.selectedSessionId) {
                    slashCommands = ownerId to list
                }
            }
            // 会话分支树应答（PROTOCOL §4.18）：缓存树 + 当前叶子。
            "tree" -> {
                treeLoading = false
                treeError = null
                val treeOwnerId = frame.sessionId
                if (treeOwnerId != null && treeOwnerId == snapshot.selectedSessionId) {
                    sessionTree = Triple(treeOwnerId, frame.leafId, frame.nodes.orEmpty())
                    treeSelectedId = frame.selectedId ?: frame.leafId
                }
            }
            // 排队稿保留双空格合并；未被编辑的自动回填可随节点更新，手工稿不覆盖。
            "branch-switched" -> {
                val queuedText = frame.queued.orEmpty().joinToString("  ")
                if (queuedText.isNotBlank()) {
                    val owner = frame.sessionId
                    if (owner == composerSessionId) {
                        val current = messageDraft.trim()
                        messageDraft = if (current.isBlank()) queuedText else "$queuedText  $current"
                    } else if (owner != null) {
                        val saved = sessionDrafts[owner]
                        sessionDrafts[owner] = ComposerDraft(
                            listOf(queuedText, saved?.text.orEmpty()).filter(String::isNotBlank).joinToString("  "),
                            saved?.images.orEmpty(), saved?.uris.orEmpty(), saved?.preparations.orEmpty(),
                            ++nextInputGeneration, null,
                        )
                    }
                }
                restoreBranchDraft(frame, queuedText.isNotBlank())
                if (frame.alreadyAt == true) {
                    showTransientNotice("已在此处")
                } else if (frame.sessionId != null && frame.sessionId == snapshot.selectedSessionId) {
                    branchSwitchedTick += 1
                }
                frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                        graph?.gatewayScope?.launch {
                            projectionActor.loadHistory(sessionId, older = false)
                        }
                    }
                }
                if (frame.sessionId == snapshot.selectedSessionId) {
                    loadSessionTree(treeViewOptions.takeIf { treeViewSessionId == snapshot.selectedSessionId })
                }
            }
            // 整条 entry 的可复制全文（Pi Ctrl+X，§4.26）：只有文本才当成功；
            // 超限帧只带 oversized+bytes，如实报“过大未复制”，不静默截断充全文。
            "entry-copy" -> {
                // frame.text 是跳模块的公开属性，不能智能转：先取本地副本再判空。
                val copiedText = frame.text
                val oversizedBytes = frame.bytes
                val outcome = when {
                    copiedText != null -> TreeCopyOutcome.Copied(copiedText)
                    frame.oversized == true -> TreeCopyOutcome.Failed(
                        if (oversizedBytes == null) "内容过大，未复制"
                        else "内容过大（约 ${(oversizedBytes + 1023) / 1024} KB），未复制"
                    )
                    else -> TreeCopyOutcome.Failed("网关没有返回可复制文本")
                }
                finishTreeCopy(frame.sessionId, frame.entryId, outcome)
            }
            // 标签写回执（Pi Shift+L，§4.27）：成功后重拉树——追加 LabelEntry
            // 会推进叶子，当前行/选择必须用网关重算的投影，不在客户端自己推。
            "label-put" -> {
                val settled = finishTreeLabelSaved(frame.sessionId, frame.entryId, frame.label)
                // frame.sessionId 是跨模块公开属性，不能智能转：取本地副本。
                val ownerSessionId = frame.sessionId
                if (settled && ownerSessionId != null && ownerSessionId == snapshot.selectedSessionId) {
                    // 新叶子是自己写出的 LabelEntry，不会投影成消息：标记后 isBehindLatest
                    // 不把“标签写完”误报成“落后于最新”（点了还会跳到 LabelEntry 上）。
                    val labelEntryId = frame.labelEntryId
                    if (labelEntryId != null) projection.markNonRenderingLeaf(ownerSessionId, labelEntryId)
                    loadSessionTree(treeViewOptions.takeIf { treeViewSessionId == snapshot.selectedSessionId })
                }
            }
            "directories" -> {
                directoryIsLoading = false
                directoryError = null
                directoryPath = frame.path
                directoryHome = frame.home
                directoryCrumbs = frame.crumbs.orEmpty()
                directoryEntries = frame.entries.orEmpty()
            }
            "directory-create" -> {
                directoryCreationIsLoading = false
                val parentPath = pendingDirectoryCreationParentPath
                pendingDirectoryCreationParentPath = null
                createdDirectoryPathToReveal = frame.path
                parentPath?.let(::browseDirectories)
            }
            "sent" -> {
                // 明确SDK已开始/排队/处理即完成本次发送等待。SDK1.0.4会重建图片块，
                // 不能再用附件ID回显作为发送确认；无disposition的旧网关保持原路径。
                recordOutgoingSent(frame.sessionId, sdkAccepted = when (frame.disposition) {
                    "started", "queued", "handled" -> true
                    else -> false
                })
            }
            "error" -> {
                clearWorkspaceRequestLoading(frame.requestType)
                // 服务端 error 帧（prompt-rejected 等，明确拒绝）：预览转失败，
                // 新会话首条纯文本回填（带图不自动回填，见 handleOutgoingServerReject）；
                // 正常 sent 已受理的不受影响。
                if (frame.requestType == "message") {
                    handleOutgoingServerReject()
                }
                if (frame.requestType in WORKSPACE_REQUEST_TYPES) {
                    platformError = GatewayErrorText.request(frame.requestType, frame.code)
                    // 目录失败的确认键是“重试”当前目录，不是断线重连。
                    platformErrorAction = if (frame.requestType == "directories") PlatformErrorAction.RETRY_DIRECTORY
                        else PlatformErrorAction.DISMISS_ONLY
                    // 面板内同样展示+重试：空列表不再被误认空目录。
                    directoryError = GatewayErrorText.request(frame.requestType, frame.code)
                }
            }
        }
    }

    internal fun handleWorkspaceRequestCancelled(event: GatewayRuntimeEvent.RequestCancelled) {
        clearWorkspaceRequestLoading(event.requestType)
        if (event.requestType == "directory-create") {
            directoryError = "创建未确认，请重新打开核对"
        } else if (event.requestType == "directories" && event.reason in setOf(
            "network-lost", "connection-closed", "recovery-timeout", "websocket-failure",
        )) directoryError = "连接已断开，请重试"
    }

    private fun clearWorkspaceRequestLoading(requestType: String? = null) {
        if (requestType == null || requestType == "directories") directoryIsLoading = false
        if (requestType == null || requestType == "directory-create") {
            directoryCreationIsLoading = false
            pendingDirectoryCreationParentPath = null
        }
    }

    /** provider 管理应答帧（PROTOCOL.md §4.15）：列表回填、保存/删除反馈。 */
    private fun handleProviderFrame(appGraph: AndroidAppGraph, frame: GatewayFrame, requestType: String?) {
        when (frame.kind) {
            "providers" -> {
                providerListIsLoading = false
                providers = frame.providers.orEmpty()
                catalogProviders = frame.catalogProviders.orEmpty()
                catalogError = frame.catalogError
            }
            "provider-saved" -> {
                providerSaveIsLoading = false
                val saved = frame.provider
                if (saved != null) {
                    providers = providers.filterNot { it.id == saved.id } + saved
                }
                val savedId = saved?.id ?: "未知"
                val receipt = when {
                    frame.admitted == false ->
                        "已保存，但加入模型菜单失败（可在电脑端 TUI 手工把 $savedId 加入 enabledModels）"
                    frame.refreshed == false ->
                        "已保存，但模型目录刷新报告了问题（可在电脑端检查 models.json 或重启网关）"
                    else -> "Provider 已保存：$savedId"
                }
                showProviderResult(receipt)
                // 保存业务终态：成功只认这一帧。`loadProviders()` 触发的列表刷新
                // 可能紧接着被合并取消，但那是读请求的终态，不会覆盖这里。
                publishSaveTerminal(
                    status = ProviderSaveTerminal.Status.Saved,
                    detail = receipt,
                    echoed = saved,
                )
                // 保存成功后重新读取完整 provider。回执里的 provider 是网关当次写入结果，
                // 独立 providers 回读才能覆盖保存期间由桌面端/网关补齐的字段，并确认
                // thinkingLevelMap 等完整配置已经按持久化结果回到客户端。
                if (supportsRequest("providers")) loadProviders()
                // 保存后不再自动测试：用户要求“必须手动测试通过才能保存”，
                // 自动测试会覆盖手测结论（曾表现为“手测正常、过一会儿自己变失败”）。
                // 保存成功后刷新模型目录，新模型立即可选。
                if (supportsRequest("models")) {
                    appGraph.gatewayScope.launch {
                        appGraph.gatewayRuntime.sendRequest(GatewayRequests.models())
                    }
                }
            }
            "provider-models-result" -> {
                providerModelsIsLoading = false
                providerModelsResult = ProviderModelsOutcome(
                    ok = frame.ok == true,
                    ids = frame.ids.orEmpty(),
                    error = frame.error,
                    providerId = frame.id
                )
            }
            "provider-test-result" -> {
                val ok = frame.ok == true
                val outcome = ProviderTestOutcome(
                    ok = ok,
                    summary = if (ok) {
                        "连接正常" + (frame.latencyMs?.let { "（${it}ms）" } ?: "")
                    } else frame.error ?: "连接失败",
                    providerId = frame.id,
                    url = frame.url,
                    failureKind = frame.failureKind,
                    httpStatus = frame.httpStatus,
                )
                if (providerTestBatchActive) {
                    // 批次只认自己的Provider回执；中间失败不对表单发通行证。
                    if (frame.id == providerProbeId) pendingProviderProbe?.complete(ProviderProbeReply(outcome))
                } else {
                    providerTestIsLoading = false
                    providerTestResult = outcome
                    if (supportsRequest("providers")) loadProviders()
                    if (supportsRequest("models")) appGraph.gatewayScope.launch {
                        appGraph.gatewayRuntime.sendRequest(GatewayRequests.models())
                    }
                }
            }
            "provider-deleted" -> {
                val deletedId = frame.id
                providers = providers.filterNot { it.id == deletedId }
                showProviderResult(
                    if (frame.refreshed == false) {
                        "Provider 已删除：${deletedId ?: "未知"}（模型目录刷新报告了问题，可在电脑端检查）"
                    } else {
                        "Provider 已删除：${deletedId ?: "未知"}"
                    }
                )
                // 删除后同步刷新模型目录，已删 provider 的模型立即从选择器消失。
                if (supportsRequest("models")) {
                    appGraph.gatewayScope.launch {
                        appGraph.gatewayRuntime.sendRequest(GatewayRequests.models())
                    }
                }
            }
            "error" -> {
                // error 帧的 requestType 可空，而 `clearProviderRequestLoading(null)` 是
                // 「全清」：无归属的帧会把 provider-put 的保存位一起抹掉，表单就会一直
                // 等一个永远不会来的保存终态。无归属就不动——真在途的请求会由它自己的
                // RequestTimedOut/Rejected 按 requestType 正常释放。
                if (requestType != null) {
                    clearProviderRequestLoading(requestType, "Provider 连接测试失败，请重试")
                    if (requestType in PROVIDER_REQUEST_TYPES) {
                        val detail = GatewayErrorText.request(requestType, frame.code)
                        // 走 showProviderResult：直接赋值会绕过清除任务管理——旧 timer
                        // 会把这条提前抹掉，而这条自己又永不清除（与 RequestCancelled 同理）。
                        showProviderResult(detail)
                        // 只有 provider-put 的服务端拒绝才是保存的终态；读请求/删除的
                        // error 只留瞬时提示，不进保存通道。
                        if (requestType == "provider-put") {
                            publishSaveTerminal(ProviderSaveTerminal.Status.Rejected, detail)
                        }
                    }
                }
            }
        }
    }

    private fun pruneAttachmentStateForSession() {
        val sessionId = snapshot.selectedSessionId
        val sessionKeys = if (sessionId == null) emptySet() else currentSessionAttachmentIds().mapTo(mutableSetOf()) {
            gatewayAttachmentCacheKey(sessionId, it)
        }
        thumbnailCache.retainKeys(sessionKeys)
        attachmentStateCache.retainKeys(sessionKeys)
        visibleAttachmentKeys = visibleAttachmentKeys intersect sessionKeys
        val retainedRequests = attachmentQueue.filter { it.cacheKey in visibleAttachmentKeys }
        attachmentQueue.clear()
        attachmentQueue.addAll(retainedRequests)
        publishAttachmentState()
    }

    private fun drainAttachmentQueue() {
        val appGraph = graph ?: return
        if (activeAttachment != null) return
        val request = attachmentQueue.removeFirstOrNull() ?: return
        activeAttachment = request
        appGraph.gatewayScope.launch {
            val cached = appGraph.gatewayRuntime.readCachedAttachment(request.sessionId, request.attachmentId)
            appGraph.diagnostics.attachmentCacheRead(
                request.attachmentId,
                hit = cached != null,
                byteCount = cached?.size ?: 0
            )
            val bytes = cached ?: run {
                // §4.22：缓存 miss 且入队时捕获了完整引用才发取回请求；
                // fetch 内部按 hello capability/单 lane REJECT_IF_BUSY 门控，
                // 成功先写会话级缓存再返回，失败（含无引用）返回 null。
                val entryId = request.entryId
                val imageIndex = request.imageIndex
                if (entryId != null && imageIndex != null) {
                    appGraph.gatewayRuntime.fetchAttachment(
                        request.sessionId,
                        entryId,
                        imageIndex,
                        request.attachmentId
                    )
                } else {
                    null
                }
            }
            withContext(Dispatchers.Main.immediate) {
                val published = bytes != null && publishThumbnail(request, bytes)
                if (!published) markAttachmentFailed(request.cacheKey)
                finishActiveAttachment(request.cacheKey)
            }
        }
    }

    private fun removeQueuedAttachment(cacheKey: String) {
        val retained = attachmentQueue.filterNot { it.cacheKey == cacheKey }
        attachmentQueue.clear()
        attachmentQueue.addAll(retained)
    }

    private fun finishActiveAttachment(cacheKey: String) {
        if (activeAttachment?.cacheKey != cacheKey) return
        activeAttachment = null
        drainAttachmentQueue()
    }

    private suspend fun publishThumbnail(request: AttachmentRequest, bytes: ByteArray): Boolean {
        val appGraph = graph ?: return false
        if (!isAttachmentVisible(request.cacheKey)) return false
        val bitmap = appGraph.attachmentThumbnailer.decode(
            bytes,
            thumbnailTargetWidthPixels,
            thumbnailTargetHeightPixels
        ) ?: return false
        val imageBitmap = bitmap.asImageBitmap()
        return withContext(Dispatchers.Main.immediate) {
            if (!isAttachmentVisible(request.cacheKey)) {
                bitmap.recycle()
                return@withContext false
            }
            commitThumbnail(request.cacheKey, imageBitmap)
        }
    }

    private fun commitThumbnail(cacheKey: String, imageBitmap: ImageBitmap): Boolean {
        val evicted = thumbnailCache.put(cacheKey, imageBitmap)
        attachmentStateCache.put(cacheKey, AttachmentLoadState.LOADED)
        evicted.filterNot { it == cacheKey }.forEach {
            attachmentStateCache.put(it, AttachmentLoadState.DEFERRED)
        }
        if (cacheKey in evicted) attachmentStateCache.put(cacheKey, AttachmentLoadState.DEFERRED)
        publishAttachmentState()
        return cacheKey !in evicted
    }

    private fun isAttachmentVisible(cacheKey: String): Boolean = cacheKey in visibleAttachmentKeys

    private fun currentSessionAttachmentIds(): Set<String> =
        snapshot.conversation.flatMap { it.images }.mapTo(mutableSetOf()) { it.attachmentId }

    /** 当前会话消息里按 attachmentId 找的图片引用（含 §4.22 的 entryId/序号）。 */
    private fun attachmentReference(attachmentId: String): GatewayImageAttachment? =
        snapshot.conversation.firstNotNullOfOrNull { item ->
            item.images.firstOrNull { it.attachmentId == attachmentId }
        }

    private fun markAttachmentFailed(cacheKey: String) {
        val currentKeys = snapshot.selectedSessionId?.let { sessionId ->
            currentSessionAttachmentIds().mapTo(mutableSetOf()) { gatewayAttachmentCacheKey(sessionId, it) }
        }.orEmpty()
        if (cacheKey !in currentKeys) return
        attachmentStateCache.put(
            cacheKey,
            if (cacheKey in visibleAttachmentKeys) AttachmentLoadState.FAILED
            else AttachmentLoadState.IDLE
        )
        publishAttachmentState()
    }

    private fun publishAttachmentState() {
        val sessionId = snapshot.selectedSessionId
        if (sessionId == null) {
            attachmentThumbnails = emptyMap()
            attachmentStates = emptyMap()
            return
        }
        attachmentThumbnails = thumbnailCache.snapshot().mapKeys { attachmentIdFromCacheKey(it.key) }
        attachmentStates = attachmentStateCache.snapshot().mapKeys { attachmentIdFromCacheKey(it.key) }
    }

    private fun cacheKeyForCurrentSession(attachmentId: String): String? =
        snapshot.selectedSessionId?.let { gatewayAttachmentCacheKey(it, attachmentId) }

    private fun attachmentIdFromCacheKey(cacheKey: String): String {
        val firstColon = cacheKey.indexOf(':')
        val sessionLength = cacheKey.substring(0, firstColon).toInt()
        return cacheKey.substring(firstColon + 1 + sessionLength + 1)
    }

    private data class AttachmentRequest(
        val sessionId: String,
        val attachmentId: String,
        val cacheKey: String,
        /** §4.22 取图引用（入队时从消息快照捕获，挂起后不读新选择）。 */
        val entryId: String?,
        val imageIndex: Int?
    )

    internal data class MessageSubmission(
        val generation: Long,
        val sessionId: String?,
        val draft: String,
        val images: List<AndroidPreparedImage>,
        /** 与 [images] 同序的来源 uri（点击时刻快照，不可从图片反推）：回填
         *  编辑器时保持托盘勾选态与待发图片的映射。 */
        val uris: List<String> = emptyList(),
        /** 仅新会话：点击发送瞬间的 cwd 快照（选中目录，回退最近会话目录）。 */
        val cwd: String? = null,
        /** 仅新会话：点击瞬间的模型预选快照，随 message 帧携带。 */
        val model: GatewayModelSelection? = null
    )

    /** provider 连接测试的展示态（表单内联提示）：失败原因是网关给的可操作文案。
     *  `providerId` 用于归属：编辑弹窗只展示当前对象的结论，避免跨 provider 残留误读。
     *  `failureKind`：`config` 需用户改配置（阻止保存）；`transient` 是上游临时问题
     *  （超时/5xx/429，不阻止保存）。注意失败原因文案已自带实际请求的 URL。 */
    data class ProviderTestOutcome(
        val ok: Boolean,
        val summary: String,
        val providerId: String? = null,
        val url: String? = null,
        val failureKind: String? = null,
        val attempts: List<ProviderTestAttempt> = emptyList(),
        val httpStatus: Int? = null,
    ) {
        /** 是否允许据此保存：连接正常，或失败属于上游临时问题。 */
        val allowsSave: Boolean get() = ok || failureKind == "transient"
    }

    /** 拉取上游模型清单的结论（provider-models-result）。 */
    data class ProviderModelsOutcome(
        val ok: Boolean,
        val ids: List<String> = emptyList(),
        val error: String? = null,
        val providerId: String? = null
    )

    /**
     * 一次 `provider-put` 的业务终态（保存专用通道，见 [providerSaveTerminal]）。
     *
     * - [providerId] 成功时来自 `provider-saved` 帧回显的 provider，失败/未确认时
     *   来自在途保存的目标 id。
     * - [echoed] 只在成功时有值：回显的 provider 全文。消费方可用它核对这条成功
     *   到底是不是「我这次提交」的结果（迟到回执会带着上一次的内容）。
     * - [token] 是解掉本次请求的本地在途标记；**为 null 表示这条终态无在途保存
     *   可归属**（例如旧保存早已超时、这会儿才到的迟到回执），消费方不应认领。
     */
    data class ProviderSaveTerminal(
        val providerId: String?,
        val status: Status,
        val detail: String,
        val echoed: GatewayProvider? = null,
        val token: String? = null,
    ) {
        /**
         * [Unconfirmed] 刻意不等于失败：取消/超时只说明**没拿到确认**，
         * 请求可能已经到网关并落盘（PROTOCOL 侧无回执可证否），文案与判定都
         * 不能说成「没保存」。只有 [Rejected]（网关明确拒绝）才是真失败。
         */
        enum class Status { Saved, Rejected, Unconfirmed }
    }

    companion object {
        private const val STREAMING_SNAPSHOT_INTERVAL_MILLISECONDS = 32L
        // instrumentation 测试专用开关（F13 排查：GatewayViewModel 系设备用例直接起
        // 生产 MainActivity，init 里的 connectStoredIfPaired() 会对设备上遗留的真实
        // 配对端点发起真连接，探测/握手/恢复窗全在墙钟里，用例体被拖到 9–11 分钟、
        // 一条无界挂死）。默认 false = 正式路径完全不变；仅测试在 @Before 置 true，
        // 且测试与主代码同进程，置位在 ActivityScenario.launch 之前即可生效。
        // 注意（复审 MINOR-4）：只对「测试体内显式 ActivityScenario.launch」有效——
        // createAndroidComposeRule<MainActivity>() 这类宿主在 @Before 之前就启动了
        // Activity，置位无效；别把它当通用开关复用。
        internal var skipStoredConnectForTest: Boolean = false
        internal const val SWITCH_REJECT_NOTICE = "会话正忙，暂不能切换分支，稍后再试"
        /** 标签长度上限：与网关 `LABEL_MAX_CHARS`（PROTOCOL §4.27）同口径的 wire 护栏。
         *  Pi 自身只 trim 不限长；树页输入框另按原型限 24 字符（视觉约束，不是协议上限）。 */
        internal const val TREE_LABEL_MAX_CHARS = 200
        private const val RECENT_PHOTOS_LIMIT = 10
        private const val RECENT_PHOTO_THUMBNAIL_EDGE = 128
        private const val HISTORY_PAGE_MESSAGE_LIMIT = 20

        private val WORKSPACE_REQUEST_TYPES = setOf(
            "directories",
            "directory-create"
        )

        private val PROVIDER_REQUEST_TYPES = setOf(
            "providers",
            "provider-put",
            "provider-delete",
            "provider-test",
            "provider-models"
        )

        /** 取消/超时的 provider 系提示文案：只有配置写操作（保存/删除）才说
         *  "配置未确认"——请求可能已达网关，落盘状态不明；列表刷新/连接测试/
         *  拉模型是读操作，失败不冒充保存结论，用中性文案。internal 供单测直接
         *  断言文案区分（三处终态在 graph 事件块中，纯 JVM 单测构造不出
         *  AndroidAppGraph，无法端到端驱动）。 */
        internal fun providerUnconfirmedResultText(requestType: String?, outcome: String): String =
            when (requestType) {
                "provider-put" -> "保存未确认，请重新打开核对"
                "provider-delete" -> "删除未确认，请重新打开核对"
                else -> "Provider 请求$outcome，请重试"
            }
        private const val MAXIMUM_THUMBNAIL_BYTES = 16L * 1_024 * 1_024
        private const val MAXIMUM_STATUS_COUNT = 256L
        private const val DEFAULT_THUMBNAIL_TARGET_PIXELS = 720
        /** sent 受理后等正式回显的窗口：超过则预览转未确认（不判失败、不重发）。 */
        private const val OUTGOING_UNCONFIRMED_AFTER_MS = 45_000L
        const val DEFAULT_WIRE_PAYLOAD =
            """{"kind":"event","sessionId":"android-demo","seq":4,"time":1786937355,"event":{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"最终消息会替换流式临时消息。"}]}}}"""
    }
}

enum class AttachmentLoadState { IDLE, LOADING, LOADED, FAILED, DEFERRED }

/**
 * 新会话落点目录的解析口径（唯一真源）：首页「工作区」行显示它，首条 message 帧
 * 也带它，二者不能各算各的。优先级=用户显式选择 > 退出会话新建时沿用的目录 >
 * 最近会话的目录（都不成立则为 null，由网关按缺省处理）。
 */
internal fun resolveNewSessionCwd(
    selectedDirectoryPath: String?,
    pendingCwd: String?,
    latestSessionCwd: String?
): String? = selectedDirectoryPath ?: pendingCwd ?: latestSessionCwd
