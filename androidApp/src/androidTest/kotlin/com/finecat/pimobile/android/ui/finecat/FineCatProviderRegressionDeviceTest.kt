package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FineCat Provider 弹卡回归（设备测试，合成网关，无真实网络/密钥/prefs）。
 *
 * 宿主形态：AndroidAppGraph 全套 override（复用 FakeGatewayFixtures，与
 * AndroidAppGraphFakeIntegrationDeviceTest 同口径）+ 生产组合路径
 * `FineCatTheme { FineCatProviderSheet(...) }`。只点真实生产 UI，不做替身表单。
 *
 * 覆盖（三个无网络用例）：
 * 1. 手动添加 → 模型二级卡 → 输入 ID/上下文/最大输出 → 返回保留；
 * 2. 非法 K 值（"12x"）：既有 provider（status.ok=true，指纹不含数值字段）保存时
 *    行内可见错误，不发 provider-put，表单不关闭；
 * 3. 测试连接失败（config）：阻止保存（断言实际 provider-test 请求帧字段 +
 *    无 provider-put + 门槛错误可见）。
 *
 * 明确未覆盖（等 Provider 按 reviewer 修复后补）：
 * - 编辑字段后测试结论失效、二次测试请求帧与 provider-put 保存全链路；
 * - 模型 API 选择与浮层/Back 层级（chips 将改 popover，本测试不绑定 chips）；
 * - 拉取模型合并、删除二次确认、transient 结论不授予新保存通行证。
 *
 * 依赖的生产 testTag（refactor 期间被替换布局时需同步更新本文件）：
 * finecat.providerNew / providerRow.<id> / providerEdit / addModel / modelRow.<i> /
 * modelDetail / sheetBack / modelId / modelContext / modelMax / formId / formBaseUrl /
 * formApiKey / providerTest / providerTestResult / formError。
 *
 * 每个用例独立 graph/holder，@After 全量 dispose（holder.close + 两个 scope cancel）；
 * 全程仅合成数据（FakePreferences/FakeCredentials/帧内假值），无截图。
 */
