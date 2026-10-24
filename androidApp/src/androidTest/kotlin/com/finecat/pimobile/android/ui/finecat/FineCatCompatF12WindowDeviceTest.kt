package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.testOnMain
import com.finecat.pimobile.android.waitUntil
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F12 条件 6 在**生产窗口配置等效宿主**上的验证：真键盘几何 + 真横屏。
 *
 * ## 与 [FineCatCompatF12DeviceTest] / [FineCatCompatF12LandscapeDeviceTest] 的分工
 *
 * 前两类的宿主是 `androidx.compose.ui.test` 自带的 `androidx.activity.ComponentActivity`，
 * 它在 `ui-test-manifest` 里的声明**既没有 `windowSoftInputMode="adjustResize"`，代码里也没有
 * `enableEdgeToEdge()`**。那类宿主上：
 *
 * - 键盘展开不 resize 窗口、也不派发 IME insets ⇒ 拿不到生产那条「sheet 被键盘顶上去」的行为，
 *   于是「键盘展开时末行可操作」在**那个宿主上不可达**（不是产品缺陷）。
 * - 旋转会重建 Activity，而 `setContent` 是在测试体里挂的 ⇒ 重建后组合丢失。
 *
 * 本类改用 [FineCatCompatHostActivity]，它的 `adjustResize` + `enableEdgeToEdge()` +
 * `onCreate` 挂载逐条对齐生产（见该类 KDoc 的对照表），因此可以在**真键盘、真方向**下取证。
 *
 * ## 旋转怎么做到（这修正了此前六次手动尝试的结论）
 *
 * 此前只试了「驱动**显示**旋转」：`settings put system user_rotation`、`wm user-rotation lock`、
 * `adb emu rotate`、注入加速度传感器等六种。这个 AVD 的显示确实不转
 * （实测 `mRotation=ROTATION_0`、`cur=1170x2520` 恒定），**那部分观察成立**。
 *
 * 但漏了一条路：**Activity 自己请求的窗口方向**（`SCREEN_ORIENTATION_LANDSCAPE`）与传感器、
 * 与显示是否旋转无关——它把**这个 Activity 的窗口**按 landscape 几何布局。本类走的就是这条。
 *
 * @see FineCatCompatF12LandscapeDeviceTest 宽视口（`wm size` 改窗口几何）那一档，本类是更高的档
 */
@RunWith(AndroidJUnit4::class)
class FineCatCompatF12WindowDeviceTest {

