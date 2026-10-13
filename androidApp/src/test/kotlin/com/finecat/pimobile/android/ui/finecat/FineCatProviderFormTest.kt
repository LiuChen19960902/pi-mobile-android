package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ProviderModelDraft
import com.finecat.pimobile.shared.gateway.PROVIDER_DEFAULT_API
import com.finecat.pimobile.shared.gateway.ProviderModelUpsert
import com.finecat.pimobile.shared.gateway.requireValidProviderUpsert
import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.protocol.GatewayProviderModel
import com.finecat.pimobile.shared.protocol.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10a/P10b Provider 表单纯逻辑（FineCat sheet 的真身，JVM 可测）：
 * 新增模型的完整 upsert（id + 模型级 api）、上游拉取只追加不覆盖、测试指纹的
 * 失效/绑定语义、K/M 数值往返、已有模型 thinkingLevelMap/compat 透传、非法输入
 * 必须报错而不是静默丢行。
 */
class FineCatProviderFormTest {
    @Test
    fun saveDirtyStateIncludesMetadataWithoutRequiringAnotherConnectionTest() {
        val existing = provider(models = listOf(GatewayProviderModel("m", contextWindow = 200000, maxTokens = 8192)))
        val rows = FineCatProviderForm.initialRows(existing)
        val originalTest = FineCatProviderForm.fingerprint(existing.baseUrl.orEmpty(), "", rows)
        fun changed(draft: List<ProviderModelDraft>, key: String = "") = FineCatProviderForm.hasChanges(
            existing, existing.id, existing.name.orEmpty(), existing.baseUrl.orEmpty(), key, draft,
        )
        assertFalse(changed(rows))
        listOf(
            rows.single().copy(name = "new display name"),
            rows.single().copy(contextWindow = "275K"),
            rows.single().copy(maxTokens = "32K"),
            rows.single().copy(reasoning = !rows.single().reasoning),
            rows.single().copy(imageOn = !rows.single().imageOn),
            rows.single().copy(thinkingMap = mapOf("high" to "max")),
            rows.single().copy(compat = mapOf("supportsStore" to "false"), compatTouched = true),
        ).forEach { draft ->
            assertTrue(changed(listOf(draft)))
            assertEquals(originalTest, FineCatProviderForm.fingerprint(existing.baseUrl.orEmpty(), "", listOf(draft)))
        }
        assertTrue(changed(rows, "synthetic-replacement"))
        assertFalse(changed(rows, "   "))
        assertTrue(changed(emptyList()))
    }

    @Test
    fun providerFieldsAndOnlyViewReorderHaveSeparateDirtySemantics() {
        val existing = provider(models = listOf(GatewayProviderModel("a"), GatewayProviderModel("b")))
        val rows = FineCatProviderForm.initialRows(existing)
        FineCatProviderForm.moveViewUp(rows, emptyList(), 1)
        assertFalse(FineCatProviderForm.hasChanges(existing, existing.id, existing.name.orEmpty(), existing.baseUrl.orEmpty(), "", rows))
        assertTrue(FineCatProviderForm.hasChanges(existing, existing.id, "renamed", existing.baseUrl.orEmpty(), "", rows))
        assertTrue(FineCatProviderForm.hasChanges(existing, existing.id, existing.name.orEmpty(), "https://other.example", "", rows))
        assertFalse(FineCatProviderForm.hasChanges(null, "", "", "", "", FineCatProviderForm.initialRows(null)))
        assertTrue(FineCatProviderForm.hasChanges(null, "new", "", "", "", FineCatProviderForm.initialRows(null)))
    }

    @Test
    fun rowTestStatusNeverBorrowsBatchSuccessOrAnotherConfiguration() {
        val attempts = listOf(
            com.finecat.pimobile.android.ProviderTestAttempt("a", false, "payment", 402),
            com.finecat.pimobile.android.ProviderTestAttempt("b", true, "ok", 200),
        )
        assertEquals(402, FineCatProviderForm.currentTestAttempt("a", "current", "current", attempts)?.httpStatus)
        assertEquals(true, FineCatProviderForm.currentTestAttempt("b", "current", "current", attempts)?.ok)
        assertNull(FineCatProviderForm.currentTestAttempt("c", "current", "current", attempts))
        assertNull(FineCatProviderForm.currentTestAttempt("b", "edited", "current", attempts))
        assertNull(FineCatProviderForm.currentTestAttempt("b", "current", null, attempts))
    }

    @Test
    fun modelUrlDraftAndEchoKeepPreserveOverrideAndInheritanceDistinct() {
        val existing = provider(models = listOf(GatewayProviderModel(
            "m", api = "anthropic-messages", reasoning = true, input = listOf("text"),
            baseUrl = "https://relay.example/custom",
        )))
        val initial = FineCatProviderForm.initialRows(existing)
        assertEquals("https://relay.example/custom", initial.single().baseUrl)
        assertFalse(FineCatProviderForm.parseModels(initial, existing).rows.single().baseUrlEdited)
        val overridden = initial.map { it.copy(baseUrl = "https://other.example/v1", baseUrlTouched = true) }
        assertNotEquals(FineCatProviderForm.fingerprint("https://root.example", "", initial), FineCatProviderForm.fingerprint("https://root.example", "", overridden))
        val rows = FineCatProviderForm.parseModels(overridden, existing).rows
        assertTrue(rows.single().baseUrlEdited)
        val submitted = FineCatProviderForm.submitFingerprint(existing.id, existing.name, existing.baseUrl, rows)
        assertFalse(FineCatProviderForm.echoMatchesSubmit(existing, submitted))
        assertTrue(FineCatProviderForm.echoMatchesSubmit(existing.copy(models = listOf(existing.models.single().copy(baseUrl = "https://other.example/v1"))), submitted))
        val inherited = FineCatProviderForm.parseModels(initial.map { it.copy(baseUrl = "", baseUrlTouched = true) }, existing).rows
        assertTrue(inherited.single().baseUrlEdited)
        assertNull(inherited.single().baseUrl)
        assertTrue(FineCatProviderForm.parseModels(listOf(ProviderModelDraft(baseUrl = "https://only-url.example")), existing).error != null)
    }

