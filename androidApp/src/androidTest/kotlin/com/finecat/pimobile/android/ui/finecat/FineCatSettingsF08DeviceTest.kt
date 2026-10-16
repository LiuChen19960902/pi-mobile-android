package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
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
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置整页、网关配对及 Provider/模型基础表单的设备回归。
 *
 * 真源：
 * - 原型 `screenSettings`/`gwRow`/`pvRow`（FineCat/prototype/app.js:388-434,456-482）与
 *   styles.css `.gw/.field/.card/.rows`（2616-2679/2318-2335/630-744）。
 * - 真实校验链：入口加入 = holder.addEndpointCandidate（空/重复本地拒）→
 *   GatewayRuntime.setEndpointCandidates → requireWebSocketEndpoint（协议非法/公网明文
 *   ws 拒绝，platformError 呈现，列表不落盘）；配对 = 本地 GatewayPairingPayloadParser
 *   行内报错、失败不清输入；provider 测试/拉取/保存 = 网关帧终态。
 * - 既有语义（P10a/b、F01）：key 只写不回显、留空保留、测试指纹门槛、拉取只增不覆盖、
 *   协议切换有损失先确认。
 *
 * 宿主：FakeTransport 合成网关（无真实网络/凭据/prefs），路径从**真实 FineCatApp 根**
 * 首页 → 抽屉 → 设置进入。设备红线：emulator-5580@5041、install -r、串行。
 * 证据：external filesDir 根 PNG + capture-measure.txt（CAPTURE-MEASURE 行），由
 * verify-stage.sh capture F08 归领。API Key 一律用 `sk-f08-TEST` 前缀的假值并在用例内
 * 断言它不出现在本地配置与 logcat。
 */
