package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ProviderModelDraft
import com.finecat.pimobile.shared.gateway.PI_THINKING_LEVELS
import com.finecat.pimobile.shared.protocol.GatewayModelCatalog
import com.finecat.pimobile.shared.protocol.GatewayModelGroup
import com.finecat.pimobile.shared.protocol.GatewayModelItem
import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.protocol.GatewayProviderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F11 思考强度映射纯逻辑（`DEC-F11-SCOPE`）。
 *
 * 目录能力仍来自真实帧；编辑键全集不是能力认证。缺目录时仍可编辑，
 * 未修改的映射继续省略；显式null与自定义值保留，不能自动覆盖宿主映射。
 */
class FineCatThinkingMapTest {

    private fun catalog(provider: String, model: String, levels: List<String>) = GatewayModelCatalog(
        groups = listOf(
            GatewayModelGroup(
                provider = provider,
                models = listOf(
                    GatewayModelItem(id = model, name = model, reasoning = true, thinkingLevels = levels),
                ),
            ),
        ),
    )

    // ---- 档位真源：目录帧 ----

    @Test
    fun declaredLevels_comesFromModelsCatalog_notFromProviderFrame() {
        val levels = FineCatThinkingMap.declaredLevels(
            catalog("relay", "m1", listOf("off", "minimal", "low", "medium", "high")),
            providerId = "relay",
            modelId = "m1",
        )
        assertEquals(listOf("off", "minimal", "low", "medium", "high"), levels)
    }

    @Test
    fun declaredLevels_modelAbsentFromCatalog_isNull_notAFallbackList() {
        // 目录未返回该模型，例如未认证或 models 帧没到：只能说「不知道」，
        // 编辑器虽提供标准键，目录查询仍不能冒充这个模型的真实声明。
        assertNull(FineCatThinkingMap.declaredLevels(null, "relay", "m1"))
        assertNull(FineCatThinkingMap.declaredLevels(catalog("other", "m1", listOf("off")), "relay", "m1"))
        assertNull(FineCatThinkingMap.declaredLevels(catalog("relay", "m2", listOf("off")), "relay", "m1"))
        assertNull(FineCatThinkingMap.declaredLevels(catalog("relay", "m1", listOf("off")), "relay", "  "))
    }

    // ---- 列装配与空态 ----

    @Test
    fun plan_nonReasoningModel_isNoReasoningEmptyState() {
        assertEquals(
            FineCatThinkingMap.Plan.NoReasoning,
            FineCatThinkingMap.plan(
                reasoning = false,
                declared = listOf("off"),
                draftMap = mapOf("off" to "off"),
                currentLevel = null,
            ),
        )
    }

    @Test
    fun plan_modelNotInCatalogProvidesEveryStandardKeyWithoutInventingOverrides() {
        val plan = FineCatThinkingMap.plan(true, null, emptyMap(), null) as FineCatThinkingMap.Plan.Columns
        assertEquals(PI_THINKING_LEVELS, plan.columns.map { it.level })
        assertTrue(plan.columns.all { it.isUnset })
        assertNull(FineCatThinkingMap.emptyHint(plan))
        assertEquals("默认", FineCatThinkingMap.displayTarget(plan.columns.first().target))
    }

    @Test
    fun plan_reasoningModelWithNoDeclaredLevelsRemainsEditable() {
        val plan = FineCatThinkingMap.plan(true, emptyList(), mapOf("high" to "vendor-high"), null)
            as FineCatThinkingMap.Plan.Columns
        assertEquals(PI_THINKING_LEVELS, plan.columns.map { it.level })
        assertEquals("vendor-high", plan.columns.single { it.level == "high" }.target)
        assertTrue(plan.columns.filter { it.level != "high" }.all { it.isUnset })
    }

    @Test
    fun plan_providesAllStandardKeysInPiOrder() {
        val plan = FineCatThinkingMap.plan(
            reasoning = true,
            declared = listOf("off", "minimal", "low", "medium", "high"),
            draftMap = emptyMap(),
            currentLevel = null,
        )
        val columns = (plan as FineCatThinkingMap.Plan.Columns).columns
        assertEquals(PI_THINKING_LEVELS, columns.map { it.level })
        // 没配过的档位是「不下发」，不是 null。
        assertTrue(columns.all { it.isUnset })
        assertFalse(columns.any { it.isUnsupported })
    }

