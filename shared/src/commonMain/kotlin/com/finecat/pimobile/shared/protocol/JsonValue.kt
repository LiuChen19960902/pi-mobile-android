package com.finecat.pimobile.shared.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

@Serializable(with = JsonValueSerializer::class)
sealed interface JsonValue {
    data class StringValue(val value: String) : JsonValue
    /** 数值字面量；整数字面量保留原文，避免 Double 往返把 3 变成 3.0。 */
    data class NumberValue(val value: Double, val rawLiteral: String? = null) : JsonValue {
        val isIntegerLiteral: Boolean get() = rawLiteral?.none { it == '.' || it == 'e' || it == 'E' } == true
    }
    data class BooleanValue(val value: Boolean) : JsonValue
    data class ObjectValue(val value: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val value: List<JsonValue>) : JsonValue
    data object NullValue : JsonValue

    val objectValue: Map<String, JsonValue>?
        get() = (this as? ObjectValue)?.value
    val arrayValue: List<JsonValue>?
        get() = (this as? ArrayValue)?.value
    val stringValue: String?
        get() = (this as? StringValue)?.value
    val doubleValue: Double?
        get() = (this as? NumberValue)?.value
    val booleanValue: Boolean?
        get() = (this as? BooleanValue)?.value

    operator fun get(key: String): JsonValue? = objectValue?.get(key)

    fun displayText(): String = prettyJson.encodeToString(JsonElement.serializer(), toJsonElement())

    fun jsonDisplayText(): String {
        val raw = stringValue ?: return displayText()
        val parsed = runCatching { wireJson.parseToJsonElement(raw) }.getOrNull() ?: return displayText()
        return prettyJson.encodeToString(JsonElement.serializer(), parsed)
    }

    /** 将供应商以 JSON 字符串承载的参数规范化为真正的 JSON 值。 */
    fun normalizedJsonValue(): JsonValue {
        val raw = stringValue ?: return this
        val parsed = runCatching { wireJson.parseToJsonElement(raw) }.getOrNull() ?: return this
        return fromJsonElement(parsed)
    }

    fun firstInteger(keys: Set<String>): Int? {
        objectValue?.let { objectValue ->
            keys.firstNotNullOfOrNull { key -> objectValue[key]?.doubleValue?.toInt() }?.let { return it }
            objectValue.values.firstNotNullOfOrNull { it.firstInteger(keys) }?.let { return it }
        }
        arrayValue?.firstNotNullOfOrNull { it.firstInteger(keys) }?.let { return it }
        return null
    }

    fun toJsonElement(): JsonElement = when (this) {
        is StringValue -> JsonPrimitive(value)
        is NumberValue -> if (isIntegerLiteral && rawLiteral != null) {
            // 整数字面量必须保持数字类型：JsonPrimitive(String) 会产出字符串，
            // 整数经解码→重编码往返后会类型丢失（如 attempt:"1"）。
            // 超出 Long 范围时回退 double（pi 端是 JS，JSON.parse 对超大整数
            // 本就按 double 处理：精度损失但不饱和、不变字符串）。
            rawLiteral.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
        } else {
            JsonPrimitive(value)
        }
        is BooleanValue -> JsonPrimitive(value)
        is ObjectValue -> JsonObject(value.mapValues { it.value.toJsonElement() })
        is ArrayValue -> JsonArray(value.map(JsonValue::toJsonElement))
        NullValue -> JsonNull
    }

    companion object {
        fun fromJsonElement(element: JsonElement): JsonValue = when (element) {
            JsonNull -> NullValue
            is JsonObject -> ObjectValue(element.mapValues { fromJsonElement(it.value) })
            is JsonArray -> ArrayValue(element.map(::fromJsonElement))
            is JsonPrimitive -> when {
                element.isString -> StringValue(element.content)
                element.booleanOrNull != null -> BooleanValue(element.booleanOrNull == true)
                else -> {
                    val doubleValue = element.doubleOrNull ?: error("Unsupported JSON primitive: $element")
                    NumberValue(doubleValue, element.content)
                }
            }
        }
    }
}

object JsonValueSerializer : KSerializer<JsonValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonValue")

    override fun deserialize(decoder: Decoder): JsonValue {
        val jsonDecoder = decoder as? JsonDecoder ?: error("JsonValue requires JsonDecoder")
        return JsonValue.fromJsonElement(jsonDecoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: JsonValue) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("JsonValue requires JsonEncoder")
        jsonEncoder.encodeJsonElement(value.toJsonElement())
    }
}

internal val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

private val prettyJson = Json(wireJson) {
    prettyPrint = true
    prettyPrintIndent = "  "
}
