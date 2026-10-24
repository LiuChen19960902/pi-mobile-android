package com.finecat.pimobile.shared.gateway

/** User-facing text comes from stable categories, never from a remote exception. */
object GatewayErrorText {
    fun connection(code: String?): String = when (code) {
        "authentication-required", "pair-token-missing" -> "设备凭据失效，请重新配对"
        "pairing-code-invalid" -> "配对码已失效，请重新生成"
        "credential-access-failed" -> "无法读取设备凭据，请重试"
        "network-lost", "network-flow-failed" -> "网络不可用"
        "gateway-disabled" -> "网关尚未启用"
        "decode-failed", "incoming-flow-failed" -> "网关响应异常，请重试"
        else -> "无法连接网关，请检查网络"
    }

    fun isConnectionFailure(code: String?): Boolean = code in setOf(
        "not-connected", "send-failed", "open-failed", "connect-failed", "stored-connect-failed",
        "transport-failed", "endpoint-unreachable", "gateway-unavailable", "network-lost",
        "network-flow-failed", "connection-closed", "recovery-timeout",
    )

    fun request(type: String?, code: String?): String = when (code) {
        "worktree-trust-required" -> "请先信任目标项目，草稿已保留"
        "worktree-result-unknown" -> "会话启动未确认，请刷新会话列表"
        "bridge-stale-runtime" -> "原生会话已变化，请刷新"
        "model-not-found" -> "此模型已不在原生 Pi 的可用范围"
        "model-auth-missing" -> "模型未配置认证"
        "model-refresh-busy" -> "当前会话忙，请空闲后刷新配置"
        "model-config-refresh-failed" -> "原生模型配置刷新失败，请检查配置"
        "model-change-failed" -> "模型切换失败，请重试"
        "model-change-unconfirmed" -> "切换结果未确认，请重新打开核对"
        "model-change-pending" -> "模型切换尚未结束，请稍后重试"
        "compaction-busy" -> "正在压缩上下文，请稍候"
        "compaction-failed" -> "上下文压缩失败，请重试"
        "compaction-cancelled" -> "上下文压缩已中止"
        "compaction-unconfirmed" -> "压缩结果未确认，请刷新核对"
        "directory-permission-denied" -> "没有目录访问权限"
        "directory-not-found" -> "目录不存在"
        "directory-not-directory" -> "路径不是目录"
        "directory-name-invalid" -> "文件夹名称无效"
        "directory-name-conflict" -> "同名条目已存在"
        "filesystem-read-only" -> "目录只读，无法写入"
        "storage-full" -> "宿主存储空间不足"
        "history-cursor-invalid" -> "历史位置已变化，请重新加载"
        "history-turn-too-large" -> "历史内容过大，请重新加载"
        "session-not-found" -> "会话不存在，请刷新"
        "session-restart-busy" -> "会话正在运行，请稍后重试"
        // 桥接模式（PROTOCOL §4.30–§4.32）：只按稳定码给短文案，不透出原生错误。
        "bridge-offline" -> "原生 Pi 实例不在线"
        "bridge-unavailable" -> when (type) {
            "models" -> "模型目录暂不可用，请重试"
            "input-resources", "input-completions", "commands" -> "请更新网关和桥接扩展"
            else -> "当前模式不支持此操作"
        }
        // 取回排队消息依赖宿主当前输入框的 dequeue 动作；被其他自定义编辑器
        // 占用、或当前 Pi 版本没有该动作，都是能力受限而非“当前模式不支持”，
        // 也不影响基础停止，所以文案不把原因归到某一种上。
        "editor-unavailable" -> if (type == "switch-branch") "会话编辑器暂不可用，请重载原生 Pi" else "输入框被占用或不支持取回排队消息"
        // 取回动作执行了却没拿到文字：队列可能已被清空，不能说成"没动队列"。
        "dequeue-unconfirmed" -> "排队消息去向未确认，请在电脑查看"
        "mode-switch-blocked" -> "会话仍在运行或有排队消息，暂时无法切换"
        "session-owned" -> "会话已被另一个 Pi 实例持有"
        "operation-id-conflict" -> "请求内容已变化，请重试"
        "bridge-start-failed" -> "后台 Pi 启动失败，请查看宿主 tmux"
        "launch-result-unknown" -> "启动结果未知，请刷新在线状态"
        "native-start-unconfirmed" -> "原生 Pi 启动未确认，草稿已保留"
        "native-submit-unconfirmed" -> "消息提交未确认，请先核对会话"
        "navigation-cancelled" -> "已取消节点跳转"
        "navigation-failed" -> "节点跳转失败，请重试"
        "navigation-unconfirmed" -> "跳转结果未确认，请刷新会话树"
        "busy" -> if (type in setOf("tree", "label-put", "switch-branch", "name")) "请先停止当前任务再操作" else "会话正在运行，请改用追加或排队"
        "provider-not-found" -> "Provider不存在，请刷新"
        "provider-config-read-failed", "scope-read-failed" -> "模型配置读取失败"
        "provider-config-write-failed", "scope-save-failed" -> "保存未确认，请重新打开核对"
        "scope-conflict" -> "模型范围已变化，请重新加载"
        "input-unavailable" -> "输入候选暂不可用"
        "input-timeout", "timeout", "request-timeout", "history-timeout" -> "请求超时，请重试"
        "attachment-not-found" -> "图片已不可用"
        // 树页动作（PROTOCOL §4.26/§4.27）：目标条目与可复制文本的专用码。
        "entry-not-found" -> "条目不存在，请刷新会话树"
        "entry-copy-empty" -> "该条目没有可复制的文本"
        "attachment-too-large", "image-limits-exceeded", "image-selection-limit-exceeded" -> "图片过多或过大，请减少图片"
        "attachment-invalid" -> "图片无效，请重新选择"
        "request-busy", "request-coalesced" -> "请求处理中，请稍后重试"
        "not-connected" -> "网关未连接"
        "invalid-message-ack" -> "发送回执无效，请重试"
        "send-failed" -> "请求未发出，请重试"
        "authentication-required", "pairing-code-invalid", "credential-access-failed", "pair-token-missing" -> connection(code)
        "unknown-command" -> "网关不支持此操作"
        "prompt-rejected" -> "消息未被受理，请重试"
        "bad-request" -> when (type) {
            "directories" -> "目录路径无效"
            "directory-create", "workspace-create" -> "文件夹名称或路径无效"
            "provider-put", "provider-test", "provider-models" -> "Provider配置无效"
            "set-model", "set-thinking", "model-scope-put" -> "模型配置无效"
            "name", "session-name" -> "会话名称无效"
            "switch-branch" -> "当前无法切换节点"
            "message" -> "消息或目录配置无效"
            else -> "请求参数无效"
        }
        else -> if (isConnectionFailure(code)) connection(code) else when (type) {
            "directories" -> "目录加载失败，请重试"
            "directory-create" -> "文件夹创建失败，请重试"
            "workspace-create" -> "工作目录创建失败，请重试"
            "history" -> "历史记录加载失败，请重试"
            "sessions" -> "会话列表加载失败，请重试"
            "name", "session-name" -> "会话改名失败"
            "provider-put" -> "Provider保存失败"
            "provider-delete" -> "Provider删除失败"
            "providers" -> "Provider加载失败，请重试"
            "provider-test" -> "连接测试失败，请重试"
            "provider-models" -> "模型同步失败，请重试"
            "models" -> "模型列表加载失败，请重试"
            "set-model" -> "模型切换失败"
            "set-thinking" -> "思考档位切换失败"
            "tree" -> "会话树加载失败，请重试"
            "switch-branch" -> "节点跳转失败"
            "entry-copy" -> "复制失败，请重试"
            "label-put" -> "标签保存失败，请重试"
            "compact" -> "上下文压缩失败"
            "abort" -> "停止请求失败，请重试"
            "message" -> "消息发送失败，草稿已保留"
            "input-resources", "input-completions" -> "输入候选加载失败，请重试"
            "model-scope", "model-scope-put" -> "模型范围操作失败，请重试"
            else -> "操作失败，请重试"
        }
    }

    /** Only local, curated text may pass through. Remote failures use request(). */
    fun local(value: String): String = when (value) {
        "image-preprocess-failed", "image-decode-failed", "image-read-failed" -> "图片无法读取，请重新选择"
        "image-input-too-large", "image-selection-limit-exceeded" -> "图片过多或过大，请减少图片"
        "camera-start-failed", "相机启动失败，请稍后重试。" -> "相机无法启动，请重试或手动配对"
        "decode-failed" -> "网关响应异常，请重试"
        "credential-access-failed" -> connection(value)
        else -> when {
            isConnectionFailure(value) -> connection(value)
            else -> "操作失败，请重试"
        }
    }
}