    @Test
    fun plan_currentLevelHighlightsExactlyOneColumn_andOnlyForTheSessionsOwnModel() {
        val declared = listOf("off", "low", "medium", "high")
        val current = FineCatThinkingMap.currentLevelFor(
            sessionProviderId = "relay",
            sessionModelId = "m1",
            sessionThinkingLevel = "medium",
            editingProviderId = "relay",
            editingModelId = "m1",
        )
        val columns = (
            FineCatThinkingMap.plan(reasoning = true, declared = declared, draftMap = emptyMap(), currentLevel = current)
                as FineCatThinkingMap.Plan.Columns
            ).columns
        assertEquals(listOf("medium"), columns.filter { it.isCurrent }.map { it.level })
    }

    @Test
    fun currentLevelFor_otherModelBeingEdited_hasNoCurrentColumn() {
        // 表单里编辑的通常不是会话当前模型：那时不存在「这一档亮着」的事实，不能拿别的模型来亮。
        assertNull(
            FineCatThinkingMap.currentLevelFor(
                sessionProviderId = "relay",
                sessionModelId = "m1",
                sessionThinkingLevel = "medium",
                editingProviderId = "relay",
                editingModelId = "m2",
            ),
        )
        assertNull(
            FineCatThinkingMap.currentLevelFor(
                sessionProviderId = null,
                sessionModelId = null,
                sessionThinkingLevel = null,
                editingProviderId = "relay",
                editingModelId = "m1",
            ),
        )
    }

    // ---- 显式 null 的档位不在目录清单里（最容易漏的契约事实） ----

    @Test
    fun plan_keepsExplicitNullColumnEvenThoughPiFiltersItOutOfThinkingLevels() {
        // pi getSupportedThinkingLevels 会把值为 null 的档过滤出 thinkingLevels，
        // 所以刚置 null 的那一档不在 declared 里；但它在 map 里，是真实的 override，
        // 不并进列的话保存会把它静默删掉。
        val plan = FineCatThinkingMap.plan(
            reasoning = true,
            declared = listOf("minimal", "low", "medium", "high"),
            draftMap = mapOf("off" to FineCatThinkingMap.UNSUPPORTED),
            currentLevel = null,
        )
        val columns = (plan as FineCatThinkingMap.Plan.Columns).columns
        assertEquals(PI_THINKING_LEVELS, columns.map { it.level })
        val off = columns.single { it.level == "off" }
        assertTrue(off.isUnsupported)
        assertEquals("null", FineCatThinkingMap.displayTarget(off.target))
    }

    @Test
    fun orderedLevels_keepsPiOrder_forBothDeclaredAndExistingKeys() {
        assertEquals(
            listOf("off", "low", "medium", "high", "xhigh", "max"),
            FineCatThinkingMap.orderedLevels(
                declared = listOf("high", "off", "xhigh"),
                existingKeys = setOf("max", "medium", "low"),
            ),
        )
    }

    // ---- 编辑动作：改值 / 显式 null / 清空被拒 ----

    @Test
    fun applyTarget_setsValueAndTrimsIt() {
        val next = FineCatThinkingMap.applyTarget(emptyMap(), "medium", "  high  ")
        assertEquals(mapOf("medium" to "high"), next)
    }

    @Test
    fun applyTarget_explicitNullChoice_writesTheSentinel() {
        val next = FineCatThinkingMap.applyUnsupported(emptyMap(), "off")
        assertEquals(mapOf("off" to FineCatThinkingMap.UNSUPPORTED), next)
        assertTrue((FineCatThinkingMap.plan(true, listOf("off"), next, null) as FineCatThinkingMap.Plan.Columns)
            .columns.single { it.level == "off" }.isUnsupported)
    }

    @Test
    fun applyTarget_blankOnAnAlreadySetLevel_isRejected_notSilentlyDeleted() {
        // 自定义空值不是删除操作；最后一项清空在当前协议中也无法表达。
        val configured = mapOf("medium" to "high", "off" to FineCatThinkingMap.UNSUPPORTED)
        assertNull(FineCatThinkingMap.applyTarget(configured, "medium", ""))
        assertNull(FineCatThinkingMap.applyTarget(configured, "medium", "   "))
        assertNull(FineCatThinkingMap.applyTarget(configured, "off", ""))
    }

