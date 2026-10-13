package com.finecat.pimobile.android.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeFrameParseProbeTest {
    @Test
    fun parseSyntheticTreeFrame() {
        val node = """{"id":"t01","prefix":"","onActivePath":true,"type":"message","role":"user","preview":"p","timestamp":"2026-09-30T09:01:00"}"""
        val frame = """{"kind":"tree","sessionId":"f06-tree","leafId":"t08","nodes":[$node]}"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(
            com.finecat.pimobile.shared.protocol.GatewayFrame.serializer(), frame,
        )
        assertEquals("tree", parsed.kind)
        assertEquals("t08", parsed.leafId)
        assertTrue(parsed.nodes?.size == 1)
        assertEquals("t01", parsed.nodes?.first()?.id)
    }
}
