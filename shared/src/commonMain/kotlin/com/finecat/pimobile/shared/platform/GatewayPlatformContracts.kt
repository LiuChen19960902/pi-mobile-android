package com.finecat.pimobile.shared.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 平台 WebSocket 的最小连接参数。凭据只允许传给 transport，禁止序列化、持久化或日志输出。
 */
data class GatewayConnectionSpec(
    val generation: Long,
    val endpoint: String,
    val deviceId: String,
    val bearerToken: String? = null,
    val pairingCode: String? = null
) {
    override fun toString(): String =
        "GatewayConnectionSpec(generation=$generation, endpoint=<redacted>, deviceId=<redacted>, " +
            "bearerToken=${bearerToken?.let { "<redacted>" }}, " +
            "pairingCode=${pairingCode?.let { "<redacted>" }})"
}

sealed interface GatewayTransportState {
    val generation: Long

    data class Closed(override val generation: Long = 0) : GatewayTransportState
    data class Opening(override val generation: Long) : GatewayTransportState
    data class Open(override val generation: Long) : GatewayTransportState

    data class Failed(
        override val generation: Long,
        val httpStatus: Int? = null,
        val closeCode: Int? = null,
        val reason: String? = null,
        val recoverable: Boolean = true
    ) : GatewayTransportState
}

/** 入站文本携带不可复用的连接代次；文本内容不得进入 toString 或日志。 */
data class GatewayTransportFrame(
    val generation: Long,
    val text: String,
    val byteCount: Int
) {
    override fun toString(): String =
        "GatewayTransportFrame(generation=$generation, byteCount=$byteCount, text=<redacted>)"
}

sealed interface GatewayTransportEvent {
    data class State(val value: GatewayTransportState) : GatewayTransportEvent
    data class Frame(val value: GatewayTransportFrame) : GatewayTransportEvent {
        override fun toString(): String = value.toString()
    }
}

/** 平台 transport 每次只拥有一个 socket，并保持单连接内帧的原始顺序。 */
interface GatewayTransport {
    val state: StateFlow<GatewayTransportState>
    /** frame 与 failure 必须共享同一有序事件流。 */
    val events: Flow<GatewayTransportEvent>

    suspend fun open(spec: GatewayConnectionSpec)
    suspend fun send(text: String)
    suspend fun close()
}

enum class GatewayNetworkState {
    AVAILABLE,
    UNAVAILABLE
}

interface GatewayNetworkMonitor {
    val state: StateFlow<GatewayNetworkState>
}

@kotlinx.serialization.Serializable
data class GatewayPreferencesSnapshot(
    /** 当前活动地址（展示与兼容旧配置）；候选列表为空时即为唯一地址。 */
    val endpoint: String = "ws://127.0.0.1:3080/ws/mobile",
    /**
     * 同一网关的多个入口，按尝试顺序排列（例：在家局域网 → 在外 Tailscale）。
     * 空表示只用 [endpoint]。地址之间共用一份凭据，切换不需要重新配对。
     */
    val endpointCandidates: List<String> = emptyList(),
    val sessionsJson: String? = null,
    /** 首页大标题自定义文案；null 回退内置默认。 */
    val heroTitle: String? = null,
    /** 首页副标题标语自定义文案；null 只显示版本号（默认不预置作者标语）。 */
    val heroTagline: String? = null,
    /** app 外观覆盖："system"（跟随系统）| "light" | "dark"。 */
    val appearanceMode: String = "system",
    /**
     * 首页"假删除"的会话黑名单：只在手机端列表里隐藏，不删除宿主机的 session 文件。
     * 真正删除会话仍由用户在电脑端 pi 里操作。
     */
    val hiddenSessionIds: List<String> = emptyList(),
    /**
     * 首页目录组级的“假删除”黑名单：整个目录下所有会话在手机端隐藏，
     * 不动宿主机的 session 文件。存完整 cwd（与 SessionGroup.cwd 口径一致）。
     */
    val hiddenDirectoryPaths: List<String> = emptyList(),
    /** 手机本地置顶，不修改 Pi 会话文件或全局配置。 */
    val pinnedSessionIds: List<String> = emptyList()
)

/** 仅保存非敏感配置；token 与 device id 不得进入该存储。 */
interface GatewayPreferences {
    val snapshots: Flow<GatewayPreferencesSnapshot>

    suspend fun load(): GatewayPreferencesSnapshot
    suspend fun update(snapshot: GatewayPreferencesSnapshot)

    /** 原子读改写：transform 在存储事务内执行。load+update 分两步会被并发写交错
     * 丢更新（如外观与标题同时改）。默认实现非原子（内存/测试假实现用）。 */
    suspend fun updateFields(transform: (GatewayPreferencesSnapshot) -> GatewayPreferencesSnapshot) {
        update(transform(load()))
    }
}

/**
 * 凭据存储必须由平台安全设施保护。token 与 device id 本身不得明文落盘。
 *
 * token 不按 endpoint 分存：网关侧凭据是设备级的，同一 token 在局域网地址与
 * tailnet 地址上同样可用，按地址分存只会制造「换个地址就 401」的假故障。
 */
interface GatewayCredentialStore {
    suspend fun loadOrCreateDeviceId(): String
    suspend fun loadToken(): String?
    suspend fun saveToken(token: String)
    suspend fun deleteToken()
}

interface GatewayAttachmentCache {
    suspend fun read(attachmentId: String): ByteArray?
    /** 仅当内存和磁盘提交均成功时返回 true。 */
    suspend fun write(attachmentId: String, bytes: ByteArray): Boolean
    suspend fun removeExpired()
}

interface GatewayClock {
    fun nowEpochMilliseconds(): Long
    suspend fun delay(milliseconds: Long)
}
