# Agent Note: 历史按活动分支原始条目分页

Status: implemented

## 问题

手机只加载部分历史，向上滚动时再取更早的页。分页要回答几个问题：翻的是哪条链、一页按什么计数、遇到不可显示的条目怎么办、单轮过大怎么办，以及最新窗口重拉时如何不丢已经加载的内容。早期实现用 Pi 的 `buildContextEntries` 生成每一页，曾跨页重复 85 条、`limit=60` 只渲染 9 条，压缩之前的历史也无法到达。

## 决定

### 翻哪条链

- 网关用 `sliceActiveBranchTail` 切文件中的原始活动分支。`buildContextEntries` 服务于模型上下文，会丢弃和重排条目，不能用于浏览分页。手机加载了多少历史与 Pi 的模型上下文无关。
- 读取当前会话文件而不是只读 live handle 的内存快照：桌面 CLI 写同一文件后内存快照会过时。投影缓存按 (mtime, size) 门控。新会话首轮尚未落盘时 `statSync` 可能返回 ENOENT，按缺失文件处理。
- `navigateTree` 切换的叶子只在运行时改变，不写入文件；网关用进程内的 `manualLeafIds` 补充活动链和 `tree.leafId`。网关重启后不保证保留手动叶子。

### 一页怎么计数

- 宣告 `history-turns-v1` 的网关按完整轮分页，首批 10 轮：从真实 user 到最终 assistant 为一轮，toolUse 不结束一轮，steer 仍在轮内，aborted / error 结束一轮，最新未完成的一轮可见。字节预算按整轮执行，不能在 `capHistoryPage` 再次截开一轮。旧网关按原始条目计数，工具结果和元数据也占名额，一页不等于一屏气泡。
- 服务端游标可能指向不渲染的元数据，客户端优先使用服务端 `nextCursor`，不用首个可见条目代替。
- 网关转换后若某页没有任何 wire 消息，沿同一活动链跳过纯元数据页，不能把空页的 `hasMore=false` 当成链头。客户端收到全是隐藏消息的页时仍有游标，分页门槛用 `selectedHistoryNextCursor`，不要求已有可见事件。
- 只有用户在顶部实际向上滚动才请求更早的页；短内容也能触发，不自动灌满视口。

### 单轮过大

- 单轮超过 2 MiB 页预算时，即使 `turnLimit=1` 网关也返回 `history-turn-too-large`。客户端仅对这种会话改用不带 `turnLimit` 的条目分页，普通会话保持按轮。
- 条目游标可能落在一轮中间，因此该会话之后的 older、补齐刷新、排队和重放都保持条目模式；真正发送时规范化原请求，保留 `beforeEntryId`、`limit`、`resetLeaf`。恢复只处理当前 generation、活动 history 且明确匹配会话的超限错误，在 lane 完成前处理，不发布会让投影取消 loading 的中间拒绝。降级后的请求再失败就走普通终态，不无限重试。
- 模式只存在 Runtime 内存中：同一凭据的 LAN / Tailscale 重连保持，新配对或凭据更换清除。最新页可能只有 assistant / tool 而没有 user，不能过滤成空页。

### 最新窗口重拉

- LATEST 批次的第一页整窗替换上一轮最新窗口的历史条目（负 seq），只保留实时记录（正 seq）。合成 seq 每轮继续向负方向递减，普通合并会让重拉找回的条目排到整段对话之前。代价是向上翻出的更早内容随整窗替换作废，与 Pi 重开会话只画可见窗口一致。页内没有可渲染记录时不替换。
- 只有新旧窗口存在稳定 entry 身份重叠时，`preserveLive` 重拉才保留更早的前缀和游标；切分支或无重叠时不猜连续性。重拉开始时如实上报本地缓冲非空，避免 `HistoryReducer.start` 提前抹掉翻页游标。
- 同一会话已有批次在途时，新的 latest 请求不清缓冲、不发注定被丢弃的请求，而是记下意图，当前批次落地、失败、超时或取消后补发；会话已切走则作废。产品 history lane 是 `COALESCE_LATEST`：新请求取消并替换唯一排队请求，不是 FIFO。
- older 在途时拒绝重载：否则重载在 lane 中排队、seq 空间先重置，迟到的 older 应答会落进新批次。
- 实时尾部与 older 页对消从页尾开始匹配，因为实时副本对应同文消息最近一次出现；零重叠时不跨空间对消。

## 考虑过的方案

**用 `buildContextEntries` 分页。** 见“问题”，会重复、丢失和重排条目。

**按数组尾部 `slice(-limit)` 截断。** 见树投影记录，Pi 把活动分支排在同级最前，切尾会切掉当前对话。

**超大轮时放宽网关的页预算。** 会让单帧过大，触发出站背压断开；只对受影响的会话降级到条目分页影响最小。

## 影响

- 浏览历史与模型上下文彻底分开，压缩前的内容也能翻到。
- 整窗替换会丢弃已经翻出的更早内容，需要重新上滑加载。
- 单轮超大的会话失去按轮分页，翻页粒度变成条目。

## 核对入口

- 网关 `src/pi-runtime.ts` 的 `history`、`sliceActiveBranchTail`、`manualLeafIds`、投影缓存；`src/wire.ts` 的 `capHistoryPage`；协议 `PROTOCOL.md` §4.6。
- 客户端 `AndroidGatewayProjection.startHistory`、`pendingLatestReloads`，`SharedHistoryStore.pageReceived`，`HistoryReducer`，`GatewayRuntime` 的 history lane 与超限降级。
- 测试：`AndroidGatewayProjectionTest.hiddenHistoryPageKeepsItsCursorWithoutVisibleEvents`、`latestReloadWhileBatchInFlightIsDeferredThenRefetched`、`LatestHistoryWindowReplacementTest`，网关 `tests/pi-runtime.test.ts` 的 metadata-only 用例。
