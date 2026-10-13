package com.finecat.pimobile.android.ui.finecat

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.ui.SessionRenameDialog
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcPopover
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只用合成文本，真实窗口触控/Back；编译不表示已在设备执行。 */
@RunWith(AndroidJUnit4::class)
class FineCatDismissConfirmDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()

    private fun back() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
        ui.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        compose.waitForIdle()
    }

    private fun outsideDialog() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val y = compose.activity.window.decorView.height / 2f
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 2f, y, 0)
            try { ui.injectInputEvent(event, true) } finally { event.recycle() }
        }
        compose.waitForIdle()
    }

    @Test fun sheetOutsideConfirms_cardSpaceDoesNot_backCancels() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            FineCatTheme {
                FcSheet(visible = true, title = "合成编辑", onDismiss = { cancelled++ },
                    onOutsideClick = { confirmed++ }) { Text("内容") }
            }
        }
        compose.onNodeWithTag("finecat.sheetCard").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(0, cancelled) }
        compose.onNodeWithTag("finecat.sheetScrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(0, cancelled) }
        back()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(1, cancelled) }
    }

    @Test fun renameOutsideConfirmsEditedText() {
        var saved: String? = null
        var cancelled = 0
        compose.setContent {
            FineCatTheme {
                SessionRenameDialog(initial = "旧名", confirmOnOutsideClick = true,
                    onDismiss = { cancelled++ }, onConfirm = { saved = it })
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("新名")
        outsideDialog()
        compose.runOnIdle { assertEquals("新名", saved); assertEquals(0, cancelled) }
    }

    @Test fun renameBlankOutsideStays_andBackDoesNotSave() {
        var saved: String? = null
        var cancelled = 0
        compose.setContent {
            FineCatTheme {
                SessionRenameDialog(initial = "旧名", confirmOnOutsideClick = true,
                    onDismiss = { cancelled++ }, onConfirm = { saved = it })
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        outsideDialog()
        compose.onNodeWithText("请输入会话名").assertExists()
        compose.runOnIdle { assertEquals(null, saved); assertEquals(0, cancelled) }
        back()
        if (cancelled == 0) back() // 首次Back可能只收IME。
        compose.runOnIdle { assertEquals(null, saved); assertEquals(1, cancelled) }
    }

    @Test fun renameValidDraftBackCancelsWithoutSaving() {
        val open = mutableStateOf(true)
        var saved: String? = null
        var cancelled = 0
        compose.setContent {
            FineCatTheme {
                if (open.value) SessionRenameDialog(initial = "旧名", confirmOnOutsideClick = true,
                    onDismiss = { cancelled++; open.value = false },
                    onConfirm = { saved = it; open.value = false })
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("有效新名")
        back()
        compose.runOnIdle { assertEquals(null, saved) }
        if (cancelled == 0) back() // 首次可仅收IME；合法草稿能识别错误的保存路由。
        compose.runOnIdle { assertEquals(null, saved); assertEquals(1, cancelled) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun popoverBackCancelsValidDraftWithoutApplying() {
        val open = mutableStateOf(true)
        var saved: String? = null
        var cancelled = 0
        compose.setContent {
            FineCatTheme {
                FcPopover(expanded = open.value, anchorBoundsInWindow = IntRect(120, 80, 240, 128),
                    onDismissRequest = { cancelled++; open.value = false },
                    onOutsideClick = { saved = "有效新取值"; open.value = false },
                    modifier = Modifier.testTag("finecat.syntheticPopover")) { Text("有效新取值") }
            }
        }
        compose.onNodeWithTag("finecat.syntheticPopover").assertExists()
        back()
        compose.runOnIdle { assertEquals(null, saved); assertEquals(1, cancelled) }
        compose.onNodeWithTag("finecat.syntheticPopover").assertDoesNotExist()
    }

    @Test fun popoverOutsideAppliesValidDraft() {
        val open = mutableStateOf(true)
        var saved: String? = null
        var cancelled = 0
        compose.setContent {
            FineCatTheme {
                FcPopover(expanded = open.value, anchorBoundsInWindow = IntRect(120, 80, 240, 128),
                    onDismissRequest = { cancelled++; open.value = false },
                    onOutsideClick = { saved = "有效新取值"; open.value = false },
                    modifier = Modifier.testTag("finecat.syntheticPopover")) { Text("有效新取值") }
            }
        }
        compose.onNodeWithTag("finecat.syntheticPopover").assertExists()
        outsideDialog()
        compose.runOnIdle { assertEquals("有效新取值", saved); assertEquals(0, cancelled) }
        compose.onNodeWithTag("finecat.syntheticPopover").assertDoesNotExist()
    }
}
