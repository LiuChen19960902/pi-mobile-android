package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.gateway.ProviderModelUpsert
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayModelSelection
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayModelScopeProtocolTest {
    @Test
    fun modelAddressHasPreserveOverrideAndInheritanceStates() {
        val rows = listOf(
            ProviderModelUpsert("preserved", baseUrl = "https://saved.example/custom"),
            ProviderModelUpsert("overridden", baseUrl = "https://other.example/v1", baseUrlEdited = true),
            ProviderModelUpsert("inherited", baseUrlEdited = true),
        )
        val put = wireJson.parseToJsonElement(GatewayRequests.providerPut("relay", "https://root.example", null, rows).payload).jsonObject
        val models = put.getValue("models").jsonArray.map { it.jsonObject }
        assertFalse("baseUrl" in models[0])
        assertEquals("https://other.example/v1", models[1].getValue("baseUrl").jsonPrimitive.content)
        assertEquals(JsonNull, models[2]["baseUrl"])
        val inherited = wireJson.parseToJsonElement(GatewayRequests.providerTest("relay", model = "inherited", modelBaseUrlSpecified = true).payload).jsonObject
        assertEquals(JsonNull, inherited["modelBaseUrl"])
        val legacy = wireJson.parseToJsonElement(GatewayRequests.providerTest("relay", model = "preserved").payload).jsonObject
        assertFalse("modelBaseUrl" in legacy)
    }

    @Test
    fun scopeWriteCarriesOriginalPatternsExplicitPicksAndCorrelation() {
        val request = GatewayRequests.modelScopePut("request-1", listOf("p/*", "old/missing"), listOf(GatewayModelSelection("p", "m", "high")), true, false)
        assertEquals("request-1", request.correlationId)
        val body = wireJson.parseToJsonElement(request.payload).jsonObject
        assertEquals(listOf("p/*", "old/missing"), body.getValue("expectedPatterns").jsonArray.map { it.jsonPrimitive.content })
        val selected = body.getValue("models").jsonArray.single().jsonObject
        assertEquals("m", selected.getValue("modelId").jsonPrimitive.content)
        assertEquals("high", selected.getValue("thinkingLevel").jsonPrimitive.content)
        val response = wireJson.decodeFromString<GatewayFrame>("""{"kind":"model-scope","scope":{"patterns":["p/*"],"groups":[],"selected":[{"provider":"p","modelId":"m","thinkingLevel":"high"}],"unmatchedPatterns":[]}}""")
        assertEquals("high", response.scope!!.selected.single().thinkingLevel)
        assertTrue(response.scope!!.unmatchedPatterns.isEmpty())
    }
}
