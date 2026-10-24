# Agent Note: 原生会话的树与改名

Status: implemented

## 问题

桥接会话不能用网关另开的 SDK 句柄读写：它的内存树可能有未落盘条目，另一个 manager 写标签或切叶子会与原生进程分叉。直接复用 SDK 树动作会破坏单写者边界。

## 决定

- 扩展从当前公开 sessionManager 读取平坦条目，只传树展示/搜索需要的字段；复制单独读取全文，与 SDK 共用纯函数及超限规则。网关继续复用既有筛选、搜索、prefix 和选中回退投影。预览截断不能决定可见性：全文非空单独保留布尔值，避免长空白前缀把真实正文节点隐藏。
- 标题/标签经公开 setSessionName/setLabel 写入同一原生进程。navigateTree 仅在 ExtensionCommandContext 提供，桥接注册唯一内部命令，用一次性 nonce 请求新鲜 context；等待真实完成或取消，不把 void sendUserMessage 当作导航完成。内部命令不调用模型。
- 导航遇到生成、压缩或待消费队列时拒绝，用户先停止或取回队列。公开命令导航不能安全复用 SDK 的自动取队列/中止流程，不在跳转失败时丢掉队列。
- user/custom_message 返回目标正文供手机原有草稿确认逻辑处理；assistant 只移动叶子。导航不依赖桥接拥有编辑器或dequeue能力：自有BridgeEditor的自动回填仅在单次setText、文本相符且期间无键入时撤回；其他编辑器不额外读/写/事后恢复，直接沿用Pi TUI“当前稿非空则保留、为空才回填”的原生规则。公开get/set没有revision或CAS，不能据文本相等猜测自动回填后覆盖新稿。`editor:unavailable`只表示桥接无法安全截获队列，不得用它把树导航整体禁用。
- 成功和错误回执都绑定 operation/session/instance/epoch；同 operationId 同内容重放结果。超时只报未确认，既不重放导航，也不假报取消。

## 考虑过的方案

初始桥接阶段统一拒绝树与改名，理由是避免 SDK 双写；见[桥接基线](2026-10-20-mobile-bridge-mode.md)。公开原生接口足以接入这些动作，因此仅替代这部分限制。保留对没有原生接线的操作的拒绝，不引入 SDK 回退或私有 AgentSession 导入。

## 影响

网关契约为 PROTOCOL §4.36；客户端复用现有树与草稿交互。网关单测、共享层四元关联回归和真实 Pi CLI 的合成 E2E 分别覆盖不同边界；后者不证明手机触控或真实 Provider 质量。离线只读与明确写动作的按需激活见[桥接基线](2026-10-20-mobile-bridge-mode.md)，不要求用户手动打开CLI。扩展改动须由目标原生Pi实际reload后生效；仅重启网关不能更新已有CLI内存中的扩展。
