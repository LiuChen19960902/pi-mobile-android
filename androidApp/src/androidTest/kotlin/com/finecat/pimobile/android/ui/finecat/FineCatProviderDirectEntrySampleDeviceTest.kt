package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F00 取证样例：设置页点 Provider 行应直达该项编辑表单（预期失败）。
 *
 * 原型依据（FineCat/prototype/app.js:841-846）：`pv-sheet` 按行上带的 id 直接进该项表单，
 * `pv-new` 直接进空表单，设置页的 Provider 行与表单之间没有中间列表层。
 *
 * 本测试在当前代码下**必须失败**：FineCatApp 把设置页传出的 provider id 丢弃了，
 * 打开的只是 Provider 列表层，而不是 A 的编辑表单。F01 修好接线后，本测试应通过，
 * 因此断言按“修好后成立”的形态写，不为通过而放松。
 *
 * 只新建本文件取证，不改生产代码、不提前实施 F01。
 */
@RunWith(AndroidJUnit4::class)
class FineCatProviderDirectEntrySampleDeviceTest {

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

    /**
     * F01 样例：设置页点 Provider A 行，直接显示 A 的编辑表单。
     *
     * 路径：生产 FineCatApp 根 → 空态首页 → 抽屉 → 设置入口 → 设置整页 → 点 A 行。
     * 当前必然失败（打开的是 Provider 列表层），失败信息即取证结论。
     */
    @Test
    fun f01_providerRowA_opensEditFormDirectly() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        // 空态首页：无选中会话（与 FineCatHomeParityDeviceTest 同一口径）
        compose.runOnIdle { holder.prepareNewSession() }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }

        // 网关状态里带 A、B 两个 Provider（真实 providers 帧格式，见文件头注释）
        transport.receive(PROVIDERS_AB_FRAME)
        runBlocking { waitUntil { holder.providers.size == 2 } }
        compose.waitForIdle()

        // 真实设置入口：抽屉 dbar 的设置按钮（tag 出处见 FineCatSessionDrawer）
        compose.onNodeWithTag("finecat.menu").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.drawerSearch")
        compose.onNodeWithTag("finecat.drawerSettings").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.settingsScreen")

        // 设置整页的 Provider A 行（tag 出处见 FineCatSettingsScreen）：滚进视口再点
        val rowA = compose.onNodeWithTag("finecat.providerRow.$PROVIDER_A_ID")
        rowA.performScrollTo()
        compose.waitForIdle()
        rowA.performClick()
        compose.waitForIdle()

        // 等 UI 对点击做出响应：编辑表单与列表层任一出现即继续（不等死某一层，
        // 否则 F01 修好后反而超时）。当前代码下出现的是列表层。
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("finecat.providerSheet").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        try {
            // 主断言（当前必然失败）：A 的编辑表单直接出现
            val editShown = compose.onAllNodesWithTag("finecat.providerEdit")
                .fetchSemanticsNodes().isNotEmpty()
            val listShown = compose.onAllNodesWithTag("finecat.providerSheet")
                .fetchSemanticsNodes().isNotEmpty()
            assertTrue(
                "预期（原型 pv-sheet，app.js:841）：点击 Provider A 直接进入 A 的编辑表单 " +
                    "(finecat.providerEdit，表单带 A 的名称/URL)；实际：编辑表单出现=$editShown，" +
                    "中间 Provider 列表层出现=$listShown。当前接线 FineCatApp.kt:1091 " +
                    "`onEditProvider = { providerSheetOpen = true }` 丢弃了设置页传出的 provider id，" +
                    "FineCatProviderSheet.kt:88 editingId 初始 null，所以只打开列表，打不开 A 的表单。",
                editShown,
            )
            // 不得出现中间 Provider 列表层（原型行与表单之间无中转）
            assertTrue(
                "预期（原型 pv-sheet）：点 Provider A 直达 A 表单，不得出现中间 Provider 列表层；" +
                    "实际：列表层出现=$listShown",
                !listShown,
            )
            // 表单字段带 A 的名称/URL 值（FormField 回填既有 provider，与 B 可区分）
            compose.onNodeWithTag("finecat.formName")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString(PROVIDER_A_NAME),
                    ),
                )
            compose.onNodeWithTag("finecat.formBaseUrl")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString(PROVIDER_A_BASE_URL),
                    ),
                )
        } finally {
            // 失败路径也留图：拍到的是点击 A 行后实际打开的那一层（当前是列表层）
            saveShot("f01-sample.png")
        }
    }

    /** 建合成网关 graph、连上（Open + hello → CONNECTED），与既有 parity 用例同口径。 */
    private fun connectFakeGateway() {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
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

    /** 等 tag 出现（进场动画/异步发布都不稳定，按 tag 轮询，不用 Thread.sleep）。 */
    private fun waitUntilTagPresent(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 整屏截图存 external filesDir（供 capture 拉取），文件名按任务要求固定。 */
    private fun saveShot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { out ->
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private companion object {
        /** A 的 id（设置页行 tag finecat.providerRow.f01-a 的后缀）。 */
        const val PROVIDER_A_ID = "f01-a"

        /** A 的名称（B 用不同名称，断言可区分两者）。 */
        const val PROVIDER_A_NAME = "F01 样例甲"

        /** A 的 Base URL（B 用不同 URL，断言可区分两者）。 */
        const val PROVIDER_A_BASE_URL = "https://f01-a.example/v1"

        /**
         * A、B 两个 Provider 的真实帧。
         *
         * 格式出处：shared/.../protocol/GatewayDtos.kt:63-64（GatewayFrame.providers 字段注释
         * “providers 应答：pi models.json 的凭证盲投影”）与 :326-358（GatewayProvider /
         * GatewayProviderModel 字段）；holder 接收入口
         * AndroidSharedStateHolder.kt:2325 handleProviderFrame `"providers" -> providers = ...`；
         * 同格式既有用例：FineCatHomeParityDeviceTest.settings_providerRow... 与
         * FineCatProviderRegressionDeviceTest.invalidContextWindow...（均为合成假数据，
         * 无真实凭据）。
         */
        val PROVIDERS_AB_FRAME =
            """{"kind":"providers","providers":[""" +
                """{"id":"f01-a","name":"$PROVIDER_A_NAME","baseUrl":"$PROVIDER_A_BASE_URL",""" +
                """"apiKeySet":true,"api":"openai-completions","active":true,"models":[""" +
                """{"id":"f01-a-model","api":"openai-completions","reasoning":false,""" +
                """"input":["text"],"contextWindow":131072,"maxTokens":8192}]},""" +
                """{"id":"f01-b","name":"F01 样例乙","baseUrl":"https://f01-b.example/v1",""" +
                """"apiKeySet":true,"api":"anthropic-messages","active":true,"models":[""" +
                """{"id":"f01-b-model","api":"anthropic-messages","reasoning":true,""" +
                """"input":["text"],"contextWindow":65536,"maxTokens":4096}]}]}"""
    }
}
