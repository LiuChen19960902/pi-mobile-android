package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
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
import com.finecat.pimobile.android.waitUntil
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FineCat Provider「协议浮层 + 保存回执」设备测试（合成网关，无真实网络/密钥/prefs）。
 *
 * 宿主形态与 [FineCatProviderRegressionDeviceTest] 同口径并复用其搭法：AndroidAppGraph
 * 全套 override（FakeGatewayFixtures）+ 生产组合路径 `FineCatTheme { FineCatProviderSheet(...) }`，
 * 只点真实生产 UI。三个用例：
 *
 * 1. [modelApiPopover_backClosesPopoverFirst_andProtocolChangeIsVisible]：模型二级卡的
 *    「方言」行点开 FcPopover；Back 先关浮层（二级卡仍在、协议未变），再 Back 才退到一级；
 *    换协议按**可见状态**核验（方言行值文本 + 一级模型行副行），不只看标签存在。
 *    亮/暗两套主题各跑一轮交互并截图（同一组合里翻主题，卡片状态不重置）。
 * 2. [existingProviderRenameOnly_putSentThenGatewayErrorKeepsDraft_thenSavedCloses]：
 *    既有 provider（`status.ok=true`）只改名称——不发 provider-test，直接保存；断言实际
 *    provider-put 帧字段（name 已改、baseUrl 沿用、apiKey 留空不上 wire）后卡片仍在原地；
 *    注入网关**真实失败应答形状**（`{"kind":"error","code":…,"message":…,"requestType":
 *    "provider-put"}`，见网关 router.ts `providerErrorFrame` 与 PROTOCOL「统一错误帧」）后
 *    名称草稿仍在且行内错误可见；再次保存注入 `provider-saved`（带 provider 对象 +
 *    admitted/refreshed，见 holder `handleProviderFrame`）后卡片收起、列表按应答回填新名。
 * 3. [formFieldRows_wholeRowIsRealHitTarget_labelAndPaddingFocusInput]：三个表单输入行（名称 /
 *    Base URL / API Key）的**输入自身语义 bounds** 按实际 density 换算后 ≥48dp（不拿父 Row
 *    高度充数），并用真实 touch 点 label 区与上、下留白，确认整行都是命中区、点完能写回。
 *
 * 回归来源：Runtime 将 error 帧转为 `RequestRejected` 后不再发布 Frame 事件，
 * 因此只在 `handleProviderFrame("error")` 释放 loading 并不足够。门面现已在拒绝终态
 * 释放保存状态并写入行内结果；本用例通过真实帧验证这条链路，防止回退到永久等待。
 * UI 只展示稳定错误码，不回显可能含敏感值的上游原始 message。
 *
 * 依赖的生产 testTag：finecat.providerSheet / providerNew / providerEdit / providerRow.<id> /
 * addModel / modelDetail / modelRow.<i> / modelId / modelContext / modelApiRow /
 * modelApi.<api> / formName / formError。浮层是独立 Popup 窗口，宿主窗口截图看不到它，
 * 浮层叠加证据另用 screencap 取全屏。
 *
 * 截图只含合成数据（example 域名/ID、API Key 一律留空），落在
 * `external-files/finecat-provider-repair`；每个用例独立 graph/holder，@After 全量 dispose
 * （holder.close + 两个 scope cancel）。
 */
