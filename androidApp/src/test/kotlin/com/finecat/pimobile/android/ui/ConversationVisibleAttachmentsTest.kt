package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 屏内附件上报（入站图片懒加载）：按可见条目的 LazyList key 映射，且必须读
 * 当前条目列表——旧实现的下标映射会被列表前部的非消息行整体错位，长生命周期
 * 协程里捕获的旧 displayEntries 也会让流式新增/切会话后的上报停在首版。
 */
class ConversationVisibleAttachmentsTest {
    @Test
    fun `attachments are mapped by visible entry key not by row index`() {
        val entries = makeConversationDisplayEntries(
            listOf(
                userItem("u1", "img-a", "img-b"),
                item("a1", ConversationItemKind.ASSISTANT, text = "回复"),
                userItem("u2", "img-c"),
            )
        )
        val ids = attachmentIdsByEntryKey(entries)

        // 列表前部插入"加载更早消息"行后，消息下标整体 +1：按 key 映射不受影响，
        // 同一批可见 key 始终报同一批附件。
        assertEquals(setOf("img-a", "img-b", "img-c"), visibleAttachmentIds(ids, listOf("u1", "a1", "u2")))
        assertEquals(setOf("img-c"), visibleAttachmentIds(ids, listOf("loading-older", "a1", "u2")))
        // 非消息条目（过程组/Working/分页行）不贡献附件，未知 key 不报错。
        assertEquals(emptySet<String>(), visibleAttachmentIds(ids, listOf("working", "history-error")))
        assertEquals(emptySet<String>(), visibleAttachmentIds(ids, emptyList()))
    }

    @Test
    fun `reporting follows the current entry list after streaming growth`() {
        val before = makeConversationDisplayEntries(
            listOf(userItem("u1", "img-a"))
        )
        // 流式新增一条带图消息：用同一入口重算映射即可上报新附件（陈旧闭包会漏）。
        val after = makeConversationDisplayEntries(
            listOf(
                userItem("u1", "img-a"),
                userItem("u2", "img-c"),
            )
        )

        assertEquals(setOf("img-a"), visibleAttachmentIds(attachmentIdsByEntryKey(before), listOf("u1", "u2")))
        assertEquals(
            setOf("img-a", "img-c"),
            visibleAttachmentIds(attachmentIdsByEntryKey(after), listOf("u1", "u2")),
        )
    }

    private fun userItem(id: String, vararg attachmentIds: String) = item(
        id = id,
        kind = ConversationItemKind.USER,
        text = id,
        images = attachmentIds.map { GatewayImageAttachment(it, "image/png", 3) },
    )

    private fun item(
        id: String,
        kind: ConversationItemKind,
        title: String = kind.name,
        text: String = id,
        images: List<GatewayImageAttachment> = emptyList(),
    ) = ConversationItem(
        id = id,
        kind = kind,
        title = title,
        text = text,
        images = images,
        epochSeconds = 0.0,
    )
}
