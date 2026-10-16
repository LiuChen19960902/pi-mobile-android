package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ProviderModelDraft
import com.finecat.pimobile.android.ProviderTestAttempt
import com.finecat.pimobile.android.ui.providerFingerprint
import com.finecat.pimobile.shared.domain.ModelNumberParsing
import com.finecat.pimobile.shared.domain.ModelNumberResult
import com.finecat.pimobile.shared.gateway.PROVIDER_COMPAT_KEYS_BY_API
import com.finecat.pimobile.shared.gateway.PROVIDER_DEFAULT_API
import com.finecat.pimobile.shared.gateway.ProviderModelUpsert
import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.protocol.GatewayProviderModel
import com.finecat.pimobile.shared.protocol.JsonValue

/** 当前应用运行期间的离线草稿；密钥不进入Bundle或本地持久化。 */
internal class ProviderEditorDraft(
    val initialProvider: GatewayProvider?,
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val rows: List<ProviderModelDraft>,
    val modelViewOrder: List<String>,
) {
    override fun toString(): String = "ProviderEditorDraft(<redacted>)"
}

/**
 * P10a/P10b FineCat Provider 表单的纯逻辑（无 Compose，JVM 可测）。
 *
 * 复用旧 UI 已验证过的两份真源：模型行用 [ProviderModelDraft]、连通性指纹用
 * [providerFingerprint]，语义与 `ProviderSettingsUi` 保持一致（同一份配置在两套
 * UI 里改，测试结论的判定口径不会分叉）。
 */
internal object FineCatProviderForm {

    // Pi SDK 1.0.4 自定义模型的缺省容量，不代表上游已声明的真实能力。
    const val DEFAULT_CONTEXT_WINDOW = 128_000L
    const val DEFAULT_MAX_TOKENS = 16_384L

    /** 保存改动包含全部模型草稿；连接测试指纹只负责测试有效性，视图顺序不参与。 */
    fun hasChanges(
        initial: GatewayProvider?, id: String, name: String, baseUrl: String,
        apiKey: String, rows: List<ProviderModelDraft>,
    ): Boolean = id != initial?.id.orEmpty() || name != initial?.name.orEmpty() ||
        baseUrl != initial?.baseUrl.orEmpty() || apiKey.isNotBlank() || rows != initialRows(initial)

    /** 没有本次配置对应的逐项结果时保持未测试，不能借 Provider 的聚合成功。 */
    fun currentTestAttempt(
        modelId: String, fingerprint: String, resultFingerprint: String?, attempts: List<ProviderTestAttempt>,
    ): ProviderTestAttempt? = if (resultFingerprint == fingerprint) {
        attempts.singleOrNull { it.modelId == modelId.trim() }
    } else null

    /** 只投影视图顺序；原 rows 顺序继续供指纹与 provider-put 使用。 */
    fun viewIndices(rows: List<ProviderModelDraft>, order: List<String>): List<Int> {
        val rank = order.withIndex().associate { it.value to it.index }
        return rows.indices.sortedWith(compareBy<Int> { rank[rows[it].id.trim()] ?: Int.MAX_VALUE }.thenBy { it })
    }

    fun moveViewUp(rows: List<ProviderModelDraft>, order: List<String>, rowIndex: Int): List<String> {
        val indices = viewIndices(rows, order).toMutableList()
        val position = indices.indexOf(rowIndex)
        if (position <= 0 || rows[rowIndex].id.isBlank() || rows[indices[position - 1]].id.isBlank()) return order
        indices.add(position - 1, indices.removeAt(position))
        return indices.map { rows[it].id.trim() }.filter(String::isNotBlank).distinct()
    }

