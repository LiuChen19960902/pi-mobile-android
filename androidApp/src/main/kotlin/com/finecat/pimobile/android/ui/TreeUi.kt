package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.GatewayTreeNode

/** 网关是 Pi 树投影的唯一来源。保留空格/穿透竖线，活动路径按 Pi 插入标记。 */
fun treeRowPrefix(node: GatewayTreeNode): String =
    node.prefix + if (node.onActivePath) "• " else ""

/**
 * 原型按每次用户输入给工具/回复画组内线。Pi 的无分叉单链没有 prefix，
 * 这里只给可见用户节点后、parentId 连续的平铺段补视觉线，深链不逐层右移。
 * 保留网关的祖先竖线/缩进，不覆盖节点本身的分叉或折叠标记；缺用户/断链不猜归属。
 */
fun treeDisplayPrefixes(nodes: List<GatewayTreeNode>): List<String> {
    val lastUserIndex = nodes.indexOfLast { it.type == "message" && it.role == "user" }
    var inVisibleTurn = false
    var previousId: String? = null
    return nodes.mapIndexed { index, node ->
        val isUser = node.type == "message" && node.role == "user"
        val follows = inVisibleTurn && node.parentId == previousId
        val ownsConnector = node.prefix.any { it in "├└─⊟⊞" }
        val decorate = !isUser && follows && !ownsConnector
        inVisibleTurn = isUser || follows
        previousId = node.id
        if (!decorate) return@mapIndexed treeRowPrefix(node)
        val next = nodes.getOrNull(index + 1)
        val continues = next != null && next.prefix == node.prefix && next.parentId == node.id &&
            next.prefix.none { it in "├└─⊟⊞" } &&
            !(next.type == "message" && next.role == "user")
        node.prefix.ifEmpty { if (index < lastUserIndex) "│  " else "   " } +
            (if (continues) "├─ " else "└─ ") +
            (if (node.onActivePath) "• " else "")
    }
}

/**
 * 树行正文的三段拆分（原型 `body()` 的“长路径弱化目录、保留尾部”）。
 * `before + dimDirectory + tail` 恒等于原文，不改一个字符、不自己截断。
 */
data class TreeRowPathParts(val before: String, val dimDirectory: String, val tail: String) {
    val joined: String get() = before + dimDirectory + tail
    val hasDimDirectory: Boolean get() = dimDirectory.isNotEmpty()
}

/**
 * 只对**最后一个含 `/` 的空白分隔片段**做目录弱化：该片段最后一个 `/` 之前（含）
 * 变淡，尾部（文件名/行号/命令尾巴）保留正常色，片段前后的文本不受影响。
 *
 * 不按原型直接取“全文最后一个 `/`”：那会把 `[grep: /pat/ in ~/x]` 的模式与
 * `cd ~/x && ls` 的 `cd ` 一并弱化，只剩下一个字母的尾部。本函数不造数据：
 * 没有含 `/` 的片段时原样返回（dimDirectory 为空）。
 */
fun treeRowPathParts(body: String): TreeRowPathParts {
    var tokenStart = -1
    var candidateStart = -1
    var candidateSlash = -1
    var index = 0
    while (index <= body.length) {
        val atEnd = index == body.length
        val isSpace = !atEnd && body[index].isWhitespace()
        if (!isSpace && !atEnd) {
            if (tokenStart < 0) tokenStart = index
            if (body[index] == '/') {
                candidateStart = tokenStart
                candidateSlash = index
            }
        } else {
            tokenStart = -1
        }
        index++
    }
    if (candidateSlash < 0) return TreeRowPathParts(body, "", "")
    return TreeRowPathParts(
        before = body.substring(0, candidateStart),
        dimDirectory = body.substring(candidateStart, candidateSlash + 1),
        tail = body.substring(candidateSlash + 1),
    )
}

/**
 * 消息行的角色标记拆分：网关 `displayText` 已含 `user: ` / `assistant: ` 前缀（Pi
 * getEntryDisplayText 口径），客户端只把它染成角色色，**不再追加自己的标签**，
 * 否则会出现 `我 user: xxx` 这类双重前缀。不匹配时返回 null（整行当正文）。
 */
fun treeRowRoleLabel(displayText: String, role: String?): String? {
    if (role != "user" && role != "assistant") return null
    val prefix = "$role: "
    return if (displayText.startsWith(prefix)) prefix else null
}

/**
 * 搜索命中的客户端强调判定（原型 `.hit`）：与网关同口径的空白分词 AND、不区分
 * 大小写，但只在**行上看得到的文本**（标签 + displayText/preview）里找。
 *
 * 这是强调，不是过滤：网关还会按角色、类型关键字与 200 字正文窗口匹配，因此
 * 本函数返回 false 的行仍可能合法地出现在结果里（例如只命中 `user` 角色词），
 * 此时不加高亮，不反过来隐藏或伪造命中。
 */
fun treeRowMatchesSearch(node: GatewayTreeNode, query: String): Boolean {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (tokens.isEmpty()) return false
    val haystack = buildString {
        node.label?.let { append(it).append(' ') }
        append(node.displayText ?: node.preview)
    }.lowercase()
    return tokens.all { it in haystack }
}

/** 树行正文：有 tree-view-v1 的 displayText 就用它（已含角色/标签前缀），
 *  旧网关回落 preview 并自己补角色前缀（与改动前同口径）。 */
fun treeRowText(node: GatewayTreeNode): String = node.displayText ?: when (node.role) {
    "user" -> "user: ${node.preview}"
    "assistant" -> "assistant: ${node.preview}"
    else -> node.preview
}

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
