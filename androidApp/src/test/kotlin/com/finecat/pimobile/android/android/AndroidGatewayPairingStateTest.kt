package com.finecat.pimobile.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewayPairingStateTest {
    private fun receive(holder: AndroidSharedStateHolder, json: String) {
        holder.wirePayload = json
        holder.submitWirePayload()
    }

    @Test
    fun pairedClearsRemoteProjectionAndDirectoryButPreservesUnsentText() {
        val holder = AndroidSharedStateHolder()
        receive(holder, """{"kind":"sessions","items":[{"id":"old","name":"old","running":false}]}""")
        holder.selectSession("old")
        holder.selectFineCatDirectory("/old-host/project")
        holder.setInputAssistContext("old", null)
        holder.messageDraft = "unsent local text"
        val epoch = holder.gatewayUiEpoch
        receive(holder, """{"kind":"paired"}""")
        assertEquals(epoch + 1, holder.gatewayUiEpoch)
        assertTrue(holder.snapshot.sessions.isEmpty())
        assertNull(holder.snapshot.selectedSessionId)
        assertNull(holder.snapshot.modelCatalog)
        assertNull(holder.fineCatNewSessionDirectoryPath)
        assertFalse(holder.inputAssist.context.valid)
        assertNull(holder.sessionTree)
        assertEquals("unsent local text", holder.messageDraft)
        holder.close()
    }

    @Test
    fun ordinaryReconnectHelloKeepsSelectionAndDirectory() {
        val holder = AndroidSharedStateHolder()
        receive(holder, """{"kind":"sessions","items":[{"id":"same","name":"same","running":false}]}""")
        holder.selectSession("same")
        holder.selectFineCatDirectory("/same-host/project")
        receive(holder, """{"kind":"hello","protocol":1,"authenticated":true}""")
        assertEquals(0L, holder.gatewayUiEpoch)
        assertEquals("same", holder.snapshot.selectedSessionId)
        assertEquals("/same-host/project", holder.fineCatNewSessionDirectoryPath)
        assertEquals(1, holder.snapshot.sessions.size)
        holder.close()
    }
}
