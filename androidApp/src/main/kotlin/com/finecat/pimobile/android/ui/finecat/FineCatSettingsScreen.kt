package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcSheetHost
import com.finecat.pimobile.android.ui.finecat.components.FcHint
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FcRowDivider
import com.finecat.pimobile.android.ui.finecat.components.FcSectionLabel
import com.finecat.pimobile.android.ui.finecat.components.FcSettingCard
import com.finecat.pimobile.android.ui.finecat.components.FcSettingRow
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayPairingPayloadParser

/**
 * 设置**整页**（原型 `screenSettings`）。
 *
 * 形态照原型：居中标题「设置」+ 左侧返回箭头（`appHead({ title:'设置', center:true })`），
 * 主体是一个纵向滚动的 `.scroll`，按「小节标题 `.sec-label` → 圆角卡片 `.card` → 卡内
 * 行 `.row`」组织：先「网关」，再「模型与 Provider」。
 *
 * 与旧的 `FineCatGatewaySheet`（底部弹卡）的差别是**形态**：原型设置是独立整页，
 * 入口管理、配对和 Provider 表单放在二级 sheet 上。
 */
@Composable
internal fun FineCatSettingsScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenManualPairing: () -> Unit,
    onEditProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    val connection = stateHolder.gatewayState.connection
    val connected = connection == GatewayConnectionState.CONNECTED
    val providersSupported = stateHolder.supportsRequest("providers")

    LaunchedEffect(connection) {
        if (connected && stateHolder.supportsRequest("providers")) stateHolder.loadProviders()
    }
    var entriesOpen by remember { mutableStateOf(false) }
    var scopeOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .testTag("finecat.settingsScreen"),
    ) {
        // 头部：左侧返回 + 居中标题（原型 center:true）
        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
            FcIconButton(
                onClick = onBack,
                icon = FineCatIcons.Back,
                contentDescription = "返回",
                solid = true,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = spacing.s5)
                    .testTag("finecat.settingsBack"),
            )
            Text(
                text = "设置",
                style = type.body,
                color = colors.text,
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag("finecat.settingsTitle"),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 原型 `.scroll { padding: 0 var(--gutter) var(--s5) }`
                .padding(horizontal = spacing.gutter),
        ) {
            FcSectionLabel(text = "网关", topPadding = 12.dp)
            FcSettingCard(modifier = Modifier.testTag("finecat.gwCard1")) {
                FcSettingRow(
                    label = "网关地址",
                    lead = FineCatIcons.Globe,
                    value = gatewayAddressLabel(stateHolder.gatewayState.endpoint
                        ?: stateHolder.endpointCandidates.firstOrNull()),
                    valueMono = true,
                    onClick = { entriesOpen = true },
                    modifier = Modifier.testTag("finecat.gwAddress"),
                )
                FcRowDivider()
                FcSettingRow(
                    label = "网关延迟",
                    lead = FineCatIcons.Activity,
                    value = if (connected) stateHolder.pingResult ?: "点击测速" else "未连接",
                    showChevron = false,
                    onClick = stateHolder::pingGateway,
                    modifier = Modifier.testTag("finecat.gwPing"),
                )
                FcRowDivider()
                FcSettingRow(
                    label = if (connected) "断开连接" else "重新连接",
                    lead = if (connected) FineCatIcons.Logout else FineCatIcons.Plus,
                    showChevron = false,
                    onClick = { if (connected) stateHolder.disconnect() else stateHolder.connect() },
                    modifier = Modifier.testTag("finecat.gwDisconnect"),
                )
                FcRowDivider()
                FcSettingRow(
                    label = "扫码配对",
                    lead = FineCatIcons.Qr,
                    onClick = onOpenScanner,
                    modifier = Modifier.testTag("finecat.openScanner"),
                )
                FcRowDivider()
                FcSettingRow(
                    label = "手动输入配对信息",
                    lead = FineCatIcons.Edit,
                    onClick = onOpenManualPairing,
                    modifier = Modifier.testTag("finecat.openManualPairing"),
                )
            }

            // ---- 模型与 Provider（原型二级小节；一级卡列表在整页，表单仍走 sheet）----
            FcSectionLabel(text = "模型与 Provider", topPadding = 24.dp)
            FcSettingCard {
                FcSettingRow(
                    label = "scope-model",
                    lead = FineCatIcons.Cloud,
                    onClick = { scopeOpen = true },
                    modifier = Modifier.testTag("finecat.modelScopeEntry"),
                )
            }
            Spacer(Modifier.height(12.dp))
            // Provider 列表（原型 `.card.rows`：每个 provider 一行 + 「添加 Provider」行）
            FcSettingCard {
                val providers = stateHolder.providers
                if (stateHolder.providerListIsLoading) {
                    Text("正在同步…", style = type.xs, color = colors.text3, modifier = Modifier.padding(20.dp))
                }
                if (providers.isEmpty() && !stateHolder.providerListIsLoading) {
                    Text(
                        text = when {
                            !connected -> "未连接网关，读不到电脑端的 models.json。"
                            !providersSupported -> "网关不支持 Provider 管理，请升级电脑端网关。"
                            else -> "还没有 provider，点下面「添加 Provider」添加。"
                        },
                        style = type.xs,
                        color = colors.text3,
                        modifier = Modifier.padding(20.dp),
                    )
                }
                providers.forEachIndexed { index, provider ->
                    if (index > 0) FcRowDivider()
                    FcSettingRow(
                        label = provider.name ?: provider.id,
                        lead = FineCatIcons.Cloud,
                        subtitle = "models.json · " + providerRowSubtitle(provider),
                        // 缺 key 标红（原型 `<span class="tag bad">缺 key</span>`，不是普通文字）
                        tag = if (provider.apiKeySet) null else "缺 key",
                        onClick = { onEditProvider(provider.id) },
                        modifier = Modifier.testTag("finecat.providerRow.${provider.id}"),
                    )
                }
                if (providersSupported) {
                    if (providers.isNotEmpty()) FcRowDivider()
                    FcSettingRow(
                        label = "添加 Provider",
                        lead = FineCatIcons.Plus,
                        onClick = { onEditProvider(NEW_PROVIDER_ID) },
                        modifier = Modifier.testTag("finecat.providerNew"),
                    )
                }
            }
            // 保存/删除/测试连接回执在整页可见（原来在 provider sheet 内）
            stateHolder.providerResult?.let { result ->
                Text(
                    text = result,
                    style = type.xs,
                    color = providerReceiptColor(result),
                    modifier = Modifier
                        .padding(vertical = spacing.s2)
                        .testTag("finecat.providerResult"),
                )
            }
            FcHint(
                text = "Provider 可在这里编辑。输入框的模型菜单遵循 Pi 已启用的模型范围。",
            )
            Spacer(modifier = Modifier.height(spacing.s5))
        }
    }
    FcSheetHost(visible = entriesOpen) {
        FineCatGatewayEntriesSheet(
            stateHolder = stateHolder,
            onDismiss = { entriesOpen = false },
        )
    }
    FcSheetHost(visible = scopeOpen) {
        FineCatModelScopeSheet(stateHolder = stateHolder, onDismiss = { scopeOpen = false })
    }
}


