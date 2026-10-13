package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.shared.gateway.PI_THINKING_LEVELS
import com.finecat.pimobile.shared.protocol.GatewayModelCatalog

/**
 * 思考映射草稿：键来自Pi七档枚举，值为上游字符串或显式null。
 * 初始显示目录档位与已有映射键的并集，保留被null过滤出目录的既有项。
 * 2026-10-08负责人要求键和值均可编辑；改键整体提交映射，不覆盖其他列。
 *
 * 网关provider-put：整个map省略或空map归一化后保留宿主映射；非空map整体替换，
 * 未包含的键走SDK默认。草稿""表示未设置，"-"编码显式null（沿用旧UI约定）。
 * 不提供删除最后一条映射；空map不能表达这个动作。
 */
internal object FineCatThinkingMap {

    /** 草稿里「未设置」的编码：键存在但值为空串，序列化时被滤掉，等价于该档不下发。 */
    const val UNSET = ""

    /** 草稿里「显式 null（该档不支持）」的编码；解码在 `ProviderModelDraft.from`。 */
    const val UNSUPPORTED = "-"

    /**
     * 「未设置」在单元格里的显示字形。它只用于展示，但用户可能照着敲进输入框；那样会被当成
     * 一个真实取值原样下发，与所见（未设置）矛盾，所以提交时与 [UNSET]/[UNSUPPORTED] 一起拒绝。
     */
    const val UNSET_GLYPH = "—"

    /** 这一档是否真的已配置（显式 null 也算已配置——它是一条会被下发的 override）。 */
    private fun String.isConfigured(): Boolean = isNotBlank()

    /**
     * 该模型在**目录帧**里真实声明的档位；`null` = 目录里查不到这个模型
     * （没认证、不在 `enabledModels` 白名单、或 `models` 帧没到），此时**不知道**它有哪些
     * 档位，不能拿全集或演示数据顶替。
     */
    fun declaredLevels(
        catalog: GatewayModelCatalog?,
        providerId: String,
        modelId: String,
    ): List<String>? {
        if (modelId.isBlank()) return null
        val model = catalog?.groups
            ?.firstOrNull { it.provider == providerId }
            ?.models
            ?.firstOrNull { it.id == modelId }
            ?: return null
        return model.thinkingLevels
    }

    /**
     * 会话当前正在用的那一档；只有当**正在编辑的模型正好是会话当前模型**时才算数。
     *
     * Provider 表单里编辑的通常是别的模型，那时不存在「这一档当前亮着」的事实，
     * 返回 null（不高亮任何列）比拿别的模型的档位来亮要诚实。
     */
    fun currentLevelFor(
        sessionProviderId: String?,
        sessionModelId: String?,
        sessionThinkingLevel: String?,
        editingProviderId: String,
        editingModelId: String,
    ): String? {
        if (sessionProviderId == null || sessionModelId == null) return null
        if (sessionProviderId != editingProviderId || sessionModelId != editingModelId) return null
        return sessionThinkingLevel
    }

    /** 映射区要渲染的形态。空态与有列互斥，UI 不需要自己猜。 */
    internal sealed interface Plan {
        /** 模型不支持推理：没有强度可映射（原型 `mpBlock` 第一分支）。 */
        data object NoReasoning : Plan

        /** 目录帧里查不到这个模型，读不到它真实声明了哪些档位。 */
        data object ModelNotInCatalog : Plan

        /** 查到了，但它没有声明任何推理档位（原型 `mpBlock` 第二分支）。 */
        data object NoDeclaredLevels : Plan

        /** 一档一列。 */
        data class Columns(val columns: List<Column>) : Plan
    }

    /**
     * 一列 = 一个 pi 档位。`target` 是草稿编码（[UNSET] / [UNSUPPORTED] / 真实取值），
     * `isCurrent` 只在 [currentLevelFor] 给出真实当前档时为真。
     */
    internal data class Column(
        val level: String,
        val target: String,
        val isCurrent: Boolean,
    ) {
        val isUnsupported: Boolean get() = target == UNSUPPORTED
        val isUnset: Boolean get() = target.isBlank() && !isUnsupported
    }

    /**
     * 按真实数据装配映射区。
     *
     * @param reasoning `providers` 帧里该模型的 `reasoning`（真实字段，不是猜测）。
     * @param declared [declaredLevels] 的结果：`null` 表示查不到。
     * @param draftMap 该模型当前的草稿映射（`ProviderModelDraft.thinkingMap`）。
     * @param currentLevel [currentLevelFor] 的结果，可空。
     */
    fun plan(
        reasoning: Boolean,
        declared: List<String>?,
        draftMap: Map<String, String>,
        currentLevel: String?,
        displayLevels: List<String>? = null,
    ): Plan {
        if (!reasoning) return Plan.NoReasoning
        if (declared == null) return Plan.ModelNotInCatalog
        val levels = displayLevels ?: orderedLevels(declared, draftMap.keys)
        if (levels.isEmpty()) return Plan.NoDeclaredLevels
        return Plan.Columns(
            levels.map { level ->
                Column(
                    level = level,
                    target = draftMap[level] ?: UNSET,
                    isCurrent = level == currentLevel,
                )
            },
        )
    }

