package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.GatewayStatusEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionStatusBarTest {
    @Test
    fun joinsAtMostFourStatusesAndDropsBlankContent() {
        val entries = (1..6).map { GatewayStatusEntry(key = "k$it", text = "s$it") }
        assertEquals("s1 · s2 · s3 · s4", extensionStatusText(entries))
        assertNull(extensionStatusText(emptyList()))
        assertNull(extensionStatusText(listOf(GatewayStatusEntry("k", "   "))))
        // 空白项先过滤再取前 4 条：第 5 条有内容时不能被前 4 条空文本挤掉。
        val withBlanks = listOf(
            GatewayStatusEntry("a", "   "),
            GatewayStatusEntry("b", ""),
            GatewayStatusEntry("c", "   "),
            GatewayStatusEntry("d", ""),
            GatewayStatusEntry("e", "agent running"),
        )
        assertEquals("agent running", extensionStatusText(withBlanks))
    }

    @Test
    fun trimsEdgesWithoutInterpretingExtensionContent() {
        assertEquals(
            "2 agents running · 1 queued · Waiting for review",
            extensionStatusText(listOf(
                GatewayStatusEntry("subagents", " 2 agents running · 1 queued "),
                GatewayStatusEntry("arbitrary-extension", " Waiting for review "),
            )),
        )
    }

    @Test
    fun keepsGatewayOrder() {
        val entries = listOf(
            GatewayStatusEntry("a", "1 agent running"),
            GatewayStatusEntry("b", "queued"),
        )
        assertEquals("1 agent running · queued", extensionStatusText(entries))
    }
}
