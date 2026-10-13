package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.finecat.conversation.formatClockLabel
import com.finecat.pimobile.android.ui.finecat.conversation.projectBasename
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头部副行与时间分隔行的纯函数口径。
 *
 * 这两处都直接决定 S4 对话屏的头部是否与原型一致：副行必须是项目基名而不是全路径
 * （原型 `data-repo` 存短名），时间分隔必须对"时间未知"的消息保持沉默，不能画出
 * 1970 之类的垃圾时间。
 */
class FcConversationLabelsTest {

    @Test
    fun projectBasename_takesLastSegment() {
        assertEquals("pi-mobile-android", projectBasename("/home/demo/pi-mobile-android"))
        assertEquals("pi-demo", projectBasename("/tmp/pi-demo"))
        assertEquals("repo", projectBasename("/a/b/repo/"))
    }

    @Test
    fun projectBasename_keepsSingleSegment() {
        assertEquals("workspace", projectBasename("workspace"))
    }

    @Test
    fun projectBasename_handlesWindowsSeparator() {
        assertEquals("pi-demo", projectBasename("""C:\Users\demo\pi-demo"""))
    }

    @Test
    fun projectBasename_blankForUnusableInput() {
        // 空/根路径/纯斜杠都取不到名字，调用方据此不渲染副行
        assertEquals("", projectBasename(null))
        assertEquals("", projectBasename(""))
        assertEquals("", projectBasename("   "))
        assertEquals("", projectBasename("/"))
        assertEquals("", projectBasename("//"))
    }

    @Test
    fun formatClockLabel_formatsKnownTimestamp() {
        // 2024-01-01T10:37:00Z；断言只取形状，具体值依赖本机时区
        val label = formatClockLabel(1_704_099_420.0)
        assertEquals(5, label.length)
        assertEquals(":", label.substring(2, 3))
        assertEquals(label, formatClockLabel(1_704_099_420.0))
    }

    @Test
    fun formatClockLabel_silentForUnknownTimestamp() {
        // epochSeconds == 0 表示缺 timestamp 的历史消息/旧网关
        assertEquals("", formatClockLabel(0.0))
        assertEquals("", formatClockLabel(-1.0))
    }
}
