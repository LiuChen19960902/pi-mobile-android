package com.finecat.pimobile.android.ui.finecat

import java.net.URI

/** Common entries are entered as host:port; full legacy ws/wss URLs remain valid. */
internal fun normalizeGatewayEndpoint(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null
    val full = value.contains("://")
    val uri = runCatching { URI(if (full) value else "ws://$value/ws/mobile") }.getOrNull() ?: return null
    if (uri.scheme !in listOf("ws", "wss") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.rawFragment != null || uri.port > 65535 || uri.port == 0 || uri.port < -1) return null
    if (!full && (uri.port < 1 || uri.rawPath != "/ws/mobile" || uri.rawQuery != null)) return null
    return uri.toASCIIString()
}

internal fun gatewayAddressLabel(endpoint: String?): String {
    val uri = endpoint?.let { runCatching { URI(it) }.getOrNull() }
    val host = uri?.host ?: return endpoint?.takeIf(String::isNotBlank) ?: "未设置"
    return host + if (uri.port >= 0) ":${uri.port}" else ""
}
