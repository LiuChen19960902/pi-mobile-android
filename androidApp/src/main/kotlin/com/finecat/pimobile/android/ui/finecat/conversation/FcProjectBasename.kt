package com.finecat.pimobile.android.ui.finecat.conversation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 时间分隔行文案（原型 `.tsep`，如 `22:37`）。取本机时区，与列表里其它本地化时间
 * 保持一致。
 *
 * @param epochSeconds 秒级时间戳；`0` 或负值表示时间未知（缺 timestamp 的历史消息、
 *   旧网关），此时返回空串，调用方据此不画分隔行——避免渲染出 `1970` 之类垃圾时间。
 */
fun formatClockLabel(epochSeconds: Double): String =
    if (epochSeconds <= 0.0) {
        ""
    } else {
        Instant.ofEpochSecond(epochSeconds.toLong())
            .atZone(ZoneId.systemDefault())
            .format(CLOCK)
    }

/**
 * 头部目录副行显示用的项目名（原型 `.sub-line` 里的 `data-repo`）。
 *
 * 原型只放项目基名（`pi-mobile-android`），不显示工作目录全路径——全路径在手机上会
 * 撑爆副行并挤掉标题。所以这里取 basename 而不是原样透传。
 *
 * 规则：
 * - 结尾斜杠（`/a/b/`）先去掉，避免空 basename；
 * - 取最后一段；空串（`/`、`//`）和无法取到名字时返回空串，由调用方决定是否整行不渲染。
 */
fun projectBasename(cwd: String?): String {
    val trimmed = cwd?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val withoutTrailingSlash = trimmed.trimEnd('/')
    if (withoutTrailingSlash.isEmpty()) return ""
    val name = withoutTrailingSlash.substringAfterLast('/')
    // Windows 风格的反斜杠（网关在 Windows 上返回 cwd 时可能出现）
    return name.substringAfterLast('\\').ifEmpty { "" }
}
