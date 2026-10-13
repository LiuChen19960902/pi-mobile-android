package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F12 条件 6 的**横屏几何**验证（`DEC-F09-COMPAT`）。
 *
 * ## 两种模式，以及它们各证明什么
 *
 * 已批准模拟器（`pi-table-repro-api34`，headless 启动）**无法通过驱动路径旋转显示**。下面六种做法全部试过，
 * `dumpsys window windows` 里的 `mRotation` 始终是 `ROTATION_0`、显示尺寸始终竖屏：
 *
 * 1. `accelerometer_rotation=0` + `user_rotation=1`
 * 2. 先 `wm size reset` 再同上
 * 3. `adb emu rotate`
 * 4. `wm user-rotation lock 1`（显示 `lock 1`，但 WM 仍报 `ROTATION_0`）
 * 5. 冷启动（`-no-snapshot-load`）后重试 1
 * 6. `adb emu sensor set acceleration 9.8:0:0` + 自动旋转开
 *
 * 因此须区分真正的显示旋转与仅改变窗口几何：
 *
 * - `landscape`（**驱动显示旋转，本环境走不到**）：真旋转，`mRotation=ROTATION_90`。
 * - `wide-viewport`（本类在本环境实际走的路径）：用 `wm size 2340x1080` 把**窗口**做成与 ROTATION_90
 *   完全相同的几何（物理屏 1080×2340 转 90° 就是 2340×1080），布局引擎看到的宽高比、可用宽度、
 *   换行与滚动行为与真横屏一致。
 *
 * **wide-viewport 不证明什么**（别把它当真横屏）：`Configuration.orientation` 仍报 portrait、
 * 系统栏位置仍是竖屏那套、也拿不到旋转动画。真旋转仍需在能旋转的设备上补验。
 *
 * ## 更高一档在 [FineCatCompatF12WindowDeviceTest]
 *
 * 上面六种都是「驱动**显示**旋转」。还有一条与传感器、与显示能否旋转都无关的路：
 * **Activity 自己请求 landscape 窗口方向**（`SCREEN_ORIENTATION_LANDSCAPE`），此时
 * `Configuration.orientation` 会真的变成 `landscape`。该类在生产等效宿主上走的就是这条，
 * 比本类的宽视口高一档。
 *
 * ## 旋转判据（此前修掉的一个真 bug）
 *
 * 判横屏要比**枚举**而不是角度：`Surface.ROTATION_90` 的枚举值是 `1`、`ROTATION_270` 是 `3`，
 * 而字符串 `ROTATION_90` 里的 `90` 是**角度**。上一版把角度抽成 `90` 再跟 `1` 比，
 * 于是「读到 ROTATION_90」也会被判成没转，静默超时掉进 wide-viewport。
 *
 * ## 为什么单独一个类、为什么在 `@BeforeClass` 里设几何
 *
 * `createAndroidComposeRule()` 会在每个用例开始时就启动它的 ComponentActivity。尺寸/方向变更会
 * 重建该 Activity，`setContent` 挂上去的组合随之丢失（实测
 * `IllegalStateException: No compose hierarchies found in the app`）。`@BeforeClass` 早于 rule 生效，
 * 正好卡在「先定几何、再启动 Activity」的顺序点上。
 *
 * ## 之前那条「横向」用例的问题
 *
 * 它只设了密度与字号、**根本没转屏幕**，截图仍是 1170×2520 竖屏：验到的只是「窄竖屏下不横向
 * 溢出」，不是横屏。本类不接受那种结果。
 */
