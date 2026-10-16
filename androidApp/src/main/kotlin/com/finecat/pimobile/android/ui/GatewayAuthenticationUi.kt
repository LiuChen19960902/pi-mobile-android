package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.gateway.GatewayErrorText

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayPairingPayloadParser
import com.finecat.pimobile.shared.gateway.GatewayRuntimeState
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualGatewayPairingSheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var pairingText by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var didAttemptConnection by rememberSaveable { mutableStateOf(false) }
    val isConnecting = stateHolder.gatewayState.connection in setOf(
        GatewayConnectionState.CONNECTING,
        GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK
    )
    val canSubmit = pairingText.isNotBlank() && !isConnecting
    val dismissWithAnimation = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
        Unit
    }

    ModalBottomSheet(
        onDismissRequest = dismissWithAnimation,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        // 独立窗口不跨窗口采样：strong 玻璃底保证可读。
        containerColor = piGlassTint(strong = true),
        contentColor = MaterialTheme.colorScheme.onBackground,
        scrimColor = Color.Black.copy(alpha = 0.22f),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                .navigationBarsPadding().imePadding()
                .testTag("gateway-manual-sheet")
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("设备认证", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Surface(
                    modifier = Modifier.align(Alignment.CenterEnd).height(48.dp)
                        .clickable(role = Role.Button, onClick = dismissWithAnimation),
                    shape = RoundedCornerShape(8.dp),
                    color = piGlassTint(),
                    shadowElevation = 0.dp
                ) {
                    Box(Modifier.padding(horizontal = 19.dp), contentAlignment = Alignment.Center) {
                        Text("完成", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("手动输入配对信息", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "粘贴 Pi WebUI 提供的 Base64URL 配对字符串。长期设备 token 仍只会安全保存到 Android Keystore。",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }

                PairingTextEditor(
                    value = pairingText,
                    onValueChange = {
                        pairingText = it
                        validationError = null
                    }
                )

                PairingResultCard(
                    presentation = pairingResultPresentation(
                        state = stateHolder.gatewayState,
                        didAttemptConnection = didAttemptConnection,
                        validationError = validationError
                    )
                )

                Surface(
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                        .clickable(enabled = canSubmit, role = Role.Button) {
                            validationError = null
                            didAttemptConnection = true
                            val trimmed = pairingText.trim()
                            val validation = runCatching {
                                GatewayPairingPayloadParser.parse(trimmed, System.currentTimeMillis())
                            }
                            if (validation.isFailure) {
                                validationError = validation.exceptionOrNull()?.message ?: "配对信息无效"
                            } else {
                                stateHolder.pair(
                                    payload = trimmed,
                                    reportFailureGlobally = false,
                                    onFailure = { validationError = it }
                                )
                            }
                        }
                        .testTag("gateway-manual-connect"),
                    shape = RoundedCornerShape(8.dp),
                    color = PiColors.Ocean.copy(alpha = if (canSubmit) 1f else 0.45f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isConnecting) {
                            PiSpinner(color = Color.White, fontSize = 14.sp)
                        } else {
                            Image(
                                painter = painterResource(R.drawable.ic_link),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                colorFilter = ColorFilter.tint(Color.White)
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED) {
                                "重新配对并连接"
                            } else {
                                "连接"
                            },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PairingTextEditor(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp).clip(shape)
            .background(piGlassTint(), shape)
            .border(piGlassOutline(), shape)
            .padding(16.dp)
            .testTag("gateway-pairing-input"),
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(PiColors.Ocean),
        decorationBox = { input ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                if (value.isEmpty()) {
                    Text(
                        MANUAL_PAIRING_PLACEHOLDER,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                input()
            }
        }
    )
}

private data class PairingResultPresentation(
    val title: String,
    val detail: String,
    val color: Color,
    val iconRes: Int
)

@Composable
private fun pairingResultPresentation(
    state: GatewayRuntimeState,
    didAttemptConnection: Boolean,
    validationError: String?
): PairingResultPresentation {
    if (validationError != null) {
        return PairingResultPresentation(
            "配对信息无效",
            validationError,
            MaterialTheme.colorScheme.error,
            R.drawable.ic_permission_warning
        )
    }
    if (!didAttemptConnection) {
        return PairingResultPresentation(
            gatewayConnectionTitle(state.connection),
            "输入 Base64URL 配对字符串后点击连接，结果会显示在这里。",
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
            R.drawable.ic_gateway_auth
        )
    }
    return when (state.connection) {
        GatewayConnectionState.CONNECTING,
        GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK -> PairingResultPresentation(
            "正在连接",
            "正在提交一次性配对码并等待 Mobile Gateway 完成设备鉴权…",
            PiColors.Amber,
            R.drawable.ic_link
        )
        GatewayConnectionState.CONNECTED -> PairingResultPresentation(
            "连接成功",
            "设备鉴权已完成，长期 token 已安全保存到 Android Keystore。",
            PiColors.Success,
            R.drawable.ic_menu_check
        )
        GatewayConnectionState.FAILED -> PairingResultPresentation(
            "连接失败",
            GatewayErrorText.connection(state.lastError),
            MaterialTheme.colorScheme.error,
            R.drawable.ic_permission_warning
        )
        GatewayConnectionState.DISCONNECTED,
        GatewayConnectionState.SUSPENDED -> PairingResultPresentation(
            "未连接",
            "请检查配对信息后重新连接。",
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
            R.drawable.ic_gateway_auth
        )
    }
}

@Composable
private fun PairingResultCard(presentation: PairingResultPresentation) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(presentation.color.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(0.8.dp, presentation.color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.Top
    ) {
        Image(
            painter = painterResource(presentation.iconRes),
            contentDescription = null,
            modifier = Modifier.size(26.dp).padding(2.dp),
            colorFilter = ColorFilter.tint(presentation.color)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(presentation.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                presentation.detail,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
internal fun GatewayQrScannerScreen(
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
    onManualEntry: () -> Unit,
    onFailure: (String) -> Unit
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        // 拒绝后留在本页给补救入口，不直接 onFailure（全局弹窗的键是“重新连接”，文不对题）。
        if (!granted) permissionDenied = true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("gateway-qr-scanner")) {
        if (hasPermission) {
            GatewayCameraPreview(onCode = onCode, onFailure = onFailure)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.58f),
                        0.48f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
            )
        )
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 18.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("扫描设备配对码", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "请扫描 Pi WebUI 生成的二维码",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier.size(44.dp).clickable(role = Role.Button, onClick = onCancel)
                        .semantics { contentDescription = "取消扫描" },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.22f))
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(272.dp)
                    .border(3.dp, Color.White, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.offset(y = 58.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.18f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.18f))
                ) {
                    Text(
                        "将二维码完整放入框内",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // 权限被拒（尤其“不再询问”）的页内补救：重试/去系统设置/返回，
            // 不再是黑遮罩假取景零补救。
            if (permissionDenied && !hasPermission) {
                Text(
                    "需要相机权限才能扫描配对码",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "重试",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable {
                            permissionDenied = false
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }.padding(10.dp)
                    )
                    Text(
                        "去系统设置",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }.padding(10.dp)
                    )
                    Text(
                        "返回",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 15.sp,
                        modifier = Modifier.clickable(onClick = onCancel).padding(10.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // 顶栏不再提供手动输入入口后，扫码页内保留这条路（相机不可用/码难扫时用）。
            Text(
                "手动输入配对信息",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    .clickable(role = Role.Button, onClick = onManualEntry)
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GatewayCameraPreview(onCode: (String) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val hapticFeedback = LocalHapticFeedback.current
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }
    var didFinish by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, controller, barcodeScanner) {
        val analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor
        ) { result ->
            if (didFinish) return@MlKitAnalyzer
            val value = result?.getValue(barcodeScanner)
                ?.firstNotNullOfOrNull { barcode -> barcode.rawValue?.takeIf(String::isNotBlank) }
            if (value != null) {
                didFinish = true
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onCode(value)
            }
        }
        runCatching {
            controller.bindToLifecycle(lifecycleOwner)
            controller.setImageAnalysisAnalyzer(mainExecutor, analyzer)
        }.onFailure {
            if (!didFinish) {
                didFinish = true
                onFailure("相机启动失败，请稍后重试。")
            }
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            barcodeScanner.close()
        }
    }

    AndroidView(
        factory = { previewContext ->
            PreviewView(previewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

internal fun gatewayConnectionTitle(state: GatewayConnectionState): String = when (state) {
    GatewayConnectionState.CONNECTED -> "已连接"
    GatewayConnectionState.CONNECTING,
    GatewayConnectionState.AUTHENTICATING -> "正在连接"
    GatewayConnectionState.WAITING_FOR_NETWORK -> "等待网络"
    GatewayConnectionState.FAILED -> "连接失败"
    GatewayConnectionState.DISCONNECTED,
    GatewayConnectionState.SUSPENDED -> "未连接"
}

private const val MANUAL_PAIRING_PLACEHOLDER =
    "eyJ2ZXJzaW9uIjoyLCJwdWJsaWNVcmwiOiJ3c3M6Ly9nYXRld2F5LmV4YW1wbGUuY29tL3dzL21vYmlsZSIsLi4ufQ"
