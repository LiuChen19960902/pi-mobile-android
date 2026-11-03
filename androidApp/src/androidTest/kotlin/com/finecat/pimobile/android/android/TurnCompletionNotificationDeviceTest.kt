package com.finecat.pimobile.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TurnCompletionNotificationDeviceTest {
    @Before
    fun skipStoredConnect() {
        AndroidSharedStateHolder.skipStoredConnectForTest = true
    }

    @After
    fun restoreStoredConnect() {
        AndroidSharedStateHolder.skipStoredConnectForTest = false
    }

    @Test
    fun backgroundTurnEndPostsNotificationAndClickSelectsTheSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PiAndroidApplication
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                app.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val holder = app.graph.stateHolder

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            waitUntil { !app.graph.isAppInForeground }
            testOnMain {
                holder.wirePayload =
                    """{"kind":"sessions","items":[{"id":"notif-session","cwd":"/tmp/notif","modified":"2026-11-02T10:00:00.000Z","messageCount":1,"firstMessage":"通知会话","running":true}]}"""
                holder.submitWirePayload()
            }
            waitUntil { holder.snapshot.sessions.any { it.id == "notif-session" } }

            testOnMain {
                holder.wirePayload =
                    """{"kind":"event","sessionId":"notif-session","seq":1,"time":1.0,"event":{"type":"agent_settled"}}"""
                holder.submitWirePayload()
            }
            waitUntil {
                manager.activeNotifications.any { it.tag == "turn-complete:notif-session" }
            }
            val posted = manager.activeNotifications.first { it.tag == "turn-complete:notif-session" }.notification
            assertEquals("通知会话", posted.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Agent 已完成本轮", posted.extras.getString(Notification.EXTRA_TEXT))
            assertTrue(posted.contentIntent != null)
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
        manager.cancelAll()

        val openIntent = android.content.Intent(app, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, "notif-session")
        ActivityScenario.launch<MainActivity>(openIntent).use {
            waitUntil { holder.snapshot.selectedSessionId == "notif-session" }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("condition not met within 10s")
    }
}
