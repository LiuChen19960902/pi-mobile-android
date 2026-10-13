# 架构

Pi Mobile 是 Kotlin Multiplatform 项目：**KMP `shared` 模块是业务状态与纯逻辑的唯一来源**，Android 侧提供 Compose UI、状态门面与平台 effect。
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

依赖方向单向向下：`ui → android 门面 → shared → platform 契约`。`shared` 不得 import 任何 Android 类型。

## 2. 一条消息的生命周期

1. Compose 事件 → `AndroidSharedStateHolder` 的语义化方法 → `GatewayRuntime.sendMessage(...)`
2. Runtime 在**串行边界**（同一把 `Mutex`）内记账本轮 turn、按 lane 策略排队或直发
3. `GatewayTransport`（Android 实现基于 OkHttp）发出文本帧；每个 socket 打一个**代次（generation）**，入站帧与状态事件都带代次，旧代次一律拒绝
4. 服务端 `event` 帧 → `PiEventNormalizer` 把 pi 的事件序列归一成内部视图事件（`turn/start`、`assistant/chunk`、`tool/call`、`turn/end`…），下游投影与 UI 不感知 pi 与 dsh 的差异
5. 投影更新 → 状态门面同步 Compose 状态 → UI 重组（Runtime 内部通过 `StateFlow` 提供连接状态）

回合结束判定用 `agent_settled`（pi 语义：一轮运行彻底落定，含重试），它同时决定后台保活是否释放。

## 3. GatewayRuntime 的关键设计

所有可变状态与平台 I/O 都过同一把 `Mutex`（`serialized { }`），避免并发下的状态分叉。

**请求 lane**：按 `responseKind` 建 lane，策略三种 —— `FIFO`、`COALESCE_LATEST`（同类请求只保留最新，例如 `sessions` 刷新）、`REJECT_IF_BUSY`。
一般请求超时（默认 30s）会回收连接并重放未完成项；输入资源和补全查询超时仅结束该关联请求，避免打断生成；`hello` 之后统一 replay（`sessions` + 当前订阅 + 排队请求）。

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
- 401 语义严格区分：正在配对时收到 401 → 报 `pairing-code-invalid`、**不删本地 token**、立即退回 Bearer 重连；无配对码时收到 401 → 才认定凭据失效并删除、停止自动重连

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
