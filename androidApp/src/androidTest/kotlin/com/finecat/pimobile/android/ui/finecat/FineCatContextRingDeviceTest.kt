package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.ui.finecat.composer.FcComposer
import com.finecat.pimobile.android.ui.finecat.composer.FcContextPopover
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.shared.protocol.GatewayContextUsage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 独立组件夹具，不连接网关、不发送compact；设备执行状态另行登记。 */
@RunWith(AndroidJUnit4::class)
class FineCatContextRingDeviceTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var changeUsage: (GatewayContextUsage?) -> Unit
    private lateinit var changeExpanded: (Boolean) -> Unit

    private fun setComposer(expanded: Boolean, dark: Boolean = false, longName: Boolean = false) {
        rule.setContent {
            var text by remember { mutableStateOf("") }
            var open by remember { mutableStateOf(expanded) }
            var usage by remember { mutableStateOf<GatewayContextUsage?>(GatewayContextUsage(74.0, 200_000, 148_000)) }
            var anchor by remember { mutableStateOf<IntRect?>(null) }
            changeUsage = { usage = it }
            changeExpanded = { open = it; if (!it) anchor = null }
            FineCatTheme(darkTheme = dark) {
                Box(Modifier.width(320.dp)) {
                    FcComposer(
                        text = text, onTextChange = { text = it }, running = false,
                        expanded = open, withShots = false,
                        modelName = if (longName) "GLM-5.3-Flash-Instruct-超长自定义模型名演示" else "模型",
                        thinkingLabel = "high", photos = emptyList(), photoThumbnails = emptyMap(), selectedPhotoUris = emptySet(),
                        onToggleShotsTray = {}, onTogglePhoto = {}, onMorePhotos = {}, onModelClick = {}, onSend = {}, onStop = {},
                        contextUsage = usage, onContextClick = { anchor = it },
                        onContextBoundsChange = { if (anchor != null) anchor = it },
                    )
                }
                if (open) anchor?.let {
                    FcContextPopover(
                        anchor = it, usage = usage, isCompacting = false, onDismiss = { anchor = null },
                        compactDisabledReason = "压缩暂不可用",
                    )
                }
            }
        }
    }

    @Test
    fun collapsedCapsuleHasNoRingOrPopover() {
        setComposer(expanded = false)
        rule.onAllNodesWithTag("fc.contextRing").assertCountEquals(0)
        rule.onAllNodesWithTag("fc.contextPopover").assertCountEquals(0)
    }

    @Test
    fun popoverUsesCurrentSnapshotKeepsEditorFocusAndNeverEnablesCompact() {
        setComposer(expanded = true)
        rule.onNodeWithTag("fc.composer.input").performTextInput("临时草稿")
        rule.onNodeWithTag("fc.contextRing").performClick()
        rule.onNodeWithText("74%").assertIsDisplayed()
        rule.onNodeWithText("148K / 200K").assertIsDisplayed()
        // CTX02 接线后按钮可用性由 onCompact 回调驱动；本夹具不提供回调 → 禁用。
        // 禁用态显示原因而非中止说明（R6）；可用时才显示「会中止当前回答」说明（M1）。
        rule.onNodeWithText("压缩暂不可用").assertIsDisplayed()
        rule.onNodeWithTag("fc.contextCompact").assertIsNotEnabled()
        rule.onNodeWithTag("fc.composer.input").assertIsFocused()
        rule.runOnIdle { changeUsage(GatewayContextUsage(null, 200_000, null)) }
        rule.onNodeWithText("— / 200K").assertIsDisplayed()
        rule.onNodeWithContentDescription("上下文占用暂无统计").assertIsDisplayed()
        rule.runOnIdle { changeExpanded(false) }
        rule.onAllNodesWithTag("fc.contextPopover").assertCountEquals(0)
        rule.onAllNodesWithTag("fc.contextRing").assertCountEquals(0)
    }

    @Test
    fun darkNarrowLongNameStillLeavesSeparateRingAndSendTargets() {
        rule.mainClock.autoAdvance = false
        setComposer(expanded = true, dark = true, longName = true)
        val chip = rule.onNodeWithTag("finecat.modelChip").fetchSemanticsNode().boundsInWindow
        val ring = rule.onNodeWithTag("fc.contextRing").fetchSemanticsNode().boundsInWindow
        val send = rule.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInWindow
        assertTrue("模型不能覆盖圆环", chip.right <= ring.left + 1f)
        assertTrue("圆环不能覆盖发送", ring.right <= send.left + 1f)
        rule.onNodeWithTag("fc.contextRing").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送").assertIsDisplayed()
    }
}
