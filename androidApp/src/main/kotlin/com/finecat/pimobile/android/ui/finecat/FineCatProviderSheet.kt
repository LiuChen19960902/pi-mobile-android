package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.finecat.pimobile.android.ProviderTestTarget
import com.finecat.pimobile.android.ProviderTestAttempt
import com.finecat.pimobile.android.providerTestHint
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.PROVIDER_COMPAT_HINTS
import com.finecat.pimobile.android.ui.ProviderModelDraft
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FcHint
import com.finecat.pimobile.android.ui.finecat.components.FcPopover
import com.finecat.pimobile.android.ui.finecat.components.FcPopoverItem
import com.finecat.pimobile.android.ui.finecat.components.FcRow
import com.finecat.pimobile.android.ui.finecat.components.FcSectionLabel
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcSheetHost
import com.finecat.pimobile.android.ui.finecat.components.FcStatusTag
import com.finecat.pimobile.android.ui.finecat.components.FcSwitch
import com.finecat.pimobile.android.ui.finecat.components.FcTagStyle
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.PI_THINKING_LEVELS
import com.finecat.pimobile.shared.gateway.PROVIDER_API_CHOICES
import com.finecat.pimobile.shared.gateway.requireValidProviderUpsert
import com.finecat.pimobile.shared.protocol.GatewayProvider
import kotlinx.coroutines.delay

/**
 * P10a/P10b Provider 列表与表单（S11-P 一级弹卡 + 二级模型卡）。真源：
 * - 原型 `providerSheet`/`modelDetail`（app.js 452/604）：Provider 卡列模型行，
 *   点行进二级卡（`.mv-back` 可返回）；「手动添加模型」直接进新行的二级卡；
 *   `pv-pull` **只补新的**，回执一行「新增 N · 跳过 M（已存在）」。
 * - 旧 UI `ProviderSettingsUi`：API Key 留空=保留既有凭据（掩码不上 wire）、
 *   测试连接绑定发起时的连接配置、协议逐模型下发。
 * - 解析/校验/拉取合并/门槛判定都在 [FineCatProviderForm]（纯函数，JVM 可测）。
 *
 * 只有异常才写字；Provider 副行不写协议（协议是模型级，components.md §8）。
 */
@Composable
internal fun FineCatProviderSheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val connected = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
    // 编辑对象按 id 记：provider-test/保存后门面会重拉 providers（列表项换新实例），
    // 用实例做键会把正在填的表单冲掉。
    var editingId by remember { mutableStateOf<String?>(null) }
    val providers = stateHolder.providers

    FcSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "Provider",
        showDone = true,
        onDone = {
            stateHolder.loadProviders()
            onDismiss()
        },
        modifier = modifier.testTag("finecat.providerSheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // 保存/删除/校验回执（含网关失败原因）在列表级可见
            stateHolder.providerResult?.let { result ->
                Text(
                    text = result,
                    style = type.xs,
                    color = providerReceiptColor(result),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .testTag("finecat.providerResult"),
                )
            }
            if (providers.isEmpty() && !stateHolder.providerListIsLoading) {
                FcHint(
                    text = if (connected) "还没有 provider，点下面「新增 Provider」添加。"
                    else "未连接网关，读不到电脑端的 models.json。",
                )
            }
            providers.forEach { provider ->
                ProviderRow(provider = provider, enabled = connected, onEdit = {
                    if (stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED) editingId = provider.id
                })
            }
            FcTextButton(
                text = "新增 Provider",
                onClick = { editingId = NEW_PROVIDER_ID },
                enabled = connected,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("finecat.providerNew"),
            )
            FcHint(
                text = if (connected) {
                    "保存/删除/测试连接都走网关；API Key 只写入不回显（留空=保留已存的 key）。"
                } else {
                    "需先连接 Pi 网关才能读写 models.json。"
                },
            )
        }
    }

    FcSheetHost(value = editingId) { editingKey ->
        // 打开瞬间快照一份 provider：编辑期间不因列表重拉换实例（数值/id 回填只用这份）
        val existing = remember(editingKey) {
            if (editingKey == NEW_PROVIDER_ID) null else providers.firstOrNull { it.id == editingKey }
        }
        if (existing != null || editingKey == NEW_PROVIDER_ID) {
            ProviderEditSheet(
                stateHolder = stateHolder,
                existing = existing,
                isNew = editingKey == NEW_PROVIDER_ID,
                connected = connected,
                onDismiss = { editingId = null },
            )
        } else {
            // 这个 id 已不在列表（远端刚删掉）：不开一张空表（会把原有 models 清掉），直接收起
            LaunchedEffect(editingKey) { editingId = null }
        }
    }
}

/**
 * Provider **表单**（二级 sheet，叠在设置整页之上）。
 *
 * 列表与「新增 Provider」入口已按原型搬进设置整页（`FineCatSettingsScreen`）的
 * 「模型与 Provider」小节，这里只负责编辑一个 provider（新建/改/删都在这一层）。
 */
@Composable
internal fun FineCatProviderEditSheet(
    stateHolder: AndroidSharedStateHolder,
    providerId: String,
    isNew: Boolean,
    closing: Boolean = false,
    onDismiss: () -> Unit,
) {
    val connected = stateHolder.gatewayState.connection ==
        com.finecat.pimobile.shared.gateway.GatewayConnectionState.CONNECTED
    val providers = stateHolder.providers
    // 打开瞬间快照一份 provider：编辑期间不因列表重拉换实例（数值/id 回填只用这份）。
    // key 里带上列表身份，列表晚到时（例如应答在打开之后才落）也能拿到正确的快照。
    val currentExisting = remember(providerId, isNew, providers) {
        if (isNew) null else providers.firstOrNull { it.id == providerId }
    }
    val retainedExisting = remember(providerId, isNew) { mutableStateOf(currentExisting) }
    SideEffect { if (currentExisting != null) retainedExisting.value = currentExisting }
    // 已打开的编辑树在掉线/重载空列表期间持续挂载，保留文字、密钥和模型草稿。
    val existing = currentExisting ?: retainedExisting.value
    if (!closing && connected && !isNew && currentExisting == null && !stateHolder.providerListIsLoading && providers.isNotEmpty()) {
        LaunchedEffect(providerId) { onDismiss() }
    }
    when {
        existing != null || isNew -> ProviderEditSheet(
            stateHolder = stateHolder,
            existing = existing,
            isNew = isNew,
            connected = connected,
            onDismiss = onDismiss,
        )
        // 加载中/空列表可为重拉瞬时态；有旧草稿时继续画完退场，不打开空表。
        else -> Unit
    }
}

/** Provider 行副行：与设置整页同一实现（原型 `pvRow`：方言 · N 个模型 · M 项改动）。 */
internal fun providerRowSubtitle(provider: GatewayProvider): String = listOfNotNull(
    provider.api?.takeIf(String::isNotBlank),
    "${provider.models.size} 个模型",
    compatOverrides(provider).takeIf { it > 0 }?.let { "$it 项改动" },
).joinToString(" · ")

/** 回执配色：保存/删除成功绿，"已保存，但…"黄，其余红（旧 UI providerResultColor 同口径）。 */
@Composable
internal fun providerReceiptColor(message: String): androidx.compose.ui.graphics.Color {
    val colors = LocalFineCatColors.current
    return when {
        message.startsWith("Provider 已保存") || message.startsWith("Provider 已删除") -> colors.ok
        message.startsWith("已保存，但") -> colors.warn
        else -> colors.err
    }
}

/**
 * Provider 行报「我在这一层统一改过几项」= 各模型 `compat` 字段数之和（原型 `pvOver`）。
 * 只数顶层键，不递归嵌套值——原型数的是 `Object.keys(m.compat).length`。
 *
 * 提到 `internal` 供 JVM 单测直接调用：`androidApp` 的 test source set 能看到
 * `internal`，而 private 只能靠复刻一份实现来测——那样测不到真代码。
 */
internal fun compatOverrides(provider: GatewayProvider): Int =
    provider.models.sumOf { model ->
        (model.compat as? com.finecat.pimobile.shared.protocol.JsonValue.ObjectValue)
            ?.value?.size ?: 0
    }

/** Provider 行（.provider-row）：cloud 图标 + 名称 + 副行（方言 · N 个模型 · M 项改动；异常才挂红标）。 */
@Composable
private fun ProviderRow(
    provider: GatewayProvider,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onEdit)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("finecat.providerRow.${provider.id}"),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = FineCatIcons.Cloud,
                contentDescription = null,
                tint = colors.text3,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = provider.name ?: provider.id,
                style = type.sm.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!provider.apiKeySet) {
                FcStatusTag(text = "缺 key", style = FcTagStyle.Bad)
            }
            if (!provider.active) {
                FcStatusTag(text = "不可用", style = FcTagStyle.Bad)
            }
        }
            Text(
            // 原型 `pvRow` 的副行是「方言 · N 个模型 · M 项改动」——报的是
            // "我在这一层统一改过几项"（各模型 compat 字段数之和），不是 baseUrl。
            // baseUrl 太长会挤掉后半段，且它属于详情卡的信息。
            text = providerRowSubtitle(provider),
            style = type.xs2,
            color = colors.text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, top = 2.dp),
        )
        // 测过且连不通：原因写在行内，不用点进去猜
        provider.status?.takeIf { !it.ok }?.error?.takeIf(String::isNotBlank)?.let { reason ->
            Text(
                text = reason,
                style = type.xs2,
                color = colors.err,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
    }
}

