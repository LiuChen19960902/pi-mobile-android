package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.theme.darkFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.lightFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.android.waitUntil
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 思考强度映射的设备 fixture，验证编辑、提交与回读的一致性。
 *
 * 判据链是 **原始档位定义 → 编辑意图 → provider-put JSON → provider-saved → providers 回读**。
 * 档位真源是 `models` 目录帧的 `thinkingLevels`（PROTOCOL §4.13；`providers` 帧 §4.15 不带这个
 * 字段），fixture 先落目录帧再落 providers 帧，然后走真实 UI，最后逐字比对本类声明的 expected。
 *
 * 三处最容易造假的地方在这里被钉死：
 * - **不能只靠本地回填**：`provider-saved` 的回执刻意不带 `status`，只有独立 `providers`
 *   回读才带。断言「回执到达后 status 仍为 null、回读到达后才非 null」，就证明状态确实由
 *   回读刷新，而不是 receipt echo 顶上去的。
 * - **取消真的零写入**：整个生命周期 `provider-put` 计数为 0，重开后显示的仍是服务端原值。
 * - **三态不互相替代**：显式 null 是真的 JSON null 条目；没动过的档位键不丢；整表为空才是
 *   字段省略。expected 里逐项写死。
 *
 * 明确不在本轮（`DEC-F11-SCOPE` 移出项）：删除最后一档 override、恢复默认、空 map 当清除、
 * 未知键原样保留。用例不提供这些动作，也不假装它们可用。
 */
