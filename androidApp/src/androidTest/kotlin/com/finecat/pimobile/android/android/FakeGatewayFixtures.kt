package com.finecat.pimobile.android

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.shared.platform.GatewayAttachmentCache
import com.finecat.pimobile.shared.platform.GatewayClock
import com.finecat.pimobile.shared.platform.GatewayConnectionSpec
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import com.finecat.pimobile.shared.platform.GatewayNetworkMonitor
import com.finecat.pimobile.shared.platform.GatewayNetworkState
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import com.finecat.pimobile.shared.platform.GatewayTransport
import com.finecat.pimobile.shared.platform.GatewayTransportEvent
import com.finecat.pimobile.shared.platform.GatewayTransportFrame
import com.finecat.pimobile.shared.platform.GatewayTransportState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 原文件内名沿用：转发到共享实现。 */
internal suspend fun waitUntil(predicate: () -> Boolean) = testWaitUntil(predicate)

internal fun onMain(block: () -> Unit) = testOnMain(block)

/** androidTest 共享的网关测试基建：FakeTransport + 平台桩（原内嵌于 AndroidAppGraphFakeIntegrationDeviceTest）。 */
internal suspend fun testWaitUntil(predicate: () -> Boolean) {
    withTimeout(5_000) {
        while (!predicate()) delay(20)
    }
}

internal fun testOnMain(block: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}

internal fun tinyPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }
}

internal fun base64TinyPng(): String =
    Base64.encodeToString(tinyPng(), Base64.NO_WRAP)

internal class FakeTransport : GatewayTransport {
    private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
    private val mutableEvents = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 2_048)
    val specs = mutableListOf<GatewayConnectionSpec>()
    private val payloads = mutableListOf<String>()
    val sentTypes: List<String>
        get() = synchronized(payloads) {
            payloads.map { Json.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content }
        }
    fun payloadsOfType(type: String) = synchronized(payloads) {
        payloads.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == type }
    }
    override val state: StateFlow<GatewayTransportState> = mutableState
    override val events: Flow<GatewayTransportEvent> = mutableEvents
    val hasFrameSubscriber: Boolean get() = mutableEvents.subscriptionCount.value > 0

    override suspend fun open(spec: GatewayConnectionSpec) {
        synchronized(specs) { specs += spec }
        emit(GatewayTransportState.Opening(spec.generation))
    }

    override suspend fun send(text: String) {
        synchronized(payloads) { payloads += text }
    }

    override suspend fun close() {
        emit(GatewayTransportState.Closed(specs.lastOrNull()?.generation ?: 0))
    }

    fun open() = emit(GatewayTransportState.Open(specs.last().generation))

    /**
     * 合成一次**不可恢复**的连接失败。
     *
     * `Closed` 状态 runtime 是直接忽略的（`handleTransportStateLocked` 对它 `Unit`），
     * 所以要拿到在途请求的 `RequestCancelled` 必须走 `Failed` 分支：它会
     * `cancelPendingLocked`。`recoverable = false` 且无凭据拒绝码 → 不进重连，
     * 状态直接落到 FAILED，用例不需要处理自动重连。
     */
    fun fail(reason: String = "synthetic-transport-failure") =
        emit(
            GatewayTransportState.Failed(
                generation = specs.lastOrNull()?.generation ?: 0,
                reason = reason,
                recoverable = false,
            )
        )

    /**
     * 合成一次**可恢复**的连接失败：runtime 会自己走重连退避（首个 2s）。
     *
     * [fail] 是不可恢复的，用完之后连接停在 FAILED、不会自己再连，想让后续用例继续
     * 只能用「重新 connect」那套。迟到回执重发场景要的是前者：先被取消、再等它自己
     * 连回来（用例侧只需在 runtime 调 open 之后替它发 Open/hello，见
     * FakeTransport.open 的调用方）。
     */
    fun failRecoverable(reason: String = "synthetic-transport-drop") =
        emit(
            GatewayTransportState.Failed(
                generation = specs.lastOrNull()?.generation ?: 0,
                reason = reason,
                recoverable = true,
            )
        )

    fun receive(json: String) {
        check(
            mutableEvents.tryEmit(
                GatewayTransportEvent.Frame(
                    GatewayTransportFrame(specs.last().generation, json, json.toByteArray().size)
                )
            )
        )
    }

    private fun emit(value: GatewayTransportState) {
        mutableState.value = value
        check(mutableEvents.tryEmit(GatewayTransportEvent.State(value)))
    }
}

internal class FakePreferences : GatewayPreferences {
    private val value = MutableStateFlow(
        GatewayPreferencesSnapshot(endpoint = "wss://gateway.example/ws/mobile")
    )
    override val snapshots: Flow<GatewayPreferencesSnapshot> = value
    override suspend fun load(): GatewayPreferencesSnapshot = value.value
    override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
        value.value = snapshot
    }
}

internal object FakeCredentials : GatewayCredentialStore {
    override suspend fun loadOrCreateDeviceId(): String = "device"
    override suspend fun loadToken(): String = "token"
    override suspend fun saveToken(token: String) = Unit
    override suspend fun deleteToken() = Unit
}

internal class FakeCache : GatewayAttachmentCache {
    val values = mutableMapOf<String, ByteArray>()
    override suspend fun read(attachmentId: String): ByteArray? = values[attachmentId]
    override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean {
        values[attachmentId] = bytes
        return true
    }
    override suspend fun removeExpired() = Unit
}

internal object FakeNetwork : GatewayNetworkMonitor {
    override val state: StateFlow<GatewayNetworkState> =
        MutableStateFlow(GatewayNetworkState.AVAILABLE)
}

internal object FakeClock : GatewayClock {
    override fun nowEpochMilliseconds(): Long = 0
    override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
}