/**
 * Provider 编辑弹卡（原型 `.provider-sheet`）：ID/名称/Base URL/API Key + 模型行 +
 * 拉取 + 手动添加 + 测试连接 + 删除；「保存」在卡头。模型行进二级卡
 * [FineCatModelDetailSheet]。本地失败（校验/解析/未测试/保存被抻住）都落 `localError`，
 * 在本卡内可见且不收起，不静默；保存**发出后也留在卡里**，等网关回执是成功才收起
 * （旧写法“本地通过就 dismiss”会把写失败连同草稿一起弄没）。
 *
 * 拉取/测试/保存的结果都按「本卡片发起过的那一次」认（[FineCatProviderForm.ownsResult]）：
 * holder 是全局的、应答帧又没有 requestId，迟到的或上一张卡的结果不该洗给新表单。
 */
@Composable
private fun ProviderEditSheet(
    stateHolder: AndroidSharedStateHolder,
    existing: GatewayProvider?,
    isNew: Boolean,
    connected: Boolean,
    onDismiss: () -> Unit,
) {
    val editorId = if (isNew) NEW_PROVIDER_ID else requireNotNull(existing).id
    // 退出动画尚未卸载时切换对象，也不能复用上一份表单或密钥。
    key(stateHolder, stateHolder.endpoint.trim(), editorId) {
        ProviderEditorForm(stateHolder, existing, isNew, connected, onDismiss)
    }
}

