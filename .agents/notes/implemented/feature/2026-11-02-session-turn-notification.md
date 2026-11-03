# Agent Note: 后台回合结束的系统通知

Status: implemented

## 问题

Pi Mobile 的回合在后台继续执行时，用户只能等回到 App 才看见结束。手机尝试通过前台服务保持连接，但连接只用于收事件和保活，没有把“这一轮已经落定”变成系统通知。用户离开会话去处理别的事后，无法知道 agent 什么时候完成，也无法从通知直接回到那一轮所在的会话。

## 决定

- 触发源是 Pi 的 `agent_settled`，由 `PiEventNormalizer` 归一为 `turn/end`。只认这个事件作为一轮结束；`message_end` 只代表一条消息结束，不是回合终态，不能用来发通知。协议见网关 `PROTOCOL.md` §4.5。
- 通知只在用户看不到会话内容时发。基础状态由 `PiAndroidApplication.onStart/onStop` 更新到 `AndroidAppGraph.isAppInForeground`，通知出口再叠加屏幕交互状态和 Keyguard 锁屏状态。只有 App 进程在前台、屏幕亮着且设备已解锁三个条件同时成立才静默；后台、熄屏、亮屏但未解锁都发通知。
- 不能只看进程生命周期。实测亮屏仍停在锁屏时，`ProcessLifecycleOwner` 可能已经回到 FOREGROUND，只凭 `isAppInForeground` 会把通知错误抑制掉。
- 通知内容只放会话标题和结束类型，不放 assistant 正文。结束时读取 `agent_settled.aborted`：true 显示“本轮已停止”，其余显示“Agent 已完成本轮”。锁屏公开版本固定为应用名和“会话已完成”，标题不进入锁屏。
- 每个会话一条通知，Notification tag 带 sessionId；同一会话下一轮结束会替换同一标签。通知使用独立的高重要性 channel，与保活前台服务的低重要性 channel 分开，避免为了省电把完成提醒压成静默。
- 点击通知走 `MainActivity` 的显式 sessionId extra。`MainActivity` 设 `singleTop`，冷启动在 `onCreate`、已有实例在 `onNewIntent` 都调用 `AndroidSharedStateHolder.selectSession`；PendingIntent 的 data URI 也带 sessionId，避免不同会话共用同一个 PendingIntent。通知 `autoCancel`，进入会话时同时按 tag 取消。
- 这套能力是本地通知，不引入 FCM 或网关推送。通知是 best-effort：只有 `agent_settled` 实际到达且用户看不到会话时才发。前台服务只保进程，不持有 WakeLock/WifiLock；息屏或系统省电可能断开 WebSocket，断线期间完成的回合不会补发通知，回前台重连补拉状态。没有正在执行的回合时 App 按既有策略挂起连接，不做额外常驻。
- 断线且本地仍记录运行态时，对话尾部显示“连接中断，状态待恢复”，不继续显示 Working；重连补拉后由真实状态决定。文案判定在 `fineCatWorkingText`。压缩状态标签和输入区停止按钮也按 `connected` 收敛，避免同屏一边提示状态待恢复、一边仍声称压缩中或可停止。

## 考虑过的方案

**用 `message_end` 或最后一次 assistant 文本到达作为完成信号。** 这样看起来更早，但 pi 的 `agent_end` 可能是自动重试中的非终态，真正的回合收尾是 `agent_settled`；提前发通知会把重试中的中间结果当完成。

**只按 Activity 或进程生命周期判断前台。** 实测亮屏但仍锁屏时进程会回到 foreground，用户看不到会话却拿不到通知；必须把屏幕交互与 Keyguard 状态一起纳入。

**复用保活前台服务的通知。** 前台服务通知的重要性是 LOW，且服务在回合结束时会主动移除，既不能形成可点击的完成提醒，也会把“正在运行”和“已完成”两种语义混在同一个通知里。

**点击只打开 App 首页。** 实现更短，但用户还要自己找会话；通知的目的就是回到刚刚结束的那一轮，必须携带 sessionId。

**在通知里显示 assistant 正文片段。** 信息更多，但会把私人会话内容带到锁屏和通知栏；本版选择只显示会话标题和通用结束文案。

**有进行中回合时持有 WakeLock/WifiLock。** 能提高息屏后的连接存活率，但增加耗电，也仍受厂商省电策略影响。轻量档不采用；通知不承诺息屏必达，优先保证回前台补状态和断线提示诚实。

## 影响

- Android 13 及以上的通知权限仍沿用现有的回合开始时的请求时机；用户拒绝通知权限时，保活和会话功能不受影响，只是没有系统提醒。
- App 在后台且没有已知运行回合时连接会挂起，此时在其他设备上启动的回合不会触发本机通知。要覆盖这种场景需要常驻推送通道，超出本地通知的范围。
- 息屏后系统可能断开网络；此时完成通知不保证必达。回前台后自动重连并补拉会话状态，断线期间对话尾部不假装 Working。
- 同一会话的旧通知会被新一轮替换；不同会话各自保留一条，点击各自回到对应会话页。

## 核对入口

- 客户端 `TurnCompletionNoticeFactory`、`SessionTurnNotifier`、`AndroidAppGraph.isAppInForeground`、`PiAndroidApplication.onStart/onStop`、`AndroidSharedStateHolder.afterFramePublish`、`MainActivity.EXTRA_SESSION_ID`、`fineCatWorkingText`。
- 测试 `TurnCompletionNoticeFactoryTest` 覆盖 `agent_settled`、`message_end`、`aborted`、标题回退、非 event 帧，以及前台且已解锁才抑制、锁屏或熄屏仍发送；`FcConversationWorkingTextTest` 覆盖断线运行态显示恢复提示而不是 Working；`TurnCompletionNotificationDeviceTest` 在设备上把 Activity 退到后台，注入 `agent_settled`，核对通知标题/正文与 sessionId 入口。
- 网关 `PROTOCOL.md` §4.4–§4.5；Pi SDK 1.3.0 的 `agent_settled` 与 `aborted` 字段。
- 真机人工检查：后台亮屏时的完成通知与点击回会话、前台解锁时静默、熄屏后连接仍存活时的锁屏通知、断线时尾部恢复提示，均符合预期。熄屏后连接也可能被系统断开，此时无通知，回前台重连补状态；两种结果都在 best-effort 约定内。
