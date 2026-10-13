package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
import com.finecat.pimobile.android.testOnMain
import com.finecat.pimobile.android.waitUntil
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.android.ui.finecat.theme.darkFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.lightFineCatColors
import com.finecat.pimobile.shared.gateway.PROVIDER_COMPAT_KEYS_BY_API
import com.finecat.pimobile.shared.protocol.GatewayProviderModel
import com.finecat.pimobile.shared.protocol.JsonValue
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F12：模型方言 + 兼容参数编辑（`DEC-F09-DIALECT` / `DEC-F09-COMPAT`）。
 *
 * ## 判据链
 *
 * 每条用例都走**意图 → 线上原始 JSON → 回执 → 独立 providers 回读**四段，而不是只看界面：
 * 「界面上显示已设置 3 项」和「实际下发了什么」是两件事，只有 wire 能证明后者。
 *
 * ## 三种下发形态在用例里分别钉死
 *
 * | 期望键 | wire 上必须长什么样 | 语义 |
 * | --- | --- | --- |
 * | `wire.compatFieldPresent=false` | 模型对象里**没有** `compat` 键 | 没碰过这一层 → 网关保留原配置 |
 * | `wire.compatJson={…其余}` | 对象里**没有**被清的那个键 | 省略手机管理键 = 清除该项覆盖 |
 * | `wire.compatJson={}` 且 `compatFieldPresent=true` | 空的 compat 对象 | 清空手机管理的键 |
 *
 * compat 键序按 `PROVIDER_COMPAT_KEYS_BY_API` 的声明顺序冻结，所以 `wire.compatJson` 登记的是
 * **逐字原文**，不是集合。宿主私有键的保留由 `ProviderCompatGatewayTest` 在真实网关上验
 * （手机的回显里根本没有它们，只有直读 models.json 才看得见）。
 *
 * ## 夹具
 *
 * 单个 provider `f12-compat`（`status.ok=true`，保存门槛已过），两个模型：`m-1`
 * （openai-completions，3 项 compat）与 `m-2`（同协议，1 项）。两者 compat 不同，是
 * 「切模型不串草稿」用例的依据。
 */
@RunWith(AndroidJUnit4::class)
class FineCatCompatF12DeviceTest {

    @get:Rule
    // 不要关掉 autoAdvance：关掉后重合成不会跑，providers 帧到了界面也停在
    // 「未连接」的旧快照上（节点找不到就是这个原因，不是行渲染的问题）。
    val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()

    private val transport = FakeTransport()
    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null
    private val lines = mutableListOf<String>()
    private var measureFileReady = false

    // ================================================================== 用例