@Composable
private fun ProviderEditorForm(
    stateHolder: AndroidSharedStateHolder,
    existing: GatewayProvider?,
    isNew: Boolean,
    connected: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val draftKey = remember {
        stateHolder.endpoint.trim() to if (isNew) NEW_PROVIDER_ID else requireNotNull(existing).id
    }
    val resumedDraft = remember { stateHolder.providerEditorDrafts[draftKey] }
    // 完成挂载后才取走；退出动画期间新存的草稿不能被旧卡片的重组消费。
    SideEffect {
        if (resumedDraft != null && stateHolder.providerEditorDrafts[draftKey] === resumedDraft) {
            stateHolder.providerEditorDrafts.remove(draftKey)
        }
    }
    val initialProvider = remember { resumedDraft?.initialProvider ?: existing }
    var id by rememberSaveable { mutableStateOf(resumedDraft?.id ?: existing?.id ?: "") }
    var name by rememberSaveable { mutableStateOf(resumedDraft?.name ?: existing?.name ?: "") }
    var baseUrl by rememberSaveable { mutableStateOf(resumedDraft?.baseUrl ?: existing?.baseUrl ?: "") }
    // API Key 只在内存：rememberSaveable 会把它写进 Bundle（进程重建/备份/抓取都可能
    // 把凭据带出去），而它的语义本来就是「留空=保留已存值」，不需要过旋转。
    var apiKey by remember { mutableStateOf(resumedDraft?.apiKey.orEmpty()) }
    val rows = remember {
        mutableStateListOf<ProviderModelDraft>().apply {
            addAll(resumedDraft?.rows ?: FineCatProviderForm.initialRows(existing))
        }
    }
    var modelViewOrder by remember {
        mutableStateOf(resumedDraft?.modelViewOrder ?: stateHolder.providerModelViewOrder(id.trim()))
    }
    val viewIndices = FineCatProviderForm.viewIndices(rows.toList(), modelViewOrder)
    val hasChanges = FineCatProviderForm.hasChanges(initialProvider, id, name, baseUrl, apiKey, rows.toList())
    /** 二级模型卡对应的行下标；null = 收起 */
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showSaveValidation by remember { mutableStateOf(false) }
    var pullReceipt by remember { mutableStateOf<String?>(null) }
    // 删除是高危动作：沿旧 UI 的二次确认 + 8s 自动解除（防误触即删）
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(8_000)
            confirmDelete = false
        }
    }
    val fingerprint = "${id.trim()}\n" + FineCatProviderForm.fingerprint(baseUrl, apiKey, rows.toList())
    // 公共地址留空在 provider-put 中表示保留；测试指纹与实际生效地址一致。
    val effectiveProviderBaseUrl = baseUrl.trim().ifBlank { initialProvider?.baseUrl.orEmpty() }
    val connectionKeys = rows.associate { row -> row.id.trim() to
        FineCatProviderForm.connectionFingerprint(id, effectiveProviderBaseUrl, apiKey, row) }
    var connectionResults by remember { mutableStateOf<Map<String, ProviderTestAttempt>>(emptyMap()) }
    var pendingTestConnections by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingTestFingerprint by remember { mutableStateOf<String?>(null) }
    var testOutcomeFingerprint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connectionKeys) {
        connectionResults = connectionResults.filterKeys { it in connectionKeys.values }
    }
    // holder 的结果由各卡片共享；卡片仅领取自己发起的结果。
    // 测试回执另由 Runtime / holder 按 providerTestId 匹配，再按连接指纹认领。
    var pendingPull by remember { mutableStateOf<FineCatProviderForm.ProviderRequestKey?>(null) }
    var pendingTest by remember { mutableStateOf<FineCatProviderForm.ProviderRequestKey?>(null) }
    var pendingSave by remember { mutableStateOf<FineCatProviderForm.ProviderRequestKey?>(null) }
    /** 本次提交的特征：用来把迟到的上一份成功回执与这一次的成功分开。 */
    var submittedFingerprint by remember { mutableStateOf<ProviderSubmitFingerprint?>(null) }
    var submittedDraft by remember { mutableStateOf<Pair<Triple<String, String, String>, List<ProviderModelDraft>>?>(null) }
    /** 本卡片取走的测试结论：不直接读 holder，重开一张卡不该拿到上一张的结论。 */
    var testOutcome by remember { mutableStateOf<AndroidSharedStateHolder.ProviderTestOutcome?>(null) }
    LaunchedEffect(stateHolder.providerTestResult) {
        val outcome = stateHolder.providerTestResult ?: return@LaunchedEffect
        if (!FineCatProviderForm.ownsResult(pendingTest, outcome.providerId)) return@LaunchedEffect
        pendingTest = null
        val pending = pendingTestFingerprint
        pendingTestFingerprint = null
        testOutcome = outcome
        testOutcomeFingerprint = pending.takeIf { it == fingerprint }
        // 只认发起时的模型连接；迟到回执不认证随后修改的地址、Key、ID或协议。
        val accepted = outcome.attempts.mapNotNull { attempt ->
            val captured = pendingTestConnections[attempt.modelId]
            if (captured != null && captured == connectionKeys[attempt.modelId]) captured to attempt else null
        }.toMap()
        connectionResults = connectionResults + accepted
        pendingTestConnections = emptyMap()
    }

    // 保存：发出就收卡会把网关失败一起丢了。现在发出后留在卡里等**自己的**终态：
    // 成功才收起，失败/未确认把原因留在卡内，草稿不没。
    //
    // 只听 holder 的保存业务终态通道 `providerSaveTerminal`，不再解析瞬时提示文案
    // —— 提示槽会被列表刷新等读请求的取消通知覆盖（同一帧），拿它判成败就会出现
    // 「实际已保存却提示已取消、表单不收」。终态通道只由本次在途 provider-put 写。
    LaunchedEffect(stateHolder.providerSaveTerminal) {
        val key = pendingSave ?: return@LaunchedEffect
        val terminal = stateHolder.providerSaveTerminal ?: return@LaunchedEffect
        // 在途标记对不上 = 这条终态解掉的是别的请求（如旧保存已超时、这会儿才到的
        // 迟到回执）。无归属的终态不认领，继续等自己那次。
        if (terminal.token != key.token) return@LaunchedEffect
        if (terminal.status == AndroidSharedStateHolder.ProviderSaveTerminal.Status.Saved &&
            !FineCatProviderForm.echoMatchesSubmit(terminal.echoed, submittedFingerprint)
        ) {
            // 回显内容与本次提交不符 = 迟到的上一份成功，不能拿它收掉这一次的表单。
            return@LaunchedEffect
        }
        pendingSave = null
        when (terminal.status) {
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Saved -> {
                if (submittedDraft == (Triple(id, name, fingerprint) to rows.toList())) {
                    stateHolder.providerEditorDrafts.remove(draftKey)
                    onDismiss()
                }
                else localError = "提交时的内容已保存，随后编辑的内容尚未保存。"
            }
            // 拒绝是真失败；取消/超时只表示未确认，原因照实写进卡内错误位，
            // 但文案不改成「没保存」（请求可能已到网关）。
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Rejected,
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            -> localError = terminal.detail
        }
    }
    val testTargets = FineCatProviderForm.testModels(viewIndices.map { rows[it] })
    val requiredGroups = FineCatProviderForm.requiredTestGroups(
        initialProvider, id, effectiveProviderBaseUrl, apiKey, rows.toList(),
    )
    val untestedGroups = FineCatProviderForm.untestedGroups(
        requiredGroups, id, effectiveProviderBaseUrl, apiKey, connectionResults,
    )
    val saveBlockedReason = when {
        untestedGroups.isEmpty() -> null
        "provider-test-id-v1" !in stateHolder.gatewayState.capabilities -> "请更新网关后测试连接。"
        testTargets.isEmpty() -> "请先同步模型或手动添加模型。"
        stateHolder.providerTestIsLoading -> "测试中，请稍候。"
        untestedGroups.first().size == 1 -> "请先测试 ${untestedGroups.first().single().id}。"
        else -> "连接配置已更改，请先测试连接。"
    }

    fun startTest(onlyModel: ProviderModelDraft? = null): String? {
        if (!connected) return "请先连接 Pi 网关。"
        if (stateHolder.providerTestIsLoading || stateHolder.providerModelsIsLoading) return "请求处理中，请稍候。"
        if ("provider-test-id-v1" !in stateHolder.gatewayState.capabilities) return "请更新网关后测试连接。"
        val modelIds = rows.map { it.id.trim() }.filter(String::isNotEmpty)
        if (modelIds.distinct().size != modelIds.size) return "模型 ID 重复，请先修改。"
        val groups = if (onlyModel != null) listOf(listOf(onlyModel)) else untestedGroups
        val ids = groups.flatten().map { it.id.trim() }.toSet()
        val targets = if (groups.isEmpty()) testTargets else testTargets.filter { it.id in ids }
        if (targets.isEmpty()) return "请先添加模型。"
        pendingTestFingerprint = fingerprint
        pendingTestConnections = targets.associate { it.id to
            FineCatProviderForm.connectionFingerprint(id, effectiveProviderBaseUrl, apiKey, it) }
        pendingTest = FineCatProviderForm.requestKey(id)
        testOutcome = null
        testOutcomeFingerprint = null
        localError = null
        stateHolder.testProviderModels(
            id = id.trim().takeIf(String::isNotBlank),
            baseUrl = baseUrl.trim().takeIf(String::isNotBlank),
            apiKey = apiKey.trim().takeIf(String::isNotBlank),
            targets = targets.map {
                ProviderTestTarget(it.id, it.api, it.baseUrl.takeIf(String::isNotEmpty),
                    modelBaseUrlSpecified = "provider-model-base-url-v1" in stateHolder.gatewayState.capabilities)
            },
            requiredModelGroups = groups.map { group -> group.map { it.id.trim() }.toSet() },
        )
        return null
    }
    // 本地校验随当前草稿重算；离线时由连接提示说明原因，业务失败/未确认仍保留。
    val saveValidationError = if (showSaveValidation && connected) {
        validateForSave(
            connected = connected,
            id = id,
            name = name.trim().takeIf(String::isNotEmpty),
            baseUrl = baseUrl.trim().takeIf(String::isNotEmpty),
            apiKey = apiKey.trim().takeIf(String::isNotEmpty),
            parsed = FineCatProviderForm.parseModels(rows.toList(), existing),
            saveGateMessage = saveBlockedReason,
        )
    } else null
    val formError = localError ?: saveValidationError

    // 上游拉取结果：一次性消费 → 按表单归属 → 只新增不覆盖 → 回执
    LaunchedEffect(stateHolder.providerModelsResult) {
        val outcome = stateHolder.providerModelsResult ?: return@LaunchedEffect
        if (!FineCatProviderForm.ownsResult(pendingPull, outcome.providerId)) return@LaunchedEffect
        pendingPull = null
        stateHolder.consumeProviderModelsResult()
        detailIndex = null
        if (!outcome.ok) {
            localError = outcome.error ?: "拉取上游模型失败"
            pullReceipt = null
            return@LaunchedEffect
        }
        val merged = FineCatProviderForm.appendPulled(rows.toList(), outcome.ids)
        rows.clear()
        rows.addAll(merged.rows)
        localError = null
        pullReceipt = if (merged.added == 0) {
            "上游没有新模型（已配置 ${merged.rows.size} 个）"
        } else {
            "新增 ${merged.added} · 跳过 ${merged.skipped}（已存在）"
        }
    }

    fun submit() {
        if (stateHolder.providerSaveIsLoading) {
            // holder 的保存位是单发的，正在存时再点会被 saveProvider 默默 return；
            // 静默吞掉一次保存比多一下点击严重点，这里把原因写出来。
            localError = "上一个保存还在路上，等它的结论回来再存。"
            return
        }
        val parsed = FineCatProviderForm.parseModels(rows.toList(), existing)
        val providerName = name.trim().takeIf(String::isNotEmpty)
        val trimmedBaseUrl = baseUrl.trim().takeIf(String::isNotEmpty)
        val trimmedApiKey = apiKey.trim().takeIf(String::isNotEmpty)
        val error = validateForSave(
            connected = connected,
            id = id,
            name = providerName,
            baseUrl = trimmedBaseUrl,
            apiKey = trimmedApiKey,
            parsed = parsed,
            saveGateMessage = saveBlockedReason,
        )
        // 本地不过：错误留在本卡可见，不发 provider-put、不收卡
        showSaveValidation = error != null
        localError = null
        if (error != null) return
        // 发出后不收卡：等这一次保存自己的终态。saveProvider 返回本次请求的在途
        // 标记，返回 null = 根本没发出去（本地二次校验不过 / 发送被挡），
        // 此时不挂等待标记。
        val token = stateHolder.saveProvider(
            id = id.trim(),
            baseUrl = trimmedBaseUrl,
            apiKey = trimmedApiKey,
            models = parsed.rows,
            name = providerName,
        )
        pendingSave = if (token == null) {
            null
        } else {
            submittedDraft = Triple(id, name, fingerprint) to rows.toList()
            submittedFingerprint = FineCatProviderForm.submitFingerprint(
                id = id,
                name = providerName,
                baseUrl = trimmedBaseUrl,
                models = parsed.rows,
            )
            FineCatProviderForm.requestKey(id, token)
        }
    }

    fun closeEditor() {
        // 用当前连接与输入值，不把重连进行中或同帧输入当成已在线/空稿。
        val online = stateHolder.gatewayState.connection ==
            com.finecat.pimobile.shared.gateway.GatewayConnectionState.CONNECTED
        if (!online && FineCatProviderForm.hasChanges(initialProvider, id, name, baseUrl, apiKey, rows.toList())) {
            stateHolder.providerEditorDrafts[draftKey] = ProviderEditorDraft(
                initialProvider, id, name, baseUrl, apiKey, rows.toList(), modelViewOrder.toList(),
            )
        } else {
            stateHolder.providerEditorDrafts.remove(draftKey)
        }
        onDismiss()
    }

    fun deleteProvider() {
        if (!connected) {
            localError = "未连接 Pi 网关，删除发不出去。"
            return
        }
        stateHolder.deleteProvider(existing?.id ?: id)
        stateHolder.providerEditorDrafts.remove(draftKey)
        onDismiss()
    }

    FcSheet(
        visible = true,
        onDismiss = { closeEditor() },
        onOutsideClick = {
            when {
                stateHolder.providerSaveIsLoading -> Unit
                confirmDelete -> confirmDelete = false
                !connected || !hasChanges -> closeEditor()
                else -> submit()
            }
        },
        title = null,
        maxHeightFraction = 0.94f,
        bottomPadding = 0.dp,
        modifier = Modifier.testTag("finecat.providerEdit"),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, bottom = 8.dp)
                    .testTag("finecat.sheetHeader"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FcIconButton(
                    icon = FineCatIcons.X, contentDescription = if (connected) "取消编辑" else "关闭并保留草稿",
                    onClick = { closeEditor() }, enabled = !stateHolder.providerSaveIsLoading,
                    buttonSize = 48.dp, iconSize = 20.dp,
                    modifier = Modifier.testTag("finecat.providerClose"),
                )
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(if (isNew) "新增 Provider" else existing?.name ?: id,
                        style = type.body.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold), color = colors.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!isNew) {
                        SelectionContainer {
                            Text("ID $id · 不可修改", style = type.xs, color = colors.text3,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("finecat.formId"))
                        }
                    }
                }
                Button(
                    onClick = { submit() },
                    enabled = hasChanges && connected && !stateHolder.providerSaveIsLoading,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White,
                        disabledContainerColor = colors.surface2, disabledContentColor = colors.text3),
                    modifier = Modifier.heightIn(min = 36.dp).testTag("finecat.sheetDone"),
                ) { Text(if (stateHolder.providerSaveIsLoading) "保存中" else "保存",
                    style = type.sm.copy(lineHeight = 20.sp, fontWeight = FontWeight.Medium), maxLines = 1) }
            }
            HorizontalDivider(color = colors.line.copy(alpha = 0.6f))
            if (!connected) {
                Row(Modifier.fillMaxWidth().testTag("finecat.providerOffline"), verticalAlignment = Alignment.CenterVertically) {
                    Text("网关未连接，草稿已保留。", style = type.xs, color = colors.warn,
                        modifier = Modifier.weight(1f).padding(start = 20.dp))
                    FcTextButton("重新连接", onClick = stateHolder::connect,
                        enabled = stateHolder.gatewayState.connection !in setOf(GatewayConnectionState.CONNECTING,
                            GatewayConnectionState.AUTHENTICATING, GatewayConnectionState.WAITING_FOR_NETWORK),
                        modifier = Modifier.padding(end = 12.dp).testTag("finecat.providerReconnect"))
                }
            }
            formError?.let { message ->
                Text(
                    text = message,
                    style = type.xs,
                    color = colors.err,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 72.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .testTag("finecat.formError"),
                )
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("连接配置", style = type.sm.copy(fontWeight = FontWeight.SemiBold), color = colors.text,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp))
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .background(colors.surface2.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(vertical = 8.dp)) {
                    if (isNew) ProviderInput(
                        label = "ID",
                        value = id,
                        onValueChange = { id = it.trim(' ').replace("/", "") },
                        placeholder = "必填，例 zhipu",
                        fieldTag = "finecat.formId",
                    )
                    ProviderInput(
                        label = "名称",
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "可选，例 智谱 · 备用",
                        fieldTag = "finecat.formName",
                    )
                    ProviderInput(
                        label = "Base URL",
                        value = baseUrl,
                        onValueChange = { baseUrl = it.replace("\n", "").replace("\r", "") },
                        placeholder = "https://…/v1",
                        url = true,
                        fieldTag = "finecat.formBaseUrl",
                    )
                    ProviderInput(
                        label = "API Key",
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        password = true,
                        placeholder = if (existing?.apiKeySet == true) "填写新 Key 以替换，留空保留" else "输入 API Key",
                        status = if (apiKey.isNotBlank()) "待保存" else if (existing?.apiKeySet == true) "已设置" else "未设置",
                        fieldTag = "finecat.formApiKey",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
                        .testTag("finecat.modelActions"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "模型 · ${rows.size}",
                        style = type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            localError = null
                            pullReceipt = null
                            pendingPull = FineCatProviderForm.requestKey(id)
                            stateHolder.pullProviderModels(
                                id = id.trim().takeIf(String::isNotBlank),
                                baseUrl = baseUrl.trim().takeIf(String::isNotBlank),
                                apiKey = apiKey.trim().takeIf(String::isNotBlank),
                                api = FineCatProviderForm.pullApi(rows.toList()),
                            )
                        },
                        enabled = connected && baseUrl.trim().matches(Regex("https?://\\S+")) &&
                            !stateHolder.providerModelsIsLoading &&
                            !stateHolder.providerTestIsLoading && stateHolder.supportsRequest("provider-models"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (testTargets.isEmpty()) Color.Black else Color.Transparent,
                            contentColor = if (testTargets.isEmpty()) Color.White else colors.text2,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = colors.text3,
                        ),
                        modifier = Modifier.heightIn(min = 36.dp).testTag("finecat.pullModels"),
                    ) { Text("同步模型", style = type.sm.copy(lineHeight = 20.sp, fontWeight = FontWeight.Medium), maxLines = 1) }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { localError = startTest() },
                        enabled = connected && !stateHolder.providerTestIsLoading &&
                            !stateHolder.providerModelsIsLoading && testTargets.isNotEmpty() &&
                            stateHolder.supportsRequest("provider-test") &&
                            "provider-test-id-v1" in stateHolder.gatewayState.capabilities,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White,
                            disabledContainerColor = Color.Transparent, disabledContentColor = colors.text3),
                        modifier = Modifier.heightIn(min = 36.dp).testTag("finecat.providerTest"),
                    ) { Text("测试连接", style = type.sm.copy(lineHeight = 20.sp, fontWeight = FontWeight.Medium), maxLines = 1) }
                }
                if (pendingTest != null && stateHolder.providerTestIsLoading) {
                    Text(stateHolder.providerTestProgress ?: "测试中…", style = type.xs, color = colors.text3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("finecat.providerTestProgress"))
                }
                if (testOutcome != null && testOutcomeFingerprint != fingerprint && untestedGroups.isNotEmpty()) {
                    Text("连接配置已更改，请重新测试", style = type.xs, color = colors.text3,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                            .testTag("finecat.providerTestResult"))
                }
                testOutcome?.takeIf { testOutcomeFingerprint == fingerprint }?.let { outcome ->
                    ProviderTestFeedback(outcome)
                }
                if (stateHolder.providerModelsIsLoading) {
                    Text("正在同步模型…", style = type.xs, color = colors.text3,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
                pullReceipt?.let { receipt ->
                    Text(receipt, style = type.xs, color = colors.text3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("finecat.pullReceipt"))
                }
                stateHolder.providerResult?.takeUnless { it == formError }?.let { result ->
                    Text(result, style = type.xs, color = providerReceiptColor(result),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                            .testTag("finecat.providerResultInEdit"))
                }

                viewIndices.forEachIndexed { viewIndex, index ->
                    val row = rows[index]
                    ModelRow(
                        index = index,
                        row = row,
                        attempt = connectionResults[connectionKeys[row.id.trim()]],
                        showTestStatus = connectionResults.isNotEmpty(),
                        enabled = connected,
                        canMoveUp = viewIndex > 0 && row.id.isNotBlank() &&
                            rows[viewIndices[viewIndex - 1]].id.isNotBlank(),
                        onMoveUp = {
                            modelViewOrder = FineCatProviderForm.moveViewUp(rows.toList(), modelViewOrder, index)
                            stateHolder.updateProviderModelViewOrder(id.trim(), modelViewOrder)
                        },
                        onClick = {
                            if (stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED) detailIndex = index
                        },
                        onRemove = {
                            rows.removeAt(index)
                            detailIndex = null
                        },
                    )
                }
                FcTextButton(
                    text = "手动添加模型",
                    onClick = {
                        if (stateHolder.gatewayState.connection != GatewayConnectionState.CONNECTED) return@FcTextButton
                        rows.add(FineCatProviderForm.newRow(rows.toList()))
                        detailIndex = rows.lastIndex
                    },
                    enabled = connected,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .testTag("finecat.addModel"),
                )
                if (!isNew) {
                    FcTextButton(
                        text = if (confirmDelete) "再点一次确认删除" else "删除 Provider",
                        onClick = {
                            if (confirmDelete) {
                                confirmDelete = false
                                deleteProvider()
                            } else confirmDelete = true
                        },
                        muted = !confirmDelete,
                        enabled = connected,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("finecat.providerDelete"),
                    )
                }
                // 保存门槛仍在submit校验；只有实际受阻才在固定错误区提示。
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    val openDetail = detailIndex?.takeIf { it in rows.indices }?.let { it to rows[it] }
    if (detailIndex != null && openDetail == null) detailIndex = null
    FcSheetHost(value = openDetail) { (index, detailRow) ->
        // 换模型重建子树，避免上一张模型卡的协议浮层/错误原样带到下一张。
        key(index) {
            FineCatModelDetailSheet(
                stateHolder = stateHolder,
                providerId = id.trim(),
                providerBaseUrl = baseUrl.trim(),
                row = detailRow,
                existing = existing,
                idLocked = FineCatProviderForm.idLocked(existing, detailRow),
                testAttempt = connectionResults[connectionKeys[detailRow.id.trim()]],
                testProgress = stateHolder.providerTestProgress.takeIf { pendingTest != null },
                onTest = { startTest(detailRow) },
                onBack = { detailIndex = null },
                onChange = { next -> if (detailIndex == index && index in rows.indices) rows[index] = next },
                onRemove = {
                    if (detailIndex == index && index in rows.indices) {
                        rows.removeAt(index)
                        detailIndex = null
                    }
                },
            )
        }
    }
}

/** 摘要只给结论；详情按模型列协议、状态码、耗时和可操作的短建议。 */
@Composable
private fun ProviderTestFeedback(outcome: AndroidSharedStateHolder.ProviderTestOutcome) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    var expanded by remember(outcome) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp)).background(colors.surface2.copy(alpha = 0.55f))
            .clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; stateDescription = if (expanded) "详情已展开" else "详情已收起" }
            .testTag("finecat.providerTestResult"), verticalAlignment = Alignment.CenterVertically) {
            Text(outcome.summary.lineSequence().first(), style = type.xs,
                color = if (outcome.ok) colors.ok else colors.err,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("详情", style = type.xs, color = colors.text2, modifier = Modifier.padding(start = 8.dp))
            Icon(if (expanded) FineCatIcons.Down else FineCatIcons.Right, contentDescription = null,
                tint = colors.text3, modifier = Modifier.padding(start = 4.dp).size(14.dp))
        }
        if (expanded) SelectionContainer {
            Column(Modifier.testTag("finecat.providerTestDetail")) {
                outcome.attempts.forEachIndexed { index, attempt ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .testTag("finecat.providerTestAttempt.$index")) {
                        Text("${attempt.modelId} · ${if (attempt.interrupted) "未完成" else attempt.summary}",
                            style = type.xs, color = if (attempt.ok) colors.ok else colors.err)
                        Text(listOfNotNull(attempt.api, attempt.httpStatus?.let { "HTTP $it" },
                            attempt.latencyMs?.let { "${it}ms" }).joinToString(" · "), style = type.xs, color = colors.text3)
                        if (!attempt.ok) Text(
                            if (attempt.interrupted) attempt.summary else providerTestHint(attempt.diagnosticCode, attempt.httpStatus),
                            style = type.xs, color = colors.text2)
                    }
                }
                if (outcome.attempts.isEmpty()) Text(outcome.summary, style = type.xs, color = colors.text2)
            }
        }
    }
}

