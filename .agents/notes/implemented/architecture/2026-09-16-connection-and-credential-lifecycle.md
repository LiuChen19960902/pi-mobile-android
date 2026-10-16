# Agent Note: 连接、配对与凭据的生命周期边界

Status: implemented

## 问题

连接层的状态来自多处：网络状态流、前台生命周期回调、配对流程、多个入口地址、Keystore 中的凭据。几个历史缺陷都发生在这些来源的交界处：一次性配对被迟到的回调打断、只清一半凭据导致永久卡死、备用入口的 401 误删了主入口的有效凭据、重新配对后旧网关的帧仍被消费。

## 决定

### 首次配对与迟到回调

`GatewayRuntime` 初始化时异步接收网络状态流的首值，`applicationDidBecomeActive` 也异步排队。若配对先开始，初次的 AVAILABLE 或迟到的前台回调曾被当作重连，再 open 一次，使一次性配对码失败。现在网络只在“不可用 → 可用”转换时重拨，前台回调在 CONNECTING / AUTHENTICATING 状态下不重开；真实的 WAITING / SUSPENDED 恢复仍走原流程。

### 凭据丢失是整库事件

确认 `keyExists()==false` 时，`DEVICE_ID_KEY` 与 `TOKEN_KEY` 必须一起清除；解密抛异常时保留密文。启动时“先 loadToken 重建密钥”和“先读 deviceId”两种顺序都存在，只清一边会在另一种顺序下永久卡死。legacy 条目不动，由迁移或 401 路径清理。

### 多入口与 401

入口列表表示同一网关的多个网络地址（如局域网与 Tailscale），共用一个 token 槽。非配对模式下，**备用入口**的 HTTP 401 不足以证明首入口 token 失效：保留 token、回到首入口并沿用退避；首入口 401 仍清理并阻断，4003 明确撤销也阻断。备用 401 暂未回到首入口时显示中性的连接失败，不提示重新配对。设置页区分等待网络、连接中、鉴权中和需要重新配对。

### 显式重新配对

- 显式重新配对把载荷中的 `publicUrl` 作为本次目标并置于首位，旧入口保留相对次序；配对码阶段只尝试签发地址，收到 `paired` 后再恢复普通备用列表。新目标失败时保留目标并提示失败，不悄悄回到旧宿主。
- 输入通道的 `connectionGeneration` 只能挡住尚未处理的旧传输帧，挡不住已进入 UI 事件队列的旧帧。客户端本地的 `gatewayRevision` 随显式配对递增并绑定到帧上，holder 消费前核对；接收、发事件与配对共用同一 Mutex。这不扩展 wire 协议。
- 远端投影、Provider、模型范围、目录、树和联想缓存在真正收到 `paired` 回执时清空，后续刷新沿有序 hello 执行。不能在观察到 CONNECTED 时抢先刷新：独立的 StateFlow 消费者可能先看到 CONNECTED 并用旧会话发 catch-up。普通断线重连保留会话和草稿。

### 订阅摘除

restart 后摘除订阅调用 `runtime.unsubscribe(sessionId, sink)`，按当前 handle 摘除。`restartSession` 在 runtime 层把所有连接的 sink 迁入新 handle，按连接保存的旧 detach 只对废弃 handle 生效，非发起方连接摘不掉，还会因 `subscribers.size > 0` 卡住清理。

## 考虑过的方案

**测试里提前 `runCurrent` 或反复重跑来消除配对竞态。** 只遮盖了生产竞态；改为用可控调度复现 open 次数从 1 变 2，再修判定条件。

**任何入口 401 都清除凭据。** 混入另一主机的旧入口后会删掉有效凭据并封锁重连。

**在路由层刷新发起方的 detach。** 当年这样修过一次，非发起方连接仍摘不掉，已推翻重做。

## 影响

- 多主机各自独立凭据尚未支持；入口列表只能服务同一网关。
- 401 的现场因果只能部分复现，凭据是否曾被删除需要客户端历史才能确认。

## 核对入口

- `GatewayRuntime`（`openTransportLocked`、网络与前台回调处理、多入口选路、`gatewayRevision`）、`AndroidGatewayCredentialStore.clearStaleCredentials`。
- 网关 `src/pi-runtime.ts` 的 `unsubscribe` / `restartSession`，`tests/router.test.ts` 的订阅用例。
- 测试用虚拟时间验证退避：既有立即完成的 FakeClock 无法证明真实退避时长。
