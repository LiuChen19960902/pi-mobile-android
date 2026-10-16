package com.finecat.pimobile.android.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayForegroundLifecycleTest {
    private class Fixture {
        var needed = false
        var promotionSucceeds = true
        var latestSystemStartId = 1
        val events = mutableListOf<String>()
        val lifecycle = GatewayForegroundLifecycle(
            promote = { events += "promote"; promotionSucceeds },
            isNeeded = { needed },
            stopSelf = { id -> events += "stop:$id"; id == latestSystemStartId },
            removeNotification = { events += "remove" },
        )
    }

    @Test fun initialIdleCannotStopAnUnregisteredService() {
        val f = Fixture()
        f.lifecycle.stopIfIdle()
        assertTrue(f.events.isEmpty())
    }

    @Test fun runFinishingBeforeStartDeliveryStillPromotesBeforeStopping() {
        val f = Fixture()
        f.needed = true
        f.needed = false // 快速失败，系统还没有送达 onStartCommand。
        assertTrue(f.lifecycle.beginStart(1))
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote", "stop:1", "remove"), f.events)
    }

    @Test fun activeRunRemainsForegroundUntilItActuallyBecomesIdle() {
        val f = Fixture()
        f.needed = true
        f.lifecycle.beginStart(1)
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote"), f.events)
        f.needed = false
        f.lifecycle.stopIfIdle()
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote", "stop:1", "remove"), f.events)
    }

    @Test fun staleIdleObservationDoesNotStopANewlyActiveRun() {
        val f = Fixture()
        f.lifecycle.beginStart(1)
        f.needed = true // 观察器即将处理旧的 false，但运行状态已经更新。
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote"), f.events)
    }

    @Test fun pendingNewStartPreventsOldStopAndNotificationRemoval() {
        val f = Fixture()
        f.lifecycle.beginStart(1)
        f.latestSystemStartId = 2
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote", "stop:1"), f.events)
        f.lifecycle.beginStart(2)
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote", "stop:1", "promote", "stop:2", "remove"), f.events)
    }

    @Test fun newActiveStartRemainsRegisteredAfterOldStopWasRejected() {
        val f = Fixture()
        f.lifecycle.beginStart(1)
        f.latestSystemStartId = 2
        f.lifecycle.stopIfIdle()
        f.needed = true
        f.lifecycle.beginStart(2)
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote", "stop:1", "promote"), f.events)
    }

    @Test fun promotionFailureStopsOnlyItsOwnStartAndDoesNotRemoveNotification() {
        val f = Fixture()
        f.promotionSucceeds = false
        f.latestSystemStartId = 2
        assertFalse(f.lifecycle.beginStart(1))
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote", "stop:1"), f.events)
        f.promotionSucceeds = true
        f.needed = true
        assertTrue(f.lifecycle.beginStart(2))
        f.lifecycle.stopIfIdle()
        assertEquals(listOf("promote", "stop:1", "promote"), f.events)
    }
}
