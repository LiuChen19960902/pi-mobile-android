package com.finecat.pimobile.android

import com.finecat.pimobile.shared.protocol.GatewayFrame
import kotlinx.serialization.json.*

internal enum class WorktreeMode { CONTINUE, EXISTING, NEW }

internal enum class WorktreePhase { LIST, SESSIONS, FORM, CREATING, SWITCHING, CONFIRM, CREATE_FAILED, SWITCH_FAILED, TARGET_GONE, TRUST }

internal data class WorktreeUiState(
    val open: Boolean = false,
    val sourceSessionId: String? = null,
    val sourceCwd: String = "",
    val navigationEpoch: Long = 0,
    val phase: WorktreePhase = WorktreePhase.LIST,
    val mode: WorktreeMode = WorktreeMode.CONTINUE,
    val sessionIds: Set<String> = emptySet(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val selectedExistingSessionId: String? = null,
    val loading: Boolean = false,
    val listing: GatewayFrame? = null,
    val operationId: String? = null,
    val action: String? = null,
    val targetPath: String? = null,
    val continueAfterCreate: Boolean = false,
    val createdPath: String? = null,
    val createBranch: String = "",
    val createBase: String = "HEAD",
    val createPath: String = "",
    val error: String? = null,
) {
    val continueConversation: Boolean get() = mode == WorktreeMode.CONTINUE
}

/** 仅持久化结果确认所需信息；正文、附件、凭据与自动重发意图不进入记录。 */
internal fun worktreePendingJson(ui: WorktreeUiState, hostKey: String): String = buildJsonObject {
    put("hostKey", hostKey)
    put("operationId", ui.operationId)
    put("action", ui.action)
    put("sourceCwd", ui.sourceCwd)
    ui.sourceSessionId?.let { put("sourceSessionId", it) }
    ui.targetPath?.let { put("targetPath", it) }
    ui.createdPath?.let { put("createdPath", it) }
    put("continueAfterCreate", ui.continueAfterCreate)
    put("continueConversation", ui.continueConversation)
}.toString()

internal fun restoreWorktreePending(raw: String?, hostKey: String): WorktreeUiState? = runCatching {
    if (raw == null || raw.length > 16_384) return null
    val value = Json.parseToJsonElement(raw).jsonObject
    fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull
    if (text("hostKey") != hostKey) return null
    val id = text("operationId") ?: return null
    if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
    val action = text("action")?.takeIf { it in setOf("create", "continue") } ?: return null
    WorktreeUiState(open = true, phase = WorktreePhase.CONFIRM, navigationEpoch = -1,
        operationId = id, action = action, sourceSessionId = text("sourceSessionId"),
        sourceCwd = text("sourceCwd").orEmpty(), targetPath = text("targetPath"), createdPath = text("createdPath"),
        continueAfterCreate = value["continueAfterCreate"]?.jsonPrimitive?.booleanOrNull ?: false,
        mode = if (value["continueConversation"]?.jsonPrimitive?.booleanOrNull != false) WorktreeMode.CONTINUE else WorktreeMode.NEW)
}.getOrNull()

internal fun worktreeErrorText(code: String?): String = when (code) {
    "worktree-busy" -> "任务进行中，暂不能 Fork 会话"
    "worktree-queued" -> "先处理待发消息，再 Fork 会话"
    "worktree-target-unavailable" -> "目标目录已失效"
    "worktree-not-git" -> "当前目录不是 Git 仓库"
    "worktree-branch-invalid" -> "分支名不合法"
    "worktree-base-invalid" -> "基点不存在"
    "worktree-no-commits" -> "仓库还没有提交，无法创建工作区"
    "worktree-path-invalid" -> "请填写完整的目标路径"
    "worktree-path-exists" -> "目标路径已存在"
    "worktree-create-failed" -> "创建失败，请修改后重试"
    "worktree-model-unavailable" -> "目标工作区无法使用当前模型"
    "worktree-source-changed" -> "源会话已变化，请重新打开工作区"
    "worktree-operation-conflict" -> "操作信息已变化，请重新打开"
    "worktree-trust-required" -> "目标目录包含尚未信任的项目配置"
    "worktree-result-unknown", "worktree-operation-not-found" -> "正在确认操作结果"
    else -> "工作区操作失败，请重试"
}