/**
 * 提交前的本地判定：只回答“这份表单能不能发 provider-put”。
 * 任一不过就把错误留在当前卡内可见；全过时由调用方发出请求并等它自己的结论，
 * 这里不发保存、不收卡（保存失败不该把草稿一起丢了）。
 *
 * 判据顺序：连接 → 字段合法性 → 本次连接改动的测试要求。
 */
private fun validateForSave(
    connected: Boolean,
    id: String,
    name: String?,
    baseUrl: String?,
    apiKey: String?,
    parsed: ParsedProviderModels,
    saveGateMessage: String?,
): String? {
    if (!connected) return "未连接 Pi 网关，保存发不出去。"
    if (id.trim().isEmpty()) return "ID 不能为空"
    if (parsed.error != null) return parsed.error
    val invalid = runCatching {
        // 与网关同口径的兜底校验（id 必填、baseUrl/apiKey 至少其一、模型 id 不重复、
        // name 过长…）：能在本地拦住的先拦住，拦不住的由回执留在卡内。
        requireValidProviderUpsert(
            id = id.trim(),
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = parsed.rows,
            name = name,
        )
        null
    }.getOrElse { it.message ?: "表单校验失败" }
    return invalid ?: saveGateMessage
}

/**
 * Provider 模型行沿用选择器的主副行层次；保留 ID 辨识和独立的上移/移除操作。
 */
