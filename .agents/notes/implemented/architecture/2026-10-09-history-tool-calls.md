# Agent Note: 历史中的工具调用从 assistant 消息投影

Status: implemented

## 问题

实时流中工具调用由 `tool_execution_start` 等事件描述，而历史里没有这些事件：Pi 会话文件中 assistant 消息含 `toolCall`，之后才是 `toolResult`。客户端原来只把历史 assistant 的工具调用用于摘除 delta，不投影调用本身，于是历史中的工具结果成了“孤儿”，详情普遍缺少命令。

## 决定

- 网关在历史中保留调用参数，规范为 `toolCallId` / `toolName` / `input`。客户端把它解码到历史 assistant 的 `message.toolCalls`（负 seq），并为这些历史助手消息恢复工具项，保留完整参数和精确的 callId。条目 ID 使用稳定的 assistant 条目身份加 callId。
- 实时（正 seq）调用继续依赖 `tool/call` 事件。混合批次中显式参数优先，但调用排在历史结果之前；同一 callId 重放不追加第二行。
- 历史 assistant 的时间不是工具开始时间，不能据此计算执行耗时。
- 参数确实缺失时如实为空，不用结果冒充命令。JSON 字符串参数中的引号属于编码内容，测试不要求静默剥除。
- 结果与调用按 `toolCallId` 配对，未命中时才回退 FIFO；并行工具乱序完成时仅靠 FIFO 会配错。

## 考虑过的方案

**只用实时事件生成工具项。** 历史会话的步骤详情全部缺失命令。

## 影响

- 历史中的工具步骤没有耗时信息。

## 核对入口

- `ConversationProjector`、`PiEventNormalizer` 中历史 assistant `toolCalls` 的处理；网关 `src/wire.ts` 的历史消息转换。