    @Test
    fun testModelAddressesMatchSavedOverrideAndBlankInheritance() {
        val existing = provider(models = listOf(GatewayProviderModel("override"), GatewayProviderModel("inherit")))
        val drafts = listOf(
            ProviderModelDraft(id = "override", baseUrl = " https://relay.example/proxy/v1 ", baseUrlTouched = true),
            ProviderModelDraft(id = "inherit", baseUrl = "   ", baseUrlTouched = true),
        )
        val targets = FineCatProviderForm.testModels(drafts)
        val saved = FineCatProviderForm.parseModels(drafts, existing)
        assertNull(saved.error)
        assertEquals("https://relay.example/proxy/v1", targets[0].baseUrl)
        assertEquals("", targets[1].baseUrl)
        assertEquals(saved.rows.map { it.baseUrl }, targets.map { it.baseUrl.takeIf(String::isNotEmpty) })
    }

    @Test
    fun scopeCheckboxesRetainPriorThinkingAndDistinguishSameIdAcrossProviders() {
        val original = listOf(com.finecat.pimobile.shared.protocol.GatewayModelSelection("p", "m", "high"))
        val removed = toggleScopeModel(original, original, "p", "m")
        assertTrue(removed.isEmpty())
        assertEquals(original, toggleScopeModel(removed, original, "p", "m"))
        val added = toggleScopeModel(original, original, "other", "m")
        assertEquals(listOf("p", "other"), added.map { it.provider })
        assertNull(added.last().thinkingLevel)
    }

    @Test
    fun viewReorderChangesTestSequenceButNotSubmitOrFingerprint() {
        val existing = provider(models = listOf(GatewayProviderModel("first"), GatewayProviderModel("second")))
        val rows = FineCatProviderForm.initialRows(existing)
        val originalWire = FineCatProviderForm.parseModels(rows, existing)
        val originalFingerprint = FineCatProviderForm.fingerprint("https://example.test", "", rows)
        val order = FineCatProviderForm.moveViewUp(rows, emptyList(), 1)
        assertEquals(listOf("second", "first"), order)
        val displayed = FineCatProviderForm.viewIndices(rows, order).map { rows[it] }
        assertEquals(listOf("second", "first"), FineCatProviderForm.testModels(displayed).map { it.id })
        assertEquals(originalWire, FineCatProviderForm.parseModels(rows, existing))
        assertEquals(originalFingerprint, FineCatProviderForm.fingerprint("https://example.test", "", rows))
    }

    @Test
    fun savedViewOrderIgnoresRemovedIdsAndAppendsNewRows() {
        val rows = listOf(ProviderModelDraft(id = "a"), ProviderModelDraft(id = "b"), ProviderModelDraft(id = "new"))
        val order = listOf("gone", "b", "a")
        assertEquals(listOf(1, 0, 2), FineCatProviderForm.viewIndices(rows, order))
        assertEquals(order, FineCatProviderForm.moveViewUp(rows, order, 1))
        val withBlank = rows + ProviderModelDraft()
        assertEquals(order, FineCatProviderForm.moveViewUp(withBlank, order, 3))
    }

    private fun provider(
        id: String = "relay",
        name: String? = "中转站",
        api: String? = "anthropic-messages",
        models: List<GatewayProviderModel> = emptyList(),
    ) = GatewayProvider(
        id = id,
        name = name,
        baseUrl = "https://relay.example/v1",
        apiKeySet = true,
        api = api,
        models = models,
    )

    // ---- 初始回填与数值往返 ----

    /** 已有模型按 provider 级 api 回退到模型级：模型没写协议才继承 provider 的。 */
    @Test
    fun initialRowsInheritProtocolPerModel() {
        val rows = FineCatProviderForm.initialRows(
            provider(
                api = "anthropic-messages",
                models = listOf(
                    GatewayProviderModel(id = "claude", api = null),
                    GatewayProviderModel(id = "gpt", api = "openai-completions"),
                ),
            ),
        )
        assertEquals("anthropic-messages", rows[0].api)
        assertEquals("openai-completions", rows[1].api)
    }

    /**
     * 10M 往返（本轮 blocker）：旧二进制contextWindow=10*1024*1024 回填原始整数，
     * 再解析必须回到同一个数，否则"能看见却不能保存"。
     */
    @Test
    fun tenMegabytesRoundTripsThroughForm() {
        val value = 10L * 1024 * 1024
        val existing = provider(
            models = listOf(GatewayProviderModel(id = "long-ctx", contextWindow = value, maxTokens = 131072L)),
        )
        val rows = FineCatProviderForm.initialRows(existing)
        assertEquals("10485760", rows.single().contextWindow)
        assertEquals("131072", rows.single().maxTokens)
        val parsed = FineCatProviderForm.parseModels(rows, existing)
        assertNull(parsed.error)
        assertEquals(value, parsed.rows.single().contextWindow)
        assertEquals(131072L, parsed.rows.single().maxTokens)
    }

    // ---- 新增模型：id + 模型级协议必须齐全 ----

    /** 手动添加的新行：id 可填、协议取默认，保存时不能是空 api（网关按 model.api 分派）。 */
    @Test
    fun newModelCarriesIdAndProtocol() {
        val rows = FineCatProviderForm.initialRows(null)
        val added = FineCatProviderForm.newRow(rows)
        assertEquals(PROVIDER_DEFAULT_API, added.api)
        assertTrue(added.id.isEmpty())

        val filled = added.copy(id = "gpt-4o-mini", contextWindow = "128K")
        val parsed = FineCatProviderForm.parseModels(listOf(filled), null)
        assertNull(parsed.error)
        val upsert = parsed.rows.single()
        assertEquals("gpt-4o-mini", upsert.id)
        assertEquals(PROVIDER_DEFAULT_API, upsert.api)
        assertEquals(128000L, upsert.contextWindow)
        // 与网关同口径：整份表单本地校验通过
        requireValidProviderUpsert(
            id = "new-provider",
            baseUrl = "https://relay.example/v1",
            apiKey = null,
            models = parsed.rows,
            name = "新建入口",
        )
    }