@Composable
private fun ModelRow(
    index: Int,
    row: ProviderModelDraft,
    attempt: ProviderTestAttempt?,
    showTestStatus: Boolean,
    enabled: Boolean,
    canMoveUp: Boolean,
    onMoveUp: () -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .testTag("finecat.modelRow.$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.id.ifBlank { "新模型（点进来填 ID）" },
                style = type.body.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                color = if (row.id.isBlank()) colors.text3 else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull(
                        row.api.ifBlank { "未选协议" },
                        "独立地址".takeIf { row.baseUrl.isNotBlank() },
                    ).joinToString(" · "),
                    style = type.xs,
                    color = colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showTestStatus) Text(
                    text = when {
                        attempt == null -> "未测试"
                        attempt.ok -> "通过"
                        attempt.httpStatus != null -> "失败 ${attempt.httpStatus}"
                        else -> "未通过"
                    },
                    style = type.xs,
                    color = when (attempt?.ok) { true -> colors.ok; false -> colors.err; null -> colors.text3 },
                    maxLines = 1,
                    modifier = Modifier.testTag("finecat.modelTestStatus.$index"),
                )
            }
        }
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FcIconButton(
                icon = FineCatIcons.ArrowUp,
                contentDescription = "上移模型 ${row.id.ifBlank { "新模型" }}",
                onClick = onMoveUp,
                enabled = enabled && canMoveUp,
                buttonSize = 48.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag("finecat.modelMoveUp.$index"),
            )
            FcIconButton(
                icon = FineCatIcons.Trash,
                contentDescription = "移除模型 ${row.id.ifBlank { "新模型" }}",
                onClick = onRemove,
                enabled = enabled,
                buttonSize = 48.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag("finecat.modelRemove.$index"),
            )
        }
    }
}

/**
 * 二级模型卡（原型 `modelDetail`：卡头 `.mv-back` 返回 Provider 卡）：模型 ID / 显示名 /
 * 上下文窗口 / 最大输出 / 协议 / 支持推理 / 输入图片 / 删除模型。
 *
 * 协议走 [ApiPopover]（原型 `mv-api-pop`）：候选贴在“方言”行上，Back 先关浮层再退卡。
 * 换协议会丢掉新协议下不存在的 compat 项，选中后先在浮层里确认才应用。
 *
 * 思考映射初始取目录档位与已有映射键；两排选择器改键/取值，显式 null 保留。
 *
 * 兼容参数走 [CompatBlock]（F12，`DEC-F09-COMPAT`）：折叠报「已设置 N 项」，展开列全量可编辑
 * 键，布尔三段、枚举走取值浮层，支持删单项与清空；三态语义见 [FineCatCompat] 的 KDoc。删除/
 * 恢复默认与未知键保留不在本轮。
 */
