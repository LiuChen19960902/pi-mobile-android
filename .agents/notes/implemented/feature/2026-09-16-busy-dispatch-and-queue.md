# Agent Note: 忙时派发、远端队列与发送停止按钮

Status: implemented

## 问题

Pi 在运行时可以接收 steer（注入本轮）和 followUp（排到下一轮）。手机需要让用户在忙时选择派发方式，并能取回尚未处理的输入。容易混淆的是两种“待发”：一种是还没发给网关的本地草稿，一种是 Pi 已经接收的服务端队列。另外，发送键与停止键在同一位置随状态切换，连点会把刚发出的消息自己中止。

## 决定

### 本地待派发项

- 运行中点发送时，文字和图片先进入输入框上方的窄条（`pendingDispatch`），用户选择 ↑ 注入本轮（steer）、→ 排到下轮（followUp）或 ✕ 取回编辑器。只保存一条待派发草稿。空闲发送用 `mode=followUp`，走 Pi 的空闲 prompt 路径。运行中完全不带 `streamingBehavior` 会被 Pi 拒绝。
- 取回时若输入框已有新文字或附件，采用交换：旧待发稿回编辑器，新稿进入待发栏；文字、图片和来源 URI 成组交换，三份状态必须同步。不把两份附件拼接，避免绕过 4 张和总字节限制。照片仍在预处理时暂缓取回。
- 压缩期间发送按钮优先显示停止：网关锁定的 SDK 在 `prompt` 中先拒绝压缩期提交，仅凭 `isStreaming` 允许发送会出现可点但无效的箭头。
- 已有会话的普通文本在 ACK 前暂存原稿：正常 `sent` 后丢弃；明确拒绝后进入有界的“失败”恢复条；超时或断连进入“未确认”恢复条，不能称为肯定失败。恢复不自动重发，当前有内容时不覆盖。明确拒绝的判定由 Runtime 标记“关联成功的服务端 error”且 `requestType=message`，不靠错误码白名单。
- 交互取舍：窄条用浅灰小字和右侧三个图标键，紧贴输入框。初版的大卡片、说明文字和大按钮已被替换。

### 服务端队列

- SDK 已派发条目按 `queuedMessages.steering / followUp` 显示，不从本地草稿推测。轮内清除依赖 `queue_update`，见 [会话运行状态的补偿路径](../architecture/2026-09-16-session-state-compensation.md)。
- 原生桥接的公开扩展接口只有 `hasPendingMessages()`，没有队列正文；`bridge-snapshot.pending` 与 `state/history.pendingMessages` 单独保存为是否待执行。无明细时显示“有消息等待执行”和整批撤回入口，不伪造 Follow-up 文本、条数或逐项身份。`submitted` 不带队列真值时保留此前 pending，由既有 sent 后 state 补拉确认；user 回显后再补一次 state，同轮已消费队列也能及时退场。`agent_settled`、明确空队列取回回执、离线和切回 SDK 清掉提示；不能只改 UI，撤回资格也必须认布尔真值。
- 桥接撤回走原生 editor 的整批 dequeue，回执带文本后才并回输入框；不能为读取队列另建 SDK 句柄，也不能用取出再放回模拟只读查询。无文本快照不代表没有队列，提交成功也不保证某条输入已经执行。
- **停止并取回**：Pi SDK 1.0.4 的 `abort()` 只停止并等待 idle，`clearQueue()` 才清空 steering / followUp 并返回文本。TUI 的 Esc 先 `clearQueue`，把 steering → followUp 用双换行放到现有编辑内容前，再 abort。客户端显式请求 `restoreQueued: true` 时，网关在同会话的 admission 串行区内执行 clearQueue → abort，`aborted.queued` 是取回文本，`queuedMessages` 是清理后的状态。默认不清队列，兼容旧客户端；旧网关的裸 ACK 只补拉 state，不从旧快照伪造取回内容。第二次空闲 abort 不会再产生 settled，需要显式清队列和权威状态回执；被取消的队列不会逐条 settled，按匹配的 idle 回执收敛该会话的 turn 计数。
- **不打断运行的取回**：独立的 `dequeue` 请求只 `clearQueue` 不 abort。`clearQueue` 同步 emit 而网关事件在微任务中 flush，所以在 admission 临界区内同步完成 clear → 快照 → 回执，否则后续 settled 可能先到。回执 `dequeued` 按会话加 `queueRequestId` 关联，载荷、队列快照和运行状态齐全才完成；新发送、派发与取回互斥。
- SDK 只整批返回文本，不提供单条撤回或排队图片恢复，取回不包含附件。不能全清后按手机旧快照重新排队：会重走输入展开、丢图，并重复执行已消费项。
- 停止等待期间阻止同会话再次发送；切会话后迟到的取回结果先按原会话暂存。ACK 丢失的跨连接取回没有协议保证。旧网关没有 dequeue 能力时隐藏入口。

### 发送后的停止冷却

发送后 500ms 内忽略“停止”（`stopCooldownMs`）：由新发送和吸附派发的唯一出口记录时刻，`abortCurrent` 在窗口内直接忽略。run 一开始按钮就从 ↑ 变为 ■，而发送后草稿为空，`canAbort` 立即成立，双击的第二下会落在停止上；曾观察到发图后 87ms 被中止，图片已进入 Pi，模型却未被调用，用户也看不到错误。Pi TUI 的 Enter 与 Esc 是不同按键，不存在这个问题。

## 考虑过的方案

**忙时发送写死为 followUp。** 能被受理，但用户无法选择 steer。

**把本地取回描述为撤回服务端队列。** 两者语义不同，界面和文档都要区分。

**停止与发送拆成两个按钮。** 用户明确要求保留同位切换的主流交互形态，用冷却窗口解决误触。

## 影响

- 取回远端队列会丢失排队消息中的图片。
- 冷却窗口内用户确实想立即停止时需要再按一次。

## 核对入口

- `AndroidSharedStateHolder` 的 `pendingDispatch`、`dispatchPending`、`cancelPendingDispatch`、`capturePendingDispatch`、`stopCooldownMs`、`abortCurrent`。
- 网关 abort 的 `restoreQueued` 分支与 `dequeue` 请求。
- Pi SDK：`agent-session` 的 `abort`、`clearQueue`，TUI 的 dequeue 与 `restoreQueuedMessagesToEditor`。
- 测试：`AndroidSharedStateHolderTest` 的 `takingBackPendingPreservesNewDraftAndAttachmentsBySwapping`、`replacingPendingDispatchKeepsUriImageMappingIntact`、`stopIsIgnoredWithinCooldownAfterSend`；`FineCatBusyDispatchF13DeviceTest`。