@RunWith(AndroidJUnit4::class)
class FineCatSettingsF08DeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null
    private var transportRef: FakeTransport? = null

    @After
    fun tearDown() {
        graph?.stateHolder?.close()
        graph?.gatewayScope?.cancel()
        graph?.applicationScope?.cancel()
        graph = null
        holder = null
        transportRef = null
    }

    // ------------------------------------------------------------------
    // 1. S11 整页结构：头部/小节/卡片/发丝线/输入行/编号/动作/配对块/Provider 行
    // ------------------------------------------------------------------
    @Test
    fun s11_settings_structure_headerCardsHairlinesInputIdxActionsProviderRows() {
        connectAndOpenSettings(seedEntries = true)
        // 头部：返回 + 居中「设置」
        waitUntilTagPresent("finecat.settingsBack")
        compose.onNodeWithText("设置").assertExists()
        // 两个小节标题（原型 .sec-label）
        compose.onNodeWithText("网关").assertExists()
        compose.onNodeWithText("模型与 Provider").assertExists()
        // 设置首页只呈现地址摘要 / 延迟 / 连接动作；入口输入和配对在二级弹层。
        compose.onNodeWithText("网关地址").assertExists()
        compose.onNodeWithText("网关延迟").assertExists()
        compose.onNodeWithText(gatewayAddressLabel(holder!!.gatewayState.endpoint)).assertExists()
        assertEquals(0, compose.onAllNodesWithTag("finecat.endpointInput").fetchSemanticsNodes().size)
        compose.onNodeWithText("点击测速").assertExists()
        compose.onNodeWithText("断开连接").assertExists()
        val card1 = tagBounds("finecat.gwCard1")
        val divider1x = firstDividerXIn("finecat.gwCard1")
        assertEquals(
            "第一张卡发丝线应从 56dp 起（原型 .rows::before inset）",
            56f,
            (divider1x - card1.left) / density,
            2f,
        )
        compose.onNodeWithText(
            "自定义模型排在内置后面，组标题就是它所属的 Provider；改完点「完成」才写回列表。",
        ).assertExists()
        // Provider 行：名称 + 副行（方言 · N 个模型 · M 项改动）+ 缺 key tag + 添加行
        compose.onNodeWithText(PROVIDER_ALPHA_NAME).assertExists()
        compose.onNodeWithText("openai-completions · 1 个模型 · 1 项改动").assertExists()
        compose.onNodeWithText(PROVIDER_BETA_NAME).assertExists()
        compose.onNodeWithText("anthropic-messages · 1 个模型").assertExists()
        compose.onNodeWithText("缺 key").assertExists()
        compose.onNodeWithText("添加 Provider").assertExists()
        compose.onNodeWithTag("finecat.gwAddress").performClick()
        waitUntilTagPresent("finecat.gatewayEntriesSheet")
        // 第二张卡：输入行在入口列表**上方**（原型 .field 在卡片顶部）
        val inputBounds = tagBounds("finecat.endpointInput")
        val row0Bounds = tagBounds("finecat.endpointRow.0")
        assertTrue(
            "输入行应在入口列表上方：input.top=${inputBounds.top} row0.top=${row0Bounds.top}",
            inputBounds.top < row0Bounds.top,
        )
        // 二级入口显示完整主机端口，实际已连接项独立标记。
        compose.onNodeWithText("1").assertDoesNotExist()
        compose.onNodeWithText("2").assertDoesNotExist()
        compose.onNodeWithText(gatewayAddressLabel(URL_HOME)).assertExists()
        compose.onNodeWithText(gatewayAddressLabel(URL_TAIL)).assertExists()
        compose.onNodeWithTag("finecat.endpointConnected.1").assertExists()
        val connectedEndpoint = holder!!.gatewayState.endpoint
        val entriesBeforeTyping = holder!!.endpointCandidates.toList()
        val input = compose.onNodeWithTag("finecat.endpointInput")
        input.performClick()
        input.performTextInput(URL_TAIL)
        compose.waitForIdle()
        assertEquals("输入草稿不得改写连接入口", connectedEndpoint, holder!!.gatewayState.endpoint)
        assertEquals("未提交草稿不得改写入口列表", entriesBeforeTyping, holder!!.endpointCandidates)
        input.performTextClearance()
        compose.waitForIdle()
        val moveUp0 = compose.onAllNodesWithTag("finecat.endpointMoveUp.0").fetchSemanticsNodes()
        assertEquals("首行也应渲染「上移」（原型 disabled 形态）", 1, moveUp0.size)
        // 行内两条提示文案与原型逐字一致
        compose.onNodeWithText(
            "多个入口按列表顺序尝试（例：1 在家局域网、2 在外 Tailscale）；" +
                "凭据是设备级的，切换入口不用重新配对。",
        ).assertExists()
        // 配对已移到设置页同级入口，不再塞进地址管理弹层。
        compose.onNodeWithTag("finecat.pairingInput").assertDoesNotExist()

        // 测量取证：发丝线内缩/通栏、输入行左内边距、序号列、动作可达
        val card2 = tagBounds("finecat.gwCard2")
        val divider2x = firstDividerXIn("finecat.gwCard2")
        assertEquals(
            "入口卡发丝线应为通栏（原型 .gw 自身 border-top）",
            0f,
            (divider2x - card2.left) / density,
            2f,
        )
        val inputText = compose.onNodeWithText("192.0.2.10:3081", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "输入行文本左内边距 20dp（.field padding 16/20）",
            20f,
            (inputText.left - card2.left) / density,
            2f,
        )
        val row0 = tagBounds("finecat.endpointRow.0")
        assertTrue(
            "入口行触控高 ≥48dp：实测 ${row0.height / density}dp",
            row0.height / density >= 48f,
        )
        val url0 = requireNodeWithTextIn("finecat.endpointRow.0", gatewayAddressLabel(URL_HOME))
        assertEquals(
            "无序号后地址距行左缘 16dp",
            16f,
            (url0.left - row0.left) / density,
            2f,
        )
        fun rectFor(tag: String): String {
            val b = tagBounds(tag)
            return "${b.left.roundToInt()},${b.top.roundToInt()}," +
                "${b.width.roundToInt()},${b.height.roundToInt()}"
        }
        saveMeasure(
            "CAPTURE-MEASURE: f08.hairline.inset56 placed=1 maxDeltaDp=0 " +
                "actual=${divider1x.roundToInt()},${(card1.top + 2 * density).roundToInt()}," +
                "${(card1.width - divider1x + card1.left).roundToInt()},${density.roundToInt()}",
        )
        saveMeasure(
            "CAPTURE-MEASURE: f08.hairline.fullwidth placed=1 maxDeltaDp=0 " +
                "actual=${divider2x.roundToInt()},${(card2.top + 2 * density).roundToInt()}," +
                "${card2.width.roundToInt()},${density.roundToInt()}",
        )
        saveMeasure(
            "CAPTURE-MEASURE: f08.endpoint.inputLeftPad placed=1 maxDeltaDp=2 " +
                "actual=${inputText.left.roundToInt()},${inputText.top.roundToInt()}," +
                "${inputText.width.roundToInt()},${inputText.height.roundToInt()}",
        )
        // r23：48dp独立触控盒贴边不重叠，中心距48dp。
        val up0 = tagBounds("finecat.endpointMoveUp.0")
        val rm0 = tagBounds("finecat.endpointRemove.0")
        val centerDistDp = (rm0.center.x - up0.center.x) / density
        // 允许亚像素取整的0.5dp判定余量；仍须留出相邻动作间隙。
        assertTrue(
            "动作按钮中心距应 ≥48dp（判定 ≥47.5dp）：实测 $centerDistDp dp",
            centerDistDp >= 47.5f,
        )
        // 归档行：两按钮合并外框矩形 + 实测中心距（三轮 N-11：可事后复核 48dp 判定）
        saveMeasure(
            "CAPTURE-MEASURE: f08.endpoint.actCenterDist placed=1 maxDeltaDp=2 " +
                "actual=${up0.left.roundToInt()},${up0.top.roundToInt()}," +
                "${(rm0.right - up0.left).roundToInt()},${(rm0.bottom - up0.top).roundToInt()} " +
                "centerDistDp=${"%.2f".format(centerDistDp)}",
        )
        // 48dp 下限断言在用例体内完成；本行归档入口行的实测几何
        saveMeasure(
            "CAPTURE-MEASURE: f08.endpoint.rowHeight placed=1 maxDeltaDp=0 " +
                "actual=${row0.left.roundToInt()},${row0.top.roundToInt()}," +
                "${row0.width.roundToInt()},${row0.height.roundToInt()}",
        )
        saveShot("f08-s11-struct.png")
    }

    // ------------------------------------------------------------------
    // 2. 入口增/删/上移：顺序严格等于操作序列；真实入库后清空草稿
    // ------------------------------------------------------------------
    @Test
    fun s11_gatewayEntry_addOrderMoveRemove_strictSequence() {
        connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        compose.waitForIdle()
        val seed = h.endpointCandidates.toList() // 初始自动连接不写 candidates，通常为空

        typeEndpointAndAdd(gatewayAddressLabel(URL_HOME))
        candidatesWait("加 HOME 后") { h.endpointCandidates == seed + URL_HOME }
        typeEndpointAndAdd(URL_TAIL)
        candidatesWait("加 TAIL 后") { h.endpointCandidates == seed + listOf(URL_HOME, URL_TAIL) }
        assertEquals(
            "新增应按操作序追加（顺序即尝试顺序）",
            seed + listOf(URL_HOME, URL_TAIL),
            h.endpointCandidates,
        )
        // 真实列表更新后才清空；非法 URL 失败路径另测保留输入。
        compose.waitForIdle()
        assertEndpointInputText("")

        // 末位上移：与前一位置互换（索引按实际列表长度取）。先滚到卡片更下方的
        // 添加行，保证动作按钮完整入视口（F04 教训：中心点在屏外时 performClick
        // 静默无效），再点击目标按钮。
        val lastIdx = h.endpointCandidates.size - 1
        scrollTagIntoView("finecat.endpointAdd")
        assertNodeInsideViewport("finecat.endpointMoveUp.$lastIdx")
        compose.onNodeWithTag("finecat.endpointMoveUp.$lastIdx").performClick()
        candidatesWait("上移后应为 TAIL,HOME") { h.endpointCandidates == seed + listOf(URL_TAIL, URL_HOME) }
        // 删除首位：剩余保持相对顺序
        scrollTagIntoView("finecat.endpointAdd")
        assertNodeInsideViewport("finecat.endpointRemove.0")
        compose.onNodeWithTag("finecat.endpointRemove.0").performClick()
        candidatesWait("删除首位后应剩 HOME") { h.endpointCandidates == seed + listOf(URL_HOME) }
        assertEquals(seed + listOf(URL_HOME), h.endpointCandidates)
        // 本地配置同一份事实
        runBlocking {
            assertEquals(h.endpointCandidates.toList(), graph!!.preferences.load().endpointCandidates)
        }
        // 全程无全局错误模态（例行增删不是错误）
        assertEquals(null, h.platformError)
        assertFalse(
            "不得出现「该地址已在列表里」",
            compose.onAllNodesWithText("该地址已在列表里").fetchSemanticsNodes().isNotEmpty(),
        )
        saveShot("f08-gw-order.png")
    }

    // ------------------------------------------------------------------
    // 3. 空/重复加入：行内报错、不写列表、无全局模态、输入保留
    // ------------------------------------------------------------------
    @Test
    fun s11_gatewayEntry_emptyAndDuplicate_inlineError_inputKept() {
        connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        compose.waitForIdle()
        val seed = h.endpointCandidates.toList()

        // 空地址加入：行内「地址为空」
        scrollTagIntoView("finecat.endpointAdd")
        compose.onNodeWithTag("finecat.endpointAdd").performClick()
        waitUntilTagPresent("finecat.endpointError")
        compose.onNodeWithText("地址为空").assertExists()
        assertEquals(seed, h.endpointCandidates)

        // 正常加入一个，然后重复加入：行内「该地址已在列表里」，列表不变
        typeEndpointAndAdd(URL_HOME)
        compose.waitUntil(5_000) { h.endpointCandidates == seed + URL_HOME }
        typeEndpointAndAdd(URL_HOME)
        compose.onNodeWithText("该地址已在列表里").assertExists()
        assertEquals(seed + URL_HOME, h.endpointCandidates)
        // 输入保留待改
        assertEndpointInputText(URL_HOME)
        assertNull("空/重复是例行校验，不得走全局模态（platformError 应为 null）", h.platformError)
        // 修正为合法新地址后行内错误清除、正常入库（URL_HOME 已在列，改用 URL_TAIL）
        typeEndpointAndAdd(URL_TAIL)
        compose.waitUntil(5_000) { h.endpointCandidates == seed + listOf(URL_HOME, URL_TAIL) }
        waitUntilTagGone("finecat.endpointError")
        runBlocking {
            assertEquals(seed + listOf(URL_HOME, URL_TAIL), graph!!.preferences.load().endpointCandidates)
        }
        saveShot("f08-gw-dup.png")
    }

    // ------------------------------------------------------------------
    // 4. 非法 URL：走真实 runtime 校验链（requireWebSocketEndpoint），全局错误面呈现、
    //    列表不落盘、输入保留。两档：协议非法、公网明文 ws。
    // ------------------------------------------------------------------
    @Test
    fun s11_gatewayEntry_illegalUrl_realChainRejects_inputAndListKept() {
        connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        compose.waitForIdle()
        val seed = h.endpointCandidates.toList()

        typeEndpointAndAdd(URL_ILLEGAL_SCHEME)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("请输入有效的 IP 或主机名:端口")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("非法地址不得入库", seed, h.endpointCandidates)
        assertEndpointInputText(URL_ILLEGAL_SCHEME)
        assertNull(h.platformError)

        // 公网明文 ws：真实链路的第二条规则（不是仅 scheme 前缀判断）
        typeEndpointAndAdd(URL_PUBLIC_WS)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("公网地址必须使用 wss://（明文 ws:// 仅限回环与私网）")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(seed, h.endpointCandidates)
        assertEndpointInputText(URL_PUBLIC_WS)
        dismissPlatformErrorDialog()
        assertNull(h.platformError)
        runBlocking { assertEquals(seed, graph!!.preferences.load().endpointCandidates) }
        saveShot("f08-gw-illegal.png")
    }

    // ------------------------------------------------------------------
    // 5. 配对：本地解析失败行内报错、输入保留；空输入不可提交
    // ------------------------------------------------------------------
    @Test
    fun s11_pairing_invalidPayload_inlineError_keepsInput() {
        connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        val scan = tagBounds("finecat.openScanner")
        val manual = tagBounds("finecat.openManualPairing")
        assertTrue("手动入口与扫码在设置页同级，不嵌套扫描相机", manual.top >= scan.bottom)
        scrollTagIntoView("finecat.pairingInput")
        // 空输入：提交不可用（Disabled 语义）
        assertTrue(
            "空配对输入时提交应禁用",
            compose.onNodeWithTag("finecat.pairingSubmit").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Disabled) != null,
        )
        // 非法 payload：行内错误 + 输入保留
        compose.onNodeWithTag("finecat.pairingInput").performClick()
        compose.onNodeWithTag("finecat.pairingInput").performTextInput(PAIRING_GARBAGE)
        compose.onNodeWithTag("finecat.pairingSubmit").performClick()
        waitUntilTagPresent("finecat.pairingOutcome")
        // 解析失败的行内错误（具体词取决于输入落在哪一档：非 Base64URL 或非配对 JSON）
        val outcomeText = compose.onAllNodesWithTag("finecat.pairingOutcome").fetchSemanticsNodes()
            .first().config[SemanticsProperties.Text].joinToString()
        assertTrue(
            "配对失败应给行内错误，实际：$outcomeText",
            outcomeText.contains("配对"),
        )
        // 配对失败保留输入：值断言兼容 InputText/EditableText 两代语义属性
        assertPairingInputText(PAIRING_GARBAGE)
        compose.onNodeWithTag("finecat.pairingInput").performTextClearance()
        compose.onNodeWithTag("finecat.pairingInput").performTextInput("abc")
        waitUntilTagGone("finecat.pairingOutcome")
        assertNull(h.platformError)
        saveShot("f08-pair.png")
    }

    // ------------------------------------------------------------------
    // 6. Ping waits for a real pong; no send-accepted success claim.
    // ------------------------------------------------------------------
    @Test
    fun s11_ping_waitsForPongThenShowsMeasuredRoundTrip() {
        val transport = connectAndOpenSettings(seedEntries = false)
        val before = transport.payloadsOfType("ping").size
        scrollTagIntoView("finecat.gwPing")
        compose.onNodeWithTag("finecat.gwPing").performClick()
        compose.waitUntil(5_000) { transport.payloadsOfType("ping").size > before }
        compose.onNodeWithText("测速中…").assertExists()
        compose.onNodeWithTag("finecat.gwPing").performClick()
        assertEquals(before + 1, transport.payloadsOfType("ping").size)
        transport.receive("""{"kind":"pong"}""")
        compose.waitUntil(5_000) { holder!!.pingResult?.endsWith(" ms") == true }
        assertTrue(holder!!.pingResult!!.removeSuffix(" ms").toLong() >= 0)
        saveShot("f08-ping.png")
    }

    // ------------------------------------------------------------------
    // 7. 拉取只增不覆盖：ID 集合严格相等、已有模型 compat/映射原样、回执计数、
    //    保存 wire 逐字段核对（容量可覆盖默认）
    // ------------------------------------------------------------------
    @Test
    fun s11_provider_pull_appendsOnly_idSetStrict_wireCompatKept() {
        val transport = connectAndOpenSettings(seedEntries = false)
        openEditFormFor(PROVIDER_ALPHA_ID)

        scrollTagIntoView("finecat.pullModels")
        clickTag("finecat.pullModels")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-models").size == 1 }
transport.receive(PULL_RESULT_FRAME)
        waitUntilTagPresent("finecat.pullReceipt")
        compose.onNodeWithText("新增 1 · 跳过 2（已存在）").assertExists()

        // 行集合：恰 2 行，ID 集合与 fixture 预期严格相等
        val rowIds = modelRowIds()
        assertEquals(listOf(MODEL_ALPHA_1, MODEL_ALPHA_2), rowIds)
        // 拉取不覆盖已有模型，容量缺省不再被标为待配置。
        compose.onNodeWithText("待配置").assertDoesNotExist()

        // 既有模型的 compat/thinking 映射原样（打开详情看透传区）。
        // F11/F12 把「本轮不可编辑」透传行换成了编辑区：本 fixture 目录帧没有声明
        // thinkingLevels（档位真源在 models 帧），思考区按 UI 规范走空态提示行；
        // compat 折叠报「已设置 1 项」。透传语义由下方 put 帧 JSON 逐字段核对兜底。
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        compose.onNodeWithTag("finecat.thinkingMap").assertExists()
        // compatCount 是折叠区里的 Text，会被合进父语义节点（tag 不在合并树上）；
        // 用其文本判在，语义等价。
        compose.onNodeWithText("已设置 1 项").assertExists()
        pressBackSystem()
        // 返回一级（卡片头返回钮；system Back 由二级卡的 BackHandler 接收）
        waitUntilTagGone("finecat.modelDetail")

        // 用户可覆盖新模型默认容量；留空保存路径由纯逻辑专项覆盖。
        compose.onNodeWithTag("finecat.modelRow.1").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        fillField("finecat.modelContext", "32K")
        tapTopScrim()
        waitUntilTagGone("finecat.modelDetail")

        // 保存：拉取后表单已变，测试门槛要求先测；测过后保存，wire 逐字段核对
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) {
            transport.payloadsOfType("provider-test").size == 1
        }