@Composable
private fun FineCatModelDetailSheet(
    stateHolder: AndroidSharedStateHolder,
    providerId: String,
    providerBaseUrl: String,
    row: ProviderModelDraft,
    existing: GatewayProvider?,
    idLocked: Boolean,
    testAttempt: ProviderTestAttempt?,
    testProgress: String?,
    onTest: () -> String?,
    onBack: () -> Unit,
    onChange: (ProviderModelDraft) -> Unit,
    onRemove: () -> Unit,
) {
    // 协议改成贴锚点浮层（原型 mv-api-pop）：锚点就是“方言”那一行，浮层悬在二级卡上。
    val connected = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
    var apiPop by remember { mutableStateOf(false) }
    var apiAnchor by remember { mutableStateOf(IntRect.Zero) }
    /** 有损失待确认的候选：非 null 时浮层只给确认/取消，compat 不被静默清空。 */
    var pendingApi by remember { mutableStateOf<String?>(null) }
    val loss = pendingApi?.let { FineCatProviderForm.compatLoss(row, it) }.orEmpty()
    // F11 思考映射的取值浮层挂在同一张二级卡上（原型 mp-pick → mpop）：锚点是那一列的值。
    var thinkPopLevel by remember { mutableStateOf<String?>(null) }
    var thinkAnchor by remember { mutableStateOf(IntRect.Zero) }
    var thinkError by remember { mutableStateOf<String?>(null) }
    var thinkPickKey by remember { mutableStateOf(false) }
    var thinkingColumns by remember(row.id) { mutableStateOf<List<String>?>(null) }
    var validateDetail by remember { mutableStateOf(false) }
    var testStartError by remember(row) { mutableStateOf<String?>(null) }
    val detailError = testStartError ?: if (validateDetail) FineCatProviderForm.parseModels(listOf(row), existing).error else null
    val declaredThinking = FineCatThinkingMap.declaredLevels(stateHolder.snapshot.modelCatalog, providerId, row.id)
    val thinkingLevels = thinkingColumns ?: FineCatThinkingMap.editableLevels(declaredThinking, row.thinkingMap.keys)
    // F12 compat 编辑区：展开态、枚举取值浮层。展开态跟着这张卡走（切模型即换新状态，
    // 不跨模型串草稿）；浮层锚点是那一行的取值。
    var compatExpanded by remember { mutableStateOf(false) }
    var compatPopKey by remember { mutableStateOf<String?>(null) }
    var compatAnchor by remember { mutableStateOf(IntRect.Zero) }
    // Back/返回先关浮层，再退二级卡、一级卡（focusable Popup 自己会吃 Back，这里的
    // 判定是它没吃到时的兜底：收起路径开头先看浮层在不在）
    val collapse: () -> Unit = {
        if (apiPop) {
            apiPop = false
            pendingApi = null
        } else if (compatPopKey != null) {
            compatPopKey = null
        } else if (thinkPopLevel != null) {
            thinkPopLevel = null
        } else {
            onBack()
        }
    }
    val confirmDetail: () -> Unit = {
        if (apiPop || compatPopKey != null || thinkPopLevel != null) {
            collapse()
        } else if (connected) {
            validateDetail = true
            if (FineCatProviderForm.parseModels(listOf(row), existing).error == null) onBack()
        }
    }
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    var showEffectiveUrl by remember { mutableStateOf(false) }
    FcSheet(
        visible = true,
        onDismiss = collapse,
        onOutsideClick = confirmDetail,
        title = null,
        maxHeightFraction = 0.94f,
        bottomPadding = 0.dp,
        modifier = Modifier.testTag("finecat.modelDetail"),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, bottom = 8.dp)
                    .testTag("finecat.sheetHeader"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FcIconButton(
                    icon = FineCatIcons.Back, contentDescription = "返回",
                    onClick = collapse, buttonSize = 48.dp, iconSize = 20.dp,
                    modifier = Modifier.testTag("finecat.sheetBack"),
                )
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("模型配置", style = type.body.copy(fontSize = 21.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold), color = colors.text)
                    if (idLocked) SelectionContainer {
                        Text("ID ${row.id} · 不可修改", style = type.xs, color = colors.text3,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("finecat.modelReadOnlyId"))
                    }
                }
                Button(
                    onClick = confirmDetail,
                    enabled = connected,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White,
                        disabledContainerColor = colors.surface2, disabledContentColor = colors.text3),
                    modifier = Modifier.heightIn(min = 36.dp).testTag("finecat.sheetDone"),
                ) { Text("完成", style = type.sm.copy(lineHeight = 20.sp, fontWeight = FontWeight.Medium), maxLines = 1) }
            }
            if (!connected) {
                Text("网关未连接，修改已保留在草稿中。", style = type.xs, color = colors.warn,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                        .testTag("finecat.modelDetailOffline"))
            }
            HorizontalDivider(color = colors.line.copy(alpha = 0.6f))
            detailError?.let { message ->
                Text(message, style = type.xs, color = colors.err,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }.testTag("finecat.modelDetailError"))
            }
            // FcSheet consumes IME insets; only the body scrolls beneath the fixed header and error.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(testProgress ?: if ("provider-test-id-v1" in stateHolder.gatewayState.capabilities)
                        "连接测试" else "请更新网关后测试连接", style = type.xs,
                        color = colors.text3,
                        modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Button(onClick = { testStartError = onTest() },
                        enabled = connected && row.id.isNotBlank() && !stateHolder.providerTestIsLoading &&
                            !stateHolder.providerModelsIsLoading && stateHolder.supportsRequest("provider-test") &&
                            "provider-test-id-v1" in stateHolder.gatewayState.capabilities,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                        modifier = Modifier.testTag("finecat.modelTest")) {
                        Text("测试连接", style = type.sm)
                    }
                }
                testAttempt?.let { attempt ->
                    ProviderTestFeedback(AndroidSharedStateHolder.ProviderTestOutcome(
                        attempt.ok, attempt.summary, attempts = listOf(attempt),
                    ))
                }
                Text("基本信息", style = type.sm.copy(fontWeight = FontWeight.SemiBold), color = colors.text,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp))
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .background(colors.surface2.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
                    if (!idLocked) ProviderInput(
                        label = "模型 ID", value = row.id,
                        onValueChange = { onChange(row.copy(id = it.trim(' ').replace("/", ""))) },
                        placeholder = "上游的 model id", fieldTag = "finecat.modelId",
                    )
                    ProviderInput(
                        label = "显示名", value = row.name,
                        onValueChange = { onChange(row.copy(name = it)) },
                        placeholder = "可选", fieldTag = "finecat.modelName",
                    )
                    if ("provider-model-base-url-v1" in stateHolder.gatewayState.capabilities) {
                        ProviderInput(
                            label = "Base URL", value = row.baseUrl,
                            onValueChange = { onChange(row.copy(baseUrl = it.replace("\n", "").replace("\r", ""), baseUrlTouched = true)) },
                            placeholder = "继承 Provider 地址", url = true,
                            status = if (row.baseUrl.isBlank()) "继承" else "独立地址",
                            fieldTag = "finecat.modelBaseUrl",
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showEffectiveUrl = !showEffectiveUrl },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("finecat.modelEffectiveUrlToggle"),
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.text2)) {
                                Text(if (showEffectiveUrl) "收起地址" else "查看生效地址", style = type.xs)
                            }
                            Spacer(Modifier.weight(1f))
                            FcTextButton(
                                text = "恢复继承",
                                onClick = { onChange(row.copy(baseUrl = "", baseUrlTouched = true)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("finecat.modelBaseUrlInherit"),
                                enabled = row.baseUrl.isNotBlank(),
                            )
                        }
                        if (showEffectiveUrl) SelectionContainer {
                            Text("使用地址：${row.baseUrl.trim().ifBlank { providerBaseUrl.ifBlank { "未设置" } }}",
                                style = type.xs, color = colors.text2,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                    .testTag("finecat.modelEffectiveUrl"))
                        }
                    } else {
                        FcHint(text = "网关未提供模型地址编辑；已有模型地址保持原样。")
                    }
                }
                Text("容量", style = type.sm.copy(fontWeight = FontWeight.SemiBold), color = colors.text,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp))
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .background(colors.surface2.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
                    Box(Modifier.weight(1f)) {
                        ProviderInput(label = "上下文", value = row.contextWindow,
                            onValueChange = { onChange(row.copy(contextWindow = it)) },
                            placeholder = if (idLocked) "留空保留" else "默认 128K", fieldTag = "finecat.modelContext")
                    }
                    Box(Modifier.weight(1f)) {
                        ProviderInput(label = "最大输出", value = row.maxTokens,
                            onValueChange = { onChange(row.copy(maxTokens = it)) },
                            placeholder = if (idLocked) "留空保留" else "默认 16.384K", fieldTag = "finecat.modelMax")
                    }
                }
                Text("协议与能力", style = type.sm.copy(fontWeight = FontWeight.SemiBold), color = colors.text,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp))
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .background(colors.surface2.copy(alpha = 0.55f), RoundedCornerShape(16.dp))) {
                    FcRow(
                        label = "协议",
                        value = row.api,
                        showChevron = true,
                        onClick = {
                            pendingApi = null
                            apiPop = true
                        },
                        modifier = Modifier
                            .testTag("finecat.modelApiRow")
                            // 当前方言写进 stateDescription：整行文字里还混着标签与副行，
                            // 设备用例按文字取值会读出「方言 + 副行 + 值」。
                            .semantics { stateDescription = row.api }
                            .onGloballyPositioned { coords ->
                                val bounds = coords.boundsInWindow()
                                apiAnchor = IntRect(
                                    bounds.left.roundToInt(), bounds.top.roundToInt(),
                                    bounds.right.roundToInt(), bounds.bottom.roundToInt(),
                                )
                            },
                    )

                    ToggleRow(
                        label = "支持推理",
                        checked = row.reasoning,
                        onChange = { onChange(row.copy(reasoning = it)) },
                        testTag = "finecat.modelReasoning",
                    )
                    ToggleRow(
                        label = "输入图片",
                        checked = row.imageOn,
                        onChange = { onChange(row.copy(imageOn = it)) },
                        testTag = "finecat.modelImage",
                    )
                }
                // F11 思考强度映射（原型 mpBlock）：一档一列，档名在上、连线居中、当前值在下，
                // 点值开取值浮层。列来自目录帧真实声明，见 FineCatThinkingMap 的契约说明。
                ThinkingMapBlock(
                    stateHolder = stateHolder,
                    providerId = providerId,
                    row = row,
                    openLevel = thinkPopLevel,
                    pickKey = thinkPickKey,
                    displayLevels = thinkingColumns,
                    error = thinkError,
                    onOpen = { level, pickKey ->
                        thinkError = null
                        thinkAnchor = IntRect.Zero
                        thinkPopLevel = level
                        thinkPickKey = pickKey
                    },
                    // 锚点回填与「打开」是两件事：布局变化会再次触发 onGloballyPositioned，
                    // 若在这里顺带清错误，刚显示的拒绝原因会被下一次布局冲掉。
                    onAnchor = { bounds -> thinkAnchor = bounds },
                )
                if (row.compat.isNotEmpty() || FineCatCompat.hasEditor(row.api)) {
                    // F12（DEC-F09-COMPAT）：兼容参数真正可编辑。没动过这一层时保存不下发
                    // compat 字段（网关保留电脑上那一层），动过才手机权威——见 FineCatCompat。
                    CompatBlock(
                        row = row,
                        expanded = compatExpanded,
                        openKey = compatPopKey,
                        onToggle = { compatExpanded = !compatExpanded },
                        onOpen = { compatPopKey = it },
                        onAnchor = { compatAnchor = it },
                        onChange = onChange,
                    )
                }
                FcTextButton(
                    text = "删除这个模型",
                    onClick = onRemove,
                    muted = true,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .testTag("finecat.modelDelete"),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    ApiPopover(
        row = row,
        anchor = apiAnchor,
        expanded = apiPop,
        pendingApi = pendingApi,
        loss = loss,
        onPending = { pendingApi = it },
        onDismiss = { apiPop = false },
        onChange = onChange,
    )

    // compat 枚举键的取值浮层贴在这一行上；选中即写回草稿并收起浮层（与方言浮层同一套落位）。
    val openCompatKey = compatPopKey
    if (openCompatKey != null) {
        CompatValuePopover(
            key = openCompatKey,
            current = row.compat[openCompatKey],
            anchor = compatAnchor,
            expanded = true,
            onDismiss = { compatPopKey = null },
            onPick = { picked ->
                onChange(
                    row.copy(
                        compat = FineCatCompat.setValue(row.compat, openCompatKey, picked),
                        compatTouched = true,
                    ),
                )
            },
        )
    }

    // 思考映射取值浮层贴在这一列的值上（原型 mp-pick → mpop）。放在根级调用：
    // Popup 的 composition 点即窗口左上，锚点坐标由 onGloballyPositioned 给出。
    val openLevel = thinkPopLevel
    if (openLevel != null) {
      if (thinkPickKey) {
        ThinkingKeyPopover(
            level = openLevel,
            draftMap = row.thinkingMap,
            anchor = thinkAnchor,
            onDismiss = { thinkPopLevel = null },
            onPick = { picked ->
                val next = FineCatThinkingMap.renameLevel(row.thinkingMap, thinkingLevels, openLevel, picked)
                if (next != null) {
                    thinkingColumns = FineCatThinkingMap.renamedColumns(thinkingLevels, openLevel, picked)
                    onChange(row.copy(thinkingMap = next))
                    thinkPopLevel = null
                }
            },
        )
      } else {
        ThinkingValuePopover(
            level = openLevel,
            current = FineCatThinkingMap.targetOf(row.thinkingMap, openLevel),
            values = FineCatThinkingMap.targetChoices(row.thinkingMap),
            anchor = thinkAnchor,
            onDismiss = { thinkPopLevel = null },
            onSubmit = { raw ->
                val next = FineCatThinkingMap.applyTarget(row.thinkingMap, openLevel, raw)
                if (next == null) {
                    // 自定义空值不能当作删除动作，留在卡内显示错误。
                    thinkError = FineCatThinkingMap.clearRejectedReason()
                } else {
                    thinkError = null
                    onChange(row.copy(thinkingMap = next))
                    thinkPopLevel = null
                }
            },
            onUnsupported = {
                thinkError = null
                onChange(row.copy(thinkingMap = FineCatThinkingMap.applyUnsupported(row.thinkingMap, openLevel)))
                thinkPopLevel = null
            },
        )
      }
    }
}

