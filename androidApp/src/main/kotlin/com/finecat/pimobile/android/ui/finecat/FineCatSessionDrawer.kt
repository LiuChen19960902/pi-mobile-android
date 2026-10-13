package com.finecat.pimobile.android.ui.finecat

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.buildHiddenRestoreLists
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FcPopover
import com.finecat.pimobile.android.ui.finecat.components.FcSearchField
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatElevation
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.conversation.projectBasename
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.android.ui.relativeTime
import com.finecat.pimobile.shared.domain.SessionSummary
import java.util.TimeZone
import kotlinx.coroutines.delay

/** 目录只筛选，置顶与日期负责分组。抽屉没有常驻底栏，隐藏入口随列表滚动。 */
@Composable
fun FineCatSessionDrawer(
    stateHolder: AndroidSharedStateHolder,
    open: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTheme: () -> Unit = {},
    onNewSession: () -> Unit = {},
    onRename: (SessionSummary) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val clipboard = LocalClipboardManager.current
    // 这些状态在早退之前声明，关闭/重开不丢。目录使用完整cwd，null表示全部。
    var query by rememberSaveable { mutableStateOf("") }
    var selectedDirectory by rememberSaveable { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var collapsedGroupKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val tabsScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var menuId by remember { mutableStateOf<String?>(null) }
    var menuAnchor by remember { mutableStateOf(IntRect.Zero) }
    LaunchedEffect(open) { if (!open) menuId = null }
    if (!open) return
    BackHandler { if (menuId != null) menuId = null else onDismiss() }

    val snapshot = stateHolder.snapshot
    val hiddenIds = stateHolder.hiddenSessionIds
    val hiddenDirs = stateHolder.hiddenDirectoryPaths
    val pinnedIds = stateHolder.pinnedSessionIds
    val normalizedQuery = query.trim()
    val searchResults = remember(snapshot.sessions, normalizedQuery) { drawerSearchSessions(snapshot.sessions, normalizedQuery) }
    val directories = remember(snapshot.sessions, searchResults) { drawerProjectGroups(snapshot.sessions, searchResults) }
    val visible = remember(searchResults, hiddenIds, hiddenDirs, showHidden, selectedDirectory) {
        searchResults.filter { session ->
            (selectedDirectory == null || session.cwd.orEmpty() == selectedDirectory) &&
                (showHidden || (session.id !in hiddenIds && session.cwd.orEmpty() !in hiddenDirs))
        }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val zone = remember { TimeZone.getDefault() }
    val groups = remember(visible, pinnedIds, now, zone) { drawerDayGroups(visible, now, zone, pinnedIds) }
    val hidden = remember(hiddenIds, hiddenDirs, snapshot.sessions) {
        buildHiddenRestoreLists(hiddenIds, snapshot.sessions, hiddenDirs)
    }
    val catalogIds = remember(snapshot.sessions) { snapshot.sessions.mapTo(mutableSetOf()) { it.id } }
    val orphanHidden = hidden.sessionEntries.filter { it.id !in catalogIds }
    val hiddenCount = hidden.sessionEntries.size + hidden.directoryPaths.size
    LaunchedEffect(hiddenCount) { if (hiddenCount == 0) showHidden = false }
    LaunchedEffect(normalizedQuery) { if (normalizedQuery.isNotEmpty()) tabsScroll.scrollTo(0) }
    LaunchedEffect(listState.isScrollInProgress, normalizedQuery, selectedDirectory) { menuId = null }
    LaunchedEffect(visible, menuId) { if (menuId != null && visible.none { it.id == menuId }) menuId = null }

    Box(modifier.fillMaxSize().testTag("finecat.drawer")) {
        Box(Modifier.fillMaxSize().background(colors.overlay)
            .clickable(role = Role.Button, onClick = onDismiss)
            .semantics { contentDescription = "关闭抽屉" })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = minOf(maxWidth * 0.86f, 316.dp)
            Column(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(panelWidth)
                .pointerInput(Unit) { detectTapGestures { } }
                .shadow(FineCatElevation.Floating, RoundedCornerShape(0.dp), clip = false,
                    ambientColor = colors.shadow, spotColor = colors.shadow)
                .background(colors.bg).statusBarsPadding()
                .drawBehind { drawRect(colors.line, Offset(size.width - 1.dp.toPx(), 0f),
                    androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
                .testTag("finecat.drawerPanel")) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    .testTag("finecat.drawerTop"), verticalAlignment = Alignment.CenterVertically) {
                    FcSearchField(query, {
                        query = it
                        selectedDirectory = null
                    }, "搜索会话或目录", "finecat.drawerSearch",
                        Modifier.weight(1f).testTag("finecat.drawerSearchPill"))
                    FcIconButton(onClick = { onDismiss(); onOpenSettings() }, icon = FineCatIcons.Sliders,
                        contentDescription = "设置", iconSize = 20.dp,
                        modifier = Modifier.testTag("finecat.drawerSettings"))
                    FcIconButton(onClick = onOpenTheme, icon = if (colors.isDark) FineCatIcons.Sun else FineCatIcons.Moon,
                        contentDescription = if (colors.isDark) "切换到浅色" else "切换到深色", iconSize = 20.dp,
                        modifier = Modifier.testTag("finecat.drawerTheme"))
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(colors.ink, RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClick = onNewSession)
                        .testTag("finecat.drawerNew"), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(FineCatIcons.Plus, null, tint = colors.onInk, modifier = Modifier.size(17.dp))
                        Text("新建会话", color = colors.onInk, style = type.sm.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                // 用padding对齐首项，滚动状态独立于列表和筛选。标签触控至少48dp，不互相重叠。
                Row(Modifier.fillMaxWidth().horizontalScroll(tabsScroll).padding(horizontal = 12.dp)
                    .testTag("finecat.drawerDirectories"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DirectoryTab("全部", null, selectedDirectory == null,
                        onClick = { selectedDirectory = null }, tag = "finecat.drawerDirectory.all")
                    directories.forEach { directory ->
                        val cwd = directory.cwd.orEmpty()
                        DirectoryTab(directory.label, cwd, selectedDirectory == cwd,
                            onClick = { selectedDirectory = cwd },
                            tag = "finecat.drawerDirectory.$cwd")
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding().navigationBarsPadding()
                    .testTag("finecat.drawerList"), state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    groups.forEachIndexed { index, group ->
                        val collapsed = group.key in collapsedGroupKeys
                        item(key = "group-${group.key}") {
                            GroupHeader(group.label, group.key, collapsed, index == 0) {
                                menuId = null
                                collapsedGroupKeys = if (collapsed) collapsedGroupKeys - group.key else collapsedGroupKeys + group.key
                            }
                        }
                        if (!collapsed) items(group.sessions, key = { it.id }) { session ->
                            SessionRow(session, session.id == snapshot.selectedSessionId,
                                session.id in hiddenIds || session.cwd.orEmpty() in hiddenDirs,
                                now, zone,
                                onClick = { stateHolder.selectSession(session.id); onDismiss() },
                                onRename = { onRename(session) },
                                onMenu = { anchor ->
                                    menuId = if (menuId == session.id) null else session.id
                                    menuAnchor = anchor
                                })
                        }
                    }
                    if (groups.isEmpty()) item(key = "empty") {
                        Text(when {
                            normalizedQuery.isNotEmpty() -> "没有匹配「$query」的会话"
                            selectedDirectory in hiddenDirs && !showHidden -> "这个目录的会话已隐藏"
                            selectedDirectory != null -> "当前目录没有可显示的会话"
                            hiddenCount > 0 -> "当前没有可显示的会话"
                            else -> "没有会话"
                        }, style = type.xs, color = colors.text3, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
                                .testTag("finecat.drawerEmpty"))
                    }
                    // 入口不由目录筛选生成，任何目录/搜索/折叠下均不会丢失。
                    if (hiddenCount > 0) item(key = "hidden-tail") {
                        FcTextButton(if (showHidden) "只看未隐藏的会话" else "含已隐藏的 $hiddenCount 条",
                            onClick = { showHidden = !showHidden; menuId = null }, muted = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                                .testTag("finecat.drawerHiddenToggle"))
                    }
                    if (showHidden) {
                        if (orphanHidden.isNotEmpty() || hidden.directoryPaths.isNotEmpty()) item(key = "hidden-header") {
                            Text("已隐藏", style = type.xs2, color = colors.text3,
                                modifier = Modifier.padding(start = 20.dp, top = 8.dp).testTag("finecat.drawerHiddenHeader"))
                        }
                        items(orphanHidden, key = { "orphan-${it.id}" }) { entry ->
                            HiddenRow(entry.displayTitle, { stateHolder.restoreHiddenSession(entry.id) },
                                "finecat.drawerHiddenSession.${entry.id}")
                        }
                        items(hidden.directoryPaths, key = { "hidden-dir-$it" }) { path ->
                            HiddenRow(path, { stateHolder.restoreHiddenDirectory(path) }, "finecat.drawerHiddenDir.$path")
                        }
                        if (hiddenCount > 1) item(key = "restore-all") {
                            HiddenRow("全部恢复", stateHolder::restoreAllHiddenSessions, "finecat.drawerRestoreAll")
                        }
                    }
                }
            }
        }
        menuId?.let { id ->
            val session = visible.firstOrNull { it.id == id }
            if (session != null) FcPopover(true, { menuId = null }, menuAnchor, focusable = false,
                elevation = FineCatElevation.Card, modifier = Modifier.testTag("finecat.drawerMenu.$id")) {
                MenuRow("重命名", FineCatIcons.Edit, "finecat.drawerRename.$id") { menuId = null; onRename(session) }
                MenuRow("复制sessionId", FineCatIcons.Copy, "finecat.drawerCopySessionId.$id") {
                    clipboard.setText(AnnotatedString(session.id))
                    menuId = null
                    stateHolder.notifyUser("已复制 sessionId")
                }
                MenuRow(if (id in pinnedIds) "取消置顶" else "置顶",
                    if (id in pinnedIds) FineCatIcons.ArrowDown else FineCatIcons.ArrowUp,
                    "finecat.drawerPin.$id", checked = id in pinnedIds) {
                    menuId = null; stateHolder.togglePinnedSession(id)
                }
                MenuRow(if (id in hiddenIds) "恢复会话" else "隐藏会话", FineCatIcons.Trash,
                    "finecat.drawerHide.$id") {
                    menuId = null
                    if (id in hiddenIds) stateHolder.restoreHiddenSession(id) else stateHolder.hideSession(id)
                }
                if (session.cwd.orEmpty() in hiddenDirs) MenuRow("恢复目录", FineCatIcons.Folder,
                    "finecat.drawerRestoreDirectory.$id") {
                    menuId = null; session.cwd?.let(stateHolder::restoreHiddenDirectory)
                }
            }
        }
    }
}

@Composable
private fun DirectoryTab(label: String, cwd: String?, isSelected: Boolean, onClick: () -> Unit,
                         tag: String) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Tab, onClick = onClick)
        .semantics { selected = isSelected; contentDescription = cwd?.takeIf(String::isNotEmpty) ?: label }
        .testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, color = if (isSelected) colors.text else colors.text3,
            style = type.xs2.copy(fontFamily = if (cwd == null) null else type.mono,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
            modifier = Modifier.background(if (isSelected) colors.surface2 else Color.Transparent,
                RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun GroupHeader(label: String, key: String, collapsed: Boolean, first: Boolean, onToggle: () -> Unit) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle)
        .semantics { stateDescription = if (collapsed) "已折叠" else "已展开" }
        .padding(start = 20.dp, end = 20.dp, top = if (first) 8.dp else 24.dp)
        .testTag("finecat.drawerGroupHeader.$key")) {
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (key.startsWith("day-")) {
                Icon(FineCatIcons.Folder, null, tint = colors.text3,
                    modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Text(label, style = type.xs2.copy(fontWeight = FontWeight.Medium), color = colors.text3,
                modifier = Modifier.weight(1f))
            Icon(if (collapsed) FineCatIcons.Down else FineCatIcons.Up, null, tint = colors.text3,
                modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, isCurrent: Boolean, isHidden: Boolean,
                       now: Long, zone: TimeZone, onClick: () -> Unit, onRename: () -> Unit,
                       onMenu: (IntRect) -> Unit) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val project = projectBasename(session.cwd)
    val ago = remember(session.lastActivityEpochSeconds, now, zone) { relativeTime(session.lastActivityEpochSeconds, now, zone) }
    val status = when {
        session.isRunning -> "进行中"
        session.hasFailure -> "失败"
        session.hasUnread -> "待查看"
        else -> null
    }
    val context = LocalContext.current
    val animateRunning = remember(context, session.isRunning) {
        session.isRunning && Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    var anchor by remember(session.id) { mutableStateOf(IntRect.Zero) }
    val pulse = if (animateRunning) {
        val transition = rememberInfiniteTransition(label = "drawer-running")
        val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
        progress
    } else 0f
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(if (isCurrent) colors.surface2 else Color.Transparent)
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onRename, onLongClickLabel = "重命名")
        .semantics { selected = isCurrent; stateDescription = status.orEmpty() }
        .alpha(if (isHidden) 0.66f else 1f)
        .drawBehind {
            if (session.isRunning) clipRect {
                val left = if (animateRunning) (size.width * 1.2f) * pulse - size.width * 0.2f else size.width * 0.4f
                drawRect(colors.text.copy(alpha = 0.35f), Offset(left, size.height - 2.dp.toPx()),
                    androidx.compose.ui.geometry.Size(size.width * 0.2f, 2.dp.toPx()))
            }
        }.testTag("finecat.drawerSession.${session.id}")) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 82.dp, top = 9.dp, bottom = 9.dp)) {
            Text(session.title, style = type.sm.copy(fontWeight = FontWeight.Bold),
                color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().testTag("finecat.drawerSub.${session.id}"), verticalAlignment = Alignment.CenterVertically) {
                if (project.isNotEmpty()) Text(project, style = type.xs2.copy(fontFamily = type.mono),
                    color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (status != null) {
                    if (project.isNotEmpty()) Text(" · ", style = type.xs2, color = colors.text3)
                    Text(status, style = type.xs2.copy(fontWeight = if (session.hasUnread && !session.hasFailure && !session.isRunning)
                        FontWeight.Normal else FontWeight(550)), color = if (session.hasUnread && !session.hasFailure && !session.isRunning)
                        colors.text3 else colors.ink, maxLines = 1)
                }
                if (ago.isNotEmpty()) {
                    Text(if (project.isNotEmpty() || status != null) " · $ago" else ago,
                        style = type.xs2, color = colors.text3, maxLines = 1,
                        modifier = Modifier.testTag("finecat.drawerTime.${session.id}"))
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(48.dp)
            .onGloballyPositioned { anchor = it.boundsInWindow().roundToIntRect() }
            .clickable(role = Role.Button) { onMenu(anchor) }
            .semantics { contentDescription = "更多操作：${session.title}" }
            .testTag("finecat.drawerAction.${session.id}")) {
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status != null) Canvas(Modifier.size(14.dp).testTag("finecat.drawerMark.${session.id}")) {
                    when {
                        session.isRunning -> {
                            drawCircle(colors.ink, radius = 3.5.dp.toPx())
                            drawCircle(colors.ink.copy(alpha = 0.55f * (1f - pulse)), radius = (3.5f + 3f * pulse).dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        }
                        session.hasFailure -> {
                            drawCircle(colors.ink, radius = 6.dp.toPx())
                            val d = 2.dp.toPx()
                            drawLine(colors.onInk, center - Offset(d, d), center + Offset(d, d), 1.5.dp.toPx())
                            drawLine(colors.onInk, center + Offset(-d, d), center + Offset(d, -d), 1.5.dp.toPx())
                        }
                        else -> drawCircle(colors.text3, radius = 4.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    }
                }
                Icon(FineCatIcons.More, null, tint = colors.text3.copy(alpha = if (isCurrent) 1f else 0.72f),
                    modifier = Modifier.size(16.dp).testTag("finecat.drawerMore.${session.id}"))
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, tag: String, checked: Boolean = false, onClick: () -> Unit) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(Modifier.width(190.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = colors.text3, modifier = Modifier.size(16.dp))
        Text(label, style = type.xs, color = colors.text, modifier = Modifier.weight(1f))
        if (checked) Icon(FineCatIcons.Check, null, tint = colors.text, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun HiddenRow(title: String, onAction: () -> Unit, tag: String) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = type.sm, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(0.66f))
        FcTextButton("恢复", onAction, modifier = Modifier.testTag("$tag.action"))
    }
}
