package com.finecat.pimobile.android

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProviderConnectionTestTest {
    private val targets = listOf(
        ProviderTestTarget("first", "anthropic-messages"),
        ProviderTestTarget("second", "openai-completions"),
        ProviderTestTarget("third", "openai-responses"),
    )

    @Test fun firstFailureThenSuccessStopsBeforeThirdModel() = runTest {
        val calls = mutableListOf<ProviderTestTarget>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = testProviderModelsUntilSuccess(targets, { i, n, _ -> progress += i to n }) { target ->
            calls += target
            ProviderProbeReply(AndroidSharedStateHolder.ProviderTestOutcome(
                ok = target.modelId == "second", summary = "reply", providerId = "p",
                httpStatus = if (target.modelId == "second") 200 else 402,
            ))
        }
        assertTrue(result.ok)
        assertEquals(targets.take(2), calls)
        assertEquals(listOf(1 to 3, 2 to 3), progress)
        assertTrue(result.summary.contains("second"))
        assertTrue(result.summary.contains("2/3"))
        assertEquals(listOf("first", "second"), result.attempts.map { it.modelId })
        assertEquals(listOf(false, true), result.attempts.map { it.ok })
        assertEquals("reply", result.attempts.first().summary)
        assertEquals(listOf(402, 200), result.attempts.map { it.httpStatus })
    }

    @Test fun firstSuccessMakesOnlyOneRequest() = runTest {
        var calls = 0
        val result = testProviderModelsUntilSuccess(targets, { _, _, _ -> }) {
            calls++
            ProviderProbeReply(AndroidSharedStateHolder.ProviderTestOutcome(true, "ok"))
        }
        assertTrue(result.ok)
        assertEquals(1, calls)
        assertEquals(listOf("first"), result.attempts.map { it.modelId })
    }

    @Test fun AllFailuresIncludingTransientDoNotPass() = runTest {
        var calls = 0
        val result = testProviderModelsUntilSuccess(targets, { _, _, _ -> }) {
            calls++
            ProviderProbeReply(AndroidSharedStateHolder.ProviderTestOutcome(
                false, "timeout", failureKind = "transient",
            ))
        }
        assertFalse(result.ok)
        assertEquals(3, calls)
        assertTrue(result.summary.contains("均未通过"))
        assertEquals(targets.map { it.modelId }, result.attempts.map { it.modelId })
        assertTrue(result.attempts.all { !it.ok && it.summary == "timeout" })
    }

    @Test fun interruptedRequestStopsBatchWithoutClaimingAllTested() = runTest {
        var calls = 0
        val result = testProviderModelsUntilSuccess(targets, { _, _, _ -> }) {
            calls++
            ProviderProbeReply(AndroidSharedStateHolder.ProviderTestOutcome(false, "已取消"), interrupted = true)
        }
        assertFalse(result.ok)
        assertEquals(1, calls)
        assertTrue(result.summary.contains("未完成"))
        assertFalse(result.summary.contains("均未通过"))
        assertEquals(1, result.attempts.size)
        assertEquals("已取消", result.attempts.single().summary)
    }
}