    @Test
    fun applyTarget_blankOnAKeyThatHoldsABlankValue_isTreatedAsUnset_notAsConfigured() {
        // 电脑上手改 models.json 写成 "low": "" 时：列上显示「—」（未设置，因为序列化会滤掉空白），
        // 若这里按「键存在=已配置」去拒绝，提示就会与所见自相矛盾。两套 UI 的保存路径都产不出
        // 空白值，所以只能从 wire 帧进来——但它一旦进来就必须自洽。
        val blankHeld = mapOf("low" to "", "high" to "high")
        assertEquals(
            "空白值等同于未设置：清空提交应是无操作而不是被拒",
            blankHeld,
            FineCatThinkingMap.applyTarget(blankHeld, "low", ""),
        )
        // 显式 null 是真的已配置（会被下发成 JSON null），清空它仍必须被拒。
        assertNull(FineCatThinkingMap.applyTarget(blankHeld + ("off" to FineCatThinkingMap.UNSUPPORTED), "off", ""))
    }

    @Test
    fun applyTarget_rejectsTheUnsetDisplayGlyph_soItIsNotSentAsARealValue() {
        // 「—」只是「未设置」的显示写法；用户照着敲进输入框时若原样下发，就与所见矛盾。
        // 未设置的一档：与空白一样是无操作，绝不凭空造出一个值为「—」的 override。
        assertEquals(
            emptyMap<String, String>(),
            FineCatThinkingMap.applyTarget(emptyMap(), "low", FineCatThinkingMap.UNSET_GLYPH),
        )
        // 已配置的一档：拒绝，并把原因留给卡内提示。
        assertNull(
            FineCatThinkingMap.applyTarget(mapOf("low" to "high"), "low", FineCatThinkingMap.UNSET_GLYPH),
        )
    }

    @Test
    fun orderedLevels_keepsDeclaredLevelsOutsideThePiConstant() {
        // 目录帧口径若与本仓 PI_THINKING_LEVELS 不同步（pi 将来加档），它仍是真实声明，
        // 不能因为不在常量里就静默消失。
        assertEquals(
            listOf("off", "low", "high", "ultra"),
            FineCatThinkingMap.orderedLevels(
                declared = listOf("off", "ultra", "high"),
                existingKeys = setOf("low"),
            ),
        )
    }

    @Test
    fun applyTarget_blankOnAnUnsetLevel_isANoOp_notADeletion() {
        // 本来就没设置 → 没有键可删，提交空值不该报错也不该改出东西。
        val empty = emptyMap<String, String>()
        assertEquals(empty, FineCatThinkingMap.applyTarget(empty, "medium", ""))
    }

    @Test
    fun displayTarget_distinguishesUnsetFromExplicitNullFromValue() {
        assertEquals(FineCatThinkingMap.UNSET_GLYPH, FineCatThinkingMap.displayTarget(FineCatThinkingMap.UNSET))
        assertEquals("null", FineCatThinkingMap.displayTarget(FineCatThinkingMap.UNSUPPORTED))
        assertEquals("high", FineCatThinkingMap.displayTarget("high"))
    }

    // ---- 端到端：providers 帧 → 表单草稿 → provider-put wire 三态 ----

    @Test
    fun endToEnd_changeValueAndExplicitNull_reachWireAsStringAndJsonNull_othersStayOmitted() {
        val existing = GatewayProvider(
            id = "relay",
            baseUrl = "https://relay.example/v1",
            apiKeySet = true,
            models = listOf(
                GatewayProviderModel(
                    id = "m1",
                    api = "openai-completions",
                    reasoning = true,
                    thinkingLevelMap = mapOf("off" to null, "low" to "low", "medium" to "low", "high" to "high"),
                ),
            ),
        )
        // providers 帧解码：null → 草稿哨兵，其余原样
        var draft = FineCatProviderForm.initialRows(existing).single()
        assertEquals(FineCatThinkingMap.UNSUPPORTED, draft.thinkingMap["off"])

        // 用户改 medium，并新置 xhigh 为显式 null
        draft = draft.copy(
            thinkingMap = FineCatThinkingMap.applyTarget(draft.thinkingMap, "medium", "high")!!,
        )
        draft = draft.copy(
            thinkingMap = FineCatThinkingMap.applyUnsupported(draft.thinkingMap, "xhigh"),
        )

        val parsed = FineCatProviderForm.parseModels(listOf(draft), existing)
        assertNull(parsed.error)
        val wire = parsed.rows.single().thinkingLevelMap
        assertEquals("high", wire["medium"])
        assertEquals(null, wire["xhigh"])
        assertTrue("显式 null 必须真的下发成 null 条目", wire.containsKey("xhigh"))
        // 没动过的档位保持原值，不被清掉。
        assertEquals("low", wire["low"])
        assertEquals("high", wire["high"])
        assertTrue(wire.containsKey("off"))
        assertNull("没动过的档位不该凭空多出 override", wire["minimal"])
    }

