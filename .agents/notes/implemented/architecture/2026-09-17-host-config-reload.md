# Agent Note: 宿主配置变更的生效方式：调用 Pi 刷新，读取侧门控

Status: implemented

## 问题

手机可以修改 Provider（写 `models.json`）和启用范围（写 `settings.json`），用户也可能在电脑上直接编辑这些文件。早期改完 Provider 不生效：选择器能看到新 Provider，旧会话 `setModel` 却报 `No API key for …`，API Key 轮换也永不生效。根因是网关 `sharedModelRuntime()` 用 `modelRuntimePromise ??=` 永久缓存了启动时的快照，`restartSession` 重建会话仍注入同一实例；代码注释写着“网关只需触发 refresh”，但这一调用从未实现。

## 决定

- `provider-put` / `provider-delete` 落盘后调用 `runtime.refreshModelRuntime()`，内部是 Pi 的 `ModelRuntime.refresh({allowNetwork:false})`，原地刷新同一实例。`models.json` 是 Pi 的进程级配置面，`ModelConfig.load` 只在 `create()` 和 `refresh()` 时读取，没有文件监听。
- 外部可编辑文件的变更检测统一用**读取前 `statSync` 的 (mtime, size) 门控**：变了才重读。不用 `fs.watch` / inotify 作为正确性机制。
- `settings.json` 使用 Pi 公开的 `SettingsManager.reload()`，在**同一实例**上调用，不重建（重建会丢失 `projectTrusted` 和未落盘的写队列）。需要写 settings 的路径（例如删除失效的模型 pattern）先做写前门控 reload 再读当前值，否则陈旧内存全量回写会覆盖用户手改；“reload → 读 → 写”整体串进 `settingsWriteTail`，因为数据面帧处理是 fire-and-forget，异步门控会把读改写切成多步。
- 失败语义：reload / refresh **抛错**（IO、EACCES）时清掉指纹，让下一帧重试；refresh **返回** `getError()`（文件格式坏）时保留指纹，避免坏文件每帧重读。
- 同样的门控用于会话文件投影缓存和历史列表索引：列表 TTL 到期不等于已知会话路径失效；打开已列出的会话可复用路径，但要验证文件存在且 SessionManager 头部 ID 仍匹配，删除、换头或读头出错时清除该路径投影并回到新索引。并发冷读共享同一代索引 promise，失效后旧代不得覆盖新索引。

## 考虑过的方案

**使缓存失效并重建 ModelRuntime。** 新实例与存活会话持有的旧实例并存，形成 split-brain：选择器看到新配置，旧会话仍用旧配置。

**依赖 `restart-session` 生效。** 重建会话仍注入同一缓存实例，不解决问题，还会打断会话。

**用 `fs.watch` 监听文件。** 各平台都会静默失败：Windows 缓冲区溢出丢事件、映射或网络驱动器不工作、编辑器 tmp + rename 使句柄失效；macOS kqueue 递归支持差；Linux inotify 数量上限、NFS / CIFS 不触发；WSL 跨 Windows 与 Linux 边界不触发；容器 bind mount 传播不一致。项目开源后可能运行在任何平台，选型和注释都不能假设某个特定平台。

## 影响

- 修改配置后下一次请求即可生效，不需要重启网关或会话。
- 每次读取前多一次 `stat`。
- 只门控全局 settings（`agentDir/settings.json`），不管项目级 `<cwd>/.pi/settings.json`。去掉串行链的并发丢写实验未能复现问题（Pi 的 `reload()` 会先 await 写队列），串行链是不依赖 Pi 内部时序的确定性保证。
- 排查“改了配置不生效”时，先在 Pi `dist/` 中检索现成的刷新 / 重载 API（例如 `async refresh(`、`async reload(`）及其调用点，再考虑自己设计缓存失效；注释描述的设计意图不等于已经实现。

## 核对入口

- 网关 `src/pi-runtime.ts` 的 `refreshModelRuntime`、`projectionCache`、`settingsWriteTail`；`src/pi-store.ts`；`src/router.ts` 的 provider-put / delete。
- Pi SDK：`model-runtime.js` 的 `refresh()`、`model-config.js`、`settings-manager.d.ts` 的 `reload()`。
