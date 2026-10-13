package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.GatewayTreeNode

/** 网关是 Pi 树投影的唯一来源。保留空格/穿透竖线，活动路径按 Pi 插入标记。 */
fun treeRowPrefix(node: GatewayTreeNode): String =
    node.prefix + if (node.onActivePath) "• " else ""

/**
 * 原型 `md()` 的行内 span 范围：只认反引号 code 与双星粗体，不解析嵌套与未闭合
 * （正则不命中即保持字面）。原型先替换 code 再替换 bold，因此 bold 可包住 code
 * （如 `**`x`**`）——两个 range 允许重叠，渲染时样式叠加。
 */
data class TreeInlineSpan(
    val start: Int,
    val endExclusive: Int,
    val code: Boolean,
    val bold: Boolean,
) {
    /** 剥掉定界符后的内容范围：原型 `md()` 替换为 `<code>$1</code>`/`<strong>$1</strong>`，
     *  定界符本身不进文本（B1 审查 m8：渲染层按此范围 append）。 */
    val innerStart: Int get() = start + if (bold) 2 else 1
    val innerEndExclusive: Int get() = endExclusive - if (bold) 2 else 1
}

fun treeInlineSpans(text: String): List<TreeInlineSpan> {
    if ('`' !in text && '*' !in text) return emptyList()
    val code = Regex("`([^`]+)`").findAll(text)
        .map { TreeInlineSpan(it.range.first, it.range.last + 1, code = true, bold = false) }
    val bold = Regex("\\*\\*([^*]+)\\*\\*").findAll(text)
        .map { TreeInlineSpan(it.range.first, it.range.last + 1, code = false, bold = true) }
    return (code + bold).toList().sortedBy { it.start }
}

/**
 * 树行时间列（原型只给 HH:mm）。pi 透传的是 ISO-8601 串（`…T09:01:00.000Z`，UTC），
 * 解析后复用对话页 `formatClockLabel`（本机时区、epoch≤0 空串同口径）——不能按字符
 * 截取（B1 审查：`takeLast(8).take(5)` 对真实形状恒得 ":00.0"）。非 ISO 串返回空串，
 * 调用方据此不画时间，不退回原串。
 */
fun treeClockLabel(ts: String): String =
    runCatching {
        com.finecat.pimobile.android.ui.finecat.conversation.formatClockLabel(
            java.time.Instant.parse(ts).epochSecond.toDouble(),
        )
    }.getOrDefault("")

/**
 * 原型 `md()` 的渲染片段序列：定界符不进文本内容（`<code>$1</code>`/`<strong>$1</strong>`），
 * 每段带样式标记；bold 包 code 时同一文本段两标记并存（R-m8 初版按边界切连续片，
 * 并集仍是全文、定界符照显——按字符扫描剥定界符才成立）。渲染层只 append + 按段
 * addStyle，拼接结果 = 原文去定界符。部分交叉重叠（如 `a**b`c**）按各自 inner 范围
 * 独立判定，与原型 HTML 嵌套结果可能不同（本函数不解析嵌套，R2-F9）。
 */
data class TreeInlineSegment(val text: String, val code: Boolean, val bold: Boolean)

fun treeInlineSegments(text: String): List<TreeInlineSegment> {
    val spans = treeInlineSpans(text)
    if (spans.isEmpty()) return listOf(TreeInlineSegment(text, code = false, bold = false))
    fun delimAt(p: Int) = spans.firstOrNull {
        (it.start <= p && p < it.innerStart) || (it.innerEndExclusive <= p && p < it.endExclusive)
    }
    val out = mutableListOf<TreeInlineSegment>()
    var i = 0
    while (i < text.length) {
        val delim = delimAt(i)
        if (delim != null) {
            i = if (i < delim.innerStart) delim.innerStart else delim.endExclusive
            continue
        }
        val code = spans.any { it.code && it.innerStart <= i && i < it.innerEndExclusive }
        val bold = spans.any { it.bold && it.innerStart <= i && i < it.innerEndExclusive }
        var j = i + 1
        while (j < text.length) {
            if (delimAt(j) != null) break
            val c2 = spans.any { it.code && it.innerStart <= j && j < it.innerEndExclusive }
            val b2 = spans.any { it.bold && it.innerStart <= j && j < it.innerEndExclusive }
            if (c2 != code || b2 != bold) break
            j++
        }
        out.add(TreeInlineSegment(text.substring(i, j), code, bold))
        i = j
    }
    return out
}