    /**
     * 进表单时的初始行：既有模型逐个转成草稿。数值按 [ModelNumberParsing] 的
     * K/M 口径显示（format 与 parse 同倍率，"10M" 这类回显值必须还能存回去）。
     */
    fun initialRows(existing: GatewayProvider?): List<ProviderModelDraft> {
        val inheritedApi = existing?.api?.takeIf(String::isNotBlank)
        return existing?.models.orEmpty().map { model ->
            ProviderModelDraft.from(model, inheritedApi).let { draft ->
                draft.copy(
                    contextWindow = model.contextWindow
                        ?.let(ModelNumberParsing::formatTokenCount) ?: "",
                    maxTokens = model.maxTokens
                        ?.let(ModelNumberParsing::formatTokenCount) ?: "",
                )
            }
        }
    }

    /** 手动添加模型：协议继承表单里第一个模型（同一入口通常是同一方言），id 空待填。 */
    fun newRow(rows: List<ProviderModelDraft>): ProviderModelDraft = ProviderModelDraft(
        api = rows.firstOrNull()?.api?.takeIf(String::isNotBlank) ?: PROVIDER_DEFAULT_API,
    )

    /** 既有模型的 id 只读（改 id 在网关侧等于删旧加新，会丢宿主侧 cost 等字段）。 */
    fun idLocked(existing: GatewayProvider?, row: ProviderModelDraft): Boolean =
        row.id in existing?.models.orEmpty().map { it.id }

    /** 拉取上游时给新模型的协议：跟当前表单走，空表回落到默认方言。 */
    fun pullApi(rows: List<ProviderModelDraft>): String =
        rows.firstOrNull()?.api?.takeIf(String::isNotBlank) ?: PROVIDER_DEFAULT_API

    /**
     * 上游拉取结果并入表单——**只新增、不覆盖**（原型 `pv-pull`：已存在的一条都不动，
     * 否则拉一次就把人配好的参数抹了）。返回新增/跳过计数供回执展示。
     */
    fun appendPulled(
        rows: List<ProviderModelDraft>,
        ids: List<String>,
    ): PullAppend {
        val nonBlank = ids.filter { it.isNotBlank() }
        val fresh = nonBlank.distinct().filter { id -> rows.none { it.id == id } }
        val api = rows.firstOrNull()?.api?.takeIf(String::isNotBlank) ?: PROVIDER_DEFAULT_API
        val added = fresh.map { ProviderModelDraft(id = it, api = api) }
        return PullAppend(
            rows = rows + added,
            added = added.size,
            // 跳过数 = 上游报了但没新增的行（已存在 + 上游重复项）
            skipped = nonBlank.size - added.size,
        )
    }

