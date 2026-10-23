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
