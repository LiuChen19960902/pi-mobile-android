package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.waitUntil
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6/P8/P9 有界 UI 修复的设备取证（隔离模拟器跑，不在 JVM 单测覆盖）：
 * 1. Tree 长节点列表纵向可滚：先记滚动前基线，保留 performScrollToNode 作诊断对照
 *    （ui-test 按"匹配节点的最近 hasScrollAction 祖先"寻址，在本树纵滚列内嵌横滚 Box
 *    的结构下纵轴无效且静默返回），再对 finecat.treeContent 做真实有界上滑逐页推进，
 *    断言纵向滚动位置真实变化且末节点实际可见；截图与纵横 scroll range/节点 bounds
 *    写入 external files（主 agent 拉回 tree-diagnosis 证据目录）；
 * 2. 含点目录名（`my.project`）点击进入下级浏览、不误选父目录，
 *    且下发的 directories 请求 path 是子目录；
 * 3. GatewaySheet 配对失败保留输入（行内错误显示，输入框内容不丢）。
 *
 * 宿主形态：AndroidAppGraph 全套 Fake override（与
 * FineCatProviderRegressionDeviceTest / AndroidAppGraphFakeIntegrationDeviceTest
 * 同口径）+ `createComposeRule` + 生产组合路径 `FineCatTheme { FineCat*Sheet/TreeView }`。
 * 树/目录状态一律由真实 `transport.receive` 应答帧注入，不写 private setter、
 * 不反射、不动生产；无真实网关、无真实 prefs/密钥；tree 用例另留截图与滚动
 * 语义取证文件（见用例 1）。
 *
 * 扫码入口（onOpenScanner→GatewayQrScannerScreen 接线）在生产根，
 * 由主 agent 协调后另行取证，本文件不覆盖。
 */
