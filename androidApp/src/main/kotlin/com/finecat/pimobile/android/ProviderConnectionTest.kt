package com.finecat.pimobile.android

internal data class ProviderTestTarget(
    val modelId: String?,
    val api: String?,
    val modelBaseUrl: String? = null,
    val modelBaseUrlSpecified: Boolean = false,
) {
    override fun toString(): String = "ProviderTestTarget(modelId=$modelId, api=$api, modelBaseUrl=<redacted>)"
}

/** 本次批次中已收到的逐模型结果，仅用于手机表单展示。 */
data class ProviderTestAttempt(
    val modelId: String,
    val ok: Boolean,
    val summary: String,
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
    val api: String? = null,
    val diagnosticCode: String? = null,
    val interrupted: Boolean = false,
)

/** 请求被取消/超时或未受理时停止整批；上游单模型失败则继续下一项。 */
internal data class ProviderProbeReply(
    val outcome: AndroidSharedStateHolder.ProviderTestOutcome,
    val interrupted: Boolean = false,
)

/** 串行使用单模型协议，满足各组所需的成功结果即停止，不把临时失败算成功。 */
internal suspend fun testProviderModelsUntilSuccess(
    targets: List<ProviderTestTarget>,
    onAttempt: (Int, Int, ProviderTestTarget) -> Unit,
    requiredModelGroups: List<Set<String>> = emptyList(),
    probe: suspend (ProviderTestTarget) -> ProviderProbeReply,
): AndroidSharedStateHolder.ProviderTestOutcome {
    require(targets.isNotEmpty())
    var last: AndroidSharedStateHolder.ProviderTestOutcome? = null
    val attempts = mutableListOf<ProviderTestAttempt>()
    targets.forEachIndexed { index, target ->
        onAttempt(index + 1, targets.size, target)
        val reply = probe(target)
        val outcome = reply.outcome
        last = outcome
        val model = target.modelId ?: "默认模型"
        attempts += ProviderTestAttempt(
            model, outcome.ok, outcome.summary, outcome.httpStatus, outcome.latencyMs, target.api,
            outcome.diagnosticCode, reply.interrupted,
        )
        val passed = attempts.filter { it.ok }.map { it.modelId }.toSet()
        if (outcome.ok && requiredModelGroups.all { group -> group.any { it in passed } }) return outcome.copy(
            summary = if (passed.size == 1) "$model 测试通过（已测试 ${index + 1}/${targets.size}）"
                else "${passed.size} 个模型测试通过（已测试 ${index + 1}/${targets.size}）",
            attempts = attempts.toList(),
        )
        if (reply.interrupted) return outcome.copy(
            summary = "测试未完成（${index + 1}/${targets.size} · $model）\n${outcome.summary}",
            attempts = attempts.toList(),
        )
    }
    return requireNotNull(last).copy(
        ok = false,
        summary = if (attempts.none { it.ok }) "已测试 ${targets.size} 个模型，均未通过"
            else "已测试 ${targets.size} 个模型，${attempts.count { !it.ok }} 个未通过",
        attempts = attempts.toList(),
    )
}

/** 只展示稳定诊断；旧网关的原始上游错误可能包含地址或凭据，不直接回显。 */
internal fun providerTestSummary(ok: Boolean, code: String?, httpStatus: Int?): String = when {
    ok -> "连接正常"
    code == "invalid-url" -> "地址格式不正确"
    code == "missing-model" -> "请填写模型 ID"
    code == "unsupported-api" -> "当前协议不支持连接测试"
    code == "timeout" -> "请求超时"
    code == "dns-failed" -> "无法解析服务地址"
    code == "tls-failed" -> "安全连接失败"
    code == "connection-refused" -> "服务拒绝连接"
    code == "network-error" -> "网络连接失败"
    code == "redirect" -> "地址发生重定向"
    code == "html-response" -> "地址返回了网页"
    code == "invalid-response" -> "未收到有效模型响应"
    code == "model-unavailable" -> "模型不存在或无访问权限"
    code == "insufficient-quota" -> "上游额度不足"
    httpStatus == 401 -> "认证被拒"
    httpStatus == 403 -> "请求被拒"
    httpStatus == 404 -> "接口或模型不存在"
    httpStatus == 402 -> "上游要求付费"
    httpStatus == 429 -> "请求受限"
    httpStatus != null && httpStatus >= 500 -> "上游服务异常"
    httpStatus != null && httpStatus >= 400 -> "上游拒绝请求"
    else -> "连接测试未通过"
}

internal fun providerTestHint(code: String?, httpStatus: Int?): String = when {
    code == "invalid-url" -> "检查 Base URL 的格式。"
    code == "missing-model" || code == "model-unavailable" -> "检查模型 ID 和该 Key 的模型权限。"
    code == "unsupported-api" -> "请确认所选协议；当前探针无法验证此协议。"
    code == "timeout" -> "上游未在时限内返回，可稍后重试。"
    code == "dns-failed" -> "检查地址拼写和网关主机的 DNS。"
    code == "tls-failed" -> "检查上游证书与网关主机的系统时间。"
    code == "connection-refused" -> "检查上游服务和端口是否开放。"
    code == "network-error" -> "检查网关主机到上游的网络。"
    code in setOf("redirect", "html-response", "invalid-response") -> "检查模型协议和 Base URL 路径是否匹配。"
    code == "insufficient-quota" || httpStatus == 402 -> "检查上游账户额度或付费状态。"
    httpStatus == 401 -> "检查 API Key 是否有效。"
    httpStatus == 403 -> "检查模型权限或上游访问限制。"
    httpStatus == 404 -> "检查模型 ID、协议与 Base URL 路径。"
    httpStatus == 429 -> "检查上游限额，稍后重试。"
    httpStatus != null && httpStatus >= 500 -> "上游暂时异常，可稍后重试。"
    httpStatus != null && httpStatus >= 400 -> "检查模型 ID、协议及请求配置。"
    else -> "请重试；仍失败时检查上游服务。"
}