    /** 已有模型重新保存时继续带上模型级协议（不被 provider 级覆盖成空）。 */
    @Test
    fun existingModelKeepsItsOwnProtocolOnResave() {
        val existing = provider(models = listOf(GatewayProviderModel(id = "m1", api = "google-generative-ai")))
        val rows = FineCatProviderForm.initialRows(existing)
        val parsed = FineCatProviderForm.parseModels(rows, existing)
        assertNull(parsed.error)
        assertEquals("google-generative-ai", parsed.rows.single().api)
    }

    // ---- 新增/拉回来的模型：没配上下文窗口不能进实际 models ----

    /**
     * 手动新增或从上游拉回来、existing 里没有同 id 的行：只挂「待配置」不够，
     * 必须行内报错拦住保存（否则一个 contextWindow 空白的模型会被落盘给 pi）。
     */
    @Test
    fun newOrPulledModelWithoutContextWindowBlocksSave() {
        val existing = provider(models = listOf(GatewayProviderModel(id = "m1", contextWindow = 131072L)))
        val rows = listOf(
            ProviderModelDraft(id = "m1", api = PROVIDER_DEFAULT_API, contextWindow = "128K"),
            ProviderModelDraft(id = "pulled-raw", api = PROVIDER_DEFAULT_API, contextWindow = ""),
        )
        val parsed = FineCatProviderForm.parseModels(rows, existing)
        assertTrue("拉回来的行必须报错：${parsed.error}", parsed.error?.contains("pulled-raw") == true)
        assertTrue("报错时不能交出可提交行", parsed.rows.isEmpty())
        // 行内标记和拦截用同一个判据，不会出现标了红却还能存
        assertTrue(FineCatProviderForm.unconfigured(existing, rows[1]))
        assertTrue(!FineCatProviderForm.unconfigured(existing, rows[0]))
    }

    /** 已有模型缺上下文窗口是 pi 走目录默认：允许原样保留，不拿新行的规矩去卡旧数据。 */
    @Test
    fun existingModelWithoutContextWindowIsKeptAsIs() {
        val existing = provider(models = listOf(GatewayProviderModel(id = "m1", api = PROVIDER_DEFAULT_API)))
        val rows = FineCatProviderForm.initialRows(existing)
        assertTrue(rows.single().contextWindow.isBlank())
        val parsed = FineCatProviderForm.parseModels(rows, existing)
        assertNull(parsed.error)
        assertNull(parsed.rows.single().contextWindow)
        assertTrue(!FineCatProviderForm.unconfigured(existing, rows.single()))
    }

    // ---- 非法输入：可见 + 不产出可提交行 ----

    @Test
    fun invalidNumberIsReportedAndBlocksSubmit() {
        listOf("abc", "1.2.3", "0", "-5K", "106M").forEach { raw ->
            val parsed = FineCatProviderForm.parseModels(
                listOf(ProviderModelDraft(id = "m1", api = PROVIDER_DEFAULT_API, contextWindow = raw)),
                null,
            )
            assertTrue("$raw 应报错", parsed.error != null)
            assertTrue("报错时不能给出可提交行", parsed.rows.isEmpty())
        }
    }

    /** 只填了别的字段却没 id：必须报"模型 ID 不能为空"，不能静默丢行。 */
    @Test
    fun missingModelIdIsVisibleError() {
        val parsed = FineCatProviderForm.parseModels(
            listOf(ProviderModelDraft(id = "  ", api = PROVIDER_DEFAULT_API, contextWindow = "128K")),
            null,
        )
        assertTrue(parsed.error!!.contains("模型 ID"))
    }

    /** 完全没填的草稿行按原型"收起时丢掉空行"处理，不算错误也不下发空 id。 */
    @Test
    fun untouchedBlankRowIsDroppedWithoutError() {
        val parsed = FineCatProviderForm.parseModels(
            listOf(ProviderModelDraft(id = "", api = PROVIDER_DEFAULT_API)),
            null,
        )
        assertNull(parsed.error)
        assertTrue(parsed.rows.isEmpty())
    }

    // ---- 上游拉取：只追加、不覆盖 ----

    @Test
    fun pullOnlyAppendsUnknownIds() {
        val edited = ProviderModelDraft(
            id = "claude-sonnet",
            name = "我改过的名字",
            api = "anthropic-messages",
            contextWindow = "200K",
            compat = mapOf("supportsStore" to "false"),
        )
        val result = FineCatProviderForm.appendPulled(
            rows = listOf(edited),
            ids = listOf("claude-sonnet", "claude-opus", "claude-haiku", "claude-opus"),
        )
        // 已存在的一条都不动（含用户改好的 compat）
        assertEquals(edited, result.rows.first())
        assertEquals(2, result.added)
        assertEquals(2, result.skipped)   // claude-sonnet 已存在 + 上游重复项
        assertEquals(listOf("claude-sonnet", "claude-opus", "claude-haiku"), result.rows.map { it.id })
        // 新行继承当前表单协议（同 provider 通常同一方言）
        assertEquals("anthropic-messages", result.rows[1].api)
    }

    /** 空表拉取：给新行兜底默认协议，不产出 disabled 空框。 */
    @Test
    fun pullIntoEmptyFormFallsBackToDefaultProtocol() {
        val result = FineCatProviderForm.appendPulled(rows = emptyList(), ids = listOf("m1"))
        assertEquals(1, result.added)
        assertEquals(0, result.skipped)
        assertEquals(PROVIDER_DEFAULT_API, result.rows.single().api)
        // 拉回来的模型没配上下文 → 表单行标记为「待配置」的判据
        assertTrue(result.rows.single().contextWindow.isBlank())
    }

    @Test
    fun pullApiFallsBackWhenFormIsEmpty() {
        assertEquals(PROVIDER_DEFAULT_API, FineCatProviderForm.pullApi(emptyList()))
        assertEquals(
            "openai-responses",
            FineCatProviderForm.pullApi(listOf(ProviderModelDraft(api = "openai-responses"))),
        )
    }

