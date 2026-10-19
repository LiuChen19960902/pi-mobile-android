package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.finecat.components.FcRowDivider
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

/** 成功浏览的位置即选择；确认会等在途浏览返回，回根清空选择。 */
@Composable
internal fun FineCatDirectorySheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sessionMode: Boolean = false,
    initialPath: String? = null,
    continueBlockedReason: String? = null,
    onContinue: ((String) -> Unit)? = null,
    onNewSession: ((String) -> Unit)? = null,
    onSelectHome: ((String) -> Unit)? = null,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val current = stateHolder.directoryPath
    var requestedPath by remember { mutableStateOf(initialPath ?: stateHolder.fineCatNewSessionDirectoryPath ?: "/") }
    var confirmWhenReady by remember { mutableStateOf(false) }
    val loading = stateHolder.directoryIsLoading
    val error = stateHolder.directoryError
    fun finishSelection() {
        if (!sessionMode && current != null && !loading && error == null && onSelectHome != null) onSelectHome(current)
        else onDismiss()
    }
    LaunchedEffect(current, loading, error, confirmWhenReady) {
        if (!sessionMode && onSelectHome == null && current != null && !loading && error == null) {
            stateHolder.selectFineCatDirectory(current)
        }
        if (confirmWhenReady && !loading) {
            confirmWhenReady = false
            if (error == null) finishSelection()
        }
    }
    fun browse(path: String) {
        if (stateHolder.directoryIsLoading) return
        requestedPath = path
        stateHolder.browseDirectories(path)
    }
    FcSheet(
        visible = true,
        onDismiss = { finishSelection() },
        onOutsideClick = { if (loading) confirmWhenReady = true else finishSelection() },
        title = "工作目录",
        showDone = true,
        doneText = if (sessionMode) "关闭" else "完成",
        onDone = { if (loading) confirmWhenReady = true else finishSelection() },
        modifier = modifier.testTag("finecat.dirSheet"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FineCatIcons.Folder, null, tint = colors.text3, modifier = Modifier.size(14.dp))
            Text(
                text = current ?: requestedPath,
                style = type.xs.copy(fontFamily = FontFamily.Monospace),
                color = colors.text3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("finecat.dirPath"),
            )
        }
        if (current == "/" && !loading) {
            Text(if (sessionMode) "选择一个目录" else "先选一个目录，新会话会建在它里面", style = type.xs, color = colors.text3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        error?.let {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, style = type.xs, color = colors.err, modifier = Modifier.weight(1f))
                FcTextButton(text = "重试", onClick = { browse(requestedPath) })
            }
        }
        if (loading) {
            Text("目录加载中…", style = type.xs, color = colors.text3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        ) {
            if (current != null && current != "/") {
                val parent = stateHolder.directoryCrumbs.dropLast(1).lastOrNull()?.path
                    ?: current.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                DirectoryRow(
                    name = "上一级", parent = true, enabled = !loading,
                    onClick = { browse(parent) },
                    modifier = Modifier.testTag("finecat.dirUp"),
                )
            }
            val entries = stateHolder.directoryEntries.filter { it.name != ".git" }
            entries.forEachIndexed { index, item ->
                if (index > 0 || (current != null && current != "/")) FcRowDivider()
                val count = stateHolder.snapshot.sessions.count { session ->
                    session.cwd == item.path || session.cwd?.startsWith(item.path + "/") == true
                }
                DirectoryRow(
                    name = item.name, count = count, enabled = !loading,
                    onClick = { browse(item.path) },
                    modifier = Modifier.testTag("finecat.dirItem.${item.name}"),
                )
            }
            if (current != null && !loading && error == null && entries.isEmpty()) {
                Text("当前目录没有子目录", style = type.xs, color = colors.text3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        if (sessionMode) {
            continueBlockedReason?.let { Text(it, style = type.xs, color = colors.warn,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            val canPick = current != null && current != "/" && !loading && error == null
            if (onContinue != null) {
                androidx.compose.material3.Button(
                    onClick = { current?.let(onContinue) },
                    enabled = canPick && current != initialPath && continueBlockedReason == null,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.onInk),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 46.dp).testTag("finecat.dirContinue"),
                ) { Text("带当前对话继续", style = type.sm) }
            }
            FcTextButton("在此新建会话", enabled = canPick, onClick = { current?.let { onNewSession?.invoke(it) } }, muted = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("finecat.dirNewSession"))
        }

    }
}

@Composable
private fun DirectoryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    parent: Boolean = false,
    count: Int = 0,
    enabled: Boolean = true,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (parent) {
            Text("..", style = type.body.copy(fontFamily = FontFamily.Monospace), color = colors.text,
                modifier = Modifier.size(24.dp))
        } else {
            Icon(FineCatIcons.Folder, null, tint = colors.text, modifier = Modifier.size(24.dp))
        }
        Text(name, style = type.body.copy(fontFamily = if (parent) FontFamily.Default else FontFamily.Monospace),
            color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count 会话", style = type.sm, color = colors.text3)
        if (!parent) Icon(FineCatIcons.Right, null, tint = colors.text3, modifier = Modifier.size(18.dp))
    }
}
