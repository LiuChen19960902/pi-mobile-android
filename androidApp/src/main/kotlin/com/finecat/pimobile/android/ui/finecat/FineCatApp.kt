package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.shared.gateway.GatewayErrorText

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.scrollBy
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.finecat.pimobile.android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import com.finecat.pimobile.android.ui.finecat.composer.*
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.BuildConfig
import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.ExtensionStatusBar
import com.finecat.pimobile.android.ui.groupAssistantRounds
import com.finecat.pimobile.android.ui.HeroCopyEditorDialog
import com.finecat.pimobile.android.ui.SessionRenameDialog
import com.finecat.pimobile.android.ui.homeBrandCopy
import com.finecat.pimobile.shared.domain.SessionSummary
import com.finecat.pimobile.android.ui.attachmentIdsByRoundKey
import com.finecat.pimobile.android.ui.displayRoundKey
import com.finecat.pimobile.android.ui.isTimelinePinnedToBottom
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.android.ui.scrollToTimelineBottom
import com.finecat.pimobile.android.ui.shouldPublishPinnedState
import com.finecat.pimobile.android.ui.shouldScrollToLatest
import com.finecat.pimobile.android.ui.visibleAttachmentIds
import com.finecat.pimobile.android.ui.GatewayQrScannerScreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.finecat.pimobile.android.ui.finecat.components.FcHeader
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcSheetHost
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FcStatusTag
import com.finecat.pimobile.android.ui.finecat.components.FcTagStyle
import com.finecat.pimobile.android.ui.finecat.components.FcViewSeg
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.conversation.FcTimeSeparator
import com.finecat.pimobile.android.ui.finecat.conversation.timeSeparatorsByRoundKey
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.conversation.FcAiMessage
import com.finecat.pimobile.android.ui.finecat.composer.FcComposer
import com.finecat.pimobile.android.ui.finecat.composer.FcModelPicker
import com.finecat.pimobile.android.ui.finecat.composer.FcContextPopover
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.positionInRoot
import com.finecat.pimobile.android.ui.finecat.conversation.FcChatMessage
import com.finecat.pimobile.android.ui.finecat.conversation.FcJumpButton
import com.finecat.pimobile.android.ui.finecat.conversation.FcMarkdownText
import com.finecat.pimobile.android.ui.finecat.conversation.FcStep
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatSteps
import com.finecat.pimobile.android.ui.finecat.conversation.mergeFineCatProcessEntries
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatWorkingGroupId
import com.finecat.pimobile.android.ui.finecat.conversation.FcStepsBlock
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatEntryHasVisibleBody
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatEntryGap
import com.finecat.pimobile.android.ui.finecat.conversation.FcUserBubble
import com.finecat.pimobile.android.ui.finecat.conversation.FcWorking
import com.finecat.pimobile.android.ui.ConversationImagePreviewDialog
import com.finecat.pimobile.android.ui.ConversationImagePreviewRequest
import com.finecat.pimobile.android.ui.decodeSampledBitmap
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.projection.ConversationItemKind as ProjectionKind

/**
 * FineCat 新 UI 根（P4a：真实只读对话）。数据真源是现有
 * [AndroidSharedStateHolder]（mutableStateOf 出口），分组复用既有
 * `makeConversationDisplayEntries` 纯函数——本层只做"展示条目 → FineCat
 * 视觉件"的映射，不解析 wire、不建第二套消息状态。
 *
 * P4a 为只读：无 composer（P4b）、无抽屉（P7）、trace/tree 段禁用（P5/P6）。
 */
// ===================== 定位观测（只诊断，不参与逻辑） =====================
// 背景：轨迹跳转在「older 请求挂起 + 用户拖动取消 + 迟到应答」竞态下的视口表现待定位。
// 本函数只读最新快照与当前布局，输出固定 stage、布尔量/计数/索引/offset、key 分类与
// hash；绝不输出消息正文、会话路径、原始 entryId/cursor/密钥。默认关闭：需
// BuildConfig.DEBUG 且 logcat 中 FineCatJumpProbe 为 DEBUG 级才输出。只读不写，
// 不新增状态/协程/语义/重组触发；guard 在入口，关闭时不读函数内 snapshot 与 layout；
// 但 detail 是调用前 eager 构造的实参，仍有少量 detail 字符串求值（这些调用点都在
// 协程/回调体里，不在 composition 或 snapshotFlow 内）。
private const val JUMP_PROBE_TAG = "FineCatJumpProbe"

private fun probeJumpObservation(
    stage: String,
    stateHolder: AndroidSharedStateHolder,
    listState: LazyListState,
    detail: String = "",
) {
    if (!BuildConfig.DEBUG || !Log.isLoggable(JUMP_PROBE_TAG, Log.DEBUG)) return
    val info = listState.layoutInfo
    fun keyClass(key: Any?): String = when (key) {
        "loading-older" -> "loading-older"
        "history-error" -> "history-error"
        "working" -> "working"
        else -> "entry"
    }
    val first = info.visibleItemsInfo.firstOrNull()
    val firstMsg = info.visibleItemsInfo.firstOrNull { keyClass(it.key) == "entry" }
    Log.d(
        JUMP_PROBE_TAG,
        "t=${SystemClock.elapsedRealtime()} stage=$stage" +
            " loading=${stateHolder.snapshot.selectedHistoryIsLoadingOlder}" +
            " historyError=${stateHolder.snapshot.selectedHistoryError != null}" +
            " count=${info.totalItemsCount}" +
            " firstIndex=${listState.firstVisibleItemIndex}" +
            " firstOffset=${listState.firstVisibleItemScrollOffset}" +
            " firstKey=${first?.let { "${keyClass(it.key)}#${it.key.hashCode()}" } ?: "none"}" +
            " firstMsg=${firstMsg?.let { "${keyClass(it.key)}#${it.key.hashCode()}@${it.offset}" } ?: "none"}" +
            (if (detail.isEmpty()) "" else " $detail"),
    )
}

