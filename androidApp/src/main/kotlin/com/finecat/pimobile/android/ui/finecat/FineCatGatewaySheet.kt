package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.finecat.components.FcRowDivider
import com.finecat.pimobile.android.ui.finecat.components.FcSectionLabel
import com.finecat.pimobile.android.ui.finecat.components.FcSettingCard
import com.finecat.pimobile.android.ui.finecat.components.FcSettingRow
import com.finecat.pimobile.android.ui.finecat.components.FcStatusTag
import com.finecat.pimobile.android.ui.finecat.components.FcTagStyle
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayPairingPayloadParser

/**
 * P9 网关设置 sheet（S11-G）：连接/断开/重连 + 配对（粘贴 token，扫码随 P8 相机
 * 链路）+ 地址输入与入口列表（增/上移/删，顺序即尝试顺序）。状态行从连接事实读取
 * （gatewayState.connection），不点击后手工置 live；Ping 只显发送反馈（无 RTT，
 * 02 §6 C8）；断开与清凭据不同动作（不顺便删 token）。
 *
 * 配对语义（旧 ManualGatewayPairingSheet 策略）：提交前本地先过
 * GatewayPairingPayloadParser，格式错直接行内报错不发网；提交走
 * holder.pair(payload, reportFailureGlobally=false, onFailure)，失败只行内报错、
 * 输入保留（不清框）；连接状态仅展示当前事实，不把已有 CONNECTED 当作本次配对成功。
 * 配对 secret 只放内存态（holder.pairingPayload），成功后由 holder 清空，
 * 本文件不做任何持久化。
 */
@Composable
internal fun FineCatGatewaySheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit,
    onOpenProviders: () -> Unit = {},
    /** 扫码配对入口：全屏 GatewayQrScannerScreen 需挂在生产根层，接线前默认空实现（点之无副作用）。 */
    onOpenScanner: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    var newEndpoint by remember { mutableStateOf("") }
    // 配对输入直绑 holder.pairingPayload（单一真相源，无本地复制）：失败时 holder
    // 不清空→输入保留；成功时 holder 按相等才清空→输入跟随清空。
    var pairingError by remember { mutableStateOf<String?>(null) }
    var didAttemptPair by remember { mutableStateOf(false) }
    val connection = stateHolder.gatewayState.connection
    val connected = connection == GatewayConnectionState.CONNECTED
    // 配对提交中：连接机进入握手态（旧手动配对页同口径），此时禁重按。
    val pairingInFlight = connection in setOf(
        GatewayConnectionState.CONNECTING,
        GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK,
    )
    val canPair = stateHolder.pairingPayload.isNotBlank() && !pairingInFlight

    FcSheetHost(
        visible = true,
        onDismiss = onDismiss,
        title = "网关",
        showDone = true,
        onDone = onDismiss,
        modifier = modifier.testTag("finecat.gatewaySheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 原型 `.scroll { padding: 0 var(--gutter) var(--s5) }`：卡片左右要留
                // gutter 边距，否则圆角卡片贴到屏幕两沿、发丝线被裁。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalFineCatSpacing.current.gutter),
        ) {
            // 「网关」小节：连接状态 / Ping / 断开三行，收在一张圆角卡片里
            // （原型 `.sec-label` + `.card.rows` + `.row`）。行是 lead·label·value·箭头
            // 四段，行间有 1px 发丝线。
            FcSectionLabel(text = "网关")
            FcSettingCard {
                FcSettingRow(
                    label = if (connected) "已连接" else "未连接",
                    // 原型这一行的 lead 是 `<span class="dot ok">`（7px 绿点），不是图标
                    leadDot = if (connected) colors.ok else colors.text3,
                    // serverPort 是网关自报端口（hello 帧），可能为空——空时不硬凑数字
                    value = stateHolder.gatewayState.serverPort?.let { "Port $it" },
                    valueColor = colors.text2,
                    onClick = { if (connected) stateHolder.disconnect() else stateHolder.connect() },
                    modifier = Modifier.testTag("finecat.gwToggle"),
                )
                FcRowDivider()
                FcSettingRow(
                    label = "Ping 网关",
                    lead = FineCatIcons.Activity,
                    // 未测过显示「未测试」；有结果就显示结果（不额外造"稳定/抖动"判断）
                    value = stateHolder.pingResult ?: "未测试",
                    onClick = stateHolder::pingGateway,
                    modifier = Modifier.testTag("finecat.gwPing"),
                )
                FcRowDivider()
                FcSettingRow(
                    label = if (connected) "断开连接" else "重新连接",
                    lead = FineCatIcons.Logout,
                    onClick = { if (connected) stateHolder.disconnect() else stateHolder.connect() },
                    modifier = Modifier.testTag("finecat.gwDisconnect"),
                )
            }
            // 入口列表（顺序即尝试顺序；第一行无上移）
            stateHolder.endpointCandidates.forEachIndexed { index, endpoint ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = type.xs,
                        color = colors.text3,
                        modifier = Modifier.testTag("finecat.endpoint.idx.$index"),
                    )
                    Text(
                        text = endpoint,
                        style = type.sm.copy(fontFamily = FontFamily.Monospace),
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (index > 0) {
                        FcTextButton(text = "上移", onClick = { stateHolder.moveEndpointCandidateUp(index) })
                    }
                    FcTextButton(text = "删除", onClick = { stateHolder.removeEndpointCandidate(index) })
                }
            }
            // 地址输入 + 加入列表
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = newEndpoint,
                    onValueChange = {
                        newEndpoint = it
                        stateHolder.endpoint = it
                    },
                    singleLine = true,
                    textStyle = type.sm.copy(fontFamily = FontFamily.Monospace, color = colors.text),
                    placeholder = {
                        Text(text = "ws://网关地址/ws/mobile", style = type.sm, color = colors.text3)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("finecat.endpointInput"),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FcTextButton(
                    text = "把当前地址加入列表",
                    onClick = {
                        stateHolder.addEndpointCandidate()
                        newEndpoint = ""
                    },
                )
            }
            // 配对（粘贴配对信息；扫码入口待生产根接线 onOpenScanner）
            Text(
                text = "配对（粘贴网关配对信息）",
                style = type.xs2.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = stateHolder.pairingPayload,
                    onValueChange = {
                        stateHolder.pairingPayload = it
                        // 继续编辑即清除上次行内错误和提交标记。
                        pairingError = null
                        didAttemptPair = false
                    },
                    singleLine = true,
                    textStyle = type.sm.copy(fontFamily = FontFamily.Monospace, color = colors.text),
                    placeholder = {
                        Text(text = "配对信息", style = type.sm, color = colors.text3)
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
                        // 本地先解析：格式错直接行内报错，不发网、不清输入。
                        val validation = runCatching {
                            GatewayPairingPayloadParser.parse(trimmed, System.currentTimeMillis())
                        }
                        if (validation.isFailure) {
                            pairingError = validation.exceptionOrNull()?.message ?: "配对信息无效"
                            didAttemptPair = true
                        } else {
                            pairingError = null
                            didAttemptPair = true
                            // 去空格后写回 holder 再提交（成功分支按相等清空内存，失败不动输入）。
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
            // 扫码入口（真实 GatewayQrScannerScreen 由生产根按 onOpenScanner 接线）
            FcTextButton(
                text = "扫码配对",
                onClick = onOpenScanner,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .testTag("finecat.openScanner"),
            )
            // 结果行只确认已发起请求；已有连接不能证明本次配对成功。
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
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .testTag("finecat.pairingOutcome"),
                )
            }
            Text(
                text = "断开仅断开连接，不清除设备凭据",
                style = type.xs2,
                color = colors.text3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            // P10a Provider 列表入口（S11-P 一级弹卡在网关 sheet 之上）
            FcTextButton(
                text = "Provider 配置",
                onClick = onOpenProviders,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .testTag("finecat.openProviders"),
            )
        }
    }
}