    /**
     * 空的 compose rule：组合由 [FineCatCompatHostActivity] 自己挂。
     *
     * 不能用 `createAndroidComposeRule<FineCatCompatHostActivity>()`：那个 rule 会持有
     * Activity 实例并在用例结束时收尾，而方向切换要的是「Activity 被系统重建」——
     * 由 rule 持久的实例会让重建后的新实例与 rule 脱节。用 `ActivityScenario` 自己掌控
     * 生命周期，才能观察到重建后界面重新显示。
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private val transport = FakeTransport()
    private var graph: AndroidAppGraph? = null
    private var scenario: ActivityScenario<FineCatCompatHostActivity>? = null
    private var measureFileReady = false

    // ================================================================== 用例

    /**
     * **真键盘**下的兼容参数区：键盘持续展开时末行完整位于可用区域，且真能触控改变取值。
     *
     * 判据全部是**关系**（位置关系、状态翻转），不冻结具体像素——像素随设备与键盘实现漂移，
     * 冻结它等于给这台模拟器过拟合，换台机器就假红。
     *
     * 步骤：点开模型名输入行拉起键盘 → 展开兼容参数区 → 尽力把末行
     * `sessionAffinityFormat` 滚到键盘线以上 → **键盘保持展开**时落位置关系 →
     * 直接在键盘展开状态下点末行、开取值浮层选值 → 取值真的变。
     */
    @Test
    fun ime_lastRowFullyOperableWhileKeyboardStaysUp() {
        launchHost()
        assertTrue("兼容参数区必须能展开", openCompat())
        // 键盘必须由**文本输入**拉起：点击非输入控件不会显示 IME。
        openImeViaModelName()
        assertTrue("点开输入行后键盘应可见", isImeShown())
        emitAssert("ime.keyboardVisible", isImeShown().toString())
        val imeTop = requireNotNull(awaitImeTop()) { "解析不到键盘顶边，IME 几何无从落证" }
        // 键盘顶边必须**真的落在屏幕下半部**：IME 窗口的 touchable region 在键盘收起时也留在
        // 上一轮的位置（实测静止状态就是 `(0,1530,1170,2520)`），直接拿它当「键盘在」会被骗。
        val rootHeight = boundsOf("finecat.compatHost.root").height
        val keyboardInLowerHalf = imeTop > rootHeight / 2f && imeTop < rootHeight
        emitAssert("ime.imeTopInLowerHalf", keyboardInLowerHalf.toString())
        assertTrue("键盘顶边 $imeTop 必须落在屏幕下半部（屏高=$rootHeight）", keyboardInLowerHalf)

        val lastRowTag = "finecat.compatKey.sessionAffinityFormat"
        // 先做**最小滚动**把末行滚进卡片可视区（performScrollTo 只滚到刚好可见）。
        // 不要一上来就大幅上滑：那样会把末行甩出语义树，反而量不到真实位置。
        scrollInModelCard(lastRowTag)
        val outcome = scrollRowAboveIme(lastRowTag, imeTop)
        emitAssert("ime.lastRowAboveIme", outcome.above.toString())
        emitAssert("ime.lastRowLost", outcome.lost.toString())

        val lastRow = boundsOf(lastRowTag)
        // 「完整位于可用区域」= 末行**整行**都在键盘线以上（上边不越出屏幕顶、底边不压到键盘）。
        //
        // 注意别拿「卡片底边在键盘线以上」当判据：这张卡是**全屏 sheet**（卡片底=屏幕底），
        // 它本来就铺满屏幕，把卡片抬起是做不到的、也不该做；真正要验的是
        // 「滚动之后末行自己有没有跑到键盘上面去」。
        val fullyAboveIme = lastRow.top >= 0f && lastRow.bottom <= imeTop
        emitAssert("ime.lastRowFullyAboveIme", fullyAboveIme.toString())
        emitAssert("ime.imeStillShownBeforeTouch", isImeShown().toString())

        assertTrue(
            "键盘展开时末行必须完整位于键盘线以上（末行=$lastRow，键盘顶边=$imeTop）",
            fullyAboveIme,
        )
        assertTrue("取证过程不许把键盘收掉", isImeShown())
        saveShot("f12-compat-ime-up.png")

        // 真触控：键盘仍展开时点末行 → 开取值浮层 → 选值 → 取值翻转。
        assertEquals("触控前末行取值应为未设置", "未设置", markerTextOf("sessionAffinityFormat"))
        emitAssert("ime.lastRowUnsetMarker", markerTextOf("sessionAffinityFormat"))
        compose.onNodeWithTag(lastRowTag).performClick()
        assertTrue("键盘展开时点末行必须能打开取值浮层", awaitTag("finecat.compatEnumItem.sessionAffinityFormat.openai"))
        compose.waitForIdle()
        val popoverOpenedWhileImeUp = isImeShown()
        emitAssert("ime.popoverOpenedWhileImeUp", popoverOpenedWhileImeUp.toString())
        assertTrue("取值浮层打开时键盘仍应展开", popoverOpenedWhileImeUp)
        saveShot("f12-compat-ime-popover.png")

        compose.onNodeWithTag("finecat.compatEnumItem.sessionAffinityFormat.openai").performClick()
        compose.waitForIdle()
        emitAssert("ime.lastRowValueAfterTouch", markerTextOf("sessionAffinityFormat"))
        emitAssert("ime.imeStillShownAfterTouch", isImeShown().toString())
        assertEquals(
            "键盘展开时末行必须真能触控改变取值",
            "openai",
            markerTextOf("sessionAffinityFormat"),
        )
        saveShot("f12-compat-ime-after-touch.png")
    }

