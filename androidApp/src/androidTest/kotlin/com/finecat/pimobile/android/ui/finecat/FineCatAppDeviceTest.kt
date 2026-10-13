package com.finecat.pimobile.android.ui.finecat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.MainActivity
import com.finecat.pimobile.android.AndroidGatewayViewModel
import com.finecat.pimobile.android.AndroidSharedStateHolder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4a：FineCat 新 UI 接真实只读对话。fixture 经真实 facade/projection 链路
 * （loadManualTestFixture）驱动，断言会话标题、用户气泡、AI 正文（截图目验
 * Markwon 段落——其文本在 AndroidView 内不进 Compose 语义树）。
 */
@RunWith(AndroidJUnit4::class)
class FineCatAppDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "finecat-p4a-evidence").apply { mkdirs() }
    }

    @Test
    fun p4a_realFixtureConversation_renders() {
        lateinit var holder: AndroidSharedStateHolder
        rule.activityRule.scenario.onActivity { activity ->
            holder = androidx.lifecycle.ViewModelProvider(activity)[AndroidGatewayViewModel::class.java].stateHolder
            holder.debugUseFineCatUi = true
        }
        runBlocking { holder.loadFixtureAndAwaitForTest() }
        rule.waitForIdle()

        // 真实数据上屏：fixture 的标题兜底成用户原文，头部与气泡各出现一次
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("请验证 Android 是否使用共享 Reducer")
                .fetchSemanticsNodes().size >= 2
        }
        // 真实会话 cwd（facade 投影）。副行按原型只放项目基名——原型 `data-repo` 存的
        // 就是 `pi-mobile-android` 这类短名，全路径会撑爆 210dp 并挤掉标题。
        rule.onNodeWithText("pi-demo").assertIsDisplayed()

        // 整屏截图：AI 正文经 FcMarkdownText(Markwon) 渲染"共享协议解码、Reducer 与投影已接入。"
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidenceDir(), "31-finecat-real-conversation.png").outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        // 正文区非全背景色：从上向下扫描横带（对话内容在屏幕上部）
        val bg = 0xFFFAFAFA.toInt()
        var foundText = false
        var y = (bitmap.height * 0.08f).toInt()
        while (y < bitmap.height * 0.6 && !foundText) {
            var x = (bitmap.width * 0.1f).toInt()
            while (x < bitmap.width * 0.9f) {
                if (bitmap.getPixel(x, y) != bg && bitmap.getPixel(x, y + 14) != bg) {
                    foundText = true
                    break
                }
                x += 10
            }
            y += 14
        }
        org.junit.Assert.assertTrue("conversation area must not be blank", foundText)
    }
}
