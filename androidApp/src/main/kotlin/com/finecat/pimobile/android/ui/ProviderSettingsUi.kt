package com.finecat.pimobile.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.PI_THINKING_LEVELS
import com.finecat.pimobile.shared.gateway.PROVIDER_API_CHOICES
import com.finecat.pimobile.shared.gateway.PROVIDER_COMPAT_BOOLEAN_KEYS
import com.finecat.pimobile.shared.gateway.PROVIDER_COMPAT_ENUMS
import com.finecat.pimobile.shared.gateway.PROVIDER_COMPAT_KEYS_BY_API
import com.finecat.pimobile.shared.gateway.PROVIDER_DEFAULT_API
import com.finecat.pimobile.shared.gateway.ProviderModelUpsert
import com.finecat.pimobile.shared.gateway.requireValidProviderUpsert
import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.protocol.GatewayProviderModel
import com.finecat.pimobile.shared.protocol.JsonValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 设置页「模型 Provider」分区：读取/新建/编辑/删除 pi models.json 的 provider。 */
@Composable
internal fun ProviderManagementSection(stateHolder: AndroidSharedStateHolder) {
    val connected = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED
    var showEditor by remember { mutableStateOf(false) }
    var editingExisting by remember { mutableStateOf<GatewayProvider?>(null) }

    SettingsSection(
        title = "模型 Provider",
        footer = "列表即电脑端 pi 的 models.json；API Key 不会回传，只显示是否已配置。" +
            "保存后 provider 会自动收录进模型菜单。"
    ) {
        if (stateHolder.providerListIsLoading && stateHolder.providers.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PiSpinner(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在读取电脑端 models.json…",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
                )
            }
        }
        val providers = stateHolder.providers
        providers.forEachIndexed { index, provider ->
            ProviderRow(provider) {
                editingExisting = provider
                showEditor = true
            }
            if (index != providers.lastIndex) SettingsDivider()
        }
        if (providers.isNotEmpty()) SettingsDivider()
        SettingsActionRow("＋ 新建 Provider", enabled = connected) {
            editingExisting = null
            showEditor = true
        }
        stateHolder.providerResult?.let { result ->
            Text(
                result,
                color = providerResultColor(result),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
    if (showEditor) {
        ProviderEditorDialog(
            stateHolder = stateHolder,
            existing = editingExisting,
            onDismiss = { showEditor = false }
        )
    }
}

@Composable
private fun ProviderRow(provider: GatewayProvider, onClick: () -> Unit) {
    val inactive = !provider.active
    // 测过且连不通的 provider 灰显（其模型也不在模型选择器里，由网关侧过滤保证）。
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (inactive) 0.38f else 1f)
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp)
    ) {
        Text(
            provider.name?.takeIf(String::isNotBlank) ?: provider.id,
            color = titleColor,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            listOfNotNull(
                provider.baseUrl?.takeIf(String::isNotBlank),
                "${provider.models.size} 个模型",
                if (provider.apiKeySet) "Key 已配置" else "Key 未配置",
                if (inactive) "未连通（模型不出现在选择器）" else null
            ).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (inactive) 0.32f else 0.48f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // 失败原因直接展示在行内：用户不用点进去猜（如“返回的是网页而不是 API 响应”）。
        provider.status?.takeIf { !it.ok }?.error?.takeIf(String::isNotBlank)?.let { reason ->
            Spacer(Modifier.height(2.dp))
            Text(
                reason,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun providerResultColor(message: String) = when {
    message.startsWith("Provider 已保存") || message.startsWith("Provider 已删除") -> PiColors.Success
    message.startsWith("已保存，但") -> Color(0xFFE8A03C)
    else -> MaterialTheme.colorScheme.error
}

/** 表单配置指纹：把“测试通过”绑定到具体一份配置。只含影响连通性的字段
 *  （Provider 默认地址 / apiKey / 每个模型的 id+api+地址）；显示名、上下文窗口等改动不影响指纹，
 *  用户仅改模型名时不必重测。 */
internal fun providerFingerprint(
    baseUrl: String,
    apiKey: String,
    models: List<ProviderModelDraft>
): String = buildString {
    append(baseUrl.trim()).append('\n').append(apiKey.trim()).append('\n')
    models.forEach {
        append(it.id.trim()).append(':').append(it.api).append(';')
        val url = it.baseUrl.trim()
        append(url.length).append(':').append(url).append(';')
    }
}

internal data class ProviderModelDraft(
    val id: String = "",
    val name: String = "",
    val contextWindow: String = "",
    val maxTokens: String = "",
    val reasoning: Boolean = true,
    val imageOn: Boolean = false,
    /** 模型级 wire 协议：pi 运行时只按 model.api 分派流实现，provider 级 api 只是宿主兜底。 */
    val api: String = PROVIDER_DEFAULT_API,
    /** pi 档位 → 表单字符串："" 沿用默认，"-" 该档位不支持，其余为映射目标。 */
    val thinkingMap: Map<String, String> = emptyMap(),
    /** compat 表单态：null = 未设置（不写入 wire），"true"/"false" = 布尔键，其余 = 枚举值。 */
    val compat: Map<String, String?> = emptyMap(),
    val baseUrl: String = "",
    /** Only an explicit edit sends the override; blank + touched restores inheritance. */
    val baseUrlTouched: Boolean = false,
    /**
     * 本次是否动过 compat（`DEC-F09-COMPAT`）。
     *
     * false = **字段整个不下发**，网关按合并语义原样保留电脑上那一层；true = 手机对这一层
     * 是权威的：草稿里没有的键会被清掉，整表为空则清空所有手机管理的键。
     *
     * 不能省掉这个标记：用户只是改了显示名却把 compat 原样回传，等于把「手机上看到什么
     * 就写什么」当成授权，一旦回显缺项（例如网关归一后不再回某个键）就会静默删掉电脑上
     * 配好的值。保留「没动过」这一态是唯一能保证不误删的办法。
     */
    val compatTouched: Boolean = false,
) {
    override fun toString(): String = "ProviderModelDraft(id=$id, api=$api, baseUrl=<redacted>)"
    companion object {
        /** [providerApi] 是 models.json 里 provider 级的 api，只用来回显宿主手写的继承值。 */
        fun from(model: GatewayProviderModel, providerApi: String?): ProviderModelDraft = ProviderModelDraft(
            id = model.id,
            api = model.api?.takeIf(String::isNotBlank)
                ?: providerApi?.takeIf(String::isNotBlank)
                ?: PROVIDER_DEFAULT_API,
            name = model.name.orEmpty(),
            baseUrl = model.baseUrl.orEmpty(),
            contextWindow = model.contextWindow?.toString().orEmpty(),
            maxTokens = model.maxTokens?.toString().orEmpty(),
            reasoning = model.reasoning,
            imageOn = "image" in model.input,
            thinkingMap = model.thinkingLevelMap.orEmpty().mapValues { (_, target) ->
                if (target == null) "-" else target
            },
            compat = model.compat?.objectValue.orEmpty().mapValues { (_, value) ->
                when (value) {
                    is JsonValue.BooleanValue -> if (value.value) "true" else "false"
                    is JsonValue.StringValue -> value.value
                    else -> null
                }
            }
        )
    }

    /**
     * 换协议：只保留新协议键集内的 compat 项（否则会把对当前 API 无意义的键落盘丢掉）。
     *
     * [compatTouched] **只在真有键被过滤掉时才置真**：那是一次有意的有损删除，字段若照旧
     * 不下发，网关合并会把旧键整个留下，UI 上确认过的「丢 N 项」就成了假话。反过来，
     * 没有键被丢掉时保持「未触碰」，是为了不把「切了个协议」升级成「授权手机重写这一层」——
     * 陈旧草稿（开着卡的时候电脑上又新增了键）在无损失切换下本该原样带回。
     */
    fun switchApi(next: String): ProviderModelDraft {
        val kept = PROVIDER_COMPAT_KEYS_BY_API[next].orEmpty().toSet()
        val filtered = compat.filterKeys { it in kept }
        val dropped = filtered.size != compat.size
        return copy(
            api = next,
            compat = filtered,
            compatTouched = compatTouched || dropped,
        )
    }
}

@Composable
private fun ProviderEditorDialog(
    stateHolder: AndroidSharedStateHolder,
    existing: GatewayProvider?,
    onDismiss: () -> Unit
) {
    val isEdit = existing != null
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf(existing?.id.orEmpty()) }
    var displayName by remember { mutableStateOf(existing?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    // 协议属于模型；provider 级 api 仅作为编辑既有 provider 时的预填继承值与上游拉取口径。
    val inheritedApi = existing?.api?.takeIf(String::isNotBlank)
    var models by remember {
        mutableStateOf(
            existing?.models?.map { ProviderModelDraft.from(it, inheritedApi) }
                ?: listOf(ProviderModelDraft(api = inheritedApi ?: PROVIDER_DEFAULT_API))
        )
    }
    // 默认全部折叠（-1）：进弹窗先看到完整的模型清单，不用先收起第一个。
    var expandedIndex by remember { mutableStateOf(-1) }
    var compatExpandedIndex by remember { mutableStateOf(-1) }
    var inlineMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // “必须手动测试通过才能保存”：记录**哪份表单值**测通过了。表单变更（指纹变）
    // 即失效；编辑既有 provider 且未改动、且上次网关侧测试通过时，允许直接保存。
    var testedFingerprint by remember { mutableStateOf<String?>(null) }
    // 点击测试时把当时指纹暂存：结果回来绑**那时**的配置，而不是绑定回来那一刻的最新指纹
    // （否则测试在途改字段会把旧结论洗成新配置的通行证）。
    var pendingTestFingerprint by remember { mutableStateOf<String?>(null) }
    val fingerprint = providerFingerprint(baseUrl, apiKey, models)
    val initialFingerprint = remember { fingerprint }
    LaunchedEffect(fingerprint) {
        if (testedFingerprint != null && testedFingerprint != fingerprint) testedFingerprint = null
    }
    LaunchedEffect(stateHolder.providerTestResult) {
        val outcome = stateHolder.providerTestResult ?: return@LaunchedEffect
        // 归属：新建（providerId 为空）对应 providerId==null 的结论。
        if (outcome.providerId != providerId.takeIf(String::isNotBlank)) return@LaunchedEffect
        val pending = pendingTestFingerprint
        pendingTestFingerprint = null
        // 配置类失败不允许保存（transient 只是上游临时问题，不阻止）。
        if (outcome.allowsSave && pending != null) testedFingerprint = pending
    }
    val testCleared = fingerprint == initialFingerprint && existing?.status?.ok == true
    // 武装 8s 自动解除：在对话框里改字段/放下手机回来再点，不会误触即删。
    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(8_000)
            confirmDelete = false
        }
    }
    /** 上游拉取的待新增模型 id（diff 过本地已有）；null = 勾选区收起。 */
    var fetchCandidates by remember { mutableStateOf<List<String>?>(null) }
    var fetchSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(stateHolder.providerModelsResult) {
        val outcome = stateHolder.providerModelsResult ?: return@LaunchedEffect
        // 一次性消费：不清掉的话，关掉弹窗再打开会立刻重放上次的失败文案/勾选区。
        stateHolder.consumeProviderModelsResult()
        // 归属同 provider-test：新建（providerId 为空）对应 providerId==null 的结论。
        if (outcome.providerId != providerId.takeIf(String::isNotBlank)) return@LaunchedEffect
        if (!outcome.ok) {
            inlineMessage = (outcome.error ?: "拉取失败") to true
            return@LaunchedEffect
        }
        // 语义是「新增」：已配置模型原样保留，只列出本地没有的。
        val fresh = outcome.ids.filter { id -> models.none { it.id == id } }
        if (fresh.isEmpty()) {
            inlineMessage = "上游没有新模型（已配置 ${models.size} 个）" to false
        } else {
            fetchCandidates = fresh
            fetchSelected = fresh.toSet()
        }
    }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            // 独立窗口不跨窗口采样：strong 玻璃底保证可读。
            color = piGlassTint(strong = true),
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().focusRequester(rootFocus)
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(6.dp)) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (isEdit) "编辑 Provider" else "新建 Provider",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(shape = RoundedCornerShape(6.dp), onClick = {
                        // 硬门槛：本份配置必须刚测通过（或上次已通过且未改动）才能保存。
                        if (testedFingerprint != fingerprint && !testCleared) {
                            inlineMessage =
                                "请先点击「测试连接」，连接正常（或仅上游暂时不可用）后才能保存" to true
                            return@TextButton
                        }
                        val failure = submit(stateHolder, providerId, displayName, baseUrl, apiKey, models)
                        inlineMessage = failure?.let { it to true }
                        // 保存成功即关弹窗；网关侧不再自动测试（避免覆盖手测结论）。
                        if (failure == null) onDismiss()
                    }) {
                        Text("保存", fontWeight = FontWeight.SemiBold)
                    }
                }
                HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        .padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    inlineMessage?.let { (message, isError) ->
                        Text(
                            message,
                            color = if (isError) MaterialTheme.colorScheme.error else PiColors.Success,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    // 连接测试结果：只展示当前编辑对象的结论（跨 provider 不残留）。
                    // 失败原因文案已由网关带上实际 URL，不再重复拼接。
                    stateHolder.providerTestResult
                        ?.takeIf { it.providerId == providerId.takeIf(String::isNotBlank) }
                        ?.let { outcome ->
                            Text(
                                if (outcome.ok) {
                                    outcome.summary
                                } else if (outcome.allowsSave) {
                                    "${outcome.summary}（上游临时问题，不代表配置错误，仍可保存）"
                                } else {
                                    "${outcome.summary}\n配置有问题，请修正后重新测试再保存"
                                },
                                color = if (outcome.allowsSave) PiColors.Success else MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    // 测试连接：用 pi 的方式真发一条最小请求；id 非空时缺省字段走
                    // 已存的 key/baseUrl，编辑场景不用重填 apiKey。旧网关未宣告能力时隐藏。
                    if (stateHolder.supportsRequest("provider-test")) {
                        TextButton(
                            shape = RoundedCornerShape(6.dp),
                            onClick = {
                                // 暂存点击时的指纹：结果回来时绑定这份配置。
                                pendingTestFingerprint = fingerprint
                                stateHolder.testProvider(
                                    id = providerId.takeIf { it.isNotBlank() },
                                    baseUrl = baseUrl,
                                    apiKey = apiKey,
                                    model = models.firstOrNull { it.id.isNotBlank() }?.id,
                                    // 协议必传：新建 anthropic/google 时网关无已存值可回退。
                                    api = models.firstOrNull { it.id.isNotBlank() }?.api
                                )
                            },
                            enabled = !stateHolder.providerTestIsLoading && models.any { it.id.isNotBlank() }
                        ) {
                            Text(if (stateHolder.providerTestIsLoading) "测试中…" else "测试连接")
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "基础信息",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        InlineLabelField(
                            label = "Provider ID",
                            value = providerId,
                            onValueChange = { providerId = it.trim(' ').replace("/", "") },
                            enabled = !isEdit
                        )
                        InlineLabelField(
                            label = "显示名",
                            value = displayName,
                            onValueChange = { displayName = it },
                            placeholder = "可选"
                        )
                        InlineLabelField(
                            label = "Base URL",
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            placeholder = "https://api.example.com/v1"
                        )
                        InlineLabelField(
                            label = "API Key",
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = if (isEdit && existing?.apiKeySet == true) "已配置 · 留空保留" else "sk-…",
                            visualMasked = !showApiKey,
                            trailing = {
                                Text(
                                    if (showApiKey) "隐藏" else "显示",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable { showApiKey = !showApiKey }
                                )
                            }
                        )
                        Text(
                            "协议在每个模型里单独选：同一个 provider 可以混用不同 API。",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "模型（${models.size}）",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.weight(1f))
                        if (stateHolder.providerModelsIsLoading) {
                            PiSpinner(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "拉取中…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        } else {
                            Text(
                                "⟳ 拉取模型",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable {
                                    // 走网关拉取：手机是凭证盲的，编辑已有 provider 时表单里
                                    // 没有 apiKey，直连上游必然 401（用户实测"测试连接能过、
                                    // 拉取模型报 401"——前者走网关、后者直连）。网关按"表单值
                                    // 优先、缺省回退 models.json 已存值"处理。
                                    stateHolder.pullProviderModels(
                                        id = providerId.takeIf { it.isNotBlank() },
                                        baseUrl = baseUrl,
                                        apiKey = apiKey.trim().takeIf(String::isNotEmpty),
                                        api = models.firstOrNull()?.api ?: PROVIDER_DEFAULT_API
                                    )
                                }
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "＋ 添加模型",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable {
                                models = models + ProviderModelDraft(
                                    api = models.firstOrNull()?.api ?: PROVIDER_DEFAULT_API
                                )
                                expandedIndex = models.lastIndex
                            }
                        )
                    }
                    Text(
                        "拉取只列出本地没有的模型，勾选后新增；已配置模型不受影响（拉取走网关，Key 沿用已保存的值，未重新填写时不需要重填）。",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    fetchCandidates?.let { candidates ->
                        FetchCandidatesSection(
                            candidates = candidates,
                            selected = fetchSelected,
                            onToggle = { id ->
                                fetchSelected = if (id in fetchSelected) fetchSelected - id else fetchSelected + id
                            },
                            onToggleAll = { all ->
                                fetchSelected = if (all) candidates.toSet() else emptySet()
                            },
                            onAdd = {
                                val picked = candidates.filter { it in fetchSelected }
                                if (picked.isNotEmpty()) {
                                    val firstNew = models.size
                                    val pickedApi = models.lastOrNull()?.api ?: PROVIDER_DEFAULT_API
                                    models = models + picked.map {
                                        ProviderModelDraft(id = it, reasoning = true, api = pickedApi)
                                    }
                                    expandedIndex = firstNew
                                    inlineMessage = "已新增 ${picked.size} 个模型（默认参数，可展开修改）" to false
                                }
                                fetchCandidates = null
                                fetchSelected = emptySet()
                            },
                            onDismiss = {
                                fetchCandidates = null
                                fetchSelected = emptySet()
                            }
                        )
                    }
                    models.forEachIndexed { index, draft ->
                        ProviderModelCard(
                            index = index,
                            draft = draft,
                            expanded = expandedIndex == index,
                            compatExpanded = compatExpandedIndex == index,
                            onExpand = { expandedIndex = index },
                            onCompatExpand = {
                                compatExpandedIndex = if (compatExpandedIndex == index) -1 else index
                            },
                            onChange = { models = models.toMutableList().apply { set(index, it) } },
                            onRemove = {
                                models = models.filterIndexed { i, _ -> i != index }
                                // 删掉展开项后不自动展开别的，回到全折叠。
                                if (expandedIndex >= models.size) expandedIndex = -1
                            }
                        )
                    }
                    Text(
                        "保存后 provider 会写入电脑端 models.json 并自动收录进 enabledModels，模型菜单即刻可用。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
                if (isEdit) {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val label = if (confirmDelete) "再点一次确认删除「${existing.id}」" else "删除此 Provider"
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable {
                                if (confirmDelete) {
                                    if (stateHolder.gatewayState.connection != GatewayConnectionState.CONNECTED) {
                                        // 未连接时删请求发不出去：留在对话框内提示，不 dismiss。
                                        inlineMessage = "请先连接 Pi 网关" to true
                                        confirmDelete = false
                                    } else {
                                        stateHolder.deleteProvider(existing.id)
                                        onDismiss()
                                    }
                                } else {
                                    confirmDelete = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun submit(
    stateHolder: AndroidSharedStateHolder,
    providerId: String,
    displayName: String,
    baseUrl: String,
    apiKey: String,
    models: List<ProviderModelDraft>
): String? {
    val parsedModels = models.map { draft ->
        // 空白=缺省（协议可空）；非空非法才拒整个表单。
        val contextWindow = draft.contextWindow.trim().takeIf(String::isNotEmpty)?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 }
                ?: return "上下文窗口必须是正整数：${draft.id.ifBlank { "模型" }}"
        }
        val maxTokens = draft.maxTokens.trim().takeIf(String::isNotEmpty)?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 }
                ?: return "最大输出必须是正整数：${draft.id.ifBlank { "模型" }}"
        }
        ProviderModelUpsert(
            id = draft.id.trim(),
            name = draft.name.trim().takeIf(String::isNotEmpty),
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            reasoning = draft.reasoning,
            supportsImages = draft.imageOn,
            // 协议逐模型显式下发：provider 级 api 不发送（网关语义 absent = 清除）。
            api = draft.api,
            thinkingLevelMap = draft.thinkingMap
                .filterValues(String::isNotBlank)
                .mapValues { (_, target) -> if (target == "-") null else target },
            compat = draft.compat
        )
    }
    val failure = runCatching {
        requireValidProviderUpsert(
            providerId.trim(),
            baseUrl.trim().takeIf(String::isNotEmpty),
            apiKey.trim().takeIf(String::isNotEmpty),
            parsedModels,
            displayName.trim().takeIf(String::isNotEmpty),
        )
        null
    }.getOrElse { it.message ?: "表单校验失败" }
    if (failure != null) return failure
    stateHolder.saveProvider(
        id = providerId,
        baseUrl = baseUrl.trim().takeIf(String::isNotEmpty),
        apiKey = apiKey.trim().takeIf(String::isNotEmpty),
        models = parsedModels,
        // 以前这里收了“显示名”但不下发，改了名字等于没改（与 FineCat 表单同口径修好）。
        name = displayName.trim().takeIf(String::isNotEmpty),
    )
    return null
}

/** 行内标签字段：label 常驻行首，点击即输入，标签与输入不再分两行。 */
@Composable
private fun InlineLabelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
    visualMasked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .background(piGlassTint(), RoundedCornerShape(12.dp))
            .border(piGlassOutline(), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.55f else 0.35f)
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).heightIn(min = 24.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
            visualTransformation = if (visualMasked) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                    trailing?.invoke()
                }
            }
        )
    }
}

@Composable
private fun ProtocolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Text(
        label,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier
            .clickable(onClick = onClick)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(9.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun ProviderModelCard(
    index: Int,
    draft: ProviderModelDraft,
    expanded: Boolean,
    compatExpanded: Boolean,
    onExpand: () -> Unit,
    onCompatExpand: () -> Unit,
    onChange: (ProviderModelDraft) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(piGlassTint(), RoundedCornerShape(12.dp))
            .border(piGlassOutline(), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                draft.id.ifBlank { "模型 ${index + 1}" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onExpand() }
            )
            Text(
                "删除",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onRemove)
            )
        }
        if (!expanded) {
            Text(
                listOfNotNull(
                    draft.api,
                    draft.contextWindow.ifBlank { null }?.let { "上下文 $it" },
                    if (draft.reasoning) "推理" else null,
                    if (draft.imageOn) "图片" else null
                ).joinToString(" · ").ifEmpty { "点按展开填写" },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
            )
        } else {
            InlineLabelField(
                label = "模型 ID",
                value = draft.id,
                onValueChange = { onChange(draft.copy(id = it.trim(' ').replace("/", ""))) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "协议",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                PROVIDER_API_CHOICES.chunked(2).forEach { rowChoices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowChoices.forEach { choice ->
                            ProtocolChip(
                                label = choice,
                                selected = draft.api == choice,
                                onClick = { onChange(draft.switchApi(choice)) }
                            )
                        }
                    }
                }
                Text(
                    "compat 键集跟随这里的协议；切换会清掉新协议下不适用的项。",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
            InlineLabelField(
                label = "显示名",
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it)) },
                placeholder = "可选"
            )
            InlineLabelField(
                label = "上下文窗口",
                value = draft.contextWindow,
                onValueChange = { onChange(draft.copy(contextWindow = it)) },
                keyboardType = KeyboardType.Number,
                placeholder = "例如 200000"
            )
            InlineLabelField(
                label = "最大输出",
                value = draft.maxTokens,
                onValueChange = { onChange(draft.copy(maxTokens = it)) },
                keyboardType = KeyboardType.Number,
                placeholder = "例如 65536"
            )
            SwitchRow("支持推理", draft.reasoning) { onChange(draft.copy(reasoning = it)) }
            SwitchRow("支持图片", draft.imageOn) { onChange(draft.copy(imageOn = it)) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "思考强度映射",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                PI_THINKING_LEVELS.forEach { level ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 30.dp)) {
                        Text(level, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                        BasicTextField(
                            value = draft.thinkingMap[level].orEmpty(),
                            onValueChange = { value ->
                                onChange(draft.copy(thinkingMap = draft.thinkingMap + (level to value.trim())))
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            decorationBox = { innerTextField ->
                                Box {
                                    if ((draft.thinkingMap[level] ?: "").isEmpty()) {
                                        Text(
                                            "默认",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
                Text(
                    "留空 = 沿用 provider 默认；填 - = 该档位不支持；填值 = 传给 provider 的推理实参。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
            CompatSection(
                api = draft.api,
                compat = draft.compat,
                expanded = compatExpanded,
                onToggleExpand = onCompatExpand,
                onChange = { onChange(draft.copy(compat = it)) }
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** compat 每键的中文解释（含推荐口径）。 */
/**
 * compat 键的一句说明。取自 pi-ai `ModelCompat` 各字段的 JSDoc 口径
 * （`OpenAICompletionsCompat` 等），旧 Provider 弹窗与新 FineCat 模型卡共用这一份，
 * 免得两套 UI 对同一个键给出不同解释。
 */
internal val PROVIDER_COMPAT_HINTS: Map<String, String> = mapOf(
    "supportsDeveloperRole" to "用 developer 角色替代 system；OpenAI 新接口默认用，多数中转建议关",
    "supportsReasoningEffort" to "是否发 reasoning_effort 参数；接推理模型建议开",
    "thinkingFormat" to "推理参数格式：DeepSeek 系选 deepseek、智谱选 zai、OpenRouter 选 openrouter",
    "requiresReasoningContentOnAssistantMessages" to "助手消息须回传空 reasoning_content；DeepSeek 系建议开",
    "supportsStore" to "请求是否带 store 字段；中转/第三方建议关",
    "maxTokensField" to "最大输出字段名：旧接口 max_tokens，OpenAI 新接口 max_completion_tokens",
    "requiresThinkingAsText" to "思考块转 <thinking> 文本；非标推理后端建议开",
    "requiresAssistantAfterToolResult" to "工具结果后需补一条 assistant 消息的兼容要求",
    "requiresToolResultName" to "工具结果须带 name 字段时开",
    "thinkingTokenBudgetField" to "思考预算字段：vLLM 用 thinking_token_budget，Qwen 用 thinking_budget",
    "supportsThinkingTokenBudget" to "是否支持思考 token 预算",
    "supportsUsageInStreaming" to "流式响应返回 usage；多数网关保持开",
    "supportsFinishReason" to "流式是否含 finish_reason；缺失时 pi 自动推断",
    "supportsStrictMode" to "工具定义支持 strict 字段",
    "supportsOpenAIGrammarTools" to "支持 Lark/regex 语法的自定义工具",
    "sendSessionAffinityHeaders" to "会话亲和头；按副本路由缓存的中转建议开",
    "supportsLongCacheRetention" to "支持 24h 长缓存",
    "zaiToolStream" to "z.ai 专用：顶层 tool_stream",
    "cacheControlFormat" to "提示词缓存标记格式（anthropic）",
    "deferredToolsMode" to "延迟工具序列化模式（kimi）",
    "sessionAffinityFormat" to "会话亲和头格式：openai / openai-nosession / openrouter",
    "supportsTemperature" to "是否接受 temperature；Claude Opus 4.7+ 需关",
    "supportsStrictTools" to "Anthropic 严格工具 schema",
    "supportsCacheControlOnTools" to "工具定义支持 cache_control；Fireworks 等需关",
    "supportsEagerToolInputStreaming" to "支持工具 eager_input_streaming",
    "supportsToolReferences" to "支持 tool_reference 延迟工具",
    "forceAdaptiveThinking" to "强制 adaptive thinking；Claude 4.5+/自适应上游开",
    "allowEmptySignature" to "空 thinking 签名按原文重放；中转 Claude 建议开",
    "supportsAdditionalTools" to "支持消息锚点 additional_tools",
    "supportsToolSearch" to "支持客户端工具搜索",
    "supportsExplicitPromptCacheMode" to "GPT-5.6+ 显式提示词缓存"
)

/** 拉取结果勾选区：只列本地没有的模型，勾选后以默认参数追加。 */
@Composable
private fun FetchCandidatesSection(
    candidates: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(piGlassTint())
            .border(piGlassOutline(), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "上游可新增（${candidates.size}）",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (selected.size == candidates.size) "全不选" else "全选",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onToggleAll(selected.size != candidates.size) }
            )
        }
        Column(
            Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
        ) {
            candidates.forEach { id ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(id) }
                ) {
                    Checkbox(checked = id in selected, onCheckedChange = { onToggle(id) })
                    Text(
                        id,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "收起",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "新增 ${selected.size} 个模型",
                color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = selected.isNotEmpty(), onClick = onAdd).padding(6.dp)
            )
        }
    }
}

@Composable
private fun CompatSection(
    api: String,
    compat: Map<String, String?>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onChange: (Map<String, String?>) -> Unit
) {
    val keys = PROVIDER_COMPAT_KEYS_BY_API[api]
    val setCount = compat.count { (_, value) -> value != null }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "兼容性 compat",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                Text(
                    if (expanded) "点按收起" else "已设置 $setCount 项 · 点按展开（未设置的走 pi 默认/自动检测）",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
            Text(
                if (expanded) "收起" else "展开",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp
            )
        }
        if (expanded) {
            if (keys == null) {
                Text(
                    "该协议（$api）pi 不使用 compat，无需设置。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            } else {
                keys.forEach { key ->
                    val value = compat[key]
                    val choices = PROVIDER_COMPAT_ENUMS[key]
                    if (choices != null) {
                        CompatEnumRow(
                            key = key,
                            value = value,
                            hint = PROVIDER_COMPAT_HINTS[key].orEmpty(),
                            choices = choices,
                            onChange = { onChange(compat + (key to it)) }
                        )
                    } else {
                        CompatBooleanRow(
                            key = key,
                            value = value,
                            hint = PROVIDER_COMPAT_HINTS[key].orEmpty(),
                            onChange = { onChange(compat + (key to it)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatBooleanRow(
    key: String,
    value: String?,
    hint: String,
    onChange: (String?) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(key, fontSize = 12.sp, lineHeight = 15.sp)
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }
        CompatSegment("默认", value == null) { onChange(null) }
        CompatSegment("开", value == "true") { onChange("true") }
        CompatSegment("关", value == "false") { onChange("false") }
    }
}

@Composable
private fun CompatEnumRow(
    key: String,
    value: String?,
    hint: String,
    choices: List<String>,
    onChange: (String?) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(key, fontSize = 12.sp, lineHeight = 15.sp)
                if (hint.isNotEmpty()) {
                    Text(
                        hint,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
            CompatSegment("默认", value == null) { onChange(null) }
        }
        BasicTextField(
            value = value.orEmpty(),
            onValueChange = { text -> onChange(text.trim().takeIf(String::isNotEmpty)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isNullOrEmpty()) {
                        Text(
                            choices.first(),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
                        )
                    }
                    innerTextField()
                }
            }
        )
        Text(
            "可选：${choices.joinToString(" / ")}",
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
    }
}

@Composable
private fun CompatSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Text(
        label,
        fontSize = 10.sp,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .padding(start = 5.dp)
            .clickable(onClick = onClick)
            .border(1.dp, borderColor, RoundedCornerShape(7.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}
