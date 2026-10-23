# Agent Note: 只对齐 Pi 公开 SDK 契约，不依赖个人扩展

Status: implemented

## 问题

早期实现参照维护者本机的 Pi 环境开发，其中有若干自建扩展：`Agent` / subagent 工具、`subagents:*` 记录、`subagent-notification`、`empty-stop-retry` 等。客户端曾为它们做专用展示（例如输入框上方的 Agent 运行条）。这些都不是 Pi 原生能力：Pi 内置工具只有 `bash`、`read`、`write`、`edit`、`ls`、`grep`、`find`、`powershell`，SDK 包里也没有 `empty-stop-retry`。开源后用户的扩展各不相同，按某个扩展名适配会在别人的环境里失效或误显示。

另一个容易混淆的点是 Pi 的两种自定义条目：它们名字相近，语义完全不同，网关和客户端曾把扩展内部状态推上屏。

## 决定

- 手机端和网关只对齐 Pi 公开 SDK 的通用消息、工具与会话契约。不按第三方插件名、私有状态或 TUI 面板做专用适配；Skills 和 Prompt 模板仍可来自 Package。支持边界维护在 [Docs/pi-compatibility.md](../../../../Docs/pi-compatibility.md)。
- **`custom`（`pi.appendEntry`）不是消息**：它是扩展状态持久化，不进入模型上下文（`sessionEntryToContextMessages` 对它返回空），TUI 只在扩展注册了 renderer 时显示。网关不得通用透传给手机。
- **`custom_message`（`pi.sendMessage`）始终进入模型上下文**，`display` 只控制是否显示：只有 `display: true` 可见，`false` 或缺省都隐藏。客户端渲染前必须检查 `display`，否则会把隐藏的续跑提示显示成错误。
- 历史中的 `custom_message` 与原生压缩条目同为 `role=custom`，要按明确来源区分，不能靠 `customType` 猜。SDK 的 custom 消息是独立条目，不保留原 message 对象引用；同一批次中能证明一一追加顺序时才绑定条目 ID，数量不符就省略身份，不按正文或时间推测。
- `sent` 回执的 `handled` 只表示本次输入被消费，既不保证产生普通 run，也不保证没有处理器另起 run。Pi SDK 1.0.4 的 `_runAgentPrompt` 在第一个 await 之前同步设置 `_isAgentRunActive`，`isStreaming` 直接读这个标记，不需要也不能伪造 streaming 快照。网关先排空旧事件再同步发送 ACK，防止陈旧快照让 Working 复活。

## 考虑过的方案

**对来源明确的扩展做可选增强。** 早期评审允许过“注明来源的可选增强”，Agent 运行条和 `empty-stop-retry` 分支就是这样加入的。开源后无法替所有扩展维护适配，且同名扩展在不同环境里语义不同，因此这一理由不再用于恢复这些功能。

**网关通用透传所有 `custom` 条目，让客户端决定是否显示。** 会把默认不可见的扩展内部状态推到手机上，也违反网关协议中“只下发消息类条目”的约定。

## 影响

- 依赖自建扩展的体验（Agent 运行条、扩展专属卡片）在手机上不再出现，扩展的普通工具调用仍按通用工具步骤显示。
- 扩展通过 `custom_message` 显示的内容可以正常展示；只写 `custom` 条目的扩展在手机上不可见，与 TUI 未注册 renderer 时一致。

## 核对入口

- Pi SDK：`session-manager.d.ts` 中两种条目的定义、`sessionEntryToContextMessages`、`export-html/template.js` 中 `custom_message && display` 的判断。
- 网关 `src/wire.ts` 的历史与实时消息转换；客户端 `PiEventNormalizer.kt` 与 `ConversationProjection.kt` 中对 `display` 的处理。
