package com.finecat.pimobile.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveHistoryPageLimitTest {
    @Test fun overrideAppliesToOlderPageOfTheSameSession() {
        assertEquals(100, resolveHistoryPageLimit("s1" to 100, "s1", "cursor", 20))
    }

    @Test fun latestPageRequestNeverConsumesTheOverride() {
        assertEquals(20, resolveHistoryPageLimit("s1" to 100, "s1", null, 20))
    }

    @Test fun overrideDoesNotLeakToAnotherSession() {
        assertEquals(20, resolveHistoryPageLimit("s1" to 100, "s2", "cursor", 20))
    }

    @Test fun noOverrideUsesTheDefault() {
        assertEquals(20, resolveHistoryPageLimit(null, "s1", "cursor", 20))
    }
}
