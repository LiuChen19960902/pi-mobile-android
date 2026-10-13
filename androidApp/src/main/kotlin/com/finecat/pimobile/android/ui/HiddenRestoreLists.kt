package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.domain.SessionSummary

/**
 * 单条隐藏会话的恢复条目。[id] 是持久化黑名单里的完整会话 ID（真源）；
 * [displayTitle] 仅是展示信息：catalog 里有就用其标题，没有就用占位文案。
 */
internal data class HiddenSessionEntry(
    val id: String,
    val displayTitle: String
) {
    companion object {
        const val FALLBACK_TITLE = "暂未获取会话标题"
    }
}

/** 隐藏恢复列表的统一口径：入口计数、空态与 Dialog 都以它为准。 */
internal data class HiddenRestoreLists(
    val sessionEntries: List<HiddenSessionEntry>,
    val directoryPaths: List<String>
)

/**
 * 构建隐藏恢复条目：持久化的 hiddenSessionIds / hiddenDirectoryPaths 是唯一真源，
 * 决定条目存在性与数量；[sessions]（catalog）只负责补标题，缺失时用占位标题 +
 * 完整 ID 保住单条恢复能力。隐藏目录不依赖 catalog，原样透传。顺序跟黑名单走，
 * 保持稳定不跳变。空白 ID/目录不产生条目（hide/hideDirectory 本就拒绝空白键）。
 */
internal fun buildHiddenRestoreLists(
    hiddenSessionIds: List<String>,
    sessions: List<SessionSummary>,
    hiddenDirectoryPaths: List<String>
): HiddenRestoreLists {
    val byId = sessions.associateBy { it.id }
    return HiddenRestoreLists(
        sessionEntries = hiddenSessionIds.filter(String::isNotBlank).map { id ->
            HiddenSessionEntry(
                id = id,
                displayTitle = byId[id]?.title?.takeIf(String::isNotBlank)
                    ?: HiddenSessionEntry.FALLBACK_TITLE
            )
        },
        directoryPaths = hiddenDirectoryPaths.filter(String::isNotBlank)
    )
}
