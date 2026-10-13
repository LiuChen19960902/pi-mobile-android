package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.finecat.components.FcStatusTag
import com.finecat.pimobile.android.ui.finecat.components.FcSearchField
import com.finecat.pimobile.android.ui.finecat.components.FcTagStyle
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.protocol.GatewayTreeNode
import com.finecat.pimobile.android.ui.treeRowPrefix

/** 会话内保留滚动、过滤与折叠；实际树布局始终由网关的 Pi 投影提供。 */
class FineCatTreeScreenState {
    val horizontal = ScrollState(0)
    val vertical = androidx.compose.foundation.lazy.LazyListState()
    var selectedNodeId: String? by mutableStateOf(null)
    var lastPositionedId: String? = null
    var view by mutableStateOf(com.finecat.pimobile.shared.protocol.GatewayTreeView())
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
    val sessionId = stateHolder.snapshot.selectedSessionId
    val fullView = "tree-view-v1" in stateHolder.gatewayState.capabilities
    val ownedTree = stateHolder.sessionTree?.takeIf { it.first == sessionId }
    val nodes = ownedTree?.third.orEmpty()
    val loading = stateHolder.treeRequestInFlight
    var query by remember(sessionId) { mutableStateOf(treeScreenState.view.search) }
    fun load(view: com.finecat.pimobile.shared.protocol.GatewayTreeView) {
        if (stateHolder.treeRequestInFlight) return
        treeScreenState.view = view
        stateHolder.loadSessionTree(if (fullView) view else null)
    }
    LaunchedEffect(sessionId, fullView) {
        treeScreenState.view = treeScreenState.view.copy(filter = "default")
        androidx.compose.runtime.snapshotFlow { stateHolder.treeRequestInFlight }.first { !it }
        stateHolder.loadSessionTree(if (fullView) treeScreenState.view else null)
    }
    // 在途请求结束后提交最后一个词；新输入会取消前一次防抖等待。
    LaunchedEffect(sessionId, fullView, query) {
        if (!fullView || query == treeScreenState.view.search) return@LaunchedEffect
        delay(250)
        androidx.compose.runtime.snapshotFlow { stateHolder.treeRequestInFlight }.first { !it }
        if (query != treeScreenState.view.search) {
            load(treeScreenState.view.copy(search = query, foldedIds = emptyList()))
        }
    }
    val seenBranchTick = remember { stateHolder.branchSwitchedTick }
    LaunchedEffect(stateHolder.branchSwitchedTick) {
        if (stateHolder.branchSwitchedTick != seenBranchTick) onBack()
    }
    // Pi优先当前节点/最近可见祖先。筛选或折叠后保留可见选择，否则回落网关选择。
    LaunchedEffect(ownedTree) {
        if (ownedTree == null) return@LaunchedEffect
        val selected = treeScreenState.selectedNodeId?.takeIf { id -> nodes.any { it.id == id } }
            ?: stateHolder.treeSelectedId?.takeIf { id -> nodes.any { it.id == id } }
            ?: nodes.lastOrNull()?.id
        treeScreenState.selectedNodeId = selected
        val index = nodes.indexOfFirst { it.id == selected }
        if (index >= 0 && selected != treeScreenState.lastPositionedId) {
            treeScreenState.vertical.scrollToItem((index - 4).coerceAtLeast(0))
            treeScreenState.lastPositionedId = selected
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.bg).testTag("finecat.tree")) {
        val viewportWidth = maxWidth
        // 等宽前缀与正文随横滑一起移动；按字符的最大字宽估算，中文也不会被压缩成几字。
        val contentWidth = remember(nodes, viewportWidth, fontScale) {
            maxOf(viewportWidth, (48 + (nodes.maxOfOrNull {
                treeRowPrefix(it).length + (it.label?.length ?: 0) + (it.displayText ?: it.preview).length + 12
            } ?: 0).coerceAtMost(4000) * 14 * fontScale).dp)
        }
        Column(Modifier.fillMaxSize()) {
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
            } else {
                Text("基础会话树；升级网关后可搜索和折叠", style = type.xs, color = colors.text3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            stateHolder.treeError?.let { error ->
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("树加载失败：$error", color = colors.err, style = type.sm, modifier = Modifier.weight(1f))
                    FcTextButton(text = "重试", enabled = !loading, onClick = { load(treeScreenState.view) })
                }
            }
            if (loading) Text("加载中…", style = type.xs, color = colors.text3, modifier = Modifier.padding(horizontal = 20.dp))
            if (ownedTree == null && !loading && stateHolder.treeError == null) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("尚未载入会话树", style = type.sm, color = colors.text3, modifier = Modifier.weight(1f))
                    FcTextButton(text = "重试", onClick = { load(treeScreenState.view) })
                }
            }
            if (ownedTree != null && nodes.isEmpty() && !loading) {
                Text(if (treeScreenState.view.search.isNotBlank()) "没有匹配的节点" else "会话还没有记录",
                    style = type.sm, color = colors.text3, modifier = Modifier.padding(20.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f).horizontalScroll(treeScreenState.horizontal)) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = treeScreenState.vertical,
                    modifier = Modifier.width(contentWidth).fillMaxHeight().testTag("finecat.treeContent"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    items(nodes.size, key = { nodes[it].id }) { index ->
                        val node = nodes[index]
                        TreeRow(node, node.id == ownedTree?.second, node.id == treeScreenState.selectedNodeId,
                            onClick = { treeScreenState.selectedNodeId = node.id; stateHolder.switchBranchTo(node.id) },
                            canFold = fullView && node.foldable && !loading,
                            onFold = {
                                val ids = treeScreenState.view.foldedIds
                                load(treeScreenState.view.copy(foldedIds = if (node.id in ids) ids - node.id else ids + node.id))
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeRow(node: GatewayTreeNode, isLeaf: Boolean, selected: Boolean, onClick: () -> Unit,
                    canFold: Boolean, onFold: () -> Unit) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(Modifier.fillMaxWidth().background(if (selected) colors.surface2 else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp)
        .testTag("finecat.treeNode.${node.id}"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isLeaf) FcStatusTag(text = "当前", style = FcTagStyle.Ok)
        Box(Modifier.width(48.dp)) {
            if (node.foldable) FcTextButton(text = if (node.folded) "+" else "−", enabled = canFold, onClick = onFold,
                modifier = Modifier.size(48.dp).semantics { contentDescription = if (node.folded) "展开分支" else "折叠分支" }.testTag("finecat.treeFold.${node.id}"))
        }
        Text(treeRowPrefix(node), style = type.sm.copy(fontFamily = FontFamily.Monospace), color = colors.text3,
            softWrap = false, maxLines = 1, modifier = Modifier.testTag("finecat.treePrefix.${node.id}"))
        node.label?.takeIf(String::isNotEmpty)?.let { Text("[$it]", style = type.xs, color = colors.brand, maxLines = 1) }
        Text(node.displayText ?: when (node.role) {
            "user" -> "user: ${node.preview}"
            "assistant" -> "assistant: ${node.preview}"
            else -> node.preview
        }, style = type.sm, color = colors.text, maxLines = 1, softWrap = false,
            modifier = Modifier.testTag("finecat.treeText.${node.id}"))
    }
}
