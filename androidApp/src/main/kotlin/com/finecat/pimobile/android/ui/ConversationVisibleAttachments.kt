package com.finecat.pimobile.android.ui

/**
 * 对话列表"屏内附件"上报的纯逻辑（入站图片懒加载的真源，02 §6）。
 *
 * 用 LazyList 条目 key 而不是可见下标：列表前部会插入非消息行（"加载更早消息"、
 * 历史失败、Working），下标会整体偏移，把别条消息的附件报成可见。非消息条目
 * （过程组/状态行）不带附件，key 命中不到即不计入。
 *
 * 逐帧映射在调用方按当前条目重算——旧实现在长生命周期协程里直接捕获首次的
 * 条目列表，流式新增消息或切会话后上报会停在首版。
 */
internal fun attachmentIdsByEntryKey(
    entries: List<ConversationDisplayEntry>
): Map<String, Set<String>> = entries
    .filterIsInstance<ConversationDisplayEntry.Message>()
    .associate { entry -> entry.id to entry.item.images.mapTo(mutableSetOf()) { it.attachmentId } }

internal fun visibleAttachmentIds(
    idsByKey: Map<String, Set<String>>,
    visibleKeys: List<String>
): Set<String> = visibleKeys.flatMapTo(mutableSetOf()) { key -> idsByKey[key].orEmpty() }


/**
 * 回合分组渲染（F02 displayRounds）下的可见附件映射：LazyColumn 的 item key 是
 * [displayRoundKey] 产出的回合 key，可见附件上报必须用同一形状查表，否则屏内
 * 附件永远匹配不上、懒加载不推进。
 */
internal fun attachmentIdsByRoundKey(
    rounds: List<List<ConversationDisplayEntry>>
): Map<String, Set<String>> = buildMap {
    rounds.forEach { round ->
        val ids = round
            .filterIsInstance<ConversationDisplayEntry.Message>()
            .flatMapTo(mutableSetOf()) { entry ->
                entry.item.images.mapTo(mutableSetOf()) { it.attachmentId }
            }
        put(displayRoundKey(round), ids)
    }
}
