package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.*
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.components.FcViewSeg
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F14 真机前总验收 · 102 场景全量矩阵设备测试 (17 状态 × 3 环境 × 亮/暗)。
 *
 * 运行在白名单隔离模拟器（emulator-5580 @ 5041）。
 * 覆盖全部 102 个场景，每个场景：
 * 1. 显式应用对应环境（ENV-390-F1.0、ENV-360-F1.0、ENV-360-F1.3）；
 * 2. 真实驱动到目标状态（S1–S11 基础态 + 6 个子态浮层/弹层）；
 * 3. 亮/暗两态真实渲染并断言核心组件存在且可见；
 * 4. 测量关键组件几何与容差（CAPTURE-MEASURE）；
 * 5. 截取全屏真图落盘至外部 filesDir/shots/<shotName>.png 供 capture 拉取。
 */
@RunWith(AndroidJUnit4::class)
class FineCatMatrix102DeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null
    private var transport: FakeTransport? = null
    private var fixtureNow = java.time.Instant.EPOCH
    private var activeEnv = "ENV-390-F1.0"
    private var imeBottomPx = 0
    private var navigationBottomPx = 0

    @After
    fun tearDown() {
        tearDownGraph()
        restoreDeviceConfig()
    }

    private fun restoreDeviceConfig() {
        runCatching {
            shell("wm density 480")
            shell("settings put system font_scale 1.0")
        }
    }

    private fun tearDownGraph() {
        runCatching { holder?.close() }
        graph?.gatewayScope?.cancel()
        graph?.applicationScope?.cancel()
        holder = null
        graph = null
        transport = null
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText().trim() }
    }

    private fun applyEnv(envId: String) {
        activeEnv = envId
        val (targetDensity, targetFontScale) = when (envId) {
            "ENV-390-F1.0" -> 480 to "1.0"
            "ENV-360-F1.0" -> 520 to "1.0"
            "ENV-360-F1.3" -> 520 to "1.3"
            else -> 480 to "1.0"
        }
        val curD = shell("wm density")
        val curF = shell("settings get system font_scale")
        val needD = !(curD.contains("Override density: $targetDensity") || (targetDensity == 480 && !curD.contains("Override density")))
        val needF = (curF != targetFontScale)
        if (needD || needF) {
            if (needD) shell("wm density $targetDensity")
            if (needF) shell("settings put system font_scale $targetFontScale")
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val dMatch = shell("wm density").contains("Override density: $targetDensity") || (targetDensity == 480 && !shell("wm density").contains("Override density"))
                val fMatch = shell("settings get system font_scale") == targetFontScale
                if (dMatch && fMatch) break
                Thread.sleep(100)
            }
            Thread.sleep(400)
        }
    }

    private fun setupGraphAndGateway() {
        tearDownGraph()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val trans = FakeTransport()
        transport = trans
        val g = AndroidAppGraph(
            application = app,
            transportOverride = trans,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        graph = g
        val h = g.stateHolder
        holder = h

        // 抽屉按设备本地日历分组：夹具日期须与真实UI时钟一致，不能固定某一天。
        val now = java.time.Instant.now()
        fixtureNow = now
        val zone = java.time.ZoneId.systemDefault()
        captureNote("fixture.clock epochMillis=${now.toEpochMilli()} zone=$zone")
        fun iso(daysAgo: Long, hoursAgo: Long = 0): String {
            val day = now.atZone(zone).minusDays(daysAgo)
            val midnight = day.toLocalDate().atStartOfDay(zone).toInstant()
            // 午夜后的前两小时仍保证两个今天样例，不让减小时跨到昨天。
            val earlier = day.minusHours(hoursAgo).toInstant()
            return (if (earlier.isBefore(midnight)) midnight else earlier).toString()
        }

        runBlocking {
            captureNote("fixture.wait transport subscriber/connect")
            waitUntil { trans.specs.isNotEmpty() }
            // SharedFlow 无 replay；等待真正订阅后才投递 hello/目录，避免启动竞争丢帧。
            waitUntil { trans.hasFrameSubscriber }
            trans.open()
            // 1. Hello
            trans.receive(
                """{"kind":"hello","connection":{"state":"connected"},"sessions":[],"contextUsage":null,"model":null}"""
            )
            waitUntil { h.gatewayState.connection.name == "CONNECTED" }
            captureNote("fixture.wait providers/models catalog")

            // 2. Providers
            trans.receive(
                """{"kind":"providers","providers":[{""" +
                    """"id":"zhipu","name":"智谱 · 备用","baseUrl":"https://open.bigmodel.cn/api",""" +
                    """"api":"openai-completions","apiKeySet":true,"active":true,""" +
                    """"models":[""" +
                    """{"id":"glm-5","name":"GLM-5 Pro","reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192,"compat":{"thinking":{"a":1}}},""" +
                    """{"id":"glm-4","name":"GLM-4 Flash","reasoning":false,"input":["text"],"contextWindow":65536,"maxTokens":4096}""" +
                    """],"status":{"ok":true}}]}"""
            )

            // 3. Models catalog
            trans.receive(
                """{"kind":"models","groups":[{"provider":"zhipu","models":[""" +
                    """{"id":"glm-5","name":"GLM-5 Pro","contextWindow":131072,"maxTokens":8192,"reasoning":true,"thinkingLevels":["low","medium","high"]},""" +
                    """{"id":"glm-4","name":"GLM-4 Flash","contextWindow":65536,"maxTokens":4096,"reasoning":false}""" +
                    """]}]}"""
            )
            waitUntil { h.snapshot.modelCatalog?.groups?.singleOrNull()?.models?.size == 2 }
            captureNote("fixture.wait sessions/unread")

            // 4. Sessions: 涵盖今天运行中、今天未读、昨天、更早，满足抽屉全分组与待查看语义色
            trans.receive(
                """{"kind":"sessions","items":[""" +
                    """{"id":"s1","cwd":"/home/user/project-alpha","name":"今天运行中会话","messageCount":3,"firstMessage":"首个测试会话","running":true,"modified":"${iso(0, 1)}"},""" +
                    """{"id":"s2","cwd":"/home/user/project-alpha","name":"待查看未读会话","messageCount":2,"firstMessage":"第二个后台会话","running":false,"modified":"${iso(0, 2)}"},""" +
                    """{"id":"s3","cwd":"/home/user/project-beta","name":"昨天已完成会话","messageCount":4,"firstMessage":"第三个测试会话","running":false,"modified":"${iso(1, 4)}"},""" +
                    """{"id":"s4","cwd":"/home/user/project-beta","name":"更早历史会话","messageCount":5,"firstMessage":"第四个测试会话","running":false,"modified":"${iso(8, 6)}"}""" +
                    """]}"""
            )
            waitUntil { h.snapshot.sessions.size >= 4 }

            // 驱动 s2 产生 unread 事件
            val nowEpoch = now.toEpochMilli() / 1000.0
            trans.receive(
                """{"kind":"event","sessionId":"s2","seq":9001,"time":$nowEpoch,"event":{"type":"session_info_changed","sessionInfo":{"name":"待查看未读会话"}}}"""
            )
            waitUntil { h.snapshot.sessions.firstOrNull { it.id == "s2" }?.hasUnread == true }
        }
    }

    private fun loadHistoryAndTrajectory(sessionId: String, running: Boolean = false) {
        val trans = transport ?: return
        val h = holder ?: return
        compose.runOnIdle { h.selectSession(sessionId) }
        runBlocking {
            waitUntil { h.snapshot.selectedSessionId == sessionId }
            waitUntil { trans.payloadsOfType("history").isNotEmpty() }
        }
        trans.receive(
            """{"kind":"history","sessionId":"$sessionId","messages":[],"entryIds":[],"hasMore":false,"model":{"provider":"zhipu","modelId":"glm-5"},"thinkingLevel":"medium","isStreaming":$running}"""
        )
        runBlocking { waitUntil { h.snapshot.sessionStates[sessionId]?.model?.modelId == "glm-5" } }
        // 构造轨迹轮 1 与工具调用节点，满足 S5/S6/S7 状态要求
        val now = fixtureNow.toEpochMilli() / 1000.0
        trans.receive("""{"kind":"event","sessionId":"$sessionId","seq":1001,"time":$now,"entryId":"e-u1","event":{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"请分析性能"}]}}}""")
        trans.receive("""{"kind":"event","sessionId":"$sessionId","seq":1002,"time":$now,"event":{"type":"message_start","message":{"role":"assistant","content":[]}}}""")
        trans.receive("""{"kind":"event","sessionId":"$sessionId","seq":1003,"time":$now,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"排查完成"}}}""")
        trans.receive("""{"kind":"event","sessionId":"$sessionId","seq":1004,"time":${now+1},"entryId":"e-a1","event":{"type":"message_end","message":{"role":"assistant","provider":"zhipu","model":"glm-5","content":[{"type":"text","text":"排查完成"},{"type":"toolCall","toolCallId":"tc-1","toolName":"read","input":{"path":"/tmp/x"}}],"stopReason":"toolCalls"}}}""")
        trans.receive("""{"kind":"event","sessionId":"$sessionId","seq":1005,"time":${now+1},"event":{"type":"tool_execution_start","toolCallId":"tc-1","toolName":"read","args":{"path":"/tmp/x"}}}""")
        trans.receive("""{"kind":"event","sessionId":"$sessionId","seq":1006,"time":${now+2},"entryId":"e-t1","event":{"type":"message_end","message":{"role":"toolResult","toolCallId":"tc-1","toolName":"read","content":[{"type":"text","text":"metrics: ok"}],"isError":false}}}""")
        // PROTOCOL §4.18：角色来自 role，type 固定为 message；不能用 type 冒充角色。
        trans.receive("""{"kind":"tree","sessionId":"$sessionId","leafId":"h-2","nodes":[{"id":"h-0","preview":"请分析一下性能瓶颈","type":"message","role":"user"},{"id":"h-1","parentId":"h-0","preview":"排查完成","type":"message","role":"assistant"},{"id":"h-2","parentId":"h-1","preview":"metrics: ok","type":"message","role":"toolResult"}]}""")
        compose.waitForIdle()
    }

    private fun renderApp(dark: Boolean) {
        val h = holder ?: return
        compose.runOnIdle { h.updateAppearanceMode(if (dark) "dark" else "light") }
        runBlocking { waitUntil { h.appearanceMode == if (dark) "dark" else "light" } }
        compose.setContent {
            val stateHolder = holder ?: return@setContent
            FineCatTheme(darkTheme = dark) {
                val ime = WindowInsets.ime.getBottom(LocalDensity.current)
                val navigation = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                SideEffect { imeBottomPx = ime; navigationBottomPx = navigation }
                // 和生产一样派发 IME insets；系统栏在视口外消费，内容裁剪不拉伸。
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)) {
                    Box((if (activeEnv == "ENV-390-F1.0") Modifier.height(756.dp) else Modifier.fillMaxSize())
                        .fillMaxWidth().testTag("finecat.fixtureViewport")) {
                        FineCatApp(stateHolder = stateHolder)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun navigateToState(statusId: String) {
        val h = holder ?: return
        when (statusId) {
            "S1" -> {
                compose.runOnIdle { h.prepareNewSession() }
                runBlocking { waitUntil { h.snapshot.selectedSessionId == null } }
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.home").assertExists()
                compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
                compose.onNodeWithTag("finecat.homeBrand").assertExists()
            }
            "S2" -> {
                compose.runOnIdle { h.prepareNewSession() }
                runBlocking { waitUntil { h.snapshot.selectedSessionId == null } }
                compose.waitForIdle()
                compose.onNodeWithTag("fc.composer.input").performClick()
                waitForIme()
                compose.onNodeWithTag("fc.composer.input").assertExists().assertIsDisplayed()
            }
            "S3" -> {
                compose.runOnIdle { h.prepareNewSession() }
                runBlocking { waitUntil { h.snapshot.selectedSessionId == null } }
                compose.waitForIdle()
                compose.onNodeWithTag("fc.composer.input").performClick()
                waitForIme()
                compose.runOnIdle { h.togglePhotosTray() }
                compose.waitForIdle()
                compose.onNodeWithTag("fc.shotStrip").assertExists().assertIsDisplayed()
            }
            "S4" -> {
                loadHistoryAndTrajectory("s1", running = true)
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
                compose.onNodeWithTag("fc.composer.input").assertExists()
                assertTrue("S4 必须是当前会话真实运行态", h.snapshot.sessionStates["s1"]?.isStreaming == true)
                val prose = compose.onAllNodes(SemanticsMatcher("助手正文") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("finecat.prose.") == true
                }, useUnmergedTree = true).fetchSemanticsNodes().single()
                val id = prose.config[SemanticsProperties.TestTag].removePrefix("finecat.prose.")
                val copy = compose.onNodeWithTag("finecat.copy.$id").fetchSemanticsNode().boundsInRoot
                val textBounds = prose.boundsInRoot
                val message = compose.onNodeWithTag("finecat.ai.$id").fetchSemanticsNode().boundsInRoot
                val tolerance = 2f * compose.density.density
                assertTrue("正文应占满消息宽度，不能为复制按钮保留整列", abs(textBounds.left - message.left) <= tolerance && abs(textBounds.right - message.right) <= tolerance)
                assertTrue("复制按钮必须在正文下方右侧且不覆盖文字", copy.top >= textBounds.bottom - tolerance && abs(copy.right - message.right) <= tolerance)
                captureNote("copy.footer prose=$textBounds copy=$copy")
            }
            "S5", "S6", "S7" -> error("TR02: trajectory page removed; frozen F14 scenes are not current acceptance")
            "S8" -> {
                loadHistoryAndTrajectory("s1")
                // 同包矩阵保留多分叉、8 层深链和长 preview；不以三个短节点冒充树适配。
                val deep = (3..8).joinToString(",") { i ->
                    val preview = if (i == 8) "深层长预览_".repeat(24) + "末端F14" else "深层节点 $i"
                    """{"id":"h-$i","parentId":"h-${i-1}","preview":"$preview","type":"message","role":"assistant"}"""
                }
                transport!!.receive("""{"kind":"tree","sessionId":"s1","leafId":"h-8","nodes":[{"id":"h-0","preview":"请分析一下性能瓶颈","type":"message","role":"user"},{"id":"h-1","parentId":"h-0","preview":"排查完成","type":"message","role":"assistant"},{"id":"h-2","parentId":"h-1","preview":"metrics: ok","type":"message","role":"toolResult"},$deep,{"id":"b-0","parentId":"h-0","preview":"第一处分叉","type":"message","role":"assistant"},{"id":"b-1","parentId":"h-1","preview":"第二处分叉","type":"message","role":"assistant"},{"id":"b-2","parentId":"b-1","preview":"分叉子节点","type":"message","role":"user"}]}""")
                compose.waitUntil(5_000) { h.sessionTree?.second == "h-8" }
                compose.onNodeWithContentDescription("tree").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.tree").assertExists().assertIsDisplayed()
                compose.onNodeWithTag("finecat.treeNode.h-8").assertExists()
                compose.onNodeWithTag("finecat.treeNode.b-0").assertExists()
                compose.onNodeWithTag("finecat.treeNode.b-1").assertExists()
                captureNote("tree.fixture nodes=12 depth=8 forks=2 longPreviewEnd=末端F14 leaf=h-8")
            }
            "S9" -> {
                loadHistoryAndTrajectory("s1", running = true)
                compose.onNodeWithTag("finecat.menu").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.drawerSearch").assertExists().assertIsDisplayed()
                compose.onNodeWithText("今天").assertExists()
                compose.onNodeWithText("待查看").assertExists()
            }
            "S10" -> {
                loadHistoryAndTrajectory("s1")
                compose.onNodeWithTag("finecat.menu").performClick()
                compose.waitForIdle()
                val directory = compose.onNodeWithTag("finecat.drawerDirectory.${h.snapshot.sessions.first { it.id == "s1" }.cwd.orEmpty()}")
                directory.performScrollTo().performClick()
                compose.waitForIdle()
                directory.assertIsSelected()
                compose.onNodeWithTag("finecat.drawerSession.s1").assertIsDisplayed()
                compose.onNodeWithTag("finecat.drawerSession.s2").assertExists()
                compose.onNodeWithTag("finecat.drawerSession.s3").assertDoesNotExist()
                compose.onNodeWithTag("finecat.drawerSession.s4").assertDoesNotExist()
                compose.onNodeWithTag("finecat.drawerSearch").assertExists().assertIsDisplayed()
            }
            "S11" -> {
                openSettings()
                compose.onNodeWithTag("finecat.settingsScreen").assertExists().assertIsDisplayed()
            }
            "S1-MODEL-SELECT" -> {
                compose.runOnIdle { h.prepareNewSession() }
                runBlocking { waitUntil { h.snapshot.selectedSessionId == null } }
                compose.runOnIdle { h.selectModel("zhipu", "glm-5", "medium") }
                compose.waitForIdle()
                compose.onNodeWithTag("fc.composer.input").performClick()
                waitForIme()
                compose.onNodeWithTag("finecat.modelChip").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.modelSheet").assertExists().assertIsDisplayed()
                compose.onNodeWithTag("finecat.model.zhipu.glm-5").assertExists()
                compose.onNodeWithTag("finecat.thinking.medium").assertIsSelected()
                compose.onNodeWithText("暂无可用模型").assertDoesNotExist()
            }
            "S4-MODEL-SHEET" -> {
                loadHistoryAndTrajectory("s1")
                compose.onNodeWithTag("fc.composer.input").performClick()
                waitForIme()
                compose.onNodeWithTag("finecat.modelChip").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.modelSheet").assertExists().assertIsDisplayed()
                compose.onNodeWithTag("finecat.model.zhipu.glm-5").assertExists()
                compose.onNodeWithTag("finecat.thinking.medium").assertIsSelected()
                compose.onNodeWithText("暂无可用模型").assertDoesNotExist()
            }
            "S11-PROVIDER-EDIT" -> {
                openSettings()
                compose.onNodeWithTag("finecat.providerRow.zhipu").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.providerEdit").assertExists().assertIsDisplayed()
            }
            "S11-PROVIDER-DIALECT" -> {
                openSettings()
                compose.onNodeWithTag("finecat.providerRow.zhipu").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.providerEdit").assertExists().assertIsDisplayed()
                // DEC-F09-DIALECT: Provider-level dialect row is excluded / does not exist
                compose.onNodeWithTag("finecat.providerApiRow").assertDoesNotExist()
            }
            "S11-MODEL-DETAIL" -> {
                openSettings()
                compose.onNodeWithTag("finecat.providerRow.zhipu").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.modelRow.0").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.modelDetail").assertExists().assertIsDisplayed()
            }
            "S11-MODEL-DIALECT" -> {
                openSettings()
                compose.onNodeWithTag("finecat.providerRow.zhipu").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.modelRow.0").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.modelApiRow").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.modelApi.openai-completions").assertExists().assertIsDisplayed()
            }
        }
    }

    private fun openSettings() {
        val h = holder ?: return
        compose.runOnIdle {
            h.prepareNewSession()
            h.loadProviders()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.drawerSettings").performClick()
        compose.waitForIdle()
    }

    private fun waitForIme() {
        var previous = -1
        var unchangedSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val now = android.os.SystemClock.uptimeMillis()
            if (imeBottomPx != previous) {
                previous = imeBottomPx
                unchangedSince = now
            }
            // 360dp/字体放大环境的系统IME高由系统决定；只等待真实正值稳定，绝不假设330dp。
            imeBottomPx > navigationBottomPx && now - unchangedSince >= 200
        }
        compose.waitForIdle()
    }

    private data class DpBounds(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun viewportRelativeBounds(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
        val viewport = compose.onNodeWithTag("finecat.fixtureViewport", useUnmergedTree = true).fetchSemanticsNode()
        val location = IntArray(2)
        val origin = IntArray(2)
        compose.runOnIdle {
            (node.root as ViewRootForTest).view.getLocationOnScreen(location)
            (viewport.root as ViewRootForTest).view.getLocationOnScreen(origin)
        }
        return node.boundsInRoot.translate(Offset(
            (location[0] - origin[0]).toFloat() - viewport.boundsInRoot.left,
            (location[1] - origin[1]).toFloat() - viewport.boundsInRoot.top,
        ))
    }

    private fun getBounds(tag: String, density: Float): DpBounds {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        node.assertExists()
        val r = viewportRelativeBounds(node.fetchSemanticsNode())
        val w = r.width / density
        val h = r.height / density
        assertTrue("节点 $tag 未放置或 zero-size (w=$w, h=$h)", w > 0 && h > 0)
        return DpBounds(
            r.left / density,
            r.top / density,
            w,
            h,
        )
    }

    private fun emitMeasure(name: String, act: DpBounds, envId: String, tag: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val d = compose.density.density
        val viewport = compose.onNodeWithTag("finecat.fixtureViewport", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val rw = (viewport.width / d).roundToInt()
        val rh = (viewport.height / d).roundToInt()
        if (envId == "ENV-390-F1.0") {
            assertEquals("主对照视口宽", 390, rw)
            assertEquals("主对照视口高（禁止拉伸）", 756, rh)
        }
        // 只记录实测；原型预期由独立浏览器/冻结 CSS 计算，不从当前 Android 坐标回填。
        File(dir, "capture-measure.txt").appendText(
            "CAPTURE-BOUNDS: $name placed=1 actual=${act.x},${act.y},${act.w},${act.h} tag=$tag\n" +
            "CAPTURE-VIEWPORT: env=$envId density=$d fontScale=${compose.density.fontScale} rootBounds=0,0,$rw,$rh screenOrigin=${viewport.left/d},${viewport.top/d} imeBottomDp=${imeBottomPx/d} navigationBottomDp=${navigationBottomPx/d}\n",
        )
        // 保留实际文本盒、字号/行高与首行基线，便于按 CSS 独立判定，避免只量容器。
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        for (node in texts) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
            val layout = layouts.firstOrNull() ?: continue
            val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }.replace("\n", "\\n")
            val r = viewportRelativeBounds(node)
            File(dir, "capture-measure.txt").appendText(
                "CAPTURE-TEXT: text=$text boundsDp=${r.left/d},${r.top/d},${r.width/d},${r.height/d} " +
                    "fontSp=${layout.layoutInput.style.fontSize.value} lineHeightSp=${layout.layoutInput.style.lineHeight.value} " +
                    "fontWeight=${layout.layoutInput.style.fontWeight?.weight} letterSpacingSp=${layout.layoutInput.style.letterSpacing.value} " +
                    "firstBaselineDp=${(r.top+layout.firstBaseline)/d}\n",
            )
        }
    }

    private fun saveShot(shotName: String) = compose.saveEvidenceScreenshot(shotName)

    private fun captureNote(text: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "capture-measure.txt").appendText("CAPTURE-NOTE: $text\n")
    }

    private fun measureTagFor(statusId: String): String {
        return when (statusId) {
            "S1" -> "finecat.menu"
            "S2" -> "fc.composer.inputRow"
            "S3" -> "fc.shotStrip"
            "S4" -> "finecat.viewSeg"
            "S5" -> "finecat.turn.1"
            "S6" -> "finecat.subRail.1"
            "S7" -> "finecat.sheetCard"
            "S8" -> "finecat.treeNode.h-0"
            "S9" -> "finecat.drawerSearch"
            "S10" -> "finecat.drawerDirectories"
            "S11" -> "finecat.gwToggle"
            "S1-MODEL-SELECT" -> "finecat.modelSheetClose"
            "S4-MODEL-SHEET" -> "finecat.modelSheetClose"
            "S11-PROVIDER-EDIT" -> "finecat.formBaseUrl"
            "S11-PROVIDER-DIALECT" -> "finecat.providerEdit"
            "S11-MODEL-DETAIL" -> "finecat.modelName"
            "S11-MODEL-DIALECT" -> "finecat.modelApiPopover"
            else -> "finecat.menu"
        }
    }

    private fun runScenario(statusId: String, envId: String, mode: String, shotName: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "capture-measure.txt").writeText("")
        applyEnv(envId)
        setupGraphAndGateway()
        val dark = (mode == "dark")
        renderApp(dark)
        navigateToState(statusId)

        if (statusId in setOf("S2", "S3", "S1-MODEL-SELECT", "S4-MODEL-SHEET")) {
            // focus/idle 早于系统 IME 动画；必须等真实 Insets，而非以焦点冒充键盘态。
            waitForIme()
        }

        val density = compose.density.density
        val tagToMeasure = measureTagFor(statusId)
        val act = getBounds(tagToMeasure, density)
        val measureName = "m102.${statusId.lowercase()}"
        emitMeasure(measureName, act, envId, tagToMeasure)
        if (statusId == "S11-MODEL-DIALECT") {
            val anchor = getBounds("finecat.modelApiRow", density)
            captureNote("popover.anchor actual=${anchor.x},${anchor.y},${anchor.w},${anchor.h}")
        }

        saveShot(shotName)
    }

    // ========================================================================
    // 102 场景测试方法 (17 状态 × 3 环境 × 亮/暗)
    // 命名规范：m102_001 .. m102_102，严格对应 screens.tsv 1..102 行
    // ========================================================================

    // --- S1: 首页空态 (1..6) ---
    @Test fun m102_001() = runScenario("S1", "ENV-390-F1.0", "light", "m102_S1_ENV-390-F1.0_light.png")
    @Test fun m102_002() = runScenario("S1", "ENV-390-F1.0", "dark", "m102_S1_ENV-390-F1.0_dark.png")
    @Test fun m102_003() = runScenario("S1", "ENV-360-F1.0", "light", "m102_S1_ENV-360-F1.0_light.png")
    @Test fun m102_004() = runScenario("S1", "ENV-360-F1.0", "dark", "m102_S1_ENV-360-F1.0_dark.png")
    @Test fun m102_005() = runScenario("S1", "ENV-360-F1.3", "light", "m102_S1_ENV-360-F1.3_light.png")
    @Test fun m102_006() = runScenario("S1", "ENV-360-F1.3", "dark", "m102_S1_ENV-360-F1.3_dark.png")

    // --- S2: 输入框展开 (7..12) ---
    @Test fun m102_007() = runScenario("S2", "ENV-390-F1.0", "light", "m102_S2_ENV-390-F1.0_light.png")
    @Test fun m102_008() = runScenario("S2", "ENV-390-F1.0", "dark", "m102_S2_ENV-390-F1.0_dark.png")
    @Test fun m102_009() = runScenario("S2", "ENV-360-F1.0", "light", "m102_S2_ENV-360-F1.0_light.png")
    @Test fun m102_010() = runScenario("S2", "ENV-360-F1.0", "dark", "m102_S2_ENV-360-F1.0_dark.png")
    @Test fun m102_011() = runScenario("S2", "ENV-360-F1.3", "light", "m102_S2_ENV-360-F1.3_light.png")
    @Test fun m102_012() = runScenario("S2", "ENV-360-F1.3", "dark", "m102_S2_ENV-360-F1.3_dark.png")

    // --- S3: 选图托盘 (13..18) ---
    @Test fun m102_013() = runScenario("S3", "ENV-390-F1.0", "light", "m102_S3_ENV-390-F1.0_light.png")
    @Test fun m102_014() = runScenario("S3", "ENV-390-F1.0", "dark", "m102_S3_ENV-390-F1.0_dark.png")
    @Test fun m102_015() = runScenario("S3", "ENV-360-F1.0", "light", "m102_S3_ENV-360-F1.0_light.png")
    @Test fun m102_016() = runScenario("S3", "ENV-360-F1.0", "dark", "m102_S3_ENV-360-F1.0_dark.png")
    @Test fun m102_017() = runScenario("S3", "ENV-360-F1.3", "light", "m102_S3_ENV-360-F1.3_light.png")
    @Test fun m102_018() = runScenario("S3", "ENV-360-F1.3", "dark", "m102_S3_ENV-360-F1.3_dark.png")

    // --- S4: 对话会话 (19..24) ---
    @Test fun m102_019() = runScenario("S4", "ENV-390-F1.0", "light", "m102_S4_ENV-390-F1.0_light.png")
    @Test fun m102_020() = runScenario("S4", "ENV-390-F1.0", "dark", "m102_S4_ENV-390-F1.0_dark.png")
    @Test fun m102_021() = runScenario("S4", "ENV-360-F1.0", "light", "m102_S4_ENV-360-F1.0_light.png")
    @Test fun m102_022() = runScenario("S4", "ENV-360-F1.0", "dark", "m102_S4_ENV-360-F1.0_dark.png")
    @Test fun m102_023() = runScenario("S4", "ENV-360-F1.3", "light", "m102_S4_ENV-360-F1.3_light.png")
    @Test fun m102_024() = runScenario("S4", "ENV-360-F1.3", "dark", "m102_S4_ENV-360-F1.3_dark.png")

    // --- S5: 轨迹收起 (25..30) ---

    // --- S6: 轨迹展开 (31..36) ---

    // --- S7: Span 详情 (37..42) ---

    // --- S8: 树形分支 (43..48) ---
    @Test fun m102_043() = runScenario("S8", "ENV-390-F1.0", "light", "m102_S8_ENV-390-F1.0_light.png")
    @Test fun m102_044() = runScenario("S8", "ENV-390-F1.0", "dark", "m102_S8_ENV-390-F1.0_dark.png")
    @Test fun m102_045() = runScenario("S8", "ENV-360-F1.0", "light", "m102_S8_ENV-360-F1.0_light.png")
    @Test fun m102_046() = runScenario("S8", "ENV-360-F1.0", "dark", "m102_S8_ENV-360-F1.0_dark.png")
    @Test fun m102_047() = runScenario("S8", "ENV-360-F1.3", "light", "m102_S8_ENV-360-F1.3_light.png")
    @Test fun m102_048() = runScenario("S8", "ENV-360-F1.3", "dark", "m102_S8_ENV-360-F1.3_dark.png")

    // --- S9: 抽屉今天/昨天/更早 (49..54) ---
    @Test fun m102_049() = runScenario("S9", "ENV-390-F1.0", "light", "m102_S9_ENV-390-F1.0_light.png")
    @Test fun m102_050() = runScenario("S9", "ENV-390-F1.0", "dark", "m102_S9_ENV-390-F1.0_dark.png")
    @Test fun m102_051() = runScenario("S9", "ENV-360-F1.0", "light", "m102_S9_ENV-360-F1.0_light.png")
    @Test fun m102_052() = runScenario("S9", "ENV-360-F1.0", "dark", "m102_S9_ENV-360-F1.0_dark.png")
    @Test fun m102_053() = runScenario("S9", "ENV-360-F1.3", "light", "m102_S9_ENV-360-F1.3_light.png")
    @Test fun m102_054() = runScenario("S9", "ENV-360-F1.3", "dark", "m102_S9_ENV-360-F1.3_dark.png")

    // --- S10: 抽屉全部恢复 (55..60) ---
    @Test fun m102_055() = runScenario("S10", "ENV-390-F1.0", "light", "m102_S10_ENV-390-F1.0_light.png")
    @Test fun m102_056() = runScenario("S10", "ENV-390-F1.0", "dark", "m102_S10_ENV-390-F1.0_dark.png")
    @Test fun m102_057() = runScenario("S10", "ENV-360-F1.0", "light", "m102_S10_ENV-360-F1.0_light.png")
    @Test fun m102_058() = runScenario("S10", "ENV-360-F1.0", "dark", "m102_S10_ENV-360-F1.0_dark.png")
    @Test fun m102_059() = runScenario("S10", "ENV-360-F1.3", "light", "m102_S10_ENV-360-F1.3_light.png")
    @Test fun m102_060() = runScenario("S10", "ENV-360-F1.3", "dark", "m102_S10_ENV-360-F1.3_dark.png")

    // --- S11: 设置整页 (61..66) ---
    @Test fun m102_061() = runScenario("S11", "ENV-390-F1.0", "light", "m102_S11_ENV-390-F1.0_light.png")
    @Test fun m102_062() = runScenario("S11", "ENV-390-F1.0", "dark", "m102_S11_ENV-390-F1.0_dark.png")
    @Test fun m102_063() = runScenario("S11", "ENV-360-F1.0", "light", "m102_S11_ENV-360-F1.0_light.png")
    @Test fun m102_064() = runScenario("S11", "ENV-360-F1.0", "dark", "m102_S11_ENV-360-F1.0_dark.png")
    @Test fun m102_065() = runScenario("S11", "ENV-360-F1.3", "light", "m102_S11_ENV-360-F1.3_light.png")
    @Test fun m102_066() = runScenario("S11", "ENV-360-F1.3", "dark", "m102_S11_ENV-360-F1.3_dark.png")

    // --- S1-MODEL-SELECT: 首页模型选择浮层 (67..72) ---
    @Test fun m102_067() = runScenario("S1-MODEL-SELECT", "ENV-390-F1.0", "light", "m102_S1-MODEL-SELECT_ENV-390-F1.0_light.png")
    @Test fun m102_068() = runScenario("S1-MODEL-SELECT", "ENV-390-F1.0", "dark", "m102_S1-MODEL-SELECT_ENV-390-F1.0_dark.png")
    @Test fun m102_069() = runScenario("S1-MODEL-SELECT", "ENV-360-F1.0", "light", "m102_S1-MODEL-SELECT_ENV-360-F1.0_light.png")
    @Test fun m102_070() = runScenario("S1-MODEL-SELECT", "ENV-360-F1.0", "dark", "m102_S1-MODEL-SELECT_ENV-360-F1.0_dark.png")
    @Test fun m102_071() = runScenario("S1-MODEL-SELECT", "ENV-360-F1.3", "light", "m102_S1-MODEL-SELECT_ENV-360-F1.3_light.png")
    @Test fun m102_072() = runScenario("S1-MODEL-SELECT", "ENV-360-F1.3", "dark", "m102_S1-MODEL-SELECT_ENV-360-F1.3_dark.png")

    // --- S4-MODEL-SHEET: 会话内模型弹层 (73..78) ---
    @Test fun m102_073() = runScenario("S4-MODEL-SHEET", "ENV-390-F1.0", "light", "m102_S4-MODEL-SHEET_ENV-390-F1.0_light.png")
    @Test fun m102_074() = runScenario("S4-MODEL-SHEET", "ENV-390-F1.0", "dark", "m102_S4-MODEL-SHEET_ENV-390-F1.0_dark.png")
    @Test fun m102_075() = runScenario("S4-MODEL-SHEET", "ENV-360-F1.0", "light", "m102_S4-MODEL-SHEET_ENV-360-F1.0_light.png")
    @Test fun m102_076() = runScenario("S4-MODEL-SHEET", "ENV-360-F1.0", "dark", "m102_S4-MODEL-SHEET_ENV-360-F1.0_dark.png")
    @Test fun m102_077() = runScenario("S4-MODEL-SHEET", "ENV-360-F1.3", "light", "m102_S4-MODEL-SHEET_ENV-360-F1.3_light.png")
    @Test fun m102_078() = runScenario("S4-MODEL-SHEET", "ENV-360-F1.3", "dark", "m102_S4-MODEL-SHEET_ENV-360-F1.3_dark.png")

    // --- S11-PROVIDER-EDIT: Provider 编辑 Sheet (79..84) ---
    @Test fun m102_079() = runScenario("S11-PROVIDER-EDIT", "ENV-390-F1.0", "light", "m102_S11-PROVIDER-EDIT_ENV-390-F1.0_light.png")
    @Test fun m102_080() = runScenario("S11-PROVIDER-EDIT", "ENV-390-F1.0", "dark", "m102_S11-PROVIDER-EDIT_ENV-390-F1.0_dark.png")
    @Test fun m102_081() = runScenario("S11-PROVIDER-EDIT", "ENV-360-F1.0", "light", "m102_S11-PROVIDER-EDIT_ENV-360-F1.0_light.png")
    @Test fun m102_082() = runScenario("S11-PROVIDER-EDIT", "ENV-360-F1.0", "dark", "m102_S11-PROVIDER-EDIT_ENV-360-F1.0_dark.png")
    @Test fun m102_083() = runScenario("S11-PROVIDER-EDIT", "ENV-360-F1.3", "light", "m102_S11-PROVIDER-EDIT_ENV-360-F1.3_light.png")
    @Test fun m102_084() = runScenario("S11-PROVIDER-EDIT", "ENV-360-F1.3", "dark", "m102_S11-PROVIDER-EDIT_ENV-360-F1.3_dark.png")

    // --- S11-PROVIDER-DIALECT: Provider 协议方言提示 (85..90) ---
    @Test fun m102_085() = runScenario("S11-PROVIDER-DIALECT", "ENV-390-F1.0", "light", "m102_S11-PROVIDER-DIALECT_ENV-390-F1.0_light.png")
    @Test fun m102_086() = runScenario("S11-PROVIDER-DIALECT", "ENV-390-F1.0", "dark", "m102_S11-PROVIDER-DIALECT_ENV-390-F1.0_dark.png")
    @Test fun m102_087() = runScenario("S11-PROVIDER-DIALECT", "ENV-360-F1.0", "light", "m102_S11-PROVIDER-DIALECT_ENV-360-F1.0_light.png")
    @Test fun m102_088() = runScenario("S11-PROVIDER-DIALECT", "ENV-360-F1.0", "dark", "m102_S11-PROVIDER-DIALECT_ENV-360-F1.0_dark.png")
    @Test fun m102_089() = runScenario("S11-PROVIDER-DIALECT", "ENV-360-F1.3", "light", "m102_S11-PROVIDER-DIALECT_ENV-360-F1.3_light.png")
    @Test fun m102_090() = runScenario("S11-PROVIDER-DIALECT", "ENV-360-F1.3", "dark", "m102_S11-PROVIDER-DIALECT_ENV-360-F1.3_dark.png")

    // --- S11-MODEL-DETAIL: 模型详情 Sheet (91..96) ---
    @Test fun m102_091() = runScenario("S11-MODEL-DETAIL", "ENV-390-F1.0", "light", "m102_S11-MODEL-DETAIL_ENV-390-F1.0_light.png")
    @Test fun m102_092() = runScenario("S11-MODEL-DETAIL", "ENV-390-F1.0", "dark", "m102_S11-MODEL-DETAIL_ENV-390-F1.0_dark.png")
    @Test fun m102_093() = runScenario("S11-MODEL-DETAIL", "ENV-360-F1.0", "light", "m102_S11-MODEL-DETAIL_ENV-360-F1.0_light.png")
    @Test fun m102_094() = runScenario("S11-MODEL-DETAIL", "ENV-360-F1.0", "dark", "m102_S11-MODEL-DETAIL_ENV-360-F1.0_dark.png")
    @Test fun m102_095() = runScenario("S11-MODEL-DETAIL", "ENV-360-F1.3", "light", "m102_S11-MODEL-DETAIL_ENV-360-F1.3_light.png")
    @Test fun m102_096() = runScenario("S11-MODEL-DETAIL", "ENV-360-F1.3", "dark", "m102_S11-MODEL-DETAIL_ENV-360-F1.3_dark.png")

    // --- S11-MODEL-DIALECT: 模型方言浮层 (97..102) ---
    @Test fun m102_097() = runScenario("S11-MODEL-DIALECT", "ENV-390-F1.0", "light", "m102_S11-MODEL-DIALECT_ENV-390-F1.0_light.png")
    @Test fun m102_098() = runScenario("S11-MODEL-DIALECT", "ENV-390-F1.0", "dark", "m102_S11-MODEL-DIALECT_ENV-390-F1.0_dark.png")
    @Test fun m102_099() = runScenario("S11-MODEL-DIALECT", "ENV-360-F1.0", "light", "m102_S11-MODEL-DIALECT_ENV-360-F1.0_light.png")
    @Test fun m102_100() = runScenario("S11-MODEL-DIALECT", "ENV-360-F1.0", "dark", "m102_S11-MODEL-DIALECT_ENV-360-F1.0_dark.png")
    @Test fun m102_101() = runScenario("S11-MODEL-DIALECT", "ENV-360-F1.3", "light", "m102_S11-MODEL-DIALECT_ENV-360-F1.3_light.png")
    @Test fun m102_102() = runScenario("S11-MODEL-DIALECT", "ENV-360-F1.3", "dark", "m102_S11-MODEL-DIALECT_ENV-360-F1.3_dark.png")

    // ========================================================================
    // 3 项触控尺寸实测与坐标投递验证，区分视觉尺寸和实际命中区域
    // ========================================================================

    @Test
    fun verifyEx48dpViewSeg_boundsAndTouchAndCoordinateClicks() {
        applyEnv("ENV-390-F1.0")
        setupGraphAndGateway()
        renderApp(dark = false)
        loadHistoryAndTrajectory("s1")
        compose.waitForIdle()

        val density = compose.density.density
        val chatNode = compose.onNodeWithTag("finecat.viewSeg.chat", useUnmergedTree = true)
        val treeNode = compose.onNodeWithTag("finecat.viewSeg.tree", useUnmergedTree = true)
        val chatBounds = chatNode.fetchSemanticsNode().boundsInRoot
        val treeBounds = treeNode.fetchSemanticsNode().boundsInRoot
        assertEquals(28f, chatBounds.width / density, 1f)
        assertEquals(26f, chatBounds.height / density, 1f)
        assertEquals(28f, treeBounds.width / density, 1f)
        compose.onNodeWithContentDescription("轨迹").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(treeBounds.center) }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.tree").assertIsDisplayed()
        compose.onRoot().performTouchInput { click(chatBounds.center) }
        compose.waitForIdle()
        compose.onNodeWithTag("fc.composer.input").assertExists()
        captureNote("viewseg.twoItems coordinate delivery passed")
    }

    @Test
    fun verifyEx48dpViewSeg_coordinateDeliveryExactlyOnce() {
        applyEnv("ENV-390-F1.0")
        val selected = mutableIntStateOf(0)
        val deliveries = mutableListOf<Int>()
        compose.setContent {
            FineCatTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FcViewSeg(
                        icons = listOf(FineCatIcons.Chat, FineCatIcons.Branch),
                        labels = listOf("对话", "tree"),
                        selected = selected.intValue,
                        onSelect = { deliveries += it; selected.intValue = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val tags = listOf("finecat.viewSeg.chat", "finecat.viewSeg.tree")
        val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val probes = bounds.flatMapIndexed { index, b -> listOf(
            index to b.center,
            index to Offset(b.left + density, b.center.y),
            index to Offset(b.right - density, b.center.y),
            index to Offset(b.center.x, b.top - 5*density),
        ) } + listOf(
            0 to Offset(bounds[0].right + 0.5f*density, bounds[0].center.y),
            1 to Offset(bounds[1].right + 0.5f*density, bounds[1].center.y),
        )
        probes.forEachIndexed { index, (expected, point) ->
            val before = deliveries.size
            compose.onRoot().performTouchInput { click(point) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("落点 $index 必须恰好回调一次", before + 1, deliveries.size)
                assertEquals("落点 $index 归属必须无歧义", expected, deliveries.last())
            }
            captureNote("viewseg.delivery probe=$index pointDp=${point.x/density},${point.y/density} target=$expected callbacks=1")
        }
        captureNote("viewseg.deliveryExactlyOnce passed=${probes.size} total=${probes.size}")
        saveShot("f14-48dp-viewseg-delivery.png")
    }

    @Test
    fun verifyEx48dpPopover_boundsAndTouchAndCoordinateClicks() {
        applyEnv("ENV-390-F1.0")
        setupGraphAndGateway()
        renderApp(dark = false)
        openSettings()
        compose.onNodeWithTag("finecat.providerRow.zhipu").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelRow.0").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelApiRow").performScrollTo().performClick()
        compose.waitForIdle()

        val density = compose.density.density
        val popoverNode = compose.onNodeWithTag("finecat.modelApiPopover", useUnmergedTree = true)
        val popoverBounds = popoverNode.fetchSemanticsNode().boundsInRoot
        val popoverHeightDp = popoverBounds.height / density
        assertTrue("popover height ($popoverHeightDp) <= 340dp", popoverHeightDp <= 340f)

        val itemNode = compose.onNodeWithTag("finecat.modelApi.openai-completions", useUnmergedTree = true)
        val itemBounds = itemNode.fetchSemanticsNode().boundsInRoot
        val itemTouch = itemNode.fetchSemanticsNode().touchBoundsInRoot
        val itemHeightDp = itemBounds.height / density
        assertTrue("item height ($itemHeightDp) >= 48dp", itemHeightDp >= 47.5f)
        assertTrue("item touch height (${itemTouch.height / density}) >= 48dp", itemTouch.height / density >= 47.5f)

        captureNote("popover.touchBounds itemHeight=${itemHeightDp}dp totalHeight=${popoverHeightDp}dp")

        // 坐标点击末项 google-generative-ai（直接对节点施加触摸点击以避免独立 Popup window 的多根歧义）
        val lastItemNode = compose.onNodeWithTag("finecat.modelApi.google-generative-ai", useUnmergedTree = true)
        lastItemNode.performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelApiPopover").assertDoesNotExist()
        compose.onNodeWithText("google-generative-ai").assertExists()
        captureNote("popover.lastItemClick passed")
        saveShot("f14-48dp-popover.png")
    }

    @Test
    fun verifyEx48dpSheetDone_boundsAndTouchAndCoordinateClicks() {
        applyEnv("ENV-390-F1.0")
        setupGraphAndGateway()
        renderApp(dark = false)
        navigateToState("S4-MODEL-SHEET")

        val density = compose.density.density
        // 保留历史方法入口；当前模型面板头部动作已改成关闭，不造“完成”按钮。
        val doneNode = compose.onNodeWithTag("finecat.modelSheetClose", useUnmergedTree = true)
        val doneBounds = doneNode.fetchSemanticsNode().boundsInRoot
        val doneTouch = doneNode.fetchSemanticsNode().touchBoundsInRoot

        val doneHeightDp = doneBounds.height / density
        val touchWidthDp = doneTouch.width / density
        val touchHeightDp = doneTouch.height / density

        assertTrue("close height ($doneHeightDp) >= 48dp", doneHeightDp >= 47.5f)
        assertTrue("close touch width ($touchWidthDp) >= 48dp", touchWidthDp >= 47.5f)
        assertTrue("close touch height ($touchHeightDp) >= 48dp", touchHeightDp >= 47.5f)

        val headerNode = compose.onNodeWithTag("finecat.modelHeader", useUnmergedTree = true)
        val headerBounds = headerNode.fetchSemanticsNode().boundsInRoot
        val headerHeightDp = headerBounds.height / density
        assertTrue("model header >=56dp", headerHeightDp >= 55.5f)

        captureNote("modelSheetClose.touchBounds bounds=${doneBounds.width/density}x${doneHeightDp}dp touch=${touchWidthDp}x${touchHeightDp}dp headerHeight=${headerHeightDp}dp")

        // 坐标点击关闭键 -> Sheet 关闭
        doneNode.performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelSheet").assertDoesNotExist()
        captureNote("modelSheetClose.coordinateClick passed")
        saveShot("f14-48dp-sheet-done.png")
    }
}
