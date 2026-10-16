# Agent Note: 设备回归用例的写法：真实根入口与真实 wire

Status: implemented

## 问题

设备回归用 `FakeTransport` 加真实的 `FineCatApp` 注入网关帧。写这类用例时有几类“静默失败”：不报错，只是等不到，容易误判为产品缺陷。另一方面，直挂组件的 harness 缺少真实根带来的并发请求，整类用例全绿而缺陷仍在。

## 决定

### 走真实根

- 涉及跨组件的并发请求、终态归属、lane 合并取消的链路，验收用例必须走 `FineCatApp` 真实根。例如 Provider 保存：直挂表单的用例没有根上的额外刷新请求，也就没有 lane 取消，缺陷存在时仍全绿。
- 没有 `AndroidAppGraph` 的 holder 会在 Provider 帧处理入口早退，`submitWirePayload` 返回不代表走过处理器。不可达的用例删除，改在可达的纯映射表上测稳定码输出；不为造绿添加生产测试缝。

### FakeTransport 用例的顺序与坑

1. 不要先调 `loadFixtureAndAwaitForTest()`：它用 fixture 快照覆盖投影，之后到达的 `sessions` / `history` 帧被丢弃。顺序是：建图 → `hello` → `sessions` → `setContent` → 显式选中会话 → 等客户端发出 `history` → 回 history。
2. 首页默认停在新会话态，不会自动选中第一个会话；要在主线程显式 `selectSession`。
3. 一轮结束的判据是 `agent_settled`，不是 `message_end`。真实序列为 `agent_start` → `message_start(assistant)` → `message_update` × N（文本在 `assistantMessageEvent.delta`）→ `message_end` → `agent_settled`，没有 `agent_end`。
4. `createComposeRule` 每个用例只允许一次 `setContent`；要测两种终态就拆成两个用例，`@After` 中关闭 holder 并取消 graph 的 scope。
5. 夹具的 `waitUntil` 固定 5 秒且只抛 `TimeoutCancellationException`，栈里没有测试行号。关键等待自己包一层，把“在等什么”和现场状态写进 `AssertionError`。
6. 假网关会让“超时”用例测成“取消”：非附件请求超时后 Runtime 回收整条连接，任何未应答的在途读请求（sessions / providers / models）会先超时并连带取消目标请求。造超时场景必须先应答所有读请求，并断言终态文案；回收后的重连（首个退避 2 秒）需要用例扮演，假传输只在显式 `open()` 时才发 hello。

### 判据

- 判 wire 时取线上原始 JSON（例如 `payloadsOfType("message")` 的 `mode`），不取界面文案。
- 成对断言：“取回”要同时断言草稿回到编辑器且没有发出 message；“N 个 chunk 合并为一条”要同时断言条数只加一且完整正文在同一条上。
- 耗时只作观测记录，不作断言阈值。
- 注入缺陷验证判别力时，先确认注入的路径确实被执行（例如“取回”按钮走的是 `cancelPendingDispatch` 而不是 `dispatchPending`）。

### 日志

产品侧临时日志若一部分用 `Log.i(tag)`、一部分走 `println`（tag 为 `System.out`），`logcat -s tag:I` 会漏掉后者，看起来像代码没执行；抓全量日志再过滤。真机闪退先抓 `logcat -b crash`。

## 考虑过的方案

**组件级 harness 覆盖所有场景。** 运行快，但缺少根上的请求交互，对终态竞态系统性漏报。

## 影响

- 根入口用例更慢、更依赖完整的帧序列，需要维护贴近真实的 wire 夹具。

## 核对入口

- `FakeGatewayFixtures.kt`、`FineCatTimelineRegressionDeviceTest#connectWithOneSession`、`FineCatConversationF02DeviceTest#injectConversation`、`FineCatProviderDirectEntryDeviceTest`、`FineCatBusyDispatchF13DeviceTest`。
