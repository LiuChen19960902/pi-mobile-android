package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.facade.SharedSessionListSnapshot
import com.finecat.pimobile.shared.facade.SharedSessionListStore
import com.finecat.pimobile.shared.facade.SharedSessionSummarySnapshot
import com.finecat.pimobile.shared.facade.SharedMviEvent
import com.finecat.pimobile.shared.facade.SharedMviEventObserver
import com.finecat.pimobile.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedSessionListStoreTest {
    private fun makeStore() = SharedSessionListStore(
        newSessionTitle = "新建会话",
        remoteSessionPrefix = "远端会话 ",
        blankSessionPrefix = "空白会话 "
    )

    @Test
    fun subscriptionPushesOnlyChangedSessionListSnapshots() {
        val store = makeStore()
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))

        store.addKnownSession("push-session", 100.0)
        store.addKnownSession("push-session", 100.0)

        assertEquals(listOf("snapshot", "transition"), events.map { it.kind })
        assertEquals(listOf(0L, 1L), events.map { it.sequence })
        assertEquals(
            "push-session",
            decode(events.last().statePayloadJson).sessions.single().id
        )
        assertEquals("[]", events.last().effectsJson)
    }

    @Test
    fun restoredPersistenceAndAllSessionIntentsUseOneKmpState() {
        val store = makeStore()
        val restored = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot(
                    id = "existing",
                    title = "旧标题",
                    lastActivityEpochSeconds = 10.0,
                    isRunning = false,
                    hasUnread = true
                )
            ),
            selectedSessionId = "selected"
        )
        assertTrue(store.restore(wireJson.encodeToString(restored)).isSuccess)

        // pi 会话列表：id/cwd/name/created/modified(ISO)/messageCount/firstMessage/running。
        val remote = """[
            {"id":"existing","cwd":"/tmp/existing","modified":"2026-09-01T00:00:02Z","messageCount":4,"firstMessage":"第一条","running":true},
            {"id":"new","cwd":"/tmp/new","modified":"2026-09-01T00:00:03Z","messageCount":2}
        ]"""
        var snapshot = decode(store.receiveRemoteSessions(remote).snapshotJson)
        assertEquals(listOf("new", "existing"), snapshot.sessions.map { it.id })
        assertEquals("new", snapshot.sessions.first().title)
        assertTrue(snapshot.sessions.last().isRunning)
        assertTrue(snapshot.sessions.last().hasUnread)
        assertEquals(1_788_220_803.0, snapshot.sessions.first().lastActivityEpochSeconds)

        // 内部事件视图：agent_start 归一为 turn/start。
        val event = """{
            "sessionId":"new","seq":1,"time":1788220805.0,
            "event":{"type":"turn/start"}
        }"""
        snapshot = decode(store.receiveEvent(event, insertedAtEpochSeconds = 9_999.0).snapshotJson)
        assertEquals("new", snapshot.sessions.first().id)
        assertTrue(snapshot.sessions.first().isRunning)
        assertTrue(snapshot.sessions.first().hasUnread)

        snapshot = decode(store.selectSession("new").snapshotJson)
        assertEquals("new", snapshot.selectedSessionId)
        snapshot = decode(store.markRead("new").snapshotJson)
        assertFalse(snapshot.sessions.first().hasUnread)

        snapshot = decode(store.messageSent("created", 8_000.0).snapshotJson)
        assertEquals(8_000.0, snapshot.sessions.first { it.id == "created" }.lastActivityEpochSeconds)
        snapshot = decode(store.addKnownSession("known", 8_001.0).snapshotJson)
        assertEquals(8_001.0, snapshot.sessions.first { it.id == "known" }.lastActivityEpochSeconds)
    }

    @Test
    fun remotePiSessionTitlePrefersNameThenCwdBasenameThenRemoteFallback() {
        val store = makeStore()
        val remote = """[
            {"id":"named","name":"重构计划","cwd":"/tmp/named"},
            {"id":"blank","cwd":"/tmp/blank","messageCount":0,"firstMessage":"(no messages)"},
            {"id":"anonymous"}
        ]"""
        val snapshot = decode(store.receiveRemoteSessions(remote).snapshotJson)
        val titles = snapshot.sessions.associateBy { it.id }
        assertEquals("重构计划", titles.getValue("named").title)
        assertTrue(titles.getValue("blank").title.startsWith("空白会话"))
        assertTrue(titles.getValue("anonymous").title.startsWith("远端会话"))
    }

    @Test
    fun malformedPlatformPayloadReturnsStructuredErrorAndKeepsState() {
        val store = makeStore()
        val initial = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot("s1", "稳定状态", 10.0, false, false)
            )
        )
        assertTrue(store.restore(wireJson.encodeToString(initial)).isSuccess)

        val failure = store.receiveRemoteSessions("not-json")
        assertFalse(failure.isSuccess)
        assertEquals("session-list-remote-sessions-failed", failure.errorCode)
        assertNotNull(failure.errorMessage)
        assertNull(failure.snapshotJson)

        val unchanged = decode(store.snapshot().snapshotJson)
        assertEquals(initial, unchanged)
    }

    @Test
    fun unchangedStreamingEventDoesNotCopyWholeSnapshotAcrossBridge() {
        val store = makeStore()
        val initial = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot("s1", "已有标题", 10.0, true, false)
            ),
            selectedSessionId = "s1"
        )
        assertTrue(store.restore(wireJson.encodeToString(initial)).isSuccess)

        val unchanged = store.receiveEvent(
            """{"sessionId":"s1","seq":2,"time":11,"event":{"type":"assistant/chunk","text":"delta"}}""",
            insertedAtEpochSeconds = 12.0
        )

        assertTrue(unchanged.isSuccess)
        assertNull(unchanged.snapshotJson)
        assertEquals(initial, decode(store.snapshot().snapshotJson))
    }

    @Test
    fun snapshotCatchesSerializationFailureAndFailedMutationDoesNotPoisonState() {
        val store = makeStore()
        val initial = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot("stable", "稳定状态", 10.0, false, false)
            )
        )
        assertTrue(store.restore(wireJson.encodeToString(initial)).isSuccess)

        val failure = store.addKnownSession("invalid", Double.NaN)
        assertFalse(failure.isSuccess)
        assertEquals("session-list-known-session-failed", failure.errorCode)
        assertNull(failure.snapshotJson)

        val snapshot = store.snapshot()
        assertTrue(snapshot.isSuccess)
        assertEquals(initial, decode(snapshot.snapshotJson))
    }

    private fun decode(json: String?): SharedSessionListSnapshot =
        wireJson.decodeFromString(requireNotNull(json))
}