@RunWith(AndroidJUnit4::class)
class FineCatCompatF12LandscapeDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val transport = FakeTransport()
    private var holder: AndroidSharedStateHolder? = null
    private var measureFileReady = false

    @Test
    fun landscape_longKeysAndPopoverFitViewport() {
        emitAssert("horizontal.mode", mode)
        assertEquals("F12-LANDSCAPE 必须明确走宽视口路径", "wide-viewport", mode)

        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        val stateHolder = graph.stateHolder
        holder = stateHolder

        compose.setContent {
            FineCatTheme(darkTheme = stateHolder.appearanceMode == "dark") {
                Box(modifier = Modifier.fillMaxSize()) {
                    FineCatProviderSheet(stateHolder = stateHolder, onDismiss = {})
                }
            }
        }
        runBlocking {
            // 不要在握手前等 `finecat.providerEdit`：那张卡要等 providers 到达才渲染，
            // 先等它会自己把自己等死。顺序照主测试类 connect()。
            await("specs") { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(hello)
            await("connected") { stateHolder.gatewayState.connection.name == "CONNECTED" }
            transport.receive(providersFrame())
            await("providers") { stateHolder.providers.any { it.id == PROVIDER } }
        }
        compose.waitForIdle()

        assertTrue("兼容参数区必须能展开", openCompat())
        val card = boundsOf("finecat.modelDetail")
        val viewportWidth = card.width
        val viewportHeight = card.height
        emitAssert("horizontal.rootWidth", viewportWidth.toString())
        emitAssert("horizontal.cardHeight", viewportHeight.toString())
        // 横屏的核心特征是「宽 > 高」。竖屏实测是 1170×2520，这里必须反过来，否则就是没进横屏。
        assertTrue(
            "横屏几何必须宽大于高（实际 ${viewportWidth}x$viewportHeight），否则没进横屏",
            viewportWidth > viewportHeight,
        )

        // 长键名不得把行**右侧的控件**挤出视口或压成 0 宽。
        // 不量行容器：`finecat.compatKey.$key` 是 fillMaxWidth 的容器，按构造就不会越界，拿它判
        // 「没被裁」恒真。真正会被挤掉的是枚举行的取值标记与布尔行最后一段。
        var allInside = true
        var worstRight = 0f
        var squeezed = 0
        for (key in listOf("thinkingTokenBudgetField", "sendSessionAffinityHeaders", "maxTokensField")) {
            scrollInModelCard("finecat.compatKey.$key")
            val controlTag = controlTagOf(key)
            val control = compose.onAllNodesWithTag(controlTag, useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull { it.boundsInRoot.width > 0f }
                ?: error("长键名 $key 的控件 $controlTag 没布局出来（宽 0 = 被挤没）")
            val c = control.boundsInRoot
            worstRight = maxOf(worstRight, c.right)
            if (c.width <= 0f || c.left < 0f || c.right > viewportWidth) {
                allInside = false
                squeezed++
            }
        }
        emitAssert("horizontal.longestKeyInside", allInside.toString())
        emitAssert("horizontal.squeezedControls", squeezed.toString())
        emitAssert("horizontal.maxRight", worstRight.toString())
        assertEquals("长键名不得把右侧控件挤出视口或压成 0 宽", 0, squeezed)
        assertTrue("长键名不得横向越出视口（最右 $worstRight / 视口 $viewportWidth）", allInside)

        // 取值浮层是窗口级 Popup：不得横向溢出窗口，且真能点得动。
        scrollInModelCard("finecat.compatKey.thinkingFormat")
        compose.onNodeWithTag("finecat.compatKey.thinkingFormat").performClick()
        assertTrue(awaitTag("finecat.compatEnumItem.thinkingFormat.openai"))
        compose.waitForIdle()
        val popLeft = boundsOf("finecat.compatEnumItem.thinkingFormat.openai").left
        val popRight = boundsOf("finecat.compatEnumItem.thinkingFormat.openai").right
        emitAssert("horizontal.popoverInside", (popLeft >= 0f && popRight <= viewportWidth).toString())
        emitAssert("horizontal.popoverBounds", "${popLeft.toInt()}..${popRight.toInt()}")
        assertTrue(
            "取值浮层不得横向溢出窗口（实际 $popLeft..$popRight / 视口 0..$viewportWidth）",
            popLeft >= 0f && popRight <= viewportWidth,
        )
        compose.onNodeWithTag("finecat.compatEnumItem.thinkingFormat.openai").performClick()
        compose.waitForIdle()
        emitAssert("horizontal.popoverPickWorks", markerTextOf("thinkingFormat"))
        saveShot("f12-compat-horizontal.png")
    }

    // ---------------------------------------------------------------- 设备几何

    companion object {
        private var baselineSize: String = ""
        private var baselineDensity: String = ""
        private var mode: String = "unset"

        /**
         * 必须在 rule 启动 Activity **之前**把方向/尺寸定好，所以放 `@BeforeClass`。
         * 返回实际生效的模式（`landscape` / `wide-viewport`），由用例落证。
         */
        @BeforeClass
        @JvmStatic
        fun setUpLandscapeGeometry() {
            baselineSize = shell("wm size").trim()
            baselineDensity = shell("wm density").trim()
            shell("wm density 520")
            mode = applyLandscapeGeometry()
        }

        /**
         * 按**进来时的基线**还原，不能用 `wm size reset` / `wm density reset`。
         *
         * 这个 worktree 的设备基线本身带覆盖（`Override size: 1170×2520`、`Override density: 480`），
         * `reset` 会把它清成物理值 1080×2340/440 —— 后面那些只设 `wm density` 的矩阵用例就会从
         * 390dp 掉到 360dp，整片变红。所以这里把基线里的 `Override` 项原样写回去。
         */
        @AfterClass
        @JvmStatic
        fun restoreDeviceGeometry() {
            runCatching { shell("settings put system user_rotation 0") }
            runCatching { shell("settings put system accelerometer_rotation 1") }
            val size = Regex("Override size: (\\d+x\\d+)").find(baselineSize)?.groupValues?.get(1)
            val density = Regex("Override density: (\\d+)").find(baselineDensity)?.groupValues?.get(1)
            runCatching { shell(if (size != null) "wm size $size" else "wm size reset") }
            runCatching { shell(if (density != null) "wm density $density" else "wm density reset") }
            Log.i(
                "F12CAP",
                "landscape 模式=$mode 已还原：size=[${shell("wm size").trim()}] density=[${shell("wm density").trim()}]",
            )
        }

        /** 优先走真旋转；转不动就退回「等价横屏几何」。 */
        private fun applyLandscapeGeometry(): String {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                if (isLandscapeDisplay()) return "landscape"
                Thread.sleep(250)
            }
            // 真旋转在本 AVD 上不生效（见类 KDoc 与证据文件）：改用与 ROTATION_90 相同的窗口几何。
            shell("wm size 2340x1080")
            Thread.sleep(2_000)
            return "wide-viewport"
        }

        /**
         * 当前显示是否已转成横屏。
         *
         * 比较的是**枚举**不是角度：`Surface.ROTATION_90` 的枚举值是 `1`、`ROTATION_270` 是 `3`，
         * 而字符串 `ROTATION_90` 里的 `90` 是角度。上一版抽成角度再跟 `1` 比，
         * 于是「读到 ROTATION_90」也会被判成没转，静默超时掉进 wide-viewport。
         * 字段本身是字符串 `ROTATION_n`（按 `(\\d+)` 解析会永远「没解析到」）。
         */
        private fun isLandscapeDisplay(): Boolean {
            val raw = Regex("mRotation=(ROTATION_\\d+)")
                .find(shell("dumpsys window displays"))?.groupValues?.get(1)
                return when (raw) {
                    "ROTATION_90", "ROTATION_270" -> true
                    else -> false
                }
        }

        private fun shell(cmd: String): String {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

        const val PROVIDER = "f12-compat"
        const val MODEL = "m-1"
        const val MODEL_2 = "m-2"
    }

    // ---------------------------------------------------------------- 辅助

    /** 可控超时的等待（共享的 waitUntil 只有 5s，超时信息也不带应用栈）。 */
    private suspend fun await(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(50)
        }
        error("等待 $what 超时（${timeoutMs}ms）")
    }

    private fun emitAssert(name: String, actual: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val file = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            file.delete()
            measureFileReady = true
        }
        file.appendText("CAPTURE-ASSERT: $name actual=$actual\n")
        Log.i("F12ASSERT", "$name=$actual")
    }

    private fun saveShot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, File(dir, name).outputStream())
        bitmap.recycle()
    }

    private fun nodeOf(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        ?: compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?: error("节点 $tag 未找到")

    private fun boundsOf(tag: String) = nodeOf(tag).boundsInRoot

    private fun textOf(tag: String): String =
        nodeOf(tag).config[SemanticsProperties.Text].firstOrNull()?.text.orEmpty()

    private fun hasNode(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** 枚举键有取值标记；布尔键没有，取当前选中段的标签。 */
    private fun markerTextOf(key: String): String =
        if (hasNode("finecat.compatMarker.$key")) textOf("finecat.compatMarker.$key")
        else textOf("finecat.compatSet.$key.true")

    /** 行右侧那个会被长键名挤掉的控件。 */
    private fun controlTagOf(key: String): String =
        if (hasNode("finecat.compatMarker.$key")) "finecat.compatMarker.$key"
        else "finecat.compatSet.$key.false"

    private fun awaitTag(tag: String, timeoutMillis: Long = 5_000): Boolean {
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollInModelCard(tag: String) {
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.waitForIdle()
    }

    /** 打开第一个模型的二级卡并展开兼容参数区（路径照主测试类 openModel/openCompat）。 */
    private fun openCompat(): Boolean {
        runCatching {
            compose.waitUntil(timeoutMillis = 3_000) {
                compose.onAllNodesWithTag("finecat.providerRow.$PROVIDER").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()) {
            if (!hasNode("finecat.providerRow.$PROVIDER")) return false
            compose.onNodeWithTag("finecat.providerRow.$PROVIDER").performClick()
            if (!awaitTag("finecat.providerEdit")) return false
        }
        compose.onNodeWithTag("finecat.modelRow.0").performScrollTo().performClick()
        if (!awaitTag("finecat.modelDetail")) return false
        scrollInModelCard("finecat.compatHeader")
        compose.onNodeWithTag("finecat.compatHeader").performClick()
        compose.waitForIdle()
        return compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isNotEmpty()
    }

    // ---------------------------------------------------------------- 夹具数据

    /**
     * hello 帧的 capabilities **必须与主测试类逐字一致**：客户端握手要按它校验能力，
     * 写成空对象会让连接卡在 CONNECTING（实测等了 20s 超时）。
     */
    private val hello = """{"kind":"hello","protocol":3,"authenticated":true,"clients":1,
            "capabilities":["ping","sessions","subscribe","unsubscribe","message","abort","history",
            "state","models","set-model","directories","directory-create","workspace-create","providers",
            "provider-put","provider-delete","provider-test","provider-models","restart-session","name",
            "compact","commands","tree","switch-branch","attachment"]}"""

    private fun providersFrame() = """
        {"kind":"providers","providers":[{
        	"id":"$PROVIDER","name":"F12 中转","baseUrl":"https://example.invalid/v1","apiKeySet":true,
        	"status":{"ok":true,"latencyMs":9},
        	"models":[{"id":"$MODEL","name":"M1","api":"openai-completions","contextWindow":128000,
        		"maxTokens":8192,"reasoning":true,"supportsImages":false,
        		"compat":{"supportsDeveloperRole":true,"supportsStore":false,"thinkingFormat":"deepseek"}},
        		{"id":"$MODEL_2","name":"M2","api":"openai-completions","contextWindow":64000,
        		"maxTokens":4096,"reasoning":false,"supportsImages":false,
        		"compat":{"supportsStore":true}}]
        }]}
    """.trimIndent()
}