    /**
     * 把表单行解析成 wire upsert。
     * 返回 `null` 表示可以提交（[ParsedProviderModels.rows] 即下发内容）；
     * 非 null 是必须**行内可见**的错误——不静默 0、不丢弃该行、也不关表单。
     *
     * 数值：已有模型空白=保留，新模型空白=SDK缺省；K/M/原始数字按 shared 同一倍率解析；
     * `compat` 是三态（`DEC-F09-COMPAT`）：**没动过 → 整个字段不下发**（网关按合并语义
     * 原样保留电脑上那一层）；动过 → 手机是权威的，草稿里没有的键会被清除，整表为空则
     * 清空所有手机管理的键。序列化顺序按协议键集声明顺序冻结，见 [FineCatCompat.orderedForWire]。
     * 两种情况都**不发显式 null**（网关 `normalizeCompat` 直接跳过 null 条目，且未知键被丢弃，
     * 发 null 与省略等价，但没有理由多发一个会被跳过的键）。
     *
     * [existing] 用来区分新旧模型：新行容量留空使用可编辑的 SDK 缺省值；
     * 已有行留空不发该字段，由网关保留原配置。不要求目录元数据或电脑端认证。
     */
    fun parseModels(
        rows: List<ProviderModelDraft>,
        existing: GatewayProvider?,
    ): ParsedProviderModels {
        val upserts = mutableListOf<ProviderModelUpsert>()
        rows.forEach { draft ->
            val modelId = draft.id.trim()
            // 完全没填的草稿行按原型「收起时丢掉空行」处理，不算错误。
            if (modelId.isEmpty() && draft.name.isBlank() &&
                draft.contextWindow.isBlank() && draft.maxTokens.isBlank() && draft.baseUrl.isBlank()
            ) {
                return@forEach
            }
            if (modelId.isEmpty()) {
                return ParsedProviderModels(error = "模型 ID 不能为空（填完或删掉这一行）")
            }
            if (draft.baseUrlTouched && draft.baseUrl.isNotBlank() &&
                !draft.baseUrl.trim().matches(Regex("https?://.+"))
            ) {
                return ParsedProviderModels(error = "$modelId: 模型地址应以 http:// 或 https:// 开头，留空可继承 Provider。")
            }
            val contextWindow = ModelNumberParsing.parseTokenCount(draft.contextWindow)
            if (contextWindow is ModelNumberResult.Invalid) {
                return ParsedProviderModels(error = "$modelId: 上下文窗口 ${contextWindow.message}")
            }
            val maxTokens = ModelNumberParsing.parseTokenCount(draft.maxTokens)
            if (maxTokens is ModelNumberResult.Invalid) {
                return ParsedProviderModels(error = "$modelId: 最大输出 ${maxTokens.message}")
            }
            val isNew = !idLocked(existing, draft.copy(id = modelId))
            upserts += ProviderModelUpsert(
                id = modelId,
                name = draft.name.trim().takeIf(String::isNotEmpty),
                contextWindow = (contextWindow as? ModelNumberResult.Ok)?.value
                    ?: DEFAULT_CONTEXT_WINDOW.takeIf { isNew },
                maxTokens = (maxTokens as? ModelNumberResult.Ok)?.value
                    ?: DEFAULT_MAX_TOKENS.takeIf { isNew },
                reasoning = draft.reasoning,
                supportsImages = draft.imageOn,
                // 协议逐模型显式下发（pi 只按 model.api 分派流实现），新模型不再传空串。
                api = draft.api.takeIf(String::isNotBlank) ?: PROVIDER_DEFAULT_API,
                thinkingLevelMap = draft.thinkingMap
                    .filterValues(String::isNotBlank)
                    .mapValues { (_, target) -> if (target == "-") null else target },
                // 三态（DEC-F09-COMPAT）：未触碰 = null = 字段整个不下发 = 保留原配置。
                compat = draft.compat
                    .takeIf { draft.compatTouched }
                    ?.let { FineCatCompat.orderedForWire(draft.api, it) },
                baseUrl = draft.baseUrl.trim().takeIf(String::isNotEmpty),
                baseUrlEdited = draft.baseUrlTouched,
            )
        }
        return ParsedProviderModels(rows = upserts)
    }

    /** 首个填了id的模型，保留原选择逻辑回归；当前批次测试使用testModels。 */
    fun firstValidModel(rows: List<ProviderModelDraft>): ProviderModelDraft? =
        rows.firstOrNull { it.id.isNotBlank() }

    /** 按表单顺序测试，忽略空行；同一模型/协议只探测一次。 */
    fun testModels(rows: List<ProviderModelDraft>): List<ProviderModelDraft> =
        rows.filter { it.id.isNotBlank() }.map { it.copy(id = it.id.trim(), baseUrl = it.baseUrl.trim()) }
            .distinctBy { it.id to it.api }

    /** 「测试时的表单」指纹（复用旧 UI 判据：只含影响连通性的字段）。 */
    fun fingerprint(baseUrl: String, apiKey: String, rows: List<ProviderModelDraft>): String =
        providerFingerprint(baseUrl, apiKey, rows)

    /**
     * 保存门槛（旧 UI 同语义）：本份表单刚测通过，或既有 provider 一字未改且上次
     * 网关侧结论是通过。没有第三种放行：表单里还没填模型时测试按钮不可用，那时
     * 就是不能保存（网关虽允许“只凭据”的 provider，但门槛不得被绕过），UI 把
     * 这一步该做什么写清楚。在途测试不在此进硬拦：同一份配置上次已通过就没必要
     * 重新排队，而“测试被 lane 挡回、结论不会来”时硬拦会把表单锁死；在途编辑的
     * 防护靠 [testedFingerprintOnResult] 只给发起时指纹的通行证。
     */
    fun canSave(
        testedFingerprint: String?,
        fingerprint: String,
        initialFingerprint: String,
        existingStatusOk: Boolean,
    ): Boolean {
        if (testedFingerprint != null && testedFingerprint == fingerprint) return true
        return fingerprint == initialFingerprint && existingStatusOk
    }

