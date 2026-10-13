package com.finecat.pimobile.android.ui.finecat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.PixelCopy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** API 34 验收宿主：直接复制各真实窗口 Surface，按屏幕坐标拼合，不走无头显示截图。 */
internal fun ComposeTestRule.saveEvidenceScreenshot(name: String) {
    check(Build.VERSION.SDK_INT >= 34) { "F14 窗口取证需要授权的 API 34 模拟器" }
    waitForIdle()
    val nodes = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        .sortedByDescending { it.boundsInRoot.width * it.boundsInRoot.height }
    check(nodes.isNotEmpty()) { "没有已放置的 Compose 窗口" }
    var result: Bitmap? = null
    var origin: IntArray? = null
    val viewport = onAllNodesWithTag("finecat.fixtureViewport", useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()
    for (node in nodes) {
        val view = (node.root as ViewRootForTest).view
        val bounds = if (viewport != null && viewport.root === node.root) viewport.boundsInRoot else node.boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
        val screen = IntArray(2)
        val inWindow = IntArray(2)
        val rootInWindow = IntArray(2)
        val latch = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        runOnIdle {
            view.getLocationOnScreen(screen)
            view.getLocationInWindow(inWindow)
            view.rootView.getLocationInWindow(rootInWindow)
            check(rootInWindow.contentEquals(intArrayOf(0, 0))) {
                "窗口整体平移导致部分视口不在 Surface 内，不能归档完整参考截图：${rootInWindow.joinToString(",")}"
            }
            // PixelCopy 的来源是未平移的窗口 Surface；根的整体窗口平移不属于来源坐标。
            val x = inWindow[0] - rootInWindow[0]
            val y = inWindow[1] - rootInWindow[1]
            val rect = Rect(
                bounds.left.roundToInt() + x, bounds.top.roundToInt() + y,
                bounds.right.roundToInt() + x, bounds.bottom.roundToInt() + y,
            )
            check(rect.left >= 0 && rect.top >= 0 && rect.right <= view.rootView.width && rect.bottom <= view.rootView.height) {
                "来源矩形越出窗口，会被 PixelCopy 裁切缩放：$rect root=${view.rootView.width}x${view.rootView.height}"
            }
            val request = PixelCopy.Request.Builder.ofWindow(view).setSourceRect(rect)
                .setDestinationBitmap(bitmap).build()
            PixelCopy.request(request, { it.run() }) {
                status = it.status
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) {
            "窗口 PixelCopy 失败: status=$status, bounds=$bounds"
        }
        val dir = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        File(dir, "capture-measure.txt").appendText(
            "CAPTURE-WINDOW: screenshot=$name rootBounds=$bounds screen=${screen.joinToString(",")} inWindow=${inWindow.joinToString(",")} pixels=${bitmap.width}x${bitmap.height}\n",
        )
        screen[0] += bounds.left.roundToInt()
        screen[1] += bounds.top.roundToInt()
        if (result == null) {
            result = bitmap
            origin = screen
        } else {
            Canvas(result).drawBitmap(bitmap, (screen[0] - origin!![0]).toFloat(), (screen[1] - origin[1]).toFloat(), null)
            bitmap.recycle()
        }
    }
    val bitmap = checkNotNull(result)
    val colors = mutableSetOf<Int>()
    for (y in bitmap.height / 10 until bitmap.height * 9 / 10 step 11) {
        for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step 11) colors += bitmap.getPixel(x, y)
    }
    check(colors.size > 8) { "取证图内容为空或纯色: $name" }
    val dir = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
    dir.mkdirs()
    File(dir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    bitmap.recycle()
}
