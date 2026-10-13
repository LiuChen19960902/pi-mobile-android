package com.finecat.pimobile.shared.protocol

import kotlinx.serialization.json.decodeFromJsonElement

object GatewayWireDecoder {
    fun decode(text: String): GatewayFrame {
        val parsed = wireJson.parseToJsonElement(text)
        // 缺 kind 即违背协议骨架：直接让序列化层抛（MissingFieldException），
        // 走 ERROR_DECODE_FAILED loudly 暴露服务端 bug，不美化成 event。
        return wireJson.decodeFromJsonElement(GatewayFrame.serializer(), parsed)
    }
}