/** 多入口管理独立弹层；扫码与手动配对在设置网关卡同级展示。 */
@Composable
private fun FineCatGatewayEntriesSheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    val connection = stateHolder.gatewayState.connection
    var newEndpoint by remember { mutableStateOf("") }
    var submittedEndpoint by remember { mutableStateOf<String?>(null) }
    var closeAfterAdd by remember { mutableStateOf(false) }
    var addSucceeded by remember { mutableStateOf(false) }
    // 加入入口的行内错误（空/重复这类本地可判的）：显示在入口卡下方，不弹全局
    // 模态——platformError 的确认键是「重新连接」，对例行校验错误有误导（F06 同类教训）。
    // 常规入口补齐 scheme/path；非法输入行内提示并保留草稿。
    var endpointError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stateHolder.endpointCandidates, submittedEndpoint, addSucceeded) {
        val submitted = submittedEndpoint
        if (addSucceeded && submitted != null && submitted in stateHolder.endpointCandidates) {
            val sameDraft = normalizeGatewayEndpoint(newEndpoint) == submitted
            if (sameDraft) newEndpoint = ""
            submittedEndpoint = null
            addSucceeded = false
            val shouldClose = closeAfterAdd && sameDraft
            closeAfterAdd = false
            if (shouldClose) onDismiss()
        }
    }

    fun addEntry(closeOnSuccess: Boolean) {
        if (submittedEndpoint != null) {
            if (closeOnSuccess) closeAfterAdd = true
            endpointError = if (normalizeGatewayEndpoint(newEndpoint) == submittedEndpoint)
                "地址正在保存，请稍候"
            else "上一条地址待确认，当前输入未提交"
            return
        }
        if (closeOnSuccess && newEndpoint.isBlank()) { onDismiss(); return }
        val candidate = normalizeGatewayEndpoint(newEndpoint)
        endpointError = when {
            newEndpoint.isBlank() -> "地址为空"
            candidate == null -> "请输入有效的 IP 或主机名:端口"
            candidate in stateHolder.endpointCandidates -> "该地址已在列表里"
            else -> null
        }
        if (endpointError == null && candidate != null) {
            submittedEndpoint = candidate
            closeAfterAdd = closeOnSuccess
            addSucceeded = false
            stateHolder.addEndpointCandidate(candidate) { error ->
                if (error == null) addSucceeded = true
                else {
                    submittedEndpoint = null
                    closeAfterAdd = false
                    endpointError = if (normalizeGatewayEndpoint(newEndpoint) == candidate) error
                        else "此前添加地址未确认；新输入尚未保存。"
                }
            }
        }
    }

    FcSheet(
        visible = true,
        title = "网关入口",
        showDone = true,
        onDone = { addEntry(closeOnSuccess = true) },
        onDismiss = onDismiss,
        onOutsideClick = { addEntry(closeOnSuccess = true) },
        modifier = Modifier.testTag("finecat.gatewayEntriesSheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.gutter),
        ) {
            // 入口卡（原型第二张卡）：顶部 .field 输入行 → 入口列表（顺序即尝试顺序）
            // → 「把当前地址加入列表」。分线是通栏（原型 .gw/添加行是元素自身 border-top）。
            FcSettingCard(modifier = Modifier.testTag("finecat.gwCard2")) {
                // 地址草稿只在本弹层保留，失败可继续编辑，提交成功后清空。
                GatewayEndpointInput(
                    value = newEndpoint,
                    // 输入缓冲只留本地态：不再同步写 holder.endpoint——它是
                    // 「当前地址/连接目标」（prefs 快照+runtime），打字改写会让
                    // 首页摘要与重连目标漂移（初审 M1、二轮 N-2）
                    onValueChange = {
                        newEndpoint = it
                        // 在途标记归属于已提交的地址，不能被后续输入清掉或借给新稿。
                        endpointError = null
                    },
                    modifier = Modifier.testTag("finecat.endpointInput"),
                )
                FcRowDivider(startInset = 0.dp)
                stateHolder.endpointCandidates.forEachIndexed { index, endpoint ->
                    if (index > 0) FcRowDivider(startInset = 0.dp)
                    GatewayEndpointRow(
                        index = index,
                        endpoint = endpoint,
                        connected = connection == GatewayConnectionState.CONNECTED &&
                            stateHolder.gatewayState.endpoint == endpoint,
                        onMoveUp = { stateHolder.moveEndpointCandidateUp(index) },
                        onRemove = { stateHolder.removeEndpointCandidate(index) },
                        modifier = Modifier.testTag("finecat.endpointRow.$index"),
                        moveUpTag = "finecat.endpointMoveUp.$index",
                        removeTag = "finecat.endpointRemove.$index",
                    )
                }
                FcRowDivider(startInset = 0.dp)
                FcSettingRow(
                    label = "把当前地址加入列表",
                    lead = FineCatIcons.Plus,
                    onClick = { addEntry(closeOnSuccess = false) },
                    modifier = Modifier.testTag("finecat.endpointAdd"),
                )
            }
            endpointError?.let { message ->
                Text(
                    text = message,
                    style = type.xs,
                    color = colors.err,
                    modifier = Modifier
                        .padding(vertical = spacing.s2)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("finecat.endpointError"),
                )
            }
            FcHint(
                text = "多个入口按列表顺序尝试（例：1 在家局域网、2 在外 Tailscale）；" +
                    "凭据是设备级的，切换入口不用重新配对。",
            )


        }
    }
}