@RunWith(AndroidJUnit4::class)
class FineCatBoundedUiDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var transport: FakeTransport
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var graph: AndroidAppGraph

    @After
    fun tearDown() {
        if (this::graph.isInitialized) {
            graph.stateHolder.close()
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    /** 合成 graph + 连接（Open + hello），每个用例独立一套。 */
    private fun connectFakeGateway() {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        holder = graph.stateHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() } // runtime 自动发起连接
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
    }

    /** 等 tag 出现（面板进场动画/异步发布都不稳定，按 tag 轮询）。 */
    private fun waitUntilTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 在包含该 tag 的滚动列里把它滚进可视区（已可见时为 no-op）。 */
    private fun scrollTagIntoView(tag: String) {
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
            .performScrollToNode(hasTestTag(tag))
    }

    // ---- 用例 1：40 节点树纵滑末节点可达（helper 诊断对照 + 真实触摸验收） ----

    @Test
    fun tree_longList_verticalScrollReachesBottom() {
        connectFakeGateway()
        compose.setContent {
            FineCatTheme {
                FineCatTreeView(stateHolder = holder, onBack = {})
            }
        }
        // 选中会话：FineCatTreeView 的 LaunchedEffect 据此发真实 tree 请求
        compose.runOnIdle { holder.selectSession("s1") }
        // 用 Compose 等待推进重组与 LaunchedEffect；只在 runBlocking 中等帧
        // 不会驱动测试时钟，可能尚未执行发起 tree 请求的 effect。
        compose.waitUntil(5_000) {
            holder.snapshot.selectedSessionId == "s1" &&
                transport.payloadsOfType("tree").isNotEmpty()
        }
        // 用真实树应答帧注入 40 节点（不写 private setter）
        val nodes = (0 until 40).joinToString(",") { i ->
            val role = if (i % 2 == 0) "user" else "assistant"
            """{"id":"n$i","prefix":"├─ ","type":"message","role":"$role","preview":"节点预览 $i"}"""
        }
        transport.receive("""{"kind":"tree","sessionId":"s1","leafId":"n39","nodes":[$nodes]}""")
        runBlocking { waitUntil { holder.sessionTree != null } }
        compose.waitForIdle()

        // 顶部节点可见（滚动前基线，强断言保留）
        compose.onNodeWithText("节点预览 0").assertIsDisplayed()

        val evidence = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "finecat-tree-repair",
        ).apply { mkdirs() }
        val viewport = compose.onNodeWithTag("finecat.treeContent").fetchSemanticsNode().boundsInRoot
        val viewportHeight = viewport.height

        // 滚动语义/几何证据：treeContent 纵轴 value/maxValue + 节点 0/39 bounds。
        // value/maxValue 是语义闭包的即时值；bounds 对未滚入视口的已放置节点同样有效
        // （可见性由 assertIsDisplayed 强断言，这里只取几何与滚动语义，不把存在当可见）。
        fun verticalAxis(node: SemanticsNode): ScrollAxisRange =
            node.config[SemanticsProperties.VerticalScrollAxisRange]

        fun evidenceLine(stage: String, horizontalValue: String): String {
            val vertical = verticalAxis(
                compose.onNodeWithTag("finecat.treeContent").fetchSemanticsNode(),
            )
            val b0 = compose.onNodeWithText("节点预览 0").fetchSemanticsNode().boundsInRoot
            val b39 = compose.onNodeWithText("节点预览 39").fetchSemanticsNode().boundsInRoot
            return "stage=$stage verticalValue=${vertical.value()} " +
                "verticalMaxValue=${vertical.maxValue()} horizontalValue=$horizontalValue " +
                "viewportHeight=$viewportHeight node0Top=${b0.top} node0Bottom=${b0.bottom} " +
                "node39Top=${b39.top} node39Bottom=${b39.bottom}"
        }

        fun saveShot(name: String) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(evidence, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }

        // 横轴 range 取自 treeContent 的子节点（生产结构：纵滚列内嵌横滚 Box）；
        // 结构变化时如实记 absent，不在证据里伪装。
        val horizontalValue = compose.onNodeWithTag("finecat.treeContent").fetchSemanticsNode()
            .children.firstNotNullOfOrNull { child ->
                child.config.getOrElseNullable(SemanticsProperties.HorizontalScrollAxisRange) { null }
            }?.value()?.toString() ?: "absent"

        val initialVertical = verticalAxis(
            compose.onNodeWithTag("finecat.treeContent").fetchSemanticsNode(),
        ).value()
        val evidenceLines = mutableListOf(evidenceLine("initial-before-scroll", horizontalValue))
        saveShot("tree-before-scroll.png")

        // 诊断步骤（保留对照，不作为验收）：performScrollToNode 在 ui-test
        // ActionsKt#scrollToNode 中按"匹配节点的最近 hasScrollAction 祖先"寻址并只对
        // 该容器执行 ScrollBy；本树中该祖先是横滚 Box，纵轴意图被交给横轴容器，
        // 滚不动时静默返回（不抛错），随后节点 39 仍不可见——与第 6 轮失败现象一致。
        compose.onNodeWithTag("finecat.treeContent")
            .performScrollToNode(hasText("节点预览 39"))
        compose.waitForIdle()
        evidenceLines += evidenceLine("after-performScrollToNode-helper", horizontalValue)
        saveShot("tree-after-scroll.png")

        // 真实触摸验收：对 treeContent 有界上滑逐页推进（走生产输入管线，手势起止
        // 均在节点 bounds 内），直到节点 39 进入视口；有界上限防异常结构挂死，
        // 到底与否由下方强断言裁决，不在循环里吞失败。
        fun node39InViewport(): Boolean {
            val b = compose.onNodeWithText("节点预览 39").fetchSemanticsNode().boundsInRoot
            return b.width > 0f && b.height > 0f &&
                b.right > viewport.left && b.left < viewport.right &&
                b.bottom > viewport.top && b.top < viewport.bottom
        }
        var swipes = 0
        while (swipes < MAX_SWIPE_PAGES && !node39InViewport()) {
            compose.onNodeWithTag("finecat.treeContent").performTouchInput { swipeUp() }
            compose.waitForIdle()
            swipes++
        }
        val finalVertical = verticalAxis(
            compose.onNodeWithTag("finecat.treeContent").fetchSemanticsNode(),
        ).value()
        evidenceLines += evidenceLine("after-real-swipe-pages=$swipes", horizontalValue)
        saveShot("tree-after-swipe.png")
        File(evidence, "tree-scroll-evidence.txt").writeText(evidenceLines.joinToString("\n"))

        // 强断言：真实触摸必须推进纵向位置（相对未滚基线）；末节点必须实际可见。
        assertTrue(
            "真实触摸后纵向滚动未推进：before=$initialVertical after=$finalVertical " +
                "pages=$swipes；${evidenceLines.joinToString("; ")}",
            finalVertical > initialVertical,
        )
        compose.onNodeWithText("节点预览 39").assertIsDisplayed()
    }

    // ---- 用例 2：带点目录名点击进入下级浏览、不选中父目录 ----

    @Test
    fun directory_dottedDirName_browsesInsteadOfSelectingParent() {
        connectFakeGateway()
        compose.setContent {
            FineCatTheme {
                FineCatDirectorySheet(stateHolder = holder, onDismiss = {})
            }
        }
        // 生产入口：打开目录面板即 beginDirectoryBrowsing → 真实 directories 请求
        compose.runOnIdle { holder.beginDirectoryBrowsing() }
        runBlocking { waitUntil { transport.payloadsOfType("directories").isNotEmpty() } }
        transport.receive(
            """{"kind":"directories","path":"/home/user","home":"/home/user",""" +
                """"crumbs":[{"name":"user","path":"/home/user","hidden":false}],""" +
                """"entries":[{"name":"my.project","path":"/home/user/my.project","hidden":false}]}"""
        )
        runBlocking {
            waitUntil { holder.directoryPath == "/home/user" && holder.directoryEntries.isNotEmpty() }
        }
        waitUntilTag("finecat.dirItem.my.project")
        scrollTagIntoView("finecat.dirItem.my.project")

        compose.onNodeWithTag("finecat.dirItem.my.project").performClick()
        // 带点目录名应走 browseDirectories(子目录)，而不是 selectDirectory(父目录)
        runBlocking { waitUntil { transport.payloadsOfType("directories").size >= 2 } }
        val browseRequest = transport.payloadsOfType("directories").last()
        assertEquals(
            "/home/user/my.project",
            browseRequest.getValue("path").jsonPrimitive.content,
        )
        assertNull(
            "带点目录名被误当文件：selectedDirectoryPath=${holder.selectedDirectoryPath}",
            holder.selectedDirectoryPath,
        )
    }

    // ---- 用例 3：非法配对保留输入、显示行内错误 ----

    @Test
    fun gateway_failedPair_keepsInput() {
        connectFakeGateway()
        compose.setContent {
            FineCatTheme {
                FineCatGatewaySheet(stateHolder = holder, onDismiss = {})
            }
        }
        compose.runOnIdle { holder.pairingPayload = "not-valid-pairing" }
        waitUntilTag("finecat.pairingSubmit")
        scrollTagIntoView("finecat.pairingSubmit")
        val specsBefore = transport.specs.size

        // 非法载荷点配对：本地解析失败→行内错误，输入保留（不清框）
        compose.onNodeWithTag("finecat.pairingSubmit").performClick()
        waitUntilTag("finecat.pairingOutcome")
        scrollTagIntoView("finecat.pairingOutcome")
        compose.onNodeWithTag("finecat.pairingOutcome")
            .assertIsDisplayed()
            .assertTextContains("配对信息无效", substring = true)
        assertEquals("not-valid-pairing", holder.pairingPayload)
        // 本地校验就拦下：不发配对/不重连（无新的连接尝试）
        assertEquals(specsBefore, transport.specs.size)
    }

    private companion object {
        /** 有界逐页上限：40 行 ≈ 2400dp 内容，正常 4–8 页到底；异常结构按上限终止并留证据。 */
        const val MAX_SWIPE_PAGES = 20
    }
}
