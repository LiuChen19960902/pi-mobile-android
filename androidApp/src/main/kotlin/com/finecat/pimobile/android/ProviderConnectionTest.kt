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
data class ProviderTestAttempt(val modelId: String, val ok: Boolean, val summary: String, val httpStatus: Int? = null)

/** 请求被取消/超时或未受理时停止整批；上游单模型失败则继续下一项。 */
internal data class ProviderProbeReply(
    val outcome: AndroidSharedStateHolder.ProviderTestOutcome,
    val interrupted: Boolean = false,
)

/** 串行使用既有单模型协议，首个真实成功即停止，不把临时失败算成功。 */
internal suspend fun testProviderModelsUntilSuccess(
    targets: List<ProviderTestTarget>,
    onAttempt: (Int, Int, ProviderTestTarget) -> Unit,
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
        attempts += ProviderTestAttempt(model, outcome.ok, outcome.summary, outcome.httpStatus)
        if (outcome.ok) return outcome.copy(
            summary = "$model 测试通过（已测试 ${index + 1}/${targets.size}）\n${outcome.summary}",
            attempts = attempts.toList(),
        )
        if (reply.interrupted) return outcome.copy(
            summary = "测试未完成（${index + 1}/${targets.size} · $model）\n${outcome.summary}",
            attempts = attempts.toList(),
        )
    }
    return requireNotNull(last).copy(
        summary = "已测试 ${targets.size} 个模型，均未通过\n${targets.last().modelId ?: "默认模型"}：${last?.summary}",
        attempts = attempts.toList(),
    )
}
