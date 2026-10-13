package com.finecat.pimobile.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.domain.SessionGroup
import com.finecat.pimobile.shared.domain.SessionGrouping
import com.finecat.pimobile.shared.domain.SessionSummary
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayRuntimeState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 闭合态下允许触发「右滑开抽屉」的左边缘宽度。 */
private val DrawerEdgeSwipeWidth = 20.dp

/**
 * 抽屉目标宽度：屏宽约 82%，上限 360dp；分屏更窄时也给主页面留出可见区域。
 * M3 默认只 cap 360dp，在 360dp 宽的手机上抽屉会铺满整屏、主页面完全看不见；
 * 显式定宽才能保证窄手机也露出右侧主页面。
 */
internal fun piDrawerWidth(screenWidth: Dp): Dp =
    (screenWidth * 0.82f).coerceAtMost(360.dp)

/**
 * 会话抽屉：旧的整屏 workspace 首页搬到这里，形态对齐参考稿——左侧独立不透
 * 内容的面板，顶部小圆角（12dp）搜索是第一元素，中部纯文字会话列表，底部固定
 * 「设置 / 状态点菜单」。连接区不再常驻顶部：收进底部状态点菜单（展开后才有
 * 连接/断开、扫码与手动配对），设置始终固定可达。
 */
