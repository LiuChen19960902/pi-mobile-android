package com.finecat.pimobile.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationScrollStateTest {
    @Test
    fun `short history fills only a settled idle viewport`() {
        val shortPage = HistoryViewportPagingState(
            cursor = "e21", hasMore = true, isLoading = false, isLatestLoading = false,
            hasError = false, connected = true, layoutReady = true,
            canScroll = false, isScrolling = false
        )
        assertTrue(shouldFillHistoryViewport(shortPage))
        // 首帧尚未测量、新页仍在途、滚底动画与用户拖动期间都不能继续补页。
        assertFalse(shouldFillHistoryViewport(shortPage.copy(layoutReady = false)))
        assertFalse(shouldFillHistoryViewport(shortPage.copy(isLoading = true)))
        assertFalse(shouldFillHistoryViewport(shortPage.copy(isScrolling = true)))
        // 已撑满、到头、断线或失败时停止，不能在同一错误上无限自动重试。
        assertFalse(shouldFillHistoryViewport(shortPage.copy(canScroll = true)))
        assertFalse(shouldFillHistoryViewport(shortPage.copy(hasMore = false)))
        assertFalse(shouldFillHistoryViewport(shortPage.copy(cursor = null)))
        assertFalse(shouldFillHistoryViewport(shortPage.copy(hasError = true)))
        assertFalse(shouldFillHistoryViewport(shortPage.copy(connected = false)))
    }

    @Test
    fun `bottom delta includes the full last row and composer padding`() {
        assertEquals(
            1_000f,
            timelineBottomScrollDelta(
                lastItemOffset = 0,
                lastItemSize = 1_600,
                viewportEndOffset = 800,
                afterContentPadding = 200
            )
        )
        assertEquals(
            0f,
            timelineBottomScrollDelta(
                lastItemOffset = 500,
                lastItemSize = 100,
                viewportEndOffset = 800,
                afterContentPadding = 200
            )
        )
    }

    @Test
    fun `the list is pinned only when it cannot scroll farther forward`() {
        assertTrue(isTimelinePinnedToBottom(totalItems = 0, canScrollForward = false))
        assertTrue(isTimelinePinnedToBottom(totalItems = 8, canScrollForward = false))
        assertFalse(isTimelinePinnedToBottom(totalItems = 8, canScrollForward = true))
    }

    @Test
    fun `streamed layout changes cannot unpin without a scroll`() {
        assertFalse(
            shouldPublishPinnedState(
                isScrolling = false,
                pinned = false,
                isProgrammaticScroll = false
            )
        )
        assertTrue(
            shouldPublishPinnedState(
                isScrolling = true,
                pinned = false,
                isProgrammaticScroll = false
            )
        )
        assertFalse(
            shouldPublishPinnedState(
                isScrolling = true,
                pinned = false,
                isProgrammaticScroll = true
            )
        )
        assertTrue(
            shouldPublishPinnedState(
                isScrolling = false,
                pinned = true,
                isProgrammaticScroll = true
            )
        )
    }

    @Test
    fun `tail follows only when opening a session or already pinned`() {
        // 打开会话首次有内容：贴底一次（原型“打开会话看最新内容”）。
        assertTrue(
            shouldScrollToLatest(
                itemCount = 3, isUserScrolling = false,
                initialPositionApplied = false, pinnedToBottom = false
            )
        )
        // 已定位后：只在用户原本贴底时跟随尾部（发送/流式增量）。
        assertTrue(
            shouldScrollToLatest(
                itemCount = 4, isUserScrolling = false,
                initialPositionApplied = true, pinnedToBottom = true
            )
        )
        // 阅读历史（已上滑离开底部）不被新增内容拉回。
        assertFalse(
            shouldScrollToLatest(
                itemCount = 5, isUserScrolling = false,
                initialPositionApplied = true, pinnedToBottom = false
            )
        )
        // 用户拖动/惯性期间不介入；无内容不滚。
        assertFalse(
            shouldScrollToLatest(
                itemCount = 5, isUserScrolling = true,
                initialPositionApplied = true, pinnedToBottom = true
            )
        )
        assertFalse(
            shouldScrollToLatest(
                itemCount = 0, isUserScrolling = false,
                initialPositionApplied = false, pinnedToBottom = true
            )
        )
    }

    @Test
    fun `older history requires a real backward user scroll near the top`() {
        assertFalse(
            shouldLoadOlderHistory(
                HistoryPagingGestureState(0, false, true, false)
            )
        )
        assertFalse(
            shouldLoadOlderHistory(
                HistoryPagingGestureState(0, true, true, true)
            )
        )
        assertFalse(
            shouldLoadOlderHistory(
                HistoryPagingGestureState(0, true, false, false)
            )
        )
        assertFalse(
            shouldLoadOlderHistory(
                HistoryPagingGestureState(3, true, true, false)
            )
        )
        assertTrue(
            shouldLoadOlderHistory(
                HistoryPagingGestureState(2, true, true, false)
            )
        )
    }

    @Test
    fun `initial history overlay only covers a cold baseline load`() {
        assertTrue(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = false,
                coldLoadStarted = false
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = true,
                coldLoadStarted = false
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = true,
                hasLocalContent = true,
                coldLoadStarted = true
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = false,
                isLoadingOlder = false,
                hasLocalContent = false,
                coldLoadStarted = true
            )
        )
        assertTrue(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = true,
                coldLoadStarted = true
            )
        )
    }

    @Test
    fun `initial history overlay yields to the failure escape hatch`() {
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = false,
                coldLoadStarted = true,
                hasError = true
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = false,
                isLoadingOlder = false,
                hasLocalContent = false,
                coldLoadStarted = false,
                hasError = true
            )
        )
        assertTrue(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = false,
                coldLoadStarted = true,
                hasError = false
            )
        )
    }

    @Test
    fun `history progress text follows the syncing states`() {
        assertEquals(
            "正在从 Mobile Gateway 同步会话内容…",
            historyLoadingProgressText(0, null)
        )
        assertEquals(
            "正在自动加载更早记录 · 已同步 12 个事件",
            historyLoadingProgressText(12, null)
        )
        assertEquals(
            "正在加载历史记录 · 12/20",
            historyLoadingProgressText(12, 20)
        )
    }
}