@RunWith(AndroidJUnit4::class)
class FineCatProviderRegressionDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null

    @After
    fun tearDown() {
        holder?.close()
        graph?.applicationScope?.cancel()
        graph?.gatewayScope?.cancel()
        holder = null
        graph = null
    }

    /** 建合成网关 graph、连上（Open + hello → CONNECTED）并挂生产 Provider sheet。 */
    private fun connectAndShowProviderSheet(modelBaseUrl: Boolean = false): FakeTransport {
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
            transport.receive(if (modelBaseUrl) """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["provider-model-base-url-v1","provider-test-id-v1","provider-test","provider-put","providers","provider-models","sessions"]}"""
                else """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["provider-test-id-v1","provider-test","provider-put","providers","provider-models","sessions"]}""")
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
        }
        compose.setContent {
            FineCatTheme {
                FineCatProviderSheet(stateHolder = stateHolder, onDismiss = {})
            }
        }
        return transport
    }

    // ---- 工具：标签出现/消失等待、sheet 内滚动定位（防止按钮落在折叠线下方） ----

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

    /** 在包含该 tag 的那张 sheet 的滚动列里把它滚进可视区（已可见时为 no-op）。 */
    private fun scrollTagIntoView(tag: String) {
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
            .performScrollToNode(hasTestTag(tag))
    }

    private fun openNewProviderEdit() {
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTag("finecat.providerEdit")
    }

    private fun openModelDetailAndBack(modelId: String, context: String?, max: String?) {
        scrollTagIntoView("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTag("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput(modelId)
        if (context != null) {
            scrollTagIntoView("finecat.modelContext")
            compose.onNodeWithTag("finecat.modelContext").performTextInput(context)
        }
        if (max != null) {
            scrollTagIntoView("finecat.modelMax")
            compose.onNodeWithTag("finecat.modelMax").performTextInput(max)
        }
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
    }

    // ---- 用例 1：手动添加 → 模型二级卡 → 输入 → 返回保留 ----

    @Test
    fun manualAdd_modelDetailInputs_backRetainsValues() {
        connectAndShowProviderSheet()
        openNewProviderEdit()

        openModelDetailAndBack(modelId = "reg-model", context = "128K", max = "8K")

        // 返回后模型行显示ID/协议；容量在重新打开的详情里逐字核对。
        compose.onNodeWithText("reg-model").assertExists()
        compose.onNodeWithTag("finecat.modelRow.0").assertTextContains("openai-completions", substring = true)

        // 重新点开二级卡：三个字段的输入值原样保留（断言生产输入值，非 assertExists）
        // 只比 EditableText 输入属性，避免 decorationBox 标签参与值比较
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTag("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("reg-model")))
        compose.onNodeWithTag("finecat.modelContext")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("128K")))
        compose.onNodeWithTag("finecat.modelMax")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("8K")))
    }

    @Test
    fun modelDetail_inheritsUrl_andKeepsCompatActionsBehindDetails() {
        val transport = connectAndShowProviderSheet(modelBaseUrl = true)
        transport.receive("""{"kind":"providers","providers":[{"id":"model-style-p","baseUrl":"https://provider.example/v1","models":[{"id":"model-style","api":"openai-completions","baseUrl":"https://model.example/custom/v2","contextWindow":500000,"maxTokens":275000,"compat":{"supportsStore":true}}]}]}""")
        runBlocking { waitUntil { holder!!.providers.any { it.id == "model-style-p" } } }
        compose.onNodeWithTag("finecat.providerRow.model-style-p").performClick()
        waitUntilTag("finecat.providerEdit")
        scrollTagIntoView("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTag("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelReadOnlyId").assertTextContains("model-style", substring = true)
        assertTrue(compose.onAllNodesWithTag("finecat.modelId").fetchSemanticsNodes().isEmpty())
        val headerTop = compose.onNodeWithTag("finecat.sheetDone").fetchSemanticsNode().boundsInRoot.top
        assertTrue(compose.onAllNodesWithTag("finecat.modelEffectiveUrl").fetchSemanticsNodes().isEmpty())
        scrollTagIntoView("finecat.modelEffectiveUrlToggle")
        compose.onNodeWithTag("finecat.modelEffectiveUrlToggle").performClick()
        compose.onNodeWithTag("finecat.modelEffectiveUrl").assertTextContains("https://model.example/custom/v2", substring = true)
        compose.onNodeWithTag("finecat.modelBaseUrlInherit").performClick()
        compose.onNodeWithTag("finecat.modelBaseUrl")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("finecat.modelEffectiveUrl").assertTextContains("https://provider.example/v1", substring = true)
        compose.onNodeWithTag("finecat.modelBaseUrlInherit").assertIsNotEnabled()
        scrollTagIntoView("finecat.compatHeader")
        compose.onNodeWithTag("finecat.compatHeader").performClick()
        compose.onNodeWithTag("finecat.compatCount").assertTextContains("已设置 1 项")
        assertTrue(compose.onAllNodesWithTag("finecat.compatHint").fetchSemanticsNodes().isEmpty())
        scrollTagIntoView("finecat.compatHelpToggle")
        compose.onNodeWithTag("finecat.compatHelpToggle").performClick()
        scrollTagIntoView("finecat.compatHint")
        compose.onNodeWithTag("finecat.compatHint").assertTextContains("手机管理的键", substring = true)
        scrollTagIntoView("finecat.compatClearAll")
        compose.onNodeWithTag("finecat.compatClearAll").performClick()
        scrollTagIntoView("finecat.compatCount")
        compose.onNodeWithTag("finecat.compatCount").assertTextContains("已设置 0 项")
        compose.onNodeWithTag("finecat.sheetDone").assertIsDisplayed()
        assertEquals(headerTop, compose.onNodeWithTag("finecat.sheetDone").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        waitUntilTagGone("finecat.modelDetail")
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
        scrollTagIntoView("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTag("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelBaseUrl")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    // ---- 用例 2：非法 K 值保存 → 行内可见错误，不发 provider-put，表单不关 ----

    @Test
    fun invalidContextWindow_saveShowsVisibleError_andBlocksProviderPut() {
        val transport = connectAndShowProviderSheet()

        // 既有 provider（status.ok=true）：不改连通性字段时门槛放行，保存走到数值解析
        val providerId = "reg-existing"
        transport.receive(
            """{"kind":"providers","providers":[{"id":"$providerId","name":"回归既有","baseUrl":"https://existing.example/v1","apiKeySet":true,"active":true,"models":[{"id":"m1","api":"openai-completions","reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192}],"status":{"ok":true}}]}"""
        )
        runBlocking { waitUntil { holder!!.providers.isNotEmpty() } }

        compose.onNodeWithTag("finecat.providerRow.$providerId").performClick()
        waitUntilTag("finecat.providerEdit")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTag("finecat.modelDetail")

        // 旧二进制数回显原始整数；详情完成与Provider保存都必须拦截非法值。
        compose.onNodeWithTag("finecat.modelContext")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("131072")))
        compose.onNodeWithTag("finecat.modelContext").performTextClearance()
        compose.onNodeWithTag("finecat.modelContext").performTextInput("375l")
        val putCountBeforeOutside = transport.payloadsOfType("provider-put").size
        val cardTop = compose.onNodeWithTag("finecat.sheetCard").fetchSemanticsNode().boundsInRoot.top
        assertTrue("详情卡上方应有可点遮罩", cardTop > 0f)
        compose.onNodeWithTag("finecat.sheetScrim").performTouchInput { click(Offset(width / 2f, cardTop / 2f)) }
        waitUntilTag("finecat.modelDetailError")
        compose.onNodeWithTag("finecat.modelDetail").assertExists()
        compose.onNodeWithTag("finecat.modelContext")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("375l")))
        assertEquals("外点非法详情不应写宿主", putCountBeforeOutside, transport.payloadsOfType("provider-put").size)
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        waitUntilTag("finecat.modelDetailError")
        compose.onNodeWithTag("finecat.modelDetailError").assertTextContains("上下文窗口", substring = true)
        compose.onNodeWithTag("finecat.modelDetail").assertExists()
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")

        compose.onNodeWithText("保存").performClick()
        waitUntilTag("finecat.formError")
        compose.onNodeWithTag("finecat.formError")
            .assertTextContains("m1", substring = true)
            .assertTextContains("上下文窗口", substring = true)
        compose.waitForIdle()
        // 校验失败必须拦在本地：不发 provider-put，编辑卡不关闭
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
        assertTrue(
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty()
        )
    }

    // ---- 用例 3：测试连接失败（config）→ 阻止保存，无 provider-put ----

    @Test
    fun failedProviderTest_blocksSave_withoutProviderPut() {
        val transport = connectAndShowProviderSheet()
        openNewProviderEdit()

        compose.onNodeWithTag("finecat.formId").performTextInput("reg-provider")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://reg.example/v1")
        compose.onNodeWithTag("finecat.formApiKey").performTextInput("sk-synthetic-only")
        openModelDetailAndBack(modelId = "reg-model", context = null, max = null)

        // 实际请求帧断言：表单值与第一个有效模型/协议逐字下发
        scrollTagIntoView("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("provider-test").isNotEmpty() } }
        val request = transport.payloadsOfType("provider-test").last()
        assertEquals("reg-provider", request.getValue("id").jsonPrimitive.content)
        assertEquals("https://reg.example/v1", request.getValue("baseUrl").jsonPrimitive.content)
        assertEquals("sk-synthetic-only", request.getValue("apiKey").jsonPrimitive.content)
        assertEquals("reg-model", request.getValue("model").jsonPrimitive.content)
        assertEquals("openai-completions", request.getValue("api").jsonPrimitive.content)

        // 网关结论：配置类失败（failureKind=config → 不可保存）
        transport.receive(
            """{"kind":"provider-test-result","providerTestId":"${transport.payloadsOfType("provider-test").last().getValue("providerTestId").jsonPrimitive.content}","ok":false,"code":"auth-rejected","httpStatus":401,"error":"synthetic-upstream-refused","failureKind":"config","id":"reg-provider"}"""
        )
        waitUntilTag("finecat.providerTestResult")
        compose.onNodeWithTag("finecat.providerTestResult")
            .assertTextContains("均未通过", substring = true).performClick()
        scrollTagIntoView("finecat.providerTestDetail")
        compose.onNodeWithTag("finecat.providerTestDetail")
            .assert(hasAnyDescendant(hasText("认证被拒", substring = true)))

        compose.onNodeWithText("保存").performClick()
        waitUntilTag("finecat.formError")
        compose.onNodeWithTag("finecat.formError")
            .assertTextContains("请先测试 reg-model", substring = true)
        compose.waitForIdle()
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
        assertTrue(
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty()
        )
    }

    // ---- 用例 4：DEC-F09-DIALECT 契约：Provider 表单不提供 provider 级 api 控件，保存不发 provider 级 api ----

    @Test
    fun decF09Dialect_providerSheetHasNoProviderApiRow_andSaveDoesNotCarryProviderApi() {
        val transport = connectAndShowProviderSheet()
        val providerId = "reg-existing"
        transport.receive(
            """{"kind":"providers","providers":[{"id":"$providerId","name":"回归既有","baseUrl":"https://existing.example/v1","apiKeySet":true,"active":true,"models":[{"id":"m1","api":"openai-completions","reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192}],"status":{"ok":true}}]}"""
        )
        runBlocking { waitUntil { holder!!.providers.isNotEmpty() } }

        compose.onNodeWithTag("finecat.providerRow.$providerId").performClick()
        waitUntilTag("finecat.providerEdit")

        // 1. 负向断言：Provider 表单没有 provider 级 api 选项行或浮层（严格遵循 DEC-F09-DIALECT）
        assertTrue(compose.onAllNodesWithTag("finecat.providerApiRow").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("finecat.providerApiPopover").fetchSemanticsNodes().isEmpty())

        // 2. 未改动不可提交；仅改名称后，保存仍不携带 provider 级 api。
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("finecat.formId").assertTextContains("ID $providerId · 不可修改")
        compose.onNodeWithTag("finecat.formName").performTextInput(" · 新名称")
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithText("保存").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("provider-put").isNotEmpty() } }
        val putPayload = transport.payloadsOfType("provider-put").last()
        assertEquals(providerId, putPayload.getValue("id").jsonPrimitive.content)
        // 关键断言：wire 载荷顶层不包含 "api"（DEC-F09-DIALECT / PROTOCOL.md §4.17：仅在 models[i].api 显式下发）
        org.junit.Assert.assertFalse("provider-put 不得携带 provider 级 api 字段", putPayload.containsKey("api"))
    }

    @Test
    fun longProviderForm_keepsSaveVisible_andOnlyViewOrderIsNotDirty() {
        val transport = connectAndShowProviderSheet()
        val models = (1..12).joinToString(",") { """{"id":"model-$it","api":"openai-completions","contextWindow":200000,"maxTokens":8192}""" }
        val url = "https://relay.example/long-path-for-provider/connections/v1"
        transport.receive("""{"kind":"providers","providers":[{"id":"long","name":"Long provider","baseUrl":"$url","apiKeySet":true,"models":[$models],"status":{"ok":true}}]}""")
        runBlocking { waitUntil { holder!!.providers.isNotEmpty() } }
        compose.onNodeWithTag("finecat.providerRow.long").performClick()
        waitUntilTag("finecat.providerEdit")
        compose.onNodeWithTag("finecat.formBaseUrl")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(url)))
        val save = compose.onNodeWithTag("finecat.sheetDone")
        save.assertIsNotEnabled()
        val headerTop = save.fetchSemanticsNode().boundsInRoot.top
        scrollTagIntoView("finecat.modelMoveUp.11")
        compose.onNodeWithTag("finecat.modelMoveUp.11").performClick()
        save.assertIsDisplayed().assertIsNotEnabled()
        assertEquals(headerTop, save.fetchSemanticsNode().boundsInRoot.top, 1f)
        scrollTagIntoView("finecat.formName")
        compose.onNodeWithTag("finecat.formName").performTextInput(" changed")
        save.assertIsEnabled()
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
    }
}