    /**
     * **真横屏**（Activity 请求 landscape 窗口方向）下的兼容参数区。
     *
     * 与 [FineCatCompatF12LandscapeDeviceTest] 的差别：那一档是 `wm size 2340x1080` 改**窗口几何**，
     * `Configuration.orientation` 仍是 portrait、系统栏仍按竖屏摆。本档由 Activity 自己请求
     * landscape，`Configuration.orientation` 会真的变成 `landscape`，且旋转导致 Activity 重建后
     * 界面重新显示（这正是把内容挂进 `onCreate` 的目的）。
     */
    @Test
    fun landscape_requestedOrientation_longKeysAndPopoverFitViewport() {
        launchHost()
        openCompat()
        emitAssert("horizontal.mode", "requested-orientation")

        // 请求 landscape 窗口方向：与传感器/显示是否旋转无关。
        activity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        // 重建后界面必须重新挂上——这一步本身就是「onCreate 里 setContent」是否真的生效的验证。
        awaitOrientation(landscape = true)
        // 等宿主根标签重新出现，而不是等 compatHeader：兼容区默认是折叠的，
        // 重建后要重新走一遍「进编辑卡 → 开模型卡 → 展开兼容区」。
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(FineCatCompatHostActivity.ROOT_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        val recreated = compose.onAllNodesWithTag(FineCatCompatHostActivity.ROOT_TAG)
            .fetchSemanticsNodes().isNotEmpty()
        emitAssert("horizontal.resurvivedRecreate", recreated.toString())
        assertTrue("横屏重建后宿主根节点必须重新出现", recreated)
        assertTrue("横屏重建后兼容参数区必须能重新展开", openCompat())
        emitAssert("horizontal.orientation", currentOrientation())
        emitAssert("horizontal.rotation", currentRotationEnum())

        val card = boundsOf("finecat.modelDetail")
        val viewportWidth = card.width
        val viewportHeight = card.height
        emitAssert("horizontal.rootWidth", viewportWidth.toString())
        emitAssert("horizontal.cardHeight", viewportHeight.toString())
        assertTrue(
            "横屏几何必须宽大于高（实际 ${viewportWidth}x$viewportHeight），否则没进横屏",
            viewportWidth > viewportHeight,
        )

        // 长键名不得把行**右侧的控件**挤出视口或压成 0 宽。
        // 不量行容器：`finecat.compatKey.$key` 是 fillMaxWidth 的容器，按构造就不会越界，
        // 拿它判「没被裁」恒真。真正会被挤掉的是枚举行的取值标记与布尔行最后一段。
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
        assertEquals("横屏下取值浮层必须真能选值", "openai", markerTextOf("thinkingFormat"))
        saveShot("f12-compat-landscape.png")

        // 收尾：还原窗口方向，别把后续用例拖进 landscape。
        activity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // ---------------------------------------------------------------- 宿主生命周期

    private fun launchHost() {
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        FineCatCompatHostActivity.install(graph!!)
        scenario = ActivityScenario.launch(FineCatCompatHostActivity::class.java)
        runBlocking {
            // 不要在握手前等 finecat.providerEdit：那张卡要等 providers 到达才渲染，
            // 先等它会自己把自己等死。顺序照主测试类 connect()。
            await("specs") { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(hello)
            await("connected") { graph!!.stateHolder.gatewayState.connection.name == "CONNECTED" }
            transport.receive(providersFrame())
            await("providers") { graph!!.stateHolder.providers.any { it.id == PROVIDER } }
        }
        compose.waitForIdle()
    }

    private fun activity(block: (FineCatCompatHostActivity) -> Unit) {
        scenario!!.onActivity { block(it) }
    }

    private fun awaitOrientation(landscape: Boolean, timeoutMs: Long = 20_000) {
        val want = if (landscape) "landscape" else "portrait"
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = currentOrientation()
        while (System.currentTimeMillis() < deadline) {
            if (seen == want) return
            Thread.sleep(250)
            seen = currentOrientation()
        }
        error("等待方向=$want 超时（${timeoutMs}ms），实测=$seen")
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun await(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(50)
        }
        error("等待 $what 超时（${timeoutMs}ms）")
    }

    private fun openCompat(): Boolean {
        runCatching {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("finecat.providerRow.$PROVIDER").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (compose.onAllNodesWithTag("finecat.providerRow.$PROVIDER").fetchSemanticsNodes().isEmpty()) {
            dumpTree("providerRow-missing")
            return false
        }
        // 列表页只渲染 provider 行；模型行要先进编辑卡（路径照主测试类 openModel）。
        if (compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("finecat.providerRow.$PROVIDER").performClick()
            if (!awaitTag("finecat.providerEdit")) {
                dumpTree("providerEdit-missing")
                return false
            }
        }
        if (compose.onAllNodesWithTag("finecat.modelDetail").fetchSemanticsNodes().isEmpty()) {
            runCatching { compose.onNodeWithTag("finecat.modelRow.0").performScrollTo().performClick() }
            if (!awaitTag("finecat.modelDetail")) {
                dumpTree("modelDetail-missing")
                return false
            }
        }
        if (compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isEmpty()) {
            scrollInModelCard("finecat.compatHeader")
            compose.onNodeWithTag("finecat.compatHeader").performClick()
            compose.waitForIdle()
        }
        return compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isNotEmpty()
    }

    /** 键盘只能由文本输入拉起：点开模型名输入行。 */
    private fun openImeViaModelName() {
        scrollInModelCard("finecat.modelName")
        compose.onNodeWithTag("finecat.modelName").performClick()
        waitForIme()
    }

    private fun scrollInModelCard(tag: String) {
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.waitForIdle()
    }

    /** 排障：把当前语义树打到 logcat，定位「节点为什么找不到」。 */
    private fun dumpTree(why: String) {
        val text = runCatching { compose.onRoot().printToString(maxDepth = 12) }
            .getOrElse { "printToString failed: ${it.message}" }
        android.util.Log.i("F12TREE", "=== $why ===\n$text")
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

    private fun markerTextOf(key: String): String =
        if (hasNode("finecat.compatMarker.$key")) textOf("finecat.compatMarker.$key")
        else textOf("finecat.compatSet.$key.true")

    /** 行右侧那个会被长键名挤掉的控件。 */
    private fun controlTagOf(key: String): String =
        if (hasNode("finecat.compatMarker.$key")) "finecat.compatMarker.$key"
        else "finecat.compatSet.$key.false"

    private fun awaitTag(tag: String, timeoutMillis: Long = 10_000): Boolean {
        runCatching {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        }
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    // ---------------------------------------------------------------- IME

    /**
     * 把目标行往上推到键盘线以上。
     *
     * 策略是「**先最小、后微调、每步复查**」：先用 `performScrollTo` 把整行滚进卡片可视区，
     * 还不达标才一小步一小步往上推，且每步都重新确认节点还在。
     *
     * 不能一上来就大幅 `swipeUp()`：实测那会把末行直接甩出语义树，之后连位置都量不到
     * （`lastRowLost=true`），只剩一个空结论。
     *
     * 返回值只报告**观测到的事实**：是否真的到过键盘线以上（[ScrollOutcome.above]）、
     * 中途是否把节点滚出了语义树（[ScrollOutcome.lost]）、最后一次看到的底边。
     */
    private fun scrollRowAboveIme(tag: String, imeTop: Int, maxSteps: Int = 6): ScrollOutcome {
        fun rowBottom(): Float? =
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
                ?.boundsInRoot?.bottom

        // 第一步：最小滚动，让整行进卡片可视区。
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.waitForIdle()
        var bottom = rowBottom() ?: return ScrollOutcome(null, above = false, lost = true)
        if (bottom <= imeTop) return ScrollOutcome(bottom, above = true, lost = false)

        // 之后只在「还差一截」时小步上推，每步复查，节点一丢就停手（别硬推）。
        repeat(maxSteps) {
            runCatching {
                compose.onNodeWithTag("finecat.modelDetail").performTouchInput { swipeUp(startY = centerY * 0.6f, endY = centerY * 0.5f) }
            }
            compose.waitForIdle()
            val after = rowBottom()
            if (after == null) return ScrollOutcome(bottom, above = false, lost = true)
            bottom = after
            if (bottom <= imeTop) return ScrollOutcome(bottom, above = true, lost = false)
        }
        return ScrollOutcome(bottom, above = false, lost = false)
    }

    private data class ScrollOutcome(
        val lastBottom: Float?,
        val above: Boolean,
        val lost: Boolean,
    )

    private fun waitForIme(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isImeShown()) return
            Thread.sleep(200)
        }
    }

    private fun isImeShown(): Boolean =
        shell("dumpsys input_method | grep -E 'mInputShown|mVisibleWindow'").contains("mInputShown=true")

    /** 键盘顶边（屏幕坐标）：取 IME 窗口 touchable region 的上边界。 */
    private fun imeTopPx(): Int? {
        val dump = shell("dumpsys window windows")
        val block = Regex("(?ms)^  Window #\\d+ Window\\{[0-9a-f]+ u0 InputMethod\\}.*?(?=^  Window #|\\z)")
            .find(dump)?.value ?: return null
        return Regex("touchable region=SkRegion\\(\\((\\d+),(\\d+),(\\d+),(\\d+)\\)\\)")
            .find(block)?.groupValues?.get(2)?.toInt()
    }

    private fun awaitImeTop(timeoutMs: Long = 10_000): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Int? = null
        while (System.currentTimeMillis() < deadline) {
            last = imeTopPx()
            if (last != null) return last
            Thread.sleep(200)
        }
        return last
    }

    /**
 * ~~键盘展开时窗口是否被 resize~~ —— **这个判据是伪的，已删除**。
 *
 * 原本量 `dumpsys window displays` 的 `app=` 高度是否小于 `cur=`，并把它当作 `adjustResize`
 * 生效的证据。实测**键盘上屏与收起时 `app=` 恒为 `1170x2302`**（收起时也是这个值），
 * 所以该判据恒为 true、与键盘是否展开无关，拿它举证等于自证。
 *
 * 已换成两个真判据：[isImeShown]（IME 确实请求显示）+ 键盘顶边落在屏幕下半部
 * （`ime.imeTopInLowerHalf`）。IME 窗口的 touchable region 在键盘收起时也留在上一轮的位置
 * （静止状态实测就是 `SkRegion((0,1530,1170,2520))`），所以**不能**直接拿它当「键盘在」，
 * 必须与 IME 可见性联用。
 */
private fun isWindowResizedRemoved(): Nothing = throw UnsupportedOperationException(
    "app= 高度与键盘是否展开无关（实测恒为 2302），此伪判据已废弃"
)

    // ---------------------------------------------------------------- 方向

    /** 宿主 Activity 的 `Configuration.orientation`（真横屏会变成 `landscape`）。 */
    private fun currentOrientation(): String {
        var value = "unknown"
        activity {
            value = if (it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                "landscape"
            } else {
                "portrait"
            }
        }
        return value
    }

    /**
     * 当前默认显示的旋转枚举名（`ROTATION_0` / `ROTATION_90` / …）。
     *
     * 从 `dumpsys window displays` 的默认显示摘要读取，避免从任意应用窗口块中取到别的
     * display rotation。这里返回枚举名而不是裸整数：`ROTATION_90` 里的 `90` 是角度，
     * 而 `Surface.ROTATION_90` 的枚举值是 `1`。
     */
    private fun currentRotationEnum(): String =
        Regex("mRotation=(ROTATION_\\d+)").find(shell("dumpsys window displays"))
            ?.groupValues?.get(1) ?: "unknown"

    // ---------------------------------------------------------------- 取证

    private fun emitAssert(name: String, actual: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val file = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            file.delete()
            measureFileReady = true
        }
        file.appendText("CAPTURE-ASSERT: $name actual=$actual\n")
        android.util.Log.i("F12ASSERT", "$name=$actual")
    }

    /**
 * 取证截图。
 *
 * 刻意**不用** `uiAutomation.takeScreenshot()`：它只截被测应用的窗口，**不含 IME 窗口**——
 * 于是键盘明明在屏上，取证图里却只有导航栏，看起来像「键盘没出现」（实测对比：
 * shell `screencap` 能拍到 QWERTY 键盘与被顶上去的内容区，`takeScreenshot` 拍不到）。
 * 用例的判据本来就是「键盘还在时」，截图必须看得见键盘才算数。
 */
private fun saveShot(name: String) {
        // 必须落到设备的外部 files 目录：verify-stage.sh 的 capture 就是从这里 adb pull 的。
        val out = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            name,
        )
        out.parentFile?.mkdirs()
        val remote = out.absolutePath
        val ok = runCatching {
            shell("screencap -p $remote")
            Thread.sleep(500)
            // screencap 产出 PNG 二进制，经 shell 文本通道会被破坏，改用 base64 回传。
            val b64 = shell("base64 $remote")
            out.writeBytes(java.util.Base64.getDecoder().decode(b64.replace("\\s".toRegex(), "")))
            true
        }.getOrElse {
            android.util.Log.w("F12SHOT", "screencap 失败（$it），退化为 takeScreenshot（不含 IME 窗口）：$name")
            false
        }
        if (!ok) {
            runCatching {
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out.outputStream())
                bitmap.recycle()
            }
        }
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        scenario = null
        FineCatCompatHostActivity.reset()
        runCatching { graph?.applicationScope?.cancel() }
        runCatching { graph?.gatewayScope?.cancel() }
        graph = null
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

    companion object {
        const val PROVIDER = "f12-compat"
        const val MODEL = "m-1"
        const val MODEL_2 = "m-2"
    }
}