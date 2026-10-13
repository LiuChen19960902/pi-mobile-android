package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.protocol.GatewayTreeView
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayTreeViewContractTest {
    @Test
    fun optionalViewPreservesLegacyRequestAndEncodesSearchLiterally() {
        assertFalse(Json.parseToJsonElement(GatewayRequests.tree("s").payload).jsonObject.containsKey("view"))
        val options = GatewayTreeView("all", "引号\" 标签", listOf("n1"))
        val view = Json.parseToJsonElement(GatewayRequests.tree("s", options).payload).jsonObject["view"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("\"all\""), view["filter"])
        assertEquals(Json.parseToJsonElement("[\"n1\"]"), view["foldedIds"])
    }

    @Test
    fun treeViewResponseKeepsPiPrefixTextFoldAndSelection() {
        val frame = GatewayWireDecoder.decode("""{"kind":"tree","sessionId":"s","leafId":"leaf","selectedId":"parent",
            "view":{"filter":"default","search":"needle","foldedIds":["parent"]},
            "nodes":[{"id":"parent","type":"message","role":"user","prefix":"├⊞ ","onActivePath":true,
            "preview":"legacy","displayText":"user: **literal**","foldable":true,"folded":true}]}""")
        assertEquals("parent", frame.selectedId)
        assertEquals("needle", frame.view?.search)
        val node = frame.nodes!!.single()
        assertEquals("├⊞ ", node.prefix)
        assertEquals("user: **literal**", node.displayText)
        assertTrue(node.folded && node.foldable && node.onActivePath)
    }
}
