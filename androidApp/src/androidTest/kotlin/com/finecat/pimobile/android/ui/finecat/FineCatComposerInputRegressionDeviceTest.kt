package com.finecat.pimobile.android.ui.finecat

import android.view.View
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.android.tinyPng
import com.finecat.pimobile.android.ui.finecat.composer.FcComposer
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 独立组件宿主，不接真实网关、不改生产草稿；本轮只编译，执行结果另行登记。 */
@RunWith(AndroidJUnit4::class)
class FineCatComposerInputRegressionDeviceTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var inputView: View
    private lateinit var hideKeyboard: () -> Unit
    private var lastRemovedIndex: Int? = null

    private fun setComposer(
        initialImages: List<AndroidPreparedImage> = emptyList(),
        photos: List<Uri> = emptyList(),
        initialUris: List<String> = initialImages.indices.map { "content://fixture/$it" },
        initialPending: Set<String> = emptySet(),
    ) {
        rule.setContent {
            var text by remember { mutableStateOf("") }
            var focused by remember { mutableStateOf(false) }
            var shots by remember { mutableStateOf(false) }
            var images by remember { mutableStateOf(initialImages) }
            var uris by remember { mutableStateOf(initialUris) }
            var pending by remember { mutableStateOf(initialPending) }
            fun remove(index: Int) {
                lastRemovedIndex = index
                images = images.filterIndexed { i, _ -> i != index }
                uris = uris.filterIndexed { i, _ -> i != index }
            }
            val currentView = LocalView.current
            val keyboard = LocalSoftwareKeyboardController.current
            SideEffect {
                inputView = currentView
                hideKeyboard = { keyboard?.hide() }
            }
            FineCatTheme {
                FcComposer(
                    text = text, onTextChange = { text = it }, running = false,
                    expanded = focused || text.isNotEmpty(), withShots = shots,
                    modelName = "测试模型", thinkingLabel = "low",
                    photos = photos, photoThumbnails = emptyMap(), selectedPhotoUris = uris.toSet() + pending,
                    onToggleShotsTray = { shots = !shots }, onTogglePhoto = { uri ->
                        val index = uris.indexOf(uri.toString())
                        if (index >= 0) remove(index) else pending = pending - uri.toString()
                    },
                    onMorePhotos = {}, onModelClick = {}, onSend = {}, onStop = {},
                    onFocusedChange = { focused = it },
                    preparedCount = images.size, preparedImages = images,
                    preparedPhotoUris = uris, preparingPhotoUris = pending,
                    onRemovePreparedImage = ::remove,
                )
            }
        }
    }

    private fun imeVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(inputView) ?: return false
        val height = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val inputBottom = rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().boundsInWindow.bottom
        val viewLocation = IntArray(2).also { inputView.getLocationInWindow(it) }
        return insets.isVisible(WindowInsetsCompat.Type.ime()) &&
            height >= 48 * inputView.resources.displayMetrics.density &&
            inputBottom <= viewLocation[1] + inputView.height - height
    }

    @Test
    fun firstTapPreservesTextFieldAndKeyboardReopensWhileFocused() {
        setComposer()
        val originalNode = rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id
        rule.onNodeWithTag("fc.composer.input").performTouchInput { click() }
        rule.waitUntil(5_000) { imeVisible() }
        assertEquals(originalNode, rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id)
        rule.runOnIdle {
            val inputMethod = inputView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            assertTrue("平台必须绑定本次编辑视图", inputMethod.isActive(inputView) && inputMethod.isAcceptingText)
            val editor = EditorInfo()
            val connection = inputView.onCreateInputConnection(editor)
            assertTrue("真实编辑连接必须存在", connection != null && editor.inputType != 0)
            assertTrue("编辑连接必须接受文字", connection!!.commitText("abc", 1))
        }
        rule.onNodeWithTag("fc.composer.input").assertTextEquals("abc")
        rule.onNodeWithTag("fc.composer.input").assertIsFocused()
        rule.runOnIdle { hideKeyboard() }
        rule.waitUntil(5_000) { !imeVisible() }
        rule.onNodeWithTag("fc.composer.input").assertIsFocused()
        rule.onNodeWithTag("fc.composer.input").performTouchInput { click() }
        rule.waitUntil(5_000) { imeVisible() }
        assertEquals(originalNode, rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id)
    }

    @Test
    fun photoTrayKeepsInputUnfocusedAndNonRecentAttachmentCanBeRemoved() {
        setComposer(listOf(image("composer-regression")))
        val originalNode = rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id
        rule.onNodeWithTag("fc.preparedStrip").assertExists()
        rule.onNodeWithContentDescription("添加图片，已选 1 张").performTouchInput { click() }
        rule.onNodeWithTag("fc.preparedStrip").assertDoesNotExist()
        rule.onNodeWithTag("fc.attachmentBadge").assertDoesNotExist()
        rule.onNodeWithTag("fc.composer.input").assertIsNotFocused()
        assertEquals(originalNode, rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id)
        rule.onNodeWithContentDescription("移除图片 1").performTouchInput { click() }
        rule.onNodeWithContentDescription("移除图片 1").assertDoesNotExist()
        rule.onNodeWithTag("fc.composer.input").assertIsNotFocused()
    }

    @Test
    fun recentSelectionHasOneRepresentationAndClosingPreservesIt() {
        val recent = Uri.parse("content://fixture/recent")
        setComposer(listOf(image("recent")), listOf(recent), listOf(recent.toString()))
        val editorId = rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id
        rule.onNodeWithTag("fc.attachmentBadge").assertTextEquals("1")
        rule.onNodeWithContentDescription("添加图片，已选 1 张").performTouchInput { click() }
        rule.onNodeWithContentDescription("最近照片 1").assertIsSelected()
        rule.onNodeWithContentDescription("移除图片 1").assertDoesNotExist()
        rule.onNodeWithTag("fc.preparedStrip").assertDoesNotExist()
        rule.onNodeWithTag("fc.attachmentBadge").assertDoesNotExist()
        rule.onNodeWithContentDescription("添加图片").performTouchInput { click() }
        rule.onNodeWithTag("fc.shotStrip").assertDoesNotExist()
        rule.onNodeWithContentDescription("移除图片 1").assertExists()
        rule.onNodeWithTag("fc.attachmentBadge").assertTextEquals("1")
        assertEquals(editorId, rule.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id)
        rule.onNodeWithTag("fc.composer.input").assertIsNotFocused()
    }

    @Test
    fun nonRecentRemovalKeepsOriginalIndexWhenRecentImagesAreHidden() {
        val recent = Uri.parse("content://fixture/recent")
        setComposer(listOf(image("recent"), image("other")), listOf(recent), listOf(recent.toString(), "content://fixture/other"))
        rule.onNodeWithContentDescription("添加图片，已选 2 张").performTouchInput { click() }
        rule.onNodeWithContentDescription("移除图片 1").assertDoesNotExist()
        rule.onNodeWithContentDescription("移除图片 2").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, lastRemovedIndex) }
        rule.onNodeWithContentDescription("最近照片 1").assertIsSelected()
        rule.onNodeWithContentDescription("添加图片").performTouchInput { click() }
        rule.onNodeWithTag("fc.attachmentBadge").assertTextEquals("1")
    }

    @Test
    fun pendingNonRecentImageCanBeCancelledInBothTrayStates() {
        setComposer(initialPending = setOf("content://fixture/pending"))
        rule.onNodeWithContentDescription("取消正在添加的图片").assertExists()
        rule.onNodeWithContentDescription("添加图片，已选 1 张").performTouchInput { click() }
        rule.onNodeWithTag("fc.preparedStrip").assertDoesNotExist()
        rule.onNodeWithContentDescription("取消正在添加的图片").performTouchInput { click() }
        rule.onNodeWithContentDescription("取消正在添加的图片").assertDoesNotExist()
        rule.onNodeWithTag("fc.composer.input").assertIsNotFocused()
    }

    private fun image(id: String) = AndroidPreparedImage(
            outgoing = GatewayOutgoingImage(
                mimeType = "image/png",
                base64Data = android.util.Base64.encodeToString(tinyPng(), android.util.Base64.NO_WRAP),
                attachmentId = id,
            ),
            width = 1, height = 1, byteCount = tinyPng().size,
        )
}