/** 扫码与手动输入共用真实配对链路；失败保留输入，取消返回设置。 */
@Composable
internal fun FineCatManualPairingSheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val connection = stateHolder.gatewayState.connection
    var pairingError by remember { mutableStateOf<String?>(null) }
    var didAttemptPair by remember { mutableStateOf(false) }
    val pairingInFlight = connection in setOf(
        GatewayConnectionState.CONNECTING,
        GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK,
    )
    val canPair = stateHolder.pairingPayload.isNotBlank() && !pairingInFlight
    FcSheet(
        visible = true,
        title = "手动配对",
        showDone = true,
        onDone = onDismiss,
        onDismiss = onDismiss,
        modifier = Modifier.testTag("finecat.manualPairingSheet"),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            FcSettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = stateHolder.pairingPayload,
                        onValueChange = {
                            stateHolder.pairingPayload = it
                            // 继续编辑即清除上次行内错误与提交标记
                            pairingError = null
                            didAttemptPair = false
                        },
                        singleLine = true,
                        textStyle = type.sm.copy(fontFamily = FontFamily.Monospace, color = colors.text),
                        cursorBrush = SolidColor(colors.brand),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (stateHolder.pairingPayload.isEmpty()) {
                                    Text(
                                        text = "配对信息",
                                        style = type.sm.copy(fontFamily = FontFamily.Monospace),
                                        color = colors.text3,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("finecat.pairingInput"),
                    )
                    FcTextButton(
                        text = "配对",
                        enabled = canPair,
                        modifier = Modifier.testTag("finecat.pairingSubmit"),
                        onClick = {
                            val trimmed = stateHolder.pairingPayload.trim()
                            // 本地先解析：格式错直接行内报错，不发网、不清输入
                            val validation = runCatching {
                                GatewayPairingPayloadParser.parse(trimmed, System.currentTimeMillis())
                            }
                            if (validation.isFailure) {
                                pairingError = validation.exceptionOrNull()?.message ?: "配对信息无效"
                                didAttemptPair = true
                            } else {
                                pairingError = null
                                didAttemptPair = true
                                stateHolder.pairingPayload = trimmed
                                stateHolder.pair(
                                    payload = trimmed,
                                    reportFailureGlobally = false,
                                    onFailure = { pairingError = it },
                                )
                            }
                        },
                    )
                }
            }
            pairingOutcomeText(
                connection = connection,
                didAttemptPair = didAttemptPair,
                validationError = pairingError,
                lastError = stateHolder.gatewayState.lastError,
            )?.let { outcome ->
                Text(
                    text = outcome.text,
                    style = type.xs,
                    color = if (outcome.isError) colors.err else colors.ok,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .testTag("finecat.pairingOutcome"),
                )
            }

        }
    }
}

