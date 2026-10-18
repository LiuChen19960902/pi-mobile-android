package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
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
import com.finecat.pimobile.shared.domain.ModelNumberParsing
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
 * 设置页 Provider 直达入口的设备回归。
 *
 * 原型真源（FineCat/prototype/app.js pv-sheet/pv-new）：设置页 Provider 行带 id 直接进
 * 该项编辑表单，「添加 Provider」直接进空新建表单，行与表单之间**没有中间列表层**。
 *
 * 与 F00 取证样例（FineCatProviderDirectEntrySampleDeviceTest，预期失败留证）的差别：
 * 本文件在接线修复后运行，全部 expect=pass；断言从**真实 FineCatApp 根**走
 * 首页空态 → 抽屉 → 设置入口 → 设置整页 → 行点击，不直接挂载孤立表单。
 *
 * 既有回归（FineCatProviderSaveDeviceTest / FineCatProviderRegressionDeviceTest）直接
 * 挂载 FineCatProviderSheet 组件测表单内部逻辑，组件保留，继续有效；本文件只验证
 * FineCatApp 的入口接线与直达行为。
 *
 * 矩阵（390/360dp × fs1.0/1.3 × 亮暗）拆为独立方法：配置注入在 setContent 之前
 * （wm density 520 → 1170px/(520/160) = 360dp），亮暗走产品真实路径
 * updateAppearanceMode（FineCatApp.kt:194 内层主题按它决定，外层包参数会被覆盖），
 * 并对表单卡面像素断言亮/暗，防止「可达但主题错」的假绿。
 * 每方法 finally 恢复 density/font_scale 并读回断言，不留设备全局脏状态。
 * 证据目录：external filesDir 根（capture 按 PNG 归领，文件名见各 saveShot 调用）。
 */