    @Test
    fun testModelsRetainsOrderAndProtocolButDropsBlankAndDuplicateRows() {
        val rows = listOf(
            ProviderModelDraft(id = " "),
            ProviderModelDraft(id = " claude ", api = "anthropic-messages"),
            ProviderModelDraft(id = "other", api = "openai-responses"),
            ProviderModelDraft(id = "claude", api = "anthropic-messages"),
        )
        val targets = FineCatProviderForm.testModels(rows)
        assertEquals(listOf("claude", "other"), targets.map { it.id })
        assertEquals(listOf("anthropic-messages", "openai-responses"), targets.map { it.api })
    }

    @Test
    fun transientBatchFailureCannotGrantFineCatSave() {
        val failure = com.finecat.pimobile.android.AndroidSharedStateHolder.ProviderTestOutcome(
            false, "超时", failureKind = "transient",
        )
        val tested = FineCatProviderForm.testedFingerprintOnResult(null, "fp", outcomeAllowsSave = failure.ok)
        assertNull(tested)
        assertFalseCanSave(tested, "fp")
    }

    // ---- 测试指纹：表单变化失效、在途编辑不被洗白 ----

    @Test
    fun fingerprintCoversProtocolAndModelIds() {
        val rows = listOf(ProviderModelDraft(id = "m1", api = "openai-completions"))
        val base = FineCatProviderForm.fingerprint("https://a/v1", "sk-1", rows)
        assertNotEquals(base, FineCatProviderForm.fingerprint("https://a/v1", "sk-2", rows))
        assertNotEquals(base, FineCatProviderForm.fingerprint("https://b/v1", "sk-1", rows))
        assertNotEquals(
            base,
            FineCatProviderForm.fingerprint("https://a/v1", "sk-1", listOf(ProviderModelDraft(id = "m1", api = "anthropic-messages"))),
        )
        // 显示名/上下文窗口不参与指纹
        assertEquals(
            base,
            FineCatProviderForm.fingerprint(
                "https://a/v1",
                "sk-1",
                listOf(ProviderModelDraft(id = "m1", api = "openai-completions", name = "别名", contextWindow = "128K")),
            ),
        )
    }

    /** 结论只绑发起时那份表单：在途改了字段，旧结论不会变成新配置的通行证。 */
    @Test
    fun inFlightEditDoesNotLaunderResult() {
        val before = FineCatProviderForm.fingerprint("https://a/v1", "sk-1", listOf(ProviderModelDraft(id = "m1")))
        val after = FineCatProviderForm.fingerprint("https://a/v1", "sk-1", listOf(ProviderModelDraft(id = "m2")))

        var tested = FineCatProviderForm.testedFingerprintOnResult(null, before, outcomeAllowsSave = true)
        assertEquals(before, tested)
        // 表单变了 → 旧结论作废
        tested = FineCatProviderForm.testedFingerprintAfterEdit(tested, after)
        assertNull(tested)
        assertFalseCanSave(tested, after)
    }

    @Test
    fun failedTestDoesNotGrantSave() {
        val fp = FineCatProviderForm.fingerprint("https://a/v1", "sk-1", listOf(ProviderModelDraft(id = "m1")))
        val tested = FineCatProviderForm.testedFingerprintOnResult(null, fp, outcomeAllowsSave = false)
        assertNull(tested)
        assertFalseCanSave(tested, fp)
    }

    private fun assertFalseCanSave(tested: String?, fingerprint: String) {
        assertTrue(
            "未测过的表单不能保存",
            !FineCatProviderForm.canSave(tested, fingerprint, "initial-other", existingStatusOk = false),
        )
    }

    /** 既有 provider 一字未改且上次网关侧测通过：沿旧语义放行，不逼用户重测。 */
    @Test
    fun unchangedExistingProviderWithOkStatusMaySave() {
        val fp = FineCatProviderForm.fingerprint("https://a/v1", "", listOf(ProviderModelDraft(id = "m1")))
        assertTrue(
            FineCatProviderForm.canSave(
                testedFingerprint = null,
                fingerprint = fp,
                initialFingerprint = fp,
                existingStatusOk = true,
            ),
        )
        // 但没测过的既有 provider（status 非 ok）仍被拦
        assertTrue(
            !FineCatProviderForm.canSave(null, fp, fp, existingStatusOk = false),
        )
    }

    @Test
    fun testedFingerprintSurvivesWhenFormUnchanged() {
        val fp = FineCatProviderForm.fingerprint("https://a/v1", "sk-1", listOf(ProviderModelDraft(id = "m1")))
        assertEquals(fp, FineCatProviderForm.testedFingerprintAfterEdit(fp, fp))
        assertTrue(FineCatProviderForm.canSave(fp, fp, "other", existingStatusOk = false))
    }

    /**
     * 没有模型可测 = 门槛必不过，保存被抻住（与旧 UI 一致：测试按钮本身就依赖
     * “至少一个模型 id”）。不拿“只凭据”当开后门：要存就先给一个能测的模型。
     */
    @Test
    fun formWithoutTestableModelCannotBypassGate() {
        assertTrue(
            !FineCatProviderForm.canSave(
                testedFingerprint = null,
                fingerprint = "fp",
                initialFingerprint = "other",
                existingStatusOk = false,
            ),
        )
        assertNull(FineCatProviderForm.firstValidModel(listOf(ProviderModelDraft(id = ""))))
    }

    /**
     * “把已有模型全删”不另加产品限制（第一轮未经授权新添的规矩已撑回）：解析本身不拦，
     * 能不能发由既有测试门槛说——没模型可测 = 门槛不过，原型也是同样口径。
     */
    @Test
    fun wipingAllModelsHasNoExtraRuleAndIsHeldByTheGate() {
        val existing = provider(models = listOf(GatewayProviderModel(id = "m1"), GatewayProviderModel(id = "m2")))
        assertNull(FineCatProviderForm.parseModels(emptyList(), existing).error)
        // 删模型会动指纹（指纹含模型 id）→ 既有门槛拦住，不会静默下发空 models
        val wipedFingerprint = FineCatProviderForm.fingerprint("https://a/v1", "", emptyList())
        val initial = FineCatProviderForm.fingerprint(
            "https://a/v1",
            "",
            listOf(ProviderModelDraft(id = "m1"), ProviderModelDraft(id = "m2")),
        )
        assertNotEquals(wipedFingerprint, initial)
        assertTrue(
            !FineCatProviderForm.canSave(
                testedFingerprint = null,
                fingerprint = wipedFingerprint,
                initialFingerprint = initial,
                existingStatusOk = true,
            ),
        )
        // 没模型可测：UI 拿到的就是 firstValidModel == null，提示写清下一步
        assertNull(FineCatProviderForm.firstValidModel(emptyList()))
    }

