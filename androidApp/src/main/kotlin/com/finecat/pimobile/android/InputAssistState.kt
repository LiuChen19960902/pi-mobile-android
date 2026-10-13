package com.finecat.pimobile.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.finecat.pimobile.shared.gateway.GatewayRequest
import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayInputCompletion
import com.finecat.pimobile.shared.protocol.GatewaySlashCommand
import com.finecat.pimobile.shared.protocol.inputCompletions
import java.util.UUID

data class InputAssistContext(val sessionId: String? = null, val cwd: String? = null) {
    val valid: Boolean get() = (sessionId != null) xor (cwd?.startsWith('/') == true)
}

/** 输入辅助独立于发送/树回填 generation，查询只携带当前 token。 */
class InputAssistState(private val nextId: () -> String = { UUID.randomUUID().toString() }) {
    var generation by mutableIntStateOf(0)
        private set
    var context: InputAssistContext by mutableStateOf(InputAssistContext())
        private set
    var commands: List<GatewaySlashCommand> by mutableStateOf(emptyList())
        private set
    var resourcesLoading by mutableStateOf(false)
        private set
    var resourcesError: String? by mutableStateOf(null)
        private set
    var previewIncomplete by mutableStateOf(false)
        private set
    var token: String by mutableStateOf("")
        private set
    var completions: List<GatewayInputCompletion> by mutableStateOf(emptyList())
        private set
    var completionsLoading by mutableStateOf(false)
        private set
    var completionsError: String? by mutableStateOf(null)
        private set
    private var resourceId: String? = null
    private var completionId: String? = null

    fun reset(next: InputAssistContext = context) {
        generation += 1
        context = next
        commands = emptyList()
        resourceId = null
        completionId = null
        resourcesLoading = false
        resourcesError = null
        previewIncomplete = false
        token = ""
        completions = emptyList()
        completionsLoading = false
        completionsError = null
    }

    fun resourcesRequest(): GatewayRequest? {
        if (!context.valid || resourcesLoading) return null
        val id = nextId()
        resourceId = id
        resourcesLoading = true
        resourcesError = null
        return GatewayRequests.inputResources(id, context.sessionId, context.cwd)
    }

    /** 空 token 同样发送，用于终止网关正在执行的 fd。 */
    fun completionsRequest(nextToken: String): GatewayRequest? {
        if (!context.valid || nextToken.length > 2048) return null
        val id = nextId()
        completionId = id
        token = nextToken
        completions = emptyList()
        completionsLoading = nextToken.isNotEmpty()
        completionsError = null
        return GatewayRequests.inputCompletions(id, nextToken, context.sessionId, context.cwd)
    }

    fun receive(frame: GatewayFrame) {
        if (frame.sessionId != context.sessionId || frame.cwd != context.cwd && context.sessionId == null) return
        when (frame.kind) {
            "input-resources" -> if (frame.queryId != null && frame.queryId == resourceId) {
                resourcesLoading = false
                resourceId = null
                previewIncomplete = frame.complete != true
                commands = frame.commands.orEmpty().filter {
                    (it.source == "prompt" && it.name.lowercase() !in MOBILE_SLASH_COMMANDS) ||
                        (it.source == "skill" && frame.skillCommandsEnabled == true && it.name.startsWith("skill:"))
                }
            }
            "input-completions" -> if (frame.queryId != null && frame.queryId == completionId &&
                frame.token == token && frame.prefix == token
            ) {
                completionId = null
                completionsLoading = false
                completions = frame.inputCompletions().filter {
                    it.type in setOf("file", "directory") && it.insertText.startsWith('@') &&
                        it.cursorOffset in 0..it.insertText.length
                }.sortedBy { it.type != "directory" }
            }
        }
    }

    fun fail(type: String, id: String?, detail: String) {
        if (id == null) return
        when (type) {
            "input-resources" -> if (id == resourceId) {
                resourceId = null
                resourcesLoading = false
                resourcesError = detail
            }
            "input-completions" -> if (id == completionId) {
                completionId = null
                completionsLoading = false
                completionsError = if (token.isEmpty()) null else detail
            }
        }
    }
}

// 与 consumeSlashCommand 的移动端路由一致；不把会被拦截的同名 Prompt 冒充可执行命令。
internal val MOBILE_SLASH_COMMANDS = setOf(
    "name", "compact", "model", "thinking", "copy", "tree", "fork", "clone", "export", "import",
    "share", "session", "scoped-models", "trust", "changelog", "hotkeys", "login", "settings",
)
