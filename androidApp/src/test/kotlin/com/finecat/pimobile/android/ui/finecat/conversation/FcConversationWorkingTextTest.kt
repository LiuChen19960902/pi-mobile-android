package com.finecat.pimobile.android.ui.finecat.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcConversationWorkingTextTest {
    @Test
    fun disconnectedRunningShowsRecoveryInsteadOfWorking() {
        assertNull(fineCatWorkingText(connected = true, running = false))
        assertNull(fineCatWorkingText(connected = false, running = false))
        assertEquals("Working", fineCatWorkingText(connected = true, running = true))
        assertEquals("连接中断，状态待恢复", fineCatWorkingText(connected = false, running = true))
    }
}