/** 协议候选浮层：贴在“方言”行下方，一行一个候选。 */
@Composable
private fun ApiPopover(
    row: ProviderModelDraft,
    anchor: IntRect,
    expanded: Boolean,
    pendingApi: String?,
    loss: List<String>,
    onPending: (String?) -> Unit,
    onDismiss: () -> Unit,
    onChange: (ProviderModelDraft) -> Unit,
) {
    FcPopover(
        expanded = expanded,
        onDismissRequest = {
            onPending(null)
            onDismiss()
        },
        anchorBoundsInWindow = anchor,
        modifier = Modifier.testTag("finecat.modelApiPopover"),
    ) {
        val target = pendingApi
        if (target == null) {
            PROVIDER_API_CHOICES.forEach { choice ->
                FcPopoverItem(
                    text = choice,
                    selected = choice == row.api,
                    onClick = {
                        when {
                            choice == row.api -> onDismiss()
                            FineCatProviderForm.compatLoss(row, choice).isEmpty() -> {
                                onChange(row.switchApi(choice))
                                onDismiss()
                            }
                            // 有 compat 要丢：先别应用，换成确认态
                            else -> onPending(choice)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("finecat.modelApi.$choice"),
                )
            }
        } else {
            // 应用前确认：取消就保留原协议与已配的 compat（原型这里是直接丢，
            // 手机端不静默改电脑端配好的参数）。
            // 必须逐项列出要丢的键名（`DEC-F09-DIALECT` 要求「先明确列出影响」），
            // 只报「丢 N 项」用户无从判断自己会失去什么。
            FcHint(
                text = "将删除：${loss.joinToString("、")}",
                modifier = Modifier.testTag("finecat.modelApiLoss"),
            )
            FcPopoverItem(
                text = "切到 $target（丢 ${loss.size} 项 compat）",
                onClick = {
                    onChange(row.switchApi(target))
                    onPending(null)
                    onDismiss()
                },
                modifier = Modifier.testTag("finecat.modelApiConfirm"),
            )
            FcPopoverItem(
                text = "取消，保留 ${row.api}",
                secondary = true,
                onClick = { onPending(null) },
                modifier = Modifier.testTag("finecat.modelApiCancel"),
            )
        }
    }
}

/**
 * F12 兼容参数编辑区（`DEC-F09-COMPAT`）。
 *
 * 折叠时只报事实：已设置几项 + 能否展开。展开后是**协议键集全量**（不是只列已配置的），
 * 每行三态可见：
 * - 布尔键：行内三段（未设置 / 开 / 关）。三段都在，未设置的键不会显示成「关」。
 * - 枚举键：行内显示当前取值（未设置显示「未设置」）+ 展开浮层选值；**不给自由输入**，
 *   因为网关对枚举取值做白名单校验（`normalizeCompat` 抛 bad-request），自由输入只会
 *   让人到保存时才发现整条 provider 被拒。
 *
 * 「清空手机管理的兼容参数」下发的是 `compat: {}`：只清手机可编辑的那些键，电脑上写在本层
 * 里的其他配置由网关合并时保留（`hostOnlyPrior`）。文案里明确这一点，避免用户以为整层没了。
 *
 * 未设置项走 pi 自己的默认值/自动检测，手机**不推算最终生效值**（没有下层值真源，
 * `DEC-F09-COMPAT` 明确不展示来源与有效值）。
 */
@Composable
private fun CompatBlock(
    row: ProviderModelDraft,
    expanded: Boolean,
    openKey: String?,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onAnchor: (IntRect) -> Unit,
    onChange: (ProviderModelDraft) -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    val editable = FineCatCompat.hasEditor(row.api)
    val plan = FineCatCompat.plan(row.api, row.compat)
    val setCount = plan.configured
    // 该协议没有键集时 `plan.configured` 恒为 0（没有「本协议内已设置」可言），但草稿里
    // 仍可能有别的协议留下的键；表头与提示必须说草稿的真实值，不能报 0 再自称「原样保留」。
    val draftSetCount = row.compat.values.count { it != null }
    var showHints by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
            .testTag("finecat.compatHeader"),
    ) {
        Column(Modifier.weight(1f)) {
            Text("兼容参数", style = type.sm.copy(fontWeight = FontWeight.SemiBold), color = colors.text)
            Text(
                text = "已设置 ${if (editable) setCount else draftSetCount} 项",
                style = type.xs2,
                color = colors.text2,
                modifier = Modifier.testTag("finecat.compatCount"),
            )
        }
        Text(
            text = if (expanded) "收起" else "展开",
            style = type.xs2,
            color = colors.brand,
            modifier = Modifier.testTag("finecat.compatToggle"),
        )
    }
    if (!expanded) return

    if (!editable) {
        // 有已配置项但该协议没有 compat 键集：只报事实，不假装还能编辑。
        FcHint(
            text = FineCatCompat.unsupportedHint(row.api) +
                if (draftSetCount > 0) "这一层已有 $draftSetCount 项，保存时原样保留。" else "",
            modifier = Modifier.testTag("finecat.compatEmpty"),
        )
        return
    }

    TextButton(
        onClick = { showHints = !showHints },
        colors = ButtonDefaults.textButtonColors(contentColor = colors.text2),
        modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)
            .testTag("finecat.compatHelpToggle"),
    ) { Text(if (showHints) "收起说明" else "参数说明", style = type.xs) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("finecat.compatBlock"),
    ) {
        plan.keys.forEach { key ->
            val value = row.compat[key]
            if (FineCatCompat.isEnumKey(key)) {
                CompatEnumRow(
                    key = key,
                    value = value,
                    open = openKey == key,
                    hint = if (showHints) PROVIDER_COMPAT_HINTS[key].orEmpty() else "",
                    onOpen = { onOpen(key) },
                    onAnchor = onAnchor,
                )
            } else {
                CompatBooleanRow(
                    key = key,
                    value = value,
                    hint = if (showHints) PROVIDER_COMPAT_HINTS[key].orEmpty() else "",
                    onChange = { picked ->
                        onChange(
                            row.copy(
                                compat = FineCatCompat.setValue(row.compat, key, picked),
                                // 只有真的动过才让本次对这一层权威：否则保存会下发
                                // 「手机上看到的」，而那可能不是电脑上的全部。
                                compatTouched = true,
                            ),
                        )
                    },
                )
            }
        }
        FcTextButton(
            text = "清空手机管理的兼容参数",
            onClick = {
                onChange(row.copy(compat = FineCatCompat.cleared(), compatTouched = true))
            },
            muted = true,
            enabled = draftSetCount > 0,
            modifier = Modifier
                .padding(horizontal = spacing.s5)
                .testTag("finecat.compatClearAll"),
        )
        if (plan.offProtocol.isNotEmpty() && !showHints) {
            Text("清空也会移除 ${plan.offProtocol.size} 项非当前协议参数，详见参数说明。",
                style = type.xs, color = colors.warn,
                modifier = Modifier.padding(horizontal = spacing.s5, vertical = 4.dp))
        }
        if (showHints) FcHint(
            text = buildString {
                append("清空＝保存时下发空的 compat 对象：手机管理的键会全部被删")
                if (plan.offProtocol.isEmpty()) {
                    append("；电脑上写在本层、但不由手机管理的其他配置由网关保留。")
                } else {
                    append("，包括下面这 ${plan.offProtocol.size} 项非本协议的键（")
                    append(plan.offProtocol.joinToString("、"))
                    append("）；电脑上写在本层、但不由手机管理的其他配置仍由网关保留。")
                }
                append("未设置的项走 pi 自己的默认值，手机不显示也不推算最终生效值。")
            },
            modifier = Modifier.testTag("finecat.compatHint"),
        )
    }
}

/** 布尔键一行：未设置 / 开 / 关 三段常驻（原型 pvCompat 同款），选中的那段高亮。 */
@Composable
private fun CompatBooleanRow(
    key: String,
    value: String?,
    hint: String,
    onChange: (String?) -> Unit,
) {
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s5, vertical = spacing.s1)
            .testTag("finecat.compatKey.$key"),
    ) {
        Text(
            text = key,
            style = type.xs.copy(fontFamily = type.mono),
            color = LocalFineCatColors.current.text2,
        )
        if (hint.isNotEmpty()) {
            Text(text = hint, style = type.xs2, color = LocalFineCatColors.current.text3)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            modifier = Modifier.padding(top = spacing.s1),
        ) {
            CompatSegment(
                label = FineCatCompat.UNSET,
                selected = value == null,
                onClick = { onChange(null) },
                tag = "finecat.compatSet.$key.default",
            )
            CompatSegment(
                label = FineCatCompat.ON,
                selected = value == "true",
                onClick = { onChange("true") },
                tag = "finecat.compatSet.$key.true",
            )
            CompatSegment(
                label = FineCatCompat.OFF,
                selected = value == "false",
                onClick = { onChange("false") },
                tag = "finecat.compatSet.$key.false",
            )
        }
    }
}

/** 枚举键一行：当前取值 + 展开浮层（不给自由输入，见 [CompatBlock] 的 KDoc）。 */
@Composable
private fun CompatEnumRow(
    key: String,
    value: String?,
    open: Boolean,
    hint: String,
    onOpen: () -> Unit,
    onAnchor: (IntRect) -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    // 锚点记在这里，并在**点击那一刻**再上报一次：布局期回写的坐标要到下一次组合才
    // 可见，而浮层正是被这次点击打开的——只靠布局回写会让浮层拿到零锚点而完全不出现。
    var lastBounds by remember { mutableStateOf(Rect.Zero) }
    fun reportAnchor(bounds: Rect): Unit {
        val rect = IntRect(
            bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.right.roundToInt(), bounds.bottom.roundToInt(),
        )
        onAnchor(rect)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                reportAnchor(lastBounds)
                onOpen()
            }
            .padding(horizontal = spacing.s5, vertical = spacing.s1)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                lastBounds = bounds
                // 只在这一行已经展开时才回写锚点：`open` 在这里被读走，展开那一刻会重排一次，
                // 于是浮层先拿到「开着的行」的真实窗口坐标。行还在视口外时布局给出的
                // bounds 是零，那样的锚点会让浮层直接不出现（F11 取值浮层同一手法）。
                if (open) reportAnchor(bounds)
            }
            // 浮层开合写进 stateDescription：不只靠箭头方向图示区分，也让设备用例能直接
            // 判断「点了这一行到底有没有打开取值浮层」，不必去猜浮层为什么没出现。
            .semantics { stateDescription = if (open) "取值浮层已打开" else "取值浮层未打开" }
            .testTag("finecat.compatKey.$key"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = key,
                style = type.xs.copy(fontFamily = type.mono),
                color = colors.text2,
            )
            if (hint.isNotEmpty()) {
                Text(text = hint, style = type.xs2, color = colors.text3)
            }
        }
        Text(
            text = FineCatCompat.marker(key, value),
            style = type.xs2,
            color = if (value == null) colors.text3 else colors.brand,
            modifier = Modifier.testTag("finecat.compatMarker.$key"),
        )
        Spacer(Modifier.width(spacing.s2))
        Icon(
            imageVector = if (open) FineCatIcons.Up else FineCatIcons.Down,
            contentDescription = null,
            tint = colors.text3,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * compat 行内分段按钮：选中高亮 + 边框，未选中保持安静。
 *
 * 选中态额外写进 `stateDescription`（不只靠颜色区分）：这一段全是「未设置/开/关」这种
 * 文本相近的短词，只靠底色区分对色弱用户不成立，也让设备用例只能去比对像素。
 */
@Composable
private fun CompatSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val radius = LocalFineCatRadius.current.chip
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(radius))
            .background(if (selected) colors.brandSoft else colors.surface3)
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 52.dp, minHeight = 40.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.xs2,
            color = if (selected) colors.brand else colors.text3,
        )
    }
}