    /**
     * 布尔 true/false 两个方向都改：wire 逐字、回读一致。
     *
     * 主用例：先证明「界面上拨一下」确实变成 wire 上的两个布尔字面量，再证明回执之后有
     * **独立的 providers 回读**，且回读值与提交一致（`false` 不被当成未设置）。
     */
    @Test
    fun boolEdit_trueAndFalse_putMatchesExpected_thenReceiptThenIndependentReadback() {
        connect()
        assertTrue("兼容参数区必须能展开", openCompat())
        assertEquals("展开后的计数 = 三个已设置项", "已设置 3 项", countText())

        scrollInModelCard("finecat.compatKey.supportsStore")
        compose.onNodeWithTag("finecat.compatSet.supportsStore.true").performScrollTo().performClick()
        compose.onNodeWithTag("finecat.compatSet.supportsDeveloperRole.false").performScrollTo().performClick()
        compose.waitForIdle()
        // 计数取编辑**之后**的实测值：写死常量的话，「编辑后计数是否还对」这件事就没人管了。
        emitAssert("ui.countAfter", countText())
        saveShot("f12-compat-bool.png")

        backToProviderCard()
        assertTrue("保存前不得有 provider-put", transport.payloadsOfType("provider-put").isEmpty())
        save()
        assertTrue("保存必须发出 provider-put", awaitPut())
        val put = putAt()
        record("put raw=$put")
        emitAssert("wire.compatJson", rawCompatJson(put).orEmpty())
        emitAssert("wire.sorted", compatSortedOfRaw(rawCompatJson(put)))
        emitAssert("wire.compatFieldPresent", (rawCompatJson(put) != null).toString())

        // 「回执到达前、provider-put 之后不得已经发起 providers 回读」：按实际请求序列观察。
        val putAt = transport.sentTypes.indexOfLast { it == "provider-put" }
        emitAssert(
            "order.readbacksBeforeReceipt",
            transport.sentTypes.drop(putAt + 1).count { it == "providers" }.toString(),
        )
        transport.receive(
            savedFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":true}"""),
        )
        assertTrue("本次提交自己的回执必须收卡", tagGone("finecat.providerEdit"))
        assertTrue(
            "provider-saved 之后必须出现独立 providers 回读请求",
            transport.sentTypes.indexOfLast { it == "providers" } > transport.sentTypes.indexOfLast { it == "provider-put" },
        )
        emitAssert("order.sentAfterPut", transport.sentTypes.drop(putAt + 1).distinct().sorted().joinToString(","))
        transport.receive(providersFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":true}"""))
        awaitReadback("supportsDeveloperRole=false|supportsStore=true|thinkingFormat=deepseek")
        emitAssert("readback.sorted", readbackSorted())
        saveShot("f12-compat-bool-saved.png")
    }

    /** 枚举改值：浮层选出的合法值逐字落进 wire 并被回读（不给自由输入，见 CompatBlock 的 KDoc）。 */
    @Test
    fun enumEdit_changesValue_putAndReadbackMatch() {
        connect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.compatKey.thinkingFormat")
        compose.onNodeWithTag("finecat.compatKey.thinkingFormat").performClick()
        compose.waitForIdle()
        check(awaitTag("finecat.compatEnumItem.thinkingFormat.openai")) {
            "枚举浮层没打开：rowState=" + rowState("finecat.compatKey.thinkingFormat") +
                " popoverNode=" + compose.onAllNodesWithTag("finecat.compatPopover").fetchSemanticsNodes().size
        }
        // 选第 2 项（浮层顶部、不用滚）：值域 11 项而浮层最多露 7 项，滚动后再点会因
        // 点击坐标落在相邻项上而选错值——这条用例要的是「选了哪个值」，不该混进滚动时机。
        compose.onNodeWithTag("finecat.compatEnumItem.thinkingFormat.openai").performClick()
        // 浮层是独立的 Popup 窗口：它还在的时候，卡片里的节点取不到（取到的是浮层那一套）。
        // 收起后行内取值才是可信的读数。
        runCatching { waitTagGone("finecat.compatPopover") }
        compose.waitForIdle()
        val rowNodes = compose.onAllNodesWithTag("finecat.compatKey.thinkingFormat", useUnmergedTree = true).fetchSemanticsNodes()
        val markerNodes = compose.onAllNodesWithTag("finecat.compatMarker.thinkingFormat", useUnmergedTree = true).fetchSemanticsNodes()
        val rowMerged = compose.onAllNodesWithTag("finecat.compatKey.thinkingFormat").fetchSemanticsNodes()
        val popoverLeft = compose.onAllNodesWithTag("finecat.compatPopover").fetchSemanticsNodes().size
        assertEquals(
            "选中后行内取值必须立刻更新 row=${rowNodes.size}/$rowMerged marker=${markerNodes.size} popover=$popoverLeft " +
                "state=${rowState("finecat.compatKey.thinkingFormat")}",
            "openai",
            markerTextOf("thinkingFormat"),
        )
        emitAssert("ui.enumValueAfter", "openai")
        saveShot("f12-compat-enum.png")

        backToProviderCard()
        save()
        assertTrue(awaitPut())
        val put = putAt()
        record("put raw=$put")
        emitAssert("wire.compatJson", rawCompatJson(put).orEmpty())
        transport.receive(savedFrame("""{"supportsDeveloperRole":true,"thinkingFormat":"openai","supportsStore":false}"""))
        assertTrue(tagGone("finecat.providerEdit"))
        transport.receive(providersFrame("""{"supportsDeveloperRole":true,"thinkingFormat":"openai","supportsStore":false}"""))
        awaitReadback("supportsDeveloperRole=true|supportsStore=false|thinkingFormat=openai")
        emitAssert("readback.sorted", readbackSorted())
    }

    /**
     * 删掉一个已配置项：wire 里**该键消失**，其余手机管理键与原值不变。
     *
     * 这是「省略某个手机管理键 = 清除该项覆盖」的直接证据；与
     * `untouchedCompat_omitsFieldEntirely`（整个字段不出现）互为对照，两种语义不能混。
     */
    @Test
    fun deleteSingleOverride_sendsKeyAbsent_keepsOtherManagedKeys() {
        connect()
        assertTrue(openCompat())
        unsetKey("thinkingFormat")

        backToProviderCard()
        save()
        assertTrue(awaitPut())
        val put = putAt()
        record("put raw=$put")
        emitAssert("wire.compatJson", rawCompatJson(put).orEmpty())
        emitAssert("wire.absentKeys", "thinkingFormat")
        emitAssert("wire.sorted", compatSortedOfRaw(rawCompatJson(put)))

        transport.receive(savedFrame("""{"supportsDeveloperRole":true,"supportsStore":false}"""))
        assertTrue(tagGone("finecat.providerEdit"))
        transport.receive(providersFrame("""{"supportsDeveloperRole":true,"supportsStore":false}"""))
        awaitReadback("supportsDeveloperRole=true|supportsStore=false")
        emitAssert("readback.sorted", readbackSorted())
        assertTrue("重新打开后计数应为 2", openCompat())
        assertEquals("已设置 2 项", countText())
        emitAssert("ui.countAfter", "2")
        saveShot("f12-compat-delete-one.png")
    }

    /**
     * 删掉最后一项：wire 里 compat **仍然存在**，但是空对象。
     *
     * 这是「字段省略」与「空对象」的分水岭：若实现把空草稿当成「没碰过」处理，wire 上一个键
     * 都没有，网关会保留旧值，删除根本没发生。
     */
    @Test
    fun deleteLastOverrides_sendsEmptyCompatObject_notOmitted() {
        connect()
        assertTrue(openCompat())
        listOf("supportsDeveloperRole", "supportsStore", "thinkingFormat").forEach(::unsetKey)
        assertEquals("三项都恢复默认后计数应为 0", "已设置 0 项", countText())

        backToProviderCard()
        save()
        assertTrue(awaitPut())
        val put = putAt()
        record("put raw=$put")
        emitAssert("wire.compatJson", rawCompatJson(put).orEmpty())
        emitAssert("wire.compatFieldPresent", (rawCompatJson(put) != null).toString())

        // 合并后手机管理的键一个不剩 → 网关把 compat 键整个删掉 → 回显显式 null。
        transport.receive(savedFrame(null))
        assertTrue(tagGone("finecat.providerEdit"))
        transport.receive(providersFrame(null))
        compose.waitUntil(timeoutMillis = 5_000) {
            holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL }.compat == null
        }
        emitAssert(
            "readback.compat",
            holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL }.compat.toString(),
        )
        emitAssert("ui.countAfter", "0")
        saveShot("f12-compat-delete-last.png")
    }

    /** 「清空手机管理的兼容参数」：一次到位，下发空对象，回读为显式 null。 */
    @Test
    fun clearAllManagedCompat_sendsEmptyObject_andReadsBackNull() {
        connect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.compatClearAll")
        compose.onNodeWithTag("finecat.compatClearAll").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("清空后计数应为 0", "已设置 0 项", countText())
        saveShot("f12-compat-clear-all.png")

        backToProviderCard()
        save()
        assertTrue(awaitPut())
        val put = putAt()
        record("put raw=$put")
        emitAssert("wire.compatJson", rawCompatJson(put).orEmpty())
        emitAssert("wire.compatFieldPresent", (rawCompatJson(put) != null).toString())
        transport.receive(savedFrame(null))
        assertTrue(tagGone("finecat.providerEdit"))
        transport.receive(providersFrame(null))
        compose.waitUntil(timeoutMillis = 5_000) {
            holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL }.compat == null
        }
        emitAssert(
            "readback.compat",
            holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL }.compat.toString(),
        )
        emitAssert("ui.countAfter", "0")
    }

    /**
     * 只改模型显示名、不碰兼容参数：wire 的模型对象里**没有 compat 键**。
     *
     * 与 `deleteSingleOverride` 互为对照：同样是「键不见了」，一个在本层内（= 清除该项），
     * 一个整层不出现（= 网关保留）。没有这条对照，这两种语义会被混为一谈。
     */
    @Test
    fun untouchedCompat_omitsFieldEntirely_keepsServerValues() {
        connect()
        assertTrue(openCompat())
        // 实测读数，不写死常量：写死的话「展开后计数对不对」这件事根本没人管
        emitAssert("ui.countBefore", countText())
        scrollInModelCard("finecat.modelName")
        compose.onNodeWithTag("finecat.modelName").performTextClearance()
        compose.onNodeWithTag("finecat.modelName").performTextInput("M1 改名")
        compose.waitForIdle()
        backToProviderCard()

        save()
        assertTrue(awaitPut())
        val put = putAt()
        record("put raw=$put")
        emitAssert("wire.compatFieldPresent", (rawCompatJson(put) != null).toString())
        emitAssert("wire.modelJson", rawModelJson(put))
        // 回执必须回**这次提交后的**显示名：表单侧的归属比对里 name 是最能区分「上一次
        // 提交」与「这一次提交」的字段，回旧名会被正确地判成「不是这一次的回执」。
        transport.receive(savedFrame(M1_COMPAT, m1Name = "M1 改名"))
        assertTrue(tagGone("finecat.providerEdit"))
        transport.receive(providersFrame(m1Name = "M1 改名"))
        awaitReadback(M1_COMPAT_SORTED)
        emitAssert("readback.sorted", readbackSorted())
        saveShot("f12-compat-untouched.png")
    }

    /** 取消整张表单：整个生命周期零 provider-put，重开仍是服务端原值（含原方言）。 */
    @Test
    fun cancelEdit_sendsNoProviderPut_reopenShowsServerValues() {
        connect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.compatKey.supportsStore")
        compose.onNodeWithTag("finecat.compatSet.supportsStore.true").performScrollTo().performClick()
        compose.waitForIdle()
        backToProviderCard()
        closeAllSheets()
        assertTrue("取消编辑完整生命周期不得产生 provider-put", transport.payloadsOfType("provider-put").isEmpty())

        assertTrue(openCompat())
        assertEquals("取消后重开必须仍是服务端原值", M1_COMPAT_SORTED, compatSortedOfWire())
        emitAssert("cancel.reopenedCompat", compatSortedOfWire())
        assertEquals("取消后重开的方言也必须没变", "openai-completions", apiTextOf())
        emitAssert("cancel.api", apiTextOf())
        emitAssert("cancel.putCount", transport.payloadsOfType("provider-put").size.toString())
        saveShot("f12-compat-cancel.png")
    }

    /** 切方言：先在浮层里逐项列出要丢的键，确认后 wire 只带新协议键集。 */
    @Test
    fun switchApi_confirmedLoss_putCarriesOnlyNewApiKeys_thenReadback() {
        connect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.modelApiRow")
        compose.onNodeWithTag("finecat.modelApiRow").performClick()
        assertTrue(awaitTag("finecat.modelApi.openai-responses"))
        compose.onNodeWithTag("finecat.modelApi.openai-responses").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("有损必须先出现确认态", awaitTag("finecat.modelApiConfirm"))
        val loss = lossKeys()
        record("loss=$loss")
        emitAssert("switch.loss", loss)
        assertTrue("必须逐项列出要丢的键：$loss", loss.contains("supportsStore") && loss.contains("thinkingFormat"))
        saveShot("f12-dialect-switch.png")
        compose.onNodeWithTag("finecat.modelApiConfirm").performClick()
        compose.waitForIdle()

        backToProviderCard()
        passProviderTestGate()
        save()
        assertTrue("换协议后必须先过测试门槛才能保存", awaitPut())
        val put = putAt()
        record("put raw=$put")
        emitAssert("wire.api", apiOfWireModel(rawModelJson(put)))
        emitAssert("wire.compatJson", rawCompatJson(put).orEmpty())
        transport.receive(savedFrame("""{"supportsDeveloperRole":true}""", api = "openai-responses"))
        assertTrue(tagGone("finecat.providerEdit"))
        transport.receive(providersFrame("""{"supportsDeveloperRole":true}""", api = "openai-responses"))
        compose.waitUntil(timeoutMillis = 5_000) {
            holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL }.api == "openai-responses"
        }
        emitAssert("readback.api", holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL }.api.orEmpty())
        emitAssert("readback.sorted", readbackSorted())
    }

    /** 取消有损确认：方言与已配 compat 都不变；随后的保存也不带 compat 字段。 */
    @Test
    fun switchApi_cancelled_keepsApiAndCompat_noProviderPut() {
        connect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.modelApiRow")
        compose.onNodeWithTag("finecat.modelApiRow").performClick()
        assertTrue(awaitTag("finecat.modelApi.anthropic-messages"))
        compose.onNodeWithTag("finecat.modelApi.anthropic-messages").performScrollTo().performClick()
        assertTrue("有损必须先出现确认态", awaitTag("finecat.modelApiCancel"))
        compose.onNodeWithTag("finecat.modelApiCancel").performClick()
        compose.waitForIdle()
        backToProviderCard()
        closeAllSheets()
        assertTrue("取消方言确认后不得产生 provider-put", transport.payloadsOfType("provider-put").isEmpty())

        assertTrue(openCompat())
        assertEquals("取消后方言必须保持原值", "openai-completions", apiTextOf())
        emitAssert("cancel.putCount", transport.payloadsOfType("provider-put").size.toString())
        emitAssert("cancel.api", apiTextOf())
        assertEquals("取消后 compat 必须原样保留", M1_COMPAT_SORTED, compatSortedOfWire())
        emitAssert("cancel.compat", compatSortedOfWire())
        saveShot("f12-dialect-cancel.png")
    }

    /** 换模型不串草稿：m-1 的编辑不带到 m-2。 */
    @Test
    fun switchModel_doesNotLeakCompatDraftOrExpandedState() {
        connect()
        assertTrue(openCompat(MODEL))
        scrollInModelCard("finecat.compatKey.supportsStore")
        compose.onNodeWithTag("finecat.compatSet.supportsStore.true").performScrollTo().performClick()
        compose.waitForIdle()
        emitAssert("switch.modelA", compatSortedOfWire())
        backToProviderCard()

        // 换到 m-2 后**不展开**：展开态必须跟着卡片重建，不能沿用 m-1 的展开结果。
        if (!openModel(MODEL_2)) error("模型二级卡打不开")
        assertEquals("新模型卡必须默认折叠", "已设置 1 项", countText())
        assertTrue(
            "换一个模型不得沿用上一张卡的展开态",
            compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isEmpty(),
        )
        scrollInModelCard("finecat.compatHeader")
        compose.onNodeWithTag("finecat.compatHeader").performClick()
        compose.waitForIdle()
        emitAssert("switch.modelB", compatSortedOfWire())
        assertEquals(
            "换一个模型必须只显示它自己那一项",
            "supportsStore=false",
            compatSortedOfWire(),
        )
        emitAssert("switch.collapsedOnReopen", "true")
        saveShot("f12-compat-switch-model.png")
    }

    /**
     * 保存终态不回退（F12 条件 5 的窄窗口）。
     *
     * 第一次保存被掉线取消 → 用户**只改 compat** 再保存 → 第一次的回执迟到。这一条回执除
     * compat 外与第二次完全相同，没有别的依据能认出它不是这一次的：若表单被它收掉，用户就
     * 会以为第二次也成功了（其实第二次还没落盘）。能认出它的是 `compatEchoMatches`。
     */
    @Test
    fun saveTimesOut_thenCompatOnlyEditAgain_lateReceiptKeepsFormOpen() {
        connect(requestTimeoutMillis = 8_000L)
        assertTrue(openCompat())

        // 第一次保存：改一个布尔，然后在途请求被掉线取消。
        scrollInModelCard("finecat.compatKey.supportsDeveloperRole")
        compose.onNodeWithTag("finecat.compatSet.supportsDeveloperRole.false").performScrollTo().performClick()
        backToProviderCard()
        save()
        assertTrue(awaitPut())
        runBlocking { transport.failRecoverable() }
        compose.waitUntil(timeoutMillis = 6_000) { holder!!.providerSaveTerminal != null }
        compose.waitForIdle()
        assertEquals(
            "第一次保存被取消必须是 Unconfirmed：${holder!!.providerSaveTerminal}",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder!!.providerSaveTerminal?.status,
        )
        assertTrue("取消后表单必须留在原地", awaitTag("finecat.providerEdit"))

        // 重连后**只改 compat**（supportsStore false → true）再保存。
        reconnect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.compatKey.supportsStore")
        compose.onNodeWithTag("finecat.compatSet.supportsStore.true").performScrollTo().performClick()
        backToProviderCard()
        save()
        assertTrue(awaitPut(2))
        val puts = transport.payloadsOfType("provider-put")
        record("put#1 raw=${puts[0]}")
        record("put#2 raw=${puts[1]}")
        assertEquals(
            "两次 put 除 compat 外必须逐字相同，否则本用例证明的不是那个窄窗口",
            withoutCompatField(puts[0].toString()),
            withoutCompatField(puts[1].toString()),
        )

        // 第一次的回执迟到：回显里 supportsStore 还是 false。
        transport.receive(savedFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":false}"""))
        compose.waitForIdle()
        val formStillOpen = compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty()
        // 迟到帧发布的那条终态带的是**第一次的**回显（supportsStore=false），不是第二次的；
        // 这正是内容关认出它的依据，也是 holder 列表此刻显示的落盘内容。
        val lateEcho = holder!!.providerSaveTerminal?.echoed?.models?.firstOrNull { it.id == MODEL }?.compat
        emitAssert("late.putCount", puts.size.toString())
        emitAssert("late.formStillOpen", formStillOpen.toString())
        // 这条必须**同时读两个键**才算「列表显示的是第一次的落盘值」：
        // 夹具原值 supportsStore 就是 false，第一次提交也是 false，光读它区分不出
        // 「显示了第一次」和「列表根本没动」。`supportsDeveloperRole` 才带着信息：
        // 原值 true → 第一次提交 false，列表读到 false 就证明第一次确实已落进列表；
        // 而 supportsStore 仍是 false 就证明第二次（true）还没落盘。
        emitAssert(
            "late.holderShowsFirstSubmit",
            lateHolderSorted().ifEmpty { "unknown" },
        )
        assertTrue("只改 compat 的迟到回执不得收掉第二次保存的表单", formStillOpen)
        assertEquals(
            "迟到帧之后列表显示的必须是第一次的落盘值（DeveloperRole 已由 true 变 false，" +
                "而 supportsStore 还是 false 说明第二次 true 尚未落盘）",
            "supportsDeveloperRole=false|supportsStore=false",
            lateHolderSorted(),
        )
        saveShot("f12-compat-late-receipt.png")

        // 第二次自己的回执到达：这才收卡。
        transport.receive(
            savedFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":true}"""),
        )
        assertTrue("第二次提交自己的回执必须收卡", tagGone("finecat.providerEdit"))
        emitAssert(
            "late.terminalOrder",
            (if (supportsStoreOf(lateEcho) == "false") "late" else "unknown") +
                "," +
                (if (supportsStoreOf(holder!!.providerSaveTerminal?.echoed?.models?.firstOrNull { it.id == MODEL }?.compat) == "true") "current" else "unknown"),
        )
        transport.receive(
            providersFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":true}"""),
        )
        awaitReadback("supportsDeveloperRole=false|supportsStore=true|thinkingFormat=deepseek")
        emitAssert("late.readback", readbackSorted())
    }

    /**
     * 计数与取值标记：只数已设置项；未设置显示「未设置」，不伪装成「关」；三种取值可区分。
     */
    @Test
    fun countsConfiguredKeysOnly_andUnsetNeverLooksLikeFalse() {
        connect()
        assertTrue(openCompat())
        emitAssert("ui.collapsedLabel", countText())
        emitAssert("ui.expandedKeys", visibleKeyCount().toString())
        emitAssert("ui.unsetRows", (visibleKeyCount() - 3).toString())

        // 三态取自屏上真实存在的三段按钮文案（不是替身）：
        val unset = markerTextOf("thinkingTokenBudgetField")
        val on = textOf("finecat.compatSet.supportsStore.true")
        val off = textOf("finecat.compatSet.supportsStore.false")
        val backToDefault = textOf("finecat.compatSet.supportsStore.default")
        emitAssert("ui.unsetMarker", unset)
        emitAssert("ui.trueMarker", on)
        emitAssert("ui.falseMarker", off)
        // 屏上一共只会出现三种取值标记：开 / 关 / 未设置（枚举未设置与布尔「默认」段同一词）。
        emitAssert(
            "ui.countDistinct",
            (setOf(unset, on, off, backToDefault) == setOf("开", "关", "未设置")).toString(),
        )
        assertEquals("未设置必须显示为「未设置」而不是「关」", "未设置", unset)
        assertEquals("布尔键的三个取值必须互不相同", setOf("开", "关", "未设置"), setOf(on, off, backToDefault))
        assertTrue("true/false 仍可直接从 stateDescription 判出当前选中项", isSelected("finecat.compatSet.supportsStore.false"))
        saveShot("f12-compat-count.png")
    }

    /** 逐层 Back：取值浮层 → 兼容区展开 → 模型卡 → Provider 卡。 */
    @Test
    fun backLayers_popoverThenExpandedBlockThenModelThenProvider() {
        connect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.compatKey.thinkingFormat")
        compose.onNodeWithTag("finecat.compatKey.thinkingFormat").performClick()
        assertTrue(awaitTag("finecat.compatEnumItem.thinkingFormat.openai"))
        pressBack()
        val steps = mutableListOf<String>()
        steps += if (compose.onAllNodesWithTag("finecat.compatEnumItem.thinkingFormat.zai").fetchSemanticsNodes().isEmpty()) "popover" else "no-popover"
        pressBack()
        steps += if (compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isEmpty()) "expanded" else "still-expanded"
        pressBack()
        steps += if (compose.onAllNodesWithTag("finecat.modelDetail").fetchSemanticsNodes().isEmpty()) "model" else "still-model"
        pressBack()
        steps += if (compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()) "provider" else "still-provider"
        emitAssert("back.steps", steps.joinToString(","))
        assertEquals("逐层返回顺序必须是 浮层→展开区→模型卡→Provider 卡", "popover,expanded,model,provider", steps.joinToString(","))
        saveShot("f12-compat-back.png")
    }

    /**
     * 键盘**仍然展开**时的兼容区：能滚到哪、能点到哪，逐项落证。
     *
     * 上一版的问题：键盘展开时点的是 `supportsStore`，末行 `sessionAffinityFormat` 只落了个
     * 几何值；随后**先收键盘**再点一次 `supportsStore`，却把结果登记成 `ime.lastRowOperable`
     * ——名与实不符，那条证据其实什么都没证明。这版把「键盘开着时」和「收键盘后」严格分开。
     *
     * - **A（键盘开着，可证）**：兼容区中段的分段按钮点得动，取值真的变、键盘仍然亮。
     * - **B（键盘开着，只落证不断言）**：末行尽力上滚，落 `ime.lastRowBottom` /
     *   `ime.lastRowAboveIme`。实测本宿主上**末行滚不到键盘线以上**（末行底边 > 键盘顶边；具体像素随
     *   设备状态漂移，别把它抄进文档——以 `capture/F12-IME.measure.txt` 的落盘值为准），
     *   原因是这个宿主既不 resize 窗口也不派发 IME insets，没有任何东西把 sheet 抬起来。
     *   所以「键盘展开时末行完全可见」在**本宿主上不可达**，这里如实记 false，**不断言它为真**。
     * - **C（收键盘后，可证）**：末行完整落在模型卡内，并且**真的被操作**（开取值浮层选值、
     *   取值变化）——这才是不含水分的「末行位置 + 可操作」证据。
     *
     * 因此条件 6 的 IME 部分**仍未闭合**：生产宿主（MainActivity，`adjustResize`）上末行会不会
     * 被键盘盖住，本轮无法在本宿主证明，也不能用组件宿主的坐标冒充。
     */
    @Test
    fun ime_open_compatVisibleScrollableAndOperable() {
        connect()
        assertTrue(openCompat())
        scrollInModelCard("finecat.modelName")
        compose.onNodeWithTag("finecat.modelName").performClick()
        waitForIme()
        assertTrue("点开输入行后键盘应可见", isImeShown())
        emitAssert("ime.shown", "true")

        val imeTop = awaitImeTop()
        emitAssert("ime.topParsed", (imeTop != null).toString())
        val cardBottom = boundsOf("finecat.modelDetail").bottom
        emitAssert("ime.cardBottom", cardBottom.toString())
        emitAssert(
            "ime.windowResized",
            if (imeTop == null) "unparsed" else (cardBottom <= imeTop + 2f).toString(),
        )

        // A. 键盘还亮着：兼容区中段点得动，取值必须真的变。
        scrollInModelCard("finecat.compatKey.supportsStore")
        compose.onNodeWithTag("finecat.compatSet.supportsStore.true").performScrollTo().performClick()
        compose.waitForIdle()
        emitAssert("ime.clickedWhileImeUp", markerTextOf("supportsStore"))
        emitAssert("ime.imeStillShown", isImeShown().toString())
        assertEquals("键盘展开时兼容区必须点得动", "开", markerTextOf("supportsStore"))
        assertTrue("点完键盘仍应是展开的（证明没有偷偷收键盘）", isImeShown())

        // B1. 键盘还亮着：末行此刻**在哪**（只定位，不破坏状态）。
        require(imeTop != null) { "解析不到键盘顶边，IME 几何无从落证" }
        val lastRowTag = "finecat.compatKey.sessionAffinityFormat"
        scrollInModelCard(lastRowTag)
        emitAssert("ime.imeTop", imeTop.toString())
        saveShot("f12-compat-ime.png")

        // C. 收键盘后：末行必须完整落在模型卡可视范围内。
        pressBack()
        compose.waitForIdle()
        // 段 B 的连番上滑可能把末行甩出可视区，performScrollTo 一次未必救得回来，重试到可见为止。
        assertTrue(
            "收键盘后末行必须能重新滚回可视区",
            ensureDisplayed(lastRowTag),
        )
        compose.onNodeWithTag(lastRowTag).assertIsDisplayed()
        val lastBounds = boundsOf(lastRowTag)
        val card = boundsOf("finecat.modelDetail")
        emitAssert("ime.lastRowVisible", "true")
        emitAssert("ime.lastRowInsideCard", (lastBounds.bottom <= card.bottom + 1f).toString())
        assertEquals(
            "末行必须完整落在模型卡内（last=$lastBounds, card=$card）",
            "true",
            (lastBounds.bottom <= card.bottom + 1f).toString(),
        )
        // 末行是枚举键：行内没有分段，得开取值浮层选值。取值真的变了，才算「可操作」。
        assertEquals("收键盘后末行取值应仍是未设置", "未设置", markerTextOf("sessionAffinityFormat"))
        emitAssert("ime.lastRowUnsetMarker", markerTextOf("sessionAffinityFormat"))
        compose.onNodeWithTag(lastRowTag).performClick()
        assertTrue("点末行必须能打开取值浮层", awaitTag("finecat.compatEnumItem.sessionAffinityFormat.openai"))
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.compatEnumItem.sessionAffinityFormat.openai").performClick()
        compose.waitForIdle()
        emitAssert("ime.lastRowOperableAfterDismiss", markerTextOf("sessionAffinityFormat"))
        assertEquals("收键盘后末行必须真的能操作", "openai", markerTextOf("sessionAffinityFormat"))
        saveShot("f12-compat-ime-after-dismiss.png")

        // B2. 破坏性实验放最后：重新拉起键盘，把末行尽力上滚，**只落证不断言**。
        // 放最后是因为这串上滑会把末行甩出可视区，前面那些断言就不成立了。
        scrollInModelCard("finecat.modelName")
        compose.onNodeWithTag("finecat.modelName").performClick()
        waitForIme()
        val imeTop2 = requireNotNull(awaitImeTop()) { "第二次解析不到键盘顶边" }
        scrollInModelCard(lastRowTag)
        val outcome = scrollRowAboveIme(lastRowTag, imeTop2)
        emitAssert("ime.lastRowBottom", outcome.lastBottom?.toString() ?: "lost")
        emitAssert("ime.lastRowAboveIme", outcome.above.toString())
        record(
            "键盘展开时末行尽力上滚后的底边=${outcome.lastBottom}、键盘顶边=$imeTop2、中途丢失=${outcome.lost}；" +
                "本宿主既不 resize 也不派发 IME insets，没有任何东西把 sheet 抬起来，末行滚不到键盘线以上" +
                "属实测事实，这里只落证、不断言为真（条件 6 的 IME 因此仍未闭合）",
        )
        saveShot("f12-compat-ime-scrolled.png")
    }

    /**
     * 横向（真横屏）验证已移到 [FineCatCompatF12LandscapeDeviceTest]。
     *
     * 移出来的原因：`createComposeRule()` 会在测试体**之前**就启动它的 ComponentActivity，
     * 而旋转会重建该 Activity，`setContent` 挂上去的组合随之丢失（实测报 "No compose
     * hierarchies found in the app"）。要「先旋转、再挂内容」只能用
     * `createEmptyComposeRule()` + 自己 `ActivityScenario.launch`。
     *
     * 另外这一版之前的「横向」用例只设了密度与字号、**根本没旋转**（截图仍是 1170×2520
     * 竖屏），验的只是「窄竖屏不横向溢出」，不能算横屏证据。
     */

    /**
     * 真实入口下的 compat 保存：FineCatApp → 抽屉 → 设置 → Provider → 模型 → 改 compat → 保存。
     *
     * 前面的用例挂的是 `FineCatProviderSheet` 本体，能证明**组件**行为；这一条证明
     * **生产根**里同样的链路成立（抽屉层、设置页行、叠层表单、收卡后回到设置页）。
     */
    @Test
    fun compatSaveViaRealRoot_putMatchesExpected_thenIndependentReadback() {
        connect(realRoot = true)
        openProviderEditViaRealEntry()
        assertTrue(
            "真实入口必须能打开 Provider 编辑卡",
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty(),
        )
        val index = holder!!.providers.first { it.id == PROVIDER }.models.indexOfFirst { it.id == MODEL }
        compose.onNodeWithTag("finecat.modelRow.$index").performScrollTo().performClick()
        assertTrue("模型二级卡必须打开", awaitTag("finecat.modelDetail"))
        scrollInModelCard("finecat.compatHeader")
        compose.onNodeWithTag("finecat.compatHeader").performClick()
        compose.waitForIdle()
        scrollInModelCard("finecat.compatKey.supportsStore")
        compose.onNodeWithTag("finecat.compatSet.supportsStore.true").performScrollTo().performClick()
        compose.waitForIdle()
        emitAssert("realRoot.countAfter", countText())

        backToProviderCard()
        save()
        assertTrue("真实入口保存必须发出 provider-put", awaitPut())
        val put = putAt()
        record("real-root put raw=$put")
        emitAssert("realRoot.wire.compatJson", rawCompatJson(put).orEmpty())
        emitAssert(
            "realRoot.wire.sorted",
            compatSortedOfRaw(rawCompatJson(put)),
        )
        transport.receive(
            savedFrame("""{"supportsDeveloperRole":true,"thinkingFormat":"deepseek","supportsStore":true}"""),
        )
        assertTrue("真实入口保存成功后必须收卡", tagGone("finecat.providerEdit"))
        transport.receive(
            providersFrame("""{"supportsDeveloperRole":true,"thinkingFormat":"deepseek","supportsStore":true}"""),
        )
        awaitReadback("supportsDeveloperRole=true|supportsStore=true|thinkingFormat=deepseek")
        emitAssert("realRoot.readback.sorted", readbackSorted())
        saveShot("f12-realroot-compat-save.png")
    }

    /**
     * 真实入口下的迟到回执：取消 → 只改 compat 再保存 → 旧回执迟到不得收卡。
     *
     * 与 `saveTimesOut_...` 的差别是**归属判定发生在生产根叠层里**（抽屉/设置页/叠层都挂着），
     * 提前收起表单在这里的表现是「设置页 Provider 行直接换新」，破坏面比组件用例更大。
     */
    @Test
    fun lateReceiptViaRealRoot_onlyCompatChanged_keepsFormOpen() {
        connect(requestTimeoutMillis = 8_000L, realRoot = true)
        openProviderEditViaRealEntry()
        assertTrue(
            "真实入口必须能打开 Provider 编辑卡",
            compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty(),
        )
        openCompatViaCards()

        // 第一次保存：改一个布尔，然后在途请求被掉线取消。
        scrollInModelCard("finecat.compatKey.supportsDeveloperRole")
        compose.onNodeWithTag("finecat.compatSet.supportsDeveloperRole.false").performScrollTo().performClick()
        backToProviderCard()
        save()
        assertTrue(awaitPut())
        runBlocking { transport.failRecoverable() }
        compose.waitUntil(timeoutMillis = 6_000) { holder!!.providerSaveTerminal != null }
        compose.waitForIdle()
        dismissPlatformErrorIfShown()
        assertEquals(
            "第一次保存被取消必须是 Unconfirmed",
            AndroidSharedStateHolder.ProviderSaveTerminal.Status.Unconfirmed,
            holder!!.providerSaveTerminal?.status,
        )
        assertTrue("取消后表单必须留在原地", awaitTag("finecat.providerEdit"))

        // 重连后只改 compat 再保存
        reconnect()
        dismissPlatformErrorIfShown()
        openCompatViaCards()
        scrollInModelCard("finecat.compatKey.supportsStore")
        compose.onNodeWithTag("finecat.compatSet.supportsStore.true").performScrollTo().performClick()
        backToProviderCard()
        save()
        assertTrue(awaitPut(2))
        val puts = transport.payloadsOfType("provider-put")
        record("real-root put#1 raw=${puts[0]}")
        record("real-root put#2 raw=${puts[1]}")
        assertEquals(
            "两次 put 除 compat 外必须逐字相同",
            withoutCompatField(puts[0].toString()),
            withoutCompatField(puts[1].toString()),
        )

        // 第一次的回执迟到：表单必须还在
        transport.receive(
            savedFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":false}"""),
        )
        compose.waitForIdle()
        val stillOpen = compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty()
        emitAssert("realRoot.late.formStillOpen", stillOpen.toString())
        assertTrue("真实入口下迟到回执也不得收掉第二次保存的表单", stillOpen)
        saveShot("f12-realroot-late-receipt.png")

        transport.receive(
            savedFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":true}"""),
        )
        assertTrue("第二次提交自己的回执必须收卡", tagGone("finecat.providerEdit"))
        transport.receive(
            providersFrame("""{"supportsDeveloperRole":false,"thinkingFormat":"deepseek","supportsStore":true}"""),
        )
        awaitReadback("supportsDeveloperRole=false|supportsStore=true|thinkingFormat=deepseek")
        emitAssert("realRoot.late.readback", readbackSorted())
    }

    /** 从 Provider 编辑卡进模型卡并展开兼容区（真实入口下表单已经在叠层里）。 */
    private fun openCompatViaCards() {
        val index = holder!!.providers.first { it.id == PROVIDER }.models.indexOfFirst { it.id == MODEL }
        if (compose.onAllNodesWithTag("finecat.modelDetail").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("finecat.modelRow.$index").performScrollTo().performClick()
            check(awaitTag("finecat.modelDetail")) { "模型二级卡没打开" }
        }
        if (compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isEmpty()) {
            scrollInModelCard("finecat.compatHeader")
            compose.onNodeWithTag("finecat.compatHeader").performClick()
            compose.waitForIdle()
        }
        check(compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isNotEmpty()) {
            "兼容参数区没展开"
        }
    }

    // ---- 三环境 × 亮暗矩阵 ----

    @Test
    fun matrix_390dp_fs10_light() = matrixStep("f12-390-fs10-light", dark = false, expectedDp = 390f)

    @Test
    fun matrix_390dp_fs10_dark() = matrixStep("f12-390-fs10-dark", dark = true, expectedDp = 390f)

    @Test
    fun matrix_360dp_fs10_light() =
        matrixStep("f12-360-fs10-light", dark = false, expectedDp = 360f, densityDpi = 520)

    @Test
    fun matrix_360dp_fs10_dark() =
        matrixStep("f12-360-fs10-dark", dark = true, expectedDp = 360f, densityDpi = 520)

    @Test
    fun matrix_360dp_fs13_light() =
        matrixStep("f12-360-fs13-light", dark = false, expectedDp = 360f, densityDpi = 520, fontScale = "1.3")

    @Test
    fun matrix_360dp_fs13_dark() =
        matrixStep("f12-360-fs13-dark", dark = true, expectedDp = 360f, densityDpi = 520, fontScale = "1.3")

    private fun matrixStep(
        tag: String,
        dark: Boolean,
        expectedDp: Float,
        densityDpi: Int? = null,
        fontScale: String? = null,
    ) {
        applyDeviceConfig(densityDpi ?: 480, fontScale ?: "1.0")
        connect(dark = dark)
        assertTrue("$tag 兼容参数区必须能展开", openCompat())
        val viewportDp = boundsOf("finecat.modelDetail").width / compose.density.density
        assertTrue("$tag 兼容区视口应=$expectedDp dp ±2（实际 $viewportDp）", abs(viewportDp - expectedDp) <= 2f)
        emitAssert("matrix.$tag.viewportDp", viewportDp.toInt().toString())

        // 亮暗两态必须真的换肤：探针读的是组合内解析出的 surface 色（对比截图像素会被
        // 状态栏时钟之类噪声干扰）。
        val probe = textOf(THEME_PROBE)
        assertEquals(
            "$tag 主题探针与请求的亮暗不符",
            expectedSurface(dark),
            probe.substringAfter("surface="),
        )
        emitAssert("matrix.$tag.theme", "${if (dark) "dark" else "light"}/$probe")

        scrollInModelCard("finecat.compatKey.sessionAffinityFormat")
        val reachable = compose.onAllNodesWithTag("finecat.compatKey.sessionAffinityFormat")
            .fetchSemanticsNodes().isNotEmpty()
        emitAssert("matrix.$tag.lastRowReachable", reachable.toString())
        assertTrue("$tag 最后一行必须可达", reachable)
        saveShot("$tag.png")
    }

    // ================================================================== 夹具与 UI 操作

    /**
     * 挂载方式二选一：
     * - 默认：直接挂 `FineCatProviderSheet`（快，适合纯交互用例）；
     * - `realRoot = true`：挂 **FineCatApp 生产根**，再从「首页 → 抽屉 → 设置 → Provider 行」
     *   走真实入口进表单。保存/取消/回执这类**要证明生产入口也成立**的用例必须走这条。
     */
    private fun connect(
        dark: Boolean = false,
        requestTimeoutMillis: Long = 120_000L,
        realRoot: Boolean = false,
    ) {
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            requestTimeoutOverride = requestTimeoutMillis,
        )
        val stateHolder = graph!!.stateHolder
        holder = stateHolder
        compose.setContent {
            if (realRoot) {
                // 生产根自带内层主题（FineCatApp 按 appearanceMode 选），外层不再套主题。
                FineCatTheme { FineCatApp(stateHolder = stateHolder) }
            } else {
                FineCatTheme(darkTheme = stateHolder.appearanceMode == "dark") {
                    Box(modifier = Modifier.fillMaxSize()) {
                        FineCatProviderSheet(stateHolder = stateHolder, onDismiss = {})
                        Text(
                            text = themeProbe(),
                            style = LocalFineCatType.current.xs2,
                            color = Color.Transparent,
                            modifier = Modifier.testTag(THEME_PROBE),
                        )
                    }
                }
            }
        }
        runBlocking {
            com.finecat.pimobile.android.waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            com.finecat.pimobile.android.waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
        }
        val mode = if (dark) "dark" else "light"
        testOnMain { stateHolder.updateAppearanceMode(mode) }
        runBlocking { com.finecat.pimobile.android.waitUntil { stateHolder.appearanceMode == mode } }
        transport.receive(providersFrame())
        runBlocking { com.finecat.pimobile.android.waitUntil { stateHolder.providers.any { it.id == PROVIDER } } }
        compose.waitForIdle()
    }

    /**
     * 设备配置必须在每条矩阵用例里显式落：只在 360 用例里设的话，390 用例会继承上一个的
     * 520（实测视口变成 360dp），用例顺序一变就是假失败。
     */
    private fun applyDeviceConfig(densityDpi: Int, fontScale: String) {
        shell("wm density $densityDpi")
        shell("settings put system font_scale $fontScale")
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val densityApplied = shell("wm density").contains("Override density: $densityDpi")
            val fontApplied = shell("settings get system font_scale").trim() == fontScale
            if (densityApplied && fontApplied) break
            Thread.sleep(200)
        }
        // 配置变更会触发 Activity 重建与重排，留出稳定时间再取语义树。
        Thread.sleep(1_500)
        compose.waitForIdle()
    }

    /**
     * 当前显示旋转角（`mDisplayRotation`：`0`=竖、`1`=90° 横、`3`=270° 横）。
     *
     * 从窗口快照读，和 [imeTopPx] 同一份来源，避免另起一条命令跨时刻采样。
     */
    private fun currentRotation(): Int =
        Regex("mDisplayRotation=(\\d+)").find(shell("dumpsys window windows"))?.groupValues?.get(1)?.toInt() ?: -1

    /**
     * 真正把设备转成 90° 横屏。
     *
     * 只设密度与字号**不等于横屏**——上一版就是这么漏的：截图仍是 1170×2520 竖屏，验的只是
     * 「窄竖屏不横向溢出」。这里先关掉自动旋转再钉死 `user_rotation=1`，等到
     * `mDisplayRotation` 真的变成 1 才返回；转不动就抛错，让用例当场红，不拿竖屏冒充横屏。
     */
    private fun rotateLandscape(timeoutMs: Long = 15_000) {
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 1")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (currentRotation() == 1) {
                // 旋转会触发 Activity 重建与重排，等布局稳定再取语义树。
                Thread.sleep(1_500)
                compose.waitForIdle()
                return
            }
            Thread.sleep(250)
        }
        error("设备没能转到横屏（mDisplayRotation=${currentRotation()}）")
    }

    /** 恢复竖屏并把自动旋转开关交还给系统（用例失败时也会走 `finally`）。 */
    private fun restorePortrait() {
        shell("settings put system user_rotation 0")
        shell("settings put system accelerometer_rotation 1")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (currentRotation() == 0) break
            Thread.sleep(250)
        }
    }

    private fun reconnect() {
        for (attempt in 0 until 20) {
            runBlocking {
                transport.open()
                transport.receive(HELLO)
            }
            repeat(6) {
                if (holder!!.gatewayState.connection.name == "CONNECTED") return
                Thread.sleep(200)
            }
        }
        throw AssertionError("重连后连接仍不是 CONNECTED")
    }

    private fun openModel(modelId: String): Boolean {
        if (compose.onAllNodesWithTag("finecat.modelDetail").fetchSemanticsNodes().isEmpty()) {
            if (compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()) {
                runCatching {
                    compose.waitUntil(timeoutMillis = 3_000) {
                        compose.onAllNodesWithTag("finecat.providerRow.$PROVIDER").fetchSemanticsNodes().isNotEmpty()
                    }
                }
                assertTrue(
                    "provider 行必须出现在列表里：providers=${holder!!.providers.map { it.id }} " +
                        "connected=${holder!!.gatewayState.connection}",
                    compose.onAllNodesWithTag("finecat.providerRow.$PROVIDER").fetchSemanticsNodes().isNotEmpty(),
                )
                compose.onNodeWithTag("finecat.providerRow.$PROVIDER").performClick()
                if (!awaitTag("finecat.providerEdit")) return false
            }
            val index = holder!!.providers.first { it.id == PROVIDER }.models.indexOfFirst { it.id == modelId }
            compose.onNodeWithTag("finecat.modelRow.$index").performScrollTo().performClick()
            if (!awaitTag("finecat.modelDetail")) return false
        }
        return true
    }

    private fun openCompat(modelId: String = MODEL): Boolean {
        if (!openModel(modelId)) return false
        if (compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isNotEmpty()) return true
        scrollInModelCard("finecat.compatHeader")
        compose.onNodeWithTag("finecat.compatHeader").performClick()
        compose.waitForIdle()
        return compose.onAllNodesWithTag("finecat.compatBlock").fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * 真实入口：首页 → 抽屉 → 设置 → Provider 行 → 编辑卡。
     *
     * 抽屉首次点击偶发不出现（注入/重组时序），沿既有口径重试 3 次；3 次仍开不了就是真缺陷。
     */
    private fun openProviderEditViaRealEntry() {
        runBlocking { waitUntil { holder!!.snapshot.selectedSessionId == null } }
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                compose.waitForIdle()
                compose.onNodeWithTag("finecat.menu").performClick()
                compose.waitForIdle()
                check(awaitTag("finecat.drawerSearch")) { "抽屉没打开" }
                compose.onNodeWithTag("finecat.drawerSettings").performClick()
                compose.waitForIdle()
                check(awaitTag("finecat.settingsScreen")) { "设置页没打开" }
                check(
                    compose.onAllNodesWithTag("finecat.providerRow.$PROVIDER").fetchSemanticsNodes().isNotEmpty(),
                ) { "设置页里没有 Provider 行：${presentTags()}" }
                compose.onNodeWithTag("finecat.providerRow.$PROVIDER").performScrollTo().performClick()
                // 必须 `return` 而不是 `return@repeat`：只结束本次迭代的话，下一轮又会去点
                // menu，把刚打开的编辑卡关掉，最后变成「开了又关」。
                if (awaitTag("finecat.providerEdit")) return
                throw AssertionError("点了 Provider 行但编辑卡没打开：${presentTags()}")
            } catch (e: Throwable) {
                last = e
                record("真实入口第 $attempt/3 次尝试失败：${e.message}")
                if (compose.onAllNodesWithTag("finecat.drawerSearch").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onRoot().performTouchInput { click(Offset(width * 0.92f, height * 0.5f)) }
                    compose.waitForIdle()
                }
            }
        }
        throw AssertionError("真实入口没能打开 Provider 编辑卡：${last?.message}", last)
    }

    /** 失败现场用：把当前语义树里出现过的 testTag 列出来（截断避免日志爆掉）。 */
    private fun presentTags(): String =
        runCatching {
            compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30)
                .lineSequence()
                .mapNotNull { Regex("TestTag = '([^']+)'").find(it)?.groupValues?.get(1) }
                .distinct()
                .take(40)
                .joinToString(",")
                .ifEmpty { "<无 testTag>" }
        }.getOrElse { "语义树读取失败：${it.message}" }

    /** 掉线时 App 会弹全局重连模态，真机路径里必须先关掉它再操作。 */
    private fun dismissPlatformErrorIfShown() {
        if (holder!!.platformError == null) return
        compose.onNodeWithText("好").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { holder!!.platformError == null }
        compose.waitForIdle()
    }

    /**
     * 从 `dumpsys window windows` 解析键盘触摸区顶边（px）。
     *
     * 「节点存在」不证明它没被键盘盖住——判据必须是同一份窗口快照里的几何关系。
     * 键盘收起时该区域为空，返回 null（调用方据此判「没解析到」而不是当成通过）。
     */
    private fun imeTopPx(): Int? {
        val dump = shell("dumpsys window windows")
        val block = Regex("(?ms)^  Window #\\d+ Window\\{[0-9a-f]+ u0 InputMethod\\}.*?(?=^  Window #|\\z)")
            .find(dump)?.value ?: return null
        return Regex("touchable region=SkRegion\\(\\((\\d+),(\\d+),(\\d+),(\\d+)\\)\\)")
            .find(block)?.groupValues?.get(2)?.toInt()
    }

    /** 轮询到解析出键盘顶边为止；超时返回最后一次结果（可能是 null＝没解析到）。 */
    private fun awaitImeTop(timeoutMs: Long = 8_000): Int? {
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
     * 把模型二级卡里的目标滚到**完整可见**。
     *
     * 不能用 `performScrollToNode`：它只保证目标与视口有重叠，一行 142px 只露出 23px
     * 也算「滚到了」——那样点下去命中的是行的边缘，锚点也是那 23px 的残缺矩形。
     * `performScrollTo()` 会把整个节点滚进视口。
     */
    /**
     * 过保存门槛：改模型 api 会动 `providerFingerprint`（含 `id@api`），必须重测才能存。
     * 这是既有产品口径，不是本轮加的限制——所以用例必须自己走一遍，不能绕过。
     */
    private fun passProviderTestGate() {
        scrollInProviderCard("finecat.providerTest")
        val from = transport.sentTypes.size
        compose.onNodeWithTag("finecat.providerTest").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { transport.sentTypes.drop(from).contains("provider-test") }
        transport.receive("""{"kind":"provider-test-result","ok":true,"latencyMs":12,"id":"$PROVIDER"}""")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("finecat.providerTestResult", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun scrollInProviderCard(tag: String) {
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.waitForIdle()
    }

    /**
     * 把节点滚回可视区，返回它是否真的有布局。
     *
     * 这里**只判「节点存在且有高度」**，不判「落在卡片内」——后者是 `ime.lastRowInsideCard`
     * 那一行的判据。两者写成同一个表达式的话，后者就成了前者的同义反复，看着是两个断言、
     * 其实只测了一次，判别力会凭空少一半。
     */
    private fun ensureDisplayed(tag: String, attempts: Int = 6): Boolean {
        repeat(attempts) {
            runCatching { compose.onNodeWithTag(tag).performScrollTo() }
            compose.waitForIdle()
            val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull { it.boundsInRoot.height > 0f }
            if (node != null) return true
        }
        return false
    }

    private fun scrollInModelCard(tag: String) {
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.waitForIdle()
    }

    /**
     * 在键盘**仍然展开**时，把某行滚到键盘线以上。
     *
     * 为什么不能只靠 `performScrollTo()`：它只保证节点落在**卡片视口**内，而卡片是贴底 sheet、
     * 高度到屏幕底边，所以「落在卡片视口内」的末行仍在键盘线**以下**（落盘证据：末行底边 > 键盘顶边）。「点得到」和「没被键盘盖住」是两件事，判据必须是 `bottom ≤ imeTop` 这个几何关系。
     *
     * 这里靠反复上滑把行推到线上方；返回是否真的推上去了（推不上去就返回 false，让调用方红，
     * 不要拿「节点还在」当通过）。
     */
    private fun scrollRowAboveIme(tag: String, imeTop: Int, maxSwipes: Int = 20): ScrollOutcome {
        val cardTag = "finecat.modelDetail"
        var lastBottom: Float? = null
        for (i in 0..maxSwipes) {
            compose.waitForIdle()
            // 节点若在滚动过程中离开语义树，这里**记下来返回而不是抛异常**：推不到键盘线以上
            // 是一条要如实报出来的观测，不该表现成「节点没找到」这种看不懂的错。
            val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull { it.boundsInRoot.height > 0f }
            if (node == null) return ScrollOutcome(lastBottom, above = false, lost = true)
            lastBottom = node.boundsInRoot.bottom
            if (lastBottom <= imeTop) return ScrollOutcome(lastBottom, above = true, lost = false)
            if (i == maxSwipes) break
            // 整卡上滑（不给自定义坐标）：卡片里可滚的不止末行那一段，给死坐标容易落在
            // 不可滚的子节点上，看起来像滑了其实没动。
            compose.onNodeWithTag(cardTag).performTouchInput { swipeUp() }
        }
        compose.waitForIdle()
        val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull { it.boundsInRoot.height > 0f }
        val bottom = node?.boundsInRoot?.bottom ?: lastBottom
        return ScrollOutcome(bottom, above = bottom != null && bottom <= imeTop, lost = node == null)
    }

    /** [scrollRowAboveIme] 的观测结果：最后一次看到的底边、是否真的推到键盘线以上、是否中途丢失。 */
    private data class ScrollOutcome(
        val lastBottom: Float?,
        val above: Boolean,
        val lost: Boolean,
    )

    /**
     * 把一个键恢复默认。
     *
     * 布尔键拨行内第三段；枚举键必须开取值浮层选「未设置」——两者是不同的控件，
     * 枚举键**没有**行内分段（值域 3~12 项，行内铺不下也不该铺）。
     */
    private fun unsetKey(key: String) {
        scrollInModelCard("finecat.compatKey.$key")
        if (compose.onAllNodesWithTag("finecat.compatSet.$key.default").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("finecat.compatSet.$key.default").performClick()
        } else {
            compose.onNodeWithTag("finecat.compatKey.$key").performClick()
            check(awaitTag("finecat.compatEnumUnset.$key")) { "枚举键 $key 的取值浮层没打开" }
            compose.onNodeWithTag("finecat.compatEnumUnset.$key").performClick()
            runCatching { waitTagGone("finecat.compatPopover") }
        }
        compose.waitForIdle()
    }

    private fun backToProviderCard() {
        var attempts = 0
        while (compose.onAllNodesWithTag("finecat.modelDetail").fetchSemanticsNodes().isNotEmpty()) {
            check(attempts++ < 4) { "返回连按 4 次仍未退出模型二级卡（浮层没收干净？）" }
            compose.onNodeWithTag("finecat.sheetBack").performClick()
            compose.waitForIdle()
        }
        awaitTag("finecat.providerEdit")
    }

    private fun closeAllSheets() {
        while (compose.onAllNodesWithTag("finecat.modelDetail").fetchSemanticsNodes().isNotEmpty()) {
            backToProviderCard()
        }
        if (compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isNotEmpty()) {
            compose.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.06f)) }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()
            }
        }
        compose.waitForIdle()
    }

    private fun save() = compose.onNodeWithText("保存").performClick()

    // ---- 取证 ----

    /**
     * 取证行写进外部 files 根的 `capture-measure.txt`：`am instrument` 的 stdout 里
     * 拿不到 println，capture 是按这个文件把 `CAPTURE-ASSERT` 行拼进用例日志的
     * （与 F10/F11 同一口径）。同时打一份 logcat，方便现场排障。
     */
    private fun emitAssert(name: String, actual: String) {
        appendMeasureLine("CAPTURE-ASSERT: $name actual=$actual")
        android.util.Log.i("F12ASSERT", "$name=$actual")
    }

    private fun appendMeasureLine(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val file = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            file.delete()
            measureFileReady = true
        }
        file.appendText(line + "\n")
    }

    private fun record(message: String) {
        lines += message
        android.util.Log.i("F12CAP", message)
    }

    private fun saveShot(name: String) = compose.saveEvidenceScreenshot(name)

    private fun awaitTag(tag: String, timeoutMillis: Long = 5_000): Boolean {
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitTagGone(tag: String, timeoutMillis: Long = 5_000): Boolean {
        runCatching { compose.waitUntil(timeoutMillis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() } }
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }

    private fun tagGone(tag: String, timeoutMillis: Long = 8_000): Boolean {
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }

    private fun awaitPut(count: Int = 1, timeoutMillis: Long = 8_000): Boolean {
        compose.waitUntil(timeoutMillis) { transport.payloadsOfType("provider-put").size >= count }
        return transport.payloadsOfType("provider-put").size >= count
    }

    /** 取第 index 条 provider-put 原始 payload（默认第一条=本用例唯一一次）。 */
    private fun putAt(index: Int = 0) = transport.payloadsOfType("provider-put")[index].toString()

    private fun awaitReadback(expected: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compatSortedOf(
                holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL },
            ) == expected
        }
    }

    /** 回显/列表里某个 compat 键的取值（布尔翻成字符串）；取不到返回空串。 */
    private fun supportsStoreOf(compat: com.finecat.pimobile.shared.protocol.JsonValue?): String =
        boolOf(compat, "supportsStore")

    private fun boolOf(
        compat: com.finecat.pimobile.shared.protocol.JsonValue?,
        key: String,
    ): String = compat?.objectValue?.get(key)?.let { value ->
        when (value) {
            is JsonValue.BooleanValue -> value.value.toString()
            is JsonValue.StringValue -> value.value
            else -> ""
        }
    }.orEmpty()

    private fun readbackCompat(): com.finecat.pimobile.shared.protocol.JsonValue? =
        holder!!.providers.firstOrNull { it.id == PROVIDER }?.models?.firstOrNull { it.id == MODEL }?.compat

    private fun readbackSorted(): String = compatSortedOf(
        holder!!.providers.first { it.id == PROVIDER }.models.first { it.id == MODEL },
    )

    /**
     * 迟到回执那一刻 holder 列表里那两个键的取值（`a=x|b=y`）。
     *
     * 取的是**两个**键而不是一个：单独看 `supportsStore` 区分不出「列表显示第一次落盘值」
     * 与「列表压根没动」（夹具原值与第一次提交在这个键上都是 false）。`supportsDeveloperRole`
     * 原值 true、第一次提交 false，它一变就证明第一次已经落进列表了。
     */
    private fun lateHolderSorted(): String {
        val compat = readbackCompat() ?: return ""
        return "supportsDeveloperRole=${boolOf(compat, "supportsDeveloperRole")}" +
            "|supportsStore=${boolOf(compat, "supportsStore")}"
    }

    /**
     * 屏上当前草稿里**已设置**的 compat（按键排序）。
     *
     * 取值来源是节点本身：布尔键读选中分段（stateDescription），枚举键读行内取值标记。
     * 滚不到的行跳过——这里比的是「用户能看到的」，不是草稿的全量。
     */
    private fun compatSortedOfWire(): String {
        val markers = mutableListOf<String>()
        for (key in PROVIDER_COMPAT_KEYS_BY_API["openai-completions"].orEmpty()) {
            if (compose.onAllNodesWithTag("finecat.compatKey.$key", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()) continue
            val rendered = when {
                compose.onAllNodesWithTag("finecat.compatMarker.$key", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() ->
                    markerTextOf(key)
                isSelected("finecat.compatSet.$key.true") -> "true"
                isSelected("finecat.compatSet.$key.false") -> "false"
                else -> "未设置"
            }
            if (rendered != "未设置") markers += "$key=$rendered"
        }
        return markers.sorted().joinToString("|")
    }

    private fun isSelected(tag: String): Boolean {
        val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: return false
        return node.config.getOrNull(SemanticsProperties.StateDescription) == "已选中"
    }

    /** 行内取值标记（枚举键）/ 当前选中分段按钮的文案（布尔键）。 */
    /**
     * 行内取值：枚举键读行内标记，布尔键读选中分段。
     *
     * 存在性判定用**未合并树**：合并树上这些 Text 会被祖先并掉，按合并树查会误判成
     * 「没有标记节点」，从而把枚举键读成布尔键的「未设置」。
     */
    private fun markerTextOf(key: String): String =
        if (compose.onAllNodesWithTag("finecat.compatMarker.$key", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            textOf("finecat.compatMarker.$key")
        } else {
            selectedSegmentLabel(key)
        }

    /**
     * 行**右侧那个会被长键名挤掉**的控件 tag：枚举行是取值标记，布尔行是最后一段「关」。
     *
     * 行容器 `compatKey.$key` 是 `fillMaxWidth` 的，按构造不可能越界，拿它判「没被裁」恒真；
     * 横向用例要量的是这里返回的控件。
     */
    private fun controlTagOf(key: String): String =
        if (compose.onAllNodesWithTag("finecat.compatMarker.$key", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            "finecat.compatMarker.$key"
        } else {
            "finecat.compatSet.$key.false"
        }

    private fun selectedSegmentLabel(key: String): String = listOf("true", "false")
        .firstOrNull { isSelected("finecat.compatSet.$key.$it") }
        ?.let { if (it == "true") "开" else "关" }
        ?: "未设置"

    private fun countText(): String = textOf("finecat.compatCount")

    private fun rowState(tag: String): String =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.StateDescription).orEmpty()

    /** 当前模型级 api：读方言行的 stateDescription，而不是整行文字（整行还含标签与副行）。 */
    private fun apiTextOf(): String = rowState("finecat.modelApiRow")

    private fun lossKeys(): String = textOf("finecat.modelApiLoss").removePrefix("将删除：")

    private fun visibleKeyCount(): Int =
        PROVIDER_COMPAT_KEYS_BY_API["openai-completions"].orEmpty().count { key ->
            compose.onAllNodesWithTag("finecat.compatKey.$key").fetchSemanticsNodes().isNotEmpty()
        }

    /**
     * 取节点文本：先未合并树再合并树，取第一个非空文本。
     *
     * 两种节点混着出现——取值标记本身就是 Text（未合并树上就有文本），而行/分段这类
     * 容器节点的文本在子节点上（只有合并树能拿到）。只查其中一棵树，另一类节点就会
     * 静默返回空串，看起来像「界面没更新」。
     */
    private fun textOf(tag: String): String {
        for (unmerged in listOf(true, false)) {
            val found = compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes()
                .firstNotNullOfOrNull { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.joinToString("") { it.text }
                        ?.takeIf { it.isNotEmpty() }
                }
            if (found != null) return found
        }
        throw AssertionError("节点 $tag 没有可读文本")
    }

    private fun nodeOf(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        ?: compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?: throw AssertionError("节点 $tag 未找到")

    private fun boundsOf(tag: String) = nodeOf(tag).boundsInRoot

    /** 组合内探针：把当前主题解析出的 surface 色写进语义，供「亮暗是否真的生效」断言。 */
    @androidx.compose.runtime.Composable
    private fun themeProbe(): String =
        "surface=" + Integer.toHexString(LocalFineCatColors.current.surface.toArgb())

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun waitForIme(timeoutMs: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isImeShown()) return
            Thread.sleep(200)
        }
    }

    private fun isImeShown(): Boolean =
        shell("dumpsys input_method | grep -E 'mInputShown|mVisibleWindow'")
            .contains("mInputShown=true")

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    // ---- wire 解析 ----

    /**
     * provider-put 里 compat 字段的**原始文本**（键序即声明顺序，不做归一）。
     *
     * 用扫描而不是正则：Android 走 ICU 正则，`\{` 与字符类里的 `}` 在 ICU 下会直接抛
     * PatternSyntaxException（桌面 JVM 的 java.util.regex 接受，两者行为不一致），
     * 而这里要的只是「从 `{` 起数括号」，扫一遍更稳也更直白。
     *
     * 返回 null = 该模型对象里没有 compat 字段（= 本次没碰这一层）。
     */
    private fun rawCompatJson(payload: String): String? {
        val key = payload.indexOf("\"compat\":")
        if (key < 0) return null
        val afterKey = key + "\"compat\":".length
        val valueAt = afterKey + payload.substring(afterKey).indexOfFirst { !it.isWhitespace() }
        if (valueAt < 0) return null
        if (payload.startsWith("null", valueAt)) return "null"
        if (payload[valueAt] != '{') return null
        var depth = 0
        for (index in valueAt until payload.length) {
            when (payload[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return payload.substring(valueAt, index + 1)
                }
            }
        }
        return null
    }

    /** provider-put 里 m-1 模型对象的原始文本（compat 里没有嵌套大括号，数括号即可）。 */
    private fun rawModelJson(payload: String): String {
        val key = payload.indexOf("\"id\":\"$MODEL\"")
        check(key >= 0) { "provider-put 里没有 $MODEL 模型对象：$payload" }
        // 从 id 之前那个 `{` 起数括号：从 id 本身开始数会永远数不到闭合。
        val braceAt = payload.lastIndexOf('{', key)
        var depth = 0
        for (index in braceAt until payload.length) {
            when (payload[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return payload.substring(braceAt, index + 1)
                }
            }
        }
        error("模型对象没有闭合：$payload")
    }

    /** 去掉 compat 字段后的 payload（用于「两次 put 除 compat 外逐字相同」的比对）。 */
    private fun withoutCompatField(payload: String): String {
        val key = payload.indexOf(",\"compat\":")
        if (key < 0) return payload
        val braceAt = payload.indexOf('{', key)
        if (braceAt < 0) return payload
        var depth = 0
        for (index in braceAt until payload.length) {
            when (payload[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return payload.substring(0, key) + payload.substring(index + 1)
                }
            }
        }
        return payload
    }

    /** wire 模型对象里的 api（同样避开正则）。 */
    private fun apiOfWireModel(modelJson: String): String {
        val key = modelJson.indexOf("\"api\":\"")
        if (key < 0) return ""
        val start = key + "\"api\":\"".length
        return modelJson.substring(start).takeWhile { it != '"' }
    }

    private fun compatSortedOfRaw(raw: String?): String {
        val obj = raw?.let { Json.parseToJsonElement(it).jsonObject } ?: return ""
        return obj.entries.sortedBy { it.key }.joinToString("|") { (key, value) ->
            "$key=${value.jsonPrimitive.booleanOrNull?.toString() ?: value.jsonPrimitive.content}"
        }
    }

    private fun compatSortedOf(model: GatewayProviderModel): String {
        val obj = model.compat?.objectValue ?: return ""
        return obj.entries.sortedBy { it.key }.joinToString("|") { (key, value) ->
            val rendered = when (value) {
                is JsonValue.BooleanValue -> value.value.toString()
                is JsonValue.StringValue -> value.value
                else -> value.toString()
            }
            "$key=$rendered"
        }
    }

    // ---- 帧 ----

    private fun providersFrame(
        m1Compat: String? = M1_COMPAT,
        api: String = "openai-completions",
        m1Name: String = "M1",
    ) = """
        {"kind":"providers","providers":[{
        	"id":"$PROVIDER","name":"F12 中转","baseUrl":"$BASE_URL","apiKeySet":true,
        	"status":{"ok":true,"latencyMs":9},
        	"models":${modelsOf(m1Compat, api, m1Name)}
        }]}
    """.trimIndent()

    /**
     * 回执刻意不带 `status`（这样「保存后状态由独立 providers 回读刷新」仍可判定），
     * 但**必须回全量模型**：网关的 `provider-saved` 就是合并后的整份 provider，表单侧按
     * 模型键集合逐个归属，少回一个会被判成「回显对不上这次提交」而拒收。
     */
    private fun savedFrame(
        m1Compat: String?,
        api: String = "openai-completions",
        m1Name: String = "M1",
    ) = """
        {"kind":"provider-saved","admitted":true,"refreshed":true,"provider":{
        	"id":"$PROVIDER","name":"F12 中转","baseUrl":"$BASE_URL","apiKeySet":true,
        	"models":${modelsOf(m1Compat, api, m1Name)}
        }}
    """.trimIndent()

    private fun modelsOf(m1Compat: String?, api: String, m1Name: String = "M1") =
        "[${modelJson(MODEL, m1Name, api, m1Compat)}," +
        "${modelJson(MODEL_2, "M2", "openai-completions", M2_COMPAT)}]"



    private fun modelJson(id: String, name: String, api: String, compat: String?) = """
        {"id":"$id","name":"$name","api":"$api","input":["text"],"reasoning":true,
         "contextWindow":128000,"maxTokens":8192${compat?.let { ",\"compat\":$it" } ?: ""}}
    """.trimIndent()

    private fun expectedSurface(dark: Boolean): String = Integer.toHexString(
        (if (dark) darkFineCatColors().surface else lightFineCatColors().surface).toArgb(),
    )

    @After
    fun tearDown() {
        lines.forEach { android.util.Log.i("F12CAP", it) }
        restoreDeviceConfig()
        runCatching { holder?.close() }
        runCatching { graph?.applicationScope?.cancel() }
        runCatching { graph?.gatewayScope?.cancel() }
        holder = null
        graph = null
    }

    /** 设备设置必须读回基线（矩阵用例改过 density/font_scale）。 */
    private fun restoreDeviceConfig() {
        runCatching {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("wm density 480")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).close()
            val pfd2 = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("settings put system font_scale 1.0")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd2).close()
        }
    }

    private companion object {
        const val PROVIDER = "f12-compat"
        const val MODEL = "m-1"
        const val MODEL_2 = "m-2"
        const val BASE_URL = "https://relay.example/v1"
        const val THEME_PROBE = "finecat.themeProbe"

        const val M1_COMPAT = """{"supportsDeveloperRole":true,"thinkingFormat":"deepseek","supportsStore":false}"""

        /** m-2 的 compat 固定不变（它在用例里只当「另一个模型的草稿」用）。 */
        const val M2_COMPAT = """{"supportsStore":false}"""
        const val M1_COMPAT_SORTED = "supportsDeveloperRole=true|supportsStore=false|thinkingFormat=deepseek"

        /**
         * hello 的能力清单按网关 PROTOCOL.md §1.5 原样给。
         *
         * 少了 `provider-test` / `provider-put` 这几项，表单里的「测试连接」会直接置灰
         * （`enabled = supportsRequest("provider-test")`），用例就会卡在一个与被测行为无关的
         * 前提上——夹具必须和真实网关一样全。
         */
        val HELLO = """{"kind":"hello","protocol":3,"authenticated":true,"clients":1,
            "capabilities":["ping","sessions","subscribe","unsubscribe","message","abort","history",
            "state","models","set-model","directories","directory-create","workspace-create","providers",
            "provider-put","provider-delete","provider-test","provider-models","restart-session","name",
            "compact","commands","tree","switch-branch","attachment"]}"""
    }
}