    /**
     * 测试结论回来时绑定「发起时那份表单」：只有结论允许保存才记下指纹，
     * 否则保持原状（失败不能把旧通行证续上）。
     */
    fun testedFingerprintOnResult(
        currentTested: String?,
        pendingFingerprint: String?,
        outcomeAllowsSave: Boolean,
    ): String? = if (outcomeAllowsSave && pendingFingerprint != null) pendingFingerprint else currentTested

    /** 表单变化后旧结论失效：仅当已测指纹 != 当前指纹时抹掉。 */
    fun testedFingerprintAfterEdit(currentTested: String?, fingerprint: String): String? =
        currentTested?.takeIf { it == fingerprint }

    /**
     * 本卡片发起过、还没取走结论的一次 provider 请求。
     *
     * 网关的 provider 应答帧**没有 requestId**（PROTOCOL.md §4.17 只回显 `id`），
     * 所以归属只能做在有界的地方：发起方自己记住「我在等哪个 id 的结果」，结果帧的
     * `providerId` 必须与发起时一致才算数。`providerId == null` 表示发起时表单还没
     * id（新建未命名），它和「没在等」是两回事，故用非空的 key 包一层。
     */
    data class ProviderRequestKey(val providerId: String?, val token: String? = null)

    /**
     * 拉取/测试结论归属：只认本卡片发起的那次。
     * 关掉重开同一个 provider、或新建表单后来填了同名 id，都不会把上一个表单的迟到
     * 结论吃进来——[ProviderRequestKey] 是每张卡自己的 remember，换卡即换新标记。
     */
    fun ownsResult(pending: ProviderRequestKey?, outcomeProviderId: String?): Boolean =
        pending != null && pending.providerId == outcomeProviderId

    /** 发起请求时给表单目标打标记：空白 id 按 null（与下发给网关的口径一致）。 */
    fun requestKey(formId: String, token: String? = null): ProviderRequestKey =
        ProviderRequestKey(formId.trim().takeIf(String::isNotBlank), token)

    /**
     * 本次提交的可核对特征：**只取用户可控、且网关会原样回显的字段**。
     *
     * 用于判断一条 `provider-saved` 是不是「我这次提交」的结果——网关应答帧没有
     * requestId（PROTOCOL §4.17 只回显 `id`），`correlationId` 在 provider 系实际
     * 为 null，同一 provider 关闭再打开时 id 也一样，光靠 id 分不出「上一份的迟到
     * 回执」和「这一次的成功」。回显内容是真实依据，不是凭空造关联。
     *
     * 覆盖 providerId / name / baseUrl / 模型 id@api / 模型级 name、contextWindow、
     * maxTokens、reasoning、input。
     *
     * 纳入标准只有一条：**用户可控、且网关会原样回显**。三个「只改一个字段」的窄窗口
     * 都要靠指纹认出来 —— 改 baseUrl（上一轮审查 F-1）、只改某个模型的数值上限、
     * 只拨一下模型卡上的「支持推理 / 输入图片」开关（比敲数字更容易误触，且
     * [providerFingerprint] 不含这些字段，**不会触发重测门槛**，无需重测就能重发）。
     * 它们的网关口径逐条核对见 [echoMatchesSubmit] 与 [ProviderModelEcho]。
     */
    fun submitFingerprint(
        id: String,
        name: String?,
        baseUrl: String?,
        models: List<ProviderModelUpsert>,
    ): ProviderSubmitFingerprint = ProviderSubmitFingerprint(
        providerId = id.trim().takeIf(String::isNotBlank),
        name = name?.trim()?.takeIf(String::isNotEmpty),
        baseUrl = baseUrl?.trim()?.takeIf(String::isNotEmpty),
        modelKeys = models.map { "${it.id.trim()}@${it.api.orEmpty()}" }.sorted(),
        // 同 id 重复会被网关以「模型 id 重复」拒收，本地不会走到回显这一步。
        modelEchoes = models.associate {
            "${it.id.trim()}@${it.api.orEmpty()}" to ProviderModelEcho.of(it)
        },
    )