/** 「新增 Provider」的哨兵 id（表单层据此判断是新建还是编辑既有）。 */
internal const val NEW_PROVIDER_ID = "__new__"

/**
 * 网关地址输入行（原型 `.field` + `.field input`）：无边框、无填充，等宽字体，
 * padding 16/20（`--s4 --s5`），placeholder 用弱色。整行都是这只输入的命中区。
 */
@Composable
private fun GatewayEndpointInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = type.sm.copy(fontFamily = FontFamily.Monospace, color = colors.text),
        cursorBrush = SolidColor(colors.brand),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "192.0.2.10:3081",
                        style = type.sm.copy(fontFamily = FontFamily.Monospace),
                        color = colors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
        modifier = modifier
            .fillMaxWidth()
            // 原型 aria-label="网关地址"
            .semantics { contentDescription = "网关地址" },
    )
}

/** 地址优先占宽，动作靠右无额外间隙；各48dp触控区不重叠，首项上移禁用。 */
@Composable
private fun GatewayEndpointRow(
    index: Int,
    endpoint: String,
    connected: Boolean,
    onMoveUp: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    moveUpTag: String = "finecat.endpointMoveUp.$index",
    removeTag: String = "finecat.endpointRemove.$index",
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = gatewayAddressLabel(endpoint),
                style = type.sm.copy(fontFamily = FontFamily.Monospace),
                color = colors.text,
            )
            if (connected) {
                Text("● 当前已连接", style = type.xs, color = colors.ok,
                    modifier = Modifier.testTag("finecat.endpointConnected.$index"))
            }
        }
        Row {
            FcIconButton(
                icon = FineCatIcons.ArrowUp,
                contentDescription = "上移第 ${index + 1} 个网关入口",
                onClick = onMoveUp,
                enabled = index > 0,
                buttonSize = 48.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag(moveUpTag),
            )
            FcIconButton(
                icon = FineCatIcons.Trash,
                contentDescription = "删除第 ${index + 1} 个网关入口",
                onClick = onRemove,
                buttonSize = 48.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag(removeTag),
            )
        }
    }
}
