package com.finecat.pimobile.android.ui.finecat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.finecat.components.FcSearchField
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.conversation.projectBasename
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatMotion
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.android.ui.treeClockLabel
import com.finecat.pimobile.android.ui.treeRowMatchesSearch
import com.finecat.pimobile.android.ui.treeRowPathParts
import com.finecat.pimobile.android.ui.treeDisplayPrefixes
import com.finecat.pimobile.android.ui.treeRowRoleLabel
import com.finecat.pimobile.android.ui.treeRowText
import com.finecat.pimobile.shared.protocol.GatewayTreeNode
import com.finecat.pimobile.shared.protocol.GatewayTreeView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 树页五种筛选药丸（Pi Ctrl+D/T/U/L/A 的触屏等价物）：值与网关 tree-view-v1 的
 * `filter` 同词表，筛选/排序/折叠全部在网关按 Pi 规则算，客户端不自己过滤。
 * 除“默认”外再次点同一枚回到 default（Pi 的 toggle 语义）。
 */
internal val TREE_FILTER_PILLS = listOf(
    "default" to "默认",
    "no-tools" to "无工具",
    "user-only" to "仅用户",
    "labeled-only" to "仅标签",
    "all" to "全部",
)

/** 树行字号/行高：原型 `finecat-tree.html` 的 13px/1.75 等宽密排视图。 */
private val TREE_ROW_FONT_SIZE = 13.sp
private val TREE_ROW_LINE_HEIGHT = 22.sp

/**
 * 实际触控尺寸（如实记录，不用重叠外扩假装 48dp）：
 * - 树行 30dp 高（原型一行约 23px；密排列表按原型密度保留，不撑到 48dp）；
 * - 折叠直接点击前缀标记，最小高 30dp，不为每行另留空列；
 * - 筛选药丸 32dp 高（原型 29px）；
 * - 动作卡按钮 42dp 高（原型 42px，达标）、关闭 ✕ 32dp、标签输入 40dp。
 */
private val TREE_ROW_MIN_HEIGHT = 30.dp
private val TREE_PILL_HEIGHT = 32.dp

/** 会话内保留滚动、筛选、折叠、浏览选中与动作卡状态；树布局始终由网关的 Pi 投影提供。 */
class FineCatTreeScreenState {
    val horizontal = ScrollState(0)
    val vertical = LazyListState()
    /** 浏览选中（动作卡的目标）：与真实 active leaf 是两个状态，取消动作卡不会跳转。 */
    var selectedNodeId: String? by mutableStateOf(null)
    /** 动作卡对应的 entry id（null = 关闭）。 */
    var actionEntryId: String? by mutableStateOf(null)
    /** 动作卡内的标签输入态（Pi Shift+L 的等价物）。 */
    var labelMode by mutableStateOf(false)
    var labelDraft by mutableStateOf("")
    /** 复制/标签的即时回执（短暂显示后自动清）。 */
    var actionFeedback: String? by mutableStateOf(null)
    var lastPositionedId: String? = null
    var view by mutableStateOf(GatewayTreeView())
}