    /**
     * 回显是否与本次提交相符。
     *
     * 判定刻意保守：**只反证，不臆测**。帧没带 provider（`echo == null`）或提交
     * 特征缺失（`submitted == null`）时一律返回 true，把归属交给 token 决定——
     * 宁可放过一次迟到的成功（表单多等一轮、用户可重试），也不要因为网关的归一化
     * 差异误判「这不是我的」而让真实成功卡住表单不收。
     *
     * 模型级字段能进指纹，依赖对网关三方口径的逐条核对
     * （网关 `pi-store.ts`，2026-10-02 实读源码）：
     * 1. **客户端发送**（`GatewayProtocol.providerPut`）：表单留空 → 该字段**整个不下发**。
     * 2. **网关入参归一**（`normalizeProviderUpsert`）：只有正整数才写进 definition；
     *    没下发/非正整数 → 该键在提交里**不存在**。
     * 3. **网关合并落盘**（`PiProviderStore.put`）：`{...上一条模型, ...本次模型}`，
     *    本次没这个键 → **保留旧值**（所以「留空」是保留，不是清除）。
     * 4. **回显**（`toWireModel`）：`asPositiveInteger(已落盘值) ?? null`——字段
     *    **恒存在**，未落盘时是显式 `null`，不会省略；回显取自刚写完的 merged 记录，
     *    不经模型目录，所以**没有目录默认值顶替**。
     *
     * 由此得到两个方向的判据：
     * - **本次提交了具体数值**（`!= null`）→ 回显**必须**等于它，不同（含回显为
     *   `null`）就是反证。回显取自刚落盘的那份记录，不经目录、不带默认值，所以
     *   「提交了就一定回显得到同样的值」是可核实的，不是推测；而且这正好覆盖最阴的
     *   那条窄窗口——上一次没填某个数（回显 null）、这一次填了，迟到的旧回执会把
     *   真实成功错认成旧内容。
     * - **本次留空**（`null`）→ 字段压根不下发，回显是网关保留的旧值（多半非空），
     *   此时**必须放过**，否则会把真实成功判成别人的回执、让表单永远不收。
     *
     * 「回显缺字段」在数值上限上不存在：wire 类型声明的是 `number | null`
     * （网关 `WireProviderModel`，非可选键），所以 `null` 是明确的「没落盘」而不是
     * 「没回」。真缺字段的帧（字段不可比较）仍按上面的「无法反证」放过。
     * `reasoning` / `input` 是客户端无条件下发、网关原样回显的（见 [ProviderModelEcho]），
     * 直接比即可；`thinkingLevelMap` / `compat` 会被网关归一与深合并，**故意不纳入**。
     *
     * 指纹侧的数值与 wire 上发出去的完全同源：`submit()` 就是拿 `parseModels` 出来
     * 的同一份 rows 算的，不会出现「发的和比的不是一个值」。
     */
    fun echoMatchesSubmit(
        echo: GatewayProvider?,
        submitted: ProviderSubmitFingerprint?,
    ): Boolean {
        if (echo == null || submitted == null) return true
        if (submitted.providerId != null && echo.id != submitted.providerId) return false
        // 名字是用户直接改的，最能区分「上一次提交」与「这一次提交」。
        if (submitted.name != null && echo.name != submitted.name) return false
        // baseUrl 同理，且是「只改了这一个字段」时唯一的区分点。回显缺该字段时
        // 同样只当「无法反证」放过——判否会让真实成功卡住表单，比放过一次迟到更糟。
        if (submitted.baseUrl != null && echo.baseUrl != null && echo.baseUrl != submitted.baseUrl) {
            return false
        }
        if (submitted.modelKeys.isNotEmpty() && echo.models.isNotEmpty()) {
            val echoed = echo.models.map { "${it.id}@${it.api.orEmpty()}" }.sorted()
            if (echoed != submitted.modelKeys) return false
            // 逐模型比回显。本次提交了具体值就要求回显一致（不同即反证，含回显为
            // null），本次留空则不参与（见 KDoc 的四方口径）；恒下发的字段直接比。
            val echoedModels = echo.models.associate {
                "${it.id}@${it.api.orEmpty()}" to ProviderModelEcho.of(it)
            }
            for ((key, sent) in submitted.modelEchoes) {
                val back = echoedModels[key] ?: continue // 回显里认不出这个模型 → 不反证
                if (sent.name != null && sent.name != back.name) return false
                if (sent.contextWindow != null && sent.contextWindow != back.contextWindow) return false
                if (sent.maxTokens != null && sent.maxTokens != back.maxTokens) return false
                if (sent.reasoning != back.reasoning) return false
                if (sent.inputs != back.inputs) return false
                if (sent.baseUrlEdited && sent.baseUrl != back.baseUrl) return false
                if (!compatEchoMatches(sent.compat, back.compat)) return false
            }
        }
        return true
    }

