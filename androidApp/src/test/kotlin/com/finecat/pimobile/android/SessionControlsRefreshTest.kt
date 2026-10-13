package com.finecat.pimobile.android

import com.finecat.pimobile.shared.facade.SharedMobileStore
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionControlsRefreshTest {
    private val refresh = SessionControlsRefresh()
    private val store = SharedMobileStore().apply { selectSession("s1") }

    private fun accept(json: String): String? {
        val snapshot = store.acceptFrame(json)
        return refresh.afterFrame(GatewayWireDecoder.decode(json), snapshot.selectedSessionId)
    }

    private val coldHistory = """{"kind":"history","sessionId":"s1","messages":[],"contextUsage":null}"""
    private val subscribed = """{"kind":"subscribed","sessionId":"s1"}"""
    private val usageState = """{"kind":"state","sessionId":"s1","contextUsage":{"percent":74.0,"tokens":148000,"contextWindow":200000}}"""

    @Test fun coldHistoryBeforeSubscribeRefreshesWithoutChangingModel() {
        assertNull(accept(coldHistory))
        assertEquals("s1", accept(subscribed))
        assertNull(accept(usageState))
        assertEquals(74.0, store.snapshot().sessionStates["s1"]!!.contextUsage!!.percent)
    }

    @Test fun lateColdHistoryAfterLiveStateRequestsFreshStatistics() {
        assertEquals("s1", accept(subscribed))
        accept(usageState)
        assertEquals("s1", accept(coldHistory))
        assertNull(store.snapshot().sessionStates["s1"]!!.contextUsage)
        accept(usageState)
        assertEquals(148000L, store.snapshot().sessionStates["s1"]!!.contextUsage!!.tokens)
    }

    @Test fun validHistoryDoesNotRequestAnExtraRefresh() {
        accept(subscribed)
        assertNull(accept(usageState.replace("\"state\"", "\"history\"")))
    }

    @Test fun sdkUnknownAfterCompactionIsNotReplacedByOldUsage() {
        accept(subscribed)
        accept(usageState)
        val unknown = """{"kind":"history","sessionId":"s1","contextUsage":{"tokens":null,"percent":null,"contextWindow":200000}}"""
        assertNull(accept(unknown))
        val usage = store.snapshot().sessionStates["s1"]!!.contextUsage!!
        assertNull(usage.tokens)
        assertNull(usage.percent)
        assertEquals(200000L, usage.contextWindow)
    }

    @Test fun switchingSessionIgnoresPreviousSessionResponses() {
        accept(subscribed)
        refresh.reset()
        store.selectSession("s2")
        assertNull(accept(coldHistory))
        assertNull(accept(subscribed))
        assertEquals("s2", accept("""{"kind":"subscribed","sessionId":"s2"}"""))
    }

    @Test fun reconnectRequiresNewSubscribeAcknowledgement() {
        accept(subscribed)
        refresh.reset()
        assertNull(accept(coldHistory))
        assertEquals("s1", accept(subscribed))
    }

    @Test fun stateReturningNullDoesNotCreateARequestLoop() {
        accept(subscribed)
        assertNull(accept("""{"kind":"state","sessionId":"s1","contextUsage":null}"""))
    }

    @Test fun emptyHomeHasNoSessionToRefresh() {
        refresh.reset()
        store.selectSession(null)
        assertNull(accept(subscribed))
        assertNull(accept(coldHistory))
    }
}