/** 主题跟随：与旧 UI 同一口径（system/light/dark），只换 token 不动业务状态。 */
@Composable
private fun isFineCatDark(appearanceMode: String): Boolean = when (appearanceMode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun FineCatApp(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.key(stateHolder.gatewayUiEpoch) {
        FineCatAppContent(stateHolder, onPickImage, modifier)
    }
    FineCatCrashReportDialog(darkTheme = isFineCatDark(stateHolder.appearanceMode))
}

@Composable
private fun FineCatAppContent(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 最近照片托盘的媒体权限（02 §6 行 2）：未授权先弹系统框，拒绝降级系统选择器
    val mediaPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val photoPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            stateHolder.togglePhotosTray()
        } else {
            onPickImage()
        }
    }
    fun openPhotosTray() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, mediaPermission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            stateHolder.togglePhotosTray()
        } else {
            photoPermissionLauncher.launch(mediaPermission)
        }
    }
    FineCatTheme(darkTheme = isFineCatDark(stateHolder.appearanceMode)) {
        val snapshot = stateHolder.snapshot
        val selectedSession = snapshot.sessions.firstOrNull { it.id == snapshot.selectedSessionId }
        val sessionState = snapshot.selectedSessionId?.let { snapshot.sessionStates[it] }
        val isStreaming = sessionState?.isStreaming == true
        val connected = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
        val showHome = snapshot.selectedSessionId == null
        val displayedCwd = if (showHome) stateHolder.fineCatNewSessionDirectoryPath else stateHolder.currentSessionDirectory
        LaunchedEffect(displayedCwd, snapshot.selectedSessionId, connected, stateHolder.supportsWorktrees) {
            stateHolder.refreshWorktreeLocation(displayedCwd, snapshot.selectedSessionId)
        }

        val colors = LocalFineCatColors.current
        // 复制全文（原型 .icopy）：与旧 ConversationScreen 同一口径，写系统剪贴板。
        val clipboard = LocalClipboardManager.current

        // 会话级 UI 状态：随会话切换重建（01 §3），键为稳定条目 id（F4 审查）
        var modelSheetOpen by remember { mutableStateOf(false) }
        var composerFocused by remember { mutableStateOf(false) }
        var editorState by remember(snapshot.selectedSessionId) {
            mutableStateOf(TextFieldValue(stateHolder.messageDraft, TextRange(stateHolder.messageDraft.length)))
        }
        // 外部树回填/取回草稿只在文本真正不同的时候移动光标；正常输入保留选区与组合。
        val editorValue = if (editorState.text == stateHolder.messageDraft) editorState else
            TextFieldValue(stateHolder.messageDraft, TextRange(stateHolder.messageDraft.length))
        SideEffect { if (editorState != editorValue) editorState = editorValue }
        fun updateEditor(value: TextFieldValue) {
            editorState = value
            stateHolder.messageDraft = value.text
        }
        val assistContextCwd = if (showHome) stateHolder.fineCatNewSessionDirectoryPath else null
        val assist = stateHolder.inputAssist
        LaunchedEffect(snapshot.selectedSessionId, assistContextCwd) {
            stateHolder.setInputAssistContext(snapshot.selectedSessionId, assistContextCwd)
        }
        val assistToken = if (composerFocused) inputAssistToken(editorValue) else null
        // 原生 /reload 的资源发现可晚于首份快照；打开候选时读取当前清单。
        LaunchedEffect(assistToken?.isFile == false, assist.generation, connected) {
            if (assistToken?.isFile == false && connected) stateHolder.refreshInputResources()
        }
        val assistContextMatches = assist.context.sessionId == snapshot.selectedSessionId &&
            (!showHome || assist.context.cwd == assistContextCwd)
        val assistCommands = if (assistContextMatches && assistToken?.isFile == false) {
            val query = assistToken.text.drop(1)
            assist.commands.filter { it.name.contains(query, ignoreCase = true) }
        } else emptyList()
        val assistItems = if (assistContextMatches && assistToken?.text == assist.token) assist.completions else emptyList()
        val assistRows = if (assistToken?.isFile == true) assistItems.map {
            InputAssistRow(it.label, file = it.type == "file", directory = it.type == "directory")
        } else assistCommands.map { InputAssistRow("/${it.name}", it.description ?: it.argumentHint) }
        var assistIndex by remember(snapshot.selectedSessionId) { mutableIntStateOf(0) }
        LaunchedEffect(assistToken?.text) { assistIndex = 0 }
        val selectedAssistIndex = assistIndex.coerceIn(0, (assistRows.size - 1).coerceAtLeast(0))
        val completionToken = assistToken?.takeIf { it.isFile }?.text.orEmpty()
        LaunchedEffect(snapshot.selectedSessionId, assistContextCwd, completionToken, connected, stateHolder.gatewayState.capabilities, assist.generation) {
            if (completionToken.isNotEmpty()) delay(150)
            if (completionToken.isNotEmpty() || assist.token.isNotEmpty()) stateHolder.requestInputCompletions(completionToken)
        }
        fun acceptAssist(index: Int) {
            val token = assistToken ?: return
            val next = if (token.isFile) assistItems.getOrNull(index)?.let { acceptInputCompletion(editorValue, token, it) }
                else assistCommands.getOrNull(index)?.let { replaceInputAssistToken(editorValue, token, "/${it.name} ") }
            if (next != null) updateEditor(next)
        }
        val dropAssist by rememberUpdatedState {
            if (assistToken != null) updateEditor(dropInputAssistToken(editorValue))
        }
        val assistOpen by rememberUpdatedState(assistToken != null)
        BackHandler(enabled = assistToken != null) { dropAssist() }
        val referenceTransformation = VisualTransformation { original ->
            val annotated = AnnotatedString.Builder(original).apply {
                inputReferenceRanges(original.text, assist.commands.map { it.name }.toSet()).forEach {
                    addStyle(SpanStyle(color = colors.brand), it.start, it.end)
                }
            }.toAnnotatedString()
            TransformedText(annotated, OffsetMapping.Identity)
        }
        var contextAnchor by remember(snapshot.selectedSessionId) { mutableStateOf<IntRect?>(null) }
        var composerBounds by remember { mutableStateOf<Rect?>(null) }
        var composerDockTop by remember { mutableStateOf<Float?>(null) }
        var rootTop by remember { mutableStateOf(0f) }
        var pageOrigin by remember { mutableStateOf(Offset.Zero) }
        val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissComposer by rememberUpdatedState {
        keyboardController?.hide()
        if (composerFocused || contextAnchor != null || stateHolder.photosTrayOpen) {
            focusManager.clearFocus(force = true)
            contextAnchor = null
            if (stateHolder.messageDraft.isEmpty() && stateHolder.preparedImages.isEmpty() &&
                stateHolder.preparingPhotoUris.isEmpty()
            ) {
                if (stateHolder.photosTrayOpen) stateHolder.togglePhotosTray()
            }
        }
    }
        var imagePreview by remember(snapshot.selectedSessionId) { mutableStateOf<ConversationImagePreviewRequest?>(null) }
        val composerControlsExpanded = composerFocused || stateHolder.messageDraft.isNotEmpty() || stateHolder.photosTrayOpen ||
            stateHolder.preparedImages.isNotEmpty() || stateHolder.preparingPhotoUris.isNotEmpty()
        LaunchedEffect(composerControlsExpanded) {
            if (!composerControlsExpanded) {
                contextAnchor = null
            }
        }
        LaunchedEffect(snapshot.selectedSessionId) { modelSheetOpen = false }
        BackHandler(enabled = contextAnchor != null) { contextAnchor = null }
        var homeDirSheet by remember { mutableStateOf(false) }
        var directoryForSession by remember { mutableStateOf(false) }
        var drawerOpen by remember { mutableStateOf(false) }
        var gatewaySheetOpen by remember { mutableStateOf(false) }
        // F01 Provider 直达（原型 pv-sheet/pv-new app.js:841-846）：记要编辑的 provider id
        // （NEW_PROVIDER_ID=新建），点行/添加直接开对应表单，不再有中间列表层。
        var providerEditId by remember { mutableStateOf<String?>(null) }
        // A restored pre-TR02 tree index was 2; keep it inside the new two-tab range.
        var selectedView by rememberSaveable(
            stateSaver = androidx.compose.runtime.saveable.Saver<Int, Int>(
                save = { it }, restore = { it.coerceIn(0, 1) },
            ),
        ) { mutableStateOf(0) }
        // 抽屉行长按 → 会话改名（pi /name 的 UI 对等物，与旧 UI 的顶栏标题点击同一真源）。
        var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
        var renameSaving by remember { mutableStateOf(false) }
        var renameError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(renameTarget?.id) { renameSaving = false; renameError = null }
        // 树屏瞬态（横/纵滚动 + 点击高亮）按会话键控提升到常驻根：when(selectedView)
        // 切 tab 会销毁 tree 组合，滚动/选中若留在屏内 remember 每次切 tab 全丢——
        // 宿主持有则切 tab 保留、切会话换新实例不继承（F06 §5）。
        val treeScreenState = remember(snapshot.selectedSessionId) { FineCatTreeScreenState() }
        var showQrScanner by remember { mutableStateOf(false) }
        var showManualPairing by remember { mutableStateOf(false) }
        // 仅点击首页品牌文字编辑，点阵和周围留白不承接此操作。
        var heroEditorOpen by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { stateHolder.updateThumbnailTargetSize(216, 216) }
        // 选中态包含正在处理的来源；处理期间再点能撤销，迟到结果不会重新勾选。
        // ModelChip 显示 Effective 值：会话级优先，无会话时回退新建预选（components.md §2）
        val pendingSelection = stateHolder.pendingModelSelection
        val effectiveModel: com.finecat.pimobile.shared.protocol.GatewayModelRef? =
            sessionState?.model ?: pendingSelection?.let {
                com.finecat.pimobile.shared.protocol.GatewayModelRef(
                    provider = it.provider, modelId = it.modelId,
                )
            }
        val effectiveThinking = sessionState?.thinkingLevel ?: pendingSelection?.thinkingLevel
        fun modelDisplayName(ref: com.finecat.pimobile.shared.protocol.GatewayModelRef?): String? {
            val catalog = snapshot.modelCatalog ?: return ref?.modelId
            val model = catalog.groups.firstOrNull { it.provider == ref?.provider }
                ?.models?.firstOrNull { it.id == ref?.modelId }
            return model?.name ?: ref?.modelId
        }
        // key存在即展开，值为组内已打开的详情；放在列表外，离屏不丢状态。
        var expandedStepGroups by remember(snapshot.selectedSessionId) {
            mutableStateOf<Map<String, String?>>(emptyMap())
        }
        // 每个会话一份列表状态：切会话不沿用旧会话的滚动位置（旧 UI remember(selectedSessionId) 同口径）。
        val listState = remember(snapshot.selectedSessionId) { LazyListState() }

        val displayEntries = remember(snapshot.conversation) {
            mergeFineCatProcessEntries(makeConversationDisplayEntries(snapshot.conversation))
        }
        val stepsByGroup = remember(displayEntries) {
            displayEntries.filterIsInstance<ConversationDisplayEntry.Process>()
                .associate { it.id to fineCatSteps(it.group) }
        }
        val workingStepsGroupId = fineCatWorkingGroupId(displayEntries, isStreaming)
        // 重载删除组/步骤时清理对应选择，避免同id重加后复活旧详情。
        LaunchedEffect(stepsByGroup) {
            expandedStepGroups = expandedStepGroups.mapNotNull { (groupId, stepId) ->
                stepsByGroup[groupId]?.takeIf { it.isNotEmpty() }?.let { steps ->
                    groupId to stepId?.takeIf { id -> steps.any { it.id == id } }
                }
            }.toMap()
        }
        // F02 回合分组：步骤组与其后正文同回合一组（组内 12dp），回合间 24dp
        val displayRounds = remember(displayEntries) {
            groupAssistantRounds(displayEntries)
        }
        // 时间分隔（DEC-F09-TIMESEP）：按"用户消息起"的回合整表重算——prepend 历史、流式
        // 落定都会换 displayRounds，分隔随之重排；键是回合首个显示回合的 displayRoundKey，
        // 每回合至多一行，不会重复。
        val timeSeparators = remember(displayRounds) {
            timeSeparatorsByRoundKey(displayRounds)
        }
        // 复制反馈（原型 app.js copy：图标变勾 1200ms 后恢复，按 messageId 隔离——
        // 同文不同消息复制后一条时前一条自动恢复，不串对象）。
        // delay 走组合时钟：设备测试用 mainClock.advanceTimeBy 受控验证 1000/1300ms。
        var copiedMessageId by remember { mutableStateOf<String?>(null) }
        var copiedTick by remember { mutableStateOf(0) }
        // F02 精确 reveal：回合是单个 LazyColumn item，jump 只能滚到回合顶。登记各
        // 条目在回合 Column 内的 y 偏移，jump 时以 scrollToItem(回合下标, 条目偏移)
        // 把目标条目顶贴到视口顶；未测量（离屏未组合）时偏移取 0，不劣于回合顶。
        val entryOffsets = remember(snapshot.selectedSessionId) {
            mutableStateMapOf<String, Int>()
        }
        // prepend 锚点补偿用：LazyColumn item key（回合键）→ 回合条目，与 items() 的
        // key 同源；换会话随 displayRounds 重建。
        val roundsByKey = remember(displayRounds) {
            displayRounds.associateBy { displayRoundKey(it) }
        }
        // tick 参与 key：1200ms 内对同一条消息再次复制会重启计时（原型每次点击都
        // 重新计 1200ms），只有 copiedMessageId 时同值不触发 effect。
        LaunchedEffect(copiedMessageId, copiedTick) {
            if (copiedMessageId != null) {
                delay(1_200)
                copiedMessageId = null
            }
        }
        // 可见附件集上报：只报屏内项（02 §6 懒加载）。映射按可见条目的 LazyList
        // key（前部可能插入分页/失败/Working 行，用下标会整体错位），并经
        // rememberUpdatedState 读当前条目——LaunchedEffect 只起一次，直接捕获局部
        // displayEntries 会永远停在首版，流式新增/切会话后上报不再更新。
        val currentIdsByRoundKey = rememberUpdatedState(
            remember(displayRounds) { attachmentIdsByRoundKey(displayRounds) }
        )
        val visibleImageIds by remember(listState) {
            derivedStateOf {
                visibleAttachmentIds(
                    idsByKey = currentIdsByRoundKey.value,
                    visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key.toString() },
                )
            }
        }
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { visibleImageIds }
                .collect { stateHolder.updateVisibleAttachments(it) }
        }

        // 贴底状态只由“用户滚动”决定：程序化贴底、流式追加一帧导致的
        // canScrollForward 变化都不算用户离开底部（判据复用旧 UI 的 shouldPublishPinnedState）。
        var pinnedToBottom by remember(snapshot.selectedSessionId) { mutableStateOf(true) }
        var programmaticScrolls by remember(snapshot.selectedSessionId) { mutableIntStateOf(0) }
        var initialBottomApplied by remember(snapshot.selectedSessionId) { mutableStateOf(false) }
        // 输入区/IME 改变视口时可能暂时还能向下滚，不代表用户已离开底部。
        val jumpVisible by remember(listState) {
            derivedStateOf { !pinnedToBottom && listState.canScrollForward }
        }
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow {
                Triple(
                    listState.isScrollInProgress,
                    isTimelinePinnedToBottom(listState.layoutInfo.totalItemsCount, listState.canScrollForward),
                    programmaticScrolls > 0,
                )
            }.collect { (isScrolling, pinned, isProgrammatic) ->
                probeJumpObservation(
                    stage = "tripleCollect",
                    stateHolder = stateHolder,
                    listState = listState,
                    detail = "isScrolling=$isScrolling pinned=$pinned isProgrammatic=$isProgrammatic",
                )
                if (shouldPublishPinnedState(isScrolling, pinned, isProgrammatic)) pinnedToBottom = pinned
            }
        }
        // LazyColumn 行集合：初始贴底与尾部跟随都要算末行下标，与 DSL 共用同一批
        // 布尔量，避免两处条件漂移。
        val historyError = snapshot.selectedHistoryError
        val showHistoryLoadingRow = !showHome && snapshot.selectedHistoryIsLoadingOlder
        val showHistoryErrorRow = !showHome && historyError != null
        // 负责人真机需求（C10）：空闲时不再显示三个点及“等待你的下一步”。
        // 流式/压缩或队列未投递完时仍显示运行状态，不把排队/压缩误报为落定。
        val runningForFooter = isStreaming ||
            sessionState?.isCompacting == true ||
            sessionState?.queuedMessages?.isEmpty == false
        val showWorkingRow = !showHome && runningForFooter
        // 有消息可承载时，「加载更早消息…」嵌在最旧那条（列表首项）的 keyed item 内部，
        // 列表首项因此始终是稳定的消息 key：迟到 older 应答撤掉提示时不再连带移除首个
        // item（第9轮观测的锚点位移）。只有完全没有消息时它才作为独立 item 兜底。
        val oldestEntryId = displayEntries.firstOrNull()?.id
        val showStandaloneLoadingRow = showHistoryLoadingRow && oldestEntryId == null
        val listItemCount = if (showHome) {
            0
        } else {
            // F02 回合分组后 LazyColumn 的 item 是回合：贴底/跟随尾部用的末行下标
            // 必须按回合计数，否则 scrollToItem 超出实际 item 数、贴底失效。
            displayRounds.size +
                (if (showStandaloneLoadingRow) 1 else 0) +
                (if (showHistoryErrorRow) 1 else 0) +
                (if (showWorkingRow) 1 else 0)
        }
        val tailTextLength = snapshot.conversation.lastOrNull()?.text?.length ?: 0
        // TextView 的流式换行在下一次测量才改变高度；跟随实际尾部位置，
        // 不等下一段文本到达才补滚，也不反复把已经可见的 Working 重定位到顶部。
        val tailLayoutBottom by remember(listState) {
            derivedStateOf {
                if (pinnedToBottom) {
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size }
                } else {
                    null
                }
            }
        }
        // 打开会话先看最新内容：本会话首次有内容时贴底一次；之后仅在用户原本贴底时
        // 跟随尾部（流式增量/发送）。阅读历史（已上滑离开底部）不抢滚动，用户拖动/
        // 惯性滚动期间不介入。
        LaunchedEffect(listState, listItemCount, tailTextLength, tailLayoutBottom, initialBottomApplied, pinnedToBottom, selectedView) {
            // 只在对话页跟随尾部，不滚动未组合的列表。
            if (selectedView != 0) return@LaunchedEffect
            if (
                !shouldScrollToLatest(
                    itemCount = listItemCount,
                    isUserScrolling = listState.isScrollInProgress,
                    initialPositionApplied = initialBottomApplied,
                    pinnedToBottom = pinnedToBottom,
                )
            ) {
                return@LaunchedEffect
            }
            initialBottomApplied = true
            programmaticScrolls += 1
            try {
                probeJumpObservation(stage = "tailScrollBefore", stateHolder = stateHolder, listState = listState)
                listState.scrollToTimelineBottom(
                    lastItemIndex = listItemCount - 1,
                    animated = false,
                )
                probeJumpObservation(stage = "tailScrollAfter", stateHolder = stateHolder, listState = listState)
            } finally {
                programmaticScrolls -= 1
            }
        }

        // 键盘弹出/收起时跟随 ime 数值重滚（与旧 ConversationScreen 同一口径）。
        // 跟随"数值"而不是可见性边沿：API 30+ 的 ime insets 逐帧动画下发，边沿在第一帧
        // 就翻 true，那时读到的 layoutInfo 还是键盘弹出前的旧值，之后无人再滚。
        // 上面那个 effect 只监听内容变化，而键盘不改变 entries——流内输入区预留 IME 压短视口
        // 后必须显式重滚，末条消息才不会停在输入栏后面。只在 pinned 时滚：翻历史不抢滚动。
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(imeBottomPx) {
            if (selectedView != 0) return@LaunchedEffect
            if (!pinnedToBottom || listItemCount == 0) return@LaunchedEffect
            programmaticScrolls += 1
            try {
                listState.scrollToTimelineBottom(
                    lastItemIndex = listItemCount - 1,
                    animated = false,
                    onlyIfLastHidden = false,
                )
            } finally {
                programmaticScrolls -= 1
            }
        }

        // Only real user scrolling requests an older page; short viewports need no
        // automatic fill. Pulling at the top also works when ten turns fit on screen.
        val historyScroll = remember(listState, snapshot.selectedSessionId) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // 只处理详情内部消费后真正传到列表顶部的剩余拖动。
                    if (source == NestedScrollSource.UserInput && available.y > 0 &&
                        !listState.canScrollBackward) {
                        stateHolder.loadOlderHistory()
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: androidx.compose.ui.unit.Velocity,
                    available: androidx.compose.ui.unit.Velocity,
                ): androidx.compose.ui.unit.Velocity {
                    // A user fling can reach the top after the finger has lifted.
                    if (available.y > 0 && !listState.canScrollBackward) stateHolder.loadOlderHistory()
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }

        // 历史翻页（older prepend）锚点保持：prepend 与时间分隔表整表重算会改变锚定
        // 回合的内部高度（分隔行出现/消失、加载行收起），LazyColumn 锚定后回合内条目
        // 整体位移（F10 实测 274px）。观察键 = Triple(index, offset, 首个可见条目实测
        // y)——第三项让「加载行插入/撤掉、分隔行重排」这类不改变 (index, offset) 的
        // 回合内高度变化也刷新基准（复审 F-1）：稳定帧持续把「首个可见条目（稳定 id +
        // 视口 y）+ 当时最旧条目 id」存为基准；prepend 帧（最旧条目 id 变化且旧 id 仍在，
        // 与基准同源判断，防陈旧 head 误判——复审 F-2）不改基准，等子项重测后用同一
        // 锚点条目的实测 y 差 scrollBy 补偿。锚点失配/守卫不满足时只丢弃旧基准重建
        //（该形态下锚点未位移，不补偿恰好正确）。不写死像素；拖动中/贴底/跳转翻页/
        // 会话重写（旧最旧条目消失）不补偿。
        val currentRoundsByKey by rememberUpdatedState(roundsByKey)
        LaunchedEffect(listState, snapshot.selectedSessionId) {
            var baseHeadId: String? = null
            var anchorEntryId: String? = null
            var anchorViewportY = Int.MIN_VALUE
            fun firstVisibleEntryY(): Pair<String, Int>? {
                for (item in listState.layoutInfo.visibleItemsInfo) {
                    val round = currentRoundsByKey[item.key.toString()] ?: continue
                    for (entry in round) {
                        val within = entryOffsets[entry.id] ?: continue
                        val y = item.offset + within
                        if (y >= 0) return entry.id to y
                    }
                }
                return null
            }
            androidx.compose.runtime.snapshotFlow {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    firstVisibleEntryY(),
                )
            }.collect { (_, _, anchor) ->
                val headId = stateHolder.snapshot.conversation.firstOrNull()?.id
                if (headId == null) {
                    baseHeadId = null
                    anchorEntryId = null
                    anchorViewportY = Int.MIN_VALUE
                    return@collect
                }
                val isPrepend = baseHeadId != null && headId != baseHeadId &&
                    stateHolder.snapshot.conversation.any { it.id == baseHeadId }
                if (isPrepend && anchorEntryId != null && anchorViewportY != Int.MIN_VALUE &&
                    !pinnedToBottom && !listState.isScrollInProgress
                ) {
                    withFrameNanos { }
                    val after = firstVisibleEntryY()?.takeIf { it.first == anchorEntryId }
                    val delta = after?.second?.minus(anchorViewportY) ?: 0
                    probeJumpObservation(
                        stage = "prependAnchorCompensate",
                        stateHolder = stateHolder,
                        listState = listState,
                        detail = "anchor=$anchorEntryId base=$anchorViewportY after=$after delta=$delta",
                    )
                    if (delta != 0) {
                        programmaticScrolls += 1
                        try {
                            listState.scrollBy(delta.toFloat())
                        } finally {
                            programmaticScrolls -= 1
                        }
                    }
                    // 基准作废，下一稳定帧按补偿后的布局重建
                    anchorEntryId = null
                    anchorViewportY = Int.MIN_VALUE
                    baseHeadId = headId
                    return@collect
                }
                if (anchor != null) {
                    // 稳定帧：基准随最新实测刷新（含用户滚动与回合内高度变化）。
                    anchorEntryId = anchor.first
                    anchorViewportY = anchor.second
                    baseHeadId = headId
                } else {
                    anchorEntryId = null
                    anchorViewportY = Int.MIN_VALUE
                }
            }
        }

        Box(modifier = modifier.fillMaxSize().background(colors.bg).onGloballyPositioned { rootTop = it.positionInRoot().y }.testTag("finecat.root")) {
            if (selectedView == 1) {
                // 与对话屏同一套头部，viewSeg 停在 tree 项（原型 appHead）
                Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    // 浮层顶随头部实测高度（64dp 硬编码在 fs1.3 会压住副行，审查 n11）
                    var treeHeaderPx by remember { mutableStateOf(0) }
                    Column(modifier = Modifier.fillMaxSize()) {
                    FcHeader(
                        modifier = Modifier.onGloballyPositioned { treeHeaderPx = it.size.height },
                        title = selectedSession?.title.orEmpty().ifEmpty { "FineCat" },
                        // TREE05/CHAT08：树页标题不挂绿色连接点（与对话页/首页同口径）。
                        // 连接状态只在设置页读，树行自己的活动/当前标识不受影响。
                        showConnectionDot = false,
                        leading = {
                            com.finecat.pimobile.android.ui.finecat.components.FcIconButton(
                                onClick = { drawerOpen = true },
                                icon = FineCatIcons.Menu,
                                contentDescription = "会话列表",
                                solid = true,
                                modifier = Modifier.testTag("finecat.menu"),
                            )
                        },
                        trailing = {
                            FcViewSeg(
                                icons = listOf(FineCatIcons.Chat, FineCatIcons.Branch),
                                labels = listOf("对话", "tree"),
                                selected = selectedView,
                                onSelect = { selectedView = it },
                                enabledItems = listOf(true, true),
                                modifier = Modifier.testTag("finecat.viewSeg"),
                            )
                        },
                    )
                    FineCatTreeView(
                        stateHolder = stateHolder,
                        onBack = { selectedView = 0 },
                        treeScreenState = treeScreenState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                    // 树页的全局瞬态提示（「已在此处」不回对话页，停在哪就要在哪看到）
                    // 必须浮在头部下方**不占布局**：流内 Text 的出现/消失会推挤树行，
                    // 3.5s 自动清除的边界上点击坐标会整体漂移（F06 设备实测）。
                    GlobalTransientNotice(
                        stateHolder = stateHolder,
                        modifier = Modifier.align(Alignment.TopCenter).padding(
                            top = with(LocalDensity.current) { treeHeaderPx.toDp() },
                        ),
                    )
                }
            } else Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding()
                    .nestedScroll(remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (source == NestedScrollSource.UserInput && available != Offset.Zero) modelSheetOpen = false
                                return Offset.Zero
                            }
                        }
                    })
                    .onGloballyPositioned { pageOrigin = it.positionInWindow() }
                    .pointerInput(Unit) {
                        // 旁观轻点，不消费事件；输入区内触控和正文拖动照常交给子节点。
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val card = composerBounds ?: return@awaitEachGesture
                            val origin = pageOrigin
                            if (card.contains(down.position + origin)) return@awaitEachGesture
                            val dismissingAssist = assistOpen
                            if (dismissingAssist) down.consume()
                            var tap = true
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (dismissingAssist) event.changes.forEach { it.consume() }
                                if (event.changes.size != 1 ||
                                    (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                    pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                ) tap = false
                                if (!pointer.pressed && pointer.previousPressed) {
                                    if (tap && !card.contains(pointer.position + origin)) {
                                        dropAssist()
                                        dismissComposer()
                                    }
                                    break
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                FcHeader(
                    // 空态首页只有菜单按钮：原型 `appHead({ menu: true, title: '' })`
                    // 会话页只留标题与 ViewSeg，连接状态在设置页查看。
                    title = if (showHome) "" else selectedSession?.title.orEmpty().ifEmpty { "FineCat" },
                    showConnectionDot = false,
                    // 顶栏标题点击改名（旧 UI 同口径：会话存在 + 已连接 + 网关支持 name）
                    onTitleClick = if (!showHome && selectedSession != null && connected &&
                        stateHolder.supportsRequest("name")
                    ) {
                        { renameTarget = selectedSession }
                    } else {
                        null
                    },
                    leading = {
                        com.finecat.pimobile.android.ui.finecat.components.FcIconButton(
                            onClick = { drawerOpen = true },
                            icon = FineCatIcons.Menu,
                            contentDescription = "会话列表",
                            solid = true,
                            floating = showHome,
                            modifier = Modifier.testTag("finecat.menu"),
                        )
                    },
                    trailing = if (showHome) {
                        null
                    } else {
                        {
                            FcViewSeg(
                                icons = listOf(FineCatIcons.Chat, FineCatIcons.Branch),
                                labels = listOf("对话", "tree"),
                                selected = selectedView,
                                onSelect = { selectedView = it },
                                enabledItems = listOf(true, true),
                                modifier = Modifier.testTag("finecat.viewSeg"),
                            )
                        }
                    },
                )
                if (sessionState?.isCompacting == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FcStatusTag(text = "上下文压缩中，暂不能发送", style = FcTagStyle.Wait)
                    }
                }
                // 全局瞬态提示（对话页原位，tree 页由 GlobalTransientNotice 同步可见）
                GlobalTransientNotice(stateHolder = stateHolder)
                // 失败/未确认发送的恢复条；已有会话纯文本明确被拒也可手动取回。
                stateHolder.outgoingPreviews.forEach { preview ->
                    val visibleHere = preview.isVisibleFor(
                        snapshot.selectedSessionId,
                        stateHolder.newSessionEpoch,
                    ) && (
                        preview.state == com.finecat.pimobile.android.OutgoingImagePreviewState.UNCONFIRMED ||
                            preview.state == com.finecat.pimobile.android.OutgoingImagePreviewState.FAILED
                        )
                    if (visibleHere) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp)
                                .testTag("finecat.outgoingPreview"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (preview.state == com.finecat.pimobile.android.OutgoingImagePreviewState.FAILED) {
                                    if (preview.images.isEmpty()) "消息发送失败" else "图片发送失败"
                                } else {
                                    if (preview.images.isEmpty()) "消息发送未确认" else "图片发送未确认"
                                },
                                style = LocalFineCatType.current.xs,
                                color = colors.text2,
                                modifier = Modifier.weight(1f),
                            )
                            FcTextButton(
                                text = "恢复草稿",
                                onClick = { stateHolder.restoreOutgoingPreviewDraft(preview.sendToken) },
                                enabled = stateHolder.canRestoreOutgoingDraft,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        // 输入区在同一 Column 中已预留 IME/nav；列表使用剩余空间，
                        // 兄弟节点再加 imePadding 会重复扣高，把历史正文压到不可见。
                        .weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(historyScroll)
                            .testTag("finecat.list"),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 0.dp, bottom = 20.dp),
                    ) {
                        if (!showHome) {
                        if (showStandaloneLoadingRow) {
                            item(key = "loading-older") {
                                HistoryOlderLoadingText()
                            }
                        }
                        if (showHistoryErrorRow) {
                            item(key = "history-error") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = GatewayErrorText.request("history", historyError),
                                        style = LocalFineCatType.current.xs,
                                        color = colors.err,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FcTextButton(
                                        text = "重试",
                                        onClick = { stateHolder.reloadSelectedHistory() },
                                    )
                                }
                            }
                        }
                        itemsIndexed(displayRounds, key = { _, round -> displayRoundKey(round) }) { roundIndex, round ->
                            val lastVisible = round.lastOrNull(::fineCatEntryHasVisibleBody)
                            val nextVisible = displayRounds.subList(roundIndex + 1, displayRounds.size)
                                .firstNotNullOfOrNull { it.firstOrNull(::fineCatEntryHasVisibleBody) }
                            val nextHasTimeSeparator = displayRounds.getOrNull(roundIndex + 1)?.let {
                                timeSeparators.containsKey(displayRoundKey(it))
                            } == true
                            val bottomGap = if (lastVisible != null && nextHasTimeSeparator) 24 else
                                fineCatEntryGap(lastVisible, nextVisible, 24)
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = bottomGap.dp)) {
                                // 时间分隔行（原型 `.tsep`）：只画在回合的首个显示回合顶部，
                                // 规则与文案见 FcTimeSeparators.kt；时间未知的回合不画。
                                val roundKey = displayRoundKey(round)
                                timeSeparators[roundKey]?.let { label ->
                                    FcTimeSeparator(
                                        text = label,
                                        modifier = Modifier.testTag("finecat.tsep.$roundKey"),
                                    )
                                }
                                // 加载提示嵌在最旧一条消息的内容上方，与消息同属一个稳定
                                // keyed item，不再单独占一行；id 唯一故只会命中首项。
                                // 最旧 displayEntry 可能位于回合中部（组内步骤组排前），按成员匹配而不是
                                // 只比回合首项，否则带工具步骤的最旧消息回合不渲染加载提示。
                                if (showHistoryLoadingRow && round.any { it.id == oldestEntryId }) {
                                    HistoryOlderLoadingText()
                                }
                                round.forEachIndexed { entryIndex, entry ->
                                    // 空正文不产生间隔；保留它的Box/offset/key用于原滚动定位。
                                    if (fineCatEntryHasVisibleBody(entry)) {
                                        val previous = round.subList(0, entryIndex).lastOrNull(::fineCatEntryHasVisibleBody)
                                        val gap = fineCatEntryGap(previous, entry, 12)
                                        if (gap > 0) Spacer(modifier = Modifier.height(gap.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onGloballyPositioned { coords ->
                                                entryOffsets[entry.id] =
                                                    coords.positionInParent().y.roundToInt()
                                            },
                                    ) {
                                    // dispose 即失效偏移：回合被回收后其上方内容高度可能已变
                                    // （组收展/图片迟到/历史重写），保留旧值会让 jump 首读到
                                    // stale 偏移且跳过二跳保护；移除后首读回到 null→二跳路径。
                                    DisposableEffect(entry.id) {
                                        onDispose { entryOffsets.remove(entry.id) }
                                    }
                                    FineCatEntryView(
                                        entry = entry,
                                        copiedMessageId = copiedMessageId,
                                        processSteps = stepsByGroup[entry.id].orEmpty(),
                                        groupExpanded = expandedStepGroups.containsKey(entry.id),
                                        expandedStepId = expandedStepGroups[entry.id],
                                        processIsRunning = workingStepsGroupId == entry.id,
                                        holderThumbnails = stateHolder.attachmentThumbnails,
                                        holderImageStates = stateHolder.attachmentStates,
                                        onRetryImage = stateHolder::retryAttachment,
                                        onOpenImage = { attachments, index -> imagePreview = ConversationImagePreviewRequest(attachments, index) },
                                        onCopyText = { entryId, text ->
                                            try {
                                                clipboard.setText(AnnotatedString(text))
                                                copiedMessageId = entryId
                                                copiedTick += 1
                                            } catch (_: Exception) {
                                                stateHolder.notifyUser("复制失败，请重试")
                                            }
                                        },
                                        onOpenGroup = { groupId ->
                                            pinnedToBottom = false
                                            expandedStepGroups = if (expandedStepGroups.containsKey(groupId))
                                                expandedStepGroups - groupId else expandedStepGroups + (groupId to null)
                                        },
                                        onOpenStep = { groupId, stepId ->
                                            pinnedToBottom = false
                                            expandedStepGroups = expandedStepGroups +
                                                (groupId to if (expandedStepGroups[groupId] == stepId) null else stepId)
                                        },
                                    )
                                    }
                                }
                            }
                        }
                        if (showWorkingRow) {
                            item(key = "working") {
                                FcWorking(
                                    text = "Working",
                                    modifier = Modifier.testTag("finecat.working"),
                                )
                            }
                        }
                        }
                    }
                    if (showHome) {
                        // 原型1B：纯色画布，Pi标志与签名整体居中；不足放置时仍隐藏装饰。
                        val brandStyle = LocalFineCatType.current.body.copy(
                            fontSize = 18.sp,
                            lineHeight = (18 * 1.55f).sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("finecat.home"),
                        ) {
                            Layout(
                                modifier = Modifier.fillMaxSize(),
                                content = {
                                    // 只有标题/标语文字单击进自定义；标志、间隙与长按不触发。
                                    Column(
                                        modifier = Modifier
                                            .padding(horizontal = 24.dp, vertical = 16.dp)
                                            .testTag("finecat.homeBrand"),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.pi_logo),
                                            contentDescription = "Pi 标志",
                                            tint = colors.text,
                                            modifier = Modifier.size(38.dp).testTag("finecat.brandLogo"),
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        val brandTextClick = Modifier
                                            .semantics {
                                                role = Role.Button
                                                onClick("自定义首页品牌") { heroEditorOpen = true; true }
                                            }
                                            .onKeyEvent { event ->
                                                if (event.type == KeyEventType.KeyUp &&
                                                    event.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)
                                                ) {
                                                    heroEditorOpen = true
                                                    true
                                                } else false
                                            }
                                            .focusable()
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onLongPress = {},
                                                    onTap = { point ->
                                                        // 不让文字的最小触控扩展区覆盖标志和留白。
                                                        if (point.x >= 0 && point.x < size.width &&
                                                            point.y >= 0 && point.y < size.height
                                                        ) heroEditorOpen = true
                                                    },
                                                )
                                            }
                                        val (brandTitle, brandTagline) = homeBrandCopy(stateHolder.heroTitle, stateHolder.heroTagline)
                                        if (brandTitle.isEmpty() && brandTagline.isEmpty()) {
                                            FcIconButton(
                                                icon = FineCatIcons.Edit,
                                                contentDescription = "自定义首页品牌",
                                                onClick = { heroEditorOpen = true },
                                                buttonSize = 48.dp,
                                                iconSize = 20.dp,
                                                modifier = Modifier.testTag("finecat.brandEdit"),
                                            )
                                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            if (brandTitle.isNotEmpty()) Text(
                                                text = brandTitle,
                                                style = brandStyle,
                                                color = colors.text2,
                                                modifier = Modifier
                                                    .testTag("finecat.brandTitle")
                                                    .then(brandTextClick),
                                            )
                                            if (brandTagline.isNotEmpty()) Text(
                                                text = brandTagline,
                                                style = brandStyle,
                                                color = colors.text2,
                                                modifier = Modifier.testTag("finecat.brandTagline")
                                                    .then(brandTextClick),
                                            )
                                        }
                                    }
                                },
                            ) { measurables, constraints ->
                                val brand = measurables.single().measure(
                                    constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity),
                                )
                                layout(constraints.maxWidth, constraints.maxHeight) {
                                    // 键盘/横屏空间不足时不放置装饰，避免侵入顶栏或输入区。
                                    if (brand.height <= constraints.maxHeight) {
                                        val y = (constraints.maxHeight - brand.height) / 2
                                        brand.placeRelative((constraints.maxWidth - brand.width) / 2, y)
                                    }
                                }
                            }
                        }
                    }
                }
                // 扩展状态行只显示当前会话的提示，不改变忙闲或发送状态。
                ExtensionStatusBar(
                    stateHolder = stateHolder,
                    sessionId = snapshot.selectedSessionId,
                    color = colors.text3,
                )
                // P4b：忙时点发送 → 草稿吸附条（注入本轮 / 排队下轮 / 取回）
                stateHolder.pendingDispatch?.let { pending ->
                    if (pending.sessionId == snapshot.selectedSessionId) {
                        PendingDispatchBar(
                            draft = pending.draft,
                            dispatchPending = stateHolder::dispatchPending,
                            onCancel = stateHolder::cancelPendingDispatch,
                        )
                    }
                }
                // 原生桥接没有队列明细；已确认的手机派发方式只标“已提交”，不猜条数/消费进度。
                sessionState?.takeIf { it.hasQueuedMessages }?.let { queueState ->
                    val queued = queueState.queuedMessages
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .testTag("finecat.queued"),
                    ) {
                        if (queued?.isEmpty != false) {
                            val modes = queueState.submittedQueueModes
                            Text(
                                text = if (modes.isEmpty()) "有消息等待执行" else modes.joinToString(" · ") {
                                    if (it == "steer") "Steer 已提交" else "Follow-up 已提交"
                                },
                                style = LocalFineCatType.current.xs,
                                color = colors.text3,
                                modifier = Modifier.testTag("finecat.queued.pending"),
                            )
                        }
                        queued?.steering.orEmpty().forEach { Text(
                            text = "Steer: $it",
                            style = LocalFineCatType.current.xs,
                            color = colors.text3,
                        ) }
                        queued?.followUp.orEmpty().forEach { Text(
                            text = "Follow-up: $it",
                            style = LocalFineCatType.current.xs,
                            color = colors.text3,
                        ) }
                        if ("dequeue" in stateHolder.gatewayState.capabilities) {
                            FcTextButton(
                                text = if (queueState.pendingMessages || queued?.let { it.steering.size + it.followUp.size > 1 } == true)
                                    "全部撤回到输入框" else "撤回到输入框",
                                onClick = stateHolder::recallQueuedMessages,
                                enabled = stateHolder.canRecallQueuedMessages,
                                modifier = Modifier.align(Alignment.End).testTag("finecat.queued.recall"),
                            )
                        }
                    }
                }
                FcComposer(
                    text = stateHolder.messageDraft,
                    inputAssist = ComposerInputAssist(
                        value = editorValue,
                    onChange = ::updateEditor,
                    transformation = referenceTransformation,
                    onKey = { event ->
                        if (assistToken == null) false else when (event.key) {
                            Key.DirectionDown, Key.DirectionUp -> {
                                if (event.type == KeyEventType.KeyDown && assistRows.isNotEmpty()) {
                                    val delta = if (event.key == Key.DirectionDown) 1 else -1
                                    assistIndex = (selectedAssistIndex + delta + assistRows.size) % assistRows.size
                                }
                                assistRows.isNotEmpty()
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                if (event.isShiftPressed) false else {
                                    if (event.type == KeyEventType.KeyDown) acceptAssist(selectedAssistIndex)
                                    true
                                }
                            }
                            Key.Escape -> {
                                if (event.type == KeyEventType.KeyDown) dropAssist()
                                true
                            }
                            else -> false
                        }
                    },
                    panel = assistToken?.let { token ->
                        { width, availableHeight ->
                            val capability = if (token.isFile) "input-completions" else "input-resources"
                            val notice = when {
                                !connected -> "未连接网关"
                                capability !in stateHolder.gatewayState.capabilities -> "网关需要更新输入补全支持"
                                !assist.context.valid -> "先选一个目录"
                                token.isFile && assist.completionsError != null -> assist.completionsError
                                !token.isFile && assist.resourcesError != null -> assist.resourcesError
                                token.isFile && (assist.token != token.text || assist.completionsLoading) -> "加载中…"
                                !token.isFile && assist.resourcesLoading -> "加载中…"
                                else -> null
                            }
                            FcInputAssistPanel(width, availableHeight, token, assistRows, selectedAssistIndex, notice,
                                assist.previewIncomplete, onAccept = ::acceptAssist,
                                onParent = {
                                    val parent = parentInputAssistToken(token.text)
                                    updateEditor(replaceInputAssistToken(editorValue, token, parent,
                                        cursorOffset = parent.length - if (parent.endsWith('"')) 1 else 0,
                                        consumeClosingQuote = true))
                                },
                                onRetry = { if (token.isFile) stateHolder.requestInputCompletions(token.text) else stateHolder.refreshInputResources() })
                        }
                    },
                    ),
                    homeStyle = showHome,
                    sendReady = showHome || connected,
                    showDirectory = true,
                    directory = displayedCwd,
                    directoryBranch = stateHolder.worktreeLocation?.takeIf { stateHolder.supportsWorktrees && it.cwd == displayedCwd && it.repoRoot != null }?.let { location ->
                        location.branch ?: location.worktrees.orEmpty().filter { displayedCwd == it.path || displayedCwd?.startsWith(it.path + "/") == true }
                            .maxByOrNull { it.path.length }?.commit
                    },
                    onDirectoryClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        if (stateHolder.supportsWorktrees && displayedCwd != null && stateHolder.worktreeLocation?.cwd == displayedCwd && stateHolder.worktreeLocation?.repoRoot != null) {
                            stateHolder.openWorktree(displayedCwd)
                        } else {
                            directoryForSession = !showHome
                            stateHolder.beginDirectoryBrowsing(displayedCwd ?: "/")
                            homeDirSheet = true
                        }
                    },
                    onTextChange = { stateHolder.messageDraft = it },
                    running = isStreaming || sessionState?.isCompacting == true,
                    // 流式中带草稿点发送 = 吸附待派发（门面 capturePendingDispatch）；
                    // 压缩中 pi 会拒 prompt，保持方点停止语义。
                    dispatchWhileRunning = isStreaming && sessionState?.isCompacting != true,
                    expanded = composerFocused || stateHolder.messageDraft.isNotEmpty(),
                    withShots = stateHolder.photosTrayOpen,
                    modelName = modelDisplayName(effectiveModel) ?: "选择模型",
                    thinkingLabel = effectiveThinking ?: "-",
                    photos = stateHolder.recentPhotos,
                    photoThumbnails = stateHolder.recentPhotoThumbnails,
                    selectedPhotoUris = stateHolder.preparedUris.toSet() + stateHolder.preparingPhotoUris,
                    onToggleShotsTray = { openPhotosTray() },
                    onTogglePhoto = { uri -> toggleTrayPhoto(uri, stateHolder) },
                    onMorePhotos = onPickImage,
                    onModelClick = {
                        contextAnchor = null
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        modelSheetOpen = true
                    },
                    contextUsage = sessionState?.contextUsage,
                    onContextClick = { modelSheetOpen = false; contextAnchor = it },
                    onContextBoundsChange = { if (contextAnchor != null) contextAnchor = it },
                    preparedCount = stateHolder.preparedImages.size,
                    preparedImages = stateHolder.preparedImages,
                    preparedPhotoUris = stateHolder.preparedUris,
                    preparingPhotoUris = stateHolder.preparingPhotoUris,
                    onRemovePreparedImage = stateHolder::removePreparedImage,
                    onSend = {
                        if (showHome || stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED) {
                            pinnedToBottom = true
                        }
                        stateHolder.sendComposerDraft(requireDirectory = true, requireConnection = !showHome)
                    },
                    onStop = stateHolder::abortCurrent,
                    showAttach = true,
                    onFocusedChange = { composerFocused = it },
                    onBoundsChange = { composerBounds = it },
                    modifier = Modifier
                        // 底部栏要跟着键盘上移，否则输入框会被键盘盖住。
                        // navigationBarsPadding 补手势条/导航键区：imePadding 在键盘
                        // 弹起时已含这段，键盘收起时不会，所以要同链补。
                        .onGloballyPositioned { composerDockTop = it.positionInRoot().y }
                        .imePadding()
                        .navigationBarsPadding()
                        .testTag("finecat.composer"),
                )
            }
            // 锚定真实dock上沿：目录、两行输入、选图区及IME变化时保持12dp视觉间距。
            if (!showHome && selectedView == 0 && jumpVisible && composerDockTop != null) {
                val scope = rememberCoroutineScope()
                FcJumpButton(
                    onClick = {
                        pinnedToBottom = true
                        scope.launch {
                            // 普通吸底滚动；jumpToLatest（切分支，有副作用）由
                            // P4c 在 isBehindLatest 判定下单独接入
                            programmaticScrolls += 1
                            try {
                                probeJumpObservation(
                                    stage = "jumpButtonScrollBefore",
                                    stateHolder = stateHolder,
                                    listState = listState,
                                )
                                listState.scrollToTimelineBottom(
                                    lastItemIndex = (listItemCount - 1).coerceAtLeast(0),
                                    animated = true,
                                )
                                probeJumpObservation(
                                    stage = "jumpButtonScrollAfter",
                                    stateHolder = stateHolder,
                                    listState = listState,
                                )
                            } finally {
                                programmaticScrolls -= 1
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(0, ((composerDockTop ?: rootTop) - rootTop - 56.dp.toPx()).roundToInt().coerceAtLeast(0))
                        }
                        .testTag("finecat.jump"),
                )
            }
            // P8 home 工作目录 sheet（WorkspaceDirectoryUi 真源）
            FcSheetHost(visible = stateHolder.worktreeUi.open) {
                FineCatWorktreeSheet(stateHolder) {
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                    stateHolder.closeWorktree()
                    directoryForSession = !showHome
                    stateHolder.beginDirectoryBrowsing(displayedCwd ?: "/")
                    homeDirSheet = true
                }
            }
            FcSheetHost(visible = homeDirSheet) {
                FineCatDirectorySheet(stateHolder = stateHolder, onDismiss = { homeDirSheet = false },
                    sessionMode = directoryForSession, initialPath = displayedCwd,
                    continueBlockedReason = if (directoryForSession) stateHolder.worktreeBlockReason() else null,
                    onContinue = if (stateHolder.supportsWorktrees && directoryForSession) {
                        { path -> homeDirSheet = false; stateHolder.continueInDirectory(path) }
                    } else null,
                    onNewSession = { path -> homeDirSheet = false; stateHolder.newSessionInWorkspace(path) },
                    onSelectHome = if (stateHolder.supportsWorktrees) {
                        { path ->
                            homeDirSheet = false
                            if (path == "/") stateHolder.selectFineCatDirectory(null)
                            else stateHolder.newSessionInWorkspace(path)
                        }
                    } else null,
                )
            }
            // P9 设置整页（原型 screenSettings：居中标题 + 返回箭头 + .sec-label 卡片组）。
            // 取代旧的网关底部弹卡；Provider 表单仍留在 sheet 上（二级操作）。
            if (gatewaySheetOpen) {
                FineCatSettingsScreen(
                    stateHolder = stateHolder,
                    onBack = { gatewaySheetOpen = false },
                    onOpenScanner = { showQrScanner = true },
                    onOpenManualPairing = { showManualPairing = true },
                    onEditProvider = { id ->
                        if (stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED) providerEditId = id
                    },
                    modifier = Modifier.statusBarsPadding(),
                )
            }
            // F01 Provider 编辑表单（原型 pv-sheet/pv-new）：设置页行/添加入口带 id 直达，
            // 中间不再过 Provider 列表层（列表已由 SettingsScreen 渲染）。
            // 叠层不改设置页组合，取消后滚动位置自然保留。
            FcSheetHost(value = providerEditId) { editProviderId ->
                FineCatProviderEditSheet(
                    stateHolder = stateHolder,
                    providerId = editProviderId,
                    isNew = editProviderId == NEW_PROVIDER_ID,
                    closing = providerEditId == null,
                    onDismiss = { providerEditId = null },
                )
            }
            imagePreview?.let { request ->
                val previewSessionId = snapshot.selectedSessionId
                ConversationImagePreviewDialog(
                    request = request,
                    thumbnails = stateHolder.attachmentThumbnails,
                    onDismiss = { imagePreview = null },
                    lightbox = true,
                    overlayColor = colors.overlayDeep,
                    loadFullImage = { attachment ->
                        val bytes = previewSessionId?.let { stateHolder.readFullAttachment(it, attachment) }
                        if (bytes == null) null else withContext(Dispatchers.IO) { decodeSampledBitmap(bytes) }
                    },
                )
            }
            // P7 会话抽屉（原型 .drawer：左滑面板，点遮罩关闭）
            FineCatSessionDrawer(
                stateHolder = stateHolder,
                open = drawerOpen,
                onDismiss = { drawerOpen = false },
                onOpenSettings = { gatewaySheetOpen = true },
                onOpenTheme = { stateHolder.updateAppearanceMode(if (colors.isDark) "light" else "dark") },
                // 具体目录药丸优先；“全部”沿用首页选择或当前会话目录。
                onNewSession = { directory ->
                    val cwd = directory?.takeIf(String::isNotBlank)
                        ?: if (showHome) stateHolder.fineCatNewSessionDirectoryPath else selectedSession?.cwd
                    dismissComposer()
                    drawerOpen = false
                    selectedView = 0
                    stateHolder.prepareNewSession(cwd)
                },
                onRename = { session ->
                    if (!connected) {
                        stateHolder.notifyUser("请先连接 Pi 网关")
                    } else if (!stateHolder.supportsRequest("name")) {
                        stateHolder.notifyUser("网关不支持会话改名，请升级网关")
                    } else {
                        // 关抽屉再改名：改名失败的瞬态提示在抽屉 z 序之下，不关会被遮罩压暗
                        // （与 onOpenSettings 同口径）。
                        drawerOpen = false
                        renameTarget = session
                    }
                },
            )
            // 抽屉行长按改名（pi /name）：确认后发 name 帧，标题只随 session-named 应答刷新。
            renameTarget?.let { target ->
                SessionRenameDialog(
                    containerColor = colors.surface,
                    confirmOnOutsideClick = true,
                    saving = renameSaving,
                    saveError = renameError,
                    onEdit = { renameError = null },
                    initial = target.title,
                    onDismiss = { if (!renameSaving) renameTarget = null },
                    onConfirm = { name ->
                        renameSaving = true
                        renameError = null
                        stateHolder.saveSessionName(target.id, name) { error ->
                            if (renameTarget?.id == target.id) {
                                renameSaving = false
                                renameError = error
                                if (error == null) renameTarget = null
                            }
                        }
                    },
                )
            }
            // 抽屉与设置整页都会遮住正文提示，反馈必须绘制在这些覆盖层之后。
            // 独立 tag 区分前景提示，避免测试命中被遮住的正文节点。
            if (drawerOpen || gatewaySheetOpen) {
                GlobalTransientNotice(
                    stateHolder = stateHolder,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp),
                    testTag = "finecat.noticeOverlay",
                )
            }
            // 首页品牌自定义（旧 HeroCopyEditorDialog 复用：updateHeroTitle/Tagline）
            if (heroEditorOpen) {
                HeroCopyEditorDialog(
                    stateHolder = stateHolder,
                    containerColor = colors.surface,
                    confirmOnOutsideClick = true,
                    onDismiss = { heroEditorOpen = false },
                )
            }
            stateHolder.branchDraftConfirmation?.let {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = stateHolder::dismissBranchDraftConfirmation,
                    containerColor = colors.surface,
                    titleContentColor = colors.text,
                    textContentColor = colors.text2,
                    modifier = Modifier.testTag("finecat.branchDraftConfirmation"),
                    title = { Text("保留未发送内容？", style = LocalFineCatType.current.title) },
                    text = { Text("跳转后可保留当前草稿，或改用节点内容。", style = LocalFineCatType.current.sm) },
                    confirmButton = {
                        FcTextButton(
                            text = "使用节点内容",
                            onClick = { stateHolder.confirmBranchDraftNavigation(useNodeDraft = true) },
                            modifier = Modifier.testTag("finecat.branchDraftReplace"),
                        )
                    },
                    dismissButton = {
                        FcTextButton(
                            text = "保留草稿",
                            onClick = { stateHolder.confirmBranchDraftNavigation(useNodeDraft = false) },
                            modifier = Modifier.testTag("finecat.branchDraftKeep"),
                        )
                    },
                )
            }
            // P4d：全局错误（R24）——定向动作按 action 类型，不把所有错误都接重连
            stateHolder.platformError?.let { error ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = stateHolder::clearPlatformError,
                    title = { Text(text = "Pi Mobile", style = LocalFineCatType.current.title) },
                    text = { Text(text = error, style = LocalFineCatType.current.sm) },
                    confirmButton = {
                        when (stateHolder.platformErrorAction) {
                            AndroidSharedStateHolder.PlatformErrorAction.RETRY_DIRECTORY -> FcTextButton(
                                text = "重试",
                                onClick = {
                                    stateHolder.retryDirectoryBrowsing()
                                },
                            )
                            AndroidSharedStateHolder.PlatformErrorAction.RECONNECT -> FcTextButton(
                                text = "重新连接",
                                onClick = {
                                    stateHolder.clearPlatformError()
                                    stateHolder.connect()
                                },
                            )
                            AndroidSharedStateHolder.PlatformErrorAction.DISMISS_ONLY -> {}
                        }
                    },
                    dismissButton = {
                        FcTextButton(text = "好", onClick = stateHolder::clearPlatformError)
                    },
                )
            }
            contextAnchor?.takeIf { composerControlsExpanded }?.let { anchor ->
                // 禁用原因（R6/REL120-01）：直接读与 canStartManualCompact 同源的闸门原因，
                // 不另写一套推断，避免"能点但提示在压缩中"或离线误报压缩中。
                val compactBlocked = stateHolder.manualCompactBlockedReason()
                val compactReady = compactBlocked == null
                // 禁用原因与按钮/执行闸门同源（REL120-01）：用同一份短句，
                // 避免"能点但提示在压缩中"或原因与动作目标会话不一致。
                val compactDisabledReason = compactBlocked?.let(stateHolder::manualCompactBlockedNotice)
                FcContextPopover(
                    anchor = anchor,
                    usage = sessionState?.contextUsage,
                    isCompacting = sessionState?.isCompacting == true,
                    onDismiss = { contextAnchor = null },
                    // CTX02：真实压缩入口；「会中止当前回答」说明行在浮层按钮上方。
                    onCompact = if (compactReady) {
                        {
                            contextAnchor = null
                            stateHolder.startManualCompact()
                        }
                    } else null,
                    // 仅已选会话且有统计对象时才可补拉；新会话/空白页无从刷新（M3）。
                    onRefreshUsage = if (snapshot.selectedSessionId != null &&
                        sessionState?.contextUsage != null &&
                        stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
                    ) {
                        { stateHolder.refreshSessionControls() }
                    } else null,
                    usageIsUnknown = sessionState?.contextUsage?.let {
                        it.percent == null && it.tokens == null
                    } == true,
                    compactDisabledReason = compactDisabledReason,
                )
            }
            // 模型目录和即时选择语义保持；底部面板独立于输入框焦点，外点/返回关闭。
            FcSheetHost(visible = modelSheetOpen) {
                LaunchedEffect(modelSheetOpen) { if (modelSheetOpen) stateHolder.refreshModelCatalog() }
                FcModelPicker(
                    catalog = snapshot.modelCatalog,
                    onRetry = if (stateHolder.modelCatalogError != null &&
                        stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
                    ) stateHolder::refreshModelCatalog else null,
                    emptyText = when {
                        stateHolder.gatewayState.connection != GatewayConnectionState.CONNECTED -> "网关未连接"
                        stateHolder.modelCatalogIsLoading -> "模型加载中…"
                        stateHolder.modelCatalogError != null -> stateHolder.modelCatalogError!!
                        else -> "暂无可用模型"
                    },
                    current = effectiveModel,
                    currentThinking = effectiveThinking,
                    onSelect = { provider, modelId, level ->
                        stateHolder.selectModel(provider, modelId, level)
                    },
                    onDismiss = { modelSheetOpen = false },
                )
            }
            FcSheetHost(visible = showManualPairing) {
                FineCatManualPairingSheet(stateHolder, onDismiss = { showManualPairing = false })
            }
            // 扫码配对：全屏相机预览挂在根层（PiProductApp 同先例），成功只走 holder.pair。
            if (showQrScanner) {
                GatewayQrScannerScreen(
                    onCode = { payload ->
                        showQrScanner = false
                        stateHolder.pair(payload)
                    },
                    onCancel = { showQrScanner = false },
                    onManualEntry = { showQrScanner = false; showManualPairing = true },
                    onFailure = { message ->
                        showQrScanner = false
                        stateHolder.showPlatformError(message)
                    },
                )
            }
        }
    }
}

