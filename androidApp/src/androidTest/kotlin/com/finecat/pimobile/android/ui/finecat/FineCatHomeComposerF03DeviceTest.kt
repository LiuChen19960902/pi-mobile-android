package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
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
import com.finecat.pimobile.android.platform.RecentPhotosProvider
import com.finecat.pimobile.android.tinyPng
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.theme.darkFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.lightFineCatColors
import com.finecat.pimobile.android.waitUntil
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F03：S1 首页 / S2 输入三态 / S3 选图条 / S1·S4 模型选择子态的原型对齐取证。
 *
 * 覆盖 Pi 标志与可编辑签名首页、输入区、选图条和模型选择面板。
 * 几何容差 ≤2dp，纯色通道容差 ≤3/255；分别检查亮暗主题与不同窗口尺寸。
 *
 * 宿主与 FineCatHomeParityDeviceTest 同口径：AndroidAppGraph 全套 Fake override +
 * 生产组合路径。暗色走产品真实路径 `holder.updateAppearanceMode`（FineCatApp 内层
 * 主题按它决定，外层 FineCatTheme(darkTheme=) 会被覆盖——见 HomeParity 注释）。
 * 选图：托盘显示/多选/上限/失败经门面真实状态（recentPhotos 注入 fake、prepare 走
 * 真实 AndroidImagePreprocessor）；权限弹窗与系统相册链路测试进程无法驱动，留真机
 * （HomeParity 已登记的同一限制）。中文组合输入（IME 合成期）同样留真机。
 *
 * 所有截图落 external files 根，由 verify-stage capture 归领；fixture 照片放
 * files/f03-media/ 子目录（capture 只拉根目录 PNG，不误归领）。
 */
@RunWith(AndroidJUnit4::class)
class FineCatHomeComposerF03DeviceTest {

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

    // ------------------------------------------------------------------
    // 宿主与 fixture
    // ------------------------------------------------------------------

