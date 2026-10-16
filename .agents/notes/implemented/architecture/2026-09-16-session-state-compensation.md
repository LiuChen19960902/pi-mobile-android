# Agent Note: 会话运行状态的结束信号与补偿路径

Status: implemented

## 问题

手机上“Working 一直转”“Steering 已经被消费却还挂着”“强退重开后 user 消息消失”“出错了却没有任何提示”这类问题，根因几乎都是某个结束信号没被消费，或者断线期间错过了它。改动画、加轮询都修不好陈旧状态。

## 决定

### 什么才算结束

- 一轮工作的结束以 `agent_settled`（客户端归一为 `turn/end`）为准。自动重试期间 `agent_end.willRetry=true` 不是终态，不清 `isStreaming`；错误消息本身也不结束 Working。
- Pi SDK 的模型 / API 失败不是 `prompt()` reject，而是 assistant `message_end` 中 `stopReason="error"` 与 `errorMessage`。空正文也要归一成一条 `assistant/message`，再由 `ConversationProjector` 生成可见的错误行。错误去重只按相同文案或条目身份，不用“连续错误只显示一条”的布尔标记，否则重试返回的新根因会被吞掉。
- 压缩失败的形态是 `compaction_end` 中 `result: undefined` 加 `errorMessage` 或 `aborted: true`；`result` 是成功时的对象，不能按字符串判断。
- Working 的显示条件是所选会话 `isStreaming || isCompacting || 服务端队列非空`。

### 轮内与重连补偿

- `queue_update` 归一为 `queue/update`，`SharedMobileStore` 更新对应会话的 `queuedMessages`，两个队列都空时置 null。轮内 steer 被消费后即时清除吸附条；`turn/end` 后补拉 state 作为兜底。
- 重连拿回 history 时同步消费 `page.state` 快照（`isStreaming`、`isCompacting`、模型、队列、上下文用量）。Android 的 history 分支先走专门的 `acceptHistory`，再把原始帧交给 `mobileStore.acceptFrame`；只补其中一层不会生效。
- 当前选中会话收到 `turn/end` 后，客户端请求一次 `preserveLive` 的最新窗口重拉，不先清缓冲；不做断连检查，事件帧到达本身就是连接证据。非选中会话的 `turn/end` 不触发。

### 未落盘窗口

强退后重开，history 可能读不到已经发出的 user 消息。存在两个窗口：

1. 新会话首轮：Pi SDK 的 `SessionManager._persist()` 在尚无 assistant 条目时延迟创建文件，user 条目只在内存树里。
2. 旧会话流式中：`_handleAgentEvent` 对 `message_end` 先 await 扩展事件和 emit，再同步 `appendMessage`，两者之间有微任务窗口。

网关 history 通常读文件；仅当文件视图是 live 条目的前缀，且属于上述窗口（首轮无 assistant，或会话正在流式）时，才把 live `SessionManager` 中尚未落盘的条目并入，并以 live 叶子作为 `latestEntryId`。前缀不成立说明文件被外部重写，不并入，防止复活已删除的条目；非流式旧会话即便 live 领先也不并入。

### 会话失败标记

抽屉中的失败标记只取规范化后的 `assistant/message isError=true`（对应 `stopReason=error`）；工具错误、主动取消和断网不算会话失败。按合并后的最新 assistant / user / turn-start 判断，新输入、重试或成功会替换旧结果，更旧分页中的 error 不覆盖较新的成功。未读取过历史的会话保持未知，不为显示图标预拉历史。`selectSession` 会清显示缓冲并重拉，已知结果因此单独保存在内存集合中，挂起的重拉意图携带 `preserveFailure`。

### turn 计数

`GatewayRuntime` 的 turn 是按会话计数，recycle 释放和 `agent_settled` 释放都是减计数，区分不了属于哪条消息。“旧消息未 ACK 就 recycle、旧 settled 在新消息发出后到达”会提前吃掉新消息的 turn；窗口很窄，作为已接受的残余风险记录在 `recycleConnectionForDeferredLocked` 的注释中。根治需要引入代次或请求标识。

## 考虑过的方案

**定时轮询 state。** 增加流量且仍有窗口；已有的事件和快照足以补偿，问题在于没有消费。

**无条件合并 live 快照到 history。** 会把外部重写已删除的条目复活。

**`turn/end` 后先清缓冲再重拉。** 正在看的对话会瞬时变成加载态并丢失滚动位置。

**给 turn 加“精确到消息”的语义。** 需要协议级请求标识，目前不值得。

## 影响

- 状态问题优先沿这几条路径排查，而不是改 UI 计时器。现象与已修场景不完全吻合时，先确认现场属于哪个窗口（例如新会话首轮还是旧会话流式中），不要把最接近的已知缺陷当成根因。强退不一定及时产生 WebSocket close，旧连接可能被网关认为长期存活，这会放大问题但通常不是首因。
- 每次 `turn/end` 都多一次 history 请求。
- 网关的未落盘修复只在网关重启后生效，单独更新手机包不够。

## 核对入口

- 客户端 `PiEventNormalizer.kt`、`SharedMobileStore.acceptEvent` / `acceptFrame`、`AndroidGatewayProjection` 的 history 分支与 `failedSessionIds`、`AndroidSharedStateHolder.afterFramePublish`、`GatewayRuntime` 的 mark/releaseTurn。
- 网关 `src/pi-runtime.ts` 的 `history` 与 `fileIsLivePrefix`。
- Pi SDK：`agent-session.js` 的 `_handleAgentEvent`，`session-manager.js` 的 `_persist`。
- 测试：`PiEventNormalizerTest`、`SharedMobileStoreTest`、`turnEndReloadsSelectedHistoryWhenIdle`、`turnEndOfOtherSessionDoesNotReloadSelectedHistory`、网关“首轮未落盘”和“旧会话流式 / 外部重写”用例。
