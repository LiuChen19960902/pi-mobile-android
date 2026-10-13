package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.finecat.composer.contextUsageDisplay
import com.finecat.pimobile.shared.protocol.GatewayContextUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class FcContextUsageTest {
    @Test
    fun noSnapshotIsUnknownInsteadOfZero() {
        val display = contextUsageDisplay(null)
        assertEquals("—", display.percent)
        assertEquals("—", display.tokens)
        assertEquals("—", display.window)
        assertEquals(0f, display.progress)
    }

    @Test
    fun postCompactionUnknownRetainsOnlyKnownWindow() {
        val display = contextUsageDisplay(GatewayContextUsage(percent = null, tokens = null, contextWindow = 200_000))
        assertEquals("—", display.percent)
        assertEquals("—", display.tokens)
        assertEquals("200K", display.window)
        assertEquals(0f, display.progress)
    }

    @Test
    fun realSnapshotUsesNativePercentRatherThanRecalculatingIt() {
        val display = contextUsageDisplay(GatewayContextUsage(percent = 74.0, tokens = 148_123, contextWindow = 200_000))
        assertEquals("74%", display.percent)
        assertEquals("148K", display.tokens)
        assertEquals("200K", display.window)
        assertEquals(0.74f, display.progress)
    }

    @Test
    fun actualZeroRemainsDistinctFromUnknown() {
        val display = contextUsageDisplay(GatewayContextUsage(percent = 0.0, tokens = 0, contextWindow = 200_000))
        assertEquals("0%", display.percent)
        assertEquals("0", display.tokens)
    }

    @Test
    fun aboveCapacityKeepsActualPercentageAndSaturatesOnlyArc() {
        val display = contextUsageDisplay(GatewayContextUsage(percent = 125.0, tokens = 1_250_000, contextWindow = 1_000_000))
        assertEquals("125%", display.percent)
        assertEquals("1.25M", display.tokens)
        assertEquals("1.00M", display.window)
        assertEquals(1f, display.progress)
    }

    @Test
    fun malformedNumbersDoNotBecomeValidUsage() {
        for (percent in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            val display = contextUsageDisplay(GatewayContextUsage(percent = percent, tokens = -1, contextWindow = 0))
            assertEquals("—", display.percent)
            assertEquals("—", display.tokens)
            assertEquals("—", display.window)
            assertEquals(0f, display.progress)
        }
    }
}