@RunWith(AndroidJUnit4::class)
class FineCatProviderDirectEntryDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var transport: FakeTransport
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var graph: AndroidAppGraph

    /** 迟到回执用例：本类实例内已注入的回执（进快照与失败消息）。 */
    private val receiptLog = mutableListOf<String>()
    private var evidenceFileReady = false

    @Test
    fun newProvider_syncBeforeTest_emptyModelsCannotTestOrSave() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        compose.onNodeWithTag("finecat.providerNew").performScrollTo().performClick()
        waitUntilTagPresent("finecat.providerEdit")
        compose.onNodeWithTag("finecat.formId").performTextInput("new-sync-first")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://models.example.invalid/ bad")
        scrollToInForm("finecat.pullModels")
        compose.onNodeWithTag("finecat.pullModels").assertIsNotEnabled()
        scrollToInForm("finecat.formBaseUrl")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextClearance()
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://models.example.invalid/v1")
        compose.onNodeWithTag("finecat.formApiKey").performTextInput("synthetic-key")
        scrollToInForm("finecat.pullModels")
        compose.onNodeWithTag("finecat.pullModels").assertIsEnabled()
        compose.onNodeWithTag("finecat.providerTest").assertIsNotEnabled()
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.pullModels").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("provider-models").isNotEmpty() } }
        transport.receive("""{"kind":"provider-models-result","id":"new-sync-first","ok":true,"ids":["model-one"]}""")
        waitUntilTagPresent("finecat.modelRow.0")
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").assertIsEnabled()
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        waitUntilTagPresent("finecat.formError")
        assertTrue(transport.payloadsOfType("provider-test").isEmpty())
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
    }

    @Test
    fun offlineCachedSettings_blocksProviderAndScopeEntries_withoutRequests() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        transport.fail()
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "FAILED" }
        val before = configurationRequestCount()
        compose.onNodeWithTag("finecat.modelConfigOffline").assertIsDisplayed()
        listOf("finecat.modelScopeEntry", "finecat.providerRow.$PROVIDER_A_ID", "finecat.providerNew").forEach { tag ->
            val entry = compose.onNodeWithTag(tag)
            entry.performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        }
        assertTrue(compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("finecat.modelScopeSheet").fetchSemanticsNodes().isEmpty())
        assertEquals(before, configurationRequestCount())
        reconnectAfterOffline()
        compose.onNodeWithTag("finecat.providerRow.$PROVIDER_A_ID").assertIsEnabled()
        compose.onNodeWithTag("finecat.modelScopeEntry").assertIsEnabled()
    }

    @Test
    fun saveValidation_hidesOffline_andRecomputesForCurrentDraftAfterReconnect() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formBaseUrl").performTextClearance()
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://offline-gate.example.invalid/v1")
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formError").assertTextContains("请先测试连接")

        transport.fail()
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "FAILED" }
        waitUntilTagGone("finecat.formError")
        compose.onNodeWithText("网关未连接，草稿已保留。").assertIsDisplayed()
        compose.onNodeWithTag("finecat.sheetDone").assertIsNotEnabled()
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").assertIsNotEnabled()
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
        assertTrue(transport.payloadsOfType("provider-test").isEmpty())

        reconnectAfterOffline()
        answerPendingReads()
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formError").assertTextContains("请先测试连接")
        scrollToInForm("finecat.formBaseUrl")
        compose.onNodeWithTag("finecat.formBaseUrl").assertTextContains("https://offline-gate.example.invalid/v1")
        scrollToInForm("finecat.modelRemove.0")
        compose.onNodeWithTag("finecat.modelRemove.0").performClick()
        compose.onNodeWithTag("finecat.formError").assertTextContains("请先同步模型或手动添加模型")
        assertTrue("本地删除和连接恢复不能自动提交", transport.payloadsOfType("provider-put").isEmpty())
        assertTrue("本地模型行删除不能直接删除宿主Provider", transport.payloadsOfType("provider-delete").isEmpty())
    }

    @Test
    fun saveUnconfirmed_staysVisibleAlongsideOfflineDraftNotice() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        openSettingsViaRealEntry()
        fillNewProviderFormAndPassTestGate("离线未确认保留", "128K")
        saveAndAwaitPut(1)
        transport.fail()
        compose.waitUntil(5_000) {
            holder.providerSaveTerminal?.status == AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed
        }
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formError").assertTextContains("未确认")
        compose.onNodeWithText("网关未连接，草稿已保留。").assertIsDisplayed()
        compose.onNodeWithTag("finecat.sheetDone").assertIsNotEnabled()
        compose.onNodeWithTag("finecat.formName").assertTextContains("离线未确认保留")
        assertEquals("断网后没有自动重提保存", 1, transport.payloadsOfType("provider-put").size)
    }

    @Test
    fun providerDraft_survivesDisconnectAndReloadEmptyList_thenReconnect() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("离线保护草稿")
        val inputId = compose.onNodeWithTag("finecat.formName").fetchSemanticsNode().id
        transport.fail()
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "FAILED" }
        val before = configurationRequestCount()
        compose.onNodeWithTag("finecat.providerOffline").assertIsDisplayed()
        compose.onNodeWithTag("finecat.sheetDone").assertIsNotEnabled()
        listOf("finecat.modelRow.0", "finecat.addModel", "finecat.pullModels", "finecat.providerTest", "finecat.providerDelete").forEach { tag ->
            scrollToInForm(tag)
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        assertEquals(before, configurationRequestCount())
        reconnectAfterOffline()
        transport.receive("""{"kind":"providers","providers":[]}""")
        compose.waitUntil(5_000) { holder.providers.isEmpty() }
        compose.waitForIdle()
        assertEquals("输入树不能因列表空态重建", inputId, compose.onNodeWithTag("finecat.formName").fetchSemanticsNode().id)
        compose.onNodeWithTag("finecat.formName").assertTextContains("离线保护草稿")
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        scrollToInForm("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").assertIsEnabled().performClick()
        waitUntilTagPresent("finecat.modelDetail")
    }

    @Test
    fun newProviderOfflineClose_reopensWithInputsAndKey_thenOnlineCancelDiscards() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        openSettingsViaRealEntry()
        fillNewProviderFormAndPassTestGate("离线新增草稿", "500K", apiKey = "synthetic-offline-key_Ab9")
        assertTrue("关卡前已有真实测试成功回执", holder.providerTestResult?.ok == true)
        val values = mapOf(
            "finecat.formId" to NEW_SAVE_ID,
            "finecat.formName" to "离线新增草稿",
            "finecat.formBaseUrl" to SAVE_BASE_URL,
            "finecat.formApiKey" to "synthetic-offline-key_Ab9",
        )
        transport.fail()
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "FAILED" }
        val before = configurationRequestCount()
        compose.onNodeWithTag("finecat.providerClose").performClick()
        waitUntilTagGone("finecat.providerEdit")
        val context = holder.endpoint.trim() to NEW_PROVIDER_ID
        assertEquals(values.getValue("finecat.formApiKey"), holder.providerEditorDrafts[context]?.apiKey)
        assertEquals(NEW_SAVE_ID, holder.providerEditorDrafts[context]?.id)
        assertEquals("500K", holder.providerEditorDrafts[context]?.rows?.single()?.contextWindow)
        reconnectAfterOffline()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        compose.onNodeWithTag("finecat.providerNew").performScrollTo().performClick()
        waitUntilTagPresent("finecat.providerEdit")
        values.forEach { (tag, value) ->
            scrollToInForm(tag)
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString(value),
            ))
        }
        assertNull("草稿取回后只由当前卡片持有", holder.providerEditorDrafts[context])
        assertEquals("关闭和恢复不能自动写配置", before, configurationRequestCount())
        scrollToInForm("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        scrollToInForm("finecat.modelContext", hostTag = "finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelContext").assertTextContains("500K")
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        assertTrue("草稿不能恢复旧测试通行证", transport.payloadsOfType("provider-put").isEmpty())
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formError").assertTextContains("请先测试连接")
        compose.onNodeWithTag("finecat.providerClose").performClick()
        waitUntilTagGone("finecat.providerEdit")
        compose.onNodeWithTag("finecat.providerNew").performScrollTo().performClick()
        waitUntilTagPresent("finecat.providerEdit")
        compose.onNodeWithTag("finecat.formId").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(""),
        ))
    }

    @Test
    fun offlineExistingDraft_isScopedToProviderAndGatewayEntry() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("A离线草稿")
        transport.fail()
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "FAILED" }
        val before = configurationRequestCount()
        compose.onNodeWithTag("finecat.providerClose").performClick()
        waitUntilTagGone("finecat.providerEdit")
        reconnectAfterOffline()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        openEditFormFor(PROVIDER_B_ID)
        compose.onNodeWithTag("finecat.formName").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(PROVIDER_B_NAME),
        ))
        compose.onNodeWithTag("finecat.providerClose").performClick()
        waitUntilTagGone("finecat.providerEdit")
        val originalEntry = holder.endpoint
        compose.runOnIdle { holder.endpoint = "ws://other.fixture.invalid:3082/ws/mobile" }
        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formName").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(PROVIDER_A_NAME),
        ))
        compose.onNodeWithTag("finecat.providerClose").performClick()
        waitUntilTagGone("finecat.providerEdit")
        compose.runOnIdle { holder.endpoint = originalEntry }
        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formName").assertTextContains("A离线草稿")
        assertEquals(before, configurationRequestCount())
    }

    @Test
    fun deletedProviderCannotBeRevivedOrConsumeAnOfflineDraft() {
        connectFakeGateway()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        val initial = holder.providers.single { it.id == PROVIDER_A_ID }
        val draft = ProviderEditorDraft(initial, initial.id, "离线草稿", initial.baseUrl.orEmpty(),
            "", FineCatProviderForm.initialRows(initial), emptyList())
        val key = holder.endpoint.trim() to initial.id
        holder.providerEditorDrafts[key] = draft
        val frame = kotlinx.serialization.json.Json.parseToJsonElement(PROVIDERS_AB_FRAME).jsonObject
        val remaining = frame.getValue("providers").jsonArray.filter {
            it.jsonObject.getValue("id").jsonPrimitive.content != initial.id
        }
        transport.receive(JsonObject(frame.toMutableMap().apply {
            this["providers"] = kotlinx.serialization.json.JsonArray(remaining)
        }).toString())
        compose.waitUntil(5_000) { holder.providers.size == 1 }
        val before = configurationRequestCount()
        val showing = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            FineCatTheme {
                if (showing.value) FineCatProviderEditSheet(holder, initial.id, isNew = false,
                    onDismiss = { showing.value = false })
            }
        }
        compose.waitUntil(5_000) { !showing.value }
        assertTrue(compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty())
        assertTrue("缺失的远端对象不应消费离线草稿", holder.providerEditorDrafts[key] === draft)
        assertEquals(before, configurationRequestCount())
    }

    @Test
    fun mountedEditorIdentitySwitch_doesNotReuseAnotherProvidersInputsOrKey() {
        connectFakeGateway()
        transport.receive(PROVIDERS_AB_FRAME)
        compose.waitUntil(5_000) { holder.providers.size == 2 }
        val editing = androidx.compose.runtime.mutableStateOf(PROVIDER_A_ID)
        compose.setContent {
            FineCatTheme {
                FineCatProviderEditSheet(holder, editing.value, isNew = false, onDismiss = {})
            }
        }
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("A未提交内容")
        scrollToInForm("finecat.formApiKey")
        compose.onNodeWithTag("finecat.formApiKey").performTextInput("synthetic-A-secret")
        // 模拟退出动画仍挂载期间，单一编辑slot换为B。
        compose.runOnIdle { editing.value = PROVIDER_B_ID }
        compose.onNodeWithTag("finecat.formName").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(PROVIDER_B_NAME),
        ))
        compose.onNodeWithTag("finecat.formApiKey").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(""),
        ))
        compose.onNodeWithTag("finecat.formApiKey").performTextInput("synthetic-B-secret")
        compose.runOnIdle { holder.endpoint = "ws://different.fixture.invalid:3082/ws/mobile" }
        compose.onNodeWithTag("finecat.formApiKey").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(""),
        ))
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
    }

    @Test
    fun scopeDraft_survivesDisconnect_andSavesSameSelectionAfterReconnect() {
        val hello = """{"kind":"hello","connection":{"state":"connected"},"sessions":[],"contextUsage":null,"model":null,"capabilities":["model-scope-v1","providers","sessions","models"]}"""
        val scope = """{"patterns":["scope-p/first"],"groups":[{"provider":"scope-p","models":[{"id":"first","name":"First"},{"id":"second","name":"Second"}]}],"selected":[{"provider":"scope-p","modelId":"first","thinkingLevel":"high"}],"unmatchedPatterns":[]}"""
        connectFakeGateway(helloFrame = hello)
        setContentFromRealRoot()
        openSettingsViaRealEntry()
        compose.onNodeWithTag("finecat.modelScopeEntry").performClick()
        awaitProviderFrame("model-scope", 1, "读取合成scope")
        transport.fail()
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "FAILED" && !holder.modelScopeIsLoading }
        compose.onNodeWithTag("finecat.modelScopeOffline").assertIsDisplayed()
        reconnectAfterOffline(hello)
        awaitProviderFrame("model-scope", 2, "首读取消后重连自动补读")
        transport.receive("""{"kind":"model-scope","scope":$scope}""")
        waitUntilTagPresent("finecat.modelScope.scope-p.second")
        compose.onNodeWithTag("finecat.modelScope.scope-p.second").performClick().assertIsOn()
        transport.fail()
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "FAILED" }
        val before = configurationRequestCount()
        compose.onNodeWithTag("finecat.modelScopeOffline").assertIsDisplayed()
        compose.onNodeWithTag("finecat.modelScopeSave").assertIsNotEnabled()
        compose.onNodeWithTag("finecat.modelScope.scope-p.second").assertIsOn().assertIsNotEnabled()
        assertEquals(before, configurationRequestCount())
        reconnectAfterOffline(hello)
        compose.onNodeWithTag("finecat.modelScope.scope-p.second").assertIsOn()
        assertEquals("重连不能重载覆盖已有草稿", 2, transport.payloadsOfType("model-scope").size)
        compose.onNodeWithTag("finecat.modelScopeSave").assertIsEnabled().performClick()
        awaitProviderFrame("model-scope-put", 1, "重连后保存scope草稿")
        val models = transport.payloadsOfType("model-scope-put").single().getValue("models").jsonArray
        assertEquals(setOf("first", "second"), models.map { it.jsonObject.getValue("modelId").jsonPrimitive.content }.toSet())
        assertEquals("high", models.first { it.jsonObject.getValue("modelId").jsonPrimitive.content == "first" }.jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
    }

    private fun configurationRequestCount(): Int = listOf(
        "provider-put", "provider-test", "provider-models", "provider-delete", "model-scope", "model-scope-put",
    ).sumOf { transport.payloadsOfType(it).size }

    private fun reconnectAfterOffline(hello: String = HELLO_CONNECTED_FRAME) {
        val openedBefore = transport.specs.size
        compose.runOnIdle { holder.connect() }
        compose.waitUntil(5_000) { transport.specs.size > openedBefore }
        transport.open()
        transport.receive(hello)
        compose.waitUntil(5_000) { holder.gatewayState.connection.name == "CONNECTED" }
    }

    @Test
    fun renameMismatchingReceipt_reportsUnconfirmed_andAllowsRetry() {
        connectFakeGateway()
        setContentFromRealRoot()
        val results = java.util.concurrent.CopyOnWriteArrayList<String?>()
        compose.runOnIdle { holder.saveSessionName("r27-synthetic-session", "新名") { results.add(it) } }
        awaitProviderFrame("name", 1, "合成改名首请求")
        transport.receive("""{"kind":"session-named","sessionId":"r27-synthetic-session","name":"旧名"}""")
        compose.waitUntil(5_000) { results.size == 1 }
        assertTrue("不匹配回执不能报告成功", results[0]?.contains("未确认") == true)
        compose.runOnIdle { holder.saveSessionName("r27-synthetic-session", "新名") { results.add(it) } }
        awaitProviderFrame("name", 2, "不匹配之后可重试")
        transport.receive("""{"kind":"session-named","sessionId":"r27-synthetic-session","name":"新名"}""")
        compose.waitUntil(5_000) { results.size == 2 }
        assertNull("匹配回执才成功", results[1])
        assertTrue("改名回归不得写Provider", transport.payloadsOfType("provider-put").isEmpty())
    }

    @After
    fun tearDown() {
        if (this::graph.isInitialized) {
            graph.stateHolder.close()
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    @Test
    fun batchTest_firstModelFails_secondSucceeds_andFeedbackStaysNearActions() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        transport.receive("""{"kind":"providers","providers":[{"id":"batch-p","baseUrl":"https://batch.example/v1","apiKeySet":true,"models":[{"id":"a","api":"openai-completions"},{"id":"b","api":"openai-responses"},{"id":"c","api":"openai-completions"}]}]}""")
        runBlocking { waitUntil { holder.providers.any { it.id == "batch-p" } } }
        openEditFormFor("batch-p")
        scrollToInForm("finecat.providerTest")
        val syncBounds = compose.onNodeWithTag("finecat.pullModels").fetchSemanticsNode().boundsInRoot
        val testBounds = compose.onNodeWithTag("finecat.providerTest").fetchSemanticsNode().boundsInRoot
        assertEquals("两操作必须同一行", syncBounds.center.y, testBounds.center.y, 1f)
        compose.onNodeWithTag("finecat.pullModels").assertTextContains("同步模型")
        compose.onNodeWithTag("finecat.providerTest").assertTextContains("测试连接")
        assertTrue(compose.onAllNodesWithTag("finecat.saveGate").fetchSemanticsNodes().isEmpty())
        assertTrue("测试前不逐行铺未测试", compose.onAllNodesWithTag("finecat.modelTestStatus.0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("finecat.providerTest").performClick()
        awaitProviderFrame("provider-test", 1, "批次首模型")
        assertEquals("a", transport.payloadsOfType("provider-test").last().getValue("model").jsonPrimitive.content)
        transport.receive("""{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","id":"batch-p","ok":false,"error":"model missing","failureKind":"config","httpStatus":402}""")
        awaitProviderFrame("provider-test", 2, "批次第二模型")
        assertEquals("b", transport.payloadsOfType("provider-test").last().getValue("model").jsonPrimitive.content)
        assertTrue(holder.providerTestIsLoading)
        waitUntilTagPresent("finecat.providerTestProgress")
        compose.onNodeWithTag("finecat.providerTestProgress").assertIsDisplayed()
        transport.receive("""{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","id":"batch-p","ok":true,"latencyMs":12}""")
        compose.waitUntil(5_000) { !holder.providerTestIsLoading }
        waitUntilTagPresent("finecat.providerTestResult")
        compose.onNodeWithTag("finecat.providerTestResult").assertIsDisplayed()
            .assertTextContains("b 测试通过", substring = true)
        val actionBounds = compose.onNodeWithTag("finecat.modelActions").fetchSemanticsNode().boundsInRoot
        val resultBounds = compose.onNodeWithTag("finecat.providerTestResult").fetchSemanticsNode().boundsInRoot
        assertTrue("反馈应紧跟操作行下方", resultBounds.top >= actionBounds.bottom - 1f &&
            resultBounds.top - actionBounds.bottom <= syncBounds.height / 48f * 24f)
        assertTrue("默认详情收起", compose.onAllNodesWithTag("finecat.providerTestAttempt.0").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("finecat.providerTestResult").performClick()
        scrollToInForm("finecat.providerTestAttempt.0")
        compose.onNodeWithTag("finecat.providerTestAttempt.0").assert(hasAnyDescendant(hasText("上游要求付费", substring = true)))
        scrollToInForm("finecat.providerTestDetail")
        compose.onNodeWithTag("finecat.providerTestDetail").assert(hasAnyDescendant(hasText("连接正常", substring = true)))
        scrollToInForm("finecat.providerTestResult")
        compose.onNodeWithTag("finecat.providerTestResult").performClick()
        assertTrue("详情仍可再次收起", compose.onAllNodesWithTag("finecat.providerTestDetail").fetchSemanticsNodes().isEmpty())
        listOf("失败 402", "通过", "未测试").forEachIndexed { index, text ->
            scrollToInForm("finecat.modelRow.$index")
            compose.onNodeWithTag("finecat.modelTestStatus.$index", useUnmergedTree = true).assertTextContains(text)
        }
        assertEquals("首成功后不得请求c", 2, transport.payloadsOfType("provider-test").size)
        assertTrue(compose.onAllNodesWithTag("finecat.saveGate").fetchSemanticsNodes().isEmpty())
        assertTrue("测试不应保存", transport.payloadsOfType("provider-put").isEmpty())
    }

    @Test
    fun outsideSave_waitsOwnReceipt_failureKeepsDraft_andTestGateStillApplies() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive("""{"kind":"providers","providers":[{"id":"$PROVIDER_A_ID","name":"$PROVIDER_A_NAME","baseUrl":"https://outside.example/v1","apiKeySet":true,"models":[{"id":"m","api":"openai-completions"}],"status":{"ok":false}}]}""")
        runBlocking { waitUntil { holder.providers.any { it.id == PROVIDER_A_ID } } }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("外点保存样例")
        dismissEditFormByScrimTap()
        awaitProviderFrame("provider-put", 1, "外点保存")
        compose.onNodeWithTag("finecat.providerEdit").assertExists()
        assertEquals("外点不能重复发保存", 1, transport.payloadsOfType("provider-put").size)
        transport.receive("""{"kind":"error","code":"bad-request","message":"synthetic-write-refused","requestType":"provider-put"}""")
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formName").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("外点保存样例")))
        dismissEditFormByScrimTap()
        awaitProviderFrame("provider-put", 2, "外点重试")
        transport.receive(savedFrameEchoingPut(1))
        waitUntilTagGone("finecat.providerEdit")
        openEditFormFor(PROVIDER_A_ID)
        scrollToInForm("finecat.formBaseUrl")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextClearance()
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://changed.example/v1")
        dismissEditFormByScrimTap()
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.providerEdit").assertExists()
        assertEquals("外点不能绕过连接测试门槛", 2, transport.payloadsOfType("provider-put").size)
    }

    @Test
    fun ordinaryModelEditWithoutPastTest_savesWithoutProbe() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        editModelField("finecat.modelContext", "500K")
        toggleModelSwitch("finecat.modelReasoning")
        saveAndAwaitPut(1)
        assertTrue("普通编辑不能暗中发模型请求", transport.payloadsOfType("provider-test").isEmpty())
        val model = transport.payloadsOfType("provider-put").last().getValue("models").jsonArray.single().jsonObject
        assertEquals("500000", model.getValue("contextWindow").jsonPrimitive.content)
        assertEquals("true", model.getValue("reasoning").jsonPrimitive.content)
    }

    @Test
    fun addedModel_usesItsOwnDetailProbe_beforeProviderSave() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        scrollToInForm("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput("new-model")
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
        compose.onNodeWithText("保存").performClick()
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formError").assertTextContains("请先测试 new-model", substring = true)
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
        scrollToInForm("finecat.modelRow.1")
        compose.onNodeWithTag("finecat.modelRow.1").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        scrollToInForm("finecat.modelTest", hostTag = "finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelTest").performClick()
        awaitProviderFrame("provider-test", 1, "模型详情测试")
        val request = transport.payloadsOfType("provider-test").single()
        assertEquals("new-model", request.getValue("model").jsonPrimitive.content)
        assertEquals("openai-completions", request.getValue("api").jsonPrimitive.content)
        transport.receive("""{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","id":"$PROVIDER_A_ID","ok":true,"httpStatus":200,"latencyMs":9}""")
        compose.waitUntil(5_000) { !holder.providerTestIsLoading }
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
        saveAndAwaitPut(1)
        assertEquals(1, transport.payloadsOfType("provider-test").size)
    }

    @Test
    fun oldGateway_cannotTestConnection_butOrdinaryEditCanSave() {
        connectFakeGateway(helloFrame = HELLO_CONNECTED_FRAME.replace("\"provider-test-id-v1\",", ""))
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        scrollToInForm("finecat.formBaseUrl")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextClearance()
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://changed.example/v1")
        compose.onNodeWithText("保存").performClick()
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formError").assertTextContains("请更新网关后测试连接", substring = true)
        assertTrue(transport.payloadsOfType("provider-put").isEmpty())
        // 公共地址留空保留宿主原值，再改普通字段仍能保存。
        scrollToInForm("finecat.formBaseUrl")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextClearance()
        editModelField("finecat.modelContext", "500K")
        saveAndAwaitPut(1)
        assertTrue(transport.payloadsOfType("provider-test").isEmpty())
    }

    @Test
    fun expiredProbe_cannotCertifyRetriedConnectionInRealRoot() {
        connectFakeGateway(requestTimeoutMillis = 5_000)
        // 只让被测探针超时；设置页自动读请求正常应答，避免无关 lane 先回收连接。
        val readReplies = mapOf(
            "sessions" to """{"kind":"sessions","items":[]}""",
            "models" to """{"kind":"models","models":[]}""",
            "providers" to PROVIDERS_AB_FRAME,
        )
        val responder = graph.gatewayScope.launch {
            val answered = mutableMapOf<String, Int>()
            while (true) {
                readReplies.forEach { (type, frame) ->
                    val count = transport.payloadsOfType(type).size
                    if (count > answered.getOrDefault(type, 0)) {
                        transport.receive(frame)
                        answered[type] = count
                    }
                }
                delay(20)
            }
        }
        try {
            setContentFromRealRoot()
            transport.receive(PROVIDERS_AB_FRAME)
            runBlocking { waitUntil { holder.providers.size == 2 } }
            openSettingsViaRealEntry()
            openEditFormFor(PROVIDER_A_ID)
            scrollToInForm("finecat.providerTest")
            compose.onNodeWithTag("finecat.providerTest").performClick()
            awaitProviderFrame("provider-test", 1, "首轮测试")
            val expiredToken = latestProbeToken()
            compose.waitUntil(8_000) { !holder.providerTestIsLoading }
            scrollToInForm("finecat.formBaseUrl")
            compose.onNodeWithTag("finecat.formBaseUrl").performTextClearance()
            compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://changed.example/v1")
            scrollToInForm("finecat.providerTest")
            compose.onNodeWithTag("finecat.providerTest").performClick()
            awaitProviderFrame("provider-test", 2, "超时后重试")
            val retryToken = latestProbeToken()
            assertFalse(expiredToken == retryToken)
            transport.receive("""{"kind":"provider-test-result","providerTestId":"$expiredToken","id":"$PROVIDER_A_ID","ok":true}""")
            compose.waitForIdle()
            assertTrue(holder.providerTestIsLoading)
            transport.receive("""{"kind":"provider-test-result","providerTestId":"$retryToken","id":"$PROVIDER_A_ID","ok":false,"code":"auth-rejected","httpStatus":401}""")
            compose.waitUntil(5_000) { !holder.providerTestIsLoading }
            compose.onNodeWithText("保存").performClick()
            waitUntilTagPresent("finecat.formError")
            assertTrue("旧成功不能授权重试的新地址", transport.payloadsOfType("provider-put").isEmpty())
        } finally { responder.cancel() }
    }

    @Test
    fun batchTest_inFlightEditDoesNotGrantNewDraftSave() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").performClick()
        awaitProviderFrame("provider-test", 1, "在途编辑前")
        scrollToInForm("finecat.formBaseUrl")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextClearance()
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://changed.example/v1")
        transport.receive("""{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","id":"$PROVIDER_A_ID","ok":true}""")
        compose.waitUntil(5_000) { !holder.providerTestIsLoading }
        scrollToInForm("finecat.providerTestResult")
        compose.onNodeWithTag("finecat.providerTestResult").assertTextContains("连接配置已更改", substring = true)
        compose.onNodeWithText("保存").performClick()
        // submit将validateForSave的门槛错误写入localError，故还应出现formError。
        waitUntilTagPresent("finecat.formError")
        assertTrue("旧回执不得放行修改后草稿", transport.payloadsOfType("provider-put").isEmpty())
    }

    @Test
    fun batchTest_reconnectCancelsWholeBatchAndClearsProgress() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        openEditFormFor(PROVIDER_A_ID)
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").performClick()
        awaitProviderFrame("provider-test", 1, "取消前首模型")
        runBlocking { holder.connect() }
        compose.waitUntil(5_000) { !holder.providerTestIsLoading && holder.providerTestResult != null }
        assertFalse(holder.providerTestResult!!.ok)
        assertTrue(holder.providerTestResult!!.summary.contains("测试未完成"))
        assertNull(holder.providerTestProgress)
        assertEquals("取消不得继续下一模型", 1, transport.payloadsOfType("provider-test").size)
        assertTrue("测试取消不应保存", transport.payloadsOfType("provider-put").isEmpty())
    }

    // ------------------------------------------------------------------
    // 条件 1：A/B 行各点击一次即出现对应编辑表单，目标 ID 严格一致；中间
    // Provider 列表层出现次数为 0；路径从真实 FineCatApp 设置入口进入。
    // ------------------------------------------------------------------
    @Test
    fun directEntry_editRowsAB_eachOpensItsOwnForm_noListLayer() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }

        openSettingsViaRealEntry()
        assertFalse(
            "设置页初始不应有 Provider 列表层（finecat.providerSheet）",
            listLayerPresent(),
        )

        openEditFormFor(PROVIDER_A_ID)
        saveShot("f01-edit-a.png")
        assertEditFormShows(id = PROVIDER_A_ID, name = PROVIDER_A_NAME, baseUrl = PROVIDER_A_BASE_URL)
        assertFalse(
            "点 A 行后不得出现中间 Provider 列表层（原型 pv-sheet：行 → 表单直达）",
            listLayerPresent(),
        )

        dismissEditFormByScrimTap()
        openEditFormFor(PROVIDER_B_ID)
        saveShot("f01-edit-b.png")
        assertEditFormShows(id = PROVIDER_B_ID, name = PROVIDER_B_NAME, baseUrl = PROVIDER_B_BASE_URL)
        assertFalse("点 B 行后同样不得出现中间列表层", listLayerPresent())
    }

    // ------------------------------------------------------------------
    // 条件 2：添加入口直接是空新建表单；未保存不写远端；取消前后配置严格
    // 相等，返回原设置页且保留滚动位置。
    // ------------------------------------------------------------------
    @Test
    fun addEntry_blankForm_noRemoteWrite_cancelRestoresConfigAndScroll() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }

        openSettingsViaRealEntry()
        val before = providersSnapshot()

        // 添加行在列表底部：滚到它再点（入口动作 = 1 次点击）
        compose.onNodeWithTag("finecat.providerNew").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")
        saveShot("f01-add-blank.png")

        // 新建表单字段为空
        assertFieldEmpty("finecat.formId")
        assertFieldEmpty("finecat.formName")
        assertFieldEmpty("finecat.formBaseUrl")
        assertFieldEmpty("finecat.formApiKey")
        // 未保存不写远端：不发保存/测试/删除任何一种写帧
        assertTrue("未保存不得发 provider-put", transport.payloadsOfType("provider-put").isEmpty())
        assertTrue("未保存不得发 provider-test", transport.payloadsOfType("provider-test").isEmpty())
        assertTrue(
            "未保存不得发 provider-delete",
            transport.payloadsOfType("provider-delete").isEmpty(),
        )

        // 点遮罩取消：表单收起，回原设置页
        // 滚动位置保留：取消前 providerNew 被滚进视口，取消后 bounds 应严格不变
        //（与视口相交是弱断言——若设置页本就短到全部可见则恒真，故比对 top 值）
        val boundsBefore = compose.onNodeWithTag("finecat.providerNew")
            .fetchSemanticsNode().boundsInRoot
        pressBack()
        waitUntilTagGone("finecat.providerEdit")
        waitUntilTagPresent("finecat.settingsScreen")
        assertEquals("新增取消前后 providers 配置应严格相等", before, providersSnapshot())
        assertTrue("取消后不得有写帧落地", transport.payloadsOfType("provider-put").isEmpty())
        val newBounds = compose.onNodeWithTag("finecat.providerNew")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "取消后「添加 Provider」行 bounds.top 应与取消前严格一致（滚动位置保留）；" +
                "before=$boundsBefore after=$newBounds",
            boundsBefore.top,
            newBounds.top,
            1f,
        )
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")
        pressBack()
    }

    // ------------------------------------------------------------------
    // 条件 2（编辑取消）：编辑 A 改名后取消，前后配置严格相等，重开表单回
    // 填的仍是原值（草稿未写远端、未持久化）。
    // ------------------------------------------------------------------
    @Test
    fun editCancel_keepsConfigExactly_formReopensWithOriginalValues() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }

        openSettingsViaRealEntry()
        val before = providersSnapshot()

        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("被取消的改名")
        pressBack()
        waitUntilTagGone("finecat.providerEdit")
        waitUntilTagPresent("finecat.settingsScreen")
        saveShot("f01-edit-cancel.png")

        assertEquals("编辑取消前后 providers 配置应严格相等（取消不覆写）", before, providersSnapshot())

        // 重开 A：回填的是取消前的原值，不是草稿值
        openEditFormFor(PROVIDER_A_ID)
        compose.onNodeWithTag("finecat.formName")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(PROVIDER_A_NAME),
                ),
            )
        pressBack()
    }

    // ------------------------------------------------------------------
    // 条件 3：保存等待真实回执（发出后不收卡）；成功回读=提交值；失败输入
    // 不丢；key 留空=保留（put 不带 apiKey）；测试门槛保持（未测试时保存被
    // 挡，连接字段改动须测通，普通参数修改可直接保存）。
    // ------------------------------------------------------------------
    @Test
    fun save_waitsRealReceipt_successReadback_failureKeepsInput_keyOmittedWhenBlank() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }

        openSettingsViaRealEntry()
        compose.onNodeWithTag("finecat.providerNew").performScrollTo()
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")

        val newId = "f01-save"
        compose.onNodeWithTag("finecat.formId").performTextInput(newId)
        compose.onNodeWithTag("finecat.formName").performTextInput("F01 保存样例")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://f01-save.example/v1")
        // 手动添加模型并覆盖默认容量；无元数据也可直接在手机配置。
        scrollToInForm("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput("f01-save-model")
        scrollToInForm("finecat.modelContext", hostTag = "finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelContext").performTextInput("131072")
        // 用卡头的返回箭头关二级卡（Back 会先被 IME 吃掉；sheetBack 在头部，不在滚动容器内）
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
        waitUntilTagPresent("finecat.modelRow.0")

        // 门槛保持：未测试直接保存被挡（文案是「未测试」而非「无模型」），且不发帧
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.formError")
        compose.onNodeWithTag("finecat.formError").assertTextContains("请先测试 f01-save-model", substring = true)
        assertTrue("门槛挡回时不得发 provider-put", transport.payloadsOfType("provider-put").isEmpty())

        // 测试连接：真实请求帧 + 真实应答帧
        // 保存门槛提示会改变滚动内容高度，须重新将按钮置于可见区域。
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").assertIsDisplayed().assertIsEnabled().performClick()
        awaitProviderFrame("provider-test", 1, "点测试连接后")
        val testReq = transport.payloadsOfType("provider-test").last()
        assertEquals(newId, testReq.getValue("id").jsonPrimitive.content)
        transport.receive("""{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","ok":true,"latencyMs":12,"id":"$newId"}""")
        waitUntilTagPresent("finecat.providerTestResult")

        // 测通后改普通参数不应撤销许可，也不应偷偷重测。
        editModelField("finecat.modelContext", "500K")
        assertEquals(1, transport.payloadsOfType("provider-test").size)
        // 保存：发出后不收卡，等自己的终态
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 1, "点保存后")
        val put = transport.payloadsOfType("provider-put").last()
        assertEquals(newId, put.getValue("id").jsonPrimitive.content)
        assertEquals("F01 保存样例", put.getValue("name").jsonPrimitive.content)
        assertEquals("https://f01-save.example/v1", put.getValue("baseUrl").jsonPrimitive.content)
        assertTrue("API Key 留空=保留，put 不带 apiKey", "apiKey" !in put)
        compose.onNodeWithTag("finecat.providerEdit").assertExists()

        // 失败回执：输入保留、表单不关、原因落在卡内错误位
        transport.receive(
            """{"kind":"error","code":"bad-request",""" +
                """"message":"synthetic-write-refused","requestType":"provider-put"}""",
        )
        compose.waitForIdle()
        waitUntilTagPresent("finecat.formError")
        saveShot("f01-save-error.png")
        compose.onNodeWithTag("finecat.formId")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(newId)))
        compose.onNodeWithTag("finecat.formName")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("F01 保存样例"),
                ),
            )

        // 重试：成功回执才收卡，回读=提交值
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 2, "失败重试后")
        transport.receive(savedFrameEchoingPut(1))
        waitUntilEditFormGoneAfterSave("新建保存，失败回执后重试")
        waitUntilTagPresent("finecat.settingsScreen")
        runBlocking { waitUntil { holder.providers.any { it.id == newId } } }
        val saved = holder.providers.first { it.id == newId }
        assertEquals("成功回读应等于提交值", "F01 保存样例", saved.name)
        assertEquals("成功回读应等于提交值", "https://f01-save.example/v1", saved.baseUrl)

        // 已存 key 留空保留：编辑刚保存的 provider，key 留空改名保存，put 仍不带 apiKey
        openEditFormFor(newId)
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("F01 保存样例改")
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 3, "编辑留空key保存后")
        val put2 = transport.payloadsOfType("provider-put").last()
        assertEquals(newId, put2.getValue("id").jsonPrimitive.content)
        assertTrue("已存 key 留空保留：编辑保存也不带 apiKey", "apiKey" !in put2)
        transport.receive(savedFrameEchoingPut(2))
        waitUntilEditFormGoneAfterSave("编辑已存 provider，key 留空改名保存")
        runBlocking {
            waitUntil { holder.providers.firstOrNull { it.id == newId }?.name == "F01 保存样例改" }
        }
    }

    // ------------------------------------------------------------------
    // 终态归属（2026-10-02 竞态修复）。以下三条都走 FineCatApp 真实根：
    // 组件级 harness 没有真实根带来的额外读请求，不会触发 lane 合并取消，
    // 同场景会假绿，不能替代这里的验收。
    // ------------------------------------------------------------------

    /**
     * 迟到回执不得收掉重新打开的表单。
     *
     * 场景：改名后保存（provider-put 在途），网关把**上一次**的 provider-saved
     * 补发过来。应答帧没有 requestId、`correlationId` 在 provider 系为 null，
     * 同一个 provider 关闭再打开时 id 也一样——只有回显内容能把「上一次的成功」
     * 和「这一次的成功」分开。先注旧回显：表单必须仍在；再注本次回显：才收。
     */
    @Test
    fun lateReceiptFromPreviousSave_doesNotCloseReopenedForm() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        createSaveableProviderNamed("F01 归属样例")

        // 第二次保存：改名后发出 put
        openEditFormFor(NEW_SAVE_ID)
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("F01 归属样例改")
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 2, "改名保存后")

        // 迟到的**上一次**成功：回显的还是旧名字
        transport.receive(providerSavedFrame(NEW_SAVE_ID, "F01 归属样例"))
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "迟到的上一份成功回执不得收掉这一次的表单（${snapshot()}）",
        )

        // 本次真正的成功：回显与提交一致，才收卡
        transport.receive(providerSavedFrame(NEW_SAVE_ID, "F01 归属样例改"))
        waitUntilEditFormGoneAfterSave("本次提交的成功回执")
    }

    /**
     * 读请求的取消通知与保存成功同批到达时，保存仍然完成，且不显示为保存失败。
     *
     * `provider-saved` 的处理会自己 `loadProviders()`；这里紧接着再发一次，
     * 列表 lane 是 COALESCE_LATEST，第一个在途的必然被合并取消——这就是
     * 「实际已保存却提示已取消、表单不收」的来源，且是真实机制而非人为时序。
     */
    @Test
    fun readRequestCoalescedCancelAfterSave_doesNotMaskSaveSuccess() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        createSaveableProviderNamed("F01 读请求样例")

        transport.receive(providerSavedFrame(NEW_SAVE_ID, "F01 读请求样例"))
        // 不等 idle：让保存回执与随后触发的读请求取消落在同一批处理里
        holder.loadProviders()
        holder.loadProviders()
        // 注意：下面这行**没有判别力**（独立审查 F-2）——`createSaveableProviderNamed`
        // 已经收卡，此刻没有表单在等，这一断言恒过。真正的判别在紧随其后的保存通道
        // 状态断言（拆掉终态隔离时那里会报 expected:<Saved> but was:<Unconfirmed>），
        // 以及修复前为红的 save_waitsRealReceipt（它在表单在途时收卡）。
        waitUntilEditFormGoneAfterSave("保存成功与读请求取消同批到达")

        runBlocking {
            waitUntil { holder.providers.any { it.id == NEW_SAVE_ID } }
        }
        // 读请求被取消**该有**反馈（真实失败/中断要看得见），但它只能进瞬时提示槽，
        // 不能进保存通道、也不能冒充保存失败。要断言的是保存通道的判定，不是提示文案。
        assertEquals(
            "保存通道必须判成功（读请求的取消不得改写保存终态）：${snapshot()}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Saved,
            holder.providerSaveTerminal?.status,
        )
        // 读请求计数照发：不能为了避开竞态而关掉回读
        assertEquals(
            "provider-put 次数必须严格等于 1（保存链路没有被重发）",
            1,
            transport.payloadsOfType("provider-put").size,
        )
        assertTrue(
            "provider-saved 之后的独立 providers 回读必须保留",
            transport.payloadsOfType("providers").isNotEmpty(),
        )
    }

    /**
     * provider-delete 的回执不得被保存流程消费：保存在途时来一条删除回执，
     * 既不能收掉表单，也不能显示成保存失败。
     */
    @Test
    fun providerDeleteReceipt_isNotConsumedBySaveFlow() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        createSaveableProviderNamed("F01 删除回执样例")

        openEditFormFor(NEW_SAVE_ID)
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("F01 删除回执样例改")
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 2, "改名保存后")

        // 别人的删除回执混进来
        transport.receive(
            """{"kind":"provider-deleted","id":"f01-a","name":"F01 样例甲"}""",
        )
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "provider-delete 的回执不得收掉保存中的表单（${snapshot()}）",
        )
        assertTrue(
            "provider-delete 的回执不得显示为保存失败（${snapshot()}）",
            holder.providerSaveTerminal?.status !=
                AndroidSharedStateHolder.ProviderSaveTerminal.Status.Rejected,
        )

        // 本次保存自己的成功仍然要收卡
        transport.receive(providerSavedFrame(NEW_SAVE_ID, "F01 删除回执样例改"))
        waitUntilEditFormGoneAfterSave("删除回执混入后的本次保存")
    }

    /**
     * provider-put 自身被取消 → 表单停等「未确认」，草稿保留、重试可达。
     *
     * 取消是真实机制且有确定性触发：**保存在途时连接断开**。runtime 的
     * `clearConnectionWorkLocked` 会为每个在途请求发 `RequestCancelled`，
     * 于是 `RequestCancelled(requestType = "provider-put")` 到达 holder。
     * （试过「再发一个 provider-put 逼出合并取消」——不成立：同 lane 有在途请求时
     *  runtime 直接以 `request-busy` 拒绝，不会排队，所以拿不到取消。）
     *
     * 断言重点是**语义**：取消只表示「没拿到确认」，请求可能已到网关并落盘，
     * 所以文案与判定都不能说成「没保存」；表单必须留在原地、草稿不能没、重试可达。
     */
    @Test
    fun saveCancelled_showsUnconfirmed_keepsFormAndDraft_retryReachable() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        compose.onNodeWithTag("finecat.providerNew").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")

        compose.onNodeWithTag("finecat.formId").performTextInput(NEW_SAVE_ID)
        compose.onNodeWithTag("finecat.formName").performTextInput("F01 未确认样例")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://f01-save.example/v1")
        scrollToInForm("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput("f01-save-model")
        scrollToInForm("finecat.modelContext", hostTag = "finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelContext").performTextInput("131072")
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").performClick()
        awaitProviderFrame("provider-test", 1, "点测试连接后")
        transport.receive(
            """{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","ok":true,"latencyMs":12,"id":"$NEW_SAVE_ID"}""",
        )
        waitUntilTagPresent("finecat.providerTestResult")

        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 1, "首次保存后")

        // 保存在途时连接失败：为在途的 provider-put 发出 RequestCancelled
        // （runtime 的处理是异步协程，compose.waitForIdle() 只等 Compose 时钟，不等它）
        runBlocking { transport.fail() }
        runBlocking { waitUntil { holder.providerSaveTerminal != null } }
        compose.waitForIdle()

        assertEquals(
            "provider-put 被取消必须是 Unconfirmed，不能当成真失败：${snapshot()}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder.providerSaveTerminal?.status,
        )
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "未确认不等于失败：表单必须留在原地（${snapshot()}）",
        )
        val detail = holder.providerSaveTerminal?.detail.orEmpty()
        assertTrue(
            "未确认文案不得声称服务端没落盘：[$detail]",
            detail.contains("未确认") && !detail.contains("没保存") && !detail.contains("未保存"),
        )
        assertTrue(
            "取消后保存位必须已释放（否则重试被静默丢弃）：${snapshot()}",
            !holder.providerSaveIsLoading,
        )
        // 草稿还在
        compose.onNodeWithTag("finecat.formName")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("F01 未确认样例"),
                ),
            )

        // 重试可达：连接已 FAILED，再点保存必须**给反馈**而不是被 `providerSaveIsLoading`
        // 静默丢弃（那正是「卡在在途位、用户以为点了没反应」的旧症状）。
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        val feedback = holder.providerResult ?: ""
        assertTrue(
            "连接断开后重试必须有可见反馈（不能静默丢弃）：[$feedback] ${snapshot()}",
            feedback.isNotEmpty() || holder.platformError != null,
        )
    }

    /**
     * provider-put **超时** → 同样是 Unconfirmed，表单草稿保留、loading 已释放。
     *
     * 超时与取消在实现里走同一段代码（都只表示「没拿到确认」），但触发机制不同：
     * 取消是连接/替换事件主动发 `RequestCancelled`，超时是 lane 自己到期发
     * `RequestTimedOut`。这里用 `requestTimeoutOverride` 把超时压到 2s，让真实超时
     * 定时器在用例内发生——不是靠手工构造事件。
     */
    @Test
    fun saveTimedOut_showsUnconfirmed_keepsFormAndDraft() {
        connectFakeGateway(requestTimeoutMillis = 8_000L)
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        compose.onNodeWithTag("finecat.providerNew").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")

        compose.onNodeWithTag("finecat.formId").performTextInput(NEW_SAVE_ID)
        compose.onNodeWithTag("finecat.formName").performTextInput("F01 超时样例")
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://f01-save.example/v1")
        scrollToInForm("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput("f01-save-model")
        scrollToInForm("finecat.modelContext", hostTag = "finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelContext").performTextInput("131072")
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").performClick()
        awaitProviderFrame("provider-test", 1, "点测试连接后")
        transport.receive(
            """{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","ok":true,"latencyMs":12,"id":"$NEW_SAVE_ID"}""",
        )
        waitUntilTagPresent("finecat.providerTestResult")

        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 1, "首次保存后")
        // 不注入任何应答帧：等真实的超时定时器到期（8s，故这里用比默认 5s 更长的等待）
        compose.waitUntil(timeoutMillis = 25_000) { holder.providerSaveTerminal != null }
        compose.waitForIdle()

        assertEquals(
            "provider-put 超时必须是 Unconfirmed，不能当成真失败：${snapshot()}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder.providerSaveTerminal?.status,
        )
        val detail = holder.providerSaveTerminal?.detail.orEmpty()
        assertTrue(
            "超时文案不得声称服务端没落盘：[$detail]",
            detail.contains("未确认") && !detail.contains("没保存"),
        )
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "超时不等于失败：表单必须留在原地（${snapshot()}）",
        )
        assertTrue(
            "超时后保存位必须已释放：${snapshot()}",
            !holder.providerSaveIsLoading,
        )
        compose.onNodeWithTag("finecat.formName")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("F01 超时样例"),
                ),
            )
    }

    // ------------------------------------------------------------------
    // 迟到回执归属补强（2026-10-02 第二个 goal）：上一条修的是「读请求取消覆盖
    // 保存成功」，指纹当时只覆盖 providerId/name/baseUrl/模型 id@api。
    // 上一次保存超时成 Unconfirmed 后，用户**只**改某个模型的 contextWindow 或
    // maxTokens 再重发，旧 provider-saved 先到时其余字段全同 → 旧回执会被当成本次
    // 成功，提前收起表单。下面三条走完整链路，都从 FineCatApp 真实根入口进。
    // ------------------------------------------------------------------

    /**
     * 完整顺序（只改 contextWindow）：保存超时 → 草稿保留 → 只改上下文窗口 →
     * 第二次保存发出 → **第一次的回执迟到** → 第二次的回执到达。
     *
     * 超时用 `requestTimeoutOverride` 让**真实超时定时器**到期，不注入任何事件。
     * 回执内容按网关 `toWireProvider(merged)` 的口径从**记录下来的 put 载荷**回放：
     * 每个模型就是那次提交的几个字段，本次没下发的数值上限显式回 null。所以「迟到的
     * 上一份回执」就是网关当时会回的那份，不是客户端凭空造的身份。
     */
    @Test
    fun lateReceiptAfterTimeout_onlyContextWindowChanged_doesNotCloseSecondSave() {
        connectFakeGateway(requestTimeoutMillis = 8_000L)
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()

        fillNewProviderFormAndPassTestGate(name = "F01 迟到回执样例", contextWindow = "131072")

        // ── 第一次保存：发出去后不注入任何应答，等真实超时定时器
        saveAndAwaitPut(count = 1)
        compose.waitUntil(timeoutMillis = 25_000) { holder.providerSaveTerminal != null }
        compose.waitForIdle()
        assertEquals(
            "第一次保存超时必须是 Unconfirmed：${attributionSnapshot()}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder.providerSaveTerminal?.status,
        )
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "超时后表单必须留在原地：${attributionSnapshot()}",
        )
        assertTrue("超时后保存位必须已释放：${attributionSnapshot()}", !holder.providerSaveIsLoading)
        assertTrue(
            "第一次保存必须是它自己的超时（不是别的请求超时后回收连接连带取消的）：" +
                attributionSnapshot(),
            holder.providerSaveTerminal?.detail.orEmpty().contains("超时"),
        )
        // 超时后 runtime 会回收连接并自己重连，假传输要由用例扮演这一步才能重发。
        reconnectFakeGateway()

        // ── 只改上下文窗口（名字/URL/模型 id@api 一个字不动）
        editModelField("finecat.modelContext", "262144")

        // ── 第二次保存
        saveAndAwaitPut(count = 2)
        val firstPut = transport.payloadsOfType("provider-put")[0]
        val secondPut = transport.payloadsOfType("provider-put")[1]
        record("put#1 raw=${firstPut}")
        record("put#2 raw=${secondPut}")
        assertEquals(
            "两次 put 的 providerId 必须相同（否则就不是同一条窄窗口）：${attributionSnapshot()}",
            firstPut.getValue("id").jsonPrimitive.content,
            secondPut.getValue("id").jsonPrimitive.content,
        )
        assertEquals(
            "两次 put 除 contextWindow 外必须逐字相同，否则本用例证明的不是那个窄窗口：" +
                attributionSnapshot(),
            firstPut.toString().replace("\"contextWindow\":131072", "@@"),
            secondPut.toString().replace("\"contextWindow\":262144", "@@"),
        )

        // ── 第一次的回执迟到：内容是 put#1 落盘后的那份
        val lateReceipt = savedFrameEchoingPut(0)
        record("receipt#1(late) raw=$lateReceipt")
        transport.receive(lateReceipt)
        compose.waitForIdle()
        record("after receipt#1 ${attributionSnapshot()}")
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "迟到的上一份成功回执不得收掉第二次保存的表单：${attributionSnapshot()}",
        )
        // holder 的保存位与瞬时提示**确实**已经被这条迟到回执改写了（provider-saved
        // 帧一到就清 providerSaveIsLoading 并写「已保存」提示），所以那两项在这里不做
        // 断言：holder 手上有在途 token，但对 provider-saved 帧没有任何可用请求身份
        // （帧不带 requestId、provider 系 correlationId 恒 null），能判「这是上一次
        // 的成功」的只有表单那次提交的指纹。归属修在表单侧，现状留档见上面的快照行。
        // 表单里的草稿仍是第二次提交的值：迟到回执既不能收卡也不能回滚表单。
        compose.onNodeWithTag("finecat.formName")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("F01 迟到回执样例"),
                ),
            )

        // ── 第二次的回执到达：这才收卡
        val trueReceipt = savedFrameEchoingPut(1)
        record("receipt#2 raw=$trueReceipt")
        transport.receive(trueReceipt)
        waitUntilEditFormGoneAfterSave("第二次提交自己的成功回执")
        record("after receipt#2 ${attributionSnapshot()}")
        waitUntilOrFail("保存后回读出现该 provider") { holder.providers.any { it.id == NEW_SAVE_ID } }
        val readback = holder.providers.first { it.id == NEW_SAVE_ID }
        assertEquals(
            "保存后回读必须是第二次提交的上下文窗口：${attributionSnapshot()}",
            262_144L,
            readback.models.first().contextWindow,
        )
        assertEquals("provider-put 次数必须严格等于 2：${attributionSnapshot()}", 2, transport.payloadsOfType("provider-put").size)
    }

    /**
     * 只拨一下模型卡的「支持推理」开关也要能认出旧回执（独立审查 F-1）。
     *
     * 与数值上限同一条窄窗口，但更容易踩：开关比敲数字容易误触，而且
     * `providerFingerprint` 只覆盖 baseUrl/apiKey/模型 id@api，**改它不会让测试门槛失效**，
     * 用户不用重测就能直接重发。网关对 `reasoning`/`input` 是无条件下发、原样回显，
     * 所以这边直接比即可。
     */
    @Test
    fun lateReceiptAfterTimeout_onlyReasoningToggled_doesNotCloseSecondSave() {
        connectFakeGateway(requestTimeoutMillis = 8_000L)
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()
        fillNewProviderFormAndPassTestGate(name = "F01 开关样例", contextWindow = "131072")

        saveAndAwaitPut(count = 1)
        compose.waitUntil(timeoutMillis = 25_000) { holder.providerSaveTerminal != null }
        compose.waitForIdle()
        assertEquals(
            "第一次保存超时必须是 Unconfirmed：${attributionSnapshot()}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder.providerSaveTerminal?.status,
        )
        assertTrue(
            "第一次保存必须是它自己的超时：${attributionSnapshot()}",
            holder.providerSaveTerminal?.detail.orEmpty().contains("超时"),
        )
        reconnectFakeGateway()

        // 只把「支持推理」关掉，别的一个字不动（新模型默认 reasoning=true，点一下变 false）
        toggleModelSwitch("finecat.modelReasoning")

        saveAndAwaitPut(count = 2)
        val firstPut = transport.payloadsOfType("provider-put")[0]
        val secondPut = transport.payloadsOfType("provider-put")[1]
        record("put#1 raw=${firstPut}")
        record("put#2 raw=${secondPut}")
        assertEquals(
            "第二次 put 必须只差 reasoning：${attributionSnapshot()}",
            firstPut.toString().replace("\"reasoning\":true", "@@"),
            secondPut.toString().replace("\"reasoning\":false", "@@"),
        )

        val lateReceipt = savedFrameEchoingPut(0)
        record("receipt#1(late) raw=$lateReceipt")
        transport.receive(lateReceipt)
        compose.waitForIdle()
        record("after receipt#1 ${attributionSnapshot()}")
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "只拨了推理开关的迟到回执不得收掉第二次保存的表单：${attributionSnapshot()}",
        )

        val trueReceipt = savedFrameEchoingPut(1)
        record("receipt#2 raw=$trueReceipt")
        transport.receive(trueReceipt)
        waitUntilEditFormGoneAfterSave("只改推理开关后的本次成功回执")
        record("after receipt#2 ${attributionSnapshot()}")
        waitUntilOrFail("保存后回读出现该 provider") { holder.providers.any { it.id == NEW_SAVE_ID } }
        assertEquals(
            "保存后回读必须是第二次提交的推理开关：${attributionSnapshot()}",
            false,
            holder.providers.first { it.id == NEW_SAVE_ID }.models.first().reasoning,
        )
    }

    /**
     * 同一序列，只改 [maxTokens]，且第一次保存走**取消**而不是超时。
     *
     * 这条同时钉住两件事：① 另一个数值上限也能反证旧回执；② 取消后的重试链路
     * （loading 释放、草稿保留、重发可达）没有因为指纹变严而回退。
     */
    @Test
    fun lateReceiptAfterCancel_onlyMaxTokensChanged_doesNotCloseSecondSave() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()

        fillNewProviderFormAndPassTestGate(name = "F01 迟到回执样例二", contextWindow = "131072")

        // ── 第一次保存：连接掉线 → 在途 provider-put 被真实取消
        saveAndAwaitPut(count = 1)
        runBlocking { transport.failRecoverable() }
        runBlocking { waitUntil { holder.providerSaveTerminal != null } }
        compose.waitForIdle()
        assertEquals(
            "第一次保存被取消必须是 Unconfirmed：${attributionSnapshot()}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder.providerSaveTerminal?.status,
        )
        assertTrue(
            "第一次保存必须是被掉线取消的（与超时用例对称，否则这条会静默退化成超时路径）：" +
                attributionSnapshot(),
            holder.providerSaveTerminal?.detail.orEmpty().contains("已取消"),
        )
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "取消后表单必须留在原地：${attributionSnapshot()}",
        )
        assertTrue("取消后保存位必须已释放：${attributionSnapshot()}", !holder.providerSaveIsLoading)
        compose.onNodeWithTag("finecat.formName")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("F01 迟到回执样例二"),
                ),
            )

        // 取消后重发可达：等 runtime 自己重连（首个退避 2s），再只改最大输出
        reconnectFakeGateway()
        editModelField("finecat.modelMax", "8192")

        // ── 第二次保存
        saveAndAwaitPut(count = 2)
        val firstPut = transport.payloadsOfType("provider-put")[0]
        val secondPut = transport.payloadsOfType("provider-put")[1]
        record("put#1 raw=${firstPut}")
        record("put#2 raw=${secondPut}")
        val stripMaxTokens = Regex(",?\"maxTokens\":\\d+")
        assertEquals(
            "两次 put 除 maxTokens 外必须逐字相同，否则本用例证明的不是那个窄窗口：" +
                attributionSnapshot(),
            firstPut.toString().replace(stripMaxTokens, ""),
            secondPut.toString().replace(stripMaxTokens, ""),
        )
        assertTrue(
            "第二次 put 必须带上了 maxTokens：${attributionSnapshot()}",
            "\"maxTokens\":8192" in secondPut.toString(),
        )

        // ── 第一次的回执迟到：回显里 maxTokens 是 null（那次提交压根没下发这个键）
        val lateReceipt = savedFrameEchoingPut(0)
        record("receipt#1(late) raw=$lateReceipt")
        transport.receive(lateReceipt)
        compose.waitForIdle()
        record("after receipt#1 ${attributionSnapshot()}")
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "只填了 maxTokens 的迟到回执不得收掉第二次保存的表单：${attributionSnapshot()}",
        )
        // holder 的保存位与提示同样会被这条迟到回执改写（原因见 contextWindow 用例的
        // 注释）：归属只在表单侧判，这里不断言那两项。

        // ── 第二次的回执到达：这才收卡
        val trueReceipt = savedFrameEchoingPut(1)
        record("receipt#2 raw=$trueReceipt")
        transport.receive(trueReceipt)
        waitUntilEditFormGoneAfterSave("第二次提交自己的成功回执")
        record("after receipt#2 ${attributionSnapshot()}")
        waitUntilOrFail("保存后回读出现该 provider") { holder.providers.any { it.id == NEW_SAVE_ID } }
        assertEquals(
            "保存后回读必须是第二次提交的最大输出：${attributionSnapshot()}",
            8_192L,
            holder.providers.first { it.id == NEW_SAVE_ID }.models.first().maxTokens,
        )
    }

    /**
     * 关闭重开后的同一窄窗口：超时 → 列表回读出现该 provider（网关其实已落盘）→
     * 关掉表单 → 从列表重开 → 只改上下文窗口 → 保存 → **旧回执迟到**。
     *
     * 与前两条的差别：这是**另一张表单卡片**（pendingSave / submittedFingerprint 全部
     * 重建），在途标记也是新铸的，所以归属只能靠回显内容——正是最容易把「上一份的
     * 成功」错认成「这一次的成功」的地方。
     */
    @Test
    fun lateReceiptAfterReopen_onlyContextWindowChanged_doesNotCloseSecondSave() {
        connectFakeGateway(requestTimeoutMillis = 8_000L)
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        openSettingsViaRealEntry()

        fillNewProviderFormAndPassTestGate(name = "F01 重开样例", contextWindow = "131072")
        saveAndAwaitPut(count = 1)
        compose.waitUntil(timeoutMillis = 25_000) { holder.providerSaveTerminal != null }
        compose.waitForIdle()
        assertEquals(
            "第一次保存超时必须是 Unconfirmed：${attributionSnapshot()}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder.providerSaveTerminal?.status,
        )
        assertTrue(
            "第一次保存必须是它自己的超时：${attributionSnapshot()}",
            holder.providerSaveTerminal?.detail.orEmpty().contains("超时"),
        )
        record("after timeout ${attributionSnapshot()}")
        reconnectFakeGateway()

        // Unconfirmed 之后列表回读：网关其实已经把第一次的提交落盘了（超时只说明
        // 客户端没等到回执）。这里按第一次 put 的载荷回放一份 providers 读出结果。
        val echo = providerEchoJsonOfPut(0, withListFields = true)
        record("providers-after-timeout raw=$echo")
        transport.receive("""{"kind":"providers","providers":[$echo]}""")
        waitUntilOrFail("回读列表里出现该 provider") { holder.providers.any { it.id == NEW_SAVE_ID } }

        // 连接回收会让 App 弹全局错误模态（真实产品行为，FineCatApp 的 platformError
        // AlertDialog）：它是独立窗口，此时语义树有两个 root，遮罩点击会报
        // 「Expected exactly '1' node but found '2'」。先按「好」关掉它。
        dismissPlatformErrorIfShown()

        // 关掉表单再从列表重开：这是一张全新的卡片
        pressBack()
        waitUntilTagGone("finecat.providerEdit")
        openEditFormFor(NEW_SAVE_ID)
        assertEditFormShows(id = NEW_SAVE_ID, name = "F01 重开样例", baseUrl = SAVE_BASE_URL)

        // 只改上下文窗口
        scrollToInForm("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        scrollToInForm("finecat.modelContext", hostTag = "finecat.modelDetail")
        // 回填按 K/M 口径显示（131072 → 128K），这里跟读端用同一个格式化函数，
        // 免得把「128K」写成魔法字符串。
        compose.onNodeWithTag("finecat.modelContext").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString(ModelNumberParsing.formatTokenCount(131_072)),
            ),
        )
        editModelDetailField("finecat.modelContext", "262144")
        saveAndAwaitPut(count = 2)
        record("put#2(reopened) raw=${transport.payloadsOfType("provider-put")[1]}")

        // ── 旧回执迟到
        val lateReceipt = savedFrameEchoingPut(0)
        record("receipt#1(late) raw=$lateReceipt")
        transport.receive(lateReceipt)
        compose.waitForIdle()
        record("after receipt#1 ${attributionSnapshot()}")
        compose.onNodeWithTag("finecat.providerEdit").assertExists(
            "重开后的表单不得被重开前那次保存的回执收掉：${attributionSnapshot()}",
        )

        val trueReceipt = savedFrameEchoingPut(1)
        record("receipt#2 raw=$trueReceipt")
        transport.receive(trueReceipt)
        waitUntilEditFormGoneAfterSave("重开后第二次提交自己的成功回执")
        record("after receipt#2 ${attributionSnapshot()}")
        waitUntilOrFail("保存后回读出现该 provider") { holder.providers.any { it.id == NEW_SAVE_ID } }
        assertEquals(
            "保存后回读必须是第二次提交的上下文窗口：${attributionSnapshot()}",
            262_144L,
            holder.providers.first { it.id == NEW_SAVE_ID }.models.first().contextWindow,
        )
    }

    /**
     * 把假网关调成「读请求都有应答」。
     *
     * 连接测试结论会触发 `providers` / `models` 刷新；它们若一直挂在途，就会比
     * provider-put **先**超时，而 runtime 对非 attachment 的超时会回收整条连接
     * （`GatewayRuntime.settleTimeoutLocked` → `recycleConnectionForDeferredLocked`），
     * 于是在途的保存被判成 `RequestCancelled`（「已取消」）而不是它自己的
     * `RequestTimedOut`（「超时」）——那就测不到「保存超时」这条路径了。
     *
     * 这里不判断「哪条请求还没回」（假传输不跟踪应答状态），而是连续几轮把读应答喂
     * 回去：同一份 providers / 目录对客户端是幂等更新，不会改变被测行为。
     */
    private fun answerPendingReads(rounds: Int = 6) {
        waitUntilOrFail("连接测试结论触发读刷新") { transport.sentTypes.contains("models") }
        repeat(rounds) {
            transport.receive(SESSIONS_EMPTY_FRAME)
            transport.receive(PROVIDERS_AB_FRAME)
            transport.receive("""{"kind":"models","groups":[]}""")
            compose.waitForIdle()
            Thread.sleep(250)
        }
    }

    /**
     * 重连假网关。
     *
     * provider-put 超时后 runtime 会回收连接并自己重连（真实传输会自己完成）；假传输
     * 只在被显式 open 时才发 Open/hello，所以这一步必须由用例扮演，否则重试会被
     * 「未连接 Pi 网关」拦下——那是假网关的缺口，不是产品行为。
     */
    private fun reconnectFakeGateway() {
        // runtime 的重连有退避（首个 2s）且由它自己调 open，所以这里反复替假传输发
        // Open/hello，直到状态真的回到 CONNECTED（通常一两轮就成了）。
        for (attempt in 0 until 20) {
            runBlocking {
                transport.open()
                transport.receive(HELLO_CONNECTED_FRAME)
            }
            repeat(6) {
                if (holder.gatewayState.connection.name == "CONNECTED") return
                Thread.sleep(200)
            }
        }
        if (holder.gatewayState.connection.name != "CONNECTED") {
            throw AssertionError("重连后连接仍不是 CONNECTED；表单现场：${attributionSnapshot()}")
        }
    }

    /** 关掉全局错误模态（连接掉线/回收时 App 会弹，见用例里对两个 root 的说明）。 */
    private fun dismissPlatformErrorIfShown() {
        if (holder.platformError == null) return
        record("platformError modal=${holder.platformError} action=${holder.platformErrorAction}")
        compose.onNodeWithText("好").performClick()
        waitUntilOrFail("全局错误模态关闭") { holder.platformError == null }
        compose.waitForIdle()
    }

    /** 新建表单：id/名称/URL + 一个模型（填上下文窗口）+ 测试门槛跑通。 */
    private fun fillNewProviderFormAndPassTestGate(name: String, contextWindow: String, apiKey: String = "") {
        compose.onNodeWithTag("finecat.providerNew").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")
        compose.onNodeWithTag("finecat.formId").performTextInput(NEW_SAVE_ID)
        compose.onNodeWithTag("finecat.formName").performTextInput(name)
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput(SAVE_BASE_URL)
        if (apiKey.isNotEmpty()) {
            scrollToInForm("finecat.formApiKey")
            compose.onNodeWithTag("finecat.formApiKey").performTextInput(apiKey)
        }
        scrollToInForm("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput(MODEL_ID)
        scrollToInForm("finecat.modelContext", hostTag = "finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelContext").performTextInput(contextWindow)
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")

        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").performClick()
        awaitProviderFrame("provider-test", 1, "点测试连接后")
        transport.receive(
            """{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","ok":true,"latencyMs":12,"id":"$NEW_SAVE_ID"}""",
        )
        waitUntilTagPresent("finecat.providerTestResult")
        // 连接测试结论会触发读刷新；先回掉，否则第一次保存的超时会被它们抢先。
        answerPendingReads()
    }

    /** 打开模型二级卡 → 改一个字段 → 回一级卡（开关传 "false"，点一下即关）。 */
    private fun editModelField(tag: String, value: String) {
        scrollToInForm("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        editModelDetailField(tag, value)
    }

    /** 二级卡已打开时改一个**文本**字段（`finecat.modelContext` / `finecat.modelMax`）。 */
    private fun editModelDetailField(tag: String, value: String) {
        scrollToInForm(tag, hostTag = "finecat.modelDetail")
        compose.onNodeWithTag(tag).performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
    }

    /**
     * 打开模型二级卡 → **点一下**开关（`finecat.modelReasoning` / `finecat.modelImage`）
     * → 回一级卡。
     *
     * `ToggleRow` 是 `clickable(role = Switch)`，没有 `toggleable` 语义，语义树里读不到
     * 开关态；所以这里只点一次，起始态由建表动作保证（新模型的 `reasoning`/`imageOn`
     * 默认关/开各按 `ProviderModelDraft` 的初值），点完后的实际值由用例拿两条 put 的
     * 逐字差异来验（见调用处），不去猜「点了几下」。
     */
    private fun toggleModelSwitch(tag: String) {
        scrollToInForm("finecat.modelRow.0")
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        scrollToInForm(tag, hostTag = "finecat.modelDetail")
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")
    }

    /** 点保存并等第 [count] 条 provider-put 发出去。 */
    private fun saveAndAwaitPut(count: Int) {
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        try {
            awaitProviderFrame("provider-put", count, "第 $count 次保存后")
        } catch (e: AssertionError) {
            // 保存发不出去时，原因一定写在卡内错误位或门槛位上；不带上下文就看不出是哪一条。
            throw AssertionError("${e.message}\n表单现场：${attributionSnapshot()}", e.cause ?: e)
        }
    }

    /**
     * 等一个条件成立；超时补上「在等什么」。
     *
     * 共享 [waitUntil] 固定 5s 且只抛 TimeoutCancellationException，既不说是哪一处等
     * 的、也不带任何现场——在这类迟到回执用例里，报错必须能看出卡在重连、等回读还是
     * 等表单。
     */
    private fun waitUntilOrFail(what: String, condition: () -> Boolean) {
        try {
            runBlocking { waitUntil(condition) }
        } catch (e: Exception) {
            throw AssertionError("等待「$what」超时（5s）；表单现场：${attributionSnapshot()}", e)
        }
    }

    /** 读某个 testTag 节点的文字（不存在时 null），用于快照里说清表单自己报了什么。 */
    private fun nodeTextOrNull(tag: String): String? = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    }.getOrNull()

    /**
     * 按网关 `toWireProvider(merged)` 的口径回放 provider 对象：回显的每个模型就是
     * 这次 put 提交的那几个字段；本次没下发的数值上限按 `asPositiveInteger(x) ?? null`
     * 显式回 null（网关是显式 null，不是省略字段）。
     */
    /**
     * 按网关 `toWireProvider(merged)` 的**字段集**回放 provider 对象：回显的每个模型
     * 就是这次 put 提交的那几个字段；本次没下发的数值上限按 `asPositiveInteger(x) ?? null`
     * 显式回 null（网关是显式 null，不是省略字段）。
     *
     * [withListFields] 只给 `providers` 列表帧用：`active` / `status` 由网关在列表帧里
     * 从 provider-status 投影补上（`router.ts`），`provider-saved` 帧**不带**这两个字段
     * （`toWireProvider` 没有它们），两处不能共用同一份 fixture。
     */
    private fun providerEchoJsonOfPut(putIndex: Int, withListFields: Boolean = false): String {
        val put = transport.payloadsOfType("provider-put")[putIndex]
        fun JsonObject.numOrNull(key: String): String = this[key]?.jsonPrimitive?.content ?: "null"
        val models = put.getValue("models").jsonArray.joinToString(",") { raw ->
            val model = raw.jsonObject
            """{"id":${model["id"]},"api":${model["api"]},"reasoning":${model["reasoning"]},""" +
                """"input":${model["input"]},"contextWindow":${model.numOrNull("contextWindow")},""" +
                """"maxTokens":${model.numOrNull("maxTokens")}}"""
        }
        val listFields = if (withListFields) ""","active":true,"status":{"ok":true}""" else ""
        return """{"id":${put.getValue("id")},"name":${put["name"] ?: "null"},""" +
            """"baseUrl":${put["baseUrl"] ?: "null"},"apiKeySet":true$listFields,"models":[$models]}"""
    }

    private fun savedFrameEchoingPut(putIndex: Int): String =
        """{"kind":"provider-saved","admitted":true,"refreshed":true,"provider":""" +
            providerEchoJsonOfPut(putIndex) + "}"

    /**
     * 迟到回执序列的取证快照：请求内容 / 已注入回执 / 表单状态 / 保存终态。
     * 断言消息与落盘证据行用同一份，保证失败时看得到全部四个维度。
     */
    private fun attributionSnapshot(): String {
        val puts = transport.payloadsOfType("provider-put")
        return "form=${if (compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()) "已收起" else "仍在"}" +
            " formError=${nodeTextOrNull("finecat.formError")}" +
            " formError=${nodeTextOrNull("finecat.formError")}" +
            " connected=${holder.gatewayState.connection.name}" +
            " saveTerminal=${holder.providerSaveTerminal?.status}/${holder.providerSaveTerminal?.detail}" +
            " saveIsLoading=${holder.providerSaveIsLoading} putCount=${puts.size}" +
            " sent=${transport.sentTypes}" +
            " puts=${puts.map { it["models"]?.toString() }}" +
            " receipts=${receiptLog.toList()}" +
            " result=${holder.providerResult}" +
            " providers=${holder.providers.map { "${it.id}/${it.models.map { m -> "${m.id}@${m.contextWindow}:${m.maxTokens}" }}" }}"
    }

    /** 追加一条取证行（请求/回执/状态），随仪器日志一起归档。 */
    private fun record(line: String) {
        if (line.startsWith("receipt")) receiptLog += line.substringBefore(" raw=")
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "late-receipt-evidence.txt")
        if (!evidenceFileReady) {
            f.delete()
            evidenceFileReady = true
        }
        f.appendText(line + "\n")
    }

    /**
     * 走一遍真实根的新建保存：填表 → 测试门槛 → 保存 → 回执 → 独立回读。
     * 供上面三条复用的建表动作，成功后 provider 名为 [name]。
     */
    private fun createSaveableProviderNamed(name: String) {
        compose.onNodeWithTag("finecat.providerNew").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.providerNew").performClick()
        waitUntilTagPresent("finecat.providerEdit")

        compose.onNodeWithTag("finecat.formId").performTextInput(NEW_SAVE_ID)
        compose.onNodeWithTag("finecat.formName").performTextInput(name)
        compose.onNodeWithTag("finecat.formBaseUrl").performTextInput("https://f01-save.example/v1")
        scrollToInForm("finecat.addModel")
        compose.onNodeWithTag("finecat.addModel").performClick()
        waitUntilTagPresent("finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelId").performTextInput("f01-save-model")
        scrollToInForm("finecat.modelContext", hostTag = "finecat.modelDetail")
        compose.onNodeWithTag("finecat.modelContext").performTextInput("131072")
        compose.onNodeWithTag("finecat.sheetBack").performClick()
        waitUntilTagGone("finecat.modelDetail")

        // 测试门槛：先测通才允许保存
        scrollToInForm("finecat.providerTest")
        compose.onNodeWithTag("finecat.providerTest").performClick()
        awaitProviderFrame("provider-test", 1, "点测试连接后")
        transport.receive(
            """{"kind":"provider-test-result","providerTestId":"${latestProbeToken()}","ok":true,"latencyMs":12,"id":"$NEW_SAVE_ID"}""",
        )
        waitUntilTagPresent("finecat.providerTestResult")

        scrollToInForm("finecat.providerTest")
        compose.onNodeWithText("保存").performClick()
        awaitProviderFrame("provider-put", 1, "首次保存后")
        transport.receive(providerSavedFrame(NEW_SAVE_ID, name))
        waitUntilEditFormGoneAfterSave("新建保存（$name）")
        runBlocking { waitUntil { holder.providers.any { it.id == NEW_SAVE_ID } } }
    }

    // ------------------------------------------------------------------
    // 条件 4（层级）：Back 和点外面都只退一层——表单收起，设置整页仍在，
    // 中间列表层不出现。
    // ------------------------------------------------------------------
    @Test
    fun backAndOutside_dismissExactlyOneLayer() {
        connectFakeGateway()
        setContentFromRealRoot()
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }

        openSettingsViaRealEntry()

        // 点外面：只退一层
        openEditFormFor(PROVIDER_A_ID)
        dismissEditFormByScrimTap()
        waitUntilTagGone("finecat.providerEdit")
        waitUntilTagPresent("finecat.settingsScreen")
        assertFalse("点外面不应打开列表层", listLayerPresent())

        // Back：只退一层
        openEditFormFor(PROVIDER_B_ID)
        pressBack()
        waitUntilTagGone("finecat.providerEdit")
        waitUntilTagPresent("finecat.settingsScreen")
        assertFalse("Back 不应打开列表层", listLayerPresent())
        saveShot("f01-back-outside.png")
    }

    // ------------------------------------------------------------------
    // 条件 4（矩阵）：每配置一个独立方法。配置注入在 setContent 之前
    // （wm density 520 → 1170px/(520/160) = 360dp），亮暗走产品真实路径
    // updateAppearanceMode，表单卡面像素断言亮/暗，finally 恢复并读回。
    // ------------------------------------------------------------------
    @Test
    fun matrix_390dp_fs10_light() = matrixStep("f01-matrix-390fs10-light.png")

    @Test
    fun matrix_390dp_fs10_dark() = matrixDarkStep("f01-matrix-390fs10-dark.png")

    @Test
    fun matrix_360dp_fs10_light() {
        shell("wm density 520")
        Thread.sleep(1_000)
        matrixStep("f01-matrix-360fs10-light.png")
    }

    @Test
    fun matrix_360dp_fs10_dark() {
        shell("wm density 520")
        Thread.sleep(1_000)
        matrixDarkStep("f01-matrix-360fs10-dark.png")
    }

    @Test
    fun matrix_360dp_fs13_light() {
        shell("wm density 520")
        shell("settings put system font_scale 1.3")
        Thread.sleep(1_000)
        matrixStep("f01-matrix-360fs13-light.png")
    }

    @Test
    fun matrix_360dp_fs13_dark() {
        shell("wm density 520")
        shell("settings put system font_scale 1.3")
        Thread.sleep(1_000)
        matrixDarkStep("f01-matrix-360fs13-dark.png")
    }

    // ==================================================================
    // 公共步骤
    // ==================================================================

    private fun matrixStep(shotName: String) {
        try {
            connectFakeGateway()
            holder.updateAppearanceMode("light")
            setContentFromRealRoot()
            transport.receive(PROVIDERS_AB_FRAME)
            runBlocking { waitUntil { holder.providers.size == 2 } }
            openSettingsViaRealEntry()
            openEditFormFor(PROVIDER_A_ID)
            assertFormThemePixel(darkExpected = false)
            saveShot(shotName)
            pressBack()
            waitUntilTagGone("finecat.providerEdit")
        } finally {
            restoreDeviceConfig()
        }
    }

    private fun matrixDarkStep(shotName: String) {
        try {
            connectFakeGateway()
            holder.updateAppearanceMode("dark")
            setContentFromRealRoot()
            transport.receive(PROVIDERS_AB_FRAME)
            runBlocking { waitUntil { holder.providers.size == 2 } }
            openSettingsViaRealEntry()
            openEditFormFor(PROVIDER_A_ID)
            assertFormThemePixel(darkExpected = true)
            saveShot(shotName)
            pressBack()
            waitUntilTagGone("finecat.providerEdit")
        } finally {
            restoreDeviceConfig()
        }
    }

    /** 表单卡面底色像素：亮色 surface≈白、暗色 surface=#17171A（FineCatTheme token）。 */
    private fun assertFormThemePixel(darkExpected: Boolean) {
        var last: Exception? = null
        repeat(4) {
            try {
                val bmp = compose.onNodeWithTag("finecat.providerEdit")
                    .captureToImage().asAndroidBitmap()
                val c = bmp.getPixel(bmp.width / 2, bmp.height * 9 / 10)
                val lum = (android.graphics.Color.red(c) +
                    android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3f
                if (darkExpected) {
                    assertTrue("暗色步骤的表单应呈暗色底（实测亮度=$lum）", lum < 80f)
                } else {
                    assertTrue("亮色步骤的表单应呈亮色底（实测亮度=$lum）", lum > 180f)
                }
                return
            } catch (e: IllegalArgumentException) {
                last = e
                Thread.sleep(300)
                compose.waitForIdle()
            }
        }
        throw IllegalStateException("采样表单底色失败", last)
    }

    /** 恢复设备全局配置并读回断言（appearanceMode 是应用内状态，随 graph 销毁不残留）。 */
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

    private fun setContentFromRealRoot() {
        compose.setContent {
            val stateHolder = holder
            // 亮暗由 FineCatApp 内层按 stateHolder.appearanceMode 决定（FineCatApp.kt:194），
            // 外层再包 FineCatTheme(darkTheme=…) 会被内层覆盖。
            FineCatTheme { FineCatApp(stateHolder = stateHolder) }
        }
        compose.waitForIdle()
    }

    /**
     * 真实设置入口：首页空态 → 抽屉 → 设置按钮 → 设置整页。
     * activity 重建（矩阵配置切换）后 holder 状态保留，重建后直接是空态首页。
     * 自愈重试：偶发首次 menu 点击后抽屉不出现（注入/重组时序），重试可开；
     * 3 次仍开不了则是真缺陷，留现场全量 tag 报错。
     */
    private fun openSettingsViaRealEntry() {
        if (!this::holder.isInitialized) return
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }
        var last: Throwable? = null
        repeat(3) { attempt ->
            println("[F01-entry] 打开设置入口：第 ${attempt + 1}/3 次尝试")
            try {
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.menu").performClick()
                compose.waitForIdle()
                waitUntilTagPresent("finecat.drawerSearch")
                compose.onNodeWithTag("finecat.drawerSettings").performClick()
                compose.waitForIdle()
                waitUntilTagPresent("finecat.settingsScreen")
                assertFalse("设置页打开后不应存在 Provider 列表层", listLayerPresent())
                return
            } catch (e: Throwable) {
                last = e
                // 抽屉已开但后续失败：先点遮罩右侧复位（关抽屉）再重试
                if (compose.onAllNodesWithTag("finecat.drawerSearch").fetchSemanticsNodes()
                        .isNotEmpty()
                ) {
                    compose.onRoot().performTouchInput {
                        click(Offset(width * 0.95f, height * 0.5f))
                    }
                    compose.waitForIdle()
                }
            }
        }
        throw AssertionError(
            "设置入口 3 次尝试未到达 settingsScreen（重试可开=注入时序，不可开=真缺陷）；" +
                "现场 tag：${dumpTags()}",
            last,
        )
    }

    /** 主窗口语义树全部 testTag（诊断用）。 */
    private fun dumpTags(): String = buildString {
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                ?.let { append(' ').append(it) }
            node.children.forEach { walk(it) }
        }
        runCatching { walk(compose.onRoot().fetchSemanticsNode()) }
            .onFailure { append("<遍历失败: ${it.message}>") }
    }

    /** 从设置页点击指定 Provider 行 1 次直达编辑表单（滚动进场 + 1 次点击）。 */
    private fun openEditFormFor(providerId: String) {
        val row = compose.onNodeWithTag("finecat.providerRow.$providerId")
        row.performScrollTo()
        compose.waitForIdle()
        row.performClick()
        waitUntilTagPresent("finecat.providerEdit")
    }

    /** 断言编辑表单回填的是该 Provider 的值（id/名称/URL 严格一致）。 */
    private fun assertEditFormShows(id: String, name: String, baseUrl: String) {
        compose.onNodeWithTag("finecat.formId")
            .assertTextContains("ID $id · 不可修改")
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetText))
        compose.onNodeWithTag("finecat.formName")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(name)),
            )
        compose.onNodeWithTag("finecat.formBaseUrl")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(baseUrl),
                ),
            )
    }

    private fun assertFieldEmpty(tag: String) {
        compose.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    /** 根据当前卡片边界点外部，兼容近全屏和键盘弹起后的高度。 */
    private fun dismissEditFormByScrimTap() {
        val rootTop = compose.onRoot().fetchSemanticsNode().boundsInRoot.top
        val cardTop = compose.onAllNodesWithTag("finecat.sheetCard").fetchSemanticsNodes().last().boundsInRoot.top
        assertTrue("表单外必须留有确认区域", cardTop > rootTop)
        compose.onRoot().performTouchInput {
            click(Offset(width / 2f, (cardTop - rootTop) / 2f))
        }
        compose.waitForIdle()
    }

    private fun listLayerPresent(): Boolean =
        compose.onAllNodesWithTag("finecat.providerSheet").fetchSemanticsNodes().isNotEmpty()

    private fun scrollToInForm(tag: String, hostTag: String = "finecat.providerEdit") {
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(hostTag)) and hasAnyDescendant(hasTestTag(tag)))
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /** providers 快照：data class 值比较（严格相等断言用）。 */
    private fun providersSnapshot() = holder.providers.toList()

    /** 等指定写帧出现；超时报出已收到的各帧计数（定位卡点用）。 */
    private fun awaitProviderFrame(type: String, atLeast: Int, what: String) {
        try {
            runBlocking { waitUntil { transport.payloadsOfType(type).size >= atLeast } }
        } catch (e: Exception) {
            val got = listOf("provider-put", "provider-test", "provider-delete")
                .joinToString(" ") { "$it=${transport.payloadsOfType(it).size}" }
            throw AssertionError("等待 $type ≥$atLeast 超时（$what）；已收到：$got", e)
        }
    }

    /** 系统级 Back（与 Save 回归测试同口径：uiAutomation 注入 KEYCODE_BACK）。 */
    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_BACK").close()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    /** 测试进程内注入/读取系统配置（矩阵用例专用，finally 恢复）。 */
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .bufferedReader().use { it.readText() }
    }

    /** 整屏截图存 external filesDir（capture 拉取归领），文件名即证据名。 */
    private fun saveShot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { out ->
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun latestProbeToken(): String = transport.payloadsOfType("provider-test").last()
        .getValue("providerTestId").jsonPrimitive.content

    /**
     * 接上假网关。[requestTimeoutMillis] 非空时覆盖运行时请求超时，用于让「请求超时」
     * 这条终态在用例里可及时发生（生产默认 30s，见 GatewayRuntime 的
     * `DEFAULT_REQUEST_TIMEOUT_MILLISECONDS`）。
     */
    private fun connectFakeGateway(requestTimeoutMillis: Long? = null, helloFrame: String = HELLO_CONNECTED_FRAME) {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            requestTimeoutOverride = requestTimeoutMillis,
        )
        holder = graph.stateHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(helloFrame)
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
    }

    private fun waitUntilTagPresent(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntilTagGone(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * 保存成功后等编辑表单收起，超时则把 holder 终态写进失败消息。
     *
     * 裸 [waitUntilTagGone] 超时只抛 `ComposeTimeoutException`，不带任何上下文：
     * 看不出是回执没到、归属没认上，还是认上了但收卡没走。保存链路的排查几乎全靠这几个字段。
     */
    private fun waitUntilEditFormGoneAfterSave(label: String) {
        // 收帧后立刻取一次快照：回执到达的同一帧内 holder 就该有结论，
        // 1s 后才取就分不清「消息没到/被 4s 清除」和「effect 压根没跑」。
        compose.waitForIdle()
        val rightAfterReceipt = snapshot()
        val timeout = runCatching {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()
            }
        }
        if (timeout.isSuccess) return
        throw AssertionError(
            "保存成功后 10s 内编辑表单未收起（$label）：\n" +
                "  收帧后立即：$rightAfterReceipt\n" +
                "  超时之后：${snapshot()}",
            timeout.exceptionOrNull(),
        )
    }

    private fun snapshot(): String =
        "providerResult=${holder.providerResult} saveIsLoading=${holder.providerSaveIsLoading} " +
            "listIsLoading=${holder.providerListIsLoading} " +
            "providers=${holder.providers.map { "${it.id}/${it.name}" }} " +
            "putCount=${transport.payloadsOfType("provider-put").size} " +
            "editNodes=${compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().size} " +
            "sheetNodes=${compose.onAllNodesWithTag("finecat.providerSheet").fetchSemanticsNodes().size}"

    private companion object {
        /** 归属用例共用的 provider id（各条用例各自独立跑，互不干扰）。 */
        const val NEW_SAVE_ID = "f01-attribution"
        const val SAVE_BASE_URL = "https://f01-save.example/v1"
        const val MODEL_ID = "f01-save-model"
        const val PROVIDER_A_ID = "f01-a"
        const val PROVIDER_A_NAME = "F01 样例甲"
        const val PROVIDER_A_BASE_URL = "https://f01-a.example/v1"
        const val PROVIDER_B_ID = "f01-b"
        const val PROVIDER_B_NAME = "F01 样例乙"
        const val PROVIDER_B_BASE_URL = "https://f01-b.example/v1"

        /**
         * A、B 两个 Provider 的真实帧格式（合成假数据，无真实凭据）。
         * 出处与 F00 样例同：shared/.../protocol/GatewayDtos.kt:326-358、
         * AndroidSharedStateHolder.kt:2325 handleProviderFrame。
         */
        val PROVIDERS_AB_FRAME =
            """{"kind":"providers","providers":[""" +
                """{"id":"$PROVIDER_A_ID","name":"$PROVIDER_A_NAME","baseUrl":"$PROVIDER_A_BASE_URL",""" +
                """"apiKeySet":true,"api":"openai-completions","active":true,"models":[""" +
                """{"id":"f01-a-model","api":"openai-completions","reasoning":false,""" +
                """"input":["text"],"contextWindow":131072,"maxTokens":8192}]},""" +
                """{"id":"$PROVIDER_B_ID","name":"$PROVIDER_B_NAME","baseUrl":"$PROVIDER_B_BASE_URL",""" +
                """"apiKeySet":true,"api":"anthropic-messages","active":true,"models":[""" +
                """{"id":"f01-b-model","api":"anthropic-messages","reasoning":true,""" +
                """"input":["text"],"contextWindow":65536,"maxTokens":4096}]}]}"""

        /** 会话列表应答（空列表）：把连接建立后那条 `sessions` 请求也回掉。 */
        const val SESSIONS_EMPTY_FRAME = """{"kind":"sessions","sessions":[]}"""

        /** 建连应答（重连时复用同一份）。 */
        const val HELLO_CONNECTED_FRAME =
            """{"kind":"hello","connection":{"state":"connected"},"sessions":[],""" +
                """"contextUsage":null,"model":null,"capabilities":["providers","provider-put","provider-delete","provider-test","provider-test-id-v1","provider-models","models","sessions"]}"""

        /** 保存成功应答；普通编辑不以历史诊断状态作为保存许可。 */
        fun providerSavedFrame(id: String, name: String): String =
            """{"kind":"provider-saved","admitted":true,"refreshed":true,"provider":{"id":"$id",""" +
                """"name":"$name","baseUrl":"https://f01-save.example/v1","apiKeySet":true,""" +
                """"active":true,"models":[{"id":"f01-save-model","api":"openai-completions",""" +
                """"reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192}],""" +
                """"status":{"ok":true}}}"""
    }
}