    /**
     * compat 回显是否与本次提交相符：**只在本次提交了 compat 时判定**。
     *
     * 依据是网关 `put` 的合并口径（`pi-store.ts`，2026-10-02 实读，并由
     * `ProviderCompatGatewayTest` 在真实网关上逐条跑过）：
     * `combined = {...宿主私有键, ...phoneCompat}`。所以当手机对这一层是权威的
     * （草稿里有值/清空），落盘后**手机管理的键恰好等于本次提交的键集**，值也一致；
     * 宿主私有键不会出现在回显里（`toWireModel` 用同一个 `normalizeCompat` 过滤），
     * 因此回显**不会**比提交多出手机管理的键，可以要求严格相等。
     *
     * 本次没碰这一层（`sent.compat == null`）时不参与比对：那时回显是网关保留的旧值，
     * 要求相等会把真实成功判成别人的回执。
     *
     * 覆盖的是最容易漏的一条窄窗口：第一次保存超时/被取消后，用户**只改 compat** 再保存。
     * 上一条回执迟到时，除 compat 外的字段逐字相同，没有别的依据能认出它不是这一次的——
     * 这条比对就是那唯一的依据。
     */
    fun compatEchoMatches(
        sent: Map<String, String?>?,
        back: Map<String, String?>?,
    ): Boolean {
        // 本次没碰这一层：回显是网关保留的旧值，不参与判定。
        if (sent == null) return true
        return sent.filterValues { it != null } == back.orEmpty()
    }

    /**
     * 换协议会静默丢掉哪些 compat 项：[ProviderModelDraft.switchApi] 只保留新协议键集
     * 里的项，老协议下配过、新协议里没有的键就落盘丢了。只算**真有值**的键
     * （null = 默认不写入，丢了没有损失），UI 在应用前把损失亮出来确认。
     */
    fun compatLoss(row: ProviderModelDraft, nextApi: String): List<String> {
        if (row.api == nextApi) return emptyList()
        val kept = PROVIDER_COMPAT_KEYS_BY_API[nextApi].orEmpty().toSet()
        return row.compat
            .filterValues { it != null }
            .keys
            .filterNot { it in kept }
            .sorted()
    }
}

/** [FineCatProviderForm.appendPulled] 的结果：新行 + 新增/跳过计数（回执用）。 */
internal data class PullAppend(
    val rows: List<ProviderModelDraft>,
    val added: Int,
    val skipped: Int,
)

/**
 * 一次 provider-put 提交的可核对特征（[FineCatProviderForm.submitFingerprint] 的产物）。
 * 只含用户可控且网关会原样回显的字段，用于把「迟到的上一份回执」和「这一次的成功」分开。
 */
