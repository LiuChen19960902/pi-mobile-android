package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayPreferencesCompatibilityTest {
    @Test
    fun prePinConfigurationLoadsWithoutDroppingExistingChoices() {
        val preferences = Json.decodeFromString<GatewayPreferencesSnapshot>(
            """{"endpoint":"ws://gateway.example:3080/ws/mobile","heroTitle":"","appearanceMode":"dark","hiddenSessionIds":["hidden"],"hiddenDirectoryPaths":["/work/hidden"]}"""
        )
        assertTrue(preferences.pinnedSessionIds.isEmpty())
        assertEquals("", preferences.heroTitle)
        assertEquals("dark", preferences.appearanceMode)
        assertEquals(listOf("hidden"), preferences.hiddenSessionIds)
        assertEquals(listOf("/work/hidden"), preferences.hiddenDirectoryPaths)
    }

    @Test
    fun unrelatedPreferenceEditPreservesPinnedSessionsThroughSerialization() {
        val original = GatewayPreferencesSnapshot(pinnedSessionIds = listOf("a", "b"))
        val restored = Json.decodeFromString<GatewayPreferencesSnapshot>(Json.encodeToString(original))
        val changed = restored.copy(appearanceMode = "dark")
        assertEquals(listOf("a", "b"), changed.pinnedSessionIds)
    }
}
