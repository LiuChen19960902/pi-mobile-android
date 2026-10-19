# 架构

Pi Mobile 是 Kotlin Multiplatform 项目：KMP `shared` 模块承载跨平台协议、领域逻辑与连接运行时；Android 侧提供 Compose UI、平台能力，并持有草稿、编辑确认等界面状态。
`shared` 目前只编译 Android 目标；本项目不做 iOS 客户端（见 README“支持的环境”）。

## 1. 分层

```
┌─────────────────────────────────────────────┐
│ androidApp：Jetpack Compose UI              │
│   ui/            页面与显示 DTO（无业务规则）│
│   android/       AndroidSharedStateHolder： │
│                  MVI 门面与 UI 状态持有      │
│   platform/      OkHttp transport、Keystore │
│                  凭据、DataStore 偏好、网络…  │
└───────────────┬─────────────────────────────┘
                │ 只经 shared 的 platform 契约接口
┌───────────────▼─────────────────────────────┐
│ shared（commonMain，无平台依赖）             │
│   facade/     Store + MVI Intent/Event      │
│   projection/ 会话时间线与轨迹投影           │
│   sync/       历史分页与 seq 对消            │
│   domain/     会话列表 reducer              │
│   gateway/    GatewayRuntime（连接与请求编排）│
│   protocol/   帧 DTO、wire 解码、事件归一器   │
│   platform/   平台契约（transport/凭据/时钟…）│
└───────────────┬─────────────────────────────┘
                │ WebSocket 帧（契约见网关仓库 PROTOCOL.md）
        pi-mobile-gateway（姊妹仓库） → pi
```

正式入口使用 `FineCatApp`；旧 `PiProductApp` 保留为 debug 对照。FineCat 会话只有对话与树视图，轨迹投影用于对话里的思考和工具步骤。

Pi 接入以网关实际锁定的 SDK 契约为边界；通用消息/工具语义、扩展 UI 与插件私有状态的区分见 [Pi 支持边界](Docs/pi-compatibility.md)。SDK 的 RPC 模式绑定不等于镜像终端或完整接入扩展 UI。

依赖方向单向向下：`ui → android 门面 → shared → platform 契约`。`shared` 不得 import 任何 Android 类型。

Tree的浏览选择与真实active leaf分开。`tree-view-v1`在网关计算筛选/搜索/折叠与前缀；`tree-actions-v1`提供entry全文复制和追加LabelEntry。客户端按会话+entry关联离散动作，导航/改配清理旧发布态；只有明确跳转才调用既有switch-branch。标签钉子只跟随其运行时后代，普通手动跳转钉子保留原行为。协议真源见网关 `PROTOCOL.md` §4.26–4.27。

工作区入口在输入框上方目录条。`worktree-v1` 提供真实 Git 发现/创建、延续会话与结果确认；首页目标和当前会话目录分离。已有会话入口复用全局`sessions`，以本次应答ID与精确cwd筛选，选择后经项目信任检查走普通会话导航；源稿和目标稿分别保存。网关以 Pi 公开 API 在目标建立独立上下文，手机在权威结果后切换订阅并迁移草稿。重试、活动分支持久化与项目信任的依据见[工作区记录](.agents/notes/implemented/feature/2026-10-18-mobile-worktree-actions.md)。

## 2. 一条消息的生命周期

1. Compose 事件 → `AndroidSharedStateHolder` 的语义化方法 → `GatewayRuntime.sendMessage(...)`
2. Runtime 在**串行边界**（同一把 `Mutex`）内记账本轮 turn、按 lane 策略排队或直发
3. `GatewayTransport`（Android 实现基于 OkHttp）发出文本帧；每个 socket 打一个**代次（generation）**，入站帧与状态事件都带代次，旧代次一律拒绝
4. 服务端 `event` 帧 → `PiEventNormalizer` 把已支持的 Pi 事件归一成内部视图事件（`turn/start`、`assistant/chunk`、`tool/call`、`turn/end`…），交给投影与 UI
5. 投影更新 → 状态门面同步 Compose 状态 → UI 重组（Runtime 内部通过 `StateFlow` 提供连接状态）

支持 `prompt-disposition-v1` 时，待回执输入与实际运行/压缩分别记账；`sent` 回传 SDK 输入处置和受理后状态，网关先排空旧事件再同步发快照回执。`handled` 仅表示该输入被消费，不能当作运行结束。实际模型运行以 `agent_start` / `agent_settled` 更新，权威状态/历史补拉纠正断线期间的活动状态；不合成结束事件。旧网关保留原计数路径及其边界。

## 3. GatewayRuntime 的关键设计