internal data class ProviderSubmitFingerprint(
    val providerId: String?,
    val name: String?,
    val baseUrl: String?,
    val modelKeys: List<String>,
    /** 模型 `id@api` → 该模型本次提交里会被网关原样回显的那几项。 */
    val modelEchoes: Map<String, ProviderModelEcho> = emptyMap(),
)

/**
 * 一个模型本次提交里**网关会原样回显**的那几项（[ProviderSubmitFingerprint] 的一格）。
 *
 * - [name] / [contextWindow] / [maxTokens]：表单留空 → wire 上**不下发** → 网关按合并
 *   语义保留旧值 → 回显多半是旧值。null 只表示「本次没提交」，所以这三项只能
 *   「本次非空才比」，不能反过来要求回显也为空。
 * - [reasoning] / [inputs]：客户端**无条件下发**（`GatewayProtocol.providerPut`），
 *   网关原样落盘、原样回显，没有 null 歧义，直接比即可。回填时表单已经在消费这两个
 *   值（`ProviderModelDraft.from` 把回显的 `reasoning` / `input` 变成开关初值），
 *   纳入比对不引入新的「网关一定回这两个字段」的假设。
 *
 * - [compat]：**本次提交了才比**，而且要求逐键严格相等（见 [compatEchoMatches]）。
 *   本次没碰这一层时为 null，不参与比对——那时回显是网关保留的旧值。
 *
 * 不纳入的是 `thinkingLevelMap`：网关会**归一**（只保留 pi 的七个档，且值为空串的条目被
 * 丢弃），回显可能与提交不同形状，拿它判否会误伤真实成功。那一层的归属要等网关有独立的
 * 显式标识再说。
 */
internal data class ProviderModelEcho(
    val name: String?,
    val contextWindow: Long?,
    val maxTokens: Long?,
    val reasoning: Boolean,
    val inputs: List<String>,
    /** 本次下发的手机可编辑 compat（null = 本次没碰这一层，不参与比对）。 */
    val compat: Map<String, String?>? = null,
    val baseUrl: String? = null,
    val baseUrlEdited: Boolean = false,
) {
    companion object {
        fun of(model: ProviderModelUpsert): ProviderModelEcho = ProviderModelEcho(
            name = model.name?.trim()?.takeIf(String::isNotEmpty),
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            reasoning = model.reasoning,
            compat = model.compat?.mapValues { (_, value) -> value }?.filterValues { it != null },
            baseUrl = model.baseUrl,
            baseUrlEdited = model.baseUrlEdited,
            // 两侧都排序：回显的 input 顺序取决于网关收到的数组顺序，比的是集合不是顺序。
            inputs = buildList {
                add("text")
                if (model.supportsImages) add("image")
            }.sorted(),
        )

        fun of(model: GatewayProviderModel): ProviderModelEcho = ProviderModelEcho(
            name = model.name?.trim()?.takeIf(String::isNotEmpty),
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            reasoning = model.reasoning,
            inputs = model.input.sorted(),
            compat = wireCompatOf(model.compat),
            baseUrl = model.baseUrl,
        )

        /**
         * wire 上回显的 compat → 与提交侧同形状的 `key -> "true"/"false"/枚举值`。
         *
         * 布尔回显翻成字符串与提交侧对齐（提交侧也是字符串）；数字/对象不是这一层能表达
         * 的形态（网关 `normalizeCompat` 会拒），直接丢掉而不是编个值去比。
         */
        fun wireCompatOf(compat: JsonValue?): Map<String, String?>? =
            compat?.objectValue?.mapNotNull { (key, value) ->
                when (value) {
                    is JsonValue.BooleanValue -> key to value.value.toString()
                    is JsonValue.StringValue -> key to value.value
                    else -> null
                }
            }?.toMap()
    }
}

/** [FineCatProviderForm.parseModels] 的结果：错误互斥于可提交行。 */
internal data class ParsedProviderModels(
    val rows: List<ProviderModelUpsert> = emptyList(),
    val error: String? = null,
)