@Composable
internal fun SessionDrawerContent(
    stateHolder: AndroidSharedStateHolder,
    onOpenSession: (String) -> Unit,
    onSettings: () -> Unit,
    onScanPairing: () -> Unit,
    onManualPairing: () -> Unit,
    onRename: (SessionSummary) -> Unit,
    onShowHiddenSessions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 已折叠的目录组（键 = cwd；rememberSaveable 对 Set 的保存不稳，用 List）。
    var collapsedGroups by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 底部状态点菜单的展开态：连接/断开、扫码与手动配对收在这里。
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    val dividerColor = piWorkspaceForeground().copy(alpha = 0.10f)
    val drawerColor = if (LocalPiDark.current) Color(0xFF101010) else Color(0xFFF7F8FA)

    // 本地黑名单只在展示层过滤（假删除），不动宿主机的 session 文件；目录级
    // 黑名单整组隐藏。分组按过滤后的列表算：搜索时组内计数与组的存在性跟着走。
    val sessions = remember(
        stateHolder.snapshot.sessions, searchQuery, stateHolder.hiddenSessionIds,
        stateHolder.hiddenDirectoryPaths
    ) {
        val all = stateHolder.snapshot.sessions.filterNot {
            it.id in stateHolder.hiddenSessionIds ||
                it.cwd.orEmpty() in stateHolder.hiddenDirectoryPaths
        }
        if (searchQuery.isBlank()) {
            all
        } else {
            all.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.id.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val sessionGroups = remember(sessions) { SessionGrouping.group(sessions) }
    val hiddenCount = stateHolder.hiddenSessionIds.size + stateHolder.hiddenDirectoryPaths.size

    // 键盘占用先从约束扣除（imePadding），并显式定宽：约束 maxWidth 即屏宽，
    // 窄手机也要露出右侧主页面，不能只依赖 M3 默认的 360dp cap。
    BoxWithConstraints(modifier = modifier.imePadding()) {
        val drawerWidth = piDrawerWidth(maxWidth)
        // 抽屉使用独立不透明的中性底色，下层内容
        // （点阵背景、巨大 logo、主页文字）不再透上来；玻璃质感只留给主页
        // 输入框这类小控件，不强行透整个抽屉。
        ModalDrawerSheet(
            // 抽屉靠左侧贴边，只收右侧圆角；M3 默认 extraEndRound 在 20dp 以上，
            // 和面板里其他控件的 12dp 圆角不同档，显式定小。
            drawerShape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp),
            modifier = Modifier.width(drawerWidth).testTag("session-drawer"),
            drawerContainerColor = drawerColor,
            drawerContentColor = piWorkspaceForeground()
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                SessionSearch(searchQuery) { searchQuery = it }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "最近会话",
                        color = piWorkspaceForeground().copy(alpha = 0.72f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    if (hiddenCount > 0) {
                        Text(
                            text = "已隐藏 $hiddenCount 项",
                            color = piWorkspaceForeground().copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onShowHiddenSessions)
                                .heightIn(min = 36.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("drawer-hidden-sessions-entry")
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.6.dp, color = dividerColor)
            // 会话列表整体是一个 item：内部 Column 与 key() 保持旧首页原状，
            // 左滑/复制的状态寿命不被 lazy 回收改变。
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("drawer-session-list")
            ) {
                item(key = "drawer-sessions") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                        if (sessions.isEmpty()) {
                            EmptySessions(
                                searchActive = searchQuery.isNotBlank(),
                                hiddenCount = hiddenCount,
                                onShowHidden = onShowHiddenSessions
                            )
                        } else {
                            SessionList(
                                sessionGroups,
                                collapsedKeys = collapsedGroups,
                                onToggleGroup = { key ->
                                    collapsedGroups =
                                        if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
                                },
                                onOpen = onOpenSession,
                                onRename = onRename,
                                onHide = { stateHolder.hideSession(it) },
                                onHideGroup = { stateHolder.hideDirectory(it) }
                            )
                        }
                    }
                }
                item(key = "drawer-list-footer") { Spacer(Modifier.height(16.dp)) }
            }
            HorizontalDivider(thickness = 0.6.dp, color = dividerColor)
            // 更多是独立菜单，不向上挤列表；横屏或键盘出现时也保留固定底栏。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DrawerBottomEntry(
                    title = "设置", onClick = onSettings, testTag = "drawer-settings",
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.onGloballyPositioned {
                    menuAnchorBounds = IntRect(it.positionInWindow().round(), it.size)
                }) {
                    // 底栏入口从「⋯ + 箭头」改为单个网关状态圆点：绿=已连接，其余状态一律橙。
                    // 触控仍守 48dp，读屏把网关状态和「打开菜单」一起报出来（图标本身不携带文字）。
                    val connection = stateHolder.gatewayState.connection
                    val dotColor = drawerStatusDotColor(connection)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { moreOpen = !moreOpen }
                            .testTag("drawer-more-menu")
                            .semantics {
                                contentDescription =
                                    "网关${gatewayConnectionTitle(connection)}，打开菜单"
                            }
                    ) {
                        Box(
                            Modifier.size(11.dp)
                                .shadow(5.dp, CircleShape, ambientColor = dotColor.copy(alpha = 0.65f))
                                .background(dotColor, CircleShape)
                        )
                    }
                    AnchoredPopupMenu(
                        expanded = moreOpen,
                        onDismissRequest = { moreOpen = false },
                        anchorBounds = menuAnchorBounds,
                        width = 260.dp
                    ) {
                        DrawerMorePanel(
                            state = stateHolder.gatewayState,
                            endpoint = stateHolder.endpoint,
                            onScanPairing = { moreOpen = false; onScanPairing() },
                            onManualPairing = { moreOpen = false; onManualPairing() },
                            onConnectToggle = {
                                moreOpen = false
                                if (stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED) {
                                    stateHolder.disconnect()
                                } else {
                                    stateHolder.clearPlatformError()
                                    stateHolder.connect()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 抽屉底部「更多」菜单内容：未连接时也能访问连接与配对入口。 */
@Composable
private fun DrawerMorePanel(
    state: GatewayRuntimeState,
    endpoint: String,
    onScanPairing: () -> Unit,
    onManualPairing: () -> Unit,
    onConnectToggle: () -> Unit
) {
    val onSurface = piWorkspaceForeground()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionDot(state)
            Spacer(Modifier.width(8.dp))
            Text(
                gatewayConnectionTitle(state.connection),
                color = onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (endpoint.isNotBlank()) {
            Text(
                endpoint,
                color = onSurface.copy(alpha = 0.42f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
            )
        }
        DropdownMenuItem(
            text = { Text(if (state.connection == GatewayConnectionState.CONNECTED) "断开" else "连接", fontSize = 14.sp) },
            onClick = onConnectToggle,
            modifier = Modifier.heightIn(min = 48.dp).testTag("drawer-connect-toggle")
        )
        DropdownMenuItem(
            text = { Text("扫码配对", fontSize = 14.sp) },
            onClick = onScanPairing,
            modifier = Modifier.heightIn(min = 48.dp).testTag("drawer-scan-pairing")
        )
        DropdownMenuItem(
            text = { Text("手动配对", fontSize = 14.sp) },
            onClick = onManualPairing,
            modifier = Modifier.heightIn(min = 48.dp).testTag("drawer-manual-pairing")
        )
    }
}

/** 抽屉底栏状态圆点的颜色：只区分「已连接/其余」，绿/橙两档（读屏另有文字）。 */
internal fun drawerStatusDotColor(state: GatewayConnectionState): Color =
    if (state == GatewayConnectionState.CONNECTED) PiColors.Success else PiColors.Amber

/** 抽屉底部的固定入口：整行可点、高度守 48dp 触控线。 */
@Composable
private fun DrawerBottomEntry(
    title: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val onSurface = piWorkspaceForeground()
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        // 右侧箭头删掉：这一行本来就是底栏入口，不需要重复的导航提示。
    }
}

/**
 * 抽屉闭合态的窄左边缘右滑触发器。
 *
 * 开抽屉不用 ModalNavigationDrawer 的 gesturesEnabled：它给整页内容装横向拖动
 * 处理器，会抢掉 HorizontalPager 与会话树页横向平移的手势。这里只在「起始点位于
 * 左边缘 20dp 内」且「位移以水平向右为主」时把手势判给打开抽屉，其余触控（点按、
 * 纵向滚动、行内左滑隐藏）一律不消费、原样让路。抽屉打开后的滑关仍交回 M3
 * （见 PiProductApp 的 gesturesEnabled = isOpen）。
 */
@Composable
internal fun Modifier.edgeSwipeToOpenDrawer(
    drawerState: DrawerState,
    enabled: Boolean
): Modifier {
    val scope = rememberCoroutineScope()
    val edgePx = with(LocalDensity.current) { DrawerEdgeSwipeWidth.toPx() }
    return this then pointerInput(drawerState, enabled, edgePx, scope) {
        if (!enabled) return@pointerInput
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            // Initial pass 先看一眼落点：不在边缘带内就直接放弃，绝不干预子节点。
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edgePx) return@awaitEachGesture
            var totalDx = 0f
            var totalDy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                totalDx += change.position.x - change.previousPosition.x
                totalDy += change.position.y - change.previousPosition.y
                if (abs(totalDx) <= touchSlop) continue
                // 方向门禁：只认向右且以水平为主的边缘滑动，否则本次手势让路。
                if (totalDx <= 0f || abs(totalDx) <= abs(totalDy) * 1.3f) break
                change.consume()
                if (drawerState.currentValue == DrawerValue.Closed) {
                    scope.launch { drawerState.open() }
                }
                break
            }
        }
    }
}

@Composable
private fun ConnectionDot(state: GatewayRuntimeState) {
    val color = when (state.connection) {
        GatewayConnectionState.CONNECTED -> PiColors.Success
        GatewayConnectionState.FAILED -> Color.Red
        GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK -> PiColors.Amber
        else -> Color.Gray
    }
    Box(
        Modifier.size(8.dp)
            .shadow(5.dp, CircleShape, ambientColor = color.copy(alpha = 0.65f))
            .background(color, CircleShape)
    )
}

@Composable
private fun SessionSearch(query: String, onChange: (String) -> Unit) {
    val onSurface = piWorkspaceForeground()
    // 参考稿的顶部搜索：通栏但只留 12dp 圆角，和抽屉面板同一档位。
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp)
            .testTag("workspace-session-search"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(onSurface.copy(alpha = 0.68f))
        )
        BasicTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).testTag("workspace-session-search-input"),
            textStyle = TextStyle(color = onSurface.copy(alpha = 0.92f), fontSize = 14.sp),
            cursorBrush = SolidColor(onSurface),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索会话内容", color = onSurface.copy(alpha = 0.58f), fontSize = 14.sp)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun SessionList(
    groups: List<SessionGroup>,
    collapsedKeys: List<String>,
    onToggleGroup: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (SessionSummary) -> Unit,
    onHide: (String) -> Unit,
    onHideGroup: (String) -> Unit
) {
    Column(Modifier.background(Color.Transparent)) {
        // 不截断：父列可滚；截断会让第 13 条起永久不可见、搜索结果被误判“搜不到”。
        groups.forEachIndexed { index, group ->
            val key = group.cwd.orEmpty()
            val collapsed = key in collapsedKeys
            if (index > 0) Spacer(Modifier.height(6.dp))
            // 按 cwd 锚定组身份：SwipeToHideBox 内有可拖动状态，组列表重排/
            // 隐藏时按位置复用会把上滑出的位移带给下一组（同会话行 key 的理由）。
            key(key) {
                HideableGroupHeader(
                    group = group,
                    collapsed = collapsed,
                    onToggle = { onToggleGroup(key) },
                    onHide = { onHideGroup(key) }
                )
            }
            if (collapsed) return@forEachIndexed
            group.sessions.forEach { session ->
                // 按 id 锚定行身份：行内有 copied 状态，列表重排时不致把“已复制”漂到别的行。
                key(session.id) {
                    HideableSessionRow(
                        session = session,
                        onOpen = { onOpen(session.id) },
                        onRename = { onRename(session) },
                        onHide = { onHide(session.id) }
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 18.dp),
                    thickness = 0.5.dp,
                    // 抽屉底随主题（旧首页只有暗底），分隔线不能写死白色：浅色下会看不见。
                    color = piWorkspaceForeground().copy(alpha = 0.10f)
                )
            }
        }
    }
}

/**
 * 左滑露出「隐藏」按钮的手势容器（自写实现，替代 M3 SwipeToDismissBox）。
 *
 * 为什么不用 M3：它内部 draggable 只沿水平轴做手势归属，斜向（≈45°）拖动时
 * 水平分量先过 slop 就把手势抢走，造成"想滚动却触发隐藏"的误触（真机已复现，
 * 调高阈值治不了）。这里用 awaitEachGesture 自管手势，在第一次过 touch slop
 * 时做方向门禁：位移近水平（|dx| > 1.3|dy|）才接管拖拽，否则本次手势直接让
 * 给列表滚动、不再参与——快速翻动时累计出来的水平抖动因此碰不到行。
 *
 * 交互（滑动本身不隐藏）：左滑行随手指移动、渐显光晕与「隐藏」文案，松手后
 * 位移 ≥ stayPx 就停在原地（不吸附固定位置），否则回弹；隐藏的唯一触发是
 * 点击「隐藏」文案。停在原地时点行内容先收起行（不会同时打开会话），右拖也
 * 可直接拖回。offset 读数走 drawBehind / offset lambda / graphicsLayer，
 * 拖动只重绘不重组。
 */
@Composable
private fun SwipeToHideBox(onHide: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val glowSpanPx = with(density) { 160.dp.toPx() }
    // 停留阈值：松手位移达到它才停在原地；同时决定「隐藏」文案的显影进度。
    val stayPx = with(density) { 96.dp.toPx() }
    val glowColor = if (LocalPiDark.current) Color.White else PiColors.Ocean
    var widthPx by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestOnHide by rememberUpdatedState(onHide)
    // 停在原地（未隐藏）：此时点行内容应收起行，而不是打开会话。
    var restingOpen by remember { mutableStateOf(false) }
    // 隐藏动画进行中：忽略手势，避免拖动取消动画导致 onHide 丢失。
    var hiding by remember { mutableStateOf(false) }

    fun close() {
        restingOpen = false
        scope.launch { offset.animateTo(0f, tween(200)) }
    }

    // 松手结算：停在松手位置（不吸附），或回弹。放在 launch 里排在所有
    // snapTo 之后执行，读到的 offset 一定是最终位移。
    fun settle() {
        scope.launch {
            if (-offset.value >= stayPx) restingOpen = true else close()
        }
    }

    Box(
        Modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .drawBehind {
                val fraction = (-offset.value / glowSpanPx).coerceIn(0f, 1f) * 0.5f
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, glowColor.copy(alpha = fraction))
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx())
                )
            }
    ) {
        // 「隐藏」文案固定在行右端，行左移时被揭开；显影进度随位移在 draw 阶段
        // 读取（与光晕同源取色），拖动只重绘不重组。停稳后它才是按钮。
        Text(
            text = "隐藏",
            color = glowColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .then(
                    // 只有停稳后它才是按钮：闭合/回弹途中它被行压住，不参与命中。
                    if (restingOpen) Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            restingOpen = false
                            hiding = true
                            scope.launch {
                                offset.animateTo(-widthPx.coerceAtLeast(stayPx), tween(150))
                                latestOnHide()
                            }
                        }
                    else Modifier
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .graphicsLayer {
                    alpha = (-offset.value / stayPx).coerceIn(0f, 1f) * 0.9f
                }
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(stayPx, hiding) {
                    if (hiding) return@pointerInput
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDx = 0f
                        var totalDy = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // 本次位移已被更深的节点消费（行内自己的手势）：让路。
                            if (change.isConsumed) break
                            if (change.changedToUpIgnoreConsumed()) break
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            if (!dragging) {
                                totalDx += dx
                                totalDy += dy
                                if (max(abs(totalDx), abs(totalDy)) <= touchSlop) continue
                                // 方向门禁：近水平才接管；斜滑/纵向本次手势让给列表。
                                if (abs(totalDx) <= abs(totalDy) * 1.3f) break
                                dragging = true
                            }
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dx).coerceIn(-widthPx, 0f))
                            }
                        }
                        if (dragging) settle()
                    }
                }
        ) {
            content()
            // 停稳时点行内容先收起行：覆盖层在最上层、先消费 down，行内点击/
            // 长按不会与收起同时触发。
            if (restingOpen) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { close() } }
                )
            }
        }
    }
}

