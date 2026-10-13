package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.shared.gateway.PROVIDER_COMPAT_ENUMS
import com.finecat.pimobile.shared.gateway.PROVIDER_COMPAT_KEYS_BY_API

/**
 * F12 模型卡「兼容性 compat」编辑的纯逻辑（无 Compose，JVM 可测），与 [FineCatThinkingMap]
 * 同一层：UI 只负责画与收意图，三态语义、字段集合、计数与 wire 顺序都在这里定。
 *
 * ## 三种下发形态（网关 `pi-store.ts` 实读，2026-10-02）
 *
 * 1. **不下发 `compat` 字段** —— 网关 `normalizeProviderUpsert` 看不到该键，`put` 的合并分支
 *    根本不进，`mergedModel = {...prior, ...model}` 把旧 `compat` 整个留下。**保留原配置**。
 * 2. **下发对象、省略某个手机管理键** —— `put` 的合并是
 *    `combined = {...宿主私有键, ...phoneCompat}`：手机管理的键没出现就是**被删掉**。
 *    即「省略该键 = 清除该项覆盖」。
 * 3. **下发 `compat: {}`** —— 手机管理键全删，宿主私有键（网关 `editableKeys` 之外的那些）
 *    由 `hostOnlyPrior` 保留；合并后为空则 `delete mergedModel.compat`。
 *
 * 另有两条决定客户端口径的事实：
 * - 网关 `normalizeCompat` 遇到**值为 null 的条目直接跳过**，未知键直接丢弃。所以 compat
 *   **不发送显式 null**（与 `thinkingLevelMap` 的 null 语义相反：那里 null 是「该档不支持」）。
 * - 宿主私有键**永远不会出现在 wire 上**（`toWireModel` 用同一个过滤器），手机既编辑不了
 *   也不该显示它们；它们只由网关合并时保留。
 *
 * ## 为什么 [orderedForWire] 要单独存在
 *
 * 草稿里的 map 是 `LinkedHashMap`，键序取决于「回显顺序 + 用户点过的顺序」，拿它序列化出来的
 * JSON 每次都不一样，验收只能比集合、不能比原文。这里把顺序冻结成
 * `PROVIDER_COMPAT_KEYS_BY_API[api]` 的**声明顺序**（该表本身就是「表单展示顺序」），
 * 不在该表里的残留键按字典序追加，于是 wire JSON 可以逐字比对。
 */
internal object FineCatCompat {

    /** 该协议手机可编辑的键（展示顺序即序列化顺序）；null = 该协议没有 compat 概念。 */
    fun editableKeys(api: String): List<String>? = PROVIDER_COMPAT_KEYS_BY_API[api]

    /** 该协议有没有 compat 编辑区（`google-generative-ai` 为 false：SDK 只定义了 4 个 compat 接口）。 */
    fun hasEditor(api: String): Boolean = PROVIDER_COMPAT_KEYS_BY_API.containsKey(api)

    /**
     * 编辑区装配：[CompatPlan.keys] 是本协议可编辑的键（展示顺序），[CompatPlan.configured] 只数其中
     * **有值**的键，[CompatPlan.offProtocol] 是草稿里存在但本协议不开放的键。
     *
     * 计数必须按 `keys` 算而不是按草稿总数算：草稿里可能挂着别的协议的键（wire 回显不按协议
     * 过滤），它们在编辑区里根本看不见，算进「已设置 N 项」就与用户能看到的行对不上。
     * 这些键**不能丢**——它们是电脑上配好的值，静默删掉等于替用户做了删除决定。
     */
    fun plan(api: String, compat: Map<String, String?>): CompatPlan {
        val keys = PROVIDER_COMPAT_KEYS_BY_API[api].orEmpty()
        val known = keys.toSet()
        return CompatPlan(
            keys = keys,
            configured = keys.count { compat[it] != null },
            // 只列有值的：值是 null 的键压根不进 wire，写进提示只会吓人。
            offProtocol = compat.keys.filter { it !in known && compat[it] != null }.sorted(),
        )
    }

    /** 空态文案用事实陈述，不编「默认值/来源词」。 */
    fun unsupportedHint(api: String): String = "该协议（$api）pi 不使用 compat，没有可设置项。"

    fun isEnumKey(key: String): Boolean = choices(key).isNotEmpty()

    fun choices(key: String): List<String> = PROVIDER_COMPAT_ENUMS[key].orEmpty()

    /** 设置/取消一个键。`value = null` = 恢复默认（在 wire 上表现为**省略该键**）。 */
    fun setValue(
        compat: Map<String, String?>,
        key: String,
        value: String?,
    ): Map<String, String?> = compat + (key to value)

    /**
     * 清空手机管理的兼容参数。
     *
     * 结果是**空 map 而不是把 compat 置 null**：空 map 会下发成 `compat: {}`（清空手机管理键，
     * 宿主私有键由网关保留）；null 会让字段整个不下发（= 什么都不改）。两者含义完全不同。
     */
    fun cleared(): Map<String, String?> = emptyMap()

    /** wire 序列化顺序：协议键集声明顺序在前，不在该表里的残留键按字典序追加。 */
    fun orderedForWire(api: String, compat: Map<String, String?>): Map<String, String?> {
        val declared = PROVIDER_COMPAT_KEYS_BY_API[api].orEmpty()
        val known = declared.toSet()
        val ordered = LinkedHashMap<String, String?>(compat.size)
        declared.forEach { key -> compat[key]?.let { ordered[key] = it } }
        compat.keys.filterNot { it in known }.sorted().forEach { key -> ordered[key] = compat[key] }
        return ordered
    }

    /**
     * 取值标记。**「未设置」不能显示成 `false`/`关`**，否则用户会以为已经改过这一项。
     *
     * 布尔键按值翻成 [ON]/[OFF]，枚举键原样显示 wire 值——两种类型都不该把
     * `true`/`false` 这种协议字面量直接摆给用户看。
     */
    fun marker(key: String, value: String?): String = when {
        value == null -> UNSET
        !isEnumKey(key) && value == "true" -> ON
        !isEnumKey(key) && value == "false" -> OFF
        else -> value
    }

    /** 取值浮层里的「未设置（走默认）」选项文案，与行内标记同一口径。 */
    const val UNSET = "未设置"

    /** 布尔键的两个取值标记（分段按钮文案），刻意不用 true/false 原样。 */
    const val ON = "开"
    const val OFF = "关"
}

/** 兼容参数编辑区的一套装配结果（[FineCatCompat.plan]）。 */
internal data class CompatPlan(
    /** 本协议可编辑的键，展示顺序 = wire 序列化顺序。 */
    val keys: List<String>,
    /** 上面这些键里已设置（有值）的个数。 */
    val configured: Int,
    /** 草稿里有值、但本协议不开放的键；原样带回，不得静默丢。 */
    val offProtocol: List<String>,
)