    @Test
    fun endToEnd_untouchedModel_keepsItsThinkingMapByteIdentical() {
        val map = mapOf("off" to null, "low" to "low", "medium" to "high")
        val existing = GatewayProvider(
            id = "relay",
            baseUrl = "https://relay.example/v1",
            apiKeySet = true,
            models = listOf(GatewayProviderModel(id = "m1", api = "openai-completions", thinkingLevelMap = map)),
        )
        val draft = FineCatProviderForm.initialRows(existing).single()
        val parsed = FineCatProviderForm.parseModels(listOf(draft), existing)
        assertNull(parsed.error)
        assertEquals(map, parsed.rows.single().thinkingLevelMap)
    }

    @Test
    fun endToEnd_emptyDraftMap_meansNoOverrides_notAClearOperation() {
        // 空 map 序列化成「字段省略」，网关按缺省保留原值——所以它不是清除操作。
        // DEC-F11-SCOPE 明确把「空 map 作为清除」移出 F11，这里钉住这条语义不被误用。
        val existing = GatewayProvider(
            id = "relay",
            baseUrl = "https://relay.example/v1",
            apiKeySet = true,
            models = listOf(
                GatewayProviderModel(
                    id = "m1",
                    api = "openai-completions",
                    thinkingLevelMap = mapOf("low" to "low"),
                ),
            ),
        )
        val draft = FineCatProviderForm.initialRows(existing).single().copy(thinkingMap = emptyMap())
        val parsed = FineCatProviderForm.parseModels(listOf(draft), existing)
        assertNull(parsed.error)
        assertTrue("空 map 必须真的空，不能被回填成已存值", parsed.rows.single().thinkingLevelMap.isEmpty())
    }
    @Test
    fun rekeyPreservesNullAndEveryUntouchedMappingInWire() {
        val original = mapOf("off" to "-", "low" to "vendor-low", "high" to "vendor-high")
        val next = FineCatThinkingMap.renameLevel(original, listOf("off", "low", "high"), "low", "xhigh")!!
        assertEquals(mapOf("off" to "-", "xhigh" to "vendor-low", "high" to "vendor-high"), next)
        val parsed = FineCatProviderForm.parseModels(listOf(ProviderModelDraft(
            id = "m1", contextWindow = "500K", reasoning = true, thinkingMap = next)), null)
        assertNull(parsed.error)
        assertEquals(mapOf("off" to null, "xhigh" to "vendor-low", "high" to "vendor-high"), parsed.rows.single().thinkingLevelMap)
    }

    @Test
    fun rekeyRejectsDuplicatesAndUnknownKeys() {
        val map = mapOf("low" to "low", "high" to "high")
        assertNull(FineCatThinkingMap.renameLevel(map, listOf("low", "high"), "low", "high"))
        assertNull(FineCatThinkingMap.renameLevel(map, listOf("low", "high"), "low", "typo"))
        assertEquals(map, FineCatThinkingMap.renameLevel(map, listOf("low", "high"), "low", "low"))
    }

    @Test
    fun changedDisplayKeyDoesNotComeBackFromTheCatalogDuringEditing() {
        val plan = FineCatThinkingMap.plan(true, listOf("low", "high"), mapOf("xhigh" to "high"), null,
            displayLevels = listOf("xhigh", "high")) as FineCatThinkingMap.Plan.Columns
        assertEquals(listOf("xhigh", "high"), plan.columns.map { it.level })
        assertEquals("high", plan.columns.first().target)
    }

    @Test
    fun valuePickerKeepsCustomStringsAndHidesDraftSentinels() {
        val choices = FineCatThinkingMap.targetChoices(mapOf("off" to "-", "low" to "custom-effort", "high" to ""))
        assertTrue("custom-effort" in choices)
        assertTrue("low" in choices)
        assertFalse("-" in choices)
        assertFalse("" in choices)
        assertEquals(choices.distinct(), choices)
    }