@Composable
fun FineCatTreeView(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    treeScreenState: FineCatTreeScreenState = remember { FineCatTreeScreenState() },
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val fontScale = LocalDensity.current.fontScale
    val clipboard = LocalClipboardManager.current
    val motion = LocalFineCatMotion.current
    val sessionId = stateHolder.snapshot.selectedSessionId
    val sessionCwd = stateHolder.snapshot.sessions.firstOrNull { it.id == sessionId }?.cwd
    val fullView = "tree-view-v1" in stateHolder.gatewayState.capabilities
    val actionsSupported = stateHolder.treeActionsSupported
    val ownedTree = stateHolder.sessionTree?.takeIf { it.first == sessionId }
    val nodes = ownedTree?.third.orEmpty()
    val displayPrefixes = remember(nodes) { treeDisplayPrefixes(nodes) }
    val leafId = ownedTree?.second
    val loading = stateHolder.treeRequestInFlight
    val view = treeScreenState.view
    var query by remember(sessionId) { mutableStateOf(view.search) }
    fun load(next: GatewayTreeView) {
        if (stateHolder.treeRequestInFlight) return
        treeScreenState.view = next
        stateHolder.loadSessionTree(if (fullView) next else null)
    }
    LaunchedEffect(sessionId, fullView) {
        treeScreenState.view = treeScreenState.view.copy(filter = "default")
        snapshotFlow { stateHolder.treeRequestInFlight }.first { !it }
        stateHolder.loadSessionTree(if (fullView) treeScreenState.view else null)
    }
    // 在途请求结束后提交最后一个词；新输入会取消前一次防抖等待。
    LaunchedEffect(sessionId, fullView, query) {
        if (!fullView || query == treeScreenState.view.search) return@LaunchedEffect
        delay(250)
        snapshotFlow { stateHolder.treeRequestInFlight }.first { !it }
        if (query != treeScreenState.view.search) {
            load(treeScreenState.view.copy(search = query, foldedIds = emptyList()))
        }
    }
    val seenBranchTick = remember { stateHolder.branchSwitchedTick }
    LaunchedEffect(stateHolder.branchSwitchedTick) {
        if (stateHolder.branchSwitchedTick != seenBranchTick) onBack()
    }
    // 换会话：浏览选中与动作卡都不跨会话保留（动作卡的目标 entry 属于旧会话）。
    LaunchedEffect(sessionId) {
        treeScreenState.selectedNodeId = null
        treeScreenState.actionEntryId = null
        treeScreenState.labelMode = false
        treeScreenState.labelDraft = ""
        treeScreenState.actionFeedback = null
    }
    // Pi优先当前节点/最近可见祖先。筛选或折叠后保留可见选择，否则回落网关选择。
    // lastSyncedView：最近一次成功同步时的 view（失败回滚用，见下方 treeError effect）。
    var lastSyncedView by remember(sessionId) { mutableStateOf(GatewayTreeView()) }
    LaunchedEffect(ownedTree) {
        if (ownedTree == null) return@LaunchedEffect
        lastSyncedView = treeScreenState.view
        val selected = treeScreenState.selectedNodeId?.takeIf { id -> nodes.any { it.id == id } }
            ?: stateHolder.treeSelectedId?.takeIf { id -> nodes.any { it.id == id } }
            ?: nodes.lastOrNull()?.id
        // 浏览选中只在新树里已消失时才回落到网关选择；用户在看的行不被重拉抢走。
        if (treeScreenState.selectedNodeId == null || nodes.none { it.id == treeScreenState.selectedNodeId }) {
            treeScreenState.selectedNodeId = selected
        }
        val index = nodes.indexOfFirst { it.id == selected }
        if (index >= 0 && selected != treeScreenState.lastPositionedId) {
            treeScreenState.vertical.scrollToItem((index - 4).coerceAtLeast(0))
            treeScreenState.lastPositionedId = selected
        }
    }
    // 复制结果：文本必须在主线程写系统剪贴板，因此在组合里消费发布态。
    LaunchedEffect(stateHolder.treeCopyOutcome) {
        val outcome = stateHolder.treeCopyOutcome ?: return@LaunchedEffect
        treeScreenState.actionFeedback = when (outcome) {
            is AndroidSharedStateHolder.TreeCopyOutcome.Copied -> {
                clipboard.setText(AnnotatedString(outcome.text))
                "已复制全文 ${outcome.text.length} 字"
            }
            is AndroidSharedStateHolder.TreeCopyOutcome.Failed -> outcome.message
        }
        stateHolder.consumeTreeCopyOutcome()
    }
    // 标签结果：成功即关闭输入态（树由网关重拉后刷新），失败保留输入让用户改。
    LaunchedEffect(stateHolder.treeLabelOutcome) {
        val outcome = stateHolder.treeLabelOutcome ?: return@LaunchedEffect
        when (outcome) {
            is AndroidSharedStateHolder.TreeLabelOutcome.Saved -> {
                // 请求期间可能已改看另一行；旧结果不能关闭新动作卡或丢掉新标签草稿。
                if (treeScreenState.actionEntryId == outcome.entryId) {
                    treeScreenState.labelMode = false
                    treeScreenState.actionEntryId = null
                }
                treeScreenState.actionFeedback =
                    if (outcome.label == null) "已清除标签" else "已保存标签「${outcome.label}」"
            }
            is AndroidSharedStateHolder.TreeLabelOutcome.Failed -> treeScreenState.actionFeedback = outcome.message
        }
        stateHolder.consumeTreeLabelOutcome()
    }
    LaunchedEffect(treeScreenState.actionFeedback) {
        if (treeScreenState.actionFeedback != null) {
            delay(2400)
            treeScreenState.actionFeedback = null
        }
    }
    // 请求失败回滚乐观 view（复审A minor 修复）：药丸高亮/空态文案声称的筛选
    // 必须与实际渲染的数据一致——sessionTree 只在成功应答后更新，失败时把 view
    // 退回上次成功快照（搜索防抖的 query 一并退回，避免退回值又触发重试循环）。
    LaunchedEffect(stateHolder.treeError) {
        if (stateHolder.treeError != null && treeScreenState.view != lastSyncedView) {
            treeScreenState.view = lastSyncedView
            query = lastSyncedView.search
        }
    }

    val actionNode = treeScreenState.actionEntryId?.let { id -> nodes.firstOrNull { it.id == id } }
    var lastActionNode by remember(sessionId) { mutableStateOf<GatewayTreeNode?>(null) }
    SideEffect { if (actionNode != null) lastActionNode = actionNode }
    // 动作卡实测高度（复审A minor 修复）：固定 150dp 装不下 foldHint/大字号下的
    // 卡高，末行仍被遮；按实测（含 navigationBarsPadding）+卡底距+余量预留。
    var actionSheetHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    // 动作卡打开时接管返回键：先关卡（不跳转），再按一次才回对话页。
    // enabled 用 actionNode（复审A minor 修复）：树重拉使目标行消失时卡面已不渲染，
    // 若仍看 actionEntryId 会吞掉一次返回键且界面无任何变化。
    BackHandler(enabled = actionNode != null) {
        treeScreenState.actionEntryId = null
        treeScreenState.labelMode = false
    }
    // 原型 pick() 会把选中行滚到可视区中部：卡面遮住底部时，选中行必须能滚到卡上方。
    // 只以开关为键（不带 nodes）：树重拉不得把已滚走的用户抽回选中行。
    LaunchedEffect(treeScreenState.actionEntryId) {
        val id = treeScreenState.actionEntryId ?: return@LaunchedEffect
        val index = nodes.indexOfFirst { it.id == id }
        if (index >= 0) treeScreenState.vertical.scrollToItem((index - 3).coerceAtLeast(0))
    }

    BoxWithConstraints(modifier.fillMaxSize().imePadding().background(colors.bg).testTag("finecat.tree")) {
        val viewportWidth = maxWidth
        // 等宽前缀与正文随横滑一起移动；按字符的最大字宽估算，中文也不会被压缩成几字。
        val contentWidth = remember(nodes, viewportWidth, fontScale) {
            maxOf(viewportWidth, (32 + (nodes.indices.maxOfOrNull { index ->
                val node = nodes[index]
                displayPrefixes[index].length + (node.label?.length ?: 0) + treeRowText(node).length + 14
            } ?: 0).coerceAtMost(4000) * 13 * fontScale).dp)
        }
        Column(Modifier.fillMaxSize()) {
            // 副行（原型 `.subtitle`）：目录基名 · 可见行数 · 最新条目时间。
            // 行数是**当前筛选/折叠下网关返回的可见行数**，不是会话总条数——网关
            // 不给总数，不用旧快照或估算冒充；时间取可见行里最大的 ISO 时间戳
            //（pi 恒 toISOString() 同形状，字典序最大即时间最新；非 ISO 串
            //  treeClockLabel 已返空串不画）。
            val subtitle = buildList {
                projectBasename(sessionCwd).takeIf(String::isNotEmpty)?.let { add(it) }
                if (ownedTree != null) add("${nodes.size} 行")
                nodes.mapNotNull { it.timestamp }.maxOrNull()?.let { treeClockLabel(it) }
                    ?.takeIf(String::isNotEmpty)?.let { add(it) }
            }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = type.xs,
                    color = colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                        .testTag("finecat.treeSubtitle"),
                )
            }
            if (fullView) {
                FcSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "搜索树",
                    fieldTag = "finecat.treeSearch",
                    onSearch = {
                        if (query != treeScreenState.view.search || stateHolder.treeError != null) {
                            load(treeScreenState.view.copy(search = query, foldedIds = emptyList()))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("finecat.treeSearchPill"),
                )
                // 五枚筛选药丸（Pi Ctrl+D/T/U/L/A）：横向可滚，窄屏不挤成两行。
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.fillMaxWidth().testTag("finecat.treeFilters"),
                ) {
                    items(TREE_FILTER_PILLS.size, key = { TREE_FILTER_PILLS[it].first }) { index ->
                        val (filter, label) = TREE_FILTER_PILLS[index]
                        TreeFilterPill(
                            label = label,
                            selected = view.filter == filter,
                            enabled = !loading,
                            onClick = {
                                // Pi 的 toggle：T/U/L/A 再点一次回 default；default 本身不回弹。
                                val next = if (view.filter == filter && filter != "default") "default" else filter
                                if (next != view.filter) load(view.copy(filter = next))
                            },
                            modifier = Modifier.testTag("finecat.treeFilter.$filter"),
                        )
                    }
                }
            } else {
                Text("基础会话树；升级网关后可搜索、筛选和折叠", style = type.xs, color = colors.text3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            stateHolder.treeError?.let { error ->
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = colors.err, style = type.sm, modifier = Modifier.weight(1f))
                    FcTextButton(text = "重试", enabled = !loading, onClick = { load(treeScreenState.view) })
                }
            }
            if (loading) Text("加载中…", style = type.xs, color = colors.text3, modifier = Modifier.padding(horizontal = 20.dp))
            // 动作回执（复制/标签）渲染在树页级而不是动作卡内（复审D blocker）：
            // 标签保存成功会同帧收起卡片，卡内反馈会被一起移出组合——用户永远
            // 看不到“已保存/已清除”；放在列表上方对两条路径都常驻可见。
            treeScreenState.actionFeedback?.let { feedback ->
                Text(
                    feedback,
                    style = type.xs,
                    color = colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                        .testTag("finecat.treeActionFeedback"),
                )
            }
            if (ownedTree == null && !loading && stateHolder.treeError == null) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("尚未载入会话树", style = type.sm, color = colors.text3, modifier = Modifier.weight(1f))
                    FcTextButton(text = "重试", onClick = { load(treeScreenState.view) })
                }
            }
            if (ownedTree != null && nodes.isEmpty() && !loading) {
                Text(
                    when {
                        view.search.isNotBlank() -> "没有匹配的节点"
                        view.filter != "default" -> "该筛选下没有条目"
                        else -> "会话还没有记录"
                    },
                    style = type.sm, color = colors.text3, modifier = Modifier.padding(20.dp)
                        .testTag("finecat.treeEmpty"),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f).horizontalScroll(treeScreenState.horizontal)) {
                LazyColumn(
                    state = treeScreenState.vertical,
                    modifier = Modifier.width(contentWidth).fillMaxHeight().testTag("finecat.treeContent"),
                    // 动作卡浮在底部不占布局（原型 .tree 的 116px 底 padding）：预留按
                    // 实测卡高（含 inset）+12dp 卡底距+8dp 余量，末行才能滚到卡上方。
                    contentPadding = PaddingValues(
                        // 原型树正文从 12px 起；行内还有 4dp，列表只留 8dp。
                        start = 8.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = if (actionNode != null && actionSheetHeightPx > 0) {
                            with(density) { actionSheetHeightPx.toDp() } + 20.dp
                        } else {
                            6.dp
                        },
                    ),
                ) {
                    items(nodes.size, key = { nodes[it].id }) { index ->
                        val node = nodes[index]
                        TreeRow(
                            node = node,
                            displayPrefix = displayPrefixes[index],
                            isLeaf = node.id == leafId,
                            selected = node.id == treeScreenState.selectedNodeId,
                            searchHit = view.search.isNotBlank() && treeRowMatchesSearch(node, view.search),
                            // 点行只浏览选中并打开动作卡，不再立即切分支（Pi：光标行 +
                            // Ctrl+X/Shift+L/Enter 三个动作分开）。
                            onClick = {
                                treeScreenState.selectedNodeId = node.id
                                treeScreenState.actionEntryId = node.id
                                treeScreenState.labelMode = false
                                treeScreenState.labelDraft = node.label.orEmpty()
                                treeScreenState.actionFeedback = null
                            },
                            canFold = fullView && node.foldable && !loading,
                            onFold = {
                                val ids = view.foldedIds
                                load(view.copy(foldedIds = if (node.id in ids) ids - node.id else ids + node.id))
                            },
                        )
                    }
                }
            }
        }

        // 保留退出阶段的内容，使关闭也向下滑出；标签输入时由树页统一避让IME。
        AnimatedVisibility(
            visible = actionNode != null,
            enter = slideInVertically(tween(motion.sheet, easing = motion.baseEasing)) { it },
            exit = slideOutVertically(tween(motion.sheet, easing = motion.baseEasing)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shownNode = actionNode ?: lastActionNode
            if (shownNode != null) {
            val interactive = actionNode?.id == shownNode.id
            TreeActionSheet(
                node = shownNode,
                isLeaf = shownNode.id == leafId,
                actionsSupported = actionsSupported,
                copyInFlight = !interactive || stateHolder.treeCopyInFlight,
                labelInFlight = !interactive || stateHolder.treeLabelInFlight,
                state = treeScreenState,
                onCopy = { if (interactive) stateHolder.copyTreeEntry(shownNode.id) },
                onSaveLabel = { if (interactive) stateHolder.setTreeEntryLabel(shownNode.id, treeScreenState.labelDraft) },
                onJump = {
                    // 只有明确的“跳到此处”才走既有 switchBranch 路径（TREE04 的
                    // 最近 user 回填、手稿/附件/队列保护都在那条链上，未改动）。
                    if (interactive) {
                        treeScreenState.actionEntryId = null
                        treeScreenState.labelMode = false
                        stateHolder.switchBranchTo(shownNode.id)
                    }
                },
                onDismiss = {
                    // 取消只关卡：浏览选中保留，绝不触发跳转。
                    treeScreenState.actionEntryId = null
                    treeScreenState.labelMode = false
                },
                modifier = Modifier.onSizeChanged { size ->
                    actionSheetHeightPx = size.height
                },
            )
            }
        }
    }
}

