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

    @Test fun editingTwoModelsDoesNotStopOnOneSuccessfulModel() = runTest {
        val calls = mutableListOf<String>()
        val result = testProviderModelsUntilSuccess(targets, { _, _, _ -> },
            requiredModelGroups = listOf(setOf("first"), setOf("second")),
        ) { target ->
            calls += target.modelId!!
            ProviderProbeReply(AndroidSharedStateHolder.ProviderTestOutcome(true, "ok", latencyMs = 32))
        }
        assertTrue(result.ok)
        assertEquals(listOf("first", "second"), calls)
        assertEquals(listOf(32L, 32L), result.attempts.map { it.latencyMs })
        assertEquals(listOf("anthropic-messages", "openai-completions"), result.attempts.map { it.api })
    }

    @Test fun anotherModelsSuccessCannotHideRequiredModelsFailure() = runTest {
        val result = testProviderModelsUntilSuccess(targets.take(2), { _, _, _ -> },
            requiredModelGroups = listOf(setOf("first"), setOf("second")),
        ) { target ->
            ProviderProbeReply(AndroidSharedStateHolder.ProviderTestOutcome(
                target.modelId == "second", "reply", httpStatus = if (target.modelId == "second") 200 else 401,
                diagnosticCode = if (target.modelId == "first") "auth-rejected" else null,
            ))
        }
        assertFalse(result.ok)
        assertTrue(result.summary.contains("1 个未通过"))
        assertEquals("auth-rejected", result.attempts.first().diagnosticCode)
    }

    @Test fun stableDiagnosticsDescribeFailuresWithoutUpstreamText() {
        assertEquals("认证被拒", providerTestSummary(false, null, 401))
        assertEquals("请求超时", providerTestSummary(false, "timeout", null))
        assertEquals("未收到有效模型响应", providerTestSummary(false, "invalid-response", 200))
        assertEquals("上游拒绝请求", providerTestSummary(false, "unknown", 400))
        assertTrue(providerTestHint("timeout", null).contains("重试"))
        assertTrue(providerTestHint(null, 401).contains("API Key"))
        assertTrue(providerTestHint("invalid-response", 200).contains("协议"))
    }
}