    @Test
    fun uncatalogedModelCanEditMappingsAndSaveWithoutDesktopSetup() {
        var row = ProviderModelDraft(id = "new-uncataloged", reasoning = true)
        val original = FineCatThinkingMap.plan(true, null, row.thinkingMap, null) as FineCatThinkingMap.Plan.Columns
        assertEquals(PI_THINKING_LEVELS, original.columns.map { it.level })
        assertTrue(row.thinkingMap.isEmpty())
        row = row.copy(thinkingMap = FineCatThinkingMap.applyTarget(row.thinkingMap, "high", "vendor-effort")!!)
        row = row.copy(thinkingMap = FineCatThinkingMap.applyUnsupported(row.thinkingMap, "off"))
        val parsed = FineCatProviderForm.parseModels(listOf(row), null)
        assertNull(parsed.error)
        assertEquals(128000L, parsed.rows.single().contextWindow)
        assertEquals(16384L, parsed.rows.single().maxTokens)
        assertEquals(mapOf("high" to "vendor-effort", "off" to null), parsed.rows.single().thinkingLevelMap)
    }

    @Test
    fun rekeyCanMoveToVisibleDefaultColumnWithoutDroppingAnyConfiguredMapping() {
        val original = mapOf("off" to "-", "low" to "vendor-low", "high" to "vendor-high")
        val displayed = FineCatThinkingMap.editableLevels(null, original.keys)
        assertTrue("xhigh" in displayed)
        assertTrue("xhigh" in FineCatThinkingMap.keyChoices(original, "low"))
        val changed = FineCatThinkingMap.renameLevel(original, displayed, "low", "xhigh")!!
        assertEquals(mapOf("off" to "-", "xhigh" to "vendor-low", "high" to "vendor-high"), changed)
        val columns = FineCatThinkingMap.renamedColumns(displayed, "low", "xhigh")
        assertEquals(displayed.toSet(), columns.toSet())
        assertEquals(columns.size, columns.distinct().size)
        val plan = FineCatThinkingMap.plan(true, null, changed, null, columns) as FineCatThinkingMap.Plan.Columns
        assertEquals("vendor-low", plan.columns.single { it.level == "xhigh" }.target)
        assertTrue(plan.columns.single { it.level == "low" }.isUnset)
        assertTrue(plan.columns.single { it.level == "off" }.isUnsupported)
    }

    @Test
    fun keyPickerCannotOverwriteCustomValueOrExplicitNull() {
        val original = mapOf("off" to "-", "low" to "vendor-low", "high" to "vendor-high")
        val choices = FineCatThinkingMap.keyChoices(original, "low")
        assertTrue("low" in choices)
        assertFalse("off" in choices)
        assertFalse("high" in choices)
        assertNull(FineCatThinkingMap.renameLevel(original, PI_THINKING_LEVELS, "low", "off"))
        assertNull(FineCatThinkingMap.renameLevel(original, PI_THINKING_LEVELS, "low", "high"))
    }

    @Test
    fun extendedCatalogKeyCanBeReplacedWithAnUnconfiguredStandardKey() {
        val original = mapOf("ultra" to "vendor-ultra", "high" to "vendor-high")
        val displayed = FineCatThinkingMap.editableLevels(listOf("ultra"), original.keys)
        assertTrue(FineCatThinkingMap.keyChoices(original, "ultra").isNotEmpty())
        val changed = FineCatThinkingMap.renameLevel(original, displayed, "ultra", "max")!!
        assertEquals(mapOf("max" to "vendor-ultra", "high" to "vendor-high"), changed)
        assertEquals(displayed.size, FineCatThinkingMap.renamedColumns(displayed, "ultra", "max").distinct().size)
    }

    @Test
    fun keyPickerKeepsCurrentExtendedKeyWhenEveryStandardTargetIsOccupied() {
        val original = PI_THINKING_LEVELS.associateWith { "vendor-$it" } + ("ultra" to "vendor-ultra")
        assertEquals(listOf("ultra"), FineCatThinkingMap.keyChoices(original, "ultra"))
        assertEquals(original, FineCatThinkingMap.renameLevel(original, original.keys.toList(), "ultra", "ultra"))
    }
}
