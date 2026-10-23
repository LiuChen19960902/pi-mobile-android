package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.gateway.GatewayErrorText

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.finecat.pimobile.android.platform.thumbnailSampleSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.round
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatImageSize
import androidx.compose.ui.zIndex
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.BuildConfig
import com.finecat.pimobile.android.AttachmentLoadState
import com.finecat.pimobile.android.OutgoingImagePreview
import com.finecat.pimobile.android.OutgoingImagePreviewState
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.protocol.GatewayModelItem
import com.finecat.pimobile.shared.protocol.GatewayTreeNode
import com.finecat.pimobile.shared.protocol.GatewayModelSelection
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import androidx.core.content.ContextCompat
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import com.finecat.pimobile.shared.projection.TrajectoryNode
import com.finecat.pimobile.shared.projection.TrajectoryNodeKind
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    onNewSession: (String?) -> Unit = {},
    /** 抽屉已展开：系统返回先给关抽屉，页内“回空白首页”的 BackHandler 让路。 */
    drawerOpen: Boolean = false
) {
    val session = stateHolder.snapshot.sessions.firstOrNull { it.id == stateHolder.snapshot.selectedSessionId }
    val title = session?.title ?: "新建对话"
    // 同一壳层的两个状态：已绑会话＝带「对话/轨迹/树」三 tab 的会话页；未绑定且
    // 没有任何内容（含发送预览）＝空白首页。首发后 selectedSessionId 由网关 sent 帧
    // 自动绑定，界面就地从品牌页切到时间线，不再多一次导航。
    val sessionBound = stateHolder.snapshot.selectedSessionId != null
    // 背景装饰与页面品牌共用同一口径，历史加载和错误期间不显示欢迎内容。
    val blankHome = isBlankHome(stateHolder)
    var showRenameDialog by remember { mutableStateOf(false) }
    var showHeroEditor by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var showDirectoryBrowser by remember { mutableStateOf(false) }
    // 目录面板的两种进法：普通选目录 / 直接进「新建工作区」的建文件夹流程。
    var directoryBrowserCreateMode by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    // 树页点击节点成功切分支后自动回对话 tab（对齐 pi TUI 的 showTreeSelector：
    // 选中即关闭树选择器、回到对话）。alreadyAt 不切（提示"已在此处"）。
    val branchSwitchedTick = stateHolder.branchSwitchedTick
    LaunchedEffect(branchSwitchedTick) {
        if (branchSwitchedTick > 0) scope.launch { pagerState.animateScrollToPage(0) }
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissInput: () -> Unit = remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    // 回空白首页：只把当前会话的 cwd 交给 holder（首页默认沿用同一工作区），
    // 不在此处建 Pi 会话：会话仍由首条 message 创建。
    val goBlankHome: () -> Unit = {
        dismissInput()
        // 空白首页不显示分段控件，必须固定落在对话 tab，否则会从轨迹/树页
        // 停在无入口的页上。
        scope.launch { pagerState.scrollToPage(0) }
        onNewSession(session?.cwd)
    }
    // 系统返回：已有会话→先回空白首页；空白首页不拦截，交回系统（退出）。
    // 抽屉展开时不接手（那一下应该是收抽屉）。
    BackHandler(enabled = sessionBound && !drawerOpen) { goBlankHome() }
    LaunchedEffect(stateHolder.snapshot.selectedSessionId) { stateHolder.refreshSessionControls() }
    // tree 页每次切进来都重拉并重新定位（pi 的 /tree 是打开时快照，切页等价于重新打开）。
    var treeLocateTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.currentPage) {
        stateHolder.setTrajectoryActive(pagerState.currentPage == 1)
        if (pagerState.currentPage == 2) {
            stateHolder.loadSessionTree()
            treeLocateTick += 1
        }
    }
    DisposableEffect(stateHolder) {
        onDispose { stateHolder.setTrajectoryActive(false) }
    }

    // 页面自身不再画背景/实色底：根宿主（PiProductApp）常驻 PiAnimatedBackground
    // + 玻璃环境，这里 Scaffold 恒透明。空白首页/会话页同底同层，发送、选会话、
    // 回首页都只是同一页内内容区变化，不再出现整页换底式的“跳页”。
    Box(
        Modifier.fillMaxSize().testTag(if (blankHome) "home-screen" else "conversation-screen")
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        // 会话名点击改名（pi /name 的 UI 入口）；未选会话或网关不支持时不可点。
                        // 空白首页顶栏不出标题：品牌在页面中央，不在这里重复。
                        val canRename = session != null &&
                            stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
                            stateHolder.supportsRequest("name")
                        if (sessionBound) {
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .testTag("conversation-title")
                                    .then(
                                        if (canRename) {
                                            Modifier.clickable { showRenameDialog = true }
                                        } else Modifier
                                    )
                            )
                        }
                    },
                    navigationIcon = {
                        // 旧「返回 workspace」换成抽屉开关：会话列表现在只住左侧抽屉。
                        TopBarIconButton(
                            iconRes = R.drawable.ic_menu_bars,
                            description = "会话列表",
                            onClick = {
                                dismissInput()
                                onOpenDrawer()
                            },
                            modifier = Modifier.padding(start = 4.dp, end = 10.dp)
                                .testTag("open-session-drawer")
                        )
                    },
                    actions = {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (sessionBound) {
                                SmallConnectionDot(stateHolder.gatewayState.connection)
                            }
                            // 右上角唯一的加号：回空白首页（不直接建 Pi 会话）。
                            TopBarIconButton(
                                iconRes = R.drawable.ic_add,
                                description = "新会话",
                                onClick = goBlankHome,
                                modifier = Modifier.testTag("new-session")
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            if (showRenameDialog && session != null) {
                SessionRenameDialog(
                    initial = session.title,
                    onDismiss = { showRenameDialog = false },
                    onConfirm = { name ->
                        showRenameDialog = false
                        stateHolder.renameSession(session.id, name)
                    }
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
            ) {
                // 三 tab 只在已有会话上出现：空白首页没有轨迹/树可看。
                if (sessionBound) {
                    SegmentedControl(pagerState.currentPage) { target ->
                        dismissInput()
                        scope.launch { pagerState.animateScrollToPage(target) }
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 三页全常驻：切到 tree（页 2）时对话页（页 0）相隔 2 页，
                    // beyond=1 会让它销毁重建，listState/pinned 等滚动状态全部重置
                    // 回最新消息；常驻后任何切页路径都与“轨迹切回”一样保持位置
                    // （对齐 pi TUI：/tree 关闭回对话不重置滚动）。
                    beyondViewportPageCount = 2,
                    // 横滑不再切页：三个 tab 一律点顶部按钮切换，横向手势腾给页内自己用
                    // （tree 页横向平移查看被缩进挤窄的正文）。
                    userScrollEnabled = false
                ) { page ->
                    when (page) {
                        0 -> ConversationPage(
                            stateHolder = stateHolder,
                            onPickImage = onPickImage,
                            onDismissInput = dismissInput,
                            home = blankHome,
                            onEditBrand = { showHeroEditor = true },
                            onPickWorkspace = { showWorkspacePicker = true }
                        )
                        1 -> TrajectoryPage(
                            nodes = stateHolder.trajectoryNodes,
                            isRunning = stateHolder.snapshot.sessionStates[stateHolder.snapshot.selectedSessionId]?.isStreaming == true
                        )
                        else -> SessionTreePage(
                            stateHolder = stateHolder,
                            locateTick = treeLocateTick,
                            // 三页常驻后本页在对话页时也被组合；只有真正切到才拉数据，
                            // 避免一进会话页就预拉 tree（旧网关还会立即弹“不支持”）。
                            visible = pagerState.currentPage == 2,
                            // 手动刷新与切页同语义：重拉后重新定位到当前节点，避免
                            // “内容变了才跳、没变不跳”的不一致。
                            onRefresh = {
                                stateHolder.loadSessionTree()
                                treeLocateTick += 1
                            }
                        )
                    }
                }
                if (showHeroEditor) {
                    HeroCopyEditorDialog(stateHolder) { showHeroEditor = false }
                }
                if (showWorkspacePicker) {
                    WorkspacePickerSheet(
                        options = recentWorkspaceOptions(
                            stateHolder.snapshot.sessions,
                            stateHolder.hiddenDirectoryPaths
                        ),
                        currentPath = stateHolder.newSessionDirectoryPath,
                        onPick = { path -> stateHolder.selectDirectory(path) },
                        onBrowse = {
                            directoryBrowserCreateMode = false
                            showDirectoryBrowser = true
                        },
                        onCreateWorkspace = {
                            directoryBrowserCreateMode = true
                            showDirectoryBrowser = true
                        },
                        onDismiss = { showWorkspacePicker = false }
                    )
                }
                if (showDirectoryBrowser) {
                    WorkspaceDirectoryBrowserSheet(
                        stateHolder = stateHolder,
                        startWithCreatePrompt = directoryBrowserCreateMode,
                        onDismiss = { showDirectoryBrowser = false }
                    )
                }
            }
        }
    }
}

/** 会话改名对话框：pi /name 的 UI 对等物；顶栏点击与会话列表长按共用。 */
@Composable
internal fun SessionRenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmOnOutsideClick: Boolean = false,
    saving: Boolean = false,
    saveError: String? = null,
    onEdit: () -> Unit = {},
    containerColor: androidx.compose.ui.graphics.Color = piGlassTint(strong = true),
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var emptyError by remember { mutableStateOf(false) }
    val confirm = {
        if (saving) Unit
        else if (draft.isBlank()) emptyError = true
        else if (draft.trim() == initial.trim()) onDismiss()
        else onConfirm(draft.trim())
    }
    AlertDialog(
        onDismissRequest = if (confirmOnOutsideClick) confirm else onDismiss,
        properties = DialogProperties(dismissOnBackPress = !confirmOnOutsideClick),
        containerColor = containerColor,
        title = { Text("会话改名") },
        text = {
            if (confirmOnOutsideClick) CancelDialogOnBack { if (!saving) onDismiss() }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; emptyError = false; onEdit() },
                enabled = !saving,
                isError = emptyError || saveError != null,
                supportingText = when {
                    emptyError -> ({ Text("请输入会话名") })
                    saveError != null -> ({ Text(saveError) })
                    else -> null
                },
                singleLine = true,
                placeholder = { Text("输入新的会话名") }
            )
        },
        confirmButton = {
            Text(
                if (saving) "保存中…" else "保存",
                color = if (draft.isBlank()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else PiColors.Ocean,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = draft.isNotBlank() && !saving) { confirm() }
                    .padding(8.dp)
            )
        },
        dismissButton = {
            Text(
                "取消",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .clickable(enabled = !saving) { onDismiss() }
                    .padding(8.dp)
            )
        }
    )
}