    private fun connectFakeGateway(recentPhotos: RecentPhotosProvider? = null) {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            recentPhotosOverride = recentPhotos,
        )
        holder = graph.stateHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(
                """{"kind":"hello","connection":{"state":"connected"},"sessions":[],""" +
                    """"contextUsage":null,"model":null}"""
            )
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
    }

    private fun setContentFromRealRoot() {
        compose.setContent {
            val stateHolder = holder
            FineCatTheme { FineCatApp(stateHolder = stateHolder) }
        }
        compose.waitForIdle()
    }

    private fun goHomeEmptyState() {
        compose.runOnIdle {
            holder.prepareNewSession()
            // 本组测发送/模型/照片，目录交互由 F10 单独覆盖；显式指定合成目录。
            holder.selectFineCatDirectory("/tmp/synth")
        }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }
        compose.waitForIdle()
    }

    /** 注入模型目录（wire 帧格式照 shared GatewayProtocolFixtures 的 models 帧）。 */
    private fun injectModelCatalog() {
        transport.receive(MODELS_FRAME)
        runBlocking { waitUntil { holder.snapshot.modelCatalog != null } }
    }

    private fun selectFixtureSession() {
        transport.receive(
            """{"kind":"sessions","items":[{"id":"s1","cwd":"/tmp/synth",""" +
                """"messageCount":4,"firstMessage":"F03 模型 sheet 会话","running":false}]}"""
        )
        runBlocking { waitUntil { holder.snapshot.sessions.isNotEmpty() } }
        compose.runOnIdle { holder.selectSession("s1") }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == "s1" } }
        compose.waitForIdle()
    }

    /** 打开模型 sheet 并等目录渲染（真实入口：聚焦展开 → 模型胶囊）。 */
    private fun openModelSheetViaChip() {
        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelChip").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.modelSheet", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun chipTexts(): List<String> =
        compose.onNodeWithTag("finecat.modelChip")
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)
            ?.map { it.text }
            ?: emptyList()

    /** 真实 PNG 落盘（file uri 可被 AndroidImagePreprocessor 实际解码）。 */
    private fun ensurePhotoFiles(count: Int): List<Uri> {
        val root = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        val dir = File(root, "f03-media").apply { mkdirs() }
        val bytes = tinyPng()
        return (1..count).map { i ->
            val f = File(dir, "f03-photo-$i.png")
            if (!f.exists()) f.writeBytes(bytes)
            Uri.fromFile(f)
        }
    }

    /** fake：托盘格子带互不相同的缩略图底色（真实解码链路属 MediaStore，留真机）。 */
    private class FakeRecentPhotos(private val uris: List<Uri>) : RecentPhotosProvider {
        override suspend fun recentUris(limit: Int): List<Uri> = uris.take(limit)
        override suspend fun thumbnail(uri: Uri, edge: Int): androidx.compose.ui.graphics.ImageBitmap? {
            val idx = uris.indexOf(uri)
            if (idx < 0) return null
            val bmp = android.graphics.Bitmap.createBitmap(
                edge, edge, android.graphics.Bitmap.Config.ARGB_8888,
            )
            bmp.eraseColor(
                android.graphics.Color.rgb(
                    (30 + idx * 47) % 220,
                    (60 + idx * 89) % 200,
                    (220 - idx * 53) % 200,
                ),
            )
            return bmp.asImageBitmap()
        }
    }

    private fun saveShot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        // 平台错误弹窗是独立窗口：测试规则下出现第二个 root，此时截弹窗所在窗口
        val shot = try {
            compose.onRoot().captureToImage()
        } catch (e: AssertionError) {
            compose.onAllNodes(androidx.compose.ui.test.isRoot())[1].captureToImage()
        }.asAndroidBitmap()
        File(dir, name).outputStream().use { out ->
            shot.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // ------------------------------------------------------------------
    // 像素/几何工具（DEC-TOLERANCE：几何 ≤2dp=6px@density480；纯色 ≤3/255）
    // ------------------------------------------------------------------

    private val pxPerDp: Float
        get() = compose.density.density

    private fun rootBitmap(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun assertPixelNear(bmp: Bitmap, x: Int, y: Int, expected: Long, what: String, tol: Int = 3) {
        val c = bmp.getPixel(x, y)
        val dr = abs(android.graphics.Color.red(c) - android.graphics.Color.red(expected.toInt()))
        val dg = abs(android.graphics.Color.green(c) - android.graphics.Color.green(expected.toInt()))
        val db = abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(expected.toInt()))
        assertTrue(
            "$what @($x,$y) 实测=${Integer.toHexString(c)} 期望=#${expected.toString(16)} " +
                "通道差 r=$dr g=$dg b=$db 超 $tol",
            dr <= tol && dg <= tol && db <= tol,
        )
    }

    // ------------------------------------------------------------------
    // 1) S1 首页结构与几何
    // ------------------------------------------------------------------

    @Test
    fun s1_home_structure_countsAndHeroGeometry() {
        connectFakeGateway()
        setContentFromRealRoot()
        goHomeEmptyState()

        // 计数硬条件：菜单=1；标题/目录副行/ViewSeg=0；品牌两词各=1
        assertEquals(
            "首页菜单按钮应恰好 1 个",
            1,
            compose.onAllNodesWithContentDescription("会话列表").fetchSemanticsNodes().size,
        )
        assertEquals(
            "首页不应存在标题触控目标（原型 appHead({menu, title:''})）",
            0,
            compose.onAllNodesWithTag("finecat.headerTitle").fetchSemanticsNodes().size,
        )
        for (seg in listOf("对话", "轨迹", "tree")) {
            assertEquals(
                "首页不应存在 ViewSeg 项 $seg",
                0,
                compose.onAllNodesWithContentDescription(seg).fetchSemanticsNodes().size,
            )
        }
        assertEquals(
            "品牌名 FineCat 应恰好 1 处",
            1,
            compose.onAllNodesWithText("FineCat", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            "品牌标语应恰好 1 处",
            1,
            compose.onAllNodesWithText("探索未至之境", useUnmergedTree = true).fetchSemanticsNodes().size,
        )

        val px = pxPerDp
        compose.onNodeWithTag("finecat.brandDots").assertDoesNotExist()
        val logo = compose.onNodeWithTag("finecat.brandLogo").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val nameNode = compose.onNodeWithTag("finecat.brandTitle", useUnmergedTree = true)
        val name = nameNode.fetchSemanticsNode().boundsInRoot
        val taglineNode = compose.onNodeWithTag("finecat.brandTagline", useUnmergedTree = true)
        val tagline = taglineNode.fetchSemanticsNode().boundsInRoot
        assertEquals("Pi标志宽38dp", 38f * px, logo.width, 2f * px)
        assertEquals("Pi标志高38dp", 38f * px, logo.height, 2f * px)
        assertEquals("标志到签名间隔16dp", 16f * px, name.top - logo.bottom, 2f * px)
        assertEquals("品牌两词间隔12dp", 12f * px, tagline.left - name.right, 2f * px)
        val homeBox = compose.onNodeWithTag("finecat.home").fetchSemanticsNode().boundsInRoot
        assertEquals("标志与签名区整体垂直居中", homeBox.center.y, (logo.top + name.bottom) / 2f, 2f * px)
        assertEquals("标志水平居中", homeBox.center.x, logo.center.x, 2f * px)
        for (node in listOf(nameNode, taglineNode)) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val style = layouts.single().layoutInput.style
            assertEquals("签名字号18sp", 18f, style.fontSize.value, 0f)
            assertEquals("签名字重600", FontWeight.SemiBold, style.fontWeight)
        }
        saveShot("f03-s1-struct.png")
    }

    // ------------------------------------------------------------------
    // 2) S1 纯色背景与Pi颜色采样（亮暗各一，≤3/255）
    // ------------------------------------------------------------------

    @Test
    fun s1_home_backdropColors_light() = backdropColorStep(dark = false, shot = "f03-s1-colors-light.png")

    @Test
    fun s1_home_backdropColors_dark() = backdropColorStep(dark = true, shot = "f03-s1-colors-dark.png")

    private fun backdropColorStep(dark: Boolean, shot: String) {
        connectFakeGateway()
        setContentFromRealRoot()
        compose.runOnIdle {
            holder.updateAppearanceMode(if (dark) "dark" else "light")
            holder.prepareNewSession()
        }
        runBlocking {
            waitUntil { holder.snapshot.selectedSessionId == null }
            waitUntil { holder.appearanceMode == if (dark) "dark" else "light" }
        }
        compose.waitForIdle()

        val bg = if (dark) 0xFF0C0C0EL else 0xFFFAFAFAL
        val ink = if (dark) 0xFFF2F2F3L else 0xFF1A1A1CL
        val bmp = rootBitmap()
        val homeBox = compose.onNodeWithTag("finecat.home").fetchSemanticsNode().boundsInRoot
        // 原网格线及其附近应均为纯色，检查上方空白区域的多个落点。
        for (x in listOf(46f, 92f, 138f, 160f)) {
            assertPixelNear(
                bmp, (homeBox.left + x * pxPerDp).roundToInt(),
                (homeBox.top + 30f * pxPerDp).roundToInt(), bg, "首页纯色背景x=$x",
            )
        }
        val logo = compose.onNodeWithTag("finecat.brandLogo").fetchSemanticsNode().boundsInRoot
        assertPixelNear(
            bmp, (logo.left + logo.width * 0.18f).roundToInt(),
            (logo.top + logo.height * 0.18f).roundToInt(), ink, "Pi标志使用当前主题文字色",
        )
        saveShot(shot)
    }

    // ------------------------------------------------------------------
    // 3) S2 输入三态与草稿
    // ------------------------------------------------------------------

    @Test
    fun s2_composer_tristate_draftAndGeometry() {
        connectFakeGateway()
        setContentFromRealRoot()
        goHomeEmptyState()
        val px = pxPerDp

        // 空闲：胶囊态只有 添加图片 + 输入 + 发送；模型入口不存在（原型 idle 隐藏 .model-btn）
        assertEquals(
            "空闲胶囊不应出现模型入口",
            0,
            compose.onAllNodesWithTag("finecat.modelChip").fetchSemanticsNodes().size,
        )
        val attachIdle = compose.onNodeWithContentDescription("添加图片")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "空闲胶囊左缘应距屏 40dp（.dock padding s10；48dp 触控槽左 − 6dp 卡内边距）",
            40f * px,
            attachIdle.left - 6f * px,
            2f * px,
        )
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        compose.onAllNodesWithText("描述你的任务", useUnmergedTree = true)[0].assertIsDisplayed()

        // 聚焦：输入独占首行、控制行在下，原型 styles.css:1158 的行间距为2dp。
        // 模型胶囊居中在控制行内，不能用它的top代替整行top。
        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        val inputRow = compose.onNodeWithTag("fc.composer.inputRow")
            .fetchSemanticsNode().boundsInRoot
        val chipNode = compose.onNodeWithTag("finecat.modelChip").fetchSemanticsNode()
        val chip = chipNode.boundsInRoot
        val controlsRow = checkNotNull(chipNode.layoutInfo.parentInfo).coordinates.boundsInRoot()
        assertTrue(
            "展开后模型入口应在输入下方（chip.top=${chip.top} inputRow.bottom=${inputRow.bottom}）",
            chip.top >= inputRow.bottom - 1f,
        )
        assertEquals(
            "输入行与控制行间距必须符合原型2dp",
            2f * px,
            controlsRow.top - inputRow.bottom,
            2f * px,
        )
        assertEquals("控制行必须是承载32dp发送槽的整行", 32f * px, controlsRow.height, 2f * px)
        val attachExp = compose.onNodeWithContentDescription("添加图片")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "展开卡片左缘应距屏 16dp（.dock padding s4；展开态卡片内缩 8dp，styles.css:1159）",
            16f * px,
            attachExp.left - 8f * px,
            2f * px,
        )

        // 草稿写入门面（单一真源）；失焦后展开保持 + 草稿不丢（真实状态驱动）
        compose.onNodeWithTag("fc.composer.input").performTextInput("F03 草稿保持")
        compose.waitForIdle()
        assertEquals("草稿必须写入 holder.messageDraft", "F03 草稿保持", holder.messageDraft)
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * 0.2f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelChip").assertIsDisplayed()
        assertEquals("失焦后草稿不得丢失", "F03 草稿保持", holder.messageDraft)
        val inputAfter = compose.onNodeWithTag("fc.composer.input")
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)
            ?.text
        assertEquals("输入框回显必须等于草稿", "F03 草稿保持", inputAfter)
        saveShot("f03-s2-composer.png")
    }

    @Test
    fun s2_composer_dark_expandedCard() {
        connectFakeGateway()
        setContentFromRealRoot()
        compose.runOnIdle {
            holder.updateAppearanceMode("dark")
            holder.prepareNewSession()
        }
        runBlocking {
            waitUntil { holder.snapshot.selectedSessionId == null }
            waitUntil { holder.appearanceMode == "dark" }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelChip").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加图片").assertIsDisplayed()

        // 卡面像素 = 暗色 surface #17171A（防假绿：外层主题覆盖陷阱见 HomeParity 注释）
        val chip = compose.onNodeWithTag("finecat.modelChip").fetchSemanticsNode().boundsInRoot
        val bmp = rootBitmap()
        assertPixelNear(
            bmp,
            (chip.left - 30f).roundToInt().coerceAtLeast(1),
            chip.center.y.roundToInt(),
            0xFF17171AL,
            "暗色输入卡面（surface token）",
        )
        saveShot("f03-s2-composer-dark.png")
    }

    // ------------------------------------------------------------------
    // 4) S3 选图条
    // ------------------------------------------------------------------

    @Test
    fun s3_shots_tray_insideCard_selectionCancelAndCanSend() {
        val uris = ensurePhotoFiles(3)
        connectFakeGateway(FakeRecentPhotos(uris))
        setContentFromRealRoot()
        goHomeEmptyState()
        val px = pxPerDp

        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        // 权限弹窗链路测试进程无法驱动（HomeParity 已登记）：托盘经门面真实状态打开
        compose.runOnIdle { holder.togglePhotosTray() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("fc.shotStrip", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        // 选图条在输入卡内部：条底到输入行容器顶 = margin 3dp；卡左缘 16dp；格子 56dp
        val strip = compose.onNodeWithTag("fc.shotStrip", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val inputRow = compose.onNodeWithTag("fc.composer.inputRow")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "选图条底到输入行顶应为 3dp（.shots margin-bottom；实测 strip=${strip.top}..${strip.bottom} " +
                "inputRow.top=${inputRow.top}，差=${inputRow.top - strip.bottom}px）",
            abs(inputRow.top - strip.bottom - 3f * px) <= 2f * px,
        )
        val attach = compose.onNodeWithContentDescription("添加图片")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "带选图条的卡片左缘应距屏 16dp（展开态卡片内缩 8dp）",
            16f * px,
            attach.left - 8f * px,
            2f * px,
        )
        val cell1 = compose.onNodeWithContentDescription("最近照片 1").fetchSemanticsNode().boundsInRoot
        assertEquals("选图格应为 56dp 见方", 56f * px, cell1.width, 0.5f * px)
        assertEquals("选图格应为 56dp 见方", 56f * px, cell1.height, 0.5f * px)
        compose.onNodeWithContentDescription("打开相册").assertIsDisplayed()

        // 多选 = 门面 preparedUris（集合断言与完成顺序无关）；再点取消
        compose.onNodeWithContentDescription("最近照片 1").performClick()
        runBlocking { waitUntil { holder.preparedUris.toSet() == setOf(uris[0].toString()) } }
        compose.onNodeWithContentDescription("最近照片 2").performClick()
        runBlocking {
            waitUntil { holder.preparedUris.toSet() == setOf(uris[0].toString(), uris[1].toString()) }
        }
        compose.onNodeWithContentDescription("最近照片 1").performClick()
        runBlocking { waitUntil { holder.preparedUris.toSet() == setOf(uris[1].toString()) } }

        // 纯图可发送：草稿空 + 已备图 + 已连接（真实 canSend 判据）
        compose.runOnIdle { holder.messageDraft = "" }
        compose.waitForIdle()
        assertTrue("纯图（草稿空 + 1 张已备）必须可发送", holder.canSend)

        // 关闭托盘：草稿与已备图保留、卡片保持展开（有草稿）
        compose.runOnIdle { holder.closePhotosTray() }
        compose.waitForIdle()
        assertEquals(
            "关闭托盘后选图条应消失",
            0,
            compose.onAllNodesWithTag("fc.shotStrip", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("finecat.modelChip").assertIsDisplayed()
        assertEquals("关闭托盘不得清已备图片", 1, holder.preparedImages.size)
        saveShot("f03-s3-tray.png")
    }

    @Test
    fun s3_shots_limitAndPrepareFailure_realFeedback() {
        val good = ensurePhotoFiles(5)
        val bad: List<Uri> = listOf(Uri.parse("content://nonexistent/f03/missing.png"))
        connectFakeGateway(FakeRecentPhotos(good + bad))
        setContentFromRealRoot()
        goHomeEmptyState()

        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        compose.runOnIdle { holder.togglePhotosTray() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("最近照片 6").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        // 上限 4：第 5 张被拒（真实计数判据），全局错误条出现且已备仍 4
        for (i in 1..4) {
            compose.onNodeWithContentDescription("最近照片 $i").performClick()
        }
        runBlocking { waitUntil { holder.preparedUris.size == 4 } }
        compose.onNodeWithContentDescription("最近照片 5").performClick()
        runBlocking { waitUntil { holder.platformError != null } }
        assertEquals(
            "第 5 张必须命中数量上限错误码",
            "image-selection-limit-exceeded",
            holder.platformError,
        )
        assertEquals("超限被拒后已备图片必须仍为 4", 4, holder.preparedUris.size)
        compose.onNodeWithText("Pi Mobile").assertIsDisplayed()
        compose.onNodeWithText("image-selection-limit-exceeded").assertIsDisplayed()
        saveShot("f03-s3-limit.png")
        compose.onNodeWithText("好").performClick()
        runBlocking { waitUntil { holder.platformError == null } }

        // 失败反馈：不可读 uri → 预处理失败错误码（真实状态驱动，非静态文案）
        compose.onNodeWithContentDescription("最近照片 6").performClick()
        runBlocking { waitUntil { holder.platformError != null } }
        assertEquals(
            "不可读图片必须报预处理失败",
            "image-preprocess-failed",
            holder.platformError,
        )
        assertEquals("失败图片不得进入已备集合", 4, holder.preparedUris.size)
        compose.onNodeWithText("好").performClick()
        runBlocking { waitUntil { holder.platformError == null } }
    }

    @Test
    fun s3_shots_endCellReachableByRealSwipe_withRightEdgeFade() {
        // 展示用 10 格：不需要真实解码（不点击），content uri 即可
        val uris = (1..10).map { Uri.parse("content://f03/photos/p$it") }
        connectFakeGateway(FakeRecentPhotos(uris))
        setContentFromRealRoot()
        goHomeEmptyState()

        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        compose.runOnIdle { holder.togglePhotosTray() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("最近照片 10").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        val strip = compose.onNodeWithTag("fc.shotStrip", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val before = rootBitmap()

        // 真实横滑手势滚到末格（有界重试：fling 距离不定），内容像素必须变化。
        // 末态判定收紧为「更多」右缘贴齐选图条右缘（滚动到底的精确特征）
        var moreRight = Float.MAX_VALUE
        var after = before
        var attempts = 0
        while (attempts < 6 && (moreRight > strip.right + 1f || moreRight < strip.right - 2f * pxPerDp)) {
            compose.onNodeWithTag("fc.shotStrip", useUnmergedTree = true)
                .performTouchInput { swipeLeft() }
            compose.waitForIdle()
            after = rootBitmap()
            moreRight = compose.onNodeWithContentDescription("打开相册")
                .fetchSemanticsNode().boundsInRoot.right
            attempts++
        }
        assertTrue(
            "横滑 $attempts 次后「更多」末格仍未贴齐右缘（moreRight=$moreRight strip=${strip.right}）",
            moreRight in strip.right - 2f * pxPerDp..strip.right + 1f,
        )
        assertTrue(
            "横滑前后选图条内容像素必须变化（真实滚动，非语义断言）",
            stripBitmapDiffers(before, after, strip),
        )

        // 右缘 18dp 渐隐线索（原型 .shots mask）：滚到底后末格贴右缘、被渐隐盖住。
        // 证据行配对（复审 A）：同一扫描行上「深缘列(右缘-2px)≈白 AND 渐隐深度之外
        // (−60..−140px) 有格子内容」才算证据——空卡面行深缘天然白、控制行深缘有色，
        // 都不会误判；方格纸底纹合成值≈245.5 不触发 <245 的内容阈值。
        // 垂直窗口上探一格高：装包后首跑曾实测绘制内容相对语义 bounds 稳定上移约一格
        // 因此固定扫描行可能扫空。采集偶发取到动画中帧：双次重试。
        val deepX = strip.right.roundToInt() - 2
        val syStart = (strip.top - 180f).roundToInt().coerceAtLeast(0)
        val syEnd = strip.bottom.roundToInt()
        fun isContent(c: Int): Boolean =
            android.graphics.Color.red(c) < 245 ||
                android.graphics.Color.green(c) < 245 ||
                android.graphics.Color.blue(c) < 245
        fun isWhite(c: Int): Boolean =
            android.graphics.Color.red(c) >= 248 &&
                android.graphics.Color.green(c) >= 248 &&
                android.graphics.Color.blue(c) >= 248
        var failure: String? = null
        var captured: Bitmap = after
        var fadeVerified = false
        repeat(2) { tryIdx ->
            val bmp = if (tryIdx == 0) {
                after
            } else {
                compose.waitForIdle()
                rootBitmap()
            }
            var evidenceRow = -1
            var sy = syStart
            while (sy <= syEnd) {
                if (isWhite(bmp.getPixel(deepX, sy))) {
                    var sx = strip.right.roundToInt() - 140
                    while (sx <= strip.right.roundToInt() - 60) {
                        if (isContent(bmp.getPixel(sx, sy))) {
                            evidenceRow = sy
                            break
                        }
                        sx += 3
                    }
                    if (evidenceRow >= 0) break
                }
                sy += 2
            }
            if (evidenceRow >= 0) {
                fadeVerified = true
                captured = bmp
            } else {
                failure = "try=$tryIdx 未找到「深缘白+渐隐深度外有内容」的证据行 " +
                    "(窗口 y=$syStart..$syEnd deepX=$deepX)"
            }
        }
        assertTrue("渐隐对照未取得证据行：$failure", fadeVerified)
        saveShotNamed(captured, "f03-s3-swipe.png")
    }

    /** 指定位图落盘（S3-SWIPE 双次采集后用胜出帧留证）。 */
    private fun saveShotNamed(bmp: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        File(dir, name).outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** 选图条区域内任一像素通道差 >8 即视为内容变化。 */
    private fun stripBitmapDiffers(a: Bitmap, b: Bitmap, strip: Rect): Boolean {
        val x0 = strip.left.roundToInt().coerceIn(0, a.width - 1)
        val x1 = strip.right.roundToInt().coerceIn(0, a.width - 1)
        val y0 = strip.top.roundToInt().coerceIn(0, a.height - 1)
        val y1 = strip.bottom.roundToInt().coerceIn(0, a.height - 1)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val ca = a.getPixel(x, y)
                val cb = b.getPixel(x, y)
                if (abs(android.graphics.Color.red(ca) - android.graphics.Color.red(cb)) > 8 ||
                    abs(android.graphics.Color.green(ca) - android.graphics.Color.green(cb)) > 8 ||
                    abs(android.graphics.Color.blue(ca) - android.graphics.Color.blue(cb)) > 8
                ) {
                    return true
                }
                x += 4
            }
            y += 4
        }
        return false
    }

    // ------------------------------------------------------------------
    // 5) 模型选择（S1-MODEL-SELECT / S4-MODEL-SHEET）
    // ------------------------------------------------------------------

    @Test
    fun modelList_sameIdInDifferentProviders_keepsSelectionIdentity() {
        connectFakeGateway()
        setContentFromRealRoot()
        goHomeEmptyState()
        transport.receive("""{"kind":"models","groups":[
            {"provider":"alpha","models":[{"id":"shared-model","name":"Same model","contextWindow":500000,"maxTokens":8192,"reasoning":true,"thinkingLevels":["low","medium","high"]}]},
            {"provider":"beta","models":[{"id":"shared-model","name":"Same model","contextWindow":500000,"maxTokens":8192,"reasoning":true,"thinkingLevels":["low","medium","high"]}]}]}""")
        runBlocking { waitUntil { holder.snapshot.modelCatalog?.groups?.size == 2 } }
        openModelSheetViaChip()
        for (provider in listOf("alpha", "beta")) {
            compose.onNodeWithTag("finecat.modelProvider.$provider.shared-model", useUnmergedTree = true)
                .assertTextEquals(provider)
            compose.onNodeWithTag("finecat.model.$provider.shared-model").performClick()
            compose.waitForIdle()
            assertEquals(provider, holder.pendingModelSelection?.provider)
            assertEquals("shared-model", holder.pendingModelSelection?.modelId)
            compose.onNodeWithTag("finecat.model.$provider.shared-model").assertIsSelected()
            val other = if (provider == "alpha") "beta" else "alpha"
            compose.onNodeWithTag("finecat.model.$other.shared-model").assertIsNotSelected()
        }
        compose.onNodeWithText("500K").assertDoesNotExist()
        compose.onNodeWithTag("finecat.manualCompact").assertDoesNotExist()
        compose.onNodeWithTag("finecat.thinking.medium").performClick()
        compose.waitForIdle()
        assertEquals("medium", holder.pendingModelSelection?.thinkingLevel)
        compose.onNodeWithTag("finecat.thinking.medium").assertIsSelected()
        compose.onNodeWithTag("finecat.thinking.high").assertIsNotSelected()
        compose.onNodeWithTag("finecat.modelSheet").assertExists()
    }

    @Test
    fun s1_modelSelect_pickModelAndWord_sheetStays_realStates() {
        connectFakeGateway()
        setContentFromRealRoot()
        goHomeEmptyState()
        injectModelCatalog()

        openModelSheetViaChip()

        // 主行模型名、副行仅Provider；显示名不同于ID时也不重复展示ID。
        compose.onNodeWithTag("finecat.modelProvider.$FIXTURE_PROVIDER.$MODEL_MAIN_ID", useUnmergedTree = true).assertTextEquals(FIXTURE_PROVIDER)
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("finecat.modelLabel.$FIXTURE_PROVIDER.$MODEL_MAIN_ID", useUnmergedTree = true).assertTextEquals(MODEL_MAIN_NAME)
        compose.onNodeWithTag("finecat.modelId.$FIXTURE_PROVIDER.$MODEL_MAIN_ID", useUnmergedTree = true).assertDoesNotExist()
        // 模型弹层只选择模型/思考，不再重复目录或压缩入口。
        assertEquals(0, compose.onAllNodesWithTag("finecat.newSessionDirectory", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("finecat.manualCompact", useUnmergedTree = true).assertDoesNotExist()

        // 选模型：sheet 保持打开；预选状态=真实 pendingModelSelection；右侧勾选中。
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo()
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelSheet", useUnmergedTree = true).assertExists()
        assertEquals("点模型必须写真实预选 provider", FIXTURE_PROVIDER, holder.pendingModelSelection?.provider)
        assertEquals("点模型必须写真实预选 modelId", MODEL_MAIN_ID, holder.pendingModelSelection?.modelId)
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").assertIsSelected()
        val labelBounds = compose.onNodeWithTag("finecat.modelLabel.$FIXTURE_PROVIDER.$MODEL_MAIN_ID", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val checkBounds = compose.onNodeWithTag("finecat.modelCheck.$FIXTURE_PROVIDER.$MODEL_MAIN_ID", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("选中勾位于模型名称右侧且不重叠", checkBounds.left >= labelBounds.right)

        // 固定底栏的档位词（medium显示mid，回传原始枚举）
        compose.onNodeWithTag("finecat.thinking.high").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("finecat.thinking.high").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelSheet", useUnmergedTree = true).assertExists()
        assertEquals(
            "点档位词必须写原始枚举值 high",
            "high",
            holder.pendingModelSelection?.thinkingLevel,
        )
        // 面板关闭后重新聚焦，原输入框入口必须保留所选模型/档位。
        compose.onNodeWithTag("finecat.modelSheetClose").performClick()
        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        assertTrue(
            "胶囊档位应显示有效值 high，实际=${chipTexts()}",
            chipTexts().any { it == "$MODEL_MAIN_NAME · high" },
        )
        openModelSheetViaChip()

        // 超长模型名：胶囊保持既有200dp最大宽及单行滚动，且保持单行
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_LONG_ID").performScrollTo()
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_LONG_ID").performClick()
        compose.waitForIdle()
        assertEquals("长名模型 ID 必须严格等于所选行", MODEL_LONG_ID, holder.pendingModelSelection?.modelId)
        compose.onNodeWithTag("finecat.modelSheetClose").performClick()
        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        val chipBounds = compose.onNodeWithTag("finecat.modelChip").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "胶囊宽度保持既有200dp上限（实际 ${chipBounds.width}px）",
            chipBounds.width <= 202f * pxPerDp,
        )
        // 高度 = 48dp 触控盒（已登记触控适配）；单行省略由宽度上限 + 长名证明
        assertTrue(
            "胶囊高度应为 48dp 触控盒（实际 ${chipBounds.height}px）",
            chipBounds.height <= 50f * pxPerDp,
        )
        openModelSheetViaChip()
        saveModelPanelShot("f03-s1-modelselect.png")

        // 只有真实可用档位出现：无档位声明模型（thinkingLevels=[]）行无任何档位词
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_NO_THINK_ID").performScrollTo()
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_NO_THINK_ID").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelSheet", useUnmergedTree = true).assertExists()
        assertEquals("模型 ID 必须严格等于所选行", MODEL_NO_THINK_ID, holder.pendingModelSelection?.modelId)
        assertEquals(
            "无档位声明的模型不得出现档位词 LOW",
            0,
            compose.onAllNodesWithTag("finecat.thinking.low").fetchSemanticsNodes().size,
        )
        assertEquals(
            "无档位声明的模型不得出现档位词 MEDIUM",
            0,
            compose.onAllNodesWithTag("finecat.thinking.medium").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun s4_modelSheet_inSession_setModelFrameAndNameRows() {
        connectFakeGateway()
        setContentFromRealRoot()
        injectModelCatalog()
        selectFixtureSession()
        openModelSheetViaChip()

        // 会话内模型面板也只选择模型/思考，不再包含压缩或目录。
        compose.onNodeWithTag("finecat.manualCompact", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(
            "会话内模型 sheet 不应有「新会话目录」行",
            0,
            compose.onAllNodesWithTag("finecat.newSessionDirectory").fetchSemanticsNodes().size,
        )

        // 可读名称单行，Provider/ID作为第二行辅文；长名不撑高普通56dp行。
        val shortRow = compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.shorty").performScrollTo().fetchSemanticsNode().boundsInRoot
        val longRow = compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_LONG_ID").performScrollTo()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "普通模型行应紧凑且至少56dp（${shortRow.height}px）",
            shortRow.height >= 56f * pxPerDp - 1f && shortRow.height <= 60f * pxPerDp,
        )
        assertTrue(
            "超长名行必须单行省略、与短名行同高（short=${shortRow.height} long=${longRow.height}）",
            abs(longRow.height - shortRow.height) <= 2f * pxPerDp,
        )

        // 会话内选模型 → 真实 set-model 帧发出（含 provider/modelId）；sheet 保持。
        // payloadsOfType 返回 JsonObject：字段断言取 jsonPrimitive，不做字符串 contains
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo()
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelSheet", useUnmergedTree = true).assertExists()
        runBlocking { waitUntil { transport.payloadsOfType("set-model").isNotEmpty() } }
        val frame = transport.payloadsOfType("set-model").last()
        assertEquals(
            "set-model 帧的 sessionId 必须等于当前会话",
            "s1",
            frame["sessionId"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "set-model 帧的 provider 必须等于目标分组",
            FIXTURE_PROVIDER,
            frame["provider"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "set-model 帧的 modelId 必须等于目标模型",
            MODEL_MAIN_ID,
            frame["modelId"]?.jsonPrimitive?.content,
        )
        saveModelPanelShot("f03-s4-modelsheet.png")
    }

    @Test
    fun s4_modelSheet_touchTargets_min48_nonOverlap() {
        connectFakeGateway()
        setContentFromRealRoot()
        injectModelCatalog()
        selectFixtureSession()
        openModelSheetViaChip()
        val minTouchPx = 48f * pxPerDp - 1f
        for (name in listOf(MODEL_MAIN_ID, "shorty", MODEL_NO_THINK_ID, MODEL_LONG_ID)) {
            val row = compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$name").performScrollTo().fetchSemanticsNode().boundsInRoot
            assertTrue("$name 触控高应≥48dp", row.height >= minTouchPx)
        }
        val (low, medium, high) = makeGlmCurrentAndFetchWords()
        for (button in listOf(low, medium, high)) {
            assertTrue("档位按钮实际宽高应≥48dp", button.width >= minTouchPx && button.height >= minTouchPx)
        }
        assertTrue("相邻档位触控不得重叠", low.right <= medium.left && medium.right <= high.left)
        val footer0 = compose.onNodeWithTag("finecat.modelFooter").fetchSemanticsNode().boundsInRoot
        val high0 = compose.onNodeWithTag("finecat.thinking.high").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_LONG_ID").performScrollTo().assertIsDisplayed()
        assertEquals("列表滚动不应移动底栏", footer0,
            compose.onNodeWithTag("finecat.modelFooter").fetchSemanticsNode().boundsInRoot)
        assertEquals("列表滚动不应移动思考档位", high0,
            compose.onNodeWithTag("finecat.thinking.high").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("finecat.manualCompact").assertDoesNotExist()
        saveModelPanelShot("f03-touch48.png")
        for (word in listOf("MEDIUM", "LOW", "HIGH")) {
            val level = word.lowercase()
            compose.onNodeWithTag("finecat.thinking.$level").performScrollTo().performClick()
            compose.waitForIdle()
            awaitFrame(level)
            assertFrameFields(level)
            transport.receive(
                """{"kind":"model-set","sessionId":"s1","model":{"provider":"$FIXTURE_PROVIDER",
                "modelId":"$MODEL_MAIN_ID"},"thinkingLevel":"$level"}"""
            )
            runBlocking { waitUntil { holder.snapshot.sessionStates["s1"]?.thinkingLevel == level } }
            compose.waitForIdle()
        }
        // 当前模型行不应以空档位重发选择，避免丢失刚选的 HIGH。
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo()
        val frames = transport.payloadsOfType("set-model").size
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(frames, transport.payloadsOfType("set-model").size)
        assertEquals("high", holder.snapshot.sessionStates["s1"]?.thinkingLevel)
    }

    @Test
    fun s4_modelSheet_touchRegion_lowSide_boundaryProbes() = probeThinkingEdge("low", left = true)

    @Test
    fun s4_modelSheet_touchRegion_highSide_boundaryProbes() = probeThinkingEdge("high", left = false)

    private fun probeThinkingEdge(level: String, left: Boolean) {
        connectFakeGateway()
        setContentFromRealRoot()
        injectModelCatalog()
        selectFixtureSession()
        openModelSheetViaChip()
        makeGlmCurrentAndFetchWords()
        val button = compose.onNodeWithTag("finecat.thinking.$level").fetchSemanticsNode().boundsInRoot
        val edge = if (left) button.left else button.right
        val outside = edge + (if (left) -2 else 2) * pxPerDp
        val inside = edge + (if (left) 2 else -2) * pxPerDp
        val before = transport.payloadsOfType("set-model").size
        tapModelPanel(outside, button.center.y)
        assertEquals("明确布局盒外不应选档位", before, transport.payloadsOfType("set-model").size)
        tapModelPanel(inside, button.center.y)
        awaitFrame(level)
        assertEquals("布局盒内一次只投递一帧", before + 1, transport.payloadsOfType("set-model").size)
        assertFrameFields(level)
    }

    @Test
    fun modelSheet_emptyCatalog_noFabricatedModels() {
        connectFakeGateway()
        setContentFromRealRoot()
        goHomeEmptyState()
        // 不注入目录：打开 sheet 触发真实 refresh 请求，目录为空 → 空态
        openModelSheetViaChip()
        compose.onNodeWithText("暂无可用模型", substring = true).assertIsDisplayed()
        assertEquals(
            "空目录不得出现任何模型行",
            0,
            compose.onAllNodesWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").fetchSemanticsNodes().size,
        )
        assertEquals(
            "空目录不得出现档位词",
            0,
            compose.onAllNodesWithTag("finecat.thinking.high").fetchSemanticsNodes().size,
        )
        assertEquals("空目录不得写预选状态", null, holder.pendingModelSelection)
        assertTrue(
            "打开空目录 sheet 必须真实发出 models 请求",
            transport.payloadsOfType("models").isNotEmpty(),
        )
        // 空目录也不包含额外的压缩或思考底栏。
        compose.onNodeWithTag("finecat.manualCompact", useUnmergedTree = true).assertDoesNotExist()
        saveShot("f03-modelsheet-empty.png")
    }

    @Test
    fun modelSheet_emptyDraft_focusLossDoesNotClose_fullWidthAndDismiss() {
        connectFakeGateway()
        setContentFromRealRoot()
        goHomeEmptyState()
        injectModelCatalog()
        openModelSheetViaChip()
        compose.onNodeWithTag("fc.composer.input").assertIsNotFocused()
        assertEquals("打开面板不得改空草稿", "", holder.messageDraft)
        compose.onNodeWithText("选择模型").assertIsDisplayed()
        // 实际IME收起异步于Compose测试时钟；等最终贴底几何，再断言布局。
        compose.waitUntil(timeoutMillis = 5_000) {
            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val cardBounds = compose.onNodeWithTag("finecat.sheetCard").fetchSemanticsNode().boundsInRoot
            abs(rootBounds.bottom - cardBounds.bottom) <= 2f * pxPerDp
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("finecat.sheetCard").fetchSemanticsNode().boundsInRoot
        assertEquals("底部面板覆盖根容器全宽", root.width, card.width, 2f * pxPerDp)
        assertEquals("面板背景贴根容器底部", root.bottom, card.bottom, 2f * pxPerDp)
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelSheet").assertExists()
        assertEquals(MODEL_MAIN_ID, holder.pendingModelSelection?.modelId)
        compose.onNodeWithTag("finecat.modelSheetClose").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelSheet").assertDoesNotExist()
        assertEquals("选择和关闭面板不得改草稿", "", holder.messageDraft)
    }

    @Test
    fun modelSheet_initialCurrentVisible_laterSelectionKeepsUserScroll() {
        connectFakeGateway()
        setContentFromRealRoot()
        goHomeEmptyState()
        val models = (0..39).joinToString(",") {
            """{"id":"model-$it","name":"模型 $it","thinkingLevels":[]}"""
        }
        transport.receive("""{"kind":"models","groups":[{"provider":"many","models":[$models]}]}""")
        runBlocking { waitUntil { holder.snapshot.modelCatalog?.groups?.singleOrNull()?.models?.size == 40 } }
        compose.runOnIdle { holder.selectModel("many", "model-39", null) }
        openModelSheetViaChip()
        compose.waitUntil(timeoutMillis = 5_000) {
            val list = compose.onNodeWithTag("finecat.modelList").fetchSemanticsNode().boundsInRoot
            val row = compose.onNodeWithTag("finecat.model.many.model-39").fetchSemanticsNode().boundsInRoot
            row.height >= 55f * pxPerDp && row.top >= list.top - 1f && row.bottom <= list.bottom + 1f
        }
        compose.onNodeWithTag("finecat.model.many.model-39").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("finecat.model.many.model-20").performScrollTo()
        val before = compose.onNodeWithTag("finecat.model.many.model-20").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("finecat.model.many.model-20").performClick()
        compose.waitForIdle()
        assertEquals("model-20", holder.pendingModelSelection?.modelId)
        val after = compose.onNodeWithTag("finecat.model.many.model-20").fetchSemanticsNode().boundsInRoot
        assertEquals("后续选择不抢用户滚动位置", before.top, after.top, 2f * pxPerDp)
        compose.onNodeWithTag("finecat.modelSheet").assertExists()
    }

    // ------------------------------------------------------------------
    // 6) 跳最新锚位
    // ------------------------------------------------------------------

    @Test
    fun jumpButton_anchor162dpAboveBottom_disappearsAtBottom() {
        connectFakeGateway()
        setContentFromRealRoot()
        selectFixtureSession()
        transport.receive(HISTORY_MANY_FRAME)
        runBlocking { waitUntil { holder.snapshot.conversation.size >= 12 } }
        compose.waitForIdle()

        // 初始贴底无跳最新；上滑（真实手势）后出现，锚位=视觉圆底边距视口底 162dp
        assertEquals(
            "贴底时不应出现跳最新",
            0,
            compose.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("finecat.list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isNotEmpty()
        }
        val rootH = compose.onRoot().fetchSemanticsNode().size.height
        val jump = compose.onNodeWithTag("finecat.jump").fetchSemanticsNode().boundsInRoot
        val visualBottom = jump.bottom - 4f * pxPerDp // 48dp 触控盒 − 40dp 视觉圆的下缘差
        assertEquals(
            "跳最新视觉底边应距视口底 162dp（原型 .jump bottom:162px）",
            rootH - 162f * pxPerDp,
            visualBottom,
            2f * pxPerDp,
        )
        val composer = compose.onNodeWithTag("finecat.composer").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "跳最新不得遮挡输入区（visualBottom=$visualBottom composer.top=${composer.top}）",
            visualBottom <= composer.top,
        )
        saveShot("f03-jump.png")

        // 点按回底后自动消失（原型：滚到底隐藏）
        compose.onNodeWithTag("finecat.jump").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isEmpty()
        }
    }

    // ------------------------------------------------------------------
    // 7) 矩阵（360dp / fs1.3 / 亮暗）
    // ------------------------------------------------------------------

    @Test
    fun matrix_360dp_dark_trayAndModelSheet_usable() {
        shell("wm density 520")
        Thread.sleep(1_000)
        try {
            val uris = ensurePhotoFiles(3)
            connectFakeGateway(FakeRecentPhotos(uris))
            setContentFromRealRoot()
            compose.runOnIdle {
                holder.updateAppearanceMode("dark")
                holder.prepareNewSession()
            }
            runBlocking {
                waitUntil { holder.snapshot.selectedSessionId == null }
                waitUntil { holder.appearanceMode == "dark" }
            }
            compose.waitForIdle()
            injectModelCatalog()

            // 暗色下托盘可用：3 格 + 更多全可见
            compose.runOnIdle { holder.togglePhotosTray() }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithContentDescription("打开相册").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("最近照片 3").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开相册").assertIsDisplayed()

            // 关托盘 → 聚焦 → 模型 sheet 可用、档位词可达
            compose.runOnIdle { holder.closePhotosTray() }
            compose.waitForIdle()
            compose.onNodeWithTag("fc.composer.input").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("finecat.modelChip").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("finecat.modelSheet", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("finecat.thinking.high").performScrollTo().assertIsDisplayed()
            // 360dp 暗色：内部视觉盒约28dp，外层显式48dp触控不重叠（
            // 几何与行为证明见 s4_modelSheet_touchTargets_min48_nonOverlap）
            val highB = compose.onNodeWithTag("finecat.thinkingVisual.high", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "360dp 暗色档位词视觉盒应保持紧凑（${highB.height}px）",
                highB.height >= 28f * pxPerDp - 1f && highB.height <= 30f * pxPerDp + 1f,
            )
            saveShot("f03-m-360-dark.png")
        } finally {
            restoreDeviceConfig()
        }
    }

    @Test
    fun matrix_360dp_fs13_light_homeComposerSheet_usable() {
        shell("wm density 520")
        shell("settings put system font_scale 1.3")
        Thread.sleep(1_000)
        try {
            connectFakeGateway()
            setContentFromRealRoot()
            goHomeEmptyState()
            injectModelCatalog()

            // 首页结构在大字体窄屏下成立
            compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
            compose.onAllNodesWithText("探索未至之境", useUnmergedTree = true)[0].assertIsDisplayed()

            // 聚焦 → 草稿 → 模型 sheet：关键操作可达
            compose.onNodeWithTag("fc.composer.input").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("fc.composer.input").performTextInput("窄屏大字草稿")
            compose.waitForIdle()
            assertEquals("窄屏大字下草稿必须写入门面", "窄屏大字草稿", holder.messageDraft)
            compose.onNodeWithTag("finecat.modelChip").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("finecat.modelSheet", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("finecat.thinking.high").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("大字下档位词必须可点且写真实枚举", "high", holder.pendingModelSelection?.thinkingLevel)
            // 360dp+fs1.3：内部视觉盒保持紧凑，大字允许自然增长。
            val highB = compose.onNodeWithTag("finecat.thinkingVisual.high", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "360dp+fs1.3 档位词视觉盒应保持紧凑（${highB.height}px）",
                highB.height >= 28f * pxPerDp - 1f && highB.height <= 36f * pxPerDp + 1f,
            )
            saveShot("f03-m-360fs13-light.png")
        } finally {
            restoreDeviceConfig()
        }
    }

    // ------------------------------------------------------------------
    // fixtures 与辅助
    // ------------------------------------------------------------------

    private fun scrollToSheetTag(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    private fun saveModelPanelShot(name: String) {
        saveShotNamed(compose.onNodeWithTag("finecat.modelSheet").captureToImage().asAndroidBitmap(), name)
    }

    /** 打开目录后把 GLM-5.3 定为当前模型（点行发 set-model，注入 model-set 应答帧），
     *  返回三个档位词的可点击 bounds（LOW, MEDIUM, HIGH）。 */
    private fun makeGlmCurrentAndFetchWords(): Triple<Rect, Rect, Rect> {
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performScrollTo()
        compose.onNodeWithTag("finecat.model.$FIXTURE_PROVIDER.$MODEL_MAIN_ID").performClick()
        compose.waitForIdle()
        runBlocking { waitUntil { transport.payloadsOfType("set-model").isNotEmpty() } }
        transport.receive(
            """{"kind":"model-set","sessionId":"s1","model":{"provider":"$FIXTURE_PROVIDER",""" +
                """"modelId":"$MODEL_MAIN_ID"},"thinkingLevel":null}"""
        )
        runBlocking {
            waitUntil { holder.snapshot.sessionStates["s1"]?.model?.modelId == MODEL_MAIN_ID }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.thinking.high").performScrollTo().assertIsDisplayed()
        fun wb(word: String) = compose.onNodeWithTag("finecat.thinking.${word.lowercase()}").fetchSemanticsNode().boundsInRoot
        return Triple(wb("LOW"), wb("MEDIUM"), wb("HIGH"))
    }

    private fun lastFrameThinking(): String? = transport.payloadsOfType("set-model").last()
        .get("thinkingLevel")?.jsonPrimitive?.content

    private fun assertFrameFields(level: String?) {
        val frame = transport.payloadsOfType("set-model").last()
        assertEquals(
            "set-model 帧 provider 必须等于当前分组",
            FIXTURE_PROVIDER,
            frame.get("provider")?.jsonPrimitive?.content,
        )
        assertEquals(
            "set-model 帧 modelId 必须等于当前模型",
            MODEL_MAIN_ID,
            frame.get("modelId")?.jsonPrimitive?.content,
        )
        assertEquals("set-model 帧 thinkingLevel 必须等于所点档位", level, lastFrameThinking())
    }

    private fun awaitFrame(level: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (lastFrameThinking() != level && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(
            "点击后应收到 thinkingLevel=$level 的 set-model 帧，" +
                "实际=${lastFrameThinking()} 帧数=${transport.payloadsOfType("set-model").size}" +
                " 类型=${transport.sentTypes}",
            lastFrameThinking() == level,
        )
    }

    private fun tapModelPanel(x: Float, y: Float) {
        val bounds = compose.onNodeWithTag("finecat.modelSheet").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("finecat.modelSheet").performTouchInput { click(Offset(x - bounds.left, y - bounds.top)) }
        compose.waitForIdle()
    }

    /** 测试进程内注入/读取系统配置（矩阵用例专用，finally 恢复，照 F01 同口径）。 */
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .bufferedReader().use { it.readText() }
    }

    private fun restoreDeviceConfig() {
        shell("wm density 480")
        shell("settings put system font_scale 1.0")
        Thread.sleep(1_000)
        val density = shell("wm density")
        val fontScale = shell("settings get system font_scale")
        assertTrue(
            "矩阵步骤结束必须恢复设备配置：density=[$density] fontScale=[$fontScale]",
            density.contains("Override density: 480") && fontScale.trim() == "1.0",
        )
    }

    private companion object {
        const val FIXTURE_PROVIDER = "智谱"
        const val MODEL_MAIN_ID = "glm-5"
        const val MODEL_MAIN_NAME = "GLM-5.3"
        const val MODEL_SHORT_NAME = "g"
        const val MODEL_NO_THINK_ID = "plain"
        const val MODEL_NO_THINK_NAME = "Plain-Model"
        const val MODEL_LONG_ID = "long-one-with-a-very-long-model-identifier-for-marquee-verification"
        const val MODEL_LONG_NAME =
            "qwen/qwen3-max-preview-ultra-long-model-name-abcdefghijklmnopqrstuvwxyz0123456789"

        val MODELS_FRAME =
            """{"kind":"models","groups":[{"provider":"$FIXTURE_PROVIDER","models":[""" +
                """{"id":"$MODEL_MAIN_ID","name":"$MODEL_MAIN_NAME","contextWindow":200000,"maxTokens":8192,""" +
                """"reasoning":true,"input":["text"],"thinkingLevels":["low","medium","high"]},""" +
                """{"id":"shorty","name":"$MODEL_SHORT_NAME","contextWindow":1000,"maxTokens":10,""" +
                """"reasoning":false,"input":["text"],"thinkingLevels":[]},""" +
                """{"id":"$MODEL_NO_THINK_ID","name":"$MODEL_NO_THINK_NAME","contextWindow":1000,"maxTokens":10,""" +
                """"reasoning":false,"input":["text"],"thinkingLevels":[]},""" +
                """{"id":"$MODEL_LONG_ID","name":"$MODEL_LONG_NAME","contextWindow":1000,"maxTokens":10,""" +
                """"reasoning":false,"input":["text"],"thinkingLevels":[]}]}]}"""

        /** 24 条短消息（12 对），保证列表可滚动、贴底判定与跳最新可见性真实成立。 */
        val HISTORY_MANY_FRAME: String =
            """{"kind":"history","sessionId":"s1","messages":[""" +
                (0 until 24).joinToString(",") { i ->
                    val role = if (i % 2 == 0) "user" else "assistant"
                    "{\"role\":\"$role\",\"content\":[{\"type\":\"text\",\"text\":\"F03 滚动消息 $i\"}]}"
                } +
                """],"entryIds":[""" +
                (0 until 24).joinToString(",") { "\"h-$it\"" } +
                """],"hasMore":false,"oldestEntryId":"h-0","bytes":1024}"""
    }
}
