# Agent Note: 桥接旧事件/旧快照回退运行状态

Status: implemented

## 问题

拉式 `state`/`history` 走独立请求 socket，订阅 socket 上较早捕获的 `bridge-snapshot` 与事件可能晚到。修复前有两处会把已应用的空闲状态改回运行中：

1. event 帧先以 raw 状态字段进门控，随后同帧归一事件（`agent_start`→`turn/start`、`settled`→`turn/end`、`compact/*`、`queue/update`）走另一入口无条件生效；
2. 同代际旧快照接受后把状态水位重置为自身 `bridgeSeq`，并把旧 `busy/model/queue` 应用回状态。

## 决定

- raw 状态字段与同帧归一事件共用同一个"状态是否权威"结论：事件帧收敛到 `SharedMobileStore.acceptEventFrame(frame, records)`，raw 不再被重解析，chunk 也不重复走全量归一。裸 `acceptLiveEvents` 在桥接模式下不预设权威（无法自证未过期），SDK 模式行为不变。
- 正文水位与状态水位分开：快照接受后 `eventSeq` 按快照前进，`stateSeq` 只升不降（`maxOf(seq, knownState)`）；严格低于正文水位的快照仍是迟到重放，整帧拒绝；介于两者之间的旧快照正文/工具前缀照投影，状态保持更新的 `state` 结论，也不改抽屉 `isRunning`。
- `SessionListReducer.EventReceived` 带 `statusAuthoritative`，非权威事件只更新标题/时间/未读；`queue/update` 非权威时整条丢弃（它本来不进对话流）。
- 投影层 `acceptLive` 只做对话/历史投影，不再调用 `acceptLiveEvents`，避免第二次应用绕过门控。

## 考虑过的方案

**只在 Runtime 拦一遍。** Store 与抽屉 `isRunning` 各有独立入口，仍然会回退；必须沿既有入口逐层门控。

**旧快照整帧拒绝。** 会吞掉仍需的正文前缀（partial/工具），断线期间只能靠历史补，正在生成的内容会闪断。

**让旧快照重新成为权威。** 会把旧 `model`/`queue`/`busy` 覆盖到更新的 `state` 上，且把 `stateSeq` 降回旧值，使中间水位的旧 `state` 重新生效。

## 影响

只改变桥接状态门控与快照前缀投影，不改协议字段、不改 SDK 模式、不改 Pi 源码。跨 socket 的真实交错仍未在真机弱网下追过。

## 核对入口

- `.pi/tasks/mobile-bridge-repair-20261023/batch-a/owner-review/R5StatusRegressionProbe.java`（主控探针，修复后两行 `busy=false`、rc0）。
- `SharedMobileStoreTest`：`normalizedTurnEventFromStaleFrameDoesNotFlipStreaming`、`staleCompactAndQueueEventsDoNotOverrideNewerState`、`sameEpochOlderSnapshotKeepsNewerStatus`、`strictlyOlderSnapshotContentIsStillRejected`。
- `GatewayRuntimeIntegrationTest.olderSameEpochSnapshotKeepsNewerStateButStillProjectsItsPrefix`、`newEpochSnapshotWithLowerSeqStillUpdatesRunningState`（新代际 wire seq 从 0/1 重计，不能拿旧 `stateSeq` 判过期）。
- `AndroidGatewayProjectionTest`：`lateAgentStartAfterIdleStateKeepsIdleWhileBodyStillProjects`、`lateAgentSettledAfterBusyStateKeepsRunningAndProjectsTool`、`olderSameEpochSnapshotSeedsContentWithoutRollingBackStatus`、`strictlyOlderSnapshotDoesNotReseedItsPrefix`。
- [桥接模式设计](../architecture/2026-10-20-mobile-bridge-mode.md)。