/** 枚举键取值浮层：未设置 + 全部合法取值；选中项高亮。 */
@Composable
private fun CompatValuePopover(
    key: String,
    current: String?,
    anchor: IntRect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    FcPopover(
        expanded = expanded,
        onDismissRequest = onDismiss,
        anchorBoundsInWindow = anchor,
        modifier = Modifier.testTag("finecat.compatPopover"),
        focusable = false,
    ) {
        FcPopoverItem(
            text = "${FineCatCompat.UNSET}（走默认）",
            selected = current == null,
            onClick = {
                onPick(null)
                onDismiss()
            },
            modifier = Modifier.testTag("finecat.compatEnumUnset.$key"),
        )
        FineCatCompat.choices(key).forEach { choice ->
            FcPopoverItem(
                text = choice,
                selected = choice == current,
                onClick = {
                    onPick(choice)
                    onDismiss()
                },
                modifier = Modifier.testTag("finecat.compatEnumItem.$key.$choice"),
            )
        }
    }
}

/** 映射上排编辑 Pi 档位，下排编辑上游取值；改键不覆盖另一列。 */
@Composable
private fun ThinkingMapBlock(
    stateHolder: AndroidSharedStateHolder,
    providerId: String,
    row: ProviderModelDraft,
    openLevel: String?,
    pickKey: Boolean,
    displayLevels: List<String>?,
    error: String?,
    onOpen: (String, Boolean) -> Unit,
    onAnchor: (IntRect) -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val snapshot = stateHolder.snapshot
    val plan = FineCatThinkingMap.plan(
        reasoning = row.reasoning,
        declared = FineCatThinkingMap.declaredLevels(snapshot.modelCatalog, providerId, row.id),
        draftMap = row.thinkingMap,
        currentLevel = FineCatThinkingMap.currentLevelFor(
            sessionProviderId = snapshot.sessionStates[snapshot.selectedSessionId]?.model?.provider,
            sessionModelId = snapshot.sessionStates[snapshot.selectedSessionId]?.model?.modelId,
            sessionThinkingLevel = snapshot.sessionStates[snapshot.selectedSessionId]?.thinkingLevel,
            editingProviderId = providerId,
            editingModelId = row.id,
        ),
        displayLevels = displayLevels,
    )
    Text("思考强度映射", style = type.sm.copy(fontWeight = FontWeight.SemiBold), color = colors.text,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp))
    error?.let { message ->
        Text(message, style = type.xs, color = colors.err,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("finecat.thinkingError"))
    }
    val columns = (plan as? FineCatThinkingMap.Plan.Columns)?.columns
    if (columns == null) {
        Text(FineCatThinkingMap.emptyHint(plan).orEmpty(), style = type.xs, color = colors.text3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("finecat.thinkingEmpty"))
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp).testTag("finecat.thinkingMap"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        columns.forEach { column ->
            ThinkingColumn(column, expanded = openLevel == column.level, pickKey = pickKey,
                onOpen = onOpen, onAnchor = onAnchor)
        }
    }
}

@Composable
private fun ThinkingColumn(
    column: FineCatThinkingMap.Column,
    expanded: Boolean,
    pickKey: Boolean,
    onOpen: (String, Boolean) -> Unit,
    onAnchor: (IntRect) -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.testTag("finecat.thinkingCol.${column.level}")) {
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(LocalFineCatRadius.current.chip))
                .clickable(role = Role.Button) { onOpen(column.level, true) }
                .onGloballyPositioned { if (expanded && pickKey) onAnchor(it.boundsInWindow().toIntRect()) }
                .padding(horizontal = 7.dp)
                .semantics { contentDescription = if (column.isCurrent) "${column.level} 当前档" else column.level }
                .testTag("finecat.thinkingKey.${column.level}"),
            contentAlignment = Alignment.Center,
        ) {
            Text(column.level, style = type.xs.copy(fontFamily = type.mono,
                fontWeight = if (column.isCurrent) FontWeight.SemiBold else FontWeight.Normal),
                color = if (column.isCurrent) colors.brand else colors.text3, maxLines = 1)
        }
        Box(Modifier.width(1.dp).height(7.dp).background(colors.line2))
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(LocalFineCatRadius.current.chip))
                .clickable(role = Role.Button) { onOpen(column.level, false) }
                .onGloballyPositioned { if (expanded && !pickKey) onAnchor(it.boundsInWindow().toIntRect()) }
                .padding(horizontal = 7.dp)
                .semantics { contentDescription = "${column.level} 取值 ${FineCatThinkingMap.displayTarget(column.target)}" }
                .testTag("finecat.thinkingVal.${column.level}"),
            contentAlignment = Alignment.Center,
        ) {
            Text(FineCatThinkingMap.displayTarget(column.target), style = type.xs.copy(fontFamily = type.mono),
                color = if (column.isUnsupported) colors.text3 else colors.text, maxLines = 1)
        }
    }
}

@Composable
private fun ThinkingKeyPopover(
    level: String,
    draftMap: Map<String, String>,
    anchor: IntRect,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    FcPopover(expanded = true, onDismissRequest = onDismiss, anchorBoundsInWindow = anchor) {
        FineCatThinkingMap.keyChoices(draftMap, level).forEach { value ->
            FcPopoverItem(text = value, selected = value == level, onClick = { onPick(value) },
                modifier = Modifier.testTag("finecat.thinkingKeyPick.$value"))
        }
    }
}

@Composable
private fun ThinkingValuePopover(
    level: String,
    current: String,
    values: List<String>,
    anchor: IntRect,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onUnsupported: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    var custom by remember(level) { mutableStateOf(false) }
    var input by remember(level, current) {
        mutableStateOf(if (current == FineCatThinkingMap.UNSUPPORTED) "" else current)
    }
    var inputError by remember(level) { mutableStateOf<String?>(null) }
    fun applyCustom() {
        if (input.isBlank()) {
            inputError = FineCatThinkingMap.clearRejectedReason()
        } else {
            inputError = null
            onSubmit(input)
        }
    }
    FcPopover(expanded = true, onDismissRequest = onDismiss, anchorBoundsInWindow = anchor,
        onOutsideClick = if (custom) ({ applyCustom() }) else null) {
        if (custom) {
            inputError?.let { Text(it, style = type.xs, color = colors.err,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            BasicTextField(
                value = input, onValueChange = { input = it; inputError = null }, singleLine = true,
                textStyle = type.xs.copy(fontFamily = type.mono, color = colors.text),
                cursorBrush = SolidColor(colors.brand),
                decorationBox = { inner ->
                    Box(Modifier.defaultMinSize(minHeight = 48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) Text("自定义取值", style = type.xs, color = colors.text3)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("finecat.thinkingInput"),
            )
            FcPopoverItem(text = "应用", onClick = { applyCustom() }, modifier = Modifier.testTag("finecat.thinkingApply"))
        } else {
            FcPopoverItem(text = "null", selected = current == FineCatThinkingMap.UNSUPPORTED,
                onClick = onUnsupported, modifier = Modifier.testTag("finecat.thinkingNull"))
            values.forEach { value ->
                FcPopoverItem(text = value, selected = current == value, onClick = { onSubmit(value) },
                    modifier = Modifier.testTag("finecat.thinkingPick.$value"))
            }
            FcPopoverItem(text = "自定义…", onClick = { custom = true }, modifier = Modifier.testTag("finecat.thinkingCustom"))
        }
    }
}

/** 整行可点的开关行（原型 `.mv-tgl`）：语义挂在行上，FcSwitch 只做视觉。 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    testTag: String,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = type.sm, color = colors.text, modifier = Modifier.weight(1f))
        FcSwitch(checked = checked)
    }
}

/** Provider 与模型共用的堆叠输入；URL只做视觉换行，字段语义由调用方保留。 */
@Composable
private fun ProviderInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    fieldTag: String,
    placeholder: String = "",
    password: Boolean = false,
    url: Boolean = false,
    status: String? = null,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = !url,
        minLines = 1,
        maxLines = if (url) 4 else 1,
        textStyle = type.sm.copy(color = colors.text, textAlign = TextAlign.Start),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (url) KeyboardType.Uri else KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = if (password) false else null,
            imeAction = if (password) ImeAction.Done else ImeAction.Next,
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(colors.ink),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(fieldTag),
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = type.xs.copy(fontWeight = FontWeight.Medium),
                        color = if (focused) colors.brand else colors.text2)
                    status?.let {
                        Text(it, style = type.xs2, color = colors.text2,
                            modifier = Modifier.background(colors.surface2, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(colors.surface, RoundedCornerShape(10.dp))
                    .border(if (focused) 1.dp else 0.5.dp,
                        if (focused) colors.brand else colors.line.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = type.sm, color = colors.text3)
                    inner()
                }
            }
        },
    )
}

/** [androidx.compose.ui.geometry.Rect] → Compose 布局用的 [IntRect]（四边各取整）。 */
private fun androidx.compose.ui.geometry.Rect.toIntRect(): IntRect = IntRect(
    left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt(),
)
