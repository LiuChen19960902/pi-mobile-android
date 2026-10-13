package com.finecat.pimobile.android.ui.finecat.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.finecat.pimobile.shared.protocol.GatewayInputCompletion

data class InputAssistToken(val start: Int, val end: Int, val text: String) {
    val isFile: Boolean get() = text.startsWith('@')
}

/** 只扫描光标之前；选区和正在组合的中文不触发候选，不打断 IME。 */
fun inputAssistToken(value: TextFieldValue): InputAssistToken? {
    if (!value.selection.collapsed || value.composition != null) return null
    val caret = value.selection.end
    var start = -1
    var quoted = false
    for (i in 0 until caret) {
        val c = value.text[i]
        if (c == '\n' || c == '\r') { start = -1; quoted = false }
        else if (c == '@' && !quoted) { start = i; quoted = false }
        else if (c == '/' && !quoted && (i == 0 || value.text[i - 1].isWhitespace())) start = i
        else if (start >= 0 && value.text[start] == '@' && c == '"') quoted = !quoted
        else if (c.isWhitespace() && !quoted) start = -1
    }
    if (start < 0) return null
    val token = value.text.substring(start, caret)
    if (token.startsWith('/') && token.drop(1).any { !it.isLetterOrDigit() && it !in "_-:." }) return null
    if (token.length > 2048) return null
    return InputAssistToken(start, caret, token)
}

fun replaceInputAssistToken(value: TextFieldValue, token: InputAssistToken, insert: String,
    cursorOffset: Int = insert.length, consumeClosingQuote: Boolean = false,
): TextFieldValue {
    // 点击到响应之间草稿/光标可能已变化：失效候选不能替换新的选区。
    if (inputAssistToken(value) != token || cursorOffset !in 0..insert.length) return value
    val end = token.end + if (consumeClosingQuote && value.text.getOrNull(token.end) == '"') 1 else 0
    return TextFieldValue(value.text.replaceRange(token.start, end, insert), TextRange(token.start + cursorOffset))
}

fun acceptInputCompletion(value: TextFieldValue, token: InputAssistToken, item: GatewayInputCompletion): TextFieldValue =
    replaceInputAssistToken(value, token, item.insertText, item.cursorOffset, item.consumeClosingQuote)

fun dropInputAssistToken(value: TextFieldValue): TextFieldValue = inputAssistToken(value)?.let {
    replaceInputAssistToken(value, it, "", consumeClosingQuote = it.text.startsWith("@\""))
} ?: value

data class InputAssistPath(val directory: String, val filter: String)
fun inputAssistPath(token: String): InputAssistPath {
    val path = token.removePrefix("@").removePrefix("\"").removeSuffix("\"")
    val slash = path.lastIndexOf('/')
    return InputAssistPath(if (slash < 0) "" else path.take(slash + 1), if (slash < 0) path else path.drop(slash + 1))
}

fun parentInputAssistToken(token: String): String {
    val directory = inputAssistPath(token).directory.trimEnd('/')
    val parent = if (directory.isEmpty() && token.startsWith("@/")) "/" else {
        val index = directory.lastIndexOf('/')
        if (index < 0) "" else directory.take(index + 1)
    }
    return if (parent.any(Char::isWhitespace)) "@\"$parent\"" else "@$parent"
}

/** 染色不改变字形或长度。命令必须来自已加载资源，不把 URL 中的斜杠当命令。 */
fun inputReferenceRanges(text: String, commandNames: Set<String>): List<TextRange> {
    val ranges = mutableListOf<TextRange>()
    var i = 0
    while (i < text.length) {
        val start = i
        when {
            text[i] == '@' -> {
                i++
                if (text.getOrNull(i) == '"') {
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') i++
                    if (text.getOrNull(i) == '"') i++
                } else while (i < text.length && !text[i].isWhitespace() && text[i] != '@') i++
                if (i - start > 1) ranges += TextRange(start, i)
            }
            text[i] == '/' && (i == 0 || text[i - 1].isWhitespace()) -> {
                i++
                while (i < text.length && !text[i].isWhitespace()) i++
                if (text.substring(start + 1, i) in commandNames) ranges += TextRange(start, i)
            }
            else -> i++
        }
    }
    return ranges
}

/** 输入控件的可选编辑/候选状态，旧 String 调用方无需改动。 */
data class ComposerInputAssist(
    val value: TextFieldValue,
    val onChange: (TextFieldValue) -> Unit,
    val transformation: VisualTransformation = VisualTransformation.None,
    val onKey: (KeyEvent) -> Boolean = { false },
    val panel: (@Composable (Dp, Dp) -> Unit)? = null,
)
