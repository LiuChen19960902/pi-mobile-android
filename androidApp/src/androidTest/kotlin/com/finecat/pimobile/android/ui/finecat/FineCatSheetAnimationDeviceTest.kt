package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcSheetHost
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 受控帧钟检查真实位移；编译不能记为设备动画已验证。 */
class FineCatSheetAnimationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()

    private fun cardTop(): Float = compose.onNodeWithTag("finecat.sheetCard")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun assertSlidesFromBelow() {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val early = cardTop()
        compose.mainClock.advanceTimeBy(96)
        val middle = cardTop()
        compose.mainClock.advanceTimeBy(600)
        val settled = cardTop()
        assertTrue("入场必须有实际向上位移", early > middle + 1f)
        assertTrue("中途尚未到终点", middle > settled + 1f)
        val root = compose.onNodeWithTag("test.root").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("finecat.sheetCard").fetchSemanticsNode().boundsInRoot
        assertEquals("动画结束背景仍贴底", root.bottom, card.bottom, 1f)
        compose.onAllNodesWithTag("finecat.sheetCard").assertCountEquals(1)
    }

    private fun assertSlidesDownAndDisposes(settled: Float) {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val early = cardTop()
        compose.mainClock.advanceTimeBy(96)
        val middle = cardTop()
        assertTrue("关闭先保留真实卡片并向下移动", early >= settled)
        assertTrue("退场中途继续下移", middle > early + 1f)
        compose.onNodeWithTag("finecat.sheetClosing").assertExists()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("finecat.sheetCard").assertDoesNotExist()
        compose.onNodeWithTag("finecat.sheetClosing").assertDoesNotExist()
    }

    @Test fun hostedSheetSlidesBothWays_disposesDraftOnlyAfterExit_andReopensFresh() {
        val mounted = mutableStateOf(false)
        var disposals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { FineCatTheme { Box(Modifier.fillMaxSize().testTag("test.root")) {
            FcSheetHost(visible = mounted.value) {
                DisposableEffect(Unit) { onDispose { disposals++ } }
                FcSheet(visible = true, onDismiss = { mounted.value = false }, title = "合成面板") {
                    Box(Modifier.fillMaxWidth().height(240.dp))
                }
            }
        } } }
        compose.onNodeWithTag("finecat.sheetCard").assertDoesNotExist()
        compose.runOnIdle { mounted.value = true }
        assertSlidesFromBelow()
        val settled = cardTop()
        compose.runOnIdle { mounted.value = false }
        assertEquals("标志关闭不能立即销毁草稿", 0, disposals)
        assertSlidesDownAndDisposes(settled)
        assertEquals(1, disposals)
        compose.runOnIdle { mounted.value = true }
        assertSlidesFromBelow()
    }

    @Test fun hostedOutsideConfirmationIsOnce_onlyAfterValidation_andBackStillCancels() {
        val visible = mutableStateOf(false)
        val valid = mutableStateOf(false)
        var confirmations = 0
        var dismissals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { FineCatTheme { Box(Modifier.fillMaxSize().testTag("test.root")) {
            FcSheetHost(visible = visible.value) {
                FcSheet(visible = true, title = "合成编辑", onDismiss = { dismissals++; visible.value = false },
                    onOutsideClick = { confirmations++; if (valid.value) visible.value = false }) {
                    Box(Modifier.fillMaxWidth().height(240.dp))
                }
            }
        } } }
        compose.runOnIdle { visible.value = true }
        assertSlidesFromBelow()
        // 卡内留白仍不能穿透成保存动作。
        compose.onNodeWithTag("finecat.sheetCard").performTouchInput { click(center) }
        assertEquals(0, confirmations)
        compose.onNodeWithTag("finecat.sheetScrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(8f, 8f)) }
        assertEquals(1, confirmations)
        assertEquals(0, dismissals)
        assertTrue("校验失败不收卡", visible.value)
        compose.onNodeWithTag("finecat.sheetClosing").assertDoesNotExist()
        val settled = cardTop()
        compose.runOnIdle { valid.value = true }
        compose.onNodeWithTag("finecat.sheetScrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(8f, 8f)) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("finecat.sheetClosing").performTouchInput { click(androidx.compose.ui.geometry.Offset(8f, 8f)) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals("退场不能重复确认", 2, confirmations)
        assertEquals("退场时返回不能穿透底页", 0, dismissals)
        assertSlidesDownAndDisposes(settled)
        compose.runOnIdle { visible.value = true }
        assertSlidesFromBelow()
        val reopenedTop = cardTop()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, dismissals)
        assertEquals(2, confirmations)
        assertSlidesDownAndDisposes(reopenedTop)
    }

    @Test fun nullablePayloadSurvivesDeletionUntilExit_andCanReopenDuringExit() {
        val selected = mutableStateOf<String?>(null)
        val backingRows = mutableStateOf(listOf("模型A"))
        var disposals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { FineCatTheme { Box(Modifier.fillMaxSize().testTag("test.root")) {
            FcSheetHost(value = selected.value) { model ->
                DisposableEffect(Unit) { onDispose { disposals++ } }
                var draft by remember { mutableStateOf(model) }
                FcSheet(visible = true, onDismiss = { selected.value = null }, title = "模型详情") {
                    Text(draft, Modifier.testTag("test.payload"))
                    Box(Modifier.fillMaxWidth().height(240.dp))
                }
            }
        } } }
        compose.runOnIdle { selected.value = backingRows.value.first() }
        assertSlidesFromBelow()
        val settled = cardTop()
        compose.runOnIdle { backingRows.value = emptyList(); selected.value = null }
        compose.mainClock.advanceTimeBy(112)
        compose.onNodeWithTag("test.payload").assertTextEquals("模型A")
        assertTrue("删除源行也应保留退场内容", cardTop() > settled)
        assertEquals(0, disposals)
        // 尚未卸载时反向打开同一面板，过渡应自然反向而非闪空。
        compose.runOnIdle { selected.value = "模型A" }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("finecat.sheetCard").assertExists()
        compose.onNodeWithTag("finecat.sheetClosing").assertDoesNotExist()
        assertEquals(0, disposals)
        compose.runOnIdle { selected.value = null }
        assertSlidesDownAndDisposes(cardTop())
        assertEquals(1, disposals)
    }

    @Test fun parentDismissAlsoSlidesNestedSheetDownBeforeDisposal() {
        val parent = mutableStateOf(false)
        val child = mutableStateOf(false)
        var childDisposals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { FineCatTheme { Box(Modifier.fillMaxSize().testTag("test.root")) {
            FcSheetHost(visible = parent.value) {
                FcSheet(visible = true, onDismiss = { parent.value = false }, title = "父面板") {
                    Box(Modifier.fillMaxWidth().height(300.dp))
                }
                FcSheetHost(visible = child.value) {
                    DisposableEffect(Unit) { onDispose { childDisposals++ } }
                    FcSheet(visible = true, onDismiss = { child.value = false }, title = "子面板") {
                        Box(Modifier.fillMaxWidth().height(240.dp).testTag("test.childBody"))
                    }
                }
            }
        } } }
        compose.runOnIdle { parent.value = true }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { child.value = true }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithTag("finecat.sheetCard").assertCountEquals(2)
        val settled = compose.onNodeWithTag("test.childBody").fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y
        compose.runOnIdle { parent.value = false }
        compose.mainClock.advanceTimeBy(112)
        val moving = compose.onNodeWithTag("test.childBody").fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y
        assertTrue("父面板退出不能截断子面板退场", moving > settled + 1f)
        assertEquals(0, childDisposals)
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithTag("finecat.sheetCard").assertCountEquals(0)
        assertEquals(1, childDisposals)
    }
}
