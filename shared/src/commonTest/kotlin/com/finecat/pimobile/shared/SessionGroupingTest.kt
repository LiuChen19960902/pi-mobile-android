package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.domain.SessionGrouping
import com.finecat.pimobile.shared.domain.SessionSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionGroupingTest {
    private fun session(id: String, cwd: String?, at: Double) = SessionSummary(
        id = id,
        title = id,
        lastActivityEpochSeconds = at,
        isRunning = false,
        hasUnread = false,
        cwd = cwd
    )

    @Test
    fun groupsByCwdKeepingInGroupOrderAndSortingGroupsByLatestActivity() {
        val groups = SessionGrouping.group(
            listOf(
                session("a1", "/home/user/code/pi-mobile-android", 300.0),
                session("b1", "/tmp", 250.0),
                session("a2", "/home/user/code/pi-mobile-android", 200.0),
                session("b2", "/tmp", 100.0)
            )
        )
        assertEquals(listOf("pi-mobile-android", "tmp"), groups.map { it.label })
        assertEquals(listOf("a1", "a2"), groups[0].sessions.map { it.id })
        assertEquals(listOf("b1", "b2"), groups[1].sessions.map { it.id })
        assertEquals("/tmp", groups[1].cwd)
    }

    @Test
    fun groupOrderFollowsEachGroupsLatestActivity() {
        val groups = SessionGrouping.group(
            listOf(
                session("b1", "/tmp", 500.0),
                session("a1", "/home/user/code/pi-mobile-android", 100.0)
            )
        )
        assertEquals(listOf("tmp", "pi-mobile-android"), groups.map { it.label })
    }

    @Test
    fun sessionsWithoutCwdShareUnknownGroup() {
        val groups = SessionGrouping.group(
            listOf(session("n1", null, 10.0), session("n2", null, 5.0))
        )
        assertEquals(1, groups.size)
        assertEquals(SessionGrouping.UNKNOWN_CWD_LABEL, groups.single().label)
        assertEquals(null, groups.single().cwd)
        assertEquals(listOf("n1", "n2"), groups.single().sessions.map { it.id })
    }

    @Test
    fun sameDirectoryNameDisambiguatesWithParentSegment() {
        val groups = SessionGrouping.group(
            listOf(
                session("a1", "/home/user/code/tmp", 300.0),
                session("b1", "/home/user/code/pi-mobile-android/tmp", 200.0),
                session("c1", "/var/tmp", 100.0)
            )
        )
        assertEquals(
            listOf("code/tmp", "pi-mobile-android/tmp", "var/tmp"),
            groups.map { it.label }
        )
    }

    @Test
    fun duplicateNamesDoNotLengthenUnrelatedDirectoryLabels() {
        val sessions = listOf(
            session("a", "/home/work/project", 30.0),
            session("b", "/tmp/project", 20.0),
            session("c", "/workpace/tmp-workpace", 10.0),
        )
        val expected = mapOf<String?, String>(
            "/home/work/project" to "work/project",
            "/tmp/project" to "tmp/project",
            "/workpace/tmp-workpace" to "tmp-workpace",
        )
        assertEquals(expected, SessionGrouping.group(sessions).associate { it.cwd to it.label })
        assertEquals(expected, SessionGrouping.group(sessions.reversed()).associate { it.cwd to it.label })
    }

    @Test
    fun onlyStillConflictingNamesGrowPastOneParent() {
        val groups = SessionGrouping.group(listOf(
            session("a", "/home/one/work/project", 40.0),
            session("b", "/home/two/work/project", 30.0),
            session("c", "/home/other/project", 20.0),
            session("d", "/home/unique", 10.0),
        ))
        assertEquals(listOf("one/work/project", "two/work/project", "other/project", "unique"),
            groups.map { it.label })
    }

    @Test
    fun shorterCompletePathRemainsDistinctFromLongerPathWithSameSuffix() {
        val groups = SessionGrouping.group(listOf(
            session("a", "/project", 20.0),
            session("b", "/work/project", 10.0),
        ))
        assertEquals(listOf("project", "work/project"), groups.map { it.label })
        assertEquals(listOf("/project", "/work/project"), groups.map { it.cwd })
    }

    @Test
    fun slashVariantsStopExpandingWithoutChangingDirectoryIdentity() {
        val groups = SessionGrouping.group(listOf(
            session("a", "/work/project", 30.0),
            session("b", "/work/project/", 20.0),
            session("c", "/home/unique", 10.0),
            session("d", "/", 5.0),
        ))
        assertEquals(listOf("work/project", "work/project", "unique", "/"), groups.map { it.label })
        assertEquals(listOf("/work/project", "/work/project/", "/home/unique", "/"), groups.map { it.cwd })
    }

    @Test
    fun blankCwdJoinsUnknownGroup() {
        val groups = SessionGrouping.group(
            listOf(session("b1", "   ", 20.0), session("n1", null, 10.0))
        )
        assertEquals(1, groups.size)
        assertEquals(SessionGrouping.UNKNOWN_CWD_LABEL, groups.single().label)
        assertEquals(listOf("b1", "n1"), groups.single().sessions.map { it.id })
    }

    @Test
    fun identicalTailSegmentsFallBackToFullPath() {
        val groups = SessionGrouping.group(
            listOf(
                session("a1", "/a/x/y/z/w/v/u/t/proj", 200.0),
                session("b1", "/b/x/y/z/w/v/u/t/proj", 100.0)
            )
        )
        assertEquals(
            listOf("a/x/y/z/w/v/u/t/proj", "b/x/y/z/w/v/u/t/proj"),
            groups.map { it.label }
        )
    }

    @Test
    fun emptyInputYieldsNoGroups() {
        assertTrue(SessionGrouping.group(emptyList()).isEmpty())
    }
}