    // ---- 归属与模型选择 ----

    /**
     * 结果只归发起它的那张卡：holder 是全局的、应答帧无 requestId，所以拿发起时的
     * 表单 id 做标记。重开同一 provider、或新建表单后来填了同名 id，都不算“本卡片发的”；
     * 迟到的结果仍归原主（标记未清）。新建未命名用 key(null)，与“没在等”互斥。
     */
    @Test
    fun resultBelongsToTheCardThatAskedForIt() {
        val relay = FineCatProviderForm.requestKey(" relay ")
        assertEquals("relay", relay.providerId)
        assertTrue(FineCatProviderForm.ownsResult(relay, "relay"))
        // 另一张同 id 的新卡片（没发起过）不吃上一张的迟到结果
        assertTrue(!FineCatProviderForm.ownsResult(null, "relay"))
        // 别的 provider 的回执不洗给本表单
        assertTrue(!FineCatProviderForm.ownsResult(relay, "other"))
        // 新建未命名：发起时表单没 id → key(null)，只认 id 也为 null 的结果
        val fresh = FineCatProviderForm.requestKey("   ")
        assertNull(fresh.providerId)
        assertTrue(FineCatProviderForm.ownsResult(fresh, null))
        assertTrue(!FineCatProviderForm.ownsResult(fresh, "relay"))
    }

    /**
     * 换协议前先算清会丢哪些 compat：只有真有值、且新协议键集里没有的项才算，
     * UI 拿这个列表做“应用前确认”（取消则原协议、原 compat 都不动）。
     */
    @Test
    fun apiSwitchLossIsListedBeforeApplyingIt() {
        val anthropic = ProviderModelDraft(
            id = "m1",
            api = "anthropic-messages",
            compat = mapOf(
                "supportsStore" to "false",              // openai-completions 也有：不算损失
                "supportsEagerToolInputStreaming" to "true",  // anthropic 没有：就是会被丢的那一个
            ),
        )
        assertEquals(emptyList<String>(), FineCatProviderForm.compatLoss(anthropic, "anthropic-messages"))
        val loss = FineCatProviderForm.compatLoss(anthropic, "openai-completions")
        assertEquals(listOf("supportsEagerToolInputStreaming"), loss)
        // 默认不写入（null）的项丢了没有损失，不应该拿出来让人确认
        val noRealValue = anthropic.copy(compat = mapOf("supportsEagerToolInputStreaming" to null))
        assertEquals(emptyList<String>(), FineCatProviderForm.compatLoss(noRealValue, "openai-completions"))
        // 真应用时用的还是草稿自己的 switchApi（与旧设置页同一口径），不另外写一份过滤
        assertEquals(
            mapOf("supportsStore" to "false"),
            anthropic.switchApi("openai-completions").compat.filterValues { it != null },
        )
    }

    /**
     * 保存终态归属：在途标记 + 回显内容双关。
     *
     * 旧实现是解析瞬时提示文案（`saveOutcome`）判成败，那条路已被移除——提示槽
     * 会被读请求的取消通知在同一帧覆盖，所以判定必须走 holder 的保存终态通道 +
     * 归属，而不是看提示字符串。本用例守住归属判据本身。
     */
    @Test
    fun saveTerminalAttribution_usesTokenAndEchoNotPromptText() {
        val submitted = FineCatProviderForm.submitFingerprint(
            id = "relay",
            name = "新名字",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                ProviderModelUpsert(id = "m1", api = "openai-completions"),
                ProviderModelUpsert(id = "m2", api = "anthropic-messages"),
            ),
        )
        assertEquals("relay", submitted.providerId)
        assertEquals("新名字", submitted.name)
        assertEquals("https://relay.example/v1", submitted.baseUrl)
        assertEquals(listOf("m1@openai-completions", "m2@anthropic-messages"), submitted.modelKeys)