/** 组标题行的左滑隐藏（目录级假删除）：光晕/阈值与行级一致，黑名单存 cwd。 */
@Composable
private fun HideableGroupHeader(
    group: SessionGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onHide: () -> Unit
) {
    // 空 cwd（"未指定目录"组）不可组级隐藏：hideDirectory 对空键直接拒绝，
    // 若允许滑动会出现行滑出屏外却不消失的死态——干脆不启用左滑。
    if (group.cwd.isNullOrBlank()) {
        SessionGroupHeader(group, collapsed, onToggle)
    } else {
        SwipeToHideBox(onHide = onHide) {
            SessionGroupHeader(group, collapsed, onToggle)
        }
    }
}

/**
 * 左滑隐藏（假删除）：只写本地黑名单，不删宿主机的 session 文件。
 * 手势与光晕实现见 [SwipeToHideBox]（斜滑让路、阈值 min(45%,200dp)、速度判定）。
 */
@Composable
private fun HideableSessionRow(
    session: SessionSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onHide: () -> Unit
) {
    SwipeToHideBox(onHide = onHide) {
        SessionRow(session, onOpen = onOpen, onRename = onRename)
    }
}

/** 目录组标题：点击折叠/展开；折叠时仍显示会话数与“有会话在跑”指示。 */
@Composable
private fun SessionGroupHeader(group: SessionGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val onGlass = piWorkspaceForeground()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 7.dp)
            .testTag("workspace-session-group-${group.cwd.orEmpty()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = if (collapsed) "▸" else "▾",
            color = onGlass.copy(alpha = 0.55f),
            fontSize = 12.sp
        )
        Text(
            text = group.label,
            color = onGlass.copy(alpha = 0.72f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (group.sessions.any { it.isRunning }) {
            Box(Modifier.size(6.dp).background(PiColors.Success, CircleShape))
        }
        Text(
            text = group.sessions.size.toString(),
            color = onGlass.copy(alpha = 0.40f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onOpen: () -> Unit, onRename: () -> Unit) {
    val onGlass = piWorkspaceForeground()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Row(
        Modifier.fillMaxWidth()
            // 行高守 48dp 触控线（原来的紧凑行在抽屉里点击面积偏小）。
            .heightIn(min = 48.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onRename)
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("workspace-session-${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        val dot = if (session.isRunning) PiColors.Success else if (session.hasUnread) PiColors.Ocean else onGlass.copy(alpha = 0.35f)
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = session.title,
                color = onGlass,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            // 目录名已在组标题上，这里显示会话 ID 短形式 + 复制按钮（点整块复制完整 ID）。
            val idTint = if (copied) PiColors.Ocean else onGlass.copy(alpha = 0.42f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PiMobile", session.id))
                        copied = true
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("workspace-session-id-${session.id}")
            ) {
                Text(
                    text = if (copied) "已复制" else session.id.take(8),
                    color = idTint,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Spacer(Modifier.width(4.dp))
                Image(
                    painter = painterResource(
                        if (copied) R.drawable.ic_menu_check else R.drawable.ic_copy_message
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(if (copied) 11.dp else 12.dp),
                    colorFilter = ColorFilter.tint(idTint)
                )
            }
        }
        Text(
            if (session.isRunning) "运行中" else relativeTime(session.lastActivityEpochSeconds),
            color = if (session.isRunning) Color(0xFF68A0FF) else onGlass.copy(alpha = 0.48f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EmptySessions(
    searchActive: Boolean = false,
    hiddenCount: Int = 0,
    onShowHidden: () -> Unit = {}
) {
    val onGlass = piWorkspaceForeground()
    // 参考稿的列表是纯文字行：空态也去卡底，只在实色面板上留字。
    val card = Modifier.fillMaxWidth().padding(18.dp)
    when {
        // 搜索无结果时不能报“暂无已知会话”或“都已隐藏”：会话只是被搜索条件滤掉了。
        searchActive -> Text(
            "没有匹配的会话。",
            color = onGlass.copy(alpha = 0.5f),
            fontSize = 14.sp,
            modifier = card
        )
        // 全部隐藏时给恢复入口：否则用户只能靠标题行的小字入口，且文案会误导。
        hiddenCount > 0 -> Column(
            modifier = card,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("当前没有可显示的会话。", color = onGlass.copy(alpha = 0.5f), fontSize = 14.sp)
            Text(
                "查看已隐藏的 $hiddenCount 项",
                color = onGlass,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onShowHidden)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("workspace-empty-hidden-entry")
            )
        }
        else -> Text(
            "暂无已知会话。连接服务后创建第一个任务。",
            color = onGlass.copy(alpha = 0.5f),
            fontSize = 14.sp,
            modifier = card
        )
    }
}

/** 已隐藏会话的恢复入口：列表只读展示，宿主文件未动，随时可恢复。 */
@Composable
internal fun HiddenSessionsDialog(
    sessionEntries: List<HiddenSessionEntry>,
    directories: List<String>,
    onRestore: (String) -> Unit,
    onRestoreDirectory: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit
) {
    // 恢复完最后一条就没有可看的内容了，自动关闭，不留空弹窗。
    LaunchedEffect(sessionEntries.isEmpty() && directories.isEmpty()) {
        if (sessionEntries.isEmpty() && directories.isEmpty()) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // 独立窗口不跨窗口采样，用 strong 玻璃底保证可读。
        containerColor = piGlassTint(strong = true),
        title = { Text("已隐藏的会话") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "这些会话只在手机上隐藏，宿主机的 session 文件没有删除。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
                if (directories.isNotEmpty()) {
                    Text(
                        "目录",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    directories.forEach { directory ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = directory.substringAfterLast('/').ifBlank { directory },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onRestoreDirectory(directory) },
                                shape = RoundedCornerShape(6.dp)
                            ) { Text("恢复") }
                        }
                    }
                    HorizontalDivider()
                }
                // 只隐藏了目录时不出“会话”标题，避免空分区误导。
                if (sessionEntries.isNotEmpty()) {
                    Text(
                        "会话",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                sessionEntries.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = entry.displayTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = entry.id.take(8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        TextButton(
                            onClick = { onRestore(entry.id) },
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("恢复") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(6.dp)) { Text("关闭") }
        },
        dismissButton = if (sessionEntries.size + directories.size > 1) {
            {
                TextButton(onClick = onRestoreAll, shape = RoundedCornerShape(6.dp)) {
                    Text("全部恢复")
                }
            }
        } else {
            null
        }
    )
}