/** 会话分支树页（pi /tree 的移动端形态，PROTOCOL §4.18/4.19）。 */
@Composable
private fun SessionTreePage(
    stateHolder: AndroidSharedStateHolder,
    locateTick: Int,
    visible: Boolean,
    onRefresh: () -> Unit
) {
    val selectedSessionId = stateHolder.snapshot.selectedSessionId
    val tree = stateHolder.sessionTree?.takeIf { it.first == selectedSessionId }
    // 缓存复用：同会话直接用（轨迹快是因为数据在内存，tree 也照此）；只有
    // 切换会话或无缓存才拉。pi 的 /tree 是打开时快照，所以切到本页时由父层
    // 重拉（locateTick），页内另有手动刷新兜底。
    LaunchedEffect(selectedSessionId, visible) {
        if (visible && tree == null) stateHolder.loadSessionTree()
    }
    if (tree == null) {
        val error = stateHolder.treeError
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("会话树加载失败：$error")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "重试",
                        color = PiColors.Ocean,
                        modifier = Modifier.clickable(onClick = onRefresh).padding(8.dp)
                    )
                }
            } else {
                PiSpinner(color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
            }
        }
        return
    }
    val (_, leafId, nodes) = tree
    if (nodes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("会话还没有记录", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        return
    }
    val listState = rememberLazyListState()
    // 定位到当前节点：pi 的 showTreeSelector 也以当前叶子为初始选中。列表首项是
    // 刷新行，所以节点行 index 要 +1；上面留 4 行上下文，避免只剩孤零零一行。
    LaunchedEffect(locateTick, leafId, nodes) {
        val index = nodes.indexOfFirst { it.id == leafId }
            .takeIf { it >= 0 } ?: nodes.indexOfLast { it.onActivePath }
        if (index >= 0) listState.scrollToItem((index + 1 - 4).coerceAtLeast(0))
    }
    // 横向平移：树深时行首前缀吃掉大半行宽，正文只剩几个字。pager 的横滑已关
    // （见上面 userScrollEnabled），横向手势归本页。LazyColumn 放进横向滚动容器
    // 必须给确定宽度，否则行内 fillMaxWidth 会拿到无限约束；按最长前缀与最长正文
    // 估算列宽，浅树（估算值 ≤ 视口）时与改动前完全一致。
    val horizontalState = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val contentWidth = remember(nodes, density) {
            // 等宽字体 13sp 单字约 0.6em；正文按中文 1em 估算（偏保守）。
            val monoChar = with(density) { 13.sp.toDp() } * 0.6f
            val textChar = with(density) { 13.sp.toDp() }
            // 前缀本身有 widthIn(max = 200.dp) 护栏，这里同样封顶。
            val prefixWidth = (monoChar * (nodes.maxOfOrNull { it.prefix.length } ?: 0))
                .coerceAtMost(200.dp)
            // label 是 11sp 比例字体，长度不受网关 preview 上限约束，单独估一项。
            val labelWidth = (with(density) { 11.sp.toDp() } * 0.6f *
                (nodes.maxOfOrNull { (it.label?.length ?: 0) + 2 } ?: 0))
                .coerceAtMost(160.dp)
            val previewWidth = (textChar * (nodes.maxOfOrNull { it.preview.length } ?: 0))
                .coerceAtMost(640.dp)
            28.dp + prefixWidth + 60.dp + labelWidth + previewWidth
        }
        Box(Modifier.fillMaxSize().horizontalScroll(horizontalState)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(maxOf(viewportWidth, contentWidth))
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item("tree-refresh") {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onRefresh)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("刷新", fontSize = 12.sp, color = PiColors.Ocean, fontWeight = FontWeight.SemiBold)
                        Text("快照视图，非实时", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
                items(nodes, key = { it.id }) { node ->
                    val roleTag = when {
                        node.isUser -> "我"
                        node.role == "assistant" -> "AI"
                        else -> null
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            // 当前节点（叶子）给一层浅底：配合切页定位，一眼看到"我在哪"。
                            .then(
                                if (node.id == leafId) {
                                    Modifier.background(PiColors.Ocean.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                } else Modifier
                            )
                            .clickable { stateHolder.switchBranchTo(node.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // pi 的树行前缀（缩进 + 穿透竖线 + 分叉字符），网关按 pi
                        // tree-selector 的规则算好：每级 3 字符、只有分叉才缩进、单链平铺。
                        // 用等宽字体保证 `│`/`├`/`└` 逐列对齐。
                        if (node.prefix.isNotEmpty()) {
                            Text(
                                node.prefix,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                maxLines = 1,
                                // 护栏：极深分叉（十几级 × 3 字符）时前缀会吃掉整行，正文
                                // 被挤到零宽。超过 200dp 就截断前缀，优先保住单行摘要。
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp),
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                        // 活跃路径：pi 用 `• ` 行标记（线本身不区分活跃）。
                        if (node.onActivePath) {
                            Text(
                                "•",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                color = PiColors.Ocean,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                        if (node.label != null) {
                            Text(
                                "[${node.label}]",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 1
                            )
                        }
                        // 角色短标签：手机端树只保留 user/assistant（PROTOCOL §4.18），用轨迹
                        // tab 同款色系（user=Ocean、assistant=Purple）；wire 不带
                        // "user: "/"assistant: " 前缀，单行宽度全留给正文。
                        if (roleTag != null) {
                            Text(
                                roleTag,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (node.isUser) PiColors.Ocean else PiColors.Purple,
                                maxLines = 1,
                                modifier = Modifier.width(20.dp)
                            )
                        }
                        Text(
                            node.preview,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (node.onActivePath) 0.95f else 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TopBarIconButton(
    iconRes: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp).clip(RoundedCornerShape(6.dp))) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            tint = tint
        )
    }
}

@Composable
private fun SegmentedControl(selected: Int, onSelect: (Int) -> Unit) {
    val trackShape = RoundedCornerShape(8.dp)
    val segmentShape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 66.dp, vertical = 12.dp)
            .height(32.dp)
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f))
            .padding(2.dp)
    ) {
        listOf("对话", "轨迹", "tree").forEachIndexed { index, title ->
            val isSelected = selected == index
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(segmentShape)
                    .background(if (isSelected) piGlassTint(strong = true) else Color.Transparent)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                0.5.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
                                segmentShape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(index) }
                    .semantics { contentDescription = title },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationPage(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onDismissInput: () -> Unit,
    /** true = 空白首页：不组合时间线，改在中间放品牌区，底部输入区保持常驻。 */
    home: Boolean = false,
    onEditBrand: () -> Unit = {},
    onPickWorkspace: () -> Unit = {}
) {
    val density = LocalDensity.current
    val sessionId = stateHolder.snapshot.selectedSessionId
    var bottomContentHeight by remember { mutableStateOf(160.dp) }
    var imagePreview by remember(sessionId) { mutableStateOf<ConversationImagePreviewRequest?>(null) }
    var isPinnedToBottom by remember(sessionId) { mutableStateOf(true) }
    var scrollToBottomToken by remember(sessionId) { mutableIntStateOf(0) }
    // ime 边沿不再触发 token：键盘跟随由 ConversationTimeline 的
    // imeBottomPx effect 负责（onlyIfLastHidden=false 强制重滚）；
    // token 仅由"回到底部"按钮使用。
    val isColdHistoryLoad = stateHolder.snapshot.selectedHistoryIsLoading &&
        !stateHolder.snapshot.selectedHistoryIsLoadingOlder &&
        stateHolder.snapshot.conversation.isEmpty()
    var coldHistoryLoadStarted by remember(sessionId) { mutableStateOf(isColdHistoryLoad) }
    LaunchedEffect(sessionId, isColdHistoryLoad, stateHolder.snapshot.selectedHistoryIsLoading) {
        when {
            isColdHistoryLoad -> coldHistoryLoadStarted = true
            !stateHolder.snapshot.selectedHistoryIsLoading -> coldHistoryLoadStarted = false
        }
    }
    val showInitialHistoryOverlay = shouldShowInitialHistoryOverlay(
        isLoading = stateHolder.snapshot.selectedHistoryIsLoading,
        isLoadingOlder = stateHolder.snapshot.selectedHistoryIsLoadingOlder,
        hasLocalContent = stateHolder.snapshot.conversation.isNotEmpty(),
        coldLoadStarted = coldHistoryLoadStarted,
        hasError = stateHolder.snapshot.selectedHistoryError != null
    )
    val historyLoadError = stateHolder.snapshot.selectedHistoryError
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeightDp = with(density) { constraints.maxHeight.toDp() }.value
        if (home) {
            // 品牌区居中于顶栏与输入区之间：用实测的 bottomContentHeight 上推，
            // 不靠写死的 padding；键盘弹出或高度不够时由 homeBrandLayout 收起/缩小。
            val brandLayout = homeBrandLayout(
                viewportHeightDp = viewportHeightDp,
                composerHeightDp = bottomContentHeight.value,
                imeVisible = WindowInsets.isImeVisible
            )
            val brandCopy = homeBrandCopy(stateHolder.heroTitle, stateHolder.heroTagline)
            HomeBrand(
                title = brandCopy.first,
                tagline = brandCopy.second,
                versionName = BuildConfig.VERSION_NAME,
                layout = brandLayout,
                onClick = onEditBrand,
                modifier = Modifier.align(Alignment.Center).padding(bottom = bottomContentHeight)
            )
        } else {
            androidx.compose.runtime.key(sessionId) {
                ConversationTimeline(
                    stateHolder = stateHolder,
                    bottomContentHeight = bottomContentHeight,
                    scrollToBottomToken = scrollToBottomToken,
                    onPinnedToBottomChanged = { isPinnedToBottom = it },
                    showEmptyState = !stateHolder.snapshot.selectedHistoryIsLoading &&
                        !showInitialHistoryOverlay && historyLoadError == null,
                    onUserInteraction = onDismissInput,
                    onPreviewImages = { attachments, initialIndex ->
                        imagePreview = ConversationImagePreviewRequest(attachments, initialIndex)
                    }
                )
            }
        }
        // 加载、错误和空态互斥；不让退出动画把旧状态留在下一状态的下面。
        if (showInitialHistoryOverlay) {
            Box(
                Modifier.align(Alignment.Center).fillMaxSize()
                    .padding(bottom = bottomContentHeight).zIndex(1f)
            ) {
                HistoryLoadingOverlay(
                    loadedEventCount = stateHolder.snapshot.selectedHistoryLoadedEventCount,
                    totalEventCount = stateHolder.snapshot.selectedHistoryTotalEventCount
                )
            }
        } else if (
            historyLoadError != null && !stateHolder.snapshot.selectedHistoryIsLoading &&
            stateHolder.snapshot.conversation.isEmpty()
        ) {
            Box(Modifier.align(Alignment.Center).zIndex(1f)) {
                HistoryLoadErrorCard(
                    errorCode = historyLoadError,
                    onRetry = { stateHolder.reloadSelectedHistory() }
                )
            }
        }
        // 底部渐隐常驻（空白首页/会话页同一条）：轻 scrim 帮消息文字在输入区后淡出，
        // 不再用实色底渐变，发送前后也不会突然出现一块实色。
        ConversationBottomFade(
            modifier = Modifier.align(Alignment.BottomCenter),
            contentHeight = bottomContentHeight
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .onSizeChanged { size ->
                    bottomContentHeight = with(density) { size.height.toDp() }
                }
                .navigationBarsPadding()
        ) {
            // pi 原生：压缩进行中 prompt 直接拒绝（agent-session 守卫）。
            // 移动端发送被拦在客户端，这里常驻提示条解释原因（compact 结束自动消失）。
            if (sessionId?.let { stateHolder.snapshot.sessionStates[it]?.isCompacting } == true) {
                Text(
                    "正在压缩上下文，暂不能发送消息",
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 1.dp)
                )
            }
            PendingDispatchBar(stateHolder, onDismissInput)
            QueuedMessagesBar(stateHolder, sessionId)
            // 工作区行常驻：未绑定时（含发送预览）仍可选目录，绑定后只读当前 cwd。
            // 显示模式不决定能否改目录，避免失败预览把新会话误判成已绑定会话。
            val boundSession = stateHolder.snapshot.sessions.firstOrNull { it.id == sessionId }
            HomeWorkspaceLine(
                directoryPath = if (sessionId == null) stateHolder.newSessionDirectoryPath else boundSession?.cwd,
                enabled = sessionId == null,
                onClick = onPickWorkspace
            )
            Composer(stateHolder, onPickImage, onDismissInput)
        }
        // 回到底部按钮：独立悬浮层，不参与底部 Column 的 onSizeChanged 测量——
        // 否则按钮出现/消失会改变 bottomContentHeight，把对话列表整条顶起或落下。
        // bottomContentHeight 已含 navigationBarsPadding 但不含 ime（Column 的
        // imePadding 在 onSizeChanged 外侧），这里同链补 imePadding，跟随键盘。
        AnimatedVisibility(
            visible = stateHolder.snapshot.conversation.isNotEmpty() && !isPinnedToBottom,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .imePadding()
                .padding(end = 20.dp, bottom = bottomContentHeight),
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f)
        ) {
            ScrollToBottomButton(
                isGenerating = stateHolder.snapshot.sessions
                    .firstOrNull { it.id == sessionId }
                    ?.isRunning == true,
                onClick = { scrollToBottomToken += 1 }
            )
        }
    }
    imagePreview?.let { request ->
        ConversationImagePreviewDialog(
            request = request,
            thumbnails = stateHolder.attachmentThumbnails,
            loadFullImage = { attachment ->
                val sessionId = stateHolder.snapshot.selectedSessionId ?: return@ConversationImagePreviewDialog null
                val bytes = stateHolder.readFullAttachment(sessionId, attachment) ?: return@ConversationImagePreviewDialog null
                withContext(Dispatchers.IO) { decodeSampledBitmap(bytes) }
            },
            onDismiss = { imagePreview = null }
        )
    }
}

internal fun shouldShowInitialHistoryOverlay(
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    hasLocalContent: Boolean,
    coldLoadStarted: Boolean = false,
    hasError: Boolean = false
): Boolean = isLoading && !isLoadingOlder && !hasError && (coldLoadStarted || !hasLocalContent)

internal fun historyLoadingProgressText(loadedEventCount: Int, totalEventCount: Int?): String = when {
    totalEventCount != null -> "正在加载历史记录 · $loadedEventCount/$totalEventCount"
    loadedEventCount > 0 -> "正在自动加载更早记录 · 已同步 $loadedEventCount 个事件"
    else -> "正在从 Mobile Gateway 同步会话内容…"
}

@Composable
internal fun HistoryLoadingOverlay(
    loadedEventCount: Int,
    totalEventCount: Int?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.24f))
            .testTag("history-loading-overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PiSpinner(color = PiColors.Ocean, fontSize = 32.sp)
            Text(
                "正在加载历史记录",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                historyLoadingProgressText(loadedEventCount, totalEventCount),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun HistoryLoadErrorCard(
    errorCode: String,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = piGlassTint(strong = true),
        tonalElevation = 3.dp,
        modifier = Modifier.testTag("history-load-error-card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "历史记录加载失败",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                GatewayErrorText.request("history", errorCode),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onRetry, shape = RoundedCornerShape(6.dp), modifier = Modifier.testTag("history-retry-button")) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun ConversationBottomFade(
    contentHeight: Dp,
    modifier: Modifier = Modifier
) {
    // 与常驻背景同色系的轻 scrim（不是实色 background）：消息滚到输入区后时
    // 渐隐即可，深色对应点阵背景底色 0xFF050505，浅色对应 Paper，两种模式
    // 都保持玻璃风格不出现实色大块。
    // 高度越过裁剪线（bottomContentHeight）44dp：列表视口在 bottomContentHeight
    // 处裁剪后，渐隐顶端必须伸入列表内，正文接近底缘时先淡出再被裁，不出现硬切边。
    val scrim = if (LocalPiDark.current) Color(0xFF050505) else PiColors.Paper
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height((contentHeight + 44.dp).coerceAtLeast(0.dp))
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.3f to scrim.copy(alpha = 0.55f),
                        1f to scrim.copy(alpha = 0.66f)
                    )
                )
            )
    )
}

@Composable
private fun ConversationTimeline(
    stateHolder: AndroidSharedStateHolder,
    bottomContentHeight: Dp,
    scrollToBottomToken: Int,
    onPinnedToBottomChanged: (Boolean) -> Unit,
    showEmptyState: Boolean,
    onUserInteraction: () -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    val latestItems = stateHolder.snapshot.conversation
    val selectedSessionId = stateHolder.snapshot.selectedSessionId
    val latestHistoryLoading = selectedSessionId in stateHolder.historyPagingSessionIds
    var items by remember(selectedSessionId) { mutableStateOf(latestItems) }
    var hasHistoryLoadingRow by remember(selectedSessionId) {
        mutableStateOf(latestHistoryLoading)
    }
    val displayEntries = remember(items) { makeConversationDisplayEntries(items) }
    val timelineEntries = remember(displayEntries) {
        makeConversationTimelineEntries(displayEntries)
    }
    // 过程组折叠状态：按会话隔离（sessionId 变化即重建）；key 是组 id——锚定前驱
    // 消息与组序数，流式 finalize 换成员 id 不换键，详见 makeConversationDisplayEntries。
    val collapsedProcessGroups = remember(selectedSessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    val attachmentIdsByTimelineId = remember(timelineEntries) {
        timelineEntries.associate { entry ->
            entry.id to entry.images.mapTo(mutableSetOf()) { it.attachmentId }
        }
    }
    val hasInitialContent = timelineEntries.isNotEmpty()
    val sessionState = stateHolder.snapshot.sessionStates[stateHolder.snapshot.selectedSessionId]
    val isCompacting = sessionState?.isCompacting == true
    val isWorking = sessionState?.isStreaming == true
    // 带图发送的本地预览条：正式回显到达前的占位，按会话/新建页代过滤。
    val outgoingPreviews = stateHolder.outgoingPreviews.filter {
        it.isVisibleFor(selectedSessionId, stateHolder.newSessionEpoch)
    }
    val hasHistoryErrorRow = latestItems.isNotEmpty() &&
        stateHolder.snapshot.selectedHistoryError != null &&
        stateHolder.snapshot.selectedHistoryHasMore &&
        !stateHolder.snapshot.selectedHistoryIsLoading && !latestHistoryLoading
    val timelineItemCount = (
        timelineEntries.size + (if (hasHistoryLoadingRow) 1 else 0) +
            (if (hasHistoryErrorRow) 1 else 0) +
            // 尾部三状态行（working/behind/compacting）在最后时间线项之后：
            // 滚底目标必须计入，否则 AI 回复中 Working… 落在预留区外。
            (if (isWorking) 1 else 0) +
            (if (selectedSessionId != null && stateHolder.isBehindLatest(selectedSessionId)) 1 else 0) +
            (if (isCompacting) 1 else 0) +
            (if (outgoingPreviews.isNotEmpty()) 1 else 0)
    )
    val lastTimelineIndex = (timelineItemCount - 1).coerceAtLeast(0)
    val showBehindLatest = selectedSessionId != null && stateHolder.isBehindLatest(selectedSessionId)
    val listState = remember(selectedSessionId) { LazyListState(lastTimelineIndex) }
    var initialPositionApplied by remember(selectedSessionId) { mutableStateOf(false) }
    var isPinnedToBottom by remember { mutableStateOf(true) }
    var programmaticScrollCount by remember { mutableIntStateOf(0) }
    var timelineUpdatesPaused by remember(selectedSessionId) { mutableStateOf(false) }
    val isUserTimelineScrolling by remember(listState) {
        derivedStateOf {
            listState.isScrollInProgress && programmaticScrollCount == 0
        }
    }
    LaunchedEffect(isUserTimelineScrolling) {
        if (isUserTimelineScrolling) {
            timelineUpdatesPaused = true
        } else if (timelineUpdatesPaused) {
            // fling 结束后留一帧稳定窗口，再把滚动期间积累的 token 一次性提交给列表。
            delay(TIMELINE_STREAM_RESUME_DELAY_MILLISECONDS)
            timelineUpdatesPaused = false
        }
    }
    LaunchedEffect(
        latestItems,
        latestHistoryLoading,
        timelineUpdatesPaused,
        isUserTimelineScrolling
    ) {
        if (!timelineUpdatesPaused && !isUserTimelineScrolling) {
            items = latestItems
            hasHistoryLoadingRow = latestHistoryLoading
        }
    }
    val markdownPreloader = rememberPiMarkdownPreloader()
    LaunchedEffect(listState, timelineEntries, markdownPreloader) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: timelineEntries.lastIndex
            val last = visible.lastOrNull()?.index ?: timelineEntries.lastIndex
            first to last
        }
            .distinctUntilChanged()
            .collectLatest { (first, last) ->
                val start = (first - MARKDOWN_PREFETCH_ROWS).coerceAtLeast(0)
                val end = (last + MARKDOWN_PREFETCH_ROWS).coerceAtMost(timelineEntries.lastIndex)
                if (start <= end) {
                    markdownPreloader.preload(
                        timelineEntries.subList(start, end + 1)
                            .filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
                            .filterNot { it.messageId.startsWith("stream-") }
                            .map(ConversationTimelineEntry.AssistantMarkdown::markdown)
                    )
                }
            }
    }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val targetHeight = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(windowInfo.containerSize.width, targetHeight) {
        stateHolder.updateThumbnailTargetSize(windowInfo.containerSize.width, targetHeight)
    }
    LaunchedEffect(listState, items) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.flatMapTo(mutableSetOf()) { visible ->
                attachmentIdsByTimelineId[visible.key.toString()].orEmpty()
            }
        }.distinctUntilChanged().collect(stateHolder::updateVisibleAttachments)
    }
    // 初始滚底依赖视口实际高度：bottomContentHeight 首帧仍是 remember 默认值（160dp），
    // 实测后视口高度变化，必须重滚一次才能真的贴底（视口现由 padding(bottom) 决定）。
    LaunchedEffect(hasInitialContent, bottomContentHeight) {
        if (!initialPositionApplied && hasInitialContent) {
            programmaticScrollCount += 1
            try {
                listState.scrollToTimelineBottom(lastTimelineIndex, animated = false)
                initialPositionApplied = true
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    val hasStreamingItem = items.any(::isStreamingConversationItem)
    // 用户主动发送建立的新预览：出现即主动滚底一次（不依赖 pinned，翻历史也露出）。
    // 仅按首次出现的 token 触发；后续状态更新/回显移除不再抢滚。
    var seenPreviewTokens by remember(selectedSessionId) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(outgoingPreviews.map(OutgoingImagePreview::sendToken)) {
        val fresh = outgoingPreviews.map(OutgoingImagePreview::sendToken).toSet() - seenPreviewTokens
        if (fresh.isEmpty()) return@LaunchedEffect
        seenPreviewTokens = seenPreviewTokens + fresh
        programmaticScrollCount += 1
        try {
            listState.scrollToTimelineBottom(lastTimelineIndex, animated = true)
        } finally {
            programmaticScrollCount -= 1
        }
    }
    LaunchedEffect(
        timelineEntries.size,
        items.lastOrNull()?.text?.length,
        initialPositionApplied,
        hasStreamingItem,
        outgoingPreviews.size,
        // 视口现由 padding(bottom = bottomContentHeight) 决定：底部常驻区高度变化
        // （排队条、压缩提示等出现/消失）会压缩视口，吸底时需重滚，否则末条被
        // 输入区盖住（旧方案只改 contentPadding，不缩视口，无此需求）。
        bottomContentHeight
    ) {
        if (initialPositionApplied && isPinnedToBottom && timelineEntries.isNotEmpty()) {
            programmaticScrollCount += 1
            try {
                listState.scrollToTimelineBottom(
                    lastTimelineIndex,
                    animated = !hasStreamingItem
                )
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    LaunchedEffect(scrollToBottomToken) {
        if (scrollToBottomToken > 0 && timelineEntries.isNotEmpty()) {
            programmaticScrollCount += 1
            try {
                listState.scrollToTimelineBottom(lastTimelineIndex, animated = true)
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    LaunchedEffect(listState, initialPositionApplied) {
        if (!initialPositionApplied) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.isPinnedToBottom(),
                programmaticScrollCount > 0
            )
        }
            .distinctUntilChanged()
            .collect { (isScrolling, pinned, isProgrammaticScroll) ->
                // A new streamed row briefly makes the old last row stop being the
                // final item before auto-scroll runs. Only a real user scroll may
                // unpin; automatic movement must not flash the jump button.
                if (shouldPublishPinnedState(isScrolling, pinned, isProgrammaticScroll)) {
                    isPinnedToBottom = pinned
                    onPinnedToBottomChanged(pinned)
                }
            }
    }
    LaunchedEffect(listState, stateHolder.snapshot.selectedHistoryHasMore, initialPositionApplied) {
        if (!initialPositionApplied) return@LaunchedEffect
        snapshotFlow {
            HistoryPagingGestureState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                isScrollInProgress = listState.isScrollInProgress,
                lastScrolledBackward = listState.lastScrolledBackward,
                isProgrammaticScroll = programmaticScrollCount > 0
            )
        }
            .distinctUntilChanged()
            .collect { gesture ->
                if (
                    shouldLoadOlderHistory(gesture) &&
                    stateHolder.snapshot.selectedHistoryHasMore
                ) {
                    stateHolder.loadOlderHistory()
                }
            }
    }
    // 小页不足一屏时没有可供用户上滑的空间，按实际布局逐页补足；游标去重防止异常页循环。
    val autoRequestedCursors = remember(selectedSessionId) { mutableSetOf<String>() }
    LaunchedEffect(listState, items, latestItems, timelineItemCount, initialPositionApplied) {
        fun pagingState(): HistoryViewportPagingState {
            val snapshot = stateHolder.snapshot
            val layout = listState.layoutInfo
            return HistoryViewportPagingState(
                cursor = snapshot.selectedHistoryNextCursor,
                hasMore = snapshot.selectedHistoryHasMore,
                isLoading = snapshot.selectedHistoryIsLoading ||
                    selectedSessionId in stateHolder.historyPagingSessionIds,
                isLatestLoading = snapshot.selectedHistoryIsLoading && !snapshot.selectedHistoryIsLoadingOlder,
                hasError = snapshot.selectedHistoryError != null,
                connected = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED,
                layoutReady = items === latestItems &&
                    (initialPositionApplied || timelineEntries.isEmpty()) &&
                    layout.viewportSize.height > 0 && layout.totalItemsCount == timelineItemCount,
                canScroll = listState.canScrollBackward || listState.canScrollForward,
                isScrolling = listState.isScrollInProgress || programmaticScrollCount > 0
            )
        }
        snapshotFlow { pagingState() }.distinctUntilChanged().collectLatest { paging ->
            if (paging.isLatestLoading || !paging.connected) autoRequestedCursors.clear()
            if (!shouldFillHistoryViewport(paging) || paging.cursor in autoRequestedCursors) return@collectLatest
            // 等新消息完成一帧测量，不能拿上一页的空布局判断是否需要补页。
            withFrameNanos { }
            if (paging != pagingState()) return@collectLatest
            paging.cursor?.let { cursor ->
                autoRequestedCursors += cursor
                stateHolder.loadOlderHistory()
            }
        }
    }
    // 键盘弹出收起时跟随 ime 数值重滚（不是"可见性边沿"单次触发）：
    // API 30+ 的 ime insets 逐帧动画下发，边沿在动画第一帧就翻 true，
    // LaunchedEffect 读到的还是键盘弹出前的旧 layoutInfo，之后无人再滚。
    // 跟随数值让动画机型逐帧贴键盘、单跳机型一次到位；收起方向同样生效。
    // 自动滚底只监听内容变化——键盘不改变 entries，视口被 imePadding 压短
    // 后必须显式重滚，底部消息才不会停在输入栏（毛玻璃）后面。
    // 非 pinned 不动：翻历史时不抢滚动。
    val currentPinnedToBottom by rememberUpdatedState(isPinnedToBottom)
    val currentHasEntries by rememberUpdatedState(timelineEntries.isNotEmpty())
    val currentLastTimelineIndex by rememberUpdatedState(lastTimelineIndex)
    val currentInitialPositionApplied by rememberUpdatedState(initialPositionApplied)
    var hasObservedIme by remember(selectedSessionId) { mutableStateOf(false) }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeInsets = WindowInsets.ime
    LaunchedEffect(listState, selectedSessionId, density, imeInsets) {
        snapshotFlow { imeInsets.getBottom(density) }
            .collect { imeBottomPx ->
                val shouldFollow = if (imeBottomPx > 0) {
                    hasObservedIme = true
                    true
                } else if (hasObservedIme) {
                    true
                } else {
                    false
                }
                if (!shouldFollow) return@collect
                try {
                    if (currentInitialPositionApplied && currentPinnedToBottom && currentHasEntries) {
                        withFrameNanos { }
                        if (currentInitialPositionApplied && currentPinnedToBottom && currentHasEntries) {
                            programmaticScrollCount += 1
                            try {
                                listState.scrollToTimelineBottom(
                                    currentLastTimelineIndex,
                                    animated = false,
                                    onlyIfLastHidden = false
                                )
                            } finally {
                                programmaticScrollCount -= 1
                            }
                        }
                    }
                } finally {
                    if (imeBottomPx == 0) {
                        hasObservedIme = false
                    }
                }
            }
    }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("conversation-timeline")
            .pointerInput(onUserInteraction) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    onUserInteraction()
                }
            }
    ) {
        if (showEmptyState && items.isEmpty() && outgoingPreviews.isEmpty()) {
            EmptyConversation(Modifier.align(Alignment.Center).padding(bottom = 150.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                // 视口裁剪：列表底缘对齐工作区行顶部，滚动正文不再画入项目名/输入区背后。
                // 不能再叠 navigationBarsPadding：bottomContentHeight 的测量链在底部
                // Column 的 navigationBarsPadding 内侧 onSizeChanged，已含 nav inset，
                // 再扣一次会让视口底缘比输入区顶缘高一个 nav bar（渐隐 scrim 伸不进列表）。
                .padding(bottom = bottomContentHeight)
                .padding(horizontal = 20.dp)
                .alpha(if (hasInitialContent && !initialPositionApplied) 0f else 1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 8.dp,
                bottom = 22.dp
            )
        ) {
            if (hasHistoryErrorRow) {
                item("history-error") {
                    TextButton(
                        onClick = {
                            autoRequestedCursors.clear()
                            stateHolder.loadOlderHistory()
                        },
                        enabled = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("更早记录加载失败，点击重试", fontSize = 12.sp)
                    }
                }
            }
            if (hasHistoryLoadingRow) {
                item("history-loading") {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PiSpinner(color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        Spacer(Modifier.width(9.dp))
                        Text("正在加载更早记录…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                    }
                }
            }
            items(
                items = timelineEntries,
                key = ConversationTimelineEntry::id,
                contentType = ConversationTimelineEntry::contentType
            ) { entry ->
                when (entry) {
                    is ConversationTimelineEntry.Display -> when (val display = entry.entry) {
                        is ConversationDisplayEntry.Message -> ConversationRow(
                            item = display.item,
                            thumbnails = stateHolder.attachmentThumbnails,
                            attachmentStates = stateHolder.attachmentStates,
                            onRetryAttachment = stateHolder::retryAttachment,
                            onPreviewImages = onPreviewImages
                        )
                        is ConversationDisplayEntry.Process ->
                            ConversationProcessRow(display.group, collapsedProcessGroups)
                    }
                    is ConversationTimelineEntry.AssistantHeader -> AssistantMessageHeader(
                        item = entry.item,
                        thumbnails = stateHolder.attachmentThumbnails,
                        states = stateHolder.attachmentStates,
                        onRetry = stateHolder::retryAttachment
                    )
                    is ConversationTimelineEntry.AssistantMarkdown -> PiLazyMarkdownText(
                        markdown = entry.markdown,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            // 带图发送的本地预览：点击空闲发送后立即占位，正式回显到达即被移除；
            // 失败/未确认给状态与恢复入口。
            if (outgoingPreviews.isNotEmpty()) {
                item("outgoing-preview") {
                    Column {
                        outgoingPreviews.forEach { preview ->
                            OutgoingImagePreviewRow(
                                preview = preview,
                                stateHolder = stateHolder,
                                onPreviewImages = onPreviewImages
                            )
                        }
                    }
                }
            }
            // pi 的 Working 指示器（StatusIndicator/Loader）：盲文帧 ⠋⠙⠹… @80ms、
            // spinner accent 色、文案 muted。AI 回复中全程可见（chunk 流入时也是）。
            if (isWorking) {
                item("working-status") {
                    WorkingSpinnerRow()
                }
            }
            // 活跃链落后于主线最新（切过分支/断连期间主线前进）：
            // 尾部给一键跳到最新的出口，不管因为什么落后都能一键回主线。
            if (showBehindLatest) {
                item("behind-latest") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { stateHolder.jumpToLatest(selectedSessionId) }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            // pi TUI /tree 运行中选择节点同样 abort 当前运行；
                            // 文案显式告知，避免误触中断正在生成的回复。
                            text = if (isWorking) "↓ 跳到最新（将中断当前回复）" else "↓ 有更新的消息，点此跳到最新",
                            fontSize = 11.sp,
                            color = PiColors.Ocean,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            // 压缩状态：pi TUI 在终端底部显示 Compacting spinner，移动端映射为
            // 对话流尾部的状态行（完成后消失，由 compact/done 行接管痕迹）。
            if (isCompacting) {
                item("compacting-status") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PiSpinner(color = PiColors.Ocean, fontSize = 11.sp)
                        Spacer(Modifier.width(9.dp))
                        Text("正在压缩上下文…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkingSpinnerRow() {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PiSpinner(
            color = PiColors.Ocean,
            fontSize = 14.sp,
            modifier = Modifier.testTag("working-spinner")
        )
        Spacer(Modifier.width(9.dp))
        Text("Working…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
    }
}

/**
 * 忙时吸附派发条（pi 语义：isStreaming 时 prompt 必须显式 steer/followUp）。
 * 紧贴输入框的窄细长条：浅灰底、浅色小字，右侧 ↑=注入本轮（steer）、
 * →=排队下轮（followUp）、X=撤销（pi 的 dequeue：草稿回编辑框）。
 */
@Composable
private fun PendingDispatchBar(stateHolder: AndroidSharedStateHolder, onDismissInput: () -> Unit) {
    val pending = stateHolder.pendingDispatch ?: return
    val sending = stateHolder.pendingDispatchIsSending
    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f))
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
            .testTag("pending-dispatch-bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (sending) "发送中" else "待发送",
            fontSize = 10.sp,
            color = subtle,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            pending.draft.ifBlank { if (pending.images.isNotEmpty()) "[图片 ×${pending.images.size}]" else "" },
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f)
        )
        PendingDispatchIcon(R.drawable.ic_arrow_up, "注入本轮（steer）", PiColors.Ocean, enabled = !sending) {
            onDismissInput()
            stateHolder.dispatchPending("steer")
        }
        PendingDispatchIcon(R.drawable.ic_arrow_right, "排队下轮（follow-up）", subtle, enabled = !sending) {
            onDismissInput()
            stateHolder.dispatchPending("followUp")
        }
        PendingDispatchIcon(R.drawable.ic_close, "撤销", MaterialTheme.colorScheme.error, enabled = !sending) {
            onDismissInput()
            stateHolder.cancelPendingDispatch()
        }
    }
}

@Composable
private fun PendingDispatchIcon(iconRes: Int, description: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
            .size(18.dp)
    )
}

/** 已排队消息（pi 的 pendingMessagesContainer：dim 色 Steering:/Follow-up: 行）。 */
@Composable
private fun QueuedMessagesBar(stateHolder: AndroidSharedStateHolder, sessionId: String?) {
    val queued = sessionId?.let { stateHolder.snapshot.sessionStates[it]?.queuedMessages } ?: return
    if (queued.isEmpty) return
    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 1.dp)
    ) {
        queued.steering.forEach { message ->
            Text(
                "Steering: $message",
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = subtle
            )
        }
        queued.followUp.forEach { message ->
            Text(
                "Follow-up: $message",
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = subtle
            )
        }
    }
}

private const val TIMELINE_STREAM_RESUME_DELAY_MILLISECONDS = 80L
private const val MARKDOWN_PREFETCH_ROWS = 24

internal data class HistoryViewportPagingState(
    val cursor: String?,
    val hasMore: Boolean,
    val isLoading: Boolean,
    val isLatestLoading: Boolean,
    val hasError: Boolean,
    val connected: Boolean,
    val layoutReady: Boolean,
    val canScroll: Boolean,
    val isScrolling: Boolean
)

internal fun shouldFillHistoryViewport(state: HistoryViewportPagingState): Boolean =
    !state.cursor.isNullOrBlank() && state.hasMore && !state.isLoading && !state.hasError &&
        state.connected && state.layoutReady && !state.canScroll && !state.isScrolling

internal data class HistoryPagingGestureState(
    val firstVisibleItemIndex: Int,
    val isScrollInProgress: Boolean,
    val lastScrolledBackward: Boolean,
    val isProgrammaticScroll: Boolean
)

internal fun shouldLoadOlderHistory(gesture: HistoryPagingGestureState): Boolean =
    gesture.firstVisibleItemIndex <= 2 &&
        gesture.isScrollInProgress &&
        gesture.lastScrolledBackward &&
        !gesture.isProgrammaticScroll

private fun androidx.compose.foundation.lazy.LazyListState.isPinnedToBottom(): Boolean {
    return isTimelinePinnedToBottom(
        totalItems = layoutInfo.totalItemsCount,
        canScrollForward = canScrollForward
    )
}

internal fun timelineBottomScrollDelta(
    lastItemOffset: Int,
    lastItemSize: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int
): Float = (
    lastItemOffset + lastItemSize + afterContentPadding - viewportEndOffset
).coerceAtLeast(0).toFloat()

internal suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToTimelineBottom(
    lastItemIndex: Int,
    animated: Boolean,
    onlyIfLastHidden: Boolean = true
) {
    // 守卫"末条可见即跳过"对内容变化触发的自动滚底是对的；但键盘弹出把视口
    // 下沿抬高后，末条往往仍部分可见——若此时也跳过，底部内容就停在键盘后方
    //（间歇性"输入框悬浮盖住消息"的结构性来源）。键盘驱动的调用方传
    // onlyIfLastHidden=false 强制重滚，LazyList 会把末条底边钳到输入栏上方。
    if (!onlyIfLastHidden || layoutInfo.visibleItemsInfo.none { it.index == lastItemIndex }) {
        if (animated) animateScrollToItem(lastItemIndex) else scrollToItem(lastItemIndex)
    }
    fun remainingDistance(): Float {
        val lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastItemIndex }
            ?: return 0f
        return timelineBottomScrollDelta(
            lastItemOffset = lastItem.offset,
            lastItemSize = lastItem.size,
            viewportEndOffset = layoutInfo.viewportEndOffset,
            afterContentPadding = layoutInfo.afterContentPadding
        )
    }
    val distance = remainingDistance()
    if (distance > 0f) {
        if (animated) animateScrollBy(distance) else scrollBy(distance)
    }
    // The last row can remeasure while Markdown, images, or the composer are
    // settling. End with an exact correction so the list has no forward range.
    val correction = remainingDistance()
    if (correction > 0f) scrollBy(correction)
}

internal fun isTimelinePinnedToBottom(
    totalItems: Int,
    canScrollForward: Boolean
): Boolean = totalItems == 0 || !canScrollForward

internal fun shouldPublishPinnedState(
    isScrolling: Boolean,
    pinned: Boolean,
    isProgrammaticScroll: Boolean
): Boolean = pinned || (isScrolling && !isProgrammaticScroll)

/**
 * 贴底跟随/首次贴底判据（新 UI 列表）：有内容、用户不在拖动/惯性中，且
 * 本次会话尚未定位过（打开会话先看最新）或用户原本就贴底。阅读历史——已上滑
 * 离开底部——不能被流式增量或列表变化抢滚动。
 */
internal fun shouldScrollToLatest(
    itemCount: Int,
    isUserScrolling: Boolean,
    initialPositionApplied: Boolean,
    pinnedToBottom: Boolean
): Boolean = itemCount > 0 && !isUserScrolling && (!initialPositionApplied || pinnedToBottom)

@Composable
internal fun ScrollToBottomButton(isGenerating: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    // 外层保留 48dp 触控区，内层只承担视觉重量，和发送按钮的比例保持一致。
    Box(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .size(48.dp)
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick
            )
            .semantics {
                contentDescription = if (isGenerating) "正在生成，滚动到最新消息" else "滚动到最新消息"
            }
            .testTag("scroll-to-bottom"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(38.dp)
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 10.dp,
                        spread = 0.dp,
                        color = Color.Black.copy(alpha = 0.10f),
                        offset = DpOffset(0.dp, 3.dp)
                    )
                ),
            shape = shape,
            color = piGlassTint(strong = true),
            border = piGlassOutline()
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isGenerating) {
                    PiSpinner(color = PiColors.Ocean, fontSize = 13.sp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 28.dp).testTag("conversation-empty-state"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PiLogoIcon(Modifier.size(48.dp))
        Text("操作远端 Pi Agent", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "发送任务后，工具调用、推理进度和最终回复会通过 Mobile Gateway 实时返回。",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** 与 consumeSlashCommand 内建拦截表同名：联想剔除，防死入口（网关三源清单无过滤）。 */
private val BUILTIN_SLASH_COMMAND_NAMES = setOf(
    "name", "compact", "model", "thinking", "copy", "tree", "fork", "clone",
    "export", "import", "share", "session", "scoped-models", "trust",
    "changelog", "hotkeys", "login", "settings"
)

@Composable
private fun Composer(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onDismissInput: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val isDark = LocalPiDark.current
    val shadowColor = piFloatingSurfaceShadow(isDark)
    val composerHasContent = stateHolder.messageDraft.trim().isNotEmpty() || stateHolder.preparedImages.isNotEmpty()
    // 与 ConversationTimeline 同口径；但草稿有内容时优先发送/吸附（忙时点发送
    // 走既有吸附语义，保留 steer/followUp 入口），空草稿才显示停止键。
    val composerSessionState = stateHolder.snapshot.sessionStates[stateHolder.snapshot.selectedSessionId]
    val canAbort = (composerSessionState?.isStreaming == true || composerSessionState?.isCompacting == true) &&
        !composerHasContent
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputIsFocused by remember { mutableStateOf(false) }
    // 图片按钮的统一入口：已授权则开/关最近照片托盘，未授权先弹系统权限框；
    // 拒绝授权时降级为原系统选择器，不堵死选图路径。
    val context = LocalContext.current
    val imagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) stateHolder.togglePhotosTray() else onPickImage()
    }
    val hasImagePermission = {
        val partialGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
        val fullGranted = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
            else ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
        fullGranted || partialGranted
    }
    val imageSourceAction = {
        if (hasImagePermission()) {
            stateHolder.togglePhotosTray()
        } else {
            imagePermissionLauncher.launch(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            )
        }
    }
    var observedSuccessfulSendCount by remember(stateHolder) {
        mutableStateOf(stateHolder.successfulMessageSendCount)
    }
    LaunchedEffect(inputIsFocused) {
        if (inputIsFocused) keyboardController?.show() else keyboardController?.hide()
    }
    LaunchedEffect(stateHolder.successfulMessageSendCount) {
        if (stateHolder.successfulMessageSendCount > observedSuccessfulSendCount) {
            observedSuccessfulSendCount = stateHolder.successfulMessageSendCount
            onDismissInput()
        }
    }
    // 玻璃 Composer：真 backdrop 玻璃只用在输入区这一个控件上（滚动性能不受影响），
    // 独立窗口/列表项才用 piGlassTint/Outline 的平价材质。降级链路见 piLiquidGlass。
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 4.dp, bottom = 10.dp)
            .dropShadow(
                shape = shape,
                shadow = Shadow(
                    radius = 10.dp,
                    spread = 0.dp,
                    color = shadowColor,
                    offset = DpOffset(x = 0.dp, y = 4.dp)
                )
        )
            .piLiquidGlass(shape, PiGlassStyle.FIELD)
    ) {
        ComposerLayout(body = {
            if (stateHolder.preparedImages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stateHolder.preparedImages.size) { index ->
                        val image = stateHolder.preparedImages[index]
                        Box(
                            Modifier.height(72.dp).width(82.dp).background(PiColors.Ocean.copy(alpha = 0.11f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${image.width}×${image.height}", fontSize = 11.sp)
                            Text(
                                "×",
                                modifier = Modifier.align(Alignment.TopEnd).clickable { stateHolder.removePreparedImage(index) }.padding(4.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            // 命中 pill：输入框上方一条只读 Ocean 胶囊（`/名字`），输入框本身保持
            // 纯文本——inlineContent 在此版 BasicTextField 不可用，外置展示零风险。
            val slashPillName = remember(stateHolder.messageDraft, stateHolder.slashCommands) {
                val draft = stateHolder.messageDraft
                val cached = stateHolder.slashCommands ?: return@remember null
                if (!draft.startsWith("/") || " " in draft) return@remember null
                val typed = draft.drop(1)
                cached.second.firstOrNull { it.name == typed }?.name
            }
            val lastSlashPillName = remember { mutableStateOf<String?>(null) }
            if (slashPillName != null) lastSlashPillName.value = slashPillName
            AnimatedVisibility(visible = slashPillName != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
                    Box(
                        Modifier.background(PiColors.Ocean, RoundedCornerShape(7.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "/${lastSlashPillName.value.orEmpty()}",
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            // String 重载的 BasicTextField 无法从外部设光标：联想回填后光标
            // 停在旧位置（如 /con 后）而不是回填文本末尾。本地持 TextFieldValue，
            // 外部写入（联想回填/拦截清空/发送清空/恢复草稿）一律把光标放末尾。
            var composerValue by remember { mutableStateOf(TextFieldValue(stateHolder.messageDraft)) }
            LaunchedEffect(stateHolder.messageDraft) {
                if (stateHolder.messageDraft != composerValue.text) {
                    composerValue = TextFieldValue(stateHolder.messageDraft, TextRange(stateHolder.messageDraft.length))
                }
            }
            BasicTextField(
                value = composerValue,
                onValueChange = {
                    composerValue = it
                    stateHolder.messageDraft = it.text
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp)
                    .heightIn(min = 38.dp, max = 120.dp)
                    .onFocusChanged { inputIsFocused = it.isFocused }
                    .testTag("composer-input"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(PiColors.Ocean),
                decorationBox = { field ->
                    Box {
                        if (stateHolder.messageDraft.isEmpty()) {
                            Text(
                                "描述你想要构建的内容",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                fontSize = 14.sp
                            )
                        }
                        field()
                    }
                }
            )
            // 斜杠命令联想：输入框上方托盘位，随输入过滤；无匹配/无清单时隐藏。
            // 单行 `/名字` + 来源徽章（PROTOCOL §4.21）；点选回填 `/名字 ` 纯文本。
            val slashCandidates = remember(stateHolder.messageDraft, stateHolder.slashCommands) {
                val draft = stateHolder.messageDraft
                val cached = stateHolder.slashCommands
                val head = draft.trimStart()
                if (!head.startsWith("/") || " " in head || cached == null) emptyList()
                else {
                    val query = head.drop(1)
                    cached.second
                        .filter { it.name.startsWith(query, ignoreCase = true) }
                        .filterNot { it.name.lowercase() in BUILTIN_SLASH_COMMAND_NAMES }
                        // 扩展命令不进联想（联想即支持承诺）：RPC 下 handler 是否
                        // 依赖 TUI 无静态元数据，no-op 命令发出去静默无反应会被当
                        // bug；手动全名输入仍照常透传，与 pi 原生一致。
                        .filterNot { it.source == "extension" }
                        .take(8)
                }
            }
            AnimatedVisibility(
                visible = slashCandidates.isNotEmpty(),
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    slashCandidates.forEach { command ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    stateHolder.messageDraft = "/${command.name} "
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "/${command.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiColors.Ocean,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            val badge = when (command.source) {
                                "skill" -> "Skill"
                                "extension" -> "扩展"
                                else -> "模板"
                            }
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
            // 最近照片托盘：插在输入框与按钮行之间，展开时 composer 自身变高。
            AnimatedVisibility(
                visible = stateHolder.photosTrayOpen,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                RecentPhotosTray(
                    stateHolder = stateHolder,
                    onPickAll = {
                        stateHolder.closePhotosTray()
                        onPickImage()
                    }
                )
            }
        }, toolbar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ComposerIconButton(R.drawable.ic_photo_stack, "添加图片", imageSourceAction)
                Spacer(Modifier.width(2.dp))
                ModelControl(
                    stateHolder = stateHolder,
                    modifier = Modifier.weight(1f)
                )
                ContextUsageRing(stateHolder)
                // pi 的 Esc：运行中（或压缩中）发送键变为停止键，点击即 session.abort()
                //（同时中止运行/压缩/重试）。压缩中被拦的发送提示条与此互不干扰。
                Box(
                    Modifier.size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = canAbort || stateHolder.canSend, onClick = {
                    // pi 内置斜杠命令的移动端拦截：有 UI 对等物的命令回提示
                    // （改名/压缩走按钮，模型档位走标签）；未知 /xxx 按文本发送。
                    if (canAbort) {
                        stateHolder.abortCurrent()
                    } else when (stateHolder.consumeSlashCommand(stateHolder.messageDraft)) {
                        AndroidSharedStateHolder.SlashCommandOutcome.CONSUMED -> Unit
                        AndroidSharedStateHolder.SlashCommandOutcome.NOT_A_COMMAND -> stateHolder.sendMessage()
                    }
                })
                        .semantics { contentDescription = if (canAbort) "停止" else "发送" },
                    contentAlignment = Alignment.Center
                ) {
                    // 可见色块内收，外层点击区域与工具栏高度保持不变。
                    Box(
                        modifier = Modifier.size(34.dp).background(
                            color = if (canAbort || composerHasContent) PiColors.Ocean
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(7.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(if (canAbort) R.drawable.ic_stop else R.drawable.ic_send_up),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (canAbort || composerHasContent) Color.White
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        })
    }
}

internal fun piFloatingSurfaceShadow(isDark: Boolean): Color =
    Color.Black.copy(alpha = if (isDark) 0.20f else 0.08f)

internal fun piGlassEdge(isDark: Boolean): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.White.copy(alpha = if (isDark) 0.21f else 0.72f),
        0.48f to Color.White.copy(alpha = if (isDark) 0.12f else 0.52f),
        1f to Color.White.copy(alpha = if (isDark) 0.15f else 0.62f)
    )
)

@Composable
private fun ComposerIconButton(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 最近照片托盘：走 MediaStore 前 10 张（新→旧）+ 尾部“相册”格（打开全量选择器）。
 * 点缩略图通入已有的 prepareImage 附件链路，选中的图出现在 composer 顶部预览条。
 */
@Composable
private fun RecentPhotosTray(stateHolder: AndroidSharedStateHolder, onPickAll: () -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(stateHolder.recentPhotos.size) { index ->
            val uri = stateHolder.recentPhotos[index]
            val thumbnail = stateHolder.recentPhotoThumbnails[uri.toString()]
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(PiColors.Ocean.copy(alpha = 0.10f))
                    .clickable { stateHolder.prepareImage(uri) },
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        item {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(PiColors.Ocean.copy(alpha = 0.10f))
                    .clickable(onClick = onPickAll),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_photo_stack),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
                    )
                    Text(
                        "相册",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerMenuSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 7.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ComposerMenuItem(title: String, selected: Boolean, onClick: () -> Unit) {
    // 紧凑行：模型清单 90+ 项，Material DropdownMenuItem 默认 48dp 触控行
    // 会把弹层撑成超长滚动列表；36dp 行 + 单行省略让可视密度接近翻倍。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(
                if (selected) R.drawable.ic_menu_check else R.drawable.ic_menu_circle
            ),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurface
                .copy(alpha = if (selected) 1f else 0.38f)
        )
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModelControl(
    stateHolder: AndroidSharedStateHolder,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // 推理等级独立入口（对齐 pi 原生：/model 选模型与 /thinking、app.thinking.cycle
    // 切档位是两个入口）：点行上的档位 tag 只弹档位清单，免得选完模型还要重拉
    // 完整弹层。两个弹层互斥。
    var effortExpanded by remember { mutableStateOf(false) }
    val effort = currentModelEffortTitle(stateHolder)
    val selection = effectiveModelSelection(stateHolder)
    val groups = stateHolder.snapshot.modelCatalog?.groups.orEmpty()
    // 新建对话也允许选：模型先记为预选，随首个 message 帧交给 pi（网关先 setModel 再 prompt）。
    val enabled = groups.isNotEmpty()
    var menuAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    Box(modifier.onGloballyPositioned {
        menuAnchorBounds = IntRect(it.positionInWindow().round(), it.size)
    }) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
                effortExpanded = false
                // 外部改过 models.json（改模型名/加 provider）后菜单要能看到：
                // 展开时重拉一次目录（网关有门控，文件没变只花一次 statSync）。
                stateHolder.refreshModelCatalog()
                expanded = true
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                modelTitle(stateHolder),
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            effort?.let {
                ReasoningEffortTag(it) {
                    expanded = false
                    effortExpanded = true
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevrons_vertical),
                contentDescription = null,
                modifier = Modifier.size(9.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
            )
        }
        AnchoredPopupMenu(
            expanded = effortExpanded,
            onDismissRequest = { effortExpanded = false },
            anchorBounds = menuAnchorBounds,
            width = 170.dp,
            horizontalOffset = 8.dp
        ) {
            // 与完整弹层同源：当前模型的 thinkingLevels；选中即以当前模型切档位。
            // selection 为空（无会话也无预选）时维持现状不动作，与完整弹层一致。
            currentModelThinkingLevels(stateHolder).forEach { level ->
                ComposerMenuItem(title = level, selected = level == currentModelEffortTitle(stateHolder)) {
                    selection ?: return@ComposerMenuItem
                    stateHolder.selectModel(selection.provider, selection.modelId, level)
                    effortExpanded = false
                }
            }
        }
        AnchoredPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchorBounds = menuAnchorBounds,
            width = 286.dp,
            horizontalOffset = 8.dp,
            // 模型列表靠近点按入口容易误选，单独增加留白。
            anchorGap = 16.dp
        ) {
            // 推理等级已迁到行上 [max] tag 的独立弹层（/thinking 对齐），这里只列模型。
            groups.forEach { group ->
                ComposerMenuSectionTitle(group.provider)
                group.models.forEach { model ->
                    ComposerMenuItem(
                        title = model.name,
                        selected = group.provider == selection?.provider && model.id == selection.modelId
                    ) {
                        val retainedLevel = currentModelThinkingLevel(stateHolder)
                            ?.takeIf { model.thinkingLevels.contains(it) }
                        stateHolder.selectModel(group.provider, model.id, retainedLevel)
                        expanded = false
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReasoningEffortTag(title: String, onClick: (() -> Unit)? = null) {
    Text(
        title,
        modifier = Modifier
            .testTag("reasoning-effort-tag")
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(
                PiColors.Purple.copy(alpha = 0.16f),
                RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 10.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun ContextUsageRing(stateHolder: AndroidSharedStateHolder) {
    val usage = stateHolder.snapshot.sessionStates[stateHolder.snapshot.selectedSessionId]?.contextUsage
    val percent = usage?.percent
    val tokens = usage?.tokens
    val contextWindow = usage?.contextWindow
    val progress = when {
        percent != null -> (percent / 100.0).toFloat()
        tokens != null && contextWindow != null && contextWindow > 0L -> tokens.toFloat() / contextWindow
        else -> 0f
    }
    // 交互分层：单击圆环=查看占用详情（直觉行为）；详情里给"压缩上下文"
    // 入口，并二次确认（会中断当前运行）——压缩不放在单击里，避免误触。
    var showDetails by remember { mutableStateOf(false) }
    var confirmCompact by remember { mutableStateOf(false) }
    Box(
        Modifier.size(22.dp)
            .clickable { showDetails = true }
            .semantics { contentDescription = "上下文占用" },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(18.dp),
            strokeWidth = 3.dp,
            color = PiColors.Ocean,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
        )
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            containerColor = piGlassTint(strong = true),
            title = { Text("上下文占用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (usage == null) {
                        Text("还没有上下文占用数据")
                    } else {
                        Text(
                            buildString {
                                append(percent?.let { "${it.toInt()}%" } ?: "—")
                                tokens?.let { append(" · $it tokens") }
                                contextWindow?.let { append(" / $it") }
                            },
                            fontSize = 15.sp
                        )
                        if (stateHolder.snapshot.sessionStates[stateHolder.snapshot.selectedSessionId]?.isCompacting == true) {
                            Text("正在压缩中…", fontSize = 13.sp, color = PiColors.Ocean)
                        }
                    }
                }
            },
            confirmButton = {
                Text(
                    "压缩上下文",
                    color = PiColors.Ocean,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(enabled = usage != null) {
                            showDetails = false
                            confirmCompact = true
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            },
            dismissButton = {
                Text(
                    "关闭",
                    modifier = Modifier
                        .clickable { showDetails = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        )
    }
    if (confirmCompact) {
        AlertDialog(
            onDismissRequest = { confirmCompact = false },
            containerColor = piGlassTint(strong = true),
            title = { Text("压缩上下文？") },
            text = { Text("会中止当前正在进行的回答，并把已有对话压缩为摘要。继续？") },
            confirmButton = {
                Text(
                    "压缩",
                    color = PiColors.Ocean,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            confirmCompact = false
                            stateHolder.startManualCompact()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            },
            dismissButton = {
                Text(
                    "取消",
                    modifier = Modifier
                        .clickable { confirmCompact = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        )
    }
}

@Composable
private fun ConversationRow(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    attachmentStates: Map<String, AttachmentLoadState>,
    onRetryAttachment: (String) -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    when (item.kind) {
        ConversationItemKind.USER -> UserMessage(
            item,
            thumbnails,
            attachmentStates,
            onRetryAttachment,
            onPreviewImages
        )
        ConversationItemKind.ASSISTANT -> AssistantMessage(item, thumbnails, attachmentStates, onRetryAttachment)
        ConversationItemKind.STATUS -> StatusRow(item)
        ConversationItemKind.SYSTEM -> SystemRow(item)
        else -> Unit
    }
}

@Composable
private fun ConversationProcessRow(
    group: ConversationProcessGroup,
    collapsedGroups: SnapshotStateMap<String, Boolean>
) {
    // 折叠状态提升到会话级 map（key 用组 id，锚定前驱消息+序数，跨流式 finalize
    // 稳定）：手动收起后流式追加、滚走回来都不会弹开或丢失；会话切换由调用方
    // 按 sessionId 重建 map。
    val groupKey = group.id
    val collapsed = collapsedGroups[groupKey] == true
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                collapsedGroups[groupKey] = !collapsed
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                group.title,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ProcessChevron(!collapsed)
        }
        if (!collapsed) {
            Column(
                modifier = Modifier.padding(start = 2.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 按原事件顺序逐段渲染（think→tool→think→tool 不重排）；组整体仍是
                // LazyColumn 的单个 item，虚拟化粒度不变。
                group.orderedSegments().forEach { segment ->
                    when (segment) {
                        is ConversationProcessSegment.Reasoning -> ThinkingRow(segment.item)
                        is ConversationProcessSegment.Context -> ProcessContextDisclosure(segment.item)
                        is ConversationProcessSegment.ToolRun ->
                            ProcessToolBundle(segment.id, segment.tools)
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 7.dp),
            thickness = 1.dp,
            color = Color.Gray.copy(alpha = 0.16f)
        )
    }
}

@Composable
private fun ProcessContextDisclosure(item: ConversationItem) {
    ProcessDisclosure(
        id = "context-${item.id}",
        title = item.title.toContextDisplayTitle(),
        preview = item.text.singleLinePreview(),
        iconRes = R.drawable.ic_process_context,
        tint = PiColors.Success
    ) {
        PiMarkdownText(item.text, Modifier.fillMaxWidth(), compact = true)
    }
}

/** thinking 独立行：流式单行跟随预览（对标 thinking-preview.ts），完成后定格可展开。
 *  `✻ Thinking · <尾部>▋`：单行压平（换行→空格）、尾部优先（Clip 左裁）、
 *  行首渐隐（横向 alpha 渐变）、块光标 500ms 闪烁；定格去光标，点开展开全文。 */
@Composable
private fun ThinkingRow(item: ConversationItem) {
    // 流式 thinking 的 item.id 是 `stream-reason-$key`，完成后是 `$id-reason`。
    val streaming = item.id.startsWith("stream-reason-")
    var expanded by remember(item.id) { mutableStateOf(false) }
    val folded = remember(item.text) { item.text.replace(Regex("\\s+"), " ").trim() }
    if (!streaming) {
        ProcessDisclosure(
            id = "thinking-${item.id}",
            title = "Think",
            preview = folded,
            previewTail = true,
            iconRes = R.drawable.ic_process_reasoning,
            tint = PiColors.Purple
        ) {
            PiMarkdownText(item.text, Modifier.fillMaxWidth(), compact = true)
        }
        return
    }
    var cursorOn by remember(item.id) { mutableStateOf(true) }
    LaunchedEffect(item.id) {
        while (true) {
            delay(500)
            cursorOn = !cursorOn
        }
    }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("✻", color = PiColors.Ocean, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("Thinking ·", color = dim, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        TailFollowText(
            text = folded,
            color = dim,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        // 光标恒占位：只切 alpha，不切字符，行宽不抖。
        Text(
            "▋",
            color = PiColors.Ocean.copy(alpha = if (cursorOn) 1f else 0f),
            fontSize = 13.sp
        )
    }
    if (expanded) {
        PiMarkdownText(item.text, Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp), compact = true)
    }
}

/**
 * 尾部跟随单行文本（thinking-preview.ts 的 Compose 对应物）：
 * 文本**左对齐**从行首打字——未溢出时就是普通打字机；写满容器后整体左移，
 * 始终让尾部贴右缘、头部被裁并由左缘渐隐吃掉，与 pi 的 `takeTail` 取尾部
 * 窗口同语义。
 *
 * 不要用 `TextAlign.End` 代替：右对齐会让短文本紧贴右缘、随长度增长从右往
 * 左"长"，观感是 PPT 飘入而非打字机（用户实测反馈）。
 */
@Composable
private fun TailFollowText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    var textWidth by remember { mutableIntStateOf(0) }
    var boxWidth by remember { mutableIntStateOf(0) }
    val overflow = (textWidth - boxWidth).coerceAtLeast(0)
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { boxWidth = it.width }
            // 未溢出时不挂渐隐：短文本左端不该淡出（pi 扩展同样只在 has-overflow 时挂 mask）。
            .then(if (overflow > 0) Modifier.fadingEdge() else Modifier)
    ) {
        Text(
            text,
            color = color,
            fontSize = fontSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { textWidth = it.size.width },
            // unbounded：按内容宽度测量，不被容器约束裁掉，否则算不出溢出量。
            // align 必须显式传 Start：默认的 CenterHorizontally 在超宽时会把内容
            // 居中（自带 -overflow/2 的内置左移），再叠加下面的 -overflow 就成了
            // -1.5×overflow，尾部不贴右缘，溢出大于两倍行宽时整行被推出左缘变空白。
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset { IntOffset(-overflow, 0) }
        )
    }
}

/** 行首左缘渐隐（thinking-preview.ts 行首灰阶渐隐的 Compose 对应物，DstIn 遮罩）。 */
private fun Modifier.fadingEdge(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.White),
            blendMode = BlendMode.DstIn
        )
    }


@Composable
private fun ProcessToolBundle(
    runId: String,
    tools: List<ConversationProcessTool>
) {
    val names = tools.mapNotNull { it.call?.title }.take(2)
    val title = when {
        names.isEmpty() -> "查看 ${tools.size} 个工具结果"
        else -> "使用了 ${names.joinToString("、")}${if (tools.size > 2) " 等工具" else ""}"
    }
    ProcessDisclosure(
        id = "tools-$runId",
        title = title,
        preview = "",
        iconRes = R.drawable.ic_process_tool,
        tint = PiColors.Orange,
        bodyStartPadding = 14.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tools.forEach { ProcessToolDisclosure(it) }
        }
    }
}

@Composable
private fun ProcessToolDisclosure(tool: ConversationProcessTool) {
    val failed = tool.result?.isError == true
    ProcessDisclosure(
        id = "tool-${tool.id}",
        title = tool.call?.title ?: tool.result?.title ?: "工具",
        preview = tool.result?.let { if (failed) "失败" else "完成" }.orEmpty(),
        iconRes = R.drawable.ic_process_terminal,
        tint = if (failed) Color.Red else PiColors.Orange
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            tool.call?.text?.takeIf(String::isNotEmpty)?.let { arguments ->
                Text(
                    "调用参数",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                PiLazyMarkdownText(
                    fencedCodeBlock("json", arguments),
                    Modifier.fillMaxWidth(),
                    compact = true,
                    deferFirstParse = true
                )
            }
            tool.result?.text?.takeIf(String::isNotEmpty)?.let { result ->
                Text(
                    if (failed) "错误" else "结果",
                    color = if (failed) Color.Red else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                PiLazyMarkdownText(result, Modifier.fillMaxWidth(), compact = true, deferFirstParse = true)
            }
        }
    }
}

@Composable
private fun ProcessDisclosure(
    id: String,
    title: String,
    preview: String,
    /** true 时 preview 按尾部跟随渲染（thinking 用：完成态要看"最后想到哪"，
     *  与流式期间的尾部预览一致；默认左对齐省略尾部，适合工具摘要）。 */
    previewTail: Boolean = false,
    iconRes: Int,
    tint: Color,
    bodyStartPadding: Dp = 26.dp,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember(id) { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tint
            )
            Text(
                title,
                color = tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (preview.isNotEmpty()) {
                Text("·", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                if (previewTail) {
                    TailFollowText(
                        text = preview,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        preview,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            ProcessChevron(expanded)
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().padding(start = bodyStartPadding, bottom = 3.dp)) {
                content()
            }
        }
    }
}

/** kimi swarm 的波条（图形版）：每格 0..1 的波动亮度，相位速率 0.32 rad/80ms、波长≈3 cells。 */
internal fun waveDotLevels(frame: Int, seed: Int, cells: Int = 6): List<Float> =
    List(cells) { i ->
        val phase = frame * 0.32 + (seed and 0x7) * 0.9 + i * 2.09
        ((kotlin.math.sin(phase) + 1.0) / 2.0).toFloat()
    }

@Composable
private fun ProcessChevron(expanded: Boolean) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(15.dp).rotate(if (expanded) 90f else 0f),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    )
}

private fun String.singleLinePreview(): String =
    replace(Regex("\\s+"), " ").trim()

/**
 * 带图发送的本地预览条：正式回显到达前的占位气泡。
 * 图片渲染完全复用对话内附件组件（128dp 上限、内嵌 base64 解码、点击全屏），
 * 只额外给出发送状态行与失败/未确认时的恢复草稿入口。
 */
@Composable
private fun OutgoingImagePreviewRow(
    preview: OutgoingImagePreview,
    stateHolder: AndroidSharedStateHolder,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    val isDark = LocalPiDark.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, top = 12.dp, bottom = 12.dp)
            .testTag("outgoing-image-preview"),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 预览数据带内嵌 base64（与正式回显同一 attachmentId 命中路径），
        // 缩略图/状态 map 传空：UserAttachmentImage 的 inline 解码直接渲染。
        UserAttachmentPreview(
            attachments = preview.images,
            thumbnails = emptyMap(),
            states = emptyMap(),
            onRetry = {},
            onPreviewImages = onPreviewImages
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (preview.state == OutgoingImagePreviewState.SENDING) {
                PiSpinner(color = PiColors.Ocean, fontSize = 11.sp)
            }
            Text(
                text = when (preview.state) {
                    OutgoingImagePreviewState.SENDING -> "发送中…"
                    OutgoingImagePreviewState.CONFIRMED -> "已发出，等待回显"
                    OutgoingImagePreviewState.UNCONFIRMED -> "发送状态未确认"
                    OutgoingImagePreviewState.FAILED -> "发送失败"
                },
                fontSize = 11.sp,
                color = if (preview.state == OutgoingImagePreviewState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
            )
        }
        if (
            preview.state == OutgoingImagePreviewState.FAILED ||
            preview.state == OutgoingImagePreviewState.UNCONFIRMED
        ) {
            // 恢复仅回填草稿不重发；输入区已有新编辑时禁用（不覆盖新草稿）。
            TextButton(
                shape = RoundedCornerShape(6.dp),
                onClick = { stateHolder.restoreOutgoingPreviewDraft(preview.sendToken) },
                enabled = stateHolder.canRestoreOutgoingDraft
            ) {
                Text("恢复草稿", fontSize = 13.sp, color = PiColors.Ocean)
            }
        }
    }
}

private fun String.toContextDisplayTitle(): String = when {
    startsWith("Context · ") -> "上下文注入 · ${removePrefix("Context · ")}"
    else -> this
}

@Composable
private fun UserMessage(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    val isDark = LocalPiDark.current
    val bubbleFill = PiColors.Ocean.copy(alpha = if (isDark) 0.24f else 0.11f)
    val bubbleEdge = PiColors.Ocean.copy(alpha = if (isDark) 0.34f else 0.08f)
    Column(
        Modifier.fillMaxWidth().padding(start = 34.dp, top = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        UserAttachmentPreview(item.images, thumbnails, states, onRetry, onPreviewImages)
        // isNotBlank：纯图消息在 pi 侧会被注入一个占位空格（空 text block 会被上游
        // 拒绝，见网关 pi-runtime.prompt），历史回放时不应因此多出一个空气泡。
        if (item.text.isNotBlank()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = item.text,
                    modifier = Modifier
                        .background(bubbleFill, RoundedCornerShape(12.dp))
                        .border(0.7.dp, bubbleEdge, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("user-text-bubble"),
                    fontSize = 14.sp
                )
                CopyButton(item.text)
            }
        }
    }
}

private val USER_ATTACHMENT_PREVIEW_MAX_SIDE = 128.dp

internal data class UserAttachmentPreviewSize(val width: Dp, val height: Dp)

internal fun userSingleAttachmentPreviewSize(
    sourceWidth: Int,
    sourceHeight: Int,
    availableWidth: Dp
): UserAttachmentPreviewSize {
    // 对话内只展示紧凑缩略图；各方向使用同一长边上限，保持比例且不放大小图。
    val maximumWidth = minOf(USER_ATTACHMENT_PREVIEW_MAX_SIDE, availableWidth)
    // 尺寸未知且缩略图尚未解码时也保留可见的小占位框，避免退化成 1dp。
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return UserAttachmentPreviewSize(width = maximumWidth, height = maximumWidth)
    }
    val safeWidth = sourceWidth.coerceAtLeast(1).toFloat()
    val safeHeight = sourceHeight.coerceAtLeast(1).toFloat()
    val maximumHeight = USER_ATTACHMENT_PREVIEW_MAX_SIDE
    val scale = minOf(
        1f,
        maximumWidth.value / safeWidth,
        maximumHeight.value / safeHeight
    )
    return UserAttachmentPreviewSize(
        width = (safeWidth * scale).dp,
        height = (safeHeight * scale).dp
    )
}

@Composable
private fun UserAttachmentPreview(
    attachments: List<GatewayImageAttachment>,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    if (attachments.isEmpty()) return
    BoxWithConstraints(Modifier.testTag("user-image-bubble")) {
        if (attachments.size == 1) {
            val attachment = attachments.first()
            val thumb = thumbnails[attachment.attachmentId]
            val previewSize = userSingleAttachmentPreviewSize(
                // 消息没有尺寸时使用已解码缩略图的宽高比；两者都未知才用小方框占位。
                sourceWidth = attachment.width.takeIf { it > 0 } ?: (thumb?.width ?: 0),
                sourceHeight = attachment.height.takeIf { it > 0 } ?: (thumb?.height ?: 0),
                availableWidth = maxWidth
            )
            UserAttachmentImage(
                attachment = attachment,
                image = thumbnails[attachment.attachmentId],
                state = states[attachment.attachmentId],
                onRetry = onRetry,
                onPreview = { onPreviewImages(attachments, 0) },
                modifier = Modifier.size(previewSize.width, previewSize.height),
                cornerRadius = 11.dp,
                borderWidth = 0.dp
            )
        } else {
            val visibleAttachments = attachments.take(3)
            val cardOffset = minOf(8.dp, maxWidth / visibleAttachments.size)
            val cardSide = minOf(
                USER_ATTACHMENT_PREVIEW_MAX_SIDE,
                (maxWidth - cardOffset * (visibleAttachments.size - 1)).coerceAtLeast(0.dp)
            )
            val previewSide = cardSide + cardOffset * (visibleAttachments.size - 1)
            Box(Modifier.size(previewSide)) {
                visibleAttachments.indices.reversed().forEach { index ->
                    val attachment = visibleAttachments[index]
                    UserAttachmentImage(
                        attachment = attachment,
                        image = thumbnails[attachment.attachmentId],
                        state = states[attachment.attachmentId],
                        onRetry = onRetry,
                        onPreview = { onPreviewImages(attachments, index) },
                        modifier = Modifier
                            .offset(
                                x = cardOffset * index,
                                y = cardOffset * (visibleAttachments.lastIndex - index)
                            )
                            .size(cardSide),
                        cornerRadius = 12.dp,
                        borderWidth = 2.dp
                    )
                }
                Text(
                    text = "${attachments.size}张",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun UserAttachmentImage(
    attachment: GatewayImageAttachment,
    image: ImageBitmap?,
    state: AttachmentLoadState?,
    onRetry: (String) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier,
    cornerRadius: Dp,
    borderWidth: Dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    // 消息不带 data（跨设备/出站预算已裁剪）且缓存 miss 时，重试永远失败：
    // 标记为不可重试，占位文案区分，避免误导。
    val unrecoverable = state == AttachmentLoadState.FAILED && attachment.dataBase64.isNullOrBlank()
    val retryable = (state == AttachmentLoadState.FAILED && !unrecoverable) ||
        state == AttachmentLoadState.DEFERRED
    // 网关未裁剪时消息自带完整 base64：直接解码渲染（pi TUI 同语义：内存消息
    // 直渲），不依赖本地附件缓存的写/读时序。解码结果按 attachmentId 记忆，
    // 滚出屏幕随 remember 销毁。
    // 消息自带完整 base64 时直接解码渲染（不依赖本地缓存链）。解码放 IO，
    // composition 主线程只消费结果。
    val inlineImage by produceState<ImageBitmap?>(
        initialValue = null,
        attachment.attachmentId,
        attachment.dataBase64
    ) {
        val data = attachment.dataBase64?.takeIf(String::isNotBlank) ?: return@produceState
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(data) }
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .then(
                if (borderWidth > 0.dp) Modifier.border(borderWidth, MaterialTheme.colorScheme.surface, shape)
                else Modifier
            )
            .clickable {
                if (retryable) onRetry(attachment.attachmentId) else onPreview()
            },
        contentAlignment = Alignment.Center
    ) {
        val resolved = image ?: inlineImage
        if (resolved != null) {
            Image(
                bitmap = resolved,
                contentDescription = attachment.name ?: "图片附件",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = when {
                    state == AttachmentLoadState.FAILED && unrecoverable -> "图片未同步"
                    state == AttachmentLoadState.FAILED -> "加载失败 · 点击重试"
                    state == AttachmentLoadState.DEFERRED -> "已释放 · 点击重载"
                    else -> "正在加载图片…"
                },
                modifier = Modifier.padding(10.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

internal data class ConversationImagePreviewRequest(
    val attachments: List<GatewayImageAttachment>,
    val initialIndex: Int
)

internal fun imagePreviewInitialPage(imageCount: Int, requestedIndex: Int): Int =
    requestedIndex.coerceIn(0, (imageCount - 1).coerceAtLeast(0))

/** base64 按显示尺寸采样解码：2000×2000 原图全尺寸 ARGB_8888 约 16MB/张，
 * 多张同屏峰值数十 MB。先读 bounds 再按 1024 边长采样。 */
internal suspend fun decodeSampledBitmap(base64Data: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
            decodeSampledBitmap(bytes)
        }.getOrNull()
    }

internal suspend fun decodeSampledBitmap(bytes: ByteArray): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = thumbnailSampleSize(bounds.outWidth, bounds.outHeight)
            android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )?.asImageBitmap()
        }.getOrNull()
    }

@Composable
internal fun ConversationImagePreviewDialog(
    request: ConversationImagePreviewRequest,
    thumbnails: Map<String, ImageBitmap>,
    onDismiss: () -> Unit,
    loadFullImage: suspend (GatewayImageAttachment) -> ImageBitmap? = { null },
    lightbox: Boolean = false,
    overlayColor: Color = Color.Black,
) {
    if (request.attachments.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = imagePreviewInitialPage(request.attachments.size, request.initialIndex),
        pageCount = request.attachments::size
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        SideEffect {
            // 新灯箱由原型遮罩控制透明度，避免与系统 dim 叠加；旧 UI 沿用默认。
            if (lightbox) (dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayColor)
                .testTag("conversation-image-preview")
                .then(if (lightbox) Modifier.semantics {
                    contentDescription = "图片预览，${pagerState.currentPage + 1} / ${request.attachments.size}"
                    onClick(label = "关闭图片预览") { onDismiss(); true }
                } else Modifier)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val attachment = request.attachments[page]
                // 缩略图被 LRU 淘汰时先走 dataBase64 现解，仍无（出站预算已剥图）
                // 再走 runtime 缓存/§4.22 按需取回，不再黑屏无提示。
                val fallbackImage by produceState<ImageBitmap?>(
                    initialValue = null,
                    attachment.attachmentId,
                    attachment.dataBase64
                ) {
                    val data = attachment.dataBase64?.takeIf(String::isNotBlank)
                    value = when {
                        data != null -> withContext(Dispatchers.IO) { decodeSampledBitmap(data) }
                        else -> loadFullImage(attachment)
                    }
                }
                ZoomablePreviewImage(
                    image = if (lightbox) fallbackImage ?: thumbnails[attachment.attachmentId]
                        else thumbnails[attachment.attachmentId] ?: fallbackImage,
                    contentDescription = attachment.name ?: "图片",
                    lightbox = lightbox,
                    onTap = if (lightbox) onDismiss else null,
                )
            }
            if (!lightbox) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .semantics { contentDescription = "关闭图片预览" },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.16f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${request.attachments.size}",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(38.dp))
            }
            }
        }
    }
}

@Composable
private fun ZoomablePreviewImage(
    image: ImageBitmap?,
    contentDescription: String,
    lightbox: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var translation by remember(image) { mutableStateOf(Offset.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(image, lightbox) {
                awaitEachGesture {
                    var event = awaitPointerEvent()
                    val start = event.changes.first().position
                    var tap = true
                    while (event.changes.any { it.pressed }) {
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount > 1 || event.changes.any {
                            it.isConsumed || (it.position - start).getDistance() > viewConfiguration.touchSlop
                        }) tap = false
                        if (pressedCount >= 2 || scale > 1f) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            if (nextScale == 1f) {
                                translation = Offset.Zero
                            } else {
                                val maximumX = size.width * (nextScale - 1f) / 2f
                                val maximumY = size.height * (nextScale - 1f) / 2f
                                val pan = event.calculatePan()
                                translation = Offset(
                                    x = (translation.x + pan.x).coerceIn(-maximumX, maximumX),
                                    y = (translation.y + pan.y).coerceIn(-maximumY, maximumY)
                                )
                            }
                            scale = nextScale
                            event.changes.forEach { it.consume() }
                        }
                        event = awaitPointerEvent()
                    }
                    if (tap && event.changes.none { it.isConsumed }) currentOnTap?.invoke()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            val imageModifier = if (lightbox) {
                Modifier.size(fineCatImageSize(image.width, image.height, maxWidth - 32.dp, maxHeight * 0.76f))
                    .clip(RoundedCornerShape(4.dp))
            } else Modifier.fillMaxSize()
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = imageModifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_photo_stack),
                contentDescription = contentDescription,
                modifier = Modifier.size(54.dp),
                tint = Color.White.copy(alpha = 0.42f)
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AssistantMessageHeaderContent(item, thumbnails, states, onRetry)
        if (item.text.isNotEmpty()) {
            PiStreamingAwareMarkdownText(
                markdown = item.text,
                isStreaming = isStreamingConversationItem(item),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AssistantMessageHeader(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        AssistantMessageHeaderContent(item, thumbnails, states, onRetry)
    }
}

@Composable
private fun AssistantMessageHeaderContent(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    // 复制入口并入已有消息头右端（对齐 pi 原生 app.message.copy 语义：复制整条
    // 消息正文而非 Markdown 分片，带反馈）；不再让复制尾行独占 LazyColumn footer。
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            PiLogoIcon(Modifier.size(24.dp))
            Text(
                item.title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        CopyButton(item.text)
    }
    AttachmentGrid(item.images, thumbnails, states, onRetry)
}

@Composable
private fun StatusRow(item: ConversationItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.25f))
        Text(listOf(item.title, item.text).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.padding(horizontal = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        HorizontalDivider(Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.25f))
    }
}

@Composable
private fun SystemRow(item: ConversationItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background((if (item.isError) Color.Red else PiColors.Ocean).copy(alpha = 0.06f), RoundedCornerShape(11.dp)).padding(10.dp)
    ) {
        Text(if (item.isError) "!" else "⌁", color = if (item.isError) Color.Red else PiColors.Ocean)
        Spacer(Modifier.width(9.dp))
        Column {
            Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (item.text.isNotEmpty()) Text(item.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun AttachmentGrid(
    attachments: List<GatewayImageAttachment>,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { attachment ->
                    val image = thumbnails[attachment.attachmentId]
                    Box(
                        Modifier.weight(1f).heightIn(min = 80.dp, max = 240.dp)
                            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .clickable(enabled = states[attachment.attachmentId] in setOf(AttachmentLoadState.FAILED, AttachmentLoadState.DEFERRED)) {
                                onRetry(attachment.attachmentId)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (image != null) {
                            Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                        } else {
                            Text(
                                when (states[attachment.attachmentId]) {
                                    AttachmentLoadState.FAILED -> "加载失败 · 点击重试"
                                    AttachmentLoadState.DEFERRED -> "已释放 · 点击重载"
                                    else -> "正在加载图片…"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    if (text.isEmpty()) return
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Box(
        modifier = Modifier
            // 40dp 触达：贴近无障碍下限的同时不给消息头翻倍撑高；图标 16dp 居中。
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .clickable {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PiMobile", text))
            copied = true
        }.semantics { contentDescription = if (copied) "已复制" else "复制正文" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(if (copied) R.drawable.ic_menu_check else R.drawable.ic_copy_message),
            contentDescription = null,
            modifier = Modifier.size(if (copied) 14.dp else 16.dp),
            tint = if (copied) PiColors.Ocean else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun MarkdownLikeText(text: String) {
    PiMarkdownText(text, Modifier.fillMaxWidth())
}

internal fun isStreamingConversationItem(item: ConversationItem): Boolean =
    item.id.startsWith("stream-")

@Composable
private fun SmallConnectionDot(state: GatewayConnectionState) {
    val color = when (state) {
        GatewayConnectionState.CONNECTED -> PiColors.Success
        GatewayConnectionState.FAILED -> Color.Red
        GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING, GatewayConnectionState.WAITING_FOR_NETWORK -> PiColors.Amber
        else -> Color.Gray
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

private fun modelTitle(stateHolder: AndroidSharedStateHolder): String {
    val selection = effectiveModelSelection(stateHolder) ?: return "选择模型"
    return currentModelItem(stateHolder)?.name ?: selection.modelId
}

/** 生效的模型选择：已有会话取 pi 会话级状态；新建对话尚无会话，取预选
 *  （selectModel 存在 holder，随首个 message 帧携带，发送成功后消费）。 */
private fun effectiveModelSelection(stateHolder: AndroidSharedStateHolder): GatewayModelSelection? {
    val sessionState = stateHolder.snapshot.sessionStates[stateHolder.snapshot.selectedSessionId]
    val model = sessionState?.model ?: return stateHolder.pendingModelSelection
    return GatewayModelSelection(model.provider, model.modelId, sessionState.thinkingLevel)
}

/** 当前推理档位（pi thinking level 字符串）；新建对话取预选。 */
private fun currentModelThinkingLevel(stateHolder: AndroidSharedStateHolder): String? =
    effectiveModelSelection(stateHolder)?.thinkingLevel

private fun currentModelItem(stateHolder: AndroidSharedStateHolder): GatewayModelItem? {
    val selection = effectiveModelSelection(stateHolder) ?: return null
    return stateHolder.snapshot.modelCatalog?.groups
        ?.firstOrNull { it.provider == selection.provider }
        ?.models
        ?.firstOrNull { it.id == selection.modelId }
}

/** 当前模型支持的推理档位（pi `thinkingLevels`，字符串档位）。 */
private fun currentModelThinkingLevels(stateHolder: AndroidSharedStateHolder): List<String> =
    currentModelItem(stateHolder)?.thinkingLevels.orEmpty()

/** pi 档位顺序（对齐 pi `defaults.js` 的 `THINKING_LEVEL_OPTIONS`）。 */
private val THINKING_LEVEL_ORDER = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/** pi `clampThinkingLevel`：请求档位不在模型支持集时，先向上找更高档位、再向下找，
 *  都没有则取支持集首项（空集为 `off`）。与 pi-ai 实现保持一致。 */
internal fun clampThinkingLevel(level: String, available: List<String>): String {
    if (available.contains(level)) return level
    val requested = THINKING_LEVEL_ORDER.indexOf(level)
    if (requested < 0) return available.firstOrNull() ?: "off"
    for (index in requested until THINKING_LEVEL_ORDER.size) {
        val candidate = THINKING_LEVEL_ORDER[index]
        if (available.contains(candidate)) return candidate
    }
    for (index in requested - 1 downTo 0) {
        val candidate = THINKING_LEVEL_ORDER[index]
        if (available.contains(candidate)) return candidate
    }
    return available.firstOrNull() ?: "off"
}

/** pi 无显式档位时的默认档位（`DEFAULT_THINKING_LEVEL`）。 */
private const val PI_DEFAULT_THINKING_LEVEL = "medium"

/** 档位**显示**名：pi 档位本身是字符串。新会话（预选尚无档位）时用 pi 的默认档位
 *  clamp 到模型支持集展示，否则档位 tag 整块不渲染，用户无法进入档位菜单。
 *  仅影响展示：未显式选档位时不会把该值写入预选/发送（保持 pi 的 settings 默认语义）。 */
private fun currentModelEffortTitle(stateHolder: AndroidSharedStateHolder): String? =
    currentModelThinkingLevel(stateHolder) ?: currentModelThinkingLevels(stateHolder)
        .takeIf { it.isNotEmpty() }
        ?.let { clampThinkingLevel(PI_DEFAULT_THINKING_LEVEL, it) }
