package com.finecat.pimobile.android

import android.app.Application
import com.finecat.pimobile.android.platform.AndroidAttachmentCache
import com.finecat.pimobile.android.platform.AndroidAttachmentThumbnailer
import com.finecat.pimobile.android.platform.AndroidGatewayClock
import com.finecat.pimobile.android.platform.AndroidGatewayCredentialStore
import com.finecat.pimobile.android.platform.AndroidGatewayDiagnostics
import com.finecat.pimobile.android.platform.AndroidGatewayPreferences
import com.finecat.pimobile.android.platform.AndroidImagePreprocessor
import com.finecat.pimobile.android.platform.AndroidNetworkMonitor
import com.finecat.pimobile.android.platform.AndroidRecentPhotos
import com.finecat.pimobile.android.platform.OkHttpGatewayTransport
import com.finecat.pimobile.android.platform.RecentPhotosProvider
import com.finecat.pimobile.shared.gateway.GatewayRuntime
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.platform.GatewayAttachmentCache
import com.finecat.pimobile.shared.platform.GatewayClock
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import com.finecat.pimobile.shared.platform.GatewayNetworkMonitor
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AndroidAppGraph(
    application: Application,
    transportOverride: GatewayTransport? = null,
    preferencesOverride: GatewayPreferences? = null,
    credentialStoreOverride: GatewayCredentialStore? = null,
    attachmentCacheOverride: GatewayAttachmentCache? = null,
    networkMonitorOverride: GatewayNetworkMonitor? = null,
    clockOverride: GatewayClock? = null,
    frameDecoderOverride: ((String) -> GatewayFrame)? = null,
    recentPhotosOverride: RecentPhotosProvider? = null,
    /** 仅测试取证放宽：网关请求超时默认随生产（注入应答帧的 fixture 需要 > 模拟器冷启动抖动）。 */
    requestTimeoutOverride: Long? = null
) {
    /** 生命周期/UI 提交使用 Main；Gateway decode、MVI 与磁盘协调使用单线程后台 dispatcher。 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val gatewayDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val gatewayScope = CoroutineScope(SupervisorJob() + gatewayDispatcher)
    internal val diagnostics = AndroidGatewayDiagnostics.forApplication(application)
    val preferences: GatewayPreferences = preferencesOverride ?: AndroidGatewayPreferences(application)
    val credentialStore: GatewayCredentialStore =
        credentialStoreOverride ?: AndroidGatewayCredentialStore(application)
    val attachmentCache: GatewayAttachmentCache =
        attachmentCacheOverride ?: AndroidAttachmentCache(application)
    val attachmentThumbnailer = AndroidAttachmentThumbnailer()
    val imagePreprocessor = AndroidImagePreprocessor(application.contentResolver)
    val recentPhotos: RecentPhotosProvider = recentPhotosOverride ?: AndroidRecentPhotos(application.contentResolver)
    val networkMonitor: GatewayNetworkMonitor = networkMonitorOverride ?: AndroidNetworkMonitor(application)
    val transport: GatewayTransport = transportOverride ?: OkHttpGatewayTransport(diagnostics = diagnostics)
    val gatewayRuntime = GatewayRuntime(
        transport = transport,
        preferences = preferences,
        credentials = credentialStore,
        attachmentCache = attachmentCache,
        networkMonitor = networkMonitor,
        clock = clockOverride ?: AndroidGatewayClock,
        scope = gatewayScope,
        requestTimeoutMilliseconds = requestTimeoutOverride ?: 30_000L,
        frameDecoder = frameDecoderOverride ?: GatewayWireDecoder::decode
    )
    val stateHolder: AndroidSharedStateHolder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidSharedStateHolder(graph = this)
    }
}
