package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.finecat.components.FcHint
import com.finecat.pimobile.android.ui.finecat.components.FcSearchField
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.protocol.GatewayModelScope
import com.finecat.pimobile.shared.protocol.GatewayModelSelection

/** 保存按钮或外点确认写全局范围；打开/明确取消不写设置，不切当前模型。 */
@Composable
internal fun FineCatModelScopeSheet(stateHolder: AndroidSharedStateHolder, onDismiss: () -> Unit) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val openingRevision = remember { stateHolder.modelScopeLoadRevision }
    var snapshot by remember { mutableStateOf<GatewayModelScope?>(null) }
    var selected by remember { mutableStateOf<List<GatewayModelSelection>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var preserveUnmatched by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    val busy = stateHolder.modelScopeIsLoading || stateHolder.modelScopeIsSaving
    val connected = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
    val supported = "model-scope-v1" in stateHolder.gatewayState.capabilities
    LaunchedEffect(Unit) { stateHolder.loadModelScope() }
    LaunchedEffect(stateHolder.modelScopeLoadRevision) {
        if (stateHolder.modelScopeLoadRevision > openingRevision) {
            snapshot = stateHolder.modelScope
            selected = snapshot?.selected.orEmpty()
            preserveUnmatched = true
            confirmReset = false
        }
    }
    LaunchedEffect(stateHolder.modelScopeSavedRequestId) {
        if (pendingSave != null && pendingSave == stateHolder.modelScopeSavedRequestId) onDismiss()
    }
    fun saveSelection() {
        val loaded = snapshot ?: return
        if (busy) return
        pendingSave = stateHolder.saveModelScope(loaded.patterns, selected, preserveUnmatched)
    }
    FcSheet(
        visible = true,
        onDismiss = { if (!stateHolder.modelScopeIsSaving) onDismiss() },
        onOutsideClick = {
            val loaded = snapshot
            when {
                stateHolder.modelScopeIsSaving -> Unit
                confirmReset -> confirmReset = false
                loaded == null -> onDismiss()
                busy -> Unit
                selected == loaded.selected && preserveUnmatched -> onDismiss()
                else -> saveSelection()
            }
        },
        bottomPadding = 0.dp,
        modifier = Modifier.testTag("finecat.modelScopeSheet"),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            val footerMaxHeight = maxHeight * 0.4f
            Column(Modifier.fillMaxSize().semantics { paneTitle = "scope-model" }) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp)) {
                    Text("scope-model", style = type.title.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp, vertical = 14.dp))
                    FcIconButton(icon = FineCatIcons.X, contentDescription = "取消模型范围编辑",
                        onClick = onDismiss, enabled = !stateHolder.modelScopeIsSaving,
                        buttonSize = 48.dp, iconSize = 20.dp,
                        modifier = Modifier.align(Alignment.CenterEnd).testTag("finecat.modelScopeClose"))
                }
                stateHolder.modelScopeError?.let {
                    Text(it, style = type.sm, color = colors.err,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp).verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp))
                }
                if (!connected || !supported) {
                    FcHint(text = if (!connected) "请先连接网关。" else "请升级网关后编辑模型范围。")
                }
                if (stateHolder.modelScopeIsLoading) FcHint(text = "正在读取 Pi 模型范围…")
                val loaded = snapshot
                if (loaded != null) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FcSearchField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "搜索模型或 provider",
                            fieldTag = "finecat.modelScopeSearch",
                            modifier = Modifier.weight(1f).testTag("finecat.modelScopeSearchPill"),
                        )
                        FcIconButton(icon = FineCatIcons.Refresh, contentDescription = "重新加载", onClick = stateHolder::loadModelScope,
                            enabled = !busy && connected && supported)
                    }
                    val filteredGroups = loaded.groups.map { group ->
                        group to group.models.filter {
                            query.isBlank() || "${group.provider} ${it.id} ${it.name}".contains(query, ignoreCase = true)
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("finecat.modelScopeList")) {
                        if (filteredGroups.all { it.second.isEmpty() }) {
                            item {
                                Text(if (query.isBlank()) "暂无可用模型" else "没有匹配的模型",
                                    style = type.sm, color = colors.text3,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
                            }
                        }
                        for ((group, models) in filteredGroups) {
                            items(models, key = { "model:${group.provider}/${it.id}" }) { model ->
                                val checked = selected.any { it.provider == group.provider && it.modelId == model.id }
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .toggleable(value = checked, enabled = !busy && connected && supported,
                                        role = Role.Checkbox, onValueChange = {
                                            selected = toggleScopeModel(selected, loaded.selected, group.provider, model.id)
                                        })
                                    .semantics { stateDescription = if (checked) "已选择" else "未选择" }
                                    .testTag("finecat.modelScope.${group.provider}.${model.id}")
                                    .padding(horizontal = 24.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(model.name.ifBlank { model.id }, style = type.body.copy(
                                            fontSize = 17.sp, lineHeight = 24.sp,
                                            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal),
                                            color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.testTag("finecat.modelScopeLabel.${group.provider}.${model.id}"))
                                        Text(group.provider, style = type.xs, color = colors.text3,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.testTag("finecat.modelScopeProvider.${group.provider}.${model.id}"))
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                        if (checked) Icon(FineCatIcons.Check, contentDescription = null,
                                            tint = colors.ink, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                        if (loaded.unmatchedPatterns.isNotEmpty()) {
                            item {
                                FcHint(text = loaded.unmatchedPatterns.joinToString("、"))
                                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = preserveUnmatched,
                                        onCheckedChange = { preserveUnmatched = it },
                                        enabled = !busy && connected && supported,
                                        colors = CheckboxDefaults.colors(checkedColor = colors.ink, uncheckedColor = colors.text3, checkmarkColor = colors.onInk),
                                    )
                                    Text("保留未匹配条目", style = type.sm, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = footerMaxHeight)
                        .verticalScroll(rememberScrollState()).testTag("finecat.modelScopeFooter")) {
                        Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(colors.line))
                        if (confirmReset) {
                            FcHint(text = "恢复默认将显示全部模型。")
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                FcTextButton("确认恢复默认", onClick = {
                                    pendingSave = stateHolder.saveModelScope(loaded.patterns, emptyList(), preserveUnmatched = false, reset = true)
                                }, enabled = !busy && connected && supported, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                                FcTextButton("取消", onClick = { confirmReset = false }, enabled = !busy, modifier = Modifier.padding(start = 16.dp).heightIn(min = 48.dp))
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                FcTextButton("恢复默认", onClick = { confirmReset = true }, muted = true, enabled = !busy && connected && supported, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                                Spacer(Modifier.width(16.dp))
                                Button(
                                    onClick = { saveSelection() },
                                    enabled = !busy && connected && supported && selected.isNotEmpty(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.onInk,
                                        disabledContainerColor = colors.surface2, disabledContentColor = colors.text3),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("finecat.modelScopeSave"),
                                ) {
                                    Text(if (stateHolder.modelScopeIsSaving) "保存中…" else "保存 · ${selected.size}", style = type.sm)
                                }
                            }
                        }
                    }
                }
                if (snapshot == null && !stateHolder.modelScopeIsLoading) {
                    FcTextButton("重新加载", onClick = stateHolder::loadModelScope, enabled = !busy && connected && supported,
                        modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp))
                }
            }
        }
    }
}

/** Re-selecting an existing model retains its saved thinking level; no wildcard is generated. */
internal fun toggleScopeModel(
    selected: List<GatewayModelSelection>, original: List<GatewayModelSelection>, provider: String, modelId: String,
): List<GatewayModelSelection> = if (selected.any { it.provider == provider && it.modelId == modelId }) {
    selected.filterNot { it.provider == provider && it.modelId == modelId }
} else {
    selected + (original.firstOrNull { it.provider == provider && it.modelId == modelId }
        ?: GatewayModelSelection(provider, modelId))
}
