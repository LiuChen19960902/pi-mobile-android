# Agent Note: 扩展状态行只桥接 Pi 的 setStatus

Status: implemented

## 问题

Pi 扩展（例如在后台派子 agent 的扩展）会通过 `ctx.ui.setStatus(key, text)` 在终端状态栏写一行提示，例如「1 agent running」。手机看不到终端，用户不知道后台还有任务在跑，容易以为会话已经空闲。

这类提示既不是 Pi 消息也不是工具结果：它没有条目身份、不进会话历史，终端侧的显示状态也不落盘。要在手机上显示它，需要一条独立的实时通道，并且要说清它和「会话是否在运行」的关系。

## 决定

- 网关只桥接 Pi 公开扩展 API 里的 `setStatus`：创建 `AgentSession` 时 `bindExtensions({mode: "rpc"})` 收到的 `uiContext` 只实现该方法，其余扩展 UI（`select`/`confirm`/`input`/`notify`/`setWidget`/自定义组件）保持 no-op。不修改 Pi 源码。
- 线上是完整快照而非增量：`{"kind":"extension-status","sessionId":<uuid>,"statuses":[{"key","text"}]}`，空数组表示清除该会话全部状态。`subscribed` 与 `session-restarted` 应答带同一个 `statuses` 字段作为初始化快照，能力名 `extension-status`。
- 客户端只存 `Map<sessionId, List<GatewayStatusEntry>>`（`SharedMobileStore`）：任何带 `statuses` 且 `sessionId` 非空的帧整表替换该会话的列表，过滤空白 `key`/`text`，同 `key` 后者覆盖前者并保持到达顺序，空列表删除该会话条目。`extension-status` 缺 `sessionId` 时无法归属，直接忽略。
- `extension-status` 列入 `GatewayRuntime.UNCORRELATED_KINDS`：它由服务端主动推送、不对应任何在途请求，显式声明后不走响应关联分支，也不会被当成 `no-active-request` 拒绝。
- 界面在旧会话页与 FineCat 会话页都显示一行状态（共用 `ui/ExtensionStatusBar.kt`，`testTag("extension-status")`，颜色由各自主题传入）：仅当当前选中会话有状态且 hello 宣告了 `extension-status` 时出现，文本前带保留最小字宽的装饰盲文动效，最多两行、最多 4 条、不可点击、不解析内容；动效跟随非空状态行存在，不证明扩展任务仍在运行；字号与行高显式对应原型，避免继承正文行高挤占输入区。旧网关没有该能力时不显示空占位。FineCat 是默认界面，不能只接旧页。
- 状态与 `sessionStates` 互不影响：`extension-status` 不写 `isStreaming`/`isCompacting`，也不改会话列表的忙闲标记。

网关对 key 只做 trim，超过 64 字符时忽略设置和清除，不生成缩写。长 key 的哈希缩写仍可能与另一合法短 key 相同，导致覆盖或误清；拒绝越界输入可以保留其他扩展的状态身份。该限制只作用于传输桥，不改变 Pi 原生的 key 范围。

## 状态不是任务终态

空 `statuses` 只说明扩展清了自己的状态行，不说明 agent 已结束；非空也不证明仍在运行——扩展崩溃或忘记清除时状态会残留。因此它不能用于保活、重连或会话回收判定；忙闲仍只由 `isStreaming`/`isCompacting`、`turn/start|end` 与 `agent_settled` 决定。

重连语义：连接重建后会话侧的扩展状态可能已经变化，重连 catch-up 的 `subscribed` 快照是唯一的纠正来源；`extension-status` 不占 `event.seq`，不参与事件水位与历史对消。`restart-session` 同理：网关在迁移订阅者后无条件补发完整快照（新扩展可能在 `session_start` 就发布状态，空状态也要发），发起连接另有 `session-restarted.statuses`。快照可能先于 `session-restarted` 到达，两者内容一致且幂等，客户端以最后一份完整快照为准。

## 网关初始化与重载

所有会话构造入口共用按会话 ID 的在途 Promise，包含普通打开与 Worktree 继承目标；目标构造跨过索引 TTL 后，普通打开也不能抢先注册另一份运行器。扩展绑定（`bindExtensions` → `session_start`）是异步的，但句柄必须先登记才能让后续调用等待同一初始化。网关因此给每个句柄挂 `ready`：`openSession`/`state`/`prompt` 等入口等待它完成，sweeper 与句柄容量淘汰跳过 `initializing` 的句柄。`restart-session` 按会话串行执行，避免两路 dispose/open 互相删掉新句柄；迁移订阅者后无条件广播完整状态快照，覆盖“旧状态为空、新扩展启动即发布”的情况。

继承副本由公开会话头的 `parentSession` 识别。普通重开和重载都使用显式项目资源信任解析；只有正在执行的继承操作设置运行门禁。否则 SDK 默认 `SettingsManager` 将项目视为已信任，可能在重开时执行此前未批准的项目扩展。模型回退拒绝仍只作用于本次继承，普通重开保留 Pi 的恢复行为。

同次构造失败时，等待者共同收到失败，不自动转宽松路径重试，避免以模型降级掩盖严格继承失败；用户后续主动重开是独立操作，构造锁在失败后释放。

## 考虑过的方案

**网关读插件私有状态或按插件名探测。** 这类实现细节随插件版本漂移，也与「只对齐 Pi 公开契约、不为第三方扩展做专用适配」的边界冲突。

**桥接整套 `extension_ui_request/response` UI 协议。** 对话框、通知和 widget 都需要交互面与取消语义，本版没有对应界面；整套桥接会让扩展卡在等待应答上，比只支持状态行风险更大。

**手机端把状态文本当作运行信号。** 能让「有 agent 在跑」的提示更及时，但把显示提示升级成了任务契约：扩展忘记清除状态时会话会永远显示为忙，发送键与保活逻辑都会跟着错。

## 影响

- 状态行是单向只读能力：用户能看到后台还有东西在跑，但无法在手机上响应扩展的任何交互。
- 快照语义让客户端不必处理增删顺序，重连与 `session-restarted` 复用同一条路径；代价是每次变化重发全部行，实际规模很小（网关侧最多 16 行、每行 200 字符）。
- 旧网关没有该能力，手机不显示状态，也不会因此发请求或报错。

## 核对入口

- 客户端：`GatewayDtos.kt` 的 `GatewayStatusEntry` 与 `GatewayFrame.statuses`、`SharedMobileStore.replaceExtensionStatuses`、`GatewayRuntime.UNCORRELATED_KINDS`、`ExtensionStatusBar` 与 `FineCatApp`；根入口显示/清除/会话隔离用例为 `FineCatExtensionStatusDeviceTest`，设备用例编译不等于实际运行；测试 `SharedMobileStoreTest`、`AndroidGatewayProjectionTest`、`GatewayRuntimeIntegrationTest` 与跨仓库 `GatewayConformanceTest`。
- 网关：`src/extension-status.ts`（`createExtensionStatusStore`、`uiContext`）、`src/router.ts` 的 `subscribe` 与 `restart-session` 应答、PROTOCOL §4.29。
- Pi SDK 1.3.0：`ExtensionUIContext.setStatus` 与 `AgentSession.bindExtensions({mode:"rpc"})`；升级后按符号重新核对 no-op 方法与 `statuses` 字段。
