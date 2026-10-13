package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.domain.ModelNumberResult
import com.finecat.pimobile.shared.domain.ModelNumberParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNumberParsingTest {
    @Test
    fun decimalSuffixesMatchTokenCounts() {
        for ((raw, value) in mapOf("500K" to 500000L, "275k" to 275000L, "2M" to 2000000L,
            "1.5m" to 1500000L, " 4K " to 4000L, "128000" to 128000L)) {
            assertEquals(raw, ModelNumberResult.Ok(value), ModelNumberParsing.parseTokenCount(raw))
        }
    }

    @Test
    fun decimalFractionsDoNotLoseTokensThroughFloatingPointTruncation() {
        for ((raw, value) in mapOf("1.001K" to 1001L, "0.029K" to 29L, "0.000001M" to 1L, "1.0" to 1L)) {
            assertEquals(raw, ModelNumberResult.Ok(value), ModelNumberParsing.parseTokenCount(raw))
        }
        assertTrue(ModelNumberParsing.parseTokenCount("1.2") is ModelNumberResult.Invalid)
        assertTrue(ModelNumberParsing.parseTokenCount("0.0001K") is ModelNumberResult.Invalid)
    }

    @Test
    fun blankIsUnset() {
        assertEquals(ModelNumberResult.Unset, ModelNumberParsing.parseTokenCount(""))
        assertEquals(ModelNumberResult.Unset, ModelNumberParsing.parseTokenCount("   "))
    }

    @Test
    fun invalidSuffixesAndNumbersAreRejected() {
        for (raw in listOf("375l", "375L", "abc", "0", "-5K", "1.2.3", "1e6", "NaN", "999999999999999999999999999999")) {
            assertTrue(raw, ModelNumberParsing.parseTokenCount(raw) is ModelNumberResult.Invalid)
        }
    }

    @Test
    fun integerCapRemainsCompatibleWithOldConfigurations() {
        assertEquals(ModelNumberResult.Ok(ModelNumberParsing.MAX_TOKEN_COUNT),
            ModelNumberParsing.parseTokenCount(ModelNumberParsing.MAX_TOKEN_COUNT.toString()))
        assertEquals(ModelNumberResult.Ok(100000000L), ModelNumberParsing.parseTokenCount("100M"))
        assertTrue(ModelNumberParsing.parseTokenCount((ModelNumberParsing.MAX_TOKEN_COUNT + 1).toString()) is ModelNumberResult.Invalid)
        assertTrue(ModelNumberParsing.parseTokenCount("106M") is ModelNumberResult.Invalid)
    }

    @Test
    fun suffixFormattingIsExact() {
        assertEquals("500K", ModelNumberParsing.formatTokenCount(500000L))
        assertEquals("275K", ModelNumberParsing.formatTokenCount(275000L))
        assertEquals("2M", ModelNumberParsing.formatTokenCount(2000000L))
        assertEquals("131072", ModelNumberParsing.formatTokenCount(131072L))
        assertEquals("1000001", ModelNumberParsing.formatTokenCount(1000001L))
    }

    @Test
    fun oldAndNewIntegersRoundTripWithoutRewriting() {
        for (value in listOf(1L, 999L, 1024L, 8192L, 131072L, 200000L, 275000L, 500000L,
            1000000L, 1048576L, 2097152L, 10485760L, 16777216L, ModelNumberParsing.MAX_TOKEN_COUNT)) {
            assertEquals(value.toString(), ModelNumberResult.Ok(value),
                ModelNumberParsing.parseTokenCount(ModelNumberParsing.formatTokenCount(value)))
        }
    }
}