transport.receive(TEST_RESULT_OK_FRAME)
transport.receive(MODELS_EMPTY) // 成功结论触发的 models 请求补齐 lane
transport.receive(PROVIDERS_FRAME) // 成功结论触发 loadProviders，补齐 lane
transport.receive(MODELS_EMPTY)    // 同一结论触发的 models 请求补齐（短超时防连坐）
        waitUntilTagPresent("finecat.providerTestResult")
        compose.onNodeWithTag("finecat.providerTestResult").assertExists()
        // 成功回执不含伪造 RTT（帧无 latencyMs → 不出现 ms）
        val pullTestOutcome = compose.onAllNodesWithTag("finecat.providerTestResult")
            .fetchSemanticsNodes().first().config[SemanticsProperties.Text].joinToString()
        assertFalse("无 RTT 时不得造 ms：$pullTestOutcome", pullTestOutcome.contains("ms"))
        // 点卡片头「保存」
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-put").size == 1 }
        val put = transport.payloadsOfType("provider-put").first()
        assertEquals(PROVIDER_ALPHA_ID, put["id"]!!.jsonPrimitive.content)
        val models = put["models"]!!.jsonArray
        assertEquals(2, models.size)
        val ids = models.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(MODEL_ALPHA_1, MODEL_ALPHA_2), ids)
        val kept = models.first().jsonObject
        // 拉取没有抹掉已配好的参数（兼容性/思考映射/窗口原样）
        assertEquals("openai-completions", kept["api"]!!.jsonPrimitive.content)
        assertEquals(131072L, kept["contextWindow"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            mapOf("medium" to "enabled"),
            kept["thinkingLevelMap"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        // 同上：PULL 用例未编辑 compat → 不发（网关合并保留 supportsStrictMode，
        // 由 PROVIDER_SAVED_AFTER_PULL_FRAME 回显带原值兜住「拉取没抹掉参数」意图）。
        assertNull(
            "拉取后未触碰 compat 不得上 wire: kept=$kept",
            kept["compat"]?.takeIf { it !is JsonNull },
        )
        val fresh = models.last().jsonObject
        assertEquals("openai-completions", fresh["api"]!!.jsonPrimitive.content)
        assertEquals(32768L, fresh["contextWindow"]!!.jsonPrimitive.content.toLong())
        // 新行的 reasoning 表单默认 on，按当前实现逐字冻结在 wire 上
        assertEquals("true", fresh["reasoning"]!!.jsonPrimitive.content)

        // 回执落位后卡片收起，列表按应答回填（2 个模型）
transport.receive(PROVIDER_SAVED_AFTER_PULL_FRAME)
        try {
            compose.waitUntil(8_000) { holder!!.providerResult?.startsWith("Provider 已保存") == true }
        } catch (e: Throwable) {
            throw AssertionError(
                "保存回执未出现：providerResult=${holder!!.providerResult} saveLoading=${holder!!.providerSaveIsLoading} " +
                    "providers=${holder!!.providers.map { it.id to it.models.size }} " +
                    "platformError=${holder!!.platformError} specs=${transportRef?.specs?.map { it.generation }}",
                e,
            )
        }
        waitUntilTagGone("finecat.providerEdit")
        compose.waitUntil(5_000) {
            holder!!.providers.first { it.id == PROVIDER_ALPHA_ID }.models.size == 2
        }
        saveShot("f08-pull.png")
    }

    // ------------------------------------------------------------------
    // 8. 测试连接：失败终态 → 成功终态（新终态覆盖旧提示）；迟到旧结论不授权新表单
    // ------------------------------------------------------------------
    @Test
    fun s11_provider_test_failureThenSuccess_staleResultAuthorizesNothing() {
        val transport = connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        openEditFormFor(PROVIDER_ALPHA_ID)

        // 失败终态：网关 error 帧 → RequestRejected → loading 结束、失败可见
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 1 }
        transport.receive(PROVIDER_TEST_ERROR_FRAME)
        waitUntilTagPresent("finecat.providerResultInEdit")
        compose.onNodeWithTag("finecat.providerResultInEdit").assertExists()
        assertFalse("失败终态后 loading 必须结束", h.providerTestIsLoading)

        // 成功终态：再次测试成功，新结论出现（旧失败提示 4s 自清，不再主导）
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 2 }
        transport.receive(TEST_RESULT_OK_FRAME)
        transport.receive(MODELS_EMPTY) // 成功结论触发的 models 请求补齐 lane
        transport.receive(PROVIDERS_FRAME) // 成功结论触发 loadProviders，补齐 lane
        transport.receive(MODELS_EMPTY)    // 同一结论触发的 models 请求补齐（短超时防连坐）
        waitUntilTagPresent("finecat.providerTestResult")
        compose.onNodeWithTag("finecat.providerTestResult").assertExists()
        assertFalse(h.providerTestIsLoading)

        // 迟到的旧结论不授权新表单：关闭卡后注入旧成功（合成链路下无在途请求，
        // 帧被静默丢弃但 holder 的结果位仍被写入）；重开并**改动表单**后，
        // 旧成功不得充当本表单的测试门槛通行证。
        pressBackSystem()
        waitUntilTagGone("finecat.providerEdit")
        transport.receive(TEST_RESULT_LATE_OK_FRAME)
        compose.waitUntil(3_000) { h.providerTestResult != null }
        openEditFormFor(PROVIDER_ALPHA_ID)
        assertFalse(
            "重开的表单不得继承旧测试结论",
            compose.onAllNodesWithText("连接正常").fetchSemanticsNodes().isNotEmpty(),
        )
        // 改动连通性字段后指纹已变（名称不在指纹内）：没有本表单自己的测试结论
        // → 保存门槛必须仍在
        fillField("finecat.formApiKey", "sk-f08-changed")
        waitUntilTagPresent("finecat.saveGate")
        compose.onNodeWithText("请点模型右侧的测试图标，至少一个模型测试通过后才能保存。").assertExists()
        // 该状态下点保存：0 次 provider-put（未回执不假保存）
        val putsBefore = transport.payloadsOfType("provider-put").size
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals(putsBefore, transport.payloadsOfType("provider-put").size)
        saveShot("f08-test-term.png")
    }

    // ------------------------------------------------------------------
    // 9. 测试连接 取消/超时 终态：loading 结束 0 泄漏。取消与超时用独立图，
    //    避免同一连接上「重连取消」与「请求超时」的时序互扰。
    // ------------------------------------------------------------------
    @Test
    fun s11_provider_test_cancel_endLoading() {
        val transport = connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        openEditFormFor(PROVIDER_ALPHA_ID)

        // 取消：发起后立即重开连接轮（connection-replaced 取消在途请求）
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 1 }
        runBlocking { h.connect() }
        compose.waitUntil(6_000) {
            compose.onAllNodesWithText("Provider 请求已取消，请重试").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("取消终态后 loading 必须结束", h.providerTestIsLoading)
        saveShot("f08-test-cancel.png")
    }

    @Test
    fun s11_provider_test_timeout_showsUnconfirmedAndEndsLoading() {
        val transport = connectAndOpenSettings(seedEntries = false, requestTimeout = 2_000L)
        transport.receive(SESSIONS_EMPTY)
        val h = holder!!
        openEditFormFor(PROVIDER_ALPHA_ID)

        // 超时：2s 无应答 → RequestTimedOut，不判死失败、给未确认回执
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 1 }
        compose.waitUntil(8_000) { h.providerResult != null }
        assertEquals("Provider 请求超时，请重试", h.providerResult)
        assertFalse("超时终态后 loading 必须结束", h.providerTestIsLoading)
        saveShot("f08-test-abort.png")
    }

    // ------------------------------------------------------------------
    // 10. 拉取 取消/超时 终态：loading 结束 0 泄漏、无半截回执
    // ------------------------------------------------------------------
    @Test
    fun s11_provider_pull_cancel_endLoading() {
        val transport = connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        openEditFormFor(PROVIDER_ALPHA_ID)

        scrollTagIntoView("finecat.pullModels")
        clickTag("finecat.pullModels")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-models").size == 1 }
        runBlocking { h.connect() }
        compose.waitUntil(6_000) {
            compose.onAllNodesWithText("Provider 请求已取消，请重试").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("取消终态后拉取 loading 必须结束", h.providerModelsIsLoading)
        assertEquals("取消后不得有半截回执", null, holder!!.providerModelsResult)
        assertEquals("取消不改动已有模型行", 1, modelRowCount())
        saveShot("f08-pull-cancel.png")
    }

    @Test
    fun s11_provider_pull_timeout_showsUnconfirmedAndEndsLoading() {
        val transport = connectAndOpenSettings(seedEntries = false, requestTimeout = 2_000L)
        transport.receive(SESSIONS_EMPTY)
        val h = holder!!
        openEditFormFor(PROVIDER_ALPHA_ID)

        scrollTagIntoView("finecat.pullModels")
        clickTag("finecat.pullModels")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-models").size == 1 }
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText("Provider 请求超时，请重试").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("超时终态后拉取 loading 必须结束", h.providerModelsIsLoading)
        assertEquals("超时不改动已有模型行", 1, modelRowCount())
        saveShot("f08-pull-abort.png")
    }

    // ------------------------------------------------------------------
    // 11. 保存 取消/超时：未回执不假保存（卡不收、列表无回读、写请求各 1 次）
    // ------------------------------------------------------------------
    @Test
    fun s11_provider_save_cancel_noFakeClose_zeroReadback() {
        val transport = connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        openNewProviderFormReadyForSave(transport)

        // 取消：保存发出后重开连接轮 → 配置未确认、卡片不收、无回读
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-put").size == 1 }
        runBlocking { h.connect() }
        compose.waitUntil(6_000) {
            compose.onAllNodesWithText("保存未确认，请重新打开核对")
                .fetchSemanticsNodes().isNotEmpty()
        }
        waitUntilTagPresent("finecat.providerEdit")
        assertNull("取消不等于保存成功：不得出现保存成功回执", h.providerResult?.takeIf { it.contains("已保存") })
        assertTrue(
            "新建 provider 不得在未回执时进入列表",
            h.providers.none { it.id == PROVIDER_NEW_ID },
        )
        saveShot("f08-save-cancel.png")
    }

    @Test
    fun s11_provider_save_timeout_noFakeClose_zeroReadback() {
        val transport = connectAndOpenSettings(seedEntries = false, requestTimeout = 2_000L)
        transport.receive(SESSIONS_EMPTY)
        val h = holder!!
        openNewProviderFormReadyForSave(transport)

        // 超时：同样不收卡、不回读
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-put").size == 1 }
        compose.waitUntil(8_000) { h.providerResult != null }
        assertEquals("保存未确认，请重新打开核对", h.providerResult)
        waitUntilTagPresent("finecat.providerEdit")
        assertTrue(
            "新建 provider 不得在未回执时进入列表",
            h.providers.none { it.id == PROVIDER_NEW_ID },
        )
        saveShot("f08-save-abort.png")
    }

    /** 新建表单填最小可保存集（ID + baseUrl + 一个带窗口的模型）并过测试门槛。 */
    private fun openNewProviderFormReadyForSave(transport: FakeTransport) {
        scrollTagIntoView("finecat.providerNew")
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")
        fillField("finecat.formId", PROVIDER_NEW_ID)
        fillField("finecat.formBaseUrl", PROVIDER_NEW_BASE_URL)
        scrollTagIntoView("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        fillField("finecat.modelId", MODEL_NEW_1)
        fillField("finecat.modelContext", "32K")
        // 聚焦的输入框会先消费 Back（清焦点），改点遮罩上部收起二级卡
        tapTopScrim()
        waitUntilTagGone("finecat.modelDetail")

        // 门槛：先测试通过（成功结论触发 loadProviders + models：等各自请求上线
        // 再应答，否则应答早于请求会被丢弃，悬空请求在短超时图上会连坐取消保存）
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 1 }
        transport.receive(TEST_RESULT_OK_FRAME_FOR_NEW)
        compose.waitUntil(5_000) { transport.payloadsOfType("providers").size >= 1 }
        transport.receive(PROVIDERS_FRAME)
        compose.waitUntil(5_000) { transport.payloadsOfType("models").size >= 1 }
        transport.receive(MODELS_EMPTY)
        waitUntilTagPresent("finecat.providerTestResult")
    }

    // ------------------------------------------------------------------
    // 12. API Key：不回显、密码变换渲染、不落本地配置、不进生产日志、留空不下发
    // ------------------------------------------------------------------
    @Test
    fun s11_apiKey_masked_notPersisted_notInLogs_blankKeptOnGateFailure() {
        val transport = connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        openEditFormFor(PROVIDER_ALPHA_ID)

        // 既有 key 不回显：空框 + 「已设置（留空保留）」占位
        val keyNode = compose.onNodeWithTag("finecat.formApiKey")
        keyNode.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithText("已设置（留空保留）").assertExists()

        // 输入测试 key：值在（草稿保留）、明文不出现在任何界面文本节点（密码变换渲染）
        keyNode.performClick()
        keyNode.performTextInput(TEST_KEY)
        assertEditableText("finecat.formApiKey", TEST_KEY)
        assertNoPlainTextNodeContains(TEST_KEY)

        // 测试连接：key 随帧上行（这是它的唯一去向）
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 1 }
        val testFrame = transport.payloadsOfType("provider-test").first()
        assertEquals(TEST_KEY, testFrame["apiKey"]!!.jsonPrimitive.content)
        transport.receive(TEST_RESULT_OK_FRAME)
        transport.receive(MODELS_EMPTY) // 成功结论触发的 models 请求补齐 lane
        transport.receive(PROVIDERS_FRAME)
        waitUntilTagPresent("finecat.providerTestResult")

        // 保存：wire 带 key（写入宿主 models.json 是网关职责）；随后本地配置/日志 0 命中
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-put").size == 1 }
        assertEquals(TEST_KEY, transport.payloadsOfType("provider-put").first()["apiKey"]!!.jsonPrimitive.content)
        transport.receive(PROVIDER_SAVED_ECHO_FRAME)
        waitUntilTagGone("finecat.providerEdit")
        runBlocking {
            assertFalse(
                "测试 key 不得落本地配置",
                graph!!.preferences.load().toString().contains(TEST_KEY),
            )
        }
        // 只读最近 20000 行：`logcat -d` 无界读全缓冲， marathon 批次后缓冲可达
        // 百 MB 级，readText() 直接 OOM（F13 终批 F08-KEY 实测 134MB 分配失败）。
        // 断言意图是「本轮刚执行的操作不把 key 写进生产日志」，尾部即语义等价。
        val logDump = shell("logcat -d -t 20000")
        assertFalse(
            "测试 key 不得出现在生产日志",
            logDump.contains("sk-f08-TEST"),
        )

        // 重开表单：key 依旧不回显；留空再保存 → provider-put 不带 apiKey 成员
        openEditFormFor(PROVIDER_ALPHA_ID)
        assertEditableText("finecat.formApiKey", "")
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 2 }
        transport.receive(TEST_RESULT_OK_FRAME)
        transport.receive(MODELS_EMPTY) // 成功结论触发的 models 请求补齐 lane
        transport.receive(PROVIDERS_FRAME)
        waitUntilTagPresent("finecat.providerTestResult")
        compose.onNodeWithText("保存").performClick()
        try {
            compose.waitUntil(5_000) { transport.payloadsOfType("provider-put").size == 2 }
        } catch (e: Throwable) {
            val dump = buildString {
                append("put 数未达 2：")
                append("testResult=${h.providerTestResult?.summary} ")
                append("formError=")
                runCatching {
                    compose.onAllNodesWithTag("finecat.formError").fetchSemanticsNodes()
                        .forEach { append(it.config[SemanticsProperties.Text].joinToString()) }
                }
                append(" saveGate=")
                runCatching {
                    compose.onAllNodesWithTag("finecat.saveGate").fetchSemanticsNodes()
                        .forEach { append(it.config[SemanticsProperties.Text].joinToString()) }
                }
            }
            throw AssertionError(dump, e)
        }
        assertNull(
            "留空 key 不得下发（网关按缺省回退已存值）",
            transport.payloadsOfType("provider-put").last()["apiKey"],
        )
        saveShot("f08-key.png")
    }

    // ------------------------------------------------------------------
    // 13. 模型详情/方言浮层：基础字段、只读 id、有损失切换可取消、无损失即生效、
    //     无假来源/假清除按钮、Back 逐层返回
    // ------------------------------------------------------------------
    @Test
    fun s11_modelDetail_fieldsToggles_dialectLossCancelable_noFakeSourceButtons() {
        val transport = connectAndOpenSettings(seedEntries = false)
        openEditFormFor(PROVIDER_ALPHA_ID)
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTagPresent("finecat.modelDetail")

        // 既有模型 id 只读（无输入框），其余字段值（输入值而非 label 断言）
        assertEquals(0, compose.onAllNodesWithTag("finecat.modelId").fetchSemanticsNodes().size)
        assertTrue(
            "只读 id 应以文本呈现",
            compose.onAllNodesWithText(MODEL_ALPHA_1).fetchSemanticsNodes().isNotEmpty(),
        )
        assertEditableText("finecat.modelName", "Alpha One")
        assertEditableText("finecat.modelContext", "128K")
        assertEditableText("finecat.modelMax", "8K")
        // F11/F12 后透传行变成编辑区：本 fixture 目录帧未声明 thinkingLevels，编辑器仍提供标准键；
        // 断言的是「帧里的透传值确实进了表单」：1 项 compat 在折叠计数里。
        compose.onNodeWithTag("finecat.thinkingMap").assertExists()
        // compatCount 是折叠区里的 Text，会被合进父语义节点（tag 不在合并树上）；
        // 用其文本判在，语义等价。
        compose.onNodeWithText("已设置 1 项").assertExists()
        // 来源/清除缺口不补假按钮
        assertEquals(0, compose.onAllNodesWithText("跟随").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("恢复上层").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("恢复默认映射").fetchSemanticsNodes().size)
        // 开关行整行可点：支持推理 off→on、输入图片 off→on（先于浮层交互，
        // 此时行已放置、bounds 真实）
        clickPlaced("finecat.modelReasoning")
        clickPlaced("finecat.modelImage")
        saveShot("f08-model-detail.png")

        // 方言浮层：切到 anthropic-messages 有 compat 损失 → 确认态 → 取消保留原协议
        clickPlaced("finecat.modelApiRow")
        compose.onNodeWithTag("finecat.modelApi.anthropic-messages").performClick()
        compose.onNodeWithText("切到 anthropic-messages（丢 1 项 compat）").assertExists()
        compose.onNodeWithText("取消，保留 openai-completions").performClick()
        compose.waitForIdle()
        // 焦点型 Popup 打开期间主窗口语义不可见：点当前协议项（等值 = 不变更且
        // 关闭浮层），得到确定性的「取消」收尾
        compose.onNodeWithTag("finecat.modelApi.openai-completions").performClick()
        waitUntilTagGone("finecat.modelApi.anthropic-messages")
        waitUntilTagPresent("finecat.modelApiRow")
        assertEditableTextNotPresentWithValue("finecat.modelApiRow", "anthropic-messages")

        // 无损失目标（openai-responses 含 supportsStrictMode）：选中即生效
        clickPlaced("finecat.modelApiRow")
        compose.onNodeWithTag("finecat.modelApi.openai-responses").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("openai-responses").fetchSemanticsNodes().isNotEmpty()
        }

        // Back 逐层：浮层开着先关浮层 → 再退二级卡 → 再退一级表单
        clickPlaced("finecat.modelApiRow")
        pressBackSystem()
        compose.waitForIdle()
        assertTrue(
            "Back 应先关浮层（二级卡仍在）",
            compose.onAllNodesWithTag("finecat.modelDetail").fetchSemanticsNodes().isNotEmpty(),
        )
        pressBackSystem()
        waitUntilTagGone("finecat.modelDetail")
        waitUntilTagPresent("finecat.providerEdit")

        // 保存走 wire：协议已切、开关已开、compat/映射原样保留
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 1 }
        transport.receive(TEST_RESULT_OK_FRAME)
        transport.receive(MODELS_EMPTY) // 成功结论触发的 models 请求补齐 lane
        transport.receive(PROVIDERS_FRAME)
        waitUntilTagPresent("finecat.providerTestResult")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-put").size == 1 }
        val model = transport.payloadsOfType("provider-put").first()["models"]!!.jsonArray
            .first().jsonObject
        assertEquals("openai-responses", model["api"]!!.jsonPrimitive.content)
        assertEquals(
            "reasoning 应随开关上 wire: model=$model",
            "true",
            model["reasoning"]!!.jsonPrimitive.content,
        )
        assertEquals(
            listOf("text", "image"),
            model["input"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // F12 `DEC-F09-COMPAT` 后 compat 只在用户触碰过该层时才上 wire
        // （FineCatProviderForm.kt:140 `takeIf { draft.compatTouched }`）。本用例只看过
        // 折叠计数、未展开编辑：不发 compat = 网关按合并语义保留原值，这正是新契约。
        // 旧透传断言（帧里必须带 supportsStrictMode）已随透传行为被替换。
        assertNull("未触碰 compat 层不得上 wire: model=$model", model["compat"]?.takeIf {
            it !is JsonNull
        })
        // 宿主回读 = 本次提交值：alpha-1 已改成 openai-responses + reasoning + 图片输入
        transport.receive(PROVIDER_SAVED_MODEL_DETAIL_FRAME)
        waitUntilTagGone("finecat.providerEdit")
    }

    // ------------------------------------------------------------------
    // 14. 保存回读与选择器一致：回读=提交值；模型目录/选择器按保存后的模型出现
    // ------------------------------------------------------------------
    @Test
    fun s11_save_readback_providerListAndModelSelectorConsistent() {
        val transport = connectAndOpenSettings(seedEntries = false)
        val h = holder!!
        openEditFormFor(PROVIDER_ALPHA_ID)
        fillField("finecat.formName", PROVIDER_ALPHA_RENAMED)
        clickTag("finecat.providerTest")
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-test").size == 1 }
        transport.receive(TEST_RESULT_OK_FRAME)
        transport.receive(MODELS_EMPTY) // 成功结论触发的 models 请求补齐 lane
        transport.receive(PROVIDERS_FRAME)
        waitUntilTagPresent("finecat.providerTestResult")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { transport.payloadsOfType("provider-put").size == 1 }
        assertEquals(PROVIDER_ALPHA_RENAMED, transport.payloadsOfType("provider-put").first()["name"]!!.jsonPrimitive.content)
        // 保存回执：宿主按应答回填（回读=提交值）
        transport.receive(PROVIDER_SAVED_RENAMED_FRAME)
        compose.waitUntil(8_000) { h.providerResult?.startsWith("Provider 已保存") == true }
        waitUntilTagGone("finecat.providerEdit")
        compose.waitUntil(5_000) {
            h.providers.firstOrNull { it.id == PROVIDER_ALPHA_ID }?.name == PROVIDER_ALPHA_RENAMED
        }
        // 保存后 holder 自动刷新模型目录：按真实 models 帧回，选择器与保存结果一致
        compose.waitUntil(5_000) { transport.payloadsOfType("models").size >= 1 }
        transport.receive(MODELS_FRAME_RENAMED)
        compose.waitUntil(5_000) {
            h.snapshot.modelCatalog?.groups?.any { it.provider == PROVIDER_ALPHA_RENAMED } == true
        }
        val group = h.snapshot.modelCatalog!!.groups.first { it.provider == PROVIDER_ALPHA_RENAMED }
        assertEquals(listOf(MODEL_ALPHA_1, MODEL_ALPHA_2), group.models.map { it.id })

        // 回首页，从真实模型入口核对选择器渲染（名称输入仍聚焦会先吃 Back，
        // 直接点设置页自己的返回按钮）
        compose.onNodeWithTag("finecat.settingsBack").performClick()
        waitUntilTagGone("finecat.settingsScreen")
        // 首页 composer 未聚焦时没有控制行：先聚焦展开，再点模型胶囊
        compose.onNodeWithTag("fc.composer.input").performClick()
        waitUntilTagPresent("finecat.modelChip")
        compose.onNodeWithTag("finecat.modelChip").performClick()
        waitUntilTagPresent("finecat.modelSheet")
        compose.onNodeWithTag("finecat.modelProvider.$PROVIDER_ALPHA_RENAMED.$MODEL_ALPHA_1", useUnmergedTree = true).assertTextEquals(PROVIDER_ALPHA_RENAMED)
        compose.onNodeWithTag("finecat.model.$PROVIDER_ALPHA_RENAMED.$MODEL_ALPHA_1").assertExists()
        compose.onNodeWithTag("finecat.model.$PROVIDER_ALPHA_RENAMED.$MODEL_ALPHA_2").assertExists()
        saveShot("f08-readback.png")
    }

    // ------------------------------------------------------------------
    // 15–19. 三环境亮暗矩阵（390 亮由 S11-STRUCT 覆盖）
    // ------------------------------------------------------------------
    @Test
    fun matrix_390dp_dark_s11() = matrixStep("f08-390d", dark = true, fs13 = false, densityDpi = 480)

    @Test
    fun matrix_360dp_light_s11() = matrixStep("f08-360l", dark = false, fs13 = false, densityDpi = 520)

    @Test
    fun matrix_360dp_dark_s11() = matrixStep("f08-360d", dark = true, fs13 = false, densityDpi = 520)

    @Test
    fun matrix_360dp_fs13_light_s11() = matrixStep("f08-fs13l", dark = false, fs13 = true, densityDpi = 520)

    @Test
    fun matrix_360dp_fs13_dark_s11() = matrixStep("f08-fs13d", dark = true, fs13 = true, densityDpi = 520)

    /** 矩阵通用步：注入配置 → 连接 → 真实入口进设置 → 关键元素可达（含滚动到底） → 截图。 */
    private fun matrixStep(prefix: String, dark: Boolean, fs13: Boolean, densityDpi: Int) {
        try {
            applyDeviceConfig(densityDpi, if (fs13) "1.3" else null)
            connectAndOpenSettings(seedEntries = true)
            if (dark) {
                holder!!.updateAppearanceMode("dark")
                runBlocking { waitUntil { holder!!.appearanceMode == "dark" } }
            }
            // 网关区关键行可达
            scrollTagIntoView("finecat.endpointAdd")
            compose.onNodeWithTag("finecat.endpointAdd").assertExists()
            // Provider 区滚到底：添加行 + 配对块可达（fs1.3 下逐项可滚至末项）
            scrollTagIntoView("finecat.providerNew")
            compose.onNodeWithTag("finecat.providerNew").performClick()
            waitUntilTagPresent("finecat.providerEdit")
            scrollTagIntoView("finecat.formApiKey")
            compose.onNodeWithTag("finecat.formApiKey").assertExists()
            pressBackSystem()
            waitUntilTagGone("finecat.providerEdit")
            scrollTagIntoView("finecat.pairingSubmit")
            compose.onNodeWithTag("finecat.pairingSubmit").assertExists()
            compose.onNodeWithTag("finecat.sheetDone").performClick()
            waitUntilTagGone("finecat.manualPairingSheet")
            saveShot("$prefix.png")
        } finally {
            restoreDeviceConfig()
        }
    }

    // ==================================================================
    // 图与 fixture
    // ==================================================================

    private companion object {
        const val URL_HOME = "ws://10.0.2.2:3081/ws/mobile"
        const val URL_TAIL = "wss://f08-tailscale.example/ws/mobile"
        const val URL_ILLEGAL_SCHEME = "https://f08.example/ws"
        const val URL_PUBLIC_WS = "ws://203.0.113.20:3081/ws/mobile"
        const val PAIRING_GARBAGE = "not-a-pairing-payload"
        const val PROVIDER_ALPHA_ID = "f08-alpha"
        const val PROVIDER_ALPHA_NAME = "F08 甲"
        const val PROVIDER_ALPHA_RENAMED = "F08 甲改"
        const val PROVIDER_BETA_ID = "f08-beta"
        const val PROVIDER_BETA_NAME = "F08 乙"
        const val PROVIDER_NEW_ID = "f08-new"
        const val PROVIDER_NEW_BASE_URL = "https://f08-new.example/v1"
        const val MODEL_ALPHA_1 = "m-alpha-1"
        const val MODEL_ALPHA_2 = "m-alpha-2"
        const val MODEL_NEW_1 = "m-new-1"
        const val TEST_KEY = "sk-f08-TEST-KEY-zero-leak"

        val HELLO = """{"kind":"hello","authenticated":true,"protocol":3,"port":3081}"""

        /** hello 后 runtime 自动补发 sessions 请求：短超时用例必须应答，否则它的
         * 超时会回收连接并连坐取消在途 provider 请求（实测根因）。 */
        val SESSIONS_EMPTY = """{"kind":"sessions","items":[]}"""
        val MODELS_EMPTY = """{"kind":"models","groups":[]}"""

        /** 甲=有 compat+映射的既有模型（apiKeySet=true）；乙=缺 key 行。 */
        val PROVIDERS_FRAME =
            """{"kind":"providers","providers":[""" +
                """{"id":"$PROVIDER_ALPHA_ID","name":"$PROVIDER_ALPHA_NAME",""" +
                """"baseUrl":"https://f08-alpha.example/v1","apiKeySet":true,""" +
                """"api":"openai-completions","active":true,""" +
                """"status":{"ok":true},"models":[""" +
                """{"id":"$MODEL_ALPHA_1","name":"Alpha One","api":"openai-completions",""" +
                """"reasoning":false,"input":["text"],"contextWindow":131072,"maxTokens":8192,""" +
                """"thinkingLevelMap":{"medium":"enabled"},""" +
                """"compat":{"supportsStrictMode":true}}]},""" +
                """{"id":"$PROVIDER_BETA_ID","name":"$PROVIDER_BETA_NAME",""" +
                """"baseUrl":"https://f08-beta.example/v1","apiKeySet":false,""" +
                """"api":"anthropic-messages","active":true,"models":[""" +
                """{"id":"m-beta-1","api":"anthropic-messages","reasoning":true,""" +
                """"input":["text"],"contextWindow":65536,"maxTokens":4096}]}]}"""

        /** 拉取应答：已有 1 + 新 1 + 上游重复 1 + 空串 1 → 新增 1 · 跳过 2。 */
        val PULL_RESULT_FRAME =
            """{"kind":"provider-models-result","ok":true,"id":"$PROVIDER_ALPHA_ID",""" +
                """"ids":["$MODEL_ALPHA_1","$MODEL_ALPHA_2","$MODEL_ALPHA_2",""]}"""

        fun testResultOk(id: String): String =
            """{"kind":"provider-test-result","ok":true,"id":"$id"}"""

        val TEST_RESULT_OK_FRAME = testResultOk(PROVIDER_ALPHA_ID)
        val TEST_RESULT_OK_FRAME_FOR_NEW = testResultOk(PROVIDER_NEW_ID)
        val TEST_RESULT_LATE_OK_FRAME = testResultOk(PROVIDER_ALPHA_ID)
        val PROVIDER_TEST_ERROR_FRAME =
            """{"kind":"error","code":"upstream-auth","message":"401 unauthorized",""" +
                """"requestType":"provider-test"}"""

        fun providerSavedEcho(
            id: String,
            name: String,
            modelsJson: String,
        ): String =
            """{"kind":"provider-saved","admitted":true,"refreshed":true,""" +
                // baseUrl 必须与 PROVIDERS_FRAME 里 ALPHA 的初值一致：F12 把 baseUrl 纳入
                // 保存回显反证（echoMatchesSubmit），宿主落盘后回读的就是提交值；模板写死
                // 一个不同域名会被判成「迟到的旧回执」而拒收卡。首版矛盾在 F08 期没人比对
                // baseUrl 所以没暴露，F13 全量设备回归抓到（F08-KEY/READBACK 曾因此失败）。
                """"provider":{"id":"$id","name":"$name","baseUrl":"https://f08-alpha.example/v1",""" +
                """"apiKeySet":true,"active":true,""" +
                """"status":{"ok":true},"models":[$modelsJson]}}"""

        /** modelDetail 用例的保存回执：忠实回显「改协议+开关」后的提交值（F12 反证）。 */
        val PROVIDER_SAVED_MODEL_DETAIL_FRAME = providerSavedEcho(
            PROVIDER_ALPHA_ID,
            PROVIDER_ALPHA_NAME,
            """{"id":"$MODEL_ALPHA_1","name":"Alpha One","api":"openai-responses",""" +
                """"reasoning":true,"input":["text","image"],""" +
                """"contextWindow":131072,"maxTokens":8192}""",
        )

        val PROVIDER_SAVED_ECHO_FRAME = providerSavedEcho(
            PROVIDER_ALPHA_ID,
            PROVIDER_ALPHA_NAME,
            """{"id":"$MODEL_ALPHA_1","name":"Alpha One","api":"openai-completions",""" +
                """"reasoning":false,"input":["text"],"contextWindow":131072,"maxTokens":8192}""",
        )

        val PROVIDER_SAVED_AFTER_PULL_FRAME = providerSavedEcho(
            PROVIDER_ALPHA_ID,
            PROVIDER_ALPHA_NAME,
            """{"id":"$MODEL_ALPHA_1","name":"Alpha One","api":"openai-completions",""" +
                """"reasoning":false,"input":["text"],"contextWindow":131072,"maxTokens":8192,""" +
                """"thinkingLevelMap":{"medium":"enabled"},"compat":{"supportsStrictMode":true}},""" +
                // alpha-2 是新加行，reasoning 默认 on；echo 必须忠实回显提交值，
            // F12 反证（echoMatchesSubmit 对 reasoning/inputs 恒比对）会拒不符回执。
            """{"id":"$MODEL_ALPHA_2","api":"openai-completions","reasoning":true,""" +
                """"input":["text"],"contextWindow":32768}""",
        )

        val PROVIDER_SAVED_RENAMED_FRAME = providerSavedEcho(
            PROVIDER_ALPHA_ID,
            PROVIDER_ALPHA_RENAMED,
            // echoMatchesSubmit（F12 反证）要求回显模型集与本次 put 提交严格一致：
            // 表单草稿只有 alpha-1（透传保留 thinkingLevelMap/compat），第二个模型只在
            // 后续 models 目录帧里出现。旧 fixture 在这里多回一个 alpha-2，历史上
            // F08 早于该反证所以没暴露；F13 全量设备回归抓出（曾因此表单关不掉）。
            """{"id":"$MODEL_ALPHA_1","name":"Alpha One","api":"openai-completions",""" +
                """"reasoning":false,"input":["text"],"contextWindow":131072,"maxTokens":8192,""" +
                """"thinkingLevelMap":{"medium":"enabled"},"compat":{"supportsStrictMode":true}}""",
        )

        val MODELS_FRAME_RENAMED =
            """{"kind":"models","groups":[{"provider":"$PROVIDER_ALPHA_RENAMED","models":[""" +
                """{"id":"$MODEL_ALPHA_1","name":"Alpha One","contextWindow":131072,"maxTokens":8192,""" +
                """"reasoning":false,"input":["text"]},""" +
                """{"id":"$MODEL_ALPHA_2","name":"Alpha Two","contextWindow":262144,""" +
                """"reasoning":false,"input":["text"]}]}]}"""
    }

    // ==================================================================
    // graph/宿主组装
    // ==================================================================

    /** 连接合成网关（可选注入请求超时）→ 挂真实根 → 进设置 → 注入 providers 帧可选 seed 入口。 */
    private fun connectAndOpenSettings(
        seedEntries: Boolean,
        requestTimeout: Long? = 120_000L,
    ): FakeTransport {
        val transport = FakeTransport()
        // seed：在 graph 启动前写入本地快照（endpoint=首项，连接落点与 UI 标记一致），
        // 行 0 = 备用入口（首行「上移」禁用），行 1 = 连接中入口（带「当前已连接」副行）。
        val prefs = FakePreferences()
        if (seedEntries) {
            runBlocking {
                prefs.update(
                    GatewayPreferencesSnapshot(
                        endpoint = URL_HOME,
                        endpointCandidates = listOf(URL_HOME, URL_TAIL),
                    ),
                )
            }
        }
        val appGraph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = prefs,
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            // fixture 注入需要窗口 >> 模拟器冷启动抖动（F06 教训）；超时用例显式给 800ms
            requestTimeoutOverride = requestTimeout,
        )
        graph = appGraph
        transportRef = transport
        val h = appGraph.stateHolder
        holder = h
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { h.gatewayState.connection.name == GatewayConnectionState.CONNECTED.name }
        }
        compose.setContent {
            // 亮暗由 FineCatApp 内层按 holder.appearanceMode 决定
            FineCatTheme { FineCatApp(stateHolder = h) }
        }
        compose.waitForIdle()
        // 真实入口：首页 → 抽屉 → dbar 设置按钮
        runBlocking { waitUntil { h.snapshot.selectedSessionId == null } }
        var last: Throwable? = null
        var attempts = 0
        while (attempts < 3) {
            attempts++
            try {
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.menu").performClick()
                compose.waitForIdle()
                waitUntilTagPresent("finecat.drawerSearch")
                compose.onNodeWithTag("finecat.drawerSettings").performClick()
                waitUntilTagPresent("finecat.settingsScreen")
                last = null
                break
            } catch (e: Throwable) {
                last = e
                if (compose.onAllNodesWithTag("finecat.drawerSearch").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onRoot().performTouchInput {
                        click(Offset(width * 0.95f, height * 0.5f))
                    }
                    compose.waitForIdle()
                }
            }
        }
        last?.let { throw AssertionError("设置入口 3 次未到达 settingsScreen", it) }
        transport.receive(PROVIDERS_FRAME)
        compose.waitUntil(5_000) { h.providers.size == 2 }
        compose.waitForIdle()
        return transport
    }

    /** 候选列表等待：超时转储当前列表与输入值（定位用，非判据本体）。 */
    private fun candidatesWait(what: String, predicate: () -> Boolean) {
        try {
            compose.waitUntil(5_000, predicate)
        } catch (e: Throwable) {
            val h = holder!!
            val prefs = runCatching { runBlocking { graph!!.preferences.load() } }.getOrNull()
            throw AssertionError(
                "等待超时：$what; candidates=${h.endpointCandidates} prefs=${prefs?.endpointCandidates}",
                e,
            )
        }
    }

    // ==================================================================
    // UI 操作/断言 helper
    // ==================================================================

    private fun waitUntilTagPresent(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntilTagGone(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * 滚到目标下方再点（目标必完整入视口）；用于卡片底部按钮。
     * [belowAnchor] 是同一滚动容器内更下方的锚点，缺省直接用目标自身。
     */
    private fun clickTag(tag: String, belowAnchor: String? = null) {
        scrollTagIntoView(belowAnchor ?: tag)
        compose.waitForIdle()
        val bounds = tagBounds(tag)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        if (bounds.bottom > root.bottom || bounds.top < root.top) {
            // 目标仍不完整：向下多滚一段（真实手势）后再核
            compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
                .performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        assertNodeInsideViewport(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    /** 把 tag 滚进设置页/表单滚动容器可视区（已可见时为 no-op）。 */
    private fun scrollTagIntoView(tag: String) {
        val inEntries = tag.startsWith("finecat.endpoint") || tag == "finecat.gwCard2"
        val inManual = tag.startsWith("finecat.pairing")
        val entriesOpen = compose.onAllNodesWithTag("finecat.gatewayEntriesSheet").fetchSemanticsNodes().isNotEmpty()
        val manualOpen = compose.onAllNodesWithTag("finecat.manualPairingSheet").fetchSemanticsNodes().isNotEmpty()
        if (entriesOpen && !inEntries) {
            compose.onNodeWithTag("finecat.sheetDone").performClick()
            waitUntilTagGone("finecat.gatewayEntriesSheet")
        }
        if (manualOpen && !inManual) {
            compose.onNodeWithTag("finecat.sheetDone").performClick()
            waitUntilTagGone("finecat.manualPairingSheet")
        }
        if (inEntries && !entriesOpen) {
            compose.onNodeWithTag("finecat.gwAddress").performClick()
            waitUntilTagPresent("finecat.gatewayEntriesSheet")
        }
        if (inManual && !manualOpen) {
            compose.onNodeWithTag("finecat.openManualPairing").performScrollTo().performClick()
            waitUntilTagPresent("finecat.manualPairingSheet")
        }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /**
     * 滚动定位 + 等待真实放置（bounds 非零）后再点击：sheet 内容里未滚到的行
     * 可能处于「已组合未放置」态（bounds 全零），此时 performClick 的注入点
     * 是 (0,0)，会误打在 sheet 遮罩上把卡片收起（F08 实测）。
     */
    private fun clickPlaced(tag: String) {
        // 节点级 scrollTo（unmerged 树，F06 同款）：把行滚进视口并完成放置，
        // 显示后整行点击（48dp 行心即命中区）
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    /** 断言节点完整落在窗口内（中心点必在屏内，performClick 才有效，F04 教训）。 */
    private fun assertNodeInsideViewport(tag: String) {
        val bounds = tagBounds(tag)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag 应完整在视口内：bounds=$bounds root=$root",
            bounds.top >= root.top && bounds.bottom <= root.bottom &&
                bounds.left >= root.left && bounds.right <= root.right,
        )
    }

    private fun typeEndpointAndAdd(url: String) {
        scrollTagIntoView("finecat.endpointInput")
        val input = compose.onNodeWithTag("finecat.endpointInput")
        input.performClick()
        // 失败输入可能仍保留：先清空再输入，保证本次提交值精确
        input.performTextClearance()
        input.performTextInput(url)
        scrollTagIntoView("finecat.endpointAdd")
        compose.onNodeWithTag("finecat.endpointAdd").performClick()
        compose.waitForIdle()
    }

    private fun assertEndpointInputText(expected: String) {
        compose.onNodeWithTag("finecat.endpointInput").assert(
            SemanticsMatcher("endpoint input == $expected") { node ->
                node.config.getOrNull(SemanticsProperties.InputText)?.text == expected ||
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
            },
        )
    }

    /** 配对输入值断言：兼容 InputText（新）/EditableText（旧）两代语义属性。 */
    private fun assertPairingInputText(expected: String) {
        compose.onNodeWithTag("finecat.pairingInput").assert(
            SemanticsMatcher("pairing input == $expected") { node ->
                node.config.getOrNull(SemanticsProperties.InputText)?.text == expected ||
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
            },
        )
    }

    private fun assertEditableText(tag: String, expected: String) {
        compose.onNodeWithTag(tag).assert(
            SemanticsMatcher("field $tag == $expected") { node ->
                node.config.getOrNull(SemanticsProperties.InputText)?.text == expected ||
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
            },
        )
    }

    /** 断言「不存在值为 [value] 的 [tag] 节点」且 [tag] 存在（方言行值核验用）。 */
    private fun assertEditableTextNotPresentWithValue(tag: String, value: String) {
        val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        assertTrue("应存在节点 $tag", nodes.isNotEmpty())
        val hit = nodes.any { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == value ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        }
        assertFalse("节点 $tag 不应带值 $value", hit)
    }

    /** 语义树中任何**显示文本**不得包含 [secret]（密码变换渲染断言；EditableText 不算显示）。 */
    private fun assertNoPlainTextNodeContains(secret: String) {
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { part ->
                assertFalse(
                    "密码字段内容不得以明文出现在界面文本中",
                    part.text.contains(secret),
                )
            }
            node.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
    }

    /** 全局错误模态（platformError）出现后的「好」确认。 */
    private fun dismissPlatformErrorDialog() {
        compose.onNodeWithText("好").performClick()
        compose.waitForIdle()
    }

    private fun fillField(tag: String, text: String) {
        val node = compose.onNodeWithTag(tag)
        node.performClick()
        // 非空字段的光标可能落在文本中间：先清空再输入，保证最终值 = text
        node.performTextClearance()
        node.performTextInput(text)
        compose.waitForIdle()
    }

    private fun openEditFormFor(providerId: String) {
        scrollTagIntoView("finecat.providerRow.$providerId")
        compose.onNodeWithTag("finecat.providerRow.$providerId").performClick()
        waitUntilTagPresent("finecat.providerEdit")
    }

    /** Provider 编辑卡内当前模型行 id 列表（行顺序 = 表单顺序；空 id 行记 ""）。 */
    private fun modelRowIds(): List<String> {
        val ids = mutableListOf<String>()
        var index = 0
        while (compose.onAllNodesWithTag("finecat.modelRow.$index").fetchSemanticsNodes().isNotEmpty()) {
            val row = compose.onAllNodesWithTag("finecat.modelRow.$index").fetchSemanticsNodes().first()
            ids += walkTexts(row).firstOrNull { it.startsWith("m-") } ?: ""
            index++
        }
        return ids
    }

    private fun modelRowCount(): Int {
        var count = 0
        while (compose.onAllNodesWithTag("finecat.modelRow.$count").fetchSemanticsNodes().isNotEmpty()) {
            count++
        }
        return count
    }

    private fun walkTexts(node: SemanticsNode): List<String> =
        buildList {
            node.config.getOrNull(SemanticsProperties.Text)?.let { texts -> addAll(texts.map { it.text }) }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
            node.children.forEach { addAll(walkTexts(it)) }
        }

    private fun requireNodeWithTextIn(tag: String, text: String): Rect {
        val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().first()
        return textBoundsInRoot(node, text)
            ?: error("节点 $tag 内未找到文本「$text」；可见文本=${dumpTexts(node)}")
    }

    private fun walkForText(node: SemanticsNode, text: String): Boolean {
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true) {
            return true
        }
        return node.children.any { walkForText(it, text) }
    }

    /** 深度优先找包含 [text] 的语义节点的 bounds；找不到返回 null（诊断信息由调用方转储）。 */
    private fun textBoundsInRoot(root: SemanticsNode, text: String): Rect? {
        if (root.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true) {
            return root.boundsInRoot
        }
        root.children.forEach { child ->
            textBoundsInRoot(child, text)?.let { return it }
        }
        return null
    }

    private fun dumpTexts(node: SemanticsNode): String = buildString {
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { append('「').append(it.text).append('」') }
            n.children.forEach { walk(it) }
        }
        walk(node)
    }

    private fun tagBounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** 卡内第一条发丝线（1dp 高、宽度>卡宽一半的 Box）的 x 起点。 */
    private fun firstDividerXIn(cardTag: String): Float {
        val card = compose.onNodeWithTag(cardTag).fetchSemanticsNode()
        val candidates = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            val b = node.boundsInRoot
            if (b.height <= density * 1.5f && b.width > (card.boundsInRoot.width / 2f)) {
                candidates += node
            }
            node.children.forEach { walk(it) }
        }
        walk(card)
        assertTrue("卡 $cardTag 内未找到发丝线", candidates.isNotEmpty())
        return candidates.minByOrNull { it.boundsInRoot.top }!!.boundsInRoot.left
    }

    private fun pressBackSystem() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_BACK").close()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    /** 点屏幕上部遮罩（sheet 占底部 74%，上部是遮罩）：收起最上层的 sheet。 */
    private fun tapTopScrim() {
        compose.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.06f))
        }
        compose.waitForIdle()
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .bufferedReader().use { it.readText() }
    }

    // ==================================================================
    // 证据落盘（capture 归领：PNG + capture-measure.txt）
    // ==================================================================

    private fun saveShot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        rootBitmap().compress(Bitmap.CompressFormat.PNG, 100, File(dir, name).outputStream())
    }

    private var measureFileReady = false

    private fun saveMeasure(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText(line.trimEnd() + "\n")
    }

    private val density: Float
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private fun rootBitmap(): Bitmap {
        // 软渲染模拟器偶发 capture 失败：重试 3 次（F06 同款）
        var last: Throwable? = null
        repeat(3) {
            runCatching {
                return compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            }.onFailure { last = it }
            runCatching {
                val decor = compose.activity.window.decorView
                val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(full))
                val top = decor.rootWindowInsets?.systemWindowInsetTop ?: 0
                return if (top > 0) {
                    Bitmap.createBitmap(full, 0, top, full.width, full.height - top)
                } else {
                    full
                }
            }.onFailure { last = it }
            Thread.sleep(200)
            compose.waitForIdle()
        }
        throw AssertionError("rootBitmap 三次采集均失败", last)
    }

    // ==================================================================
    // 矩阵设备配置（F06 同款：注入 + 轮询读回生效 + finally 恢复读回断言）
    // ==================================================================

    private fun applyDeviceConfig(densityDpi: Int, fontScale: String?) {
        shell("wm density $densityDpi")
        if (fontScale != null) shell("settings put system font_scale $fontScale")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("wm density").contains("Override density: $densityDpi")) break
            Thread.sleep(200)
        }
        Thread.sleep(500)
        compose.waitForIdle()
    }

    private fun restoreDeviceConfig() {
        shell("wm density 480")
        shell("settings put system font_scale 1.0")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("wm density").contains("Override density: 480")) break
            Thread.sleep(200)
        }
        val densityOut = shell("wm density")
        val fontScale = shell("settings get system font_scale")
        assertTrue(
            "矩阵步骤结束必须恢复设备配置：density=[$densityOut] fontScale=[$fontScale]",
            densityOut.contains("Override density: 480") && fontScale.trim() == "1.0",
        )
    }
}
