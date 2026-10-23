# Agent Note: 手动压缩的受理、结束与状态对账

Status: implemented

## 问题

压缩可能持续几分钟。早期客户端只在 `compact-started` 时置位、不消费 `compaction_end`：压缩已经落盘，手机仍显示“正在压缩”。后续又出现几类问题：受理后迟迟没有开始事件被误判为失败；完成后对话的 Working 结束了，抽屉里的会话仍显示进行中；快速拒绝时 Android 前台服务崩溃。

## 决定

### 主链

- 入口在输入框展开态的上下文占用圆环，详情中确认后调用 `startManualCompact` → 网关 `beginCompact` → 网关锁定的 Pi SDK `session.compact()`，在宿主网关进程执行；不另起桌面 Pi，也不在手机实现摘要。输入 `/compact` 只提示从圆环进入。
- SDK 手动压缩先 abort 当前回答，再触发 `session_before_compact`；扩展返回摘要时采用扩展摘要，否则用内置摘要。实际采用哪种策略取决于宿主加载的扩展。
- 协议采用异步受理：`compact-started` 只表示已受理，完成看事件和 state，避免长压缩占满请求超时窗口。

### 受理不等于开始

- SDK 1.0.4 的 `compact` 先 await abort / `waitForIdle`，之后才设置原生 `isCompacting` 并发出开始事件。ACK 后 8 秒没有开始事件不能证明请求未执行：客户端只提示，保留本地等待。
- 网关以每个句柄的在途 Promise 覆盖启动期，合并重复调用，并纳入 busy 与回收门控；等待 abort 期间 ACK 仍报 busy。
- 结束时网关先排空原生事件，再推送 `compact-finished` 快照。完成通知只证明收尾，不是成功声明；它严格关联会话，不消费普通 state 请求。完成通知不直接清当前本地请求槽位：上一轮释放后用户可能已经发起下一轮，迟到的通知会误释放新等待。新网关的 ACK(true) 负责交接槽位。

### 事件映射

| 原始事件 / 数据 | 客户端归一 |
| --- | --- |
| `compaction_start` | `compact/start`，`isCompacting=true`，不渲染消息 |
| `compaction_end` 成功 | `compact/end`，清除 `isCompacting` |
| `compaction_end` 失败 / declined | `notice/compact`，清状态并显示失败或未执行行 |
| `entry_appended(compaction)` | `compact/done`，显示成功标记 |
| history 中 `role=custom, customType=compaction` | 还原为 `compact/done` |
| state / history.state | 同步 `isCompacting` 快照 |

`compact/start` 只更新压缩标志，不清流式标志；两者不是互斥枚举。网关捕获到启动异常时只写日志，不保证另发失败帧（例如未选模型）。

### 抽屉对账

空闲时手动压缩没有 `agent_settled`。会话列表的运行标记与压缩详情必须用同一份会话状态 `isStreaming || isCompacting` 对账，覆盖 compact-started / finished、state、重连 history 和原生生命周期事件；任一侧未结束都仍为运行中。真实压缩错误可标失败，aborted / declined 不算失败。失败不写入 Pi 消息文件，重拉旧的成功历史不能抹掉刚发生的失败；按同宿主时间与历史最新结果对账。旧的实时 seq 可能排在新 history 之后，不能只取最大 seq 判断新旧。

### 上下文用量未知

压缩后尚未得到成功 assistant 的 usage 时，SDK `getContextUsage` 保留窗口大小，tokens / percent 为 null。界面显示“— / 窗口”，不是 0%，也不回填旧数字。打开历史会话时，未打开 SDK handle 的 history 读取可能返回 `contextUsage=null`；收到当前会话的 subscribed ACK 后再异步请求 state，ACK 之后迟到的空统计 history 也要补取。不在帧消费回调里同步等 runtime 锁，生产者可能正在等消费队列腾位。

### 快速拒绝与前台服务

SDK 可能在开始后立即以 `Nothing to compact (session too small)` 拒绝，这是合法业务结果。Android 上若 Application 在运行态 true → false 时先 `startForegroundService`、再外部 `stopService`，后者可能早于服务内的 `startForeground`，系统会发出前台服务崩溃消息。现在 Application 只请求启动，服务每次 `onStartCommand` 先注册前台，再在主线程观察运行状态自行停止；`stopSelfResult(startId)` 拒绝旧请求时保留通知，新 startId 即使状态没变也检查一次空闲。原生明确的“无内容”“已压缩”错误映射成对应短提示，其他错误用通用短提示。

## 考虑过的方案

**按等待秒数判定启动失败并允许重试。** SDK 先等 abort，长时间无开始事件是正常的；重试会产生重复压缩。

**单独建立跨层的 transient notice 通道显示失败。** 已有的 notice 系统行足够。

**用延迟或去抖掩盖前台服务的顺序问题。** 只缩小窗口，不消除竞态。

## 影响

- 新旧网关混用时只保证旧行为；完整语义需要客户端与网关一起升级。
- 失败结果只在内存中保留，进程重启后消失。

## 核对入口

- 客户端 `AndroidSharedStateHolder.startManualCompact`、`PiEventNormalizer`、`SharedMobileStore`、`SessionListReducer`、`GatewayForegroundLifecycle`、`GatewayConnectionService`、`PiAndroidApplication`。
- 网关 `beginCompact`、`compact-finished` 通知；协议 `PROTOCOL.md` §4.16。
- Pi SDK：`agent-session` 的 `compact`、`getContextUsage`。