/** 忙时吸附条：草稿已进 pendingDispatch，用户选择注入本轮 / 排队下轮 / 取回。 */
@Composable
private fun PendingDispatchBar(
    draft: String,
    dispatchPending: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .testTag("finecat.pendingBar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "待发送：${draft.take(40)}",
            style = type.xs,
            color = colors.text2,
            modifier = Modifier.weight(1f),
        )
        FcTextButton(text = "取回", onClick = onCancel, muted = true)
        FcTextButton(text = "排队下轮", onClick = { dispatchPending("followUp") })
        FcTextButton(text = "注入本轮", onClick = { dispatchPending("steer") })
    }
}

/**
 * 「加载更早消息…」提示行。非空 displayEntries 时嵌在最旧消息的 item 顶部，仅在列表
 * 没有任何消息时作为独立 item 兜底；两处共用本函数以保证文字、颜色与间距等价。
 */
@Composable
private fun HistoryOlderLoadingText() {
    Text(
        text = "加载更早消息…",
        style = LocalFineCatType.current.xs,
        color = LocalFineCatColors.current.text3,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/** 单条展示条目 → FineCat 视觉件。 */
@Composable
private fun FineCatEntryView(
    entry: ConversationDisplayEntry,
    copiedMessageId: String?,
    processSteps: List<FcStep>,
    groupExpanded: Boolean,
    expandedStepId: String?,
    processIsRunning: Boolean,
    holderThumbnails: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
    holderImageStates: Map<String, com.finecat.pimobile.android.AttachmentLoadState>,
    onRetryImage: (String) -> Unit,
    onOpenImage: (List<GatewayImageAttachment>, Int) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenStep: (String, String) -> Unit,
    onCopyText: (String, String) -> Unit,
) {
    val colors = LocalFineCatColors.current
    when (entry) {
        is ConversationDisplayEntry.Message -> {
            val item = entry.item
            when (item.kind) {
                ProjectionKind.USER -> FcUserBubble(
                    message = FcChatMessage(
                        id = item.id,
                        isUser = true,
                        text = item.text,
                        imageCount = item.images.size,
                    ),
                    attachments = item.images,
                    thumbnails = holderThumbnails,
                    imageStates = holderImageStates,
                    onRetryImage = onRetryImage,
                    onOpenImage = onOpenImage,
                    copied = copiedMessageId == item.id,
                    onCopy = { text -> onCopyText(item.id, text) },
                )
                ProjectionKind.ASSISTANT -> FcAiMessage(
                    message = FcChatMessage(
                        id = item.id,
                        isUser = false,
                        text = item.text,
                    ),
                    copied = copiedMessageId == item.id,
                    onCopy = { text -> onCopyText(item.id, text) },
                )
                ProjectionKind.SYSTEM -> {
                    // 旧 SystemRow 口径：title + text；compact/done 只有 title 也要可见
                    val systemText = listOf(item.title, item.text)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (systemText.isNotEmpty()) {
                        Text(
                            text = systemText,
                            style = LocalFineCatType.current.xs,
                            color = if (item.isError) colors.err else colors.text3,
                        )
                    }
                }
                else -> {}
            }
        }
        is ConversationDisplayEntry.Process -> {
            if (processSteps.isNotEmpty()) {
                FcStepsBlock(
                    messageId = entry.group.id,
                    steps = processSteps,
                    onOpenGroup = onOpenGroup,
                    expanded = groupExpanded,
                    expandedStepId = expandedStepId,
                    onOpenStep = { stepId -> onOpenStep(entry.id, stepId) },
                    isRunning = processIsRunning,
                )
            }
        }
    }
}


/** 托盘点击同时处理已选与在途图片，取消不等待预处理完成。 */
private fun toggleTrayPhoto(
    uri: android.net.Uri,
    stateHolder: AndroidSharedStateHolder,
) {
    stateHolder.togglePreparedPhoto(uri)
}

/**
 * 全局瞬态提示（finecat.notice）：原先只在对话页渲染，树页 alreadyAt「已在此处」、
 * 网关不支持类提示停在哪一页就应能在哪一页看到（F06 实测缺口）。
 */
@Composable
private fun GlobalTransientNotice(
    stateHolder: AndroidSharedStateHolder,
    modifier: Modifier = Modifier,
    /**
     * 独立 tag（默认 `finecat.notice`）。浮层之上的那份用 `finecat.noticeOverlay`：
     * 同 tag 的两份节点会让 `hasTag("finecat.notice")` 这类断言在被遮挡时也通过
     * （审查 F2 最初的假绿），也给后续用例埋坑。
     */
    testTag: String = "finecat.notice",
) {
    stateHolder.transientNotice?.let { notice ->
        Text(
            text = notice,
            style = LocalFineCatType.current.xs,
            color = LocalFineCatColors.current.text2,
            modifier = modifier
                .background(
                    LocalFineCatColors.current.surface2,
                    RoundedCornerShape(LocalFineCatRadius.current.tag),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(testTag),
        )
    }
}