@RunWith(AndroidJUnit4::class)
class FineCatProviderSaveDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null

    /** 主题开关：在同一组合里换 LocalFineCatColors，不重开 content、不清卡片内部状态。 */
    private var dark by mutableStateOf(false)

    @After
    fun tearDown() {
        holder?.close()
        graph?.applicationScope?.cancel()
        graph?.gatewayScope?.cancel()
        holder = null
        graph = null
    }

    /** 建合成网关 graph、连上（Open + hello → CONNECTED）并挂生产 Provider sheet。 */
    private fun connectAndShowProviderSheet(): FakeTransport {
        val transport = FakeTransport()
        val appGraph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        graph = appGraph
        val stateHolder = appGraph.stateHolder
        holder = stateHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() } // runtime 自动发起连接
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3}""")
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
        }
        compose.setContent {
            FineCatTheme(darkTheme = dark) {
                FineCatProviderSheet(stateHolder = stateHolder, onDismiss = {})
            }
        }
        compose.waitForIdle()
        return transport
    }

    // ---- 工具：等待、滚动、Back、截图 ----

    private fun waitUntilTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntilTagGone(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    /** 把 tag 滚进它所属 sheet 的可视区（已可见时为 no-op）。 */
    private fun scrollTagIntoView(tag: String) {
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
            .performScrollToNode(hasTestTag(tag))
    }

    /**
     * 系统级 Back：路由到当前焦点窗口——焦点在 focusable Popup 上时先由浮层消费，
     * 否则由 Sheet 的 BackHandler 处理，与真实按键一致。
     */
    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_BACK").close()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "finecat-provider-repair").apply { mkdirs() }
    }

    /**
     * 宿主窗口截图（Sheet 在宿主窗口里；浮层另走 [screencap]），返回卡面底色采样像素，
     * 供亮暗对比断言；surface 瞬时不可用时重试。
     */
    private fun shoot(name: String, hostTag: String): Int {
        var last: Exception? = null
        repeat(4) {
            try {
                val bitmap = compose.onNodeWithTag(hostTag).captureToImage().asAndroidBitmap()
                File(evidenceDir(), "$name.png").outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                return bitmap.getPixel(bitmap.width / 2, bitmap.height * 9 / 10)
            } catch (e: IllegalArgumentException) {
                last = e
                Thread.sleep(300)
                compose.waitForIdle()
            }
        }
        throw IllegalStateException("capture $name failed", last)
    }

    /** 全屏截图（含 Popup 独立窗口）到 /data/local/tmp（shell 可写，应用外部目录不可写）。 */
    private fun screencap(fileName: String) {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("screencap -p /data/local/tmp/$fileName").close()
            Thread.sleep(200)
        }
    }

    // ---- 用例 1：协议浮层的层级与换协议的可见状态（亮 + 暗） ----

    /** 新增 Provider → 手动添加模型 → 二级卡填 ID/上下文，停在二级卡。 */
    private fun openNewModelDetailWithValues(modelId: String, context: String) {
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTag("finecat.providerEdit")
        scrollTagIntoView("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTag("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput(modelId)
        scrollTagIntoView("finecat.modelContext")
        compose.onNodeWithTag("finecat.modelContext").performTextInput(context)
        scrollTagIntoView("finecat.modelApiRow")
    }

    /** 从一级卡重新点开第 0 个模型行（上一轮已配好的行，不重建表单）。 */
    private fun reopenModelDetail() {
        scrollTagIntoView("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTag("finecat.modelDetail")
        scrollTagIntoView("finecat.modelApiRow")
    }

    /**
     * 一级卡里模型行的副行（`协议 · 上下文`）——换协议是否真的落到表单状态，看这行文本。
     * 断言用整串精确匹配，不做「标签存在」这种弱检查。
     */
    private fun modelRowSub(api: String) = "$api · 上下文 128K"

    /**
     * 在**已打开**的二级模型卡上跑一轮浮层交互：开浮层 → Back 只关浮层 → 选协议 →
     * 二级卡仍在且协议值已换 → Back 退一级且一级副行随之改变。
     * 返回末张一级卡截图的底色采样像素（供亮暗对比）。
     */
    private fun runPopoverFlow(prefix: String, fromApi: String, toApi: String): Int {
        val toTag = "finecat.modelApi.$toApi"
        // 起点：卡里可见的是旧协议
        compose.onNodeWithText(fromApi).assertExists()
        compose.onNodeWithText(toApi).assertDoesNotExist()

        // 1) 点「方言」行 → FcPopover 打开（浮层悬在二级卡上）
        compose.onNodeWithTag("finecat.modelApiRow").performClick()
        waitUntilTag(toTag)
        shoot("$prefix-model-detail", "finecat.modelDetail")
        screencap("$prefix-popover-open.png")

        // 2) Back 先关浮层：二级卡仍在，协议没被动过
        pressBack()
        waitUntilTagGone(toTag)
        compose.onNodeWithTag("finecat.modelDetail").assertExists()
        compose.onNodeWithText(fromApi).assertExists()
        compose.onNodeWithText(toApi).assertDoesNotExist()

        // 3) 再开浮层选不同协议：这一行 compat 为空 → 无需确认，直接应用并关浮层
        compose.onNodeWithTag("finecat.modelApiRow").performClick()
        waitUntilTag(toTag)
        compose.onNodeWithTag(toTag).performClick()
        waitUntilTagGone(toTag)
        compose.onNodeWithTag("finecat.modelDetail").assertExists()
        compose.onNodeWithText(toApi).assertExists()
        compose.onNodeWithText(fromApi).assertDoesNotExist()

        // 4) 再 Back 才退到一级卡；换协议随表单落到一级模型行副行
        pressBack()
        waitUntilTagGone("finecat.modelDetail")
        compose.onNodeWithTag("finecat.providerEdit").assertExists()
        compose.onNodeWithText(modelRowSub(toApi)).assertExists()
        compose.onNodeWithText(modelRowSub(fromApi)).assertDoesNotExist()
        return shoot("$prefix-provider-edit", "finecat.providerEdit")
    }

    @Test
    fun modelApiPopover_backClosesPopoverFirst_andProtocolChangeIsVisible() {
        connectAndShowProviderSheet()

        openNewModelDetailWithValues(modelId = "example-model", context = "128K")
        val lightEditPixel = runPopoverFlow(
            prefix = "10-light",
            fromApi = "openai-completions",
            toApi = "anthropic-messages",
        )

        // 暗色：同一组合里翻主题（卡片状态不重置），上一轮留在一级卡的表单接着跑
        dark = true
        compose.waitForIdle()
        reopenModelDetail()
        val darkEditPixel = runPopoverFlow(
            prefix = "11-dark",
            fromApi = "anthropic-messages",
            toApi = "openai-responses",
        )
        // 截图确实是两套主题（不是同色重名）
        assertNotEquals(lightEditPixel, darkEditPixel)
    }

    // ---- 用例 2：仅改名称的保存链路 ----

    /** 既有 provider 的列表帧：`status.ok=true`（改名称不触发重测门槛）、key 不在本机。 */
    private fun existingProvidersFrame(id: String, name: String): String =
        """{"kind":"providers","providers":[{"id":"$id","name":"$name",""" +
            """"baseUrl":"https://api.example.invalid/v1","apiKeySet":false,"active":true,""" +
            """"models":[{"id":"example-model","api":"openai-completions","reasoning":true,""" +
            """"input":["text"],"contextWindow":131072,"maxTokens":8192}],"status":{"ok":true}}]}"""

    /** 保存成功应答：holder 按 provider 对象回填列表、按「Provider 已保存：<id>」认结论。 */
    private fun providerSavedFrame(id: String, name: String): String =
        """{"kind":"provider-saved","admitted":true,"refreshed":true,"provider":{"id":"$id",""" +
            """"name":"$name","baseUrl":"https://api.example.invalid/v1","apiKeySet":false,""" +
            """"active":true,"models":[{"id":"example-model","api":"openai-completions",""" +
            """"reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192}]}}"""

    @Test
    fun existingProviderRenameOnly_putSentThenGatewayErrorKeepsDraft_thenSavedCloses() {
        val transport = connectAndShowProviderSheet()
        val providerId = "example-existing"
        transport.receive(existingProvidersFrame(providerId, "示例既有"))
        runBlocking { waitUntil { holder!!.providers.isNotEmpty() } }

        compose.onNodeWithTag("finecat.providerRow.$providerId").performClick()
        waitUntilTag("finecat.providerEdit")
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("示例改名")

        // 只改名称：连通性字段一个没动 → 门槛放行，不该逼用户去真跑一次上游测试
        compose.onNodeWithText("保存").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("provider-put").isNotEmpty() } }
        val put = transport.payloadsOfType("provider-put").last()
        assertEquals(providerId, put.getValue("id").jsonPrimitive.content)
        assertEquals("示例改名", put.getValue("name").jsonPrimitive.content)
        assertEquals("https://api.example.invalid/v1", put.getValue("baseUrl").jsonPrimitive.content)
        assertTrue("API Key 留空=保留已存值，不下发", "apiKey" !in put)
        assertTrue("保存路径不该顺带发测试请求", transport.payloadsOfType("provider-test").isEmpty())
        // 发出后不收卡：留在原地等自己这一次的终态
        // 只比 EditableText 输入属性，避免 decorationBox 标签参与值比较
        compose.onNodeWithTag("finecat.providerEdit").assertExists()
        compose.onNodeWithTag("finecat.formName")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("示例改名")))

        // 网关失败应答（统一错误帧：requestType 回指本次请求，才会关联到在途 lane）
        transport.receive(
            """{"kind":"error","code":"bad-request",""" +
                """"message":"synthetic-models-json-write-refused","requestType":"provider-put"}"""
        )
        compose.waitForIdle()

        // 草稿不能丢、卡不能自己关掉
        compose.onNodeWithTag("finecat.formName")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("示例改名")))
        compose.onNodeWithTag("finecat.providerEdit").assertExists()
        // 失败原因必须落在这张卡的行内错误位（不是 4s 就自动消失的全局回执）
        waitUntilTag("finecat.formError")
        // Runtime 只传稳定 code 给 UI，不回显可能含敏感值的上游 message。
        compose.onNodeWithTag("finecat.formError")
            .assertTextContains("bad-request", substring = true)
        shoot("20-light-save-error", "finecat.providerEdit")

        // 修好后原地再存一次：成功应答才收卡
        compose.onNodeWithText("保存").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("provider-put").size >= 2 } }
        transport.receive(providerSavedFrame(providerId, "示例改名"))
        waitUntilTagGone("finecat.providerEdit")
        compose.onNodeWithTag("finecat.providerSheet").assertExists()
        // 列表行名字按应答里的 provider 对象回填
        compose.onNodeWithText("示例改名").assertExists()
        shoot("21-light-list-after-save", "finecat.providerSheet")
    }

    // ---- 用例 3：表单字段整行是真实命中区 ----

    /** 用真实 touch 点该行内给定分数坐标（相对输入语义 bounds 左上角，px），不用语义动作。 */
    private fun tapInRow(tag: String, xFraction: Float, yFraction: Float) {
        compose.onNodeWithTag(tag).performTouchInput {
            click(Offset(width * xFraction, height * yFraction))
        }
        compose.waitForIdle()
    }

    /** 输入语义 bounds 的高度，换算成 dp（boundsInRoot 是 px，除以实际 density）。 */
    private fun inputHeightDp(tag: String): Float {
        scrollTagIntoView(tag)
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return bounds.height / compose.density.density
    }

    /**
     * 表单字段行的命中回归：只看**输入自身**的语义节点（tag 就打在 BasicTextField 上），
     * 所以父 Row 高度不能充数。
     *
     * - 三行 bounds 高度 ≥48dp；
     * - 堆叠标签（0.08 宽/0.1 高）、输入框上留白（0.55 宽/0.42 高）、
     *   输入框下留白（0.55 宽/0.88 高）三个点都走真实
     *   touch；每次先把焦点转到**另一行**、确认目标行当前没焦点，再点回来断言取得焦点，
     *   避免「本来就有焦点」的空转。
     * - 点完最后一击焦点应还在这行上，此时普通输入能写回（API Key 不填任何值，只验 Password
     *   语义，fixture 里也没有真实凭据）。
     */
    @Test
    fun formFieldRows_wholeRowIsRealHitTarget_labelAndPaddingFocusInput() {
        connectAndShowProviderSheet()
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTag("finecat.providerEdit")

        val fields = listOf("finecat.formName", "finecat.formBaseUrl", "finecat.formApiKey")
        fields.forEach { tag ->
            val heightDp = inputHeightDp(tag)
            assertTrue("$tag 输入语义高度 ${heightDp}dp 不足 48dp", heightDp >= 48f)
        }

        fields.forEachIndexed { index, tag ->
            val partner = fields[(index + 1) % fields.size]
            listOf(0.08f to 0.1f, 0.55f to 0.42f, 0.55f to 0.88f).forEach { (xF, yF) ->
                // 转焦：先让另一行拿到焦点（点它值列中心），确认目标行此刻未聚焦
                tapInRow(partner, 0.55f, 0.5f)
                compose.onNodeWithTag(partner).assertIsFocused()
                compose.onNodeWithTag(tag).assertIsNotFocused()
                // 再真实点击目标行的 label 区 / 上下留白，必须把焦点带到这一行的输入上
                tapInRow(tag, xF, yF)
                compose.onNodeWithTag(tag).assertIsFocused()
            }
            if (tag != "finecat.formApiKey") {
                // 上一击留下的焦点要能把键入写回这一行
                compose.onNodeWithTag(tag).performTextInput("ok")
                compose.onNodeWithTag(tag).assertTextContains("ok", substring = true)
                compose.onNodeWithTag(tag).performTextClearance()
            }
        }

        // 密码行保持掩码语义：只验语义节点，不填任何值、不落明文截图/日志
        compose.onNodeWithTag("finecat.formApiKey")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }
}