连接与请求状态通过同一把 `Mutex`（`serialized { }`）串行更新，避免并发下的状态分叉；附件缓存的读写在锁外执行。

**请求 lane**：按 `responseKind` 建 lane，策略三种 —— `FIFO`、`COALESCE_LATEST`（同类请求只保留最新，例如 `sessions` 刷新）、`REJECT_IF_BUSY`。
一般请求超时（默认 30s）会结束该活动请求并回收连接，不自动重发它；回收时其他在途请求也可能被取消。输入资源和补全查询超时仅结束该关联请求，避免打断生成。重新收到 `hello` 后刷新 `sessions`、恢复当前订阅，并重发已转入 deferred 的排队请求。

**连接与重连**：

- `desiredConnection` 表示用户意图；`reconnectBlocked` 表示"再试也没用"（凭据被拒、网关禁用、不可恢复错误）
- 退避：`2s << attempt`，上限 30s；网络恢复、回前台、用户显式操作都会立即重试
- 后台且无进行中 turn → 主动挂起连接（省电），回前台重连；有进行中 turn → 保持前台服务式保活，并有 60s 恢复窗口兜底

**多入口选路**（`endpointOrder` + 游标）：

- 有序地址列表来自偏好设置，一轮内依次尝试；**连上哪个都不重排列表**，否则一旦走了 tailnet 就再也不重探局域网
- 每地址握手看门狗：非末位候选 2.5s（黑洞地址快速放弃，不计退避次数），末位候选 10s（容忍冷启动慢的 mesh VPN）
- 网络切换事件、回前台、整轮走完 → 游标归零，重开一轮

**凭据模型**：

- 配对码一次性；成功后服务端下发长期 token，客户端存平台安全存储
- token 是**设备级单份**，不按地址分存（网关侧 token 与接入地址无关）
- 配对被拒不删除已有 token；有 token 时可退回 Bearer，无 token 则停止重试。非配对期，备用地址的 401 保留凭据并返回首地址；首地址的 401 删除 token 并停止重连，明确撤销码 4003 也会停止重连。单个备用地址拒绝不能证明原宿主凭据失效

## 4. 平台契约（`shared/platform`）

`shared` 只声明接口，Android 提供实现：`GatewayTransport`、`GatewayCredentialStore`、`GatewayPreferences`、`GatewayNetworkMonitor`、`GatewayAttachmentCache`、`GatewayClock`。
好处是 `commonTest` 用假实现就能覆盖连接编排、lane、重连、选路、凭据这些最容易出并发 bug 的路径。

Android 实现要点：

- **transport**：OkHttp WebSocket，事件（状态与帧）走同一条有序队列，防止帧越过其所属状态被处理；入站消息有大小上限
- **凭据**：Android Keystore AES-GCM，AAD 绑定键名，密文落 `no_backup` DataStore；普通偏好里永不出现敏感明文；旧版按地址哈希分存的条目会自动迁移
- **附件**：出站上限 4 张 / 单张 base64 ≤ 4.9M 字符 / 总量 ≤ 16M 字符；入站缓存 8MB 上限 + LRU 缩略图

## 5. 失败与不可信输入的处理

- 入站帧解码失败、代次不匹配、会话不匹配 → 拒绝并计数，不污染投影
- 任何 collector 抛异常 → 连接进入 `FAILED` 并作废代次（fail-closed），而不是静默继续
- 日志与 `toString()` 对 endpoint、token、pairingCode、原始帧文本一律 redact；诊断只允许输出白名单内的稳定错误码

## 6. 测试策略

| 层 | 位置 | 覆盖 |
| --- | --- | --- |
| KMP 纯逻辑 | `shared/src/commonTest` | 协议解析、事件归一、投影、历史同步、Runtime 的 lane/超时/重连/选路/凭据（假 transport 与虚拟时钟） |
| Android 单测 | `androidApp/src/test` | 状态门面、投影、平台边界、**跨仓库协议一致性** |
| 跨仓库一致性 | `androidApp/src/test/.../GatewayConformanceTest.kt` | 启动真实 fixture 网关跑往返，防两端各自漂移 |
| 真机 | `androidApp/src/androidTest` + 手工 | Compose 渲染、Keystore/DataStore、后台生命周期 |

协议变更必须**先改网关仓库的 `PROTOCOL.md` 与一致性测试**，再动本仓库客户端。

手动上下文压缩由composer圆环触发。客户端以请求对象隔离本地等待；8秒只提示，不推断可重试。网关的每句柄在途调用涵盖SDK等待abort的启动期并合并重复触发，收尾先排空原生事件，再发独立compact-finished状态通知。该通知校验会话/活动字段，不消费state请求，也不认领可能已属于下一轮的本地等待。
