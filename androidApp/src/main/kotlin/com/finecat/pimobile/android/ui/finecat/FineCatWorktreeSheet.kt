package com.finecat.pimobile.android.ui.finecat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.finecat.pimobile.android.ui.finecat.conversation.FcWorking
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.WorktreeMode
import com.finecat.pimobile.android.WorktreePhase
import com.finecat.pimobile.android.ui.relativeTime
import java.util.TimeZone
import com.finecat.pimobile.android.ui.finecat.components.*
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

internal fun shortWorktreePath(path: String, paths: List<String>): String {
    val mine = path.trimEnd('/').split('/').filter(String::isNotEmpty)
    if (mine.isEmpty()) return "/"
    var depth = 1
    while (depth < mine.size && paths.any { it != path && it.trimEnd('/').split('/').takeLast(depth) == mine.takeLast(depth) }) depth++
    return (if (depth < mine.size) "…/" else "/") + mine.takeLast(depth).joinToString("/")
}

@Composable
internal fun FineCatWorktreeSheet(stateHolder: AndroidSharedStateHolder, onOtherDirectory: () -> Unit) {
    val ui = stateHolder.worktreeUi
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val busy = ui.phase in setOf(WorktreePhase.CREATING, WorktreePhase.SWITCHING)
    val blocked = stateHolder.worktreeBlockReason()
    var query by remember(ui.sourceCwd) { mutableStateOf("") }
    var branch by remember(ui.sourceCwd) { mutableStateOf(ui.createBranch) }
    var base by remember(ui.sourceCwd) { mutableStateOf(ui.createBase) }
    var path by remember(ui.sourceCwd) { mutableStateOf(ui.createPath) }
    var pathEdited by remember(ui.sourceCwd) { mutableStateOf(ui.createPath.isNotEmpty()) }
    var details by remember(ui.sourceCwd) { mutableStateOf(false) }
    LaunchedEffect(ui.createdPath) { if (ui.createdPath != null) query = "" }
    fun suggestedPath(name: String): String = ui.listing?.suggestDir.orEmpty().trimEnd('/') + "/" +
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "worktree" }
    val effectivePath = if (pathEdited) path else suggestedPath(branch)
    val title = when (ui.phase) {
        WorktreePhase.FORM, WorktreePhase.CREATING, WorktreePhase.CREATE_FAILED -> "新建 worktree"
        WorktreePhase.SESSIONS -> "打开已有会话"
        else -> "工作区"
    }
    var sessionQuery by remember(ui.targetPath) { mutableStateOf("") }
    FcSheet(visible = true, onDismiss = stateHolder::closeWorktree, title = title,
        showDone = !busy, doneText = if (ui.phase in setOf(WorktreePhase.FORM, WorktreePhase.SESSIONS)) "返回" else "关闭",
        onDone = { when (ui.phase) {
            WorktreePhase.FORM -> stateHolder.worktreeForm(false)
            WorktreePhase.SESSIONS -> stateHolder.worktreeSessionListBack()
            else -> stateHolder.closeWorktree()
        } },
        maxHeightFraction = 0.86f, modifier = Modifier.testTag("finecat.worktreeSheet")) {
        BackHandler(enabled = ui.phase == WorktreePhase.SESSIONS) { stateHolder.worktreeSessionListBack() }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            when (ui.phase) {
                WorktreePhase.LIST -> {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val modes = if (ui.sourceSessionId == null) listOf(WorktreeMode.NEW, WorktreeMode.EXISTING)
                            else listOf(WorktreeMode.CONTINUE, WorktreeMode.EXISTING, WorktreeMode.NEW)
                        modes.forEach { mode ->
                            val isSelected = ui.mode == mode
                            val interaction = remember { MutableInteractionSource() }
                            val focused by interaction.collectIsFocusedAsState()
                            val label = when (mode) {
                                WorktreeMode.CONTINUE -> "Fork 当前会话"
                                WorktreeMode.EXISTING -> "打开已有会话"
                                WorktreeMode.NEW -> "新建空白会话"
                            }
                            Box(Modifier.heightIn(min = 48.dp)
                                .then(if (focused) Modifier.border(1.dp, colors.ink, RoundedCornerShape(4.dp)) else Modifier)
                                .selectable(selected = isSelected, interactionSource = interaction,
                                    indication = null, role = Role.Tab) { stateHolder.worktreeMode(mode) }
                                .testTag("finecat.worktreeMode.$mode"),
                                contentAlignment = Alignment.Center) {
                                Text(label, style = type.sm, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) colors.ink else colors.text3, maxLines = 1,
                                    modifier = Modifier.drawBehind {
                                        if (isSelected) drawRoundRect(colors.ink,
                                            topLeft = Offset(0f, size.height - 4.dp.toPx()),
                                            size = Size(size.width, 2.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                                    }.padding(vertical = 5.dp))
                            }
                        }
                    }
                    if (ui.continueConversation) Text("在目标工作区创建完整对话副本，原会话保留。",
                        style = type.xs, color = colors.text3, modifier = Modifier.padding(bottom = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FcSearchField(query, { query = it }, "搜索分支或路径", "finecat.worktreeSearch", Modifier.weight(1f))
                        FcIconButton({ stateHolder.refreshWorktreeLocation(ui.sourceCwd, ui.sourceSessionId) }, FineCatIcons.Refresh,
                            "刷新工作区", enabled = !ui.loading, modifier = Modifier.size(48.dp))
                    }
                    if (ui.loading) Text("正在读取工作区…", color = colors.text3, style = type.xs)
                    if (ui.continueConversation && blocked != null) Text(blocked, color = colors.warn, style = type.xs,
                        modifier = Modifier.padding(vertical = 8.dp))
                    ui.error?.let { Text(it, color = colors.err, style = type.xs, modifier = Modifier.padding(vertical = 8.dp)) }
                    val all = ui.listing?.worktrees.orEmpty()
                    val rows = all.filter { (it.branch.orEmpty() + " " + it.commit + " " + it.path).contains(query, ignoreCase = true) }
                    if (ui.createdPath != null) Text("已创建，仍在当前工作区", style = type.xs, color = colors.text3)
                    val current = all.filter { ui.sourceCwd == it.path || ui.sourceCwd.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        if (!ui.loading && rows.isEmpty()) Text(if (ui.listing?.repoRoot == null) "当前目录不是 Git 仓库" else "没有匹配的工作区",
                            color = colors.text3, style = type.sm, modifier = Modifier.padding(vertical = 24.dp))
                        if (rows.isEmpty() && query.isNotEmpty()) FcTextButton("清除搜索", onClick = { query = "" })
                        rows.forEachIndexed { index, item ->
                            if (index > 0) FcRowDivider()
                            val isCurrent = item.path == current?.path
                            val enabled = !item.missing && !(ui.continueConversation && ui.sourceSessionId != null && blocked != null) &&
                                !(isCurrent && ui.mode == WorktreeMode.CONTINUE)
                            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { keyboard?.hide(); focus.clearFocus(force = true); stateHolder.chooseWorktree(item.path) }
                                .semantics { contentDescription = "${item.branch ?: item.commit}，${item.path}" }
                                .heightIn(min = 56.dp).padding(vertical = 12.dp).testTag("finecat.worktreeRow.${item.path}"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(FineCatIcons.Branch, null, tint = if (enabled || isCurrent) colors.text else colors.text3, modifier = Modifier.size(24.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.branch ?: item.commit, style = type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(shortWorktreePath(item.path, all.map { it.path }), style = type.xs.copy(fontFamily = FontFamily.Monospace),
                                        color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (isCurrent || item.main || item.missing) WorktreeIdentity(isCurrent, item.main, item.missing)
                                if (enabled) Icon(FineCatIcons.Right, null, tint = colors.text3, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (ui.mode != WorktreeMode.EXISTING && all.size == 1 && all.first().main) Text("这个仓库只有主工作区，可以新建一个再带对话过去。", style = type.xs, color = colors.text3)
                    if (ui.listing?.repoRoot != null) WorktreeButton("＋ 新建 worktree", true, { branch = ""; base = "HEAD"; path = ""; pathEdited = false; details = false; stateHolder.worktreeForm(true) })
                    FcTextButton("选择其他目录…", onClick = onOtherDirectory, muted = true,
                        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp))
                }
                WorktreePhase.SESSIONS -> {
                    Text(ui.targetPath.orEmpty(), style = type.xs.copy(fontFamily = FontFamily.Monospace),
                        color = colors.text3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FcSearchField(sessionQuery, { sessionQuery = it }, "搜索会话", "finecat.worktreeSessionSearch", Modifier.weight(1f))
                        FcIconButton(stateHolder::refreshWorktreeSessions, FineCatIcons.Refresh, "刷新会话", enabled = !ui.sessionsLoading,
                            modifier = Modifier.size(48.dp))
                    }
                    val sessions = stateHolder.worktreeSessions.filter { it.title.contains(sessionQuery.trim(), ignoreCase = true) }
                    if (ui.sessionsLoading) Text("正在读取会话…", style = type.xs, color = colors.text3, modifier = Modifier.padding(vertical = 12.dp))
                    (ui.sessionsError ?: ui.error)?.let {
                        Text(it, style = type.xs, color = colors.err, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    if (ui.sessionsError != null) FcTextButton("重试", onClick = stateHolder::refreshWorktreeSessions)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(sessions, key = { it.id }) { session ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !ui.sessionsLoading && ui.sessionsError == null, role = Role.Button) {
                                keyboard?.hide(); focus.clearFocus(force = true); stateHolder.openWorktreeSession(session.id)
                            }.heightIn(min = 64.dp).padding(vertical = 12.dp).testTag("finecat.worktreeSession.${session.id}"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(session.title, style = type.sm.copy(fontWeight = FontWeight.SemiBold), color = colors.text,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val ago = relativeTime(session.lastActivityEpochSeconds, System.currentTimeMillis(), TimeZone.getDefault())
                                    Text(listOfNotNull("进行中".takeIf { session.isRunning }, ago.takeIf(String::isNotBlank)).joinToString(" · "),
                                        style = type.xs, color = colors.text3)
                                }
                                if (session.id == ui.sourceSessionId) WorktreeIdentity(isCurrent = true)
                                Icon(FineCatIcons.Right, null, tint = colors.text3, modifier = Modifier.size(18.dp))
                            }
                            FcRowDivider()
                        }
                    }
                    if (!ui.sessionsLoading && ui.sessionsError == null && sessions.isEmpty()) {
                        Text(if (sessionQuery.isBlank()) "该工作区暂无会话" else "没有匹配的会话", style = type.sm,
                            color = colors.text3, modifier = Modifier.padding(vertical = 24.dp))
                        if (sessionQuery.isNotBlank()) FcTextButton("清除搜索", onClick = { sessionQuery = "" })
                        else WorktreeButton("在此新建会话", true, { ui.targetPath?.let { stateHolder.newSessionInWorkspace(it) } })
                    }
                }
                WorktreePhase.FORM -> {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (ui.sourceSessionId != null && ui.continueConversation) Text("在目标工作区创建完整对话副本，原会话保留。",
                            style = type.xs, color = colors.text3)
                        WorktreeField("分支名", branch, { branch = it }, "finecat.worktreeBranch")
                        if (ui.listing?.dirty == true) Text("新 worktree 不会带上当前目录里未提交的改动。", style = type.xs, color = colors.text3)
                        FcTextButton("基点与目标路径 ${if (details) "⌃" else "⌄"}", onClick = { details = !details }, muted = true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        if (details) {
                            WorktreeField("基点", base, { base = it }, "finecat.worktreeBase")
                            Text("默认当前工作区的 HEAD", style = type.xs, color = colors.text3)
                            WorktreeField("目标路径", effectivePath, { pathEdited = true; path = it }, "finecat.worktreePath", lines = 2)
                        }
                        val reason = blocked.takeIf { ui.sourceSessionId != null && ui.continueConversation }
                        (reason ?: ui.error)?.let { Text(it, style = type.xs, color = colors.warn) }
                        val canCreate = branch.isNotBlank() && base.isNotBlank() && effectivePath.startsWith('/')
                        WorktreeButton(if (ui.sourceSessionId == null) "创建并使用" else if (ui.continueConversation) "创建并 Fork" else "创建并新建空白会话",
                            canCreate && reason == null, { stateHolder.createWorktree(branch, base, effectivePath, false) })
                        FcTextButton("仅创建", enabled = canCreate, onClick = { stateHolder.createWorktree(branch, base, effectivePath, true) },
                            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 40.dp), muted = true)
                    }
                }
                else -> {
                    val heading = when (ui.phase) {
                        WorktreePhase.CREATING -> "正在创建…"
                        WorktreePhase.SWITCHING -> if (ui.action in setOf("select", "open", "resume")) "正在确认工作区…" else "正在 Fork 会话…"
                        WorktreePhase.CONFIRM -> "正在确认操作结果…"
                        WorktreePhase.CREATE_FAILED -> "创建失败"
                        WorktreePhase.TARGET_GONE -> "目标目录已失效"
                        WorktreePhase.TRUST -> if (ui.action == "resume") "信任此目录？" else "信任此项目并继续？"
                        else -> if (ui.createdPath != null) "已创建，切换未完成" else "切换失败"
                    }
                    Text(heading, style = type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.text, modifier = Modifier.padding(vertical = 16.dp))
                    Text(ui.targetPath.orEmpty(), style = type.xs.copy(fontFamily = FontFamily.Monospace), color = colors.text3)
                    ui.error?.let { Text(it, style = type.xs, color = colors.text3, modifier = Modifier.padding(vertical = 12.dp)) }
                    when (ui.phase) {
                        WorktreePhase.CREATING, WorktreePhase.SWITCHING -> FcWorking(if (ui.phase == WorktreePhase.CREATING) "创建目标工作区" else if (ui.action in setOf("select", "open", "resume")) "确认项目信任" else "在目标工作区创建会话副本", Modifier.padding(vertical = 16.dp))
                        WorktreePhase.CONFIRM -> WorktreeButton("重新查询", true, stateHolder::recheckWorktree)
                        WorktreePhase.CREATE_FAILED -> {
                            Text("当前工作区和对话都没变。", style = type.xs, color = colors.text3)
                            WorktreeButton("重试创建", branch.isNotBlank(), { stateHolder.createWorktree(branch, base, effectivePath, !ui.continueAfterCreate) })
                            FcTextButton("返回修改", onClick = { stateHolder.worktreeForm(true) }, modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                        WorktreePhase.TRUST -> {
                            Text("允许加载此目录的项目配置、Skills 和扩展。", style = type.xs, color = colors.text3, modifier = Modifier.padding(vertical = 12.dp))
                            WorktreeButton(if (ui.action == "resume") "信任" else "信任此项目并继续",
                                ui.action in setOf("select", "open", "resume") || blocked == null, { stateHolder.confirmWorktreeTrust() })
                            FcTextButton("取消", onClick = stateHolder::closeWorktree, modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                        else -> {
                            Text("当前对话仍在原工作区，草稿保留。", style = type.xs, color = colors.text3, modifier = Modifier.padding(vertical = 12.dp))
                            if (ui.action == "continue") blocked?.let { Text(it, style = type.xs, color = colors.warn) }
                            when (ui.action) {
                                "continue" -> WorktreeButton("重试 Fork", blocked == null, { stateHolder.retryWorktreeContinue() })
                                "select" -> WorktreeButton("重试选择", true, { ui.targetPath?.let { stateHolder.chooseWorktree(it) } })
                            }
                            FcTextButton("返回列表", onClick = { stateHolder.worktreeForm(false); stateHolder.refreshWorktreeLocation(ui.sourceCwd, ui.sourceSessionId) },
                                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 40.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorktreeIdentity(isCurrent: Boolean, main: Boolean = false, missing: Boolean = false) {
    val colors = LocalFineCatColors.current
    val labels = listOfNotNull("当前".takeIf { isCurrent }, "主工作区".takeIf { main }, "目录不存在".takeIf { missing })
    Text(buildAnnotatedString {
        labels.forEachIndexed { index, label ->
            if (index > 0) append(" · ")
            withStyle(SpanStyle(color = when (label) { "当前" -> colors.ink; "目录不存在" -> colors.err; else -> colors.text3 },
                fontWeight = if (label == "当前") FontWeight.SemiBold else FontWeight.Normal)) { append(label) }
        }
    }, style = LocalFineCatType.current.xs, color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun WorktreeButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalFineCatColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Button(onClick = { keyboard?.hide(); focus.clearFocus(force = true); onClick() }, enabled = enabled, shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.onInk,
            disabledContainerColor = colors.ink.copy(alpha = 0.35f), disabledContentColor = colors.onInk),
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(top = 8.dp)) {
        Text(text, style = LocalFineCatType.current.sm.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun WorktreeField(label: String, value: String, onChange: (String) -> Unit, tag: String, lines: Int = 1) {
    val colors = LocalFineCatColors.current
    Text(label, style = LocalFineCatType.current.xs, color = colors.text3)
    BasicTextField(value, onChange, textStyle = LocalFineCatType.current.body.copy(color = colors.text,
        fontFamily = if (lines > 1) FontFamily.Monospace else FontFamily.Default), minLines = lines, maxLines = lines,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp).testTag(tag))
}