        // 同一份提交的回显：认。
        assertTrue(
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(
                    id = "relay",
                    name = "新名字",
                    baseUrl = "https://relay.example/v1",
                    models = listOf(
                        GatewayProviderModel(
                            id = "m2",
                            api = "anthropic-messages",
                            reasoning = true,
                            input = listOf("text"),
                        ),
                        GatewayProviderModel(
                            id = "m1",
                            api = "openai-completions",
                            reasoning = true,
                            input = listOf("text"),
                        ),
                    ),
                ),
                submitted,
            ),
        )
        // 名字是上一次提交留下的：迟到的成功，不能拿去收掉这一次的表单。
        assertFalse(
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(
                    id = "relay",
                    name = "旧名字",
                    baseUrl = "https://relay.example/v1",
                    models = submitted.modelKeys.map {
                    val (id, api) = it.split("@")
                    GatewayProviderModel(id = id, api = api, reasoning = true, input = listOf("text"))
                }),
                submitted,
            ),
        )
        // 模型集合对不上：同样不是这一次。
        assertFalse(
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(
                    id = "relay",
                    name = "新名字",
                    baseUrl = "https://relay.example/v1",
                    models = listOf(
                        GatewayProviderModel(
                            id = "m1",
                            api = "openai-completions",
                            reasoning = true,
                            input = listOf("text"),
                        ),
                    ),
                ),
                submitted,
            ),
        )
        // 只改了 baseUrl 的窄窗口（名称没变）：靠 baseUrl 认出来（独立审查 F-1）。
        assertFalse(
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(id = "relay", name = "新名字", baseUrl = "https://旧.example/v1"),
                submitted,
            ),
        )
        // providerId 对不上：不是这份表单的。
        assertFalse(
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(id = "other", name = "新名字"),
                submitted,
            ),
        )
        // 回显缺 baseUrl：无法反证，放过（宁可放过迟到，也不让真实成功卡住表单）。
        assertTrue(
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(id = "relay", name = "新名字"),
                submitted,
            ),
        )
        // 帧没带 provider / 没有提交特征：一律交给 token 判断，这里不反证。
        assertTrue(FineCatProviderForm.echoMatchesSubmit(null, submitted))
        assertTrue(FineCatProviderForm.echoMatchesSubmit(GatewayProvider(id = "relay"), null))
    }

    /**
     * 模型级 `contextWindow` / `maxTokens` 也能把「迟到的上一份成功」认出来。
     *
     * 这是 [FineCatProviderForm.submitFingerprint] 纳入数值上限的原因：上一份保存
     * 超时成 Unconfirmed 后，用户**只**改了这个模型的上下文窗口就重发，旧 provider-saved
     * 先到时 id/name/baseUrl/模型集合全同，光靠那些字段认不出来。
     *
     * 判据是「本次提交了具体值就要求回显一致（含回显为 null）」——本方法同时钉住
     * 反面：留空提交遇到回显旧值必须放过（wire 上没下发，网关按合并语义保留旧值，
     * 回显有值是正常的，判否会让真实成功永远收不了卡）。
     */
    @Test
    fun echoMatchesSubmit_refutesStaleNumericLimits_butPassesBlankSubmit() {
        val submitted = FineCatProviderForm.submitFingerprint(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                ProviderModelUpsert(
                    id = "m1",
                    api = "openai-completions",
                    contextWindow = 262_144,
                    maxTokens = 32_768,
                ),
            ),
        )
        // 回显里的 reasoning / input 是网关**无条件下发**的两项（`toWireModel`），
        // 这里与提交侧一致（默认 reasoning、只收文本），别写成缺省值——那会与
        // 「客户端无条件下发 reasoning=true、input=[text]」的真实链路对不上。
        fun echo(contextWindow: Long?, maxTokens: Long?) = GatewayProvider(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                GatewayProviderModel(
                    id = "m1",
                    api = "openai-completions",
                    reasoning = true,
                    input = listOf("text"),
                    contextWindow = contextWindow,
                    maxTokens = maxTokens,
                ),
            ),
        )

        // 本次提交的回显：认。
        assertTrue(FineCatProviderForm.echoMatchesSubmit(echo(262_144, 32_768), submitted))
        // 只改了 contextWindow 的窄窗口：旧回显（131072）必须被反证掉。
        assertFalse(FineCatProviderForm.echoMatchesSubmit(echo(131_072, 32_768), submitted))
        // 只改了 maxTokens 的窄窗口：同样反证掉。
        assertFalse(FineCatProviderForm.echoMatchesSubmit(echo(262_144, 8_192), submitted))
        // 最阴的一条：上一次没填这个数（回显 null）、这一次填了。回显的 null 是
        // 明确的「没落盘」（网关 toWireModel 恒发该字段），与「本次填了」矛盾 → 反证。
        assertFalse(FineCatProviderForm.echoMatchesSubmit(echo(null, 32_768), submitted))

        // 反面一：本次留空 → wire 不下发该字段，回显是网关保留的旧值，必须放过
        // （判否会让真实成功永远收不了卡）。
        val blankSubmit = FineCatProviderForm.submitFingerprint(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(ProviderModelUpsert(id = "m1", api = "openai-completions")),
        )
        assertTrue(FineCatProviderForm.echoMatchesSubmit(echo(131_072, 8_192), blankSubmit))
        assertTrue(FineCatProviderForm.echoMatchesSubmit(echo(null, null), blankSubmit))
        // 回显里压根没有该模型条目（认不出是哪条）→ 不反证。
        assertTrue(
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(id = "relay", name = "中转站", baseUrl = "https://relay.example/v1"),
                submitted,
            ),
        )
        // 只提交了其中一个数时，只用它判：maxTokens 没提交就完全不参与。
        val contextOnly = FineCatProviderForm.submitFingerprint(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                ProviderModelUpsert(id = "m1", api = "openai-completions", contextWindow = 262_144),
            ),
        )
        assertFalse(FineCatProviderForm.echoMatchesSubmit(echo(131_072, 8_192), contextOnly))
        assertTrue(FineCatProviderForm.echoMatchesSubmit(echo(262_144, 8_192), contextOnly))
    }

    /**
     * 模型级「开关」类字段也能把迟到的上一份成功认出来（独立审查 F-1）。
     *
     * 上一条用例覆盖数值上限后还剩同一类的窄窗口：用户在模型二级卡只拨一下
     * 「支持推理 / 输入图片」或只改模型显示名就重发。这三个字段也不在
     * `providerFingerprint`（只含 baseUrl/apiKey/模型 id@api）里，**不会触发重测门槛**，
     * 而开关比敲数字更容易误触。
     */
    @Test
    fun echoMatchesSubmit_refutesStaleModelTogglesAndName() {
        val submitted = FineCatProviderForm.submitFingerprint(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                ProviderModelUpsert(
                    id = "m1",
                    name = "M1",
                    api = "openai-completions",
                    supportsImages = true,
                ),
            ),
        )
        fun echo(reasoning: Boolean, input: List<String>, name: String? = "M1") = GatewayProvider(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                GatewayProviderModel(
                    id = "m1",
                    api = "openai-completions",
                    name = name,
                    reasoning = reasoning,
                    input = input,
                ),
            ),
        )

        // 本次提交的回显：认（reasoning 默认 true、input 含 image、显示名 M1）。
        assertTrue(FineCatProviderForm.echoMatchesSubmit(echo(true, listOf("text", "image")), submitted))
        // 只拨了「支持推理」：旧回显 reasoning 仍是 false → 反证。
        assertFalse(FineCatProviderForm.echoMatchesSubmit(echo(false, listOf("text", "image")), submitted))
        // 只拨了「输入图片」：旧回显 input 里没有 image → 反证。
        assertFalse(FineCatProviderForm.echoMatchesSubmit(echo(true, listOf("text")), submitted))
        // 只改了模型显示名 → 反证。
        assertFalse(
            FineCatProviderForm.echoMatchesSubmit(
                echo(true, listOf("text", "image"), name = "另一个名字"),
                submitted,
            ),
        )
        // input 的顺序无关（网关回显按输入顺序，客户端按键排序比较）。
        assertTrue(FineCatProviderForm.echoMatchesSubmit(echo(true, listOf("image", "text")), submitted))
    }

    /** 空白 id 按 null（与下发给网关的口径一致）；token 随请求回传。 */
    @Test
    fun requestKey_carriesTokenAndNormalizesBlankId() {
        assertNull(FineCatProviderForm.requestKey("  ").providerId)
        val key = FineCatProviderForm.requestKey("  relay  ", "save-1")
        assertEquals("relay", key.providerId)
        assertEquals("save-1", key.token)
        // token 是归属的第一道关：不同请求的终态互不认领。
        assertNotEquals(key.token, FineCatProviderForm.requestKey("relay", "save-2").token)
    }

    /** 测试用第一个填了 id 的模型；整行只给了名字不算有效（不发必败请求）。 */
    @Test
    fun testTargetPicksFirstValidModel() {
        val rows = listOf(
            ProviderModelDraft(id = "", name = "还没填"),
            ProviderModelDraft(id = "m2", api = "anthropic-messages"),
        )
        assertEquals("m2", FineCatProviderForm.firstValidModel(rows)?.id)
        assertEquals("anthropic-messages", FineCatProviderForm.firstValidModel(rows)?.api)
        assertNull(FineCatProviderForm.firstValidModel(listOf(ProviderModelDraft(id = ""))))
    }

    /** 已有模型的 id 只读（改 id 在网关侧等于删旧加新），新行的 id 可填。 */
    @Test
    fun idLockedOnlyForExistingModels() {
        val existing = provider(models = listOf(GatewayProviderModel(id = "m1")))
        assertTrue(FineCatProviderForm.idLocked(existing, ProviderModelDraft(id = "m1")))
        assertTrue(!FineCatProviderForm.idLocked(existing, ProviderModelDraft(id = "m2")))
        assertTrue(!FineCatProviderForm.idLocked(null, ProviderModelDraft(id = "m1")))
    }

    // ---- compat 三态（DEC-F09-COMPAT）：不下发 / 权威覆盖 / 清空；thinkingLevelMap 仍透传 ----

    /**
     * 没动过 compat 的已有模型：**字段整个不下发**（`upsert.compat == null`）。
     *
     * 这是「重新保存不会把电脑上配好的这一层冲掉」最强的一种保证——不下发就没有任何
     * 可能被网关的合并/归一改写，比「原样回传一份」更稳（回显缺项时回传会静默删键）。
     */
    @Test
    fun untouchedCompatIsOmittedWhileThinkingMapStillPassesThrough() {
        val rows = FineCatProviderForm.initialRows(
            provider(
                models = listOf(
                    GatewayProviderModel(
                        id = "m1",
                        api = "openai-completions",
                        thinkingLevelMap = mapOf("low" to "medium", "off" to null),
                        compat = JsonValue.ObjectValue(
                            mapOf(
                                "supportsStore" to JsonValue.BooleanValue(false),
                                "maxTokensField" to JsonValue.StringValue("max_tokens"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val existing = provider(
            models = listOf(GatewayProviderModel(id = "m1", api = "openai-completions")),
        )
        val parsed = FineCatProviderForm.parseModels(rows, existing)
        assertNull(parsed.error)
        val upsert = parsed.rows.single()
        assertEquals(mapOf("low" to "medium", "off" to null), upsert.thinkingLevelMap)
        assertNull("compat 没动过就必须不下发：字段省略 = 网关保留原配置", upsert.compat)
        requireValidProviderUpsert("relay", "https://relay.example/v1", null, parsed.rows, "中转站")
    }

    /**
     * 动过 compat 的已有模型：手机是权威的，草稿按协议键集声明顺序下发。
     *
     * 这条同时钉住「编辑一个字段不丢其他键」：草稿里除 `supportsStore` 之外还有
     * `maxTokensField` 与一个**不属于本协议**的 `forceAdaptiveThinking`（wire 回显不按协议
     * 过滤），三者都必须原样出现在下发里，顺序按声明顺序、残留键按字典序。
     */
    @Test
    fun touchedCompatIsSentInDeclaredKeyOrder() {
        val row = ProviderModelDraft(
            id = "m1",
            api = "openai-completions",
            contextWindow = "128K",
            compat = mapOf(
                "supportsStore" to "true",
                "maxTokensField" to "max_tokens",
                "forceAdaptiveThinking" to "true",
            ),
            compatTouched = true,
        )
        val upsert = FineCatProviderForm.parseModels(listOf(row), null).rows.single()
        assertEquals(
            listOf("supportsStore", "maxTokensField", "forceAdaptiveThinking"),
            upsert.compat!!.keys.toList(),
        )
        requireValidProviderUpsert("relay", "https://relay.example/v1", null, listOf(upsert), "中转站")
    }

    /** 「清空手机管理的兼容参数」下发空对象而不是省略字段：两者含义完全不同。 */
    @Test
    fun clearedCompatSendsEmptyObjectNotOmittedField() {
        val cleared = ProviderModelDraft(
            id = "m1",
            api = "openai-completions",
            contextWindow = "128K",
            compat = FineCatCompat.cleared(),
            compatTouched = true,
        )
        val upsert = FineCatProviderForm.parseModels(listOf(cleared), null).rows.single()
        assertEquals(emptyMap<String, String?>(), upsert.compat)
    }

    /**
     * 切协议是有意的有损删除：必须置 `compatTouched`，否则字段省略会让网关把旧键整个留下，
     * UI 上确认过的「丢 N 项」就成了假话。
     */
    @Test
    fun switchingProtocolMarksCompatTouchedAndKeepsOnlyNewApiKeys() {
        val row = ProviderModelDraft(
            id = "m1",
            api = "openai-completions",
            contextWindow = "128K",
            compat = mapOf(
                "supportsDeveloperRole" to "true",
                "supportsStore" to "false",
                "thinkingFormat" to "deepseek",
            ),
        )
        assertEquals(
            listOf("supportsStore", "thinkingFormat"),
            FineCatProviderForm.compatLoss(row, "openai-responses"),
        )
        val switched = row.switchApi("openai-responses")
        assertTrue("切协议必须让本次对 compat 权威", switched.compatTouched)
        val upsert = FineCatProviderForm.parseModels(listOf(switched), null).rows.single()
        assertEquals(mapOf("supportsDeveloperRole" to "true"), upsert.compat)
    }

    /**
     * 反向：**无损**切协议不得把 compat 升级成权威写。
     *
     * 有损时置 `compatTouched` 是必须的（否则字段省略会让网关把旧键整个留下，UI 上确认过的
     * 「丢 N 项」就成了假话）。但无损时若也置上，本次保存就会把当前草稿当成权威整份下发：
     * 用户没碰过的键会被**静默删掉**——比如切协议后、又在电脑上给同一模型加了一个键，保存时
     * 那台机器的改动就没了。这条断言是 MINOR-7 处置的负向腿，缺了它「只在有损失时才 touched」
     * 只是代码里的一个条件，没有测试守着。
     */
    @Test
    fun switchingProtocolWithoutLossKeepsCompatUntouched() {
        // 这两个键 openai-completions 与 anthropic-messages **都有**，所以这次切换无损失。
        // 换之前先用前提断言钉住这一点：键集表一改，这里红了才说明是键集变了，不是有损分支变了。
        val base = ProviderModelDraft(
            id = "m1",
            api = "openai-completions",
            contextWindow = "128K",
            compat = mapOf(
                "supportsLongCacheRetention" to "true",
                "sendSessionAffinityHeaders" to "false",
            ),
        )
        val target = "anthropic-messages"
        assertEquals(
            "前提：这两个键必须同时存在于两个协议，否则本用例证明的不是无损分支",
            emptyList<String>(),
            FineCatProviderForm.compatLoss(base, target),
        )
        val switched = base.switchApi(target)
        assertFalse("无损切协议不得把 compat 升级为权威写", switched.compatTouched)
        // 字段整体省略 ⇒ 网关原样保留电脑上那一层，而不是被手机的草稿覆盖。
        assertEquals(
            null,
            FineCatProviderForm.parseModels(listOf(switched), null).rows.single().compat,
        )
    }

    /**
     * compat 回显反证（F12 条件 5 的窄窗口）：第一次保存超时/被取消后，用户**只改 compat**
     * 再保存，上一条回执迟到时除 compat 外逐字相同，没有别的依据能认出它不是这一次的。
     */
    @Test
    fun echoMatchesSubmit_refutesLateReceiptWhenOnlyCompatChanged() {
        fun upsert(supportsStore: String) = ProviderModelUpsert(
            id = "m-1",
            api = "openai-completions",
            contextWindow = 128_000,
            maxTokens = 8192,
            compat = linkedMapOf(
                "supportsDeveloperRole" to "true",
                "supportsStore" to supportsStore,
            ),
        )
        fun echo(providerStore: Boolean) = GatewayProvider(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                GatewayProviderModel(
                    id = "m-1",
                    api = "openai-completions",
                    contextWindow = 128_000,
                    maxTokens = 8192,
                    reasoning = true,
                    input = listOf("text"),
                    compat = JsonValue.ObjectValue(
                        mapOf(
                            "supportsDeveloperRole" to JsonValue.BooleanValue(true),
                            "supportsStore" to JsonValue.BooleanValue(providerStore),
                        ),
                    ),
                ),
            ),
        )

        val submitted = FineCatProviderForm.submitFingerprint(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(upsert("true")),
        )
        assertFalse(
            "只改 compat 的迟到回执必须被反证：否则会把上一次的超时错认成本次成功并提前收卡",
            FineCatProviderForm.echoMatchesSubmit(echo(providerStore = false), submitted),
        )
        assertTrue(
            "本次提交自己的 compat 回显必须被接受",
            FineCatProviderForm.echoMatchesSubmit(echo(providerStore = true), submitted),
        )
    }

    /** 本次没碰 compat（字段不下发）时不参与反证：回显是网关保留的旧值，不能据此判否。 */
    @Test
    fun echoMatchesSubmit_ignoresCompatWhenNotSubmitted() {
        val submitted = FineCatProviderForm.submitFingerprint(
            id = "relay",
            name = "中转站",
            baseUrl = "https://relay.example/v1",
            models = listOf(
                ProviderModelUpsert(id = "m-1", api = "openai-completions", contextWindow = 128_000),
            ),
        )
        assertTrue(
            "没下发 compat 就不能拿它反证",
            FineCatProviderForm.echoMatchesSubmit(
                GatewayProvider(
                    id = "relay",
                    name = "中转站",
                    baseUrl = "https://relay.example/v1",
                    models = listOf(
                        GatewayProviderModel(
                            id = "m-1",
                            api = "openai-completions",
                            contextWindow = 128_000,
                            reasoning = true,
                            input = listOf("text"),
                            compat = JsonValue.ObjectValue(
                                mapOf("supportsStore" to JsonValue.BooleanValue(true)),
                            ),
                        ),
                    ),
                ),
                submitted,
            ),
        )
    }
    @Test
    fun decimalUnitsProduceExactProviderUpsertNumbers() {
        val parsed = FineCatProviderForm.parseModels(listOf(ProviderModelDraft(
            id = "decimal", contextWindow = "500K", maxTokens = "275K")), null)
        assertNull(parsed.error)
        assertEquals(500000L, parsed.rows.single().contextWindow)
        assertEquals(275000L, parsed.rows.single().maxTokens)
        val mega = FineCatProviderForm.parseModels(listOf(ProviderModelDraft(
            id = "mega", contextWindow = "2.5M", maxTokens = "1M")), null)
        assertNull(mega.error)
        assertEquals(2500000L, mega.rows.single().contextWindow)
        assertEquals(1000000L, mega.rows.single().maxTokens)
    }

    @Test
    fun invalid375lCannotProduceASaveRequestModel() {
        val existing = provider(models = listOf(GatewayProviderModel(id = "m1", contextWindow = 500000L)))
        val base = FineCatProviderForm.initialRows(existing).single()
        for (draft in listOf(base.copy(contextWindow = "375l"), base.copy(maxTokens = "375l"))) {
            val parsed = FineCatProviderForm.parseModels(listOf(draft), existing)
            assertNotNull(parsed.error)
            assertTrue(parsed.rows.isEmpty())
        }
    }

}