/** 筛选药丸（原型 `.modes button`）：选中=ink 实底，未选=白底细边。 */
@Composable
private fun TreeFilterPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val pillSelected = selected
    Box(
        modifier
            .height(TREE_PILL_HEIGHT)
            .clip(CircleShape)
            .background(if (selected) colors.ink else colors.surface)
            .border(1.dp, if (selected) colors.ink else colors.line, CircleShape)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics {
                contentDescription = label
                // 选中态对 TalkBack/设备断言可见：请求失败回滚后高亮必须跟着退回。
                this.selected = pillSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = type.xs,
            color = if (selected) colors.onInk else colors.text2,
            maxLines = 1,
        )
    }
}

/**
 * 一行树（原型 `.l`）：等宽、单行、分支连接线由网关前缀给出。
 * 层次：`[折叠] [前缀+活动标记] [[标签]] [角色标记][正文（目录弱化/尾部保留）] [active leaf 标记]`。
 * active leaf 是深色实心行（原型 `.l.cur`），与浏览选中（浅底）分开。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    node: GatewayTreeNode,
    displayPrefix: String,
    isLeaf: Boolean,
    selected: Boolean,
    searchHit: Boolean,
    onClick: () -> Unit,
    canFold: Boolean,
    onFold: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val rowSelected = selected
    val rowStyle = type.xs.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = TREE_ROW_FONT_SIZE,
        lineHeight = TREE_ROW_LINE_HEIGHT,
    )
    // 深色行上所有前景换成 onInk；压缩条目按原型用紫色（roleAi 在两套主题里都是紫）。
    val isCompaction = node.type == "compaction"
    val body = treeRowText(node)
    val roleLabel = treeRowRoleLabel(body, node.role)
    val background = when {
        isLeaf -> colors.ink
        selected -> colors.surface3
        searchHit -> colors.brandSoft
        else -> Color.Transparent
    }
    val dimColor = if (isLeaf) colors.onInk.copy(alpha = 0.55f) else colors.text3
    val tagColor = when {
        isLeaf -> colors.onInk.copy(alpha = 0.82f)
        isCompaction -> colors.roleAi
        else -> colors.text3
    }
    val bodyColor = when {
        isLeaf -> colors.onInk.copy(alpha = 0.82f)
        isCompaction -> colors.roleAi
        else -> colors.text2
    }
    val roleColor = when {
        isLeaf -> colors.onInk
        isCompaction -> colors.roleAi
        else -> colors.text
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = TREE_ROW_MIN_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(
                if (searchHit && !isLeaf) {
                    Modifier.border(1.dp, colors.brandLine, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            // 长按与前缀标记都折叠/展开该分支。
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = if (canFold) onFold else null,
            )
            // 浏览选中对 TalkBack 可见（与深色 active leaf 行是两个状态）。
            .semantics { this.selected = rowSelected }
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("finecat.treeNode.${node.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 连接线从所有行同一个位置起，不另留 32dp 空列。
        // 网关给可折叠根的展开态 prefix 可能为空；只为这类缺标记行补真实折叠按钮。
        val foldMarker = if (node.foldable && node.prefix.none { it == '⊞' || it == '⊟' }) {
            if (node.folded) "⊞ " else "⊟ "
        } else {
            ""
        }
        Box(
            Modifier.heightIn(min = TREE_ROW_MIN_HEIGHT).then(
                if (node.foldable) {
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = canFold, role = Role.Button, onClick = onFold)
                        .semantics { contentDescription = if (node.folded) "展开分支" else "折叠分支" }
                        .testTag("finecat.treeFold.${node.id}")
                } else {
                    Modifier
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                displayPrefix + foldMarker,
                style = rowStyle,
                color = dimColor,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.testTag("finecat.treePrefix.${node.id}"),
            )
        }
        // 标记仍在正文行首；放在线之后，避免把当前行的连接线整体推右而断开。
        if (isLeaf) {
            Text(
                "← 当前",
                style = rowStyle.copy(fontSize = 11.5.sp),
                color = colors.onInk.copy(alpha = 0.75f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 6.dp).testTag("finecat.treeLeafMark.${node.id}"),
            )
        }
        node.label?.takeIf(String::isNotEmpty)?.let {
            Text(
                "[$it]",
                style = rowStyle,
                color = tagColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 4.dp).testTag("finecat.treeLabel.${node.id}"),
            )
        }
        // 正文：user/assistant 角色前缀用主题主文字色加粗（网关 displayText 已含 `user: `
        // 前缀，客户端不再加自己的标签，避免双重前缀）；工具/元数据行按原型
        // 弱化目录、保留尾部。
        val isMessageText = node.type == "message" && (node.role == "user" || node.role == "assistant")
        Text(
            text = buildAnnotatedString {
                val remainder = if (roleLabel != null) {
                    withStyle(SpanStyle(color = roleColor, fontWeight = FontWeight.SemiBold)) { append(roleLabel) }
                    body.substring(roleLabel.length)
                } else {
                    body
                }
                if (isMessageText) {
                    withStyle(SpanStyle(color = bodyColor)) { append(remainder) }
                } else {
                    val parts = treeRowPathParts(remainder)
                    withStyle(SpanStyle(color = tagColor)) { append(parts.before) }
                    if (parts.hasDimDirectory) {
                        withStyle(SpanStyle(color = dimColor)) { append(parts.dimDirectory) }
                    }
                    withStyle(SpanStyle(color = bodyColor)) { append(parts.tail) }
                }
            },
            style = rowStyle,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag("finecat.treeText.${node.id}"),
        )
    }
}

/**
 * 动作卡（原型 `.sheet`）：浏览选中后弹出，四个动作互不混淆——
 * 复制=整条 entry 全文（Pi Ctrl+X）、标签=新增/修改/清空（Pi Shift+L）、
 * 跳到此处=唯一的导航出口（既有 switchBranch）、✕/取消=只关卡不跳转。
 */