/** 校验错误优先；连接状态不带配对请求身份，因此不据 CONNECTED 宣告本次配对成功。 */
internal data class PairingOutcomeText(val text: String, val isError: Boolean)

internal fun pairingOutcomeText(
    connection: GatewayConnectionState,
    didAttemptPair: Boolean,
    validationError: String?,
    lastError: String?,
): PairingOutcomeText? {
    if (validationError != null) return PairingOutcomeText("配对信息无效：$validationError", true)
    if (!didAttemptPair) return null
    return when (connection) {
        GatewayConnectionState.CONNECTED ->
            PairingOutcomeText("已发起配对，请核对上方连接状态。", false)
        GatewayConnectionState.CONNECTING,
        GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK ->
            PairingOutcomeText("正在提交配对信息…", false)
        GatewayConnectionState.FAILED -> PairingOutcomeText(
            when (lastError) {
                "pairing-code-invalid" -> "配对码已过期或已被使用，请在电脑上重新生成一条。"
                "endpoint-unreachable" -> "列表里的地址都没连上；检查备用地址顺序（在家局域网优先，外出 Tailscale）。"
                else -> lastError ?: "请检查配对信息后重新连接。"
            },
            true,
        )
        GatewayConnectionState.DISCONNECTED,
        GatewayConnectionState.SUSPENDED -> null
    }
}

/** FcSheet 包装（避免 FineCatGatewaySheet 直接依赖 components 命名冲突）。 */
@Composable
private fun FcSheetHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    showDone: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    com.finecat.pimobile.android.ui.finecat.components.FcSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        showDone = showDone,
        onDone = onDone,
        modifier = modifier,
        content = { content() },
    )
}
