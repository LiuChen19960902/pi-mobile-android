package com.finecat.pimobile.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import java.util.Locale

/** 外观三选一：mode -> 展示文案。 */
internal val AppearanceOptions = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")

internal fun appearanceLabel(mode: String): String =
    AppearanceOptions.firstOrNull { it.first == mode }?.second ?: "跟随系统"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit
) {
    // 移除实色整页底，让根背景的玻璃动效透出；顶栏同步透明。
    LaunchedEffect(stateHolder.gatewayState.connection) { stateHolder.refreshProductState() }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarIconButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = "返回",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    SettingsSection(
                        title = "Mobile Gateway",
                        footer = "多个入口按列表顺序尝试（例：1 在家局域网、2 在外 Tailscale）；"
                            + "凭据是设备级的，切换入口不用重新配对。"
                    ) {
                        GatewayEndpointRow(stateHolder)
                        SettingsDivider()
                        GatewayCandidateRows(stateHolder)
                        SettingsDivider()
                        GatewayStatusRow(stateHolder)
                        SettingsDivider()
                        SettingsActionRow(if (isConnected(stateHolder)) "断开连接" else "连接") {
                            if (isConnected(stateHolder)) stateHolder.disconnect() else stateHolder.connect()
                        }
                        SettingsDivider()
                        SettingsActionRow("Ping 网关", enabled = isConnected(stateHolder), onClick = stateHolder::pingGateway)
                        stateHolder.pingResult?.let { result ->
                            Text(result, color = PiColors.Success, fontSize = 13.sp)
                        }
                    }
                    SettingsSection(
                        title = "外观",
                        footer = "界面外观即时生效；应用跟随系统语言，无单独语言开关。"
                    ) {
                        var appearanceDialog by remember { mutableStateOf(false) }
                        SettingsValueRow("界面", appearanceLabel(stateHolder.appearanceMode)) {
                            appearanceDialog = true
                        }
                        if (appearanceDialog) {
                            AlertDialog(
                                onDismissRequest = { appearanceDialog = false },
                                // 独立窗口不跨窗口采样，用 strong 玻璃底保证可读。
                                containerColor = piGlassTint(strong = true),
                                title = { Text("界面") },
                                text = {
                                    Column {
                                        AppearanceOptions.forEach { (mode, label) ->
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .clickable {
                                                        stateHolder.updateAppearanceMode(mode)
                                                        appearanceDialog = false
                                                    },
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = stateHolder.appearanceMode == mode,
                                                    onClick = {
                                                        stateHolder.updateAppearanceMode(mode)
                                                        appearanceDialog = false
                                                    }
                                                )
                                                Text(label, fontSize = 14.sp)
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    Text("完成", fontSize = 14.sp, modifier = Modifier.padding(12.dp).clickable { appearanceDialog = false })
                                }
                            )
                        }
                        SettingsDivider()
                        SettingsValueRow("语言", Locale.getDefault().displayLanguage.ifBlank { "简体中文" })
                    }
                    ProviderManagementSection(stateHolder)
                    stateHolder.platformError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    Spacer(Modifier.padding(bottom = 20.dp))
                }
            }
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 20.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f)
        )
        Column(
            Modifier.fillMaxWidth()
                .background(piGlassTint(), RoundedCornerShape(12.dp))
                .border(piGlassOutline(), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) { content() }
        footer?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 20.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f)
            )
        }
    }
}

@Composable
private fun SettingsValueRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val rowAlpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha), fontSize = 14.sp)
        Spacer(Modifier.weight(1f).padding(horizontal = 6.dp))
        if (isLoading) {
            PiSpinner(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                fontSize = 12.sp
            )
        } else {
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.52f else 0.3f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onClick != null) {
            Text(
                "  ›",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.30f else 0.15f),
                fontSize = 20.sp
            )
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    )
}

@Composable
private fun GatewayEndpointRow(stateHolder: AndroidSharedStateHolder) {
    BasicTextField(
        value = stateHolder.endpoint,
        onValueChange = { stateHolder.endpoint = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (stateHolder.endpoint.isBlank()) {
                    Text(
                        "ws://host:3080/ws/mobile",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                        fontSize = 14.sp
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun GatewayCandidateRows(stateHolder: AndroidSharedStateHolder) {
    val candidates = stateHolder.endpointCandidates
    if (candidates.isEmpty()) {
        Text(
            "暂无备用入口：只在下面「把当前地址加入列表」后才会按顺序重试多个地址。",
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
            fontSize = 14.sp,
            lineHeight = 19.sp
        )
    } else {
        candidates.forEachIndexed { index, url ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "${index + 1}",
                    modifier = Modifier.widthIn(min = 14.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                    fontSize = 14.sp
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        url,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (url == stateHolder.gatewayState.endpoint &&
                        stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
                    ) {
                        Text("当前已连接", color = PiColors.Success, fontSize = 11.sp)
                    }
                }
                Text(
                    "↑",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        .clickable(enabled = index > 0) { stateHolder.moveEndpointCandidateUp(index) },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (index > 0) 0.66f else 0.22f),
                    fontSize = 17.sp
                )
                Text(
                    "✕",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        .clickable { stateHolder.removeEndpointCandidate(index) },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    fontSize = 15.sp
                )
            }
            if (index != candidates.lastIndex) SettingsDivider()
        }
    }
    SettingsActionRow("把当前地址加入列表", onClick = stateHolder::addEndpointCandidate)
}

@Composable
private fun GatewayStatusRow(stateHolder: AndroidSharedStateHolder) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(9.dp).background(statusColor(stateHolder), CircleShape))
        Text(connectionLabel(stateHolder), fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        stateHolder.gatewayState.serverPort?.let { port ->
            Text(
                "Port $port",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
internal fun SettingsActionRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f),
            fontSize = 14.sp
        )
    }
}

private fun isConnected(holder: AndroidSharedStateHolder) = holder.gatewayState.connection == GatewayConnectionState.CONNECTED

private fun connectionLabel(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> "已连接"
    GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING -> "连接中"
    GatewayConnectionState.WAITING_FOR_NETWORK -> "等待网络"
    GatewayConnectionState.FAILED -> "连接失败"
    GatewayConnectionState.SUSPENDED -> "已挂起"
    GatewayConnectionState.DISCONNECTED -> "未连接"
}

@Composable
private fun statusColor(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> PiColors.Success
    GatewayConnectionState.FAILED -> Color.Red
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
}
