package com.finecat.pimobile.android

import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewaySlashCommand
import com.finecat.pimobile.shared.protocol.JsonValue
import org.junit.Assert.*
import org.junit.Test

class InputAssistStateTest {
    private fun state(): InputAssistState { var n = 0; return InputAssistState { "q${++n}" } }

    @Test fun resourceSnapshotHonorsContextEnabledSkillsAndMobileShadows() {
        val s = state()
        s.reset(InputAssistContext(cwd = "/project"))
        val id = s.resourcesRequest()!!.correlationId
        val commands = listOf(GatewaySlashCommand("skill:audit", source = "skill", disableModelInvocation = true),
            GatewaySlashCommand("evaluate", source = "prompt"), GatewaySlashCommand("model", source = "prompt"),
            GatewaySlashCommand("custom", source = "extension"))
        s.receive(GatewayFrame("input-resources", queryId = id, cwd = "/wrong", commands = commands, skillCommandsEnabled = true))
        assertTrue(s.resourcesLoading)
        s.receive(GatewayFrame("input-resources", queryId = id, cwd = "/project", commands = commands, skillCommandsEnabled = true, complete = false))
        assertEquals(listOf("skill:audit", "evaluate"), s.commands.map { it.name })
        assertTrue(s.previewIncomplete)
        s.reset(InputAssistContext("session"))
        val next = s.resourcesRequest()!!.correlationId
        s.receive(GatewayFrame("input-resources", queryId = next, sessionId = "session", commands = commands, skillCommandsEnabled = false, complete = true))
        assertEquals(listOf("evaluate"), s.commands.map { it.name })
    }

    @Test fun oldQuerySuccessAndFailureCannotFinishLatestOrCrossSessionQuery() {
        val s = state(); s.reset(InputAssistContext("s1"))
        val old = s.completionsRequest("@a")!!.correlationId
        val current = s.completionsRequest("@b")!!.correlationId
        s.fail("input-completions", old, "old failure")
        s.receive(GatewayFrame("input-completions", queryId = old, sessionId = "s1", token = "@a", prefix = "@a"))
        assertTrue(s.completionsLoading)
        s.receive(GatewayFrame("input-completions", queryId = current, sessionId = "s2", token = "@b", prefix = "@b"))
        assertTrue(s.completionsLoading)
        s.receive(GatewayFrame("input-completions", queryId = current, sessionId = "s1", token = "@b", prefix = "@b"))
        assertFalse(s.completionsLoading)
        s.fail("input-completions", current, "late failure")
        assertNull(s.completionsError)
    }

    @Test fun cancellationAndReconnectInvalidateResultsWithoutChangingDraftGeneration() {
        val s = state(); s.reset(InputAssistContext(cwd = "/a"))
        val id = s.completionsRequest("@file")!!.correlationId
        val cancel = s.completionsRequest("")!!
        assertTrue(cancel.payload.contains("\"token\":\"\""))
        s.fail("input-completions", id, "cancelled")
        assertNull(s.completionsError)
        s.reset(InputAssistContext(cwd = "/b"))
        assertEquals(2, s.generation)
        s.receive(GatewayFrame("input-completions", queryId = id, cwd = "/a", token = "@file", prefix = "@file"))
        assertEquals("", s.token)
        assertTrue(s.completions.isEmpty())
        s.reset(InputAssistContext())
        assertNull(s.resourcesRequest())
    }
}