    /**
     * 列顺序 = pi 档位顺序（[PI_THINKING_LEVELS]）：声明的与已存的都在同一轮里按 pi 次序
     * 落位，所以相对次序稳定、且与 pi 自己的档位序一致。
     * 不在 pi 档位全集里的键（网关 `normalizeThinkingLevelMap` 读回时已丢弃，正常帧里不会
     * 出现）稳定排到最后，不打乱前面几档。
     */
    fun orderedLevels(declared: List<String>, existingKeys: Set<String>): List<String> {
        val ordered = ArrayList<String>(declared.size + existingKeys.size)
        for (level in PI_THINKING_LEVELS) {
            if (level in declared || level in existingKeys) ordered += level
        }
        // 兜底：declared / existingKeys 里不在 PI_THINKING_LEVELS 的项。网关归一化会丢掉它们，
        // 但目录帧口径若与本常量不同步（pi 将来加档），它们仍是真实声明，不能静默消失。
        for (level in declared + existingKeys) {
            if (level !in ordered) ordered += level
        }
        return ordered
    }

    /** 该档当前在草稿里的编码；键缺失与空串都归一为 [UNSET]。 */
    fun targetOf(draftMap: Map<String, String>, level: String): String = draftMap[level] ?: UNSET

    /** 改键保留其取值及所有其他映射；重复键拒绝，不能静默覆盖另一列。 */
    fun renameLevel(draftMap: Map<String, String>, displayed: List<String>, from: String, to: String): Map<String, String>? {
        if (from !in displayed || to !in PI_THINKING_LEVELS) return null
        if (from == to) return draftMap
        if (to in displayed || to in draftMap) return null
        val target = draftMap[from] ?: return draftMap
        return (draftMap - from) + (to to target)
    }

    /** SDK通用强度字面值加实际配置值；任意上游自定义串仍可手填。 */
    fun targetChoices(draftMap: Map<String, String>): List<String> =
        (draftMap.values.filter { it.isNotBlank() && it != UNSUPPORTED } + PI_THINKING_LEVELS + "none").distinct()

    /**
     * 提交一个取值。返回 `null` = 拒绝，原因用 [clearRejectedReason] 显示在卡内。
     *
     * 空白提交分两种，含义完全不同：
     * - 本来就没设置 → 空操作（键本来就不存在，没有东西可删）；
     * - 原来有值或已是 null → **拒绝**。当前编辑器不提供删除动作；尤其清空最后一项时，空 map 不能表达恢复默认。
     *   这里不能默默把键删掉——那会让用户以为清空了，实际下发的却是「保留」。
     */
    fun applyTarget(draftMap: Map<String, String>, level: String, raw: String): Map<String, String>? {
        val value = raw.trim()
        if (value == UNSET || value == UNSET_GLYPH) {
            // 清空一档 = 删掉它的 override。只有「真的已配置」才需要拒绝；键存在但值是空白时
            // 序列化本就会滤掉它（`parseModels` 的 filterValues），那本来就是未设置，
            // 按已设置来报「你正在删除映射」会与列上显示的「—」自相矛盾。
            return if (targetOf(draftMap, level).isConfigured()) null else draftMap
        }
        return draftMap + (level to value)
    }

    /** 置为显式 null（该档不支持）：写 [UNSUPPORTED]，保存后下发 JSON `null`。 */
    fun applyUnsupported(draftMap: Map<String, String>, level: String): Map<String, String> =
        draftMap + (level to UNSUPPORTED)

    /** 单元格显示文本：[UNSET] 用破折号表示「不下发」，[UNSUPPORTED] 显示 `null`。 */
    fun displayTarget(target: String): String = when {
        target == UNSUPPORTED -> "null"
        target.isBlank() -> UNSET_GLYPH
        else -> target
    }

    /** 空态文案（原型 `mpBlock` 的两条 `f-note`；第三种是我们自己的诚实说明）。 */
    fun emptyHint(plan: Plan): String? = when (plan) {
        is Plan.NoReasoning -> "这个模型不支持推理，没有强度可映射。"
        is Plan.NoDeclaredLevels -> "这个模型还没声明有哪些强度档位。"
        is Plan.ModelNotInCatalog ->
            "模型目录里没有这个模型，读不到它真实声明的档位。先在电脑上认证并启用它，再回来编辑。"
        is Plan.Columns -> null
    }

    /**
     * 拒绝清空时的可见原因。UI 与单测引用同一句话，避免「提示改了、判据没改」的分叉。
     */
    fun clearRejectedReason(): String = "请选择取值或 null；自定义取值不能为空。"
}