@Composable
private fun TreeActionSheet(
    node: GatewayTreeNode,
    isLeaf: Boolean,
    actionsSupported: Boolean,
    copyInFlight: Boolean,
    labelInFlight: Boolean,
    state: FineCatTreeScreenState,
    onCopy: () -> Unit,
    onSaveLabel: () -> Unit,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("finecat.treeActionSheet"),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                treeRowText(node),
                style = type.xs.copy(fontFamily = FontFamily.Monospace),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(end = 32.dp)
                    .testTag("finecat.treeActionTitle"),
            )
            Box(
                Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "关闭动作卡" }
                    .testTag("finecat.treeActionClose"),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", style = type.body, color = colors.text2)
            }
        }
        if (state.labelMode) {
            // Shift+L 等价物：输入框预填当前标签，留空保存即清除（Pi 语义）。
            Row(
                Modifier.fillMaxWidth().padding(top = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = state.labelDraft,
                    onValueChange = { next -> state.labelDraft = next.take(24) },
                    enabled = !labelInFlight,
                    singleLine = true,
                    textStyle = type.sm.copy(color = colors.text, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveLabel() }),
                    modifier = Modifier.weight(1f).height(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(colors.surface)
                        .border(1.5.dp, colors.brand, RoundedCornerShape(11.dp))
                        .padding(horizontal = 11.dp)
                        .testTag("finecat.treeLabelInput")
                        .semantics { contentDescription = "标签名" },
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (state.labelDraft.isEmpty()) {
                                Text("标签名（留空即清除）", style = type.sm, color = colors.text3, maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
                SheetButton(text = "取消", primary = false, enabled = !labelInFlight, onClick = { state.labelMode = false })
                SheetButton(
                    text = if (labelInFlight) "保存中…" else "保存",
                    primary = true,
                    enabled = !labelInFlight,
                    onClick = onSaveLabel,
                    tag = "finecat.treeLabelSave",
                )
            }
        } else {
            if (!actionsSupported) {
                // 旧网关：不留假按钮，明说哪些动作要升级网关才可用。
                Text(
                    "升级网关后可复制条目全文与写标签",
                    style = type.xs,
                    color = colors.text3,
                    modifier = Modifier.padding(top = 5.dp).testTag("finecat.treeActionUpgradeHint"),
                )
            }
            Text(
                if (isLeaf) {
                    "这一行就是当前 leaf；跳转会改变对话历史，不会回滚工作区文件。"
                } else {
                    "移动 leaf 只改变对话历史，不会回滚工作区文件。"
                },
                style = type.xs2,
                color = colors.text3,
                modifier = Modifier.padding(top = 5.dp).testTag("finecat.treeActionHint"),
            )
            if (node.foldable) {
                // 折叠不做第二个假按钮：行首 −/+ 与长按行都是真实网关折叠，
                // 这里只告知当前分支的折叠状态与入口。
                Text(
                    if (node.folded) "该分支已折叠：行首 + 或长按行展开" else "该分支可折叠：行首 − 或长按行",
                    style = type.xs2,
                    color = colors.text3,
                    modifier = Modifier.padding(top = 2.dp).testTag("finecat.treeActionFoldHint"),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (actionsSupported) {
                    SheetButton(
                        text = if (copyInFlight) "复制中…" else "复制",
                        primary = false,
                        enabled = !copyInFlight,
                        onClick = onCopy,
                        tag = "finecat.treeActionCopy",
                    )
                    SheetButton(
                        text = "标签",
                        primary = false,
                        enabled = !copyInFlight && !labelInFlight,
                        onClick = {
                            state.labelDraft = node.label.orEmpty()
                            state.labelMode = true
                            state.actionFeedback = null
                        },
                        tag = "finecat.treeActionLabel",
                    )
                }
                SheetButton(
                    text = if (isLeaf) "已在此处" else "跳到此处",
                    primary = true,
                    enabled = true,
                    onClick = onJump,
                    tag = "finecat.treeActionJump",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 动作卡按钮（原型 `.ghost` / `.pillbtn`）：42dp 高，达标触控尺寸。 */
@Composable
private fun SheetButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Box(
        modifier
            .height(42.dp)
            .clip(CircleShape)
            .background(if (primary) colors.ink else colors.surface)
            .border(1.dp, if (primary) colors.ink else colors.line2, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = type.sm,
            color = when {
                !enabled -> colors.text3
                primary -> colors.onInk
                else -> colors.text
            },
            maxLines = 1,
        )
    }
}
