# Agent Note: 起始目录已删除时目录面板的逃生

Status: implemented

## 问题

会话的工作目录在宿主被删除后，会话仍出现在列表：Pi SDK 1.3.0 的 `SessionManager.listAll` 扫描会话存储，不检查 cwd 是否存在，桌面端同样如此，历史仍可打开。这不是缺陷，手机端保留该语义。真正的困局在目录面板：会话内 composer 目录入口以会话 cwd 发 `directories`，网关 `requireAbsoluteDirectory` 对 ENOENT 回 `directory-not-found`；面板失败时没有成功浏览位置，错误态只有「重试」（重发同一条失效路径），「上一级」又依赖成功位置的 crumbs，用户无法切到任何有效目录。

## 决定

保留错误展示与重试语义（失败不是空目录，不静默改位置），在错误态补两条逃生：

- 「上一级」由请求路径字符串推导父级（`directoryErrorParentPath`），不依赖服务端 crumbs；根目录与非绝对路径不给该行。
- 「主目录」发不带 path 的 `directories`，网关缺省回 home；成功后浏览位置接管显示。

面板局部请求目标与 holder 的 `requestedDirectoryPath` 同义（null = 主目录）：「重试」与全局弹窗同源，重试最后一次实际请求；主目录请求失败后的重试仍落在主目录，不退回失效旧路径。逃出后按既有规则选择、继续或新建；不改写旧会话 cwd，不动协议与网关。worktree 面板「选择其他目录…」进入同一面板，同样受益。

## 考虑过的方案

**失败后自动回落 home。** 静默改位置，违背「失败保留状态 + 显式重试」的既有决定；回归用例断言重试使用原请求路径，自动回落会打红它们。

**隐藏目录已删除的会话。** 偏离 Pi 原生 `listAll` 语义，且丢掉仍可读的历史入口；需要额外存在性协议字段才能标记，收益不抵成本。

## 影响

F10 设备用例 `existingSession_directoryClickOpensSheet_headersStayReadOnly` 按 [worktree 决定](../feature/2026-10-18-mobile-worktree-actions.md) 断言会话内目录入口打开选择器并浏览会话 cwd，三视图标题副行只读、旧会话 cwd 不改写；更早的「入口不开选择器」断言与该决定冲突。设备用例不进 CI，需随相关改动显式运行。旧 UI `WorkspaceDirectoryUi` 的重试本就回落 home，未改动。

## 核对入口

- [目录面板与父级推导](../../../../androidApp/src/main/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatDirectorySheet.kt)：`directoryErrorParentPath`、错误态逃生行、可空请求目标。
- [JVM 反例用例](../../../../androidApp/src/test/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatDirectorySheetParentPathTest.kt)与[设备回归](../../../../androidApp/src/androidTest/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatDirectoryEntryF10DeviceTest.kt)：`deletedSessionCwd_directoryError_canEscapeToParentAndHome`（上一级请求父路径、主目录请求不带 path、主目录失败后重试对齐、旧会话不改写）与 `rootDirectoryFailure_noParentRow_homeEscapeRemains`（根失败隐藏上一级）。
- [跨仓一致性](../../../../androidApp/src/test/kotlin/com/finecat/pimobile/android/GatewayConformanceTest.kt)：缺省 path 的 `directories` 应答 `path == home`，保住「主目录」标签的落点契约。
- 网关 `host-fs.ts` `classifyDirectoryError`（ENOENT → directory-not-found）；Pi SDK `dist/core/session-manager.js` `listAll`（不检查 cwd 存在）。
