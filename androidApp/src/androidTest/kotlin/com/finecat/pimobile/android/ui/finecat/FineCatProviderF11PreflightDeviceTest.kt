package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
import com.finecat.pimobile.android.testOnMain
import com.finecat.pimobile.android.waitUntil
import com.finecat.pimobile.shared.gateway.ProviderModelUpsert
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F11 开始前的专用前置 fixture。
 *
 * 这里验证协议和保存生命周期，暂不声称已经实现 thinking map 编辑 UI：
 * - holder 真实序列化已知档位改值、显式 null，以及空 map 的省略语义；
 * - provider-saved 之后确实发起独立 providers 回读，并以回读的完整 map 更新状态；
 * - 真实 Provider 编辑卡打开、改草稿、Back 取消、再次打开，整个过程不产生 provider-put。
 */
@RunWith(AndroidJUnit4::class)
class FineCatProviderF11PreflightDeviceTest {
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

    private fun connect(): FakeTransport {
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
        holder = appGraph.stateHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3}""")
            waitUntil { holder!!.gatewayState.connection.name == "CONNECTED" }
        }
        return transport
    }

    private fun connectAndShowProviderSheet(): FakeTransport {
        val transport = connect()
        compose.setContent {
            FineCatTheme {
                FineCatProviderSheet(stateHolder = holder!!, onDismiss = {})
            }
        }
        compose.waitForIdle()
        return transport
    }

    private fun providersFrame(
        id: String,
        name: String,
        thinkingLevelMap: String,
    ): String =
        """{"kind":"providers","providers":[{"id":"$id","name":"$name","baseUrl":"https://api.example.invalid/v1","apiKeySet":false,"active":true,"models":[{"id":"example-model","api":"openai-completions","reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192,"thinkingLevelMap":$thinkingLevelMap}],"status":{"ok":true}}]}"""

    private fun providerSavedFrame(id: String, name: String): String =
        """{"kind":"provider-saved","admitted":true,"refreshed":true,"provider":{"id":"$id","name":"$name","baseUrl":"https://api.example.invalid/v1","apiKeySet":false,"active":true,"models":[{"id":"example-model","api":"openai-completions","reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192,"thinkingLevelMap":{"off":null,"low":"low","medium":"high","high":"high","xhigh":"xhigh","max":"max"}}]}}"""

    @Test
    fun providerPutThinkingMap_fixtureContainsChangedValuesExplicitNull_andOmitsEmptyMap() {
        val transport = connect()
        val initial = linkedMapOf(
            "off" to null,
            "low" to "low",
            "medium" to "low",
            "high" to "high",
            "xhigh" to "xhigh",
            "max" to "max",
        )
        val changed = initial.toMutableMap().apply { this["medium"] = "high" }
        val id = "f11-wire"

        testOnMain {
            holder!!.saveProvider(
                id = id,
                baseUrl = "https://api.example.invalid/v1",
                apiKey = null,
                models = listOf(
                    ProviderModelUpsert(
                        id = "changed-model",
                        api = "openai-completions",
                        contextWindow = 131072,
                        maxTokens = 8192,
                        thinkingLevelMap = changed,
                    ),
                    ProviderModelUpsert(
                        id = "empty-map-model",
                        api = "openai-completions",
                        contextWindow = 131072,
                        maxTokens = 8192,
                        thinkingLevelMap = emptyMap(),
                    ),
                ),
            )
        }
        runBlocking { waitUntil { transport.payloadsOfType("provider-put").isNotEmpty() } }

        val putModels = transport.payloadsOfType("provider-put").single().getValue("models").jsonArray
        val changedModel = putModels.first { it.jsonObject.getValue("id").jsonPrimitive.content == "changed-model" }.jsonObject
        val emptyMapModel = putModels.first { it.jsonObject.getValue("id").jsonPrimitive.content == "empty-map-model" }.jsonObject
        val wireMap = changedModel.getValue("thinkingLevelMap").jsonObject

        assertEquals("high", wireMap.getValue("medium").jsonPrimitive.content)
        assertEquals("high", wireMap.getValue("high").jsonPrimitive.content)
        assertEquals(JsonNull, wireMap.getValue("off"))
        assertEquals(setOf("off", "low", "medium", "high", "xhigh", "max"), wireMap.keys)
        assertTrue("非空 map 必须保留 thinkingLevelMap 字段", "thinkingLevelMap" in changedModel)
        assertFalse("空 map 沿现有协议省略字段", "thinkingLevelMap" in emptyMapModel)
    }

    @Test
    fun providerSaved_thenProvidersReadback_returnsCompletePersistedThinkingMap() {
        val transport = connect()
        val id = "f11-roundtrip"
        val initialProviders = transport.payloadsOfType("providers").size
        val persistedMap = linkedMapOf(
            "off" to null,
            "low" to "low",
            "medium" to "high",
            "high" to "high",
            "xhigh" to "xhigh",
            "max" to "max",
        )

        testOnMain {
            holder!!.saveProvider(
                id = id,
                baseUrl = "https://api.example.invalid/v1",
                apiKey = null,
                models = listOf(
                    ProviderModelUpsert(
                        id = "example-model",
                        api = "openai-completions",
                        contextWindow = 131072,
                        maxTokens = 8192,
                        thinkingLevelMap = persistedMap,
                    )
                ),
            )
        }
        runBlocking { waitUntil { transport.payloadsOfType("provider-put").isNotEmpty() } }
        assertEquals(
            "provider-saved 未到达前不应提前发起保存后的 providers 回读",
            initialProviders,
            transport.payloadsOfType("providers").size,
        )

        val putIndex = transport.sentTypes.indexOfLast { it == "provider-put" }
        transport.receive(providerSavedFrame(id, "F11 保存回执"))
        runBlocking {
            waitUntil { transport.payloadsOfType("providers").size > initialProviders }
        }
        val typesAfterSaved = transport.sentTypes.drop(putIndex + 1)
        assertTrue("provider-saved 后必须出现独立 providers 请求", "providers" in typesAfterSaved)

        transport.receive(providersFrame(id, "F11 持久化回读", """{"off":null,"low":"low","medium":"high","high":"high","xhigh":"xhigh","max":"max"}"""))
        runBlocking {
            waitUntil {
                holder!!.providers.firstOrNull { it.id == id }
                    ?.models?.firstOrNull { it.id == "example-model" }
                    ?.thinkingLevelMap == persistedMap
            }
        }
        assertEquals(persistedMap, holder!!.providers.single { it.id == id }.models.single().thinkingLevelMap)
    }

    @Test
    fun cancelProviderEdit_thenReopen_keepsDraftOutOfWire() {
        val transport = connectAndShowProviderSheet()
        val id = "f11-cancel"
        transport.receive(providersFrame(id, "取消前名称", """{"off":null,"medium":"low"}"""))
        runBlocking { waitUntil { holder!!.providers.any { it.id == id } } }
        compose.waitForIdle()

        compose.onNodeWithTag("finecat.providerRow.$id").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("finecat.formName").performTextClearance()
        compose.onNodeWithTag("finecat.formName").performTextInput("取消后的草稿")

        // 外点确认会保存；明确的取消入口才应丢弃这份临时草稿。
        compose.onNodeWithContentDescription("取消编辑").performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()
        }

        assertTrue("取消编辑完整生命周期不应产生 provider-put", transport.payloadsOfType("provider-put").isEmpty())
        compose.onNodeWithTag("finecat.providerRow.$id").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("finecat.formName")
            .assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("取消前名称"),
            ))
        assertTrue("重新打开后仍不应产生 provider-put", transport.payloadsOfType("provider-put").isEmpty())
    }
}