@RunWith(AndroidJUnit4::class)
class FineCatThinkingMapF11DeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null
    private lateinit var transport: FakeTransport
    private var measureFileReady = false

    @After
    fun tearDown() {
        restoreDeviceConfig()
        runCatching { holder?.close() }
        runCatching { graph?.applicationScope?.cancel() }
        runCatching { graph?.gatewayScope?.cancel() }
        holder = null
        graph = null
    }

    // ================================================================== 用例

    /**
     * 改 `medium` 的取值、把 `minimal` 置显式 null，然后保存。
     *
     * 期望 wire（严格相等）：`{"off":null,"minimal":null,"low":"low","medium":"high","high":"high"}`
     * —— `minimal` 的 null 是**显式下发**的条目；没动过的 `off` 仍是原来的 null；没有任何一档
     * 被删掉或凭空新增。
     */
    @Test
    fun changedValueAndExplicitNull_putMatchesExpected_thenReceiptThenIndependentReadback() {
        connect()

        val columns = openMapping(WIRE, "reasoner")
        assertEquals("映射列数必须等于目录帧真实声明的档位数", DECLARED, columns)
        assertEquals(
            "初始取值展示：显式 null / 未设置 / 三个实值",
            listOf("null", "—", "low", "low", "high"),
            valuesOf(columns),
        )
        emitAssert("map.columns", columns.joinToString(","))
        emitAssert("map.values.before", valuesOf(columns).joinToString(","))
        saveShot("f11-map-columns.png")

        setValue("medium", "high")
        setUnsupported("minimal")
        compose.waitForIdle()
        assertEquals(
            "编辑后取值展示：两档 null、low、medium=high、high",
            listOf("null", "null", "low", "high", "high"),
            valuesOf(columns),
        )
        emitAssert("map.values.edited", valuesOf(columns).joinToString(","))
        saveShot("f11-map-edited.png")

        // 退二级卡 → 点保存
        backToProviderCard()
        val readbacksBeforeSave = countProviders()
        compose.onNodeWithText("保存").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("provider-put").isNotEmpty() } }

        // ---- wire：实际 JSON 与 expected 严格相等 ----
        val put = transport.payloadsOfType("provider-put").single()
        val putModels = put.getValue("models").jsonArray
        assertEquals("provider-put 应只含这一行模型", 1, putModels.size)
        val wireMap = putModels.single().jsonObject.getValue("thinkingLevelMap").jsonObject
        assertEquals("保存后的实际 JSON 必须与 fixture expected 严格相等", EXPECTED_WIRE, wireMap)
        // 取证行用「按键排序的规范写法」而不是 JSON 原文：结构相等由上面的 assertEquals
        // 严格把关，这里只让 check 能逐字比对，且不依赖 map 的迭代顺序。
        emitAssert("wire.sorted", canonicalMap(wireMap))
        // 原始 JSON 也落一行：规范写法便于逐字比对，原始文本用于人工复核键序与字面量。
        emitAssert("wire.rawJson", putModels.single().jsonObject.getValue("thinkingLevelMap").toString())
        emitAssert("wire.nullLevels", wireMap.filterValues { it == JsonNull }.keys.sorted().joinToString(","))
        emitAssert("wire.valueLevels", wireMap.filterValues { it != JsonNull }.keys.sorted().joinToString(","))

        // ---- 顺序：回执到达前不得提前发起回读 ----
        assertEquals(
            "provider-saved 到达前不得发起保存后的 providers 回读",
            readbacksBeforeSave,
            countProviders(),
        )
        transport.receive(providerSavedFrame())
        runBlocking { waitUntil { holder!!.providerSaveIsLoading.not() } }

        assertNull(
            "回执到达后、providers 回读到达前，列表必须还是回执那份（回执不带 status）",
            holder!!.providers.first { it.id == WIRE }.status,
        )
        val putIndex = transport.sentTypes.indexOfLast { it == "provider-put" }
        val readbackIndex = transport.sentTypes.indexOfLast { it == "providers" }
        assertTrue("provider-saved 之后必须出现独立 providers 回读请求", readbackIndex > putIndex)
        emitAssert("order.readbacksBeforeReceipt", readbacksBeforeSave.toString())
        emitAssert("order.sentAfterPut", transport.sentTypes.drop(putIndex + 1).distinct().sorted().joinToString(","))

        transport.receive(providersFrame(persisted = true))
        runBlocking { waitUntil { holder!!.providers.first { it.id == WIRE }.status != null } }

        // ---- 回读值与 expected 严格相等，且只可能来自回读 ----
        val readback = holder!!.providers.first { it.id == WIRE }
        assertNotNull("独立 providers 回读必须真的刷新了列表（回执不带 status）", readback.status)
        val readMap = readback.models.single().thinkingLevelMap.orEmpty()
        assertEquals("providers 回读的完整 thinking map 必须与 expected 严格相等", EXPECTED_WIRE_TEXT, readMap)
        emitAssert("readback.sorted", readMap.entries.sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value ?: "null"}" })

        // 重开表单：显示的必须是回读后的服务端持久化值，不是本地草稿。
        closeAllSheets()
        val reopened = openMapping(WIRE, "reasoner")
        assertEquals("重开后列集合不变", DECLARED, reopened)
        assertEquals(
            "重开后显示的必须是服务端持久化后的值",
            listOf("null", "null", "low", "high", "high"),
            valuesOf(reopened),
        )
        saveShot("f11-map-readback.png")
    }

    /**
     * 取消 = 收掉整张 Provider 表单；模型二级卡的「返回」只是回上一级，草稿仍在（那是回上一级，
     * 不是取消）。本用例走完整链路：编辑 → 回一级（草稿仍在）→ 取消整表 → 重开。
     *
     * 全程 `provider-put` 为 0，且重开后显示的仍是服务端原值。
     */
    @Test
    fun cancelEdit_thenReopen_keepsServerValuesAndSendsNoProviderPut() {
        connect()
        val columns = openMapping(WIRE, "reasoner")
        val before = valuesOf(columns)

        setValue("medium", "xhigh")
        setUnsupported("minimal")
        compose.waitForIdle()
        assertEquals(
            "草稿确实改了（否则本用例测不到取消）",
            listOf("null", "null", "low", "xhigh", "high"),
            valuesOf(columns),
        )

        // 回一级不是取消：草稿必须还在，否则用户改一个模型再回来看就没了。
        backToProviderCard()
        compose.onNodeWithTag("finecat.modelRow.0").performClick()
        awaitTag("finecat.modelDetail")
        assertEquals(
            "回一级后草稿仍在（回上一级 ≠ 取消）",
            listOf("null", "null", "low", "xhigh", "high"),
            valuesOf(columnTags()),
        )
        backToProviderCard()
        assertTrue("到这一步仍不得发 provider-put", transport.payloadsOfType("provider-put").isEmpty())

        // 真正的取消：收掉整张 Provider 表单。
        closeAllSheets()
        assertTrue("取消编辑完整生命周期不得产生 provider-put", transport.payloadsOfType("provider-put").isEmpty())

        val reopened = openMapping(WIRE, "reasoner")
        assertEquals("重新打开后仍显示服务端原值", before, valuesOf(reopened))
        assertTrue("重新打开后仍不得产生 provider-put", transport.payloadsOfType("provider-put").isEmpty())
        emitAssert("cancel.putCount", transport.payloadsOfType("provider-put").size.toString())
        emitAssert("cancel.reopenedValues", valuesOf(reopened).joinToString(","))
        saveShot("f11-map-cancel.png")
    }

    /**
     * 三种空态各一句话，且都不出现任何列：
     * - `noreason`（providers 帧 `reasoning:false`）
     * - `nodeclared`（目录帧声明 `thinkingLevels:[]`）
     * - `uncataloged`（providers 里有、目录帧查不到 → 读不到真实声明，不能拿全集顶替）
     */
    @Test
    fun emptyStates_noReasoning_noDeclaredLevels_modelNotInCatalog() {
        connect()
        compose.onNodeWithTag("finecat.providerRow.$EMPTY").performClick()
        awaitTag("finecat.providerEdit")

        val hints = listOf("noreason", "nodeclared", "uncataloged").map { modelId ->
            val index = holder!!.providers.first { it.id == EMPTY }.models.indexOfFirst { it.id == modelId }
            compose.onNodeWithTag("finecat.modelRow.$index").performScrollTo().performClick()
            awaitTag("finecat.modelDetail")
            scrollToMapping()
            val hint = textOf("finecat.thinkingEmpty")
            assertTrue("$modelId 必须显示空态说明", hint.isNotBlank())
            assertEquals("$modelId 不得出现任何档位列", 0, columnTags().size)
            backToProviderCard()
            "$modelId=$hint"
        }
        assertEquals(
            listOf(
                "noreason=这个模型不支持推理，没有强度可映射。",
                "nodeclared=这个模型还没声明有哪些强度档位。",
                "uncataloged=模型目录里没有这个模型，读不到它真实声明的档位。先在电脑上认证并启用它，再回来编辑。",
            ),
            hints,
        )
        emitAssert("empty.hints", hints.joinToString(" | "))
        saveShot("f11-map-empty.png")
    }

    /**
     * 在 A 模型上开着取值浮层退卡 → 打开同一个 provider 的 B 模型：B 不得继承 A 的浮层、
     * 锚点或错误行。
     *
     * 口径说明：本用例证明的是**用户可见行为**（不串卡）。它并不能单独证伪 `key(行下标)` 那一行
     * ——退卡时 `detailIndex=null` 已让子树离开组合、`remember` 本就会重建，去掉 `key()` 照样绿。
     * `key()` 的价值在于「子树存活期间行下标变化」这条当前 UI 不可达的路径。
     *
     * 用 `f11-empty` 这个 provider：它同时有 5 列的 `reasoner2` 和 0 列的 `nodeclared`，
     * 所以「换模型」是同一张表单内的真实切换，不用跨 provider。
     */
    @Test
    fun switchModel_doesNotLeakPopoverOrDraft() {
        connect()
        val a = openMapping(EMPTY, "reasoner2")
        assertEquals("A 有目录声明的 5 档", DECLARED, a)
        setValue("medium", "high")
        compose.onNodeWithTag("finecat.thinkingVal.medium").performScrollTo().performClick()
        openCustomValuePicker()
        awaitTag("finecat.thinkingInput")
        // 切走之后 A 的节点已不在树里，取值必须先快照下来。
        val aValues = valuesOf(a)
        backToProviderCard()
        assertTrue("退卡后浮层必须收掉", compose.onAllNodesWithTag("finecat.thinkingInput").fetchSemanticsNodes().isEmpty())

        // B = 同一 provider 里目录帧声明了 0 档的模型。
        compose.onNodeWithTag("finecat.modelRow.2").performClick()
        awaitTag("finecat.modelDetail")
        assertTrue(
            "换模型后不得出现上一张卡的浮层",
            compose.onAllNodesWithTag("finecat.thinkingInput").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "换模型后不得出现上一张卡的错误行",
            compose.onAllNodesWithTag("finecat.thinkingError").fetchSemanticsNodes().isEmpty(),
        )
        assertEquals("B 没有列", 0, columnTags().size)
        assertEquals("A 的草稿改到了 medium=high", listOf("—", "—", "—", "high", "—"), aValues)
        emitAssert("switch.bColumnCount", "0")
        emitAssert("switch.aValues", aValues.joinToString(","))

        // 回到 A 确认草稿真的还在（没有被 B 的空态洗掉）。
        backToProviderCard()
        compose.onNodeWithTag("finecat.modelRow.0").performScrollTo().performClick()
        awaitTag("finecat.modelDetail")
        assertEquals("回到 A 草稿仍是 medium=high", aValues, valuesOf(columnTags()))
        saveShot("f11-map-switch-model.png")
    }

    /**
     * 换协议走 `switchApi`（只按新协议键集裁 compat），thinkingMap 不在其列：
     * 这里钉住「换协议不会静默抹掉思考映射」，同时确认基础表单的协议语义没有回退。
     */
    @Test
    fun switchApi_keepsThinkingMap_andBasicFormStillSwitchesApi() {
        connect()
        val columns = openMapping(WIRE, "reasoner")
        setValue("medium", "high")

        compose.onNodeWithTag("finecat.modelApiRow").performScrollTo().performClick()
        awaitTag("finecat.modelApi.anthropic-messages")
        compose.onNodeWithTag("finecat.modelApi.anthropic-messages").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.thinkingMap").performScrollTo()
        compose.waitForIdle()

        assertEquals("换协议不得抹掉思考映射", listOf("null", "—", "low", "high", "high"), valuesOf(columns))
        assertTrue(
            "协议已切到 anthropic-messages（基础表单语义不回退）",
            compose.onAllNodesWithText("anthropic-messages", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        emitAssert("switchApi.values", valuesOf(columns).joinToString(","))
        saveShot("f11-map-switch-api.png")
    }

    /** 把一个已设置的档位清空 = 删除 override，本轮不可表达：必须拒绝并把原因留在卡内。 */
    @Test
    fun clearAnExistingLevel_isRejected_withVisibleReasonAndNoDraftChange() {
        connect()
        val columns = openMapping(WIRE, "reasoner")
        compose.onNodeWithTag("finecat.thinkingVal.low").performScrollTo().performClick()
        openCustomValuePicker()
        awaitTag("finecat.thinkingInput")
        // performTextClearance 需要焦点：先点一下输入框，否则根本没被清空，测的就不是「清空」了。
        compose.onNodeWithTag("finecat.thinkingInput").performClick()
        compose.onNodeWithTag("finecat.thinkingInput").performTextClearance()
        assertEquals("输入框已清空（否则本用例测的不是清空）", "", textOf("finecat.thinkingInput"))
        compose.onNodeWithTag("finecat.thinkingApply").performClick()
        compose.waitForIdle()

        assertTrue(
            "拒绝清空必须留下可见原因",
            compose.onAllNodesWithTag("finecat.thinkingError").fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals("被拒绝的清空不得改动草稿", "low", valueOf("low"))
        assertEquals("整列取值不变", listOf("null", "—", "low", "low", "high"), valuesOf(columns))
        emitAssert("clearRejected.error", textOf("finecat.thinkingError"))
        emitAssert("clearRejected.values", valuesOf(columns).joinToString(","))
        saveShot("f11-map-clear-rejected.png")
    }

    /**
     * 取值浮层里的输入框 IME/insets 行为，四条判据：
     *
     * 1. 点输入框 → 软键盘真的弹起（`dumpsys input_method` 的 `mInputShown=true`）；
     * 2. 键盘弹起后浮层不被遮挡：从同一份 `dumpsys window windows` 快照解析出目标浮层
     *    （frame 宽度≥300px 的非 EXITING Pop-Up Window，排除 62px 的选择手柄小窗）的 frame 底边，
     *    与 InputMethod 窗口 touchable region 的顶边，断言 浮层底边 ≤ 键盘顶边，并钉住
     *    浮层窗口的 softInputMode 已是 `adjust=resize`（FcPopover 设置，WMS 依此把浮层
     *    约束到键盘上方）；
     * 3. 键盘亮着时按系统 Back → 浮层收掉（focusable Popup 自己 dismiss）；
     * 4. 键盘收起后仍能把取值提交进草稿。
     */
    @Test
    fun ime_appearsOnValuePick_backDismissesPopoverAndValueStillCommits() {
        connect()
        openMapping(WIRE, "reasoner")
        compose.onNodeWithTag("finecat.thinkingVal.medium").performScrollTo().performClick()
        openCustomValuePicker()
        awaitTag("finecat.thinkingInput")
        compose.onNodeWithTag("finecat.thinkingInput").performClick()
        waitForIme()
        val shown = isImeShown()
        assertTrue("点开取值浮层后键盘应可见", shown)
        emitAssert("ime.shown", shown.toString())

        // 落位判据：浮层 frame 底边不得超过键盘触摸区顶边，且窗口参数确为 resize。
        // 断言值来自最后一次真实解析（可能是 false/非 resize/unparsed），判定由 check 把关，
        // 避免「只在满足条件时才落证」的重言式断言；坐标与截图 f11-map-ime.png 同一稳定态。
        val scan = scanPopoverPlacementUntilSettled()
        val placement = scan.placement
        appendMeasureLine("ime.popupFrame=${placement?.popupFrame ?: "unparsed"}")
        appendMeasureLine("ime.popupDisplay=${placement?.popupDisplay ?: "unparsed"}")
        appendMeasureLine("ime.popupBottom=${placement?.popupBottom ?: "unparsed"}")
        appendMeasureLine("ime.imeTop=${placement?.imeTop ?: "unparsed"}")
        emitAssert("ime.popupAdjustMode", placement?.adjustMode ?: "unparsed")
        emitAssert("ime.popupAboveIme", placement?.let { (it.popupBottom <= it.imeTop).toString() } ?: "unparsed")
        check(placement != null && placement.adjustMode == "resize" && placement.popupBottom <= placement.imeTop) {
            "取值浮层未稳定落到键盘上方：placement=$placement candidates=${scan.popupCandidates} " +
                "imeSeen=${scan.imeSeen} popups=[${scan.popupSummaries}]"
        }
        saveShot("f11-map-ime.png")

        // 键盘亮着时按系统 Back。Back 是分层的：带焦点的输入框先收键盘，再收浮层，
        // 所以按到浮层真的收掉为止（有次数上限），并把实际次数作为取证行落下来。
        // 用真实按键而不是往 root 注入触摸——键盘弹起时 root 触摸注入会直接失败。
        var backs = 0
        while (compose.onAllNodesWithTag("finecat.thinkingInput").fetchSemanticsNodes().isNotEmpty()) {
            check(backs < 4) { "连按 4 次 Back 仍未收起取值浮层" }
            InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            backs++
            compose.waitForIdle()
        }
        // 可证伪：backs=0（浮层自己先收掉、Back 没起作用）会落 false，与登记期望 true 不等。
        emitAssert("ime.dismissedByBack",
            (backs in 1..4 &&
                compose.onAllNodesWithTag("finecat.thinkingInput").fetchSemanticsNodes().isEmpty()).toString())
        emitAssert("ime.backPresses", backs.toString())

        // 键盘收起后取值仍能正常提交（真正落进草稿）。
        setValue("medium", "high")
        assertEquals("键盘收起后取值提交生效", "high", valueOf("medium"))
        emitAssert("ime.committedValue", valueOf("medium"))
        saveShot("f11-map-ime-committed.png")
    }

    // ---- 三环境亮暗矩阵 ----

    @Test
    fun matrix_390dp_fs10_light() = matrixStep("f11-390-fs10-light", dark = false, expectedDp = 390f)

    @Test
    fun matrix_390dp_fs10_dark() = matrixStep("f11-390-fs10-dark", dark = true, expectedDp = 390f)

    @Test
    fun matrix_360dp_fs10_light() =
        matrixStep("f11-360-fs10-light", dark = false, expectedDp = 360f, densityDpi = 520)

    @Test
    fun matrix_360dp_fs10_dark() =
        matrixStep("f11-360-fs10-dark", dark = true, expectedDp = 360f, densityDpi = 520)

    @Test
    fun matrix_360dp_fs13_light() =
        matrixStep("f11-360-fs13-light", dark = false, expectedDp = 360f, densityDpi = 520, fontScale = "1.3")

    @Test
    fun matrix_360dp_fs13_dark() =
        matrixStep("f11-360-fs13-dark", dark = true, expectedDp = 360f, densityDpi = 520, fontScale = "1.3")

    /**
     * 三环境亮暗 + 横向可达。
     *
     * 横向可达必须用 **7 档**的模型测：目录帧对 `reasoner` 只声明 5 档，5 档在 360dp 下
     * 放得下，测不出滚动。`wide` 的目录清单是 pi 档位全集 7 档（`xhigh`/`max` 只有显式
     * 映射过才会出现在真实清单里，所以这里给它们配了 override），360dp 下必然溢出。
     */
    private fun matrixStep(
        tag: String,
        dark: Boolean,
        expectedDp: Float,
        densityDpi: Int? = null,
        fontScale: String? = null,
    ) {
        // 每个用例都显式落配置：390dp↔density 480、360dp↔density 520。若只在 360 用例里设置，
        // 390 用例会继承上一个的 520（实测视口变 360dp），用例顺序一变就假失败。
        applyDeviceConfig(densityDpi ?: 480, fontScale ?: "1.0")
        connect(dark = dark)
        val columns = openMapping(EMPTY, "wide")
        assertEquals("$tag 7 档模型列集合不受环境影响", DECLARED_WIDE, columns)

        val row = boundsOf("finecat.thinkingMap")
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val viewportDp = root.width / compose.density.density
        assertTrue(
            "$tag 整屏视口应=$expectedDp dp ±2（实际 $viewportDp）",
            abs(viewportDp - expectedDp) <= 2f,
        )
        // 原型 .sheet .scroll（styles.css:931）左右各20px；映射区不是整屏宽度。
        val mapViewportDp = row.width / compose.density.density
        val expectedMapDp = expectedDp - 40f
        assertTrue(
            "$tag 映射区视口应=$expectedMapDp dp ±2（实际 $mapViewportDp）",
            abs(mapViewportDp - expectedMapDp) <= 2f,
        )
        assertTrue("$tag 映射区左边距应20dp±2", abs((row.left - root.left) / compose.density.density - 20f) <= 2f)
        assertTrue("$tag 映射区右边距应20dp±2", abs((root.right - row.right) / compose.density.density - 20f) <= 2f)

        // 7 档在 390/360dp 下都要溢出：末列初始不在可视区内，横向滑一次才可达。
        // 「不在可视区」有两种正确表现——bounds 宽度为 0（还没布局到）或右边界超出映射区。
        val lastBefore = rawBoundsOf("finecat.thinkingVal.max")
        assertTrue(
            "$tag 末列初始不在可视区内（last=$lastBefore rowRight=${row.right}）",
            lastBefore.width == 0f || lastBefore.left >= row.right - 1f || lastBefore.right > row.right + 1f,
        )
        compose.onNodeWithTag("finecat.thinkingMap").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val lastAfter = boundsOf("finecat.thinkingVal.max")
        assertTrue(
            "$tag 横向滑动后末列必须完整进入可视区（last=$lastAfter row=$row）",
            lastAfter.width > 0f &&
                lastAfter.left >= row.left - 1f &&
                lastAfter.right <= row.right + 1f,
        )
        // 亮暗两态必须真的换肤：只对比截图像素会被状态栏时钟之类噪声干扰，
        // 探针读的是组合内解析出的 surface 色，是「主题生效」这件事的直接事实。
        val probe = textOf(THEME_PROBE)
        assertTrue(
            "$tag 主题探针缺失（appearanceMode=${holder!!.appearanceMode}，期望 dark=$dark）",
            probe.startsWith("surface="),
        )
        assertEquals(
            "$tag 主题探针与请求的亮暗不符：期望 dark=$dark，实际 $probe",
            expectedSurface(dark),
            probe.substringAfter("surface="),
        )
        assertTrue(
            "亮暗两档的期望 surface 必须不同，否则上面那条断言判别不了任何东西",
            expectedSurface(true) != expectedSurface(false),
        )
        emitAssert("matrix.$tag.theme", "${if (dark) "dark" else "light"}/$probe")
        emitAssert("matrix.$tag.columns", columns.joinToString(","))
        emitAssert("matrix.$tag.viewportDp", viewportDp.toInt().toString())
        emitAssert("matrix.$tag.mapViewportDp", mapViewportDp.toInt().toString())
        emitAssert("matrix.$tag.lastColumnReachable", "true")
        saveShot("$tag.png")
    }

    // ================================================================== fixture 与 UI 操作

    private fun connect(dark: Boolean = false) {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            requestTimeoutOverride = 120_000L,
        )
        val stateHolder = graph!!.stateHolder
        holder = stateHolder
        compose.setContent {
            // 主题必须由 appearanceMode 驱动。写死 `FineCatTheme { }`（默认 light）时
            // `updateAppearanceMode("dark")` 改了状态却进不了组合树，暗色矩阵会拍出两张
            // 字节相同的图。探针节点把解析出的 surface 色写进语义，暗色是否真的生效由此断言。
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
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3}""")
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
        }
        val mode = if (dark) "dark" else "light"
        testOnMain { stateHolder.updateAppearanceMode(mode) }
        runBlocking { waitUntil { stateHolder.appearanceMode == mode } }
        // 档位真源与列表分开送：目录帧先到（providers 帧不带 thinkingLevels）。
        transport.receive(modelsFrame())
        runBlocking { waitUntil { holder!!.snapshot.modelCatalog != null } }
        transport.receive(providersFrame())
        runBlocking { waitUntil { holder!!.providers.size == 2 } }
        compose.waitForIdle()
    }

    /** 打开某 provider 下某模型的二级卡并滚到映射区，返回列名（按屏上从左到右）。 */
    private fun openMapping(providerId: String, modelId: String): List<String> {
        if (compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("finecat.providerRow.$providerId").performClick()
            awaitTag("finecat.providerEdit")
        }
        val index = holder!!.providers.first { it.id == providerId }.models.indexOfFirst { it.id == modelId }
        compose.onNodeWithTag("finecat.modelRow.$index").performScrollTo().performClick()
        awaitTag("finecat.modelDetail")
        return scrollToMapping()
    }

    private fun scrollToMapping(): List<String> {
        if (compose.onAllNodesWithTag("finecat.thinkingMap").fetchSemanticsNodes().isNotEmpty()) {
            runCatching { compose.onNodeWithTag("finecat.thinkingMap").performScrollTo() }
            compose.waitForIdle()
        }
        return columnTags()
    }

    /**
     * 二级卡 → 一级卡。返回键是分层的：浮层开着时先只关浮层，所以这里按到二级卡真的收掉为止
     * （有次数上限，不会在没退出去时无限点）。
     */
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
            tapScrim()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("finecat.providerEdit").fetchSemanticsNodes().isEmpty()
            }
        }
        compose.waitForIdle()
    }

    private fun tapScrim() {
        compose.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.06f)) }
        compose.waitForIdle()
    }

    private fun setValue(level: String, value: String) {
        compose.onNodeWithTag("finecat.thinkingVal.$level").performScrollTo().performClick()
        openCustomValuePicker()
        awaitTag("finecat.thinkingInput")
        compose.onNodeWithTag("finecat.thinkingInput").performTextClearance()
        compose.onNodeWithTag("finecat.thinkingInput").performTextInput(value)
        compose.onNodeWithTag("finecat.thinkingApply").performClick()
        compose.waitForIdle()
    }

    private fun setUnsupported(level: String) {
        compose.onNodeWithTag("finecat.thinkingVal.$level").performScrollTo().performClick()
        awaitTag("finecat.thinkingNull")
        compose.onNodeWithTag("finecat.thinkingNull").performClick()
        compose.waitForIdle()
    }

    private fun openCustomValuePicker() {
        awaitTag("finecat.thinkingCustom")
        compose.onNodeWithTag("finecat.thinkingCustom").performScrollTo().performClick()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun countProviders(): Int = transport.sentTypes.count { it == "providers" }

    /** 按键排序的规范写法：`high=high|low=low|...`，null 值写作 `null`。 */
    private fun canonicalMap(map: JsonObject): String = map.entries.sortedBy { it.key }
        .joinToString("|") { "${it.key}=${(it.value as? JsonPrimitive)?.contentOrNull ?: "null"}" }

    // ================================================================== 读取与断言辅助

    /**
     * 列名按语义树顺序返回——Row 的组合顺序就是从左到右的视觉顺序。
     *
     * **不能**按 `boundsInRoot.left` 排：横向溢出时可视区外的列 bounds 宽度是 0（实测
     * `xhigh=left 1116, width 0`、`max` 整块为零），按 left 排会把它们静默排到最前面。
     * 也不要求每列 bounds 非零——那正是「尚未滚到」的正确表现，横向可达另用滚后 bounds 断言。
     */
    private fun columnTags(): List<String> =
        compose.onAllNodes(
            SemanticsMatcher("thinking column") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(COL_PREFIX) == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.TestTag)!!.removePrefix(COL_PREFIX) }

    private fun valuesOf(columns: List<String>): List<String> = columns.map { valueOf(it) }

    private fun valueOf(level: String): String =
        descriptionOf("finecat.thinkingVal.$level").substringAfter(" 取值 ")

    private fun descriptionOf(tag: String): String = nodeOf(tag)
        .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

    private fun textOf(tag: String): String = nodeOf(tag)
        .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun nodeOf(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            ?: compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: throw AssertionError("节点 $tag 未找到")

    private fun boundsOf(tag: String): Rect = nodeOf(tag).boundsInRoot

    /** 不过滤零尺寸：横向未滚到的列就是宽度 0，调用方要自己按「不在可视区」判断。 */
    private fun rawBoundsOf(tag: String): Rect = compose
        .onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull()?.boundsInRoot ?: throw AssertionError("节点 $tag 未找到")

    // ================================================================== 设备配置 / IME

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
        // 配置变更会触发 Activity 重建与重排，留出稳定时间再取语义树，
        // 否则紧跟其后的取节点会撞上「No compose hierarchies found」。
        Thread.sleep(1_500)
        compose.waitForIdle()
    }

    private fun restoreDeviceConfig() {
        runCatching { shell("wm density 480") }
        runCatching { shell("settings put system font_scale 1.0") }
    }

    private fun waitForIme() {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && !isImeShown()) Thread.sleep(200)
        compose.waitForIdle()
    }

    private fun isImeShown(): Boolean =
        shell("dumpsys input_method | grep mInputShown").contains("mInputShown=true")

    /** 同一窗口快照里的落位证据：浮层 frame、WMS 约束后的 display 与键盘触摸区顶边。 */
    private data class ImePlacement(
        val popupFrame: String,
        val popupDisplay: String,
        val popupBottom: Int,
        val imeTop: Int,
        val adjustMode: String,
    )

    /** 一次快照的解析结果；placement 为空时靠诊断字段定位是解析失配还是修复失效。 */
    private data class WindowScan(
        val placement: ImePlacement?,
        val popupCandidates: Int,
        val popupSummaries: String,
        val imeSeen: Boolean,
    )

    /**
     * 轮询整份 `dumpsys window windows` 快照（同一快照内取浮层与 IME 两个窗口，避免跨命令
     * 采样的时序错位），直到「浮层 adjust=resize 且底边不超过键盘顶边」或超时。返回最后一次
     * 扫描（含诊断），判定与断言全由调用方做——取证行写的必须是实际观测值而不是恒真值。
     */
    private fun scanPopoverPlacementUntilSettled(timeoutMs: Long = 8_000): WindowScan {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = WindowScan(null, 0, "无快照", imeSeen = false)
        while (System.currentTimeMillis() < deadline) {
            last = scanWindowSnapshot(shell("dumpsys window windows"))
            val p = last.placement
            if (p != null && p.adjustMode == "resize" && p.popupBottom <= p.imeTop) return last
            Thread.sleep(200)
        }
        return last
    }

    /**
     * 从一份 `dumpsys window windows` 快照解析：
     * - 目标浮层：标题为 `Pop-Up Window`、非 EXITING、frame 宽度≥300px（选择手柄实测 62px，
     *   被阈值排除）的窗口，取其 `Frames:` 行的 `frame=`/`display=` 与 mAttrs 的
     *   `sim={adjust=...}`；宽度用 frame 实宽（r-l），不用 mAttrs 的 (WxH)——后者是请求尺寸，
     *   跨版本可能变成 (wrapxwrap)。候选恰好 1 个才产生 placement，否则只留诊断。
     * - 键盘顶边：`InputMethod` 窗口的 `touchable region=SkRegion((l,t,r,b))` 的 t；
     *   键盘未展开时该区域为空，`imeSeen=false`。
     */
    private fun scanWindowSnapshot(dump: String): WindowScan {
        val blocks = Regex("(?ms)^  Window #\\d+ .*?(?=^  Window #|\\z)").findAll(dump).map { it.value }
        var popupFrame: String? = null
        var popupDisplay: String? = null
        var popupBottom: Int? = null
        var adjustMode: String? = null
        var candidates = 0
        val summaries = mutableListOf<String>()
        var imeTop: Int? = null
        for (block in blocks) {
            val title = Regex("^  Window #\\d+ Window\\{[0-9a-f]+ u0 ([^}]+?)\\}:")
                .find(block)?.groupValues?.get(1) ?: continue
            when {
                title == "InputMethod" -> {
                    val m = Regex("touchable region=SkRegion\\(\\((\\d+),(\\d+),(\\d+),(\\d+)\\)\\)")
                        .find(block) ?: continue
                    imeTop = m.groupValues[2].toInt()
                }
                title.startsWith("Pop-Up Window") && !title.contains("EXITING") -> {
                    val adjust = Regex("sim=\\{adjust=(\\w+)").find(block)?.groupValues?.get(1) ?: "unknown"
                    val framesLine = block.lines().firstOrNull { it.trimStart().startsWith("Frames:") }
                    val f = framesLine?.let {
                        Regex("frame=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(it)
                    }
                    val frameWidth = f?.let { it.groupValues[3].toInt() - it.groupValues[1].toInt() } ?: 0
                    if (f == null || frameWidth < 300) continue // 选择手柄等小窗（实测 62px）
                    candidates++
                    val size = Regex("\\((\\d+)x(\\d+)\\)").find(block)
                    summaries += "frame=${f.groupValues[1]},${f.groupValues[2]}..${f.groupValues[3]},${f.groupValues[4]} mAttrs=${size?.value ?: "?"} adjust=$adjust"
                    popupFrame = "[${f.groupValues[1]},${f.groupValues[2]}][${f.groupValues[3]},${f.groupValues[4]}]"
                    popupBottom = f.groupValues[4].toInt()
                    popupDisplay = Regex("display=\\[[^]]+]\\[[^]]+]").find(framesLine!!)?.value
                        ?.removePrefix("display=")
                    adjustMode = adjust
                }
            }
        }
        val bottom = popupBottom
        val top = imeTop
        val placement = if (candidates == 1 && popupFrame != null && bottom != null && top != null) {
            ImePlacement(popupFrame, popupDisplay ?: "?", bottom, top, adjustMode ?: "unknown")
        } else {
            null
        }
        return WindowScan(
            placement, candidates,
            summaries.joinToString("; ").ifEmpty { "无≥300px候选" },
            imeSeen = imeTop != null,
        )
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    // ================================================================== 证据落盘

    /**
     * 走 UiAutomator 截屏而不是 `onRoot().captureToImage()`：后者在有 Popup window
     * （取值浮层、键盘）时抓不到节点，而 F11 恰好要证明键盘起来之后的可点性。
     */
    private fun saveShot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, File(dir, name).outputStream())
        bitmap.recycle()
    }

    /** 组合内探针：把当前主题解析出的 surface 色写进语义，供「暗色是否真的生效」断言。 */
    @androidx.compose.runtime.Composable
    private fun themeProbe(): String =
        "surface=" + Integer.toHexString(LocalFineCatColors.current.surface.toArgb())

    private fun appendMeasureLine(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText(line + "\n")
    }

    private fun emitAssert(name: String, actual: String) =
        appendMeasureLine("CAPTURE-ASSERT: $name actual=$actual")

    // ================================================================== fixture 帧

    private fun modelsFrame(): String =
        """{"kind":"models","groups":[
             {"provider":"$WIRE","models":[
               {"id":"reasoner","name":"reasoner","contextWindow":131072,"maxTokens":8192,
                "reasoning":true,"input":["text"],
                "thinkingLevels":["minimal","low","medium","high"]}]},
             {"provider":"$EMPTY","models":[
               {"id":"reasoner2","name":"reasoner2","contextWindow":131072,"maxTokens":8192,
                "reasoning":true,"input":["text"],
                "thinkingLevels":["off","minimal","low","medium","high"]},
               {"id":"noreason","name":"noreason","contextWindow":131072,"maxTokens":8192,
                "reasoning":false,"input":["text"],"thinkingLevels":["off"]},
               {"id":"nodeclared","name":"nodeclared","contextWindow":131072,"maxTokens":8192,
                "reasoning":true,"input":["text"],"thinkingLevels":[]},
               {"id":"wide","name":"wide","contextWindow":131072,"maxTokens":8192,
                "reasoning":true,"input":["text"],
                "thinkingLevels":["off","minimal","low","medium","high","xhigh","max"]}]}
           ]}""".trimIndent()

    /**
     * [persisted]=true 是保存后的回读帧：thinking map 换成期望值，并把 `status` 换成带
     * `testedAt` 的那一份。判别位是 **`provider-saved` 回执不带 `status`**：于是
     * 「回执到达后 status 为 null、回读到达后才非 null」就证明状态确实由回读刷新。
     */
    private fun providersFrame(persisted: Boolean = false): String {
        val wireMap = if (persisted) {
            """"thinkingLevelMap":{"off":null,"minimal":null,"low":"low","medium":"high","high":"high"}"""
        } else {
            """"thinkingLevelMap":{"off":null,"low":"low","medium":"low","high":"high"}"""
        }
        val wireStatus = if (persisted) {
            """"status":{"ok":true,"testedAt":"2026-10-02T00:00:00Z"}"""
        } else {
            """"status":{"ok":true}"""
        }
        return """{"kind":"providers","providers":[
            {"id":"$WIRE","name":"F11 映射","baseUrl":"https://api.example.invalid/v1",
             "apiKeySet":true,"active":true,$wireStatus,
             "models":[{"id":"reasoner","name":"reasoner","api":"openai-completions",
               "reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192,
               $wireMap}]},
            {"id":"$EMPTY","name":"F11 空态","baseUrl":"https://api.example.invalid/v1",
             "apiKeySet":true,"active":true,"status":{"ok":true},
             "models":[
               {"id":"reasoner2","name":"reasoner2","api":"openai-completions","reasoning":true,
                "input":["text"],"contextWindow":131072,"maxTokens":8192},
               {"id":"noreason","name":"noreason","api":"openai-completions","reasoning":false,
                "input":["text"],"contextWindow":131072,"maxTokens":8192},
               {"id":"nodeclared","name":"nodeclared","api":"openai-completions","reasoning":true,
                "input":["text"],"contextWindow":131072,"maxTokens":8192},
               {"id":"uncataloged","name":"uncataloged","api":"openai-completions","reasoning":true,
                "input":["text"],"contextWindow":131072,"maxTokens":8192,
                "thinkingLevelMap":{"low":"low"}},
               {"id":"wide","name":"wide","api":"openai-completions","reasoning":true,
                "input":["text"],"contextWindow":131072,"maxTokens":8192,
                "thinkingLevelMap":{"xhigh":"xhigh","max":"max"}}]}
           ]}""".trimIndent()
    }

    /** 回执刻意不带 `status`：这样「状态由独立 providers 回读刷新」才是可判定的。 */
    private fun providerSavedFrame(): String =
        """{"kind":"provider-saved","admitted":true,"refreshed":true,
          "provider":{"id":"$WIRE","name":"F11 映射","baseUrl":"https://api.example.invalid/v1",
            "apiKeySet":true,"active":true,
            "models":[{"id":"reasoner","name":"reasoner","api":"openai-completions","reasoning":true,
              "input":["text"],"contextWindow":131072,"maxTokens":8192,
              "thinkingLevelMap":{"off":null,"minimal":null,"low":"low","medium":"high","high":"high"}}]}}"""
            .trimIndent()

    private companion object {
        const val WIRE = "f11-wire"
        const val EMPTY = "f11-empty"
        const val COL_PREFIX = "finecat.thinkingCol."
        const val THEME_PROBE = "finecat.themeProbe"

        /**
         * 期望的 surface 色由主题表本身推导：树里解析出来的必须等于「我请求的那一档」。
         * 写死色值会让正常的换肤改动也报红。
         */
        fun expectedSurface(dark: Boolean): String = Integer.toHexString(
            (if (dark) darkFineCatColors().surface else lightFineCatColors().surface).toArgb(),
        )

        /** 目录帧对 reasoner 真实声明的档位；列集合必须与之严格相等。 */
        val DECLARED = listOf("off", "minimal", "low", "medium", "high")

        /** 目录帧对 wide 真实声明的 7 档（xhigh/max 因显式映射而出现），用于测横向可达。 */
        val DECLARED_WIDE = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

        /** 批准 fixture 的 expected wire：显式 null 下发为 JSON null，没动过的档位键不丢。 */
        val EXPECTED_WIRE: JsonObject = buildJsonObject {
            put("off", JsonNull)
            put("minimal", JsonNull)
            put("low", JsonPrimitive("low"))
            put("medium", JsonPrimitive("high"))
            put("high", JsonPrimitive("high"))
        }
        val EXPECTED_WIRE_TEXT: Map<String, String?> = mapOf(
            "off" to null,
            "minimal" to null,
            "low" to "low",
            "medium" to "high",
            "high" to "high",
        )
    }
}
