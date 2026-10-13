package com.finecat.pimobile.android.platform

import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import com.finecat.pimobile.shared.gateway.GatewayRuntimeState
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewayDiagnosticsTest {
    @Test
    fun highFrequencyChunksAreSampledInsteadOfLoggedPerToken() {
        val lines = mutableListOf<String>()
        val diagnostics = AndroidGatewayDiagnostics.forTest { _, _, message -> lines += message }
        repeat(256) {
            diagnostics.runtimeEvent(
                GatewayRuntimeEvent.Frame(
                    rawJson = "redacted",
                    frame = GatewayFrame(kind = "event"),
                    correlatedSessionId = "session",
                    normalizedEvents = listOf(
                        SessionEvent(
                            sessionId = "session",
                            seq = 1,
                            time = 0.0,
                            event = GatewayEvent("assistant/chunk", chunkType = "text-delta", text = "x")
                        )
                    )
                )
            )
        }

        assertTrue(lines.any { "count=1" in it })
        assertTrue(lines.any { "count=128" in it })
        assertTrue(lines.any { "count=256" in it })
        assertEquals(3, lines.size)
    }

    @Test
    fun diagnosticLinesKeepPayloadAndIdentifiersRedacted() {
        val lines = mutableListOf<String>()
        val diagnostics = AndroidGatewayDiagnostics.forTest { _, _, message -> lines += message }
        val secret = "secret-token-message-session-attachment"

        diagnostics.runtimeState(
            GatewayRuntimeState(
                connection = GatewayConnectionState.FAILED,
                endpoint = secret,
                activeTurnSessionIds = setOf(secret),
                lastError = secret
            )
        )
        diagnostics.runtimeEvent(
            GatewayRuntimeEvent.Frame(
                rawJson = secret,
                frame = GatewayFrame(kind = secret, token = secret, message = secret),
                correlatedSessionId = secret
            )
        )
        diagnostics.runtimeEvent(
            GatewayRuntimeEvent.RequestRejected(
                requestType = secret,
                reason = secret,
                targetSessionId = secret,
                correlationId = secret
            )
        )
        diagnostics.runtimeState(GatewayRuntimeState(connection = GatewayConnectionState.CONNECTED))

        val output = lines.joinToString("\n")
        assertFalse(output.contains(secret))
        assertFalse(output.contains("token="))
        assertTrue(output.contains("kind=unknown"))
        assertTrue(output.contains("error=unknown"))
        assertTrue(output.contains("error=none"))
        assertTrue(output.contains("hasTarget=true"))
        assertTrue(output.contains("hasCorrelation=true"))
    }
}
