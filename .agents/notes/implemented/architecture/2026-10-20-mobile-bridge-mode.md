# Agent Note: 桥接模式接入原生 Pi

Status: implemented

## 问题

此前手机只能使用网关自己创建并持有的 SDK 会话。用户电脑上已经开着的原生 Pi（CLI/TUI）会话在手机上不可见，也无法继续；用户要么在电脑上结束工作，要么在手机另起一个会话，两边上下文割裂。POC 已证明：只要原生 Pi 加载一个桥接扩展，就能在不修改 Pi 源码、不接管 TUI 的前提下，把同一会话的实时输出与操作暴露给网关。

## 决定

- 网关新增按宿主持久化的 `host-mode`：`sdk`（默认，行为不变）与 `bridge`。手机只在 hello 宣告 `bridge` 与 `host-mode` 后显示模式入口；旧网关保持纯 SDK 列表，不显示“在线”标记。切换要求两侧都没有运行中、待消费队列、SDK 在途打开/写入或后台启动窗口（从读取会话列表起），否则网关回 `mode-switch-blocked`，客户端只提示、不本地猜成功。
- 桥接模式的会话列表由网关把磁盘条目与在线实例按真实 `sessionId` 合并。手机端 `GatewaySessionSummary` 新增 `online`/`source`/`runtime`，`SessionSummary.isOnline` 用三态（`true`/`false`/`null`）：只有 `sessions.mode == "bridge"` 的应答才把“不在在线集合”判为离线；SDK 模式保持 `null`，不误显示离线。抽屉状态优先级是“进行中 > 在线 > 离线 > 失败”。
- `subscribe` 先回 `subscribed`，再发完整 `bridge-snapshot`。快照是扩展侧真值：`busy` 直接校正运行集合，`pending` 只表示有未消费队列（不伪造文本）。`bridge-snapshot`/`bridge-offline` 列入 `UNCORRELATED_KINDS`，不参与请求关联；`bridge-offline` 清掉该会话的运行与后台保活，但会话仍可按磁盘历史只读浏览。
- `sent` 在桥接模式只到 `submitted`，并附受理后的 `isStreaming`/`isCompacting`，以便按 `prompt-disposition-v1` 收掉乐观等待；不伪造 `started`/`queued` 或完成。`busy`、`session-owned`、`operation-id-conflict`、`bridge-offline` 等稳定码映射成短文案，不透出扩展或上游原文。
- 队列取回保留 Pi 原生语义：`dequeue`/`abort(restoreQueued:true)` 由扩展整批返回文本。网关同时下发 SDK 形状的 `queued:[整体文本]` 与单元素 `queuedText`，Android 复用既有 `handleRecalledQueue`/`handleStoppedQueue`，不需要为桥接另写草稿恢复路径。电脑草稿在捕获期间保持不变。`abort` 已经取出的文字若随后失败，网关错误帧仍回显请求的 `operationId/instanceId/epoch` 并透传 `queuedText`；手机只为匹配当前停止操作的失败建立恢复记录，不覆盖之后输入的新草稿，也不自动重发或伪造 idle。
- 快照中的 `partial` 正文、thinking 块和 `tools` 必须进入手机会话投影，不能只用来校正 busy；快照切片只在**新的 user 轮、运行结束或代际切换**时退场，工具事件不清 assistant partial（工具没有等价的权威正文条目），工具按 `toolCallId` 与正式条目去重。partial 不是临时 overlay：它按当前 turn/step 种进 `ConversationStore` 的同一流，后续 delta 才接在同一段；同代际重放快照或历史页晚到重建时，先摘掉上一条已种流，再按“快照前缀 + 已收 delta”重排，不能重复拼接或把正在生成的回答吞掉。每次快照到达时客户端重建 normalizer（输入坐标归零），投影则分配新的流键范围承接此前缀；wire seq 重订阅归零后，已接受事件仍用本地单调序号。可见流和历史缓冲中的旧 chunk 同步清理，避免完成后补拉历史又重放旧片段。这一条覆盖“手机在 CLI 已输出一半或工具执行中才订阅”的接续场景。
- 手机以 `instanceId+epoch` 为实时代际：**只有 `bridge-snapshot` 能建立新代际**。新快照之前到达的新 epoch 事件也先丢弃，旧 epoch 的迟到事件不能把代际切回去；同一代际内 `bridgeSeq` 小于已消费值的事件和快照按迟到帧丢弃；等于已消费水位的快照是同一实例断线或用户显式断开后的合法重同步（期间可能没有新事件），必须接受并重置 normalizer 和投影位置，重复等水位快照保持幂等。合法新快照（新代际、同代际等水位或更高水位）都会重置；低水位同代际快照不能把已追加的正文回退。接受快照时先摘掉旧种子流和旧 turn/step 的活动流，避免旧片段在后续 delta 或历史重建时复活。切代际时先清实时段和快照种入的流，再按已加载历史重建，新快照只替换实时段。写请求和 `operation-status` 都带捕获时的 `instanceId`/`epoch`，网关不匹配时回 `bridge-stale-runtime`，不把旧界面查询或写入悄悄投给新实例。
- 当前会话的 Worktree 元数据在权威 `bridge-snapshot` 建立身份后刷新；相同实例与代际的重复快照不重查，重连、重新选中会话或离线后重新允许刷新；能力、目录或连接尚未就绪时不记为已刷新。Compose 的目录变化先于身份到达时，早期请求会被身份闸门拒绝，不能仅依赖目录和连接状态变化触发刷新，否则 Git 目录一直落到普通文件浏览器。
- `worktree-list` 带 `sessionId` 时走原生 descriptor 目录读取，不进入 `PiRuntime.openSession`；新会话与 Fork 约束见[原生工作区](2026-10-22-native-workspaces.md)。模式切换前同时检查运行、待消费队列、桥接在途写、SDK `openSession`/写计数、`bridge-start` 启动窗口和所有者快照；快照或会话列表失败记 `owner-unknown`，在途写按会话计数而不是按描述符是否仍存在判断，与 `in-flight` 一样阻止切换，不把“状态未知”当成离线放行。切换成功后释放旧模式订阅并逐会话发 `unsubscribed(reason="host-mode-changed")`；SDK 分支同时从 `subscriptions` 集合删除，否则切回 sdk 后客户端重新 subscribe 会命中“已订阅”而跳过 `attach`，实时事件永远不再来。手机收到后重新订阅并补拉历史。
- 网关对桥接请求的代际校验以扩展端 `handle()` 为准：连接建立时 hello 还没到，不能在 `withSocket` 里用 `socket.hello` 把请求判成 stale；该处只保留连接与请求转发。
- 网关与扩展的 IPC 单帧按字节分帧，完整一行后再 UTF-8 解码；双方上限统一 24 MiB。扩展响应超限时用同 id 回小错误帧；网关读缓冲超限时先拒绝所有在途 waiter 为 `frame-too-large`，再关闭 IPC。任何一侧都不把超限伪装成 `bridge-offline`。合法的 900 KiB 图片（base64 约 1.2 MiB）可以跨桥。
- 稳定 `operationId` 从手机发送 token 贯穿网关到扩展；`send`/`abort`/`dequeue` 都按它去重。发送 token 带跨 tracker、跨进程唯一的随机前缀，不能只用实例内序号。`dequeue` 先落回执再取队列，`abort` 先落 in-flight 回执再等待 settle；`operation-status` 查不到时回 `unknown`，只表示记录已淘汰，不表示没执行。正常发送收到匹配的 submitted 回执后结束发送等待；没有回执时保留恢复入口，不自动重发。桥接写请求在 `operationId` 和 `instanceId+epoch` 都不为空时，客户端用 `correlationId` 绑定当前操作；`sent`/`aborted`/错误回执必须四元匹配才消费 lane，迟到 A 的 ACK 不能消费 B 的 lane。
- `submitted` 证明桥接实例已调用公开的 void `sendUserMessage`，不证明模型已开始或完成。手机收到匹配提交回执后结束发送等待、释放对应原稿与附件，不再等待普通 send 不会提供的 `operation-status=settled`。Runtime 的操作/会话/实例/代际四元匹配和 Holder 的等待队头守卫保留，只清对应且未改写的草稿；缺回执时仍走未确认恢复路径。不能按 session+正文匹配，也不能把后续任意 user/message 当作该操作确认。原因和回归见[提交回执修复](../bug-fix/2026-10-21-bridge-submission-ack.md)。
- `bridge-offline` 同时清掉在线活动态、压缩态和服务器队列快照；缺回执的发送仍保留原有恢复入口，不混作待消费的服务器队列，重连不自动重发。
- `operation-status` 协议与 Runtime 查询能力保留，普通发送的正常收尾不依赖它；Android 不再为已经收到提交回执的 send 建立永久收据和自动对账队列。
- 桥接 `sessions` 只对“在线但无磁盘索引”的条目下发 `path: ""`，不把宿主绝对路径带到手机；有磁盘索引的条目保留真实 path，`cwd` 沿用 SDK 列表的既有公开口径，用于项目名显示。
- 重连后自动补拉 `host-mode`，避免 UI 用旧模式判定；模式切换用 `host-mode` 同一 lane，拒绝走统一 `RequestRejected` 瞬态提示，不进全局错误面。
- 树、复制、标签、跳转和改名的原生接入见[原生会话操作](2026-10-22-native-session-actions.md)，部分取代本记录对这些操作的初始拒绝策略。输入候选及 Skills/Prompt 调用见[原生输入辅助](2026-10-22-native-input-assist.md)。原生会话与工作区见[工作区接线](2026-10-22-native-workspaces.md)，重载仍拒绝 SDK 回退。
- 网关宣告 `bridge-start` 后，手机可对离线会话请求后台启动一个带扩展的原生 Pi。网关用真实会话文件启动宿主 tmux 会话，等注册表出现描述符才回 `bridge-started`；按 `operationId` 与 `sessionId` 双重去重，并在 `await` 前同步占位，避免同一 operationId 并发启动两个目标。journal 回放要求注册表中仍有 `instanceId+sessionId+epoch` 三者一致的在线实例，`/new` 后旧记录只回 `launch-result-unknown`，不假装成功。超时同样只回未知。`attachHint` 只给人看，不作为协议路径；sessionId 必须匹配安全字符，否则明确拒绝，避免启动名与 `pi-mobile attach` 不一致。
- 桥接扩展只接管自己安装的 custom editor；启动时已有其他 editor 就不覆盖，并在 hello 快照报 `editor: unavailable`。`dequeue` 一律回 `editor-unavailable`（不借别人的 editor 取队列）。停止是另一回事：没有待消费队列时停止不依赖编辑器归属；有队列而桥接不持有 editor 时照常完成中止，队列交给 Pi 原生 abort 拼回电脑当前编辑器（`queuedDisposition:"left-on-desktop"`，承诺范围只到本轮运行队列，不含 TUI 压缩排队），不返回 `queuedText`、不重发。取回动作抛错且没捕获文字时报 `unknown`，手机不得声称已恢复。停止或失败只清自己的 factory，不用私有 hook，也不改 Pi。

- 终态条目身份与快照重放的约束见[桥接终态与重放修复](../bug-fix/2026-10-21-bridge-terminal-and-replay.md)，不能把 SDK public subscribe 的延后经验直接用于发生在 append 之前的扩展回调。

- 没有在线CLI时，模型选择器可读宿主全局可用目录/启用范围，Tree和全文复制只读原文件；这些查询不启动Pi，不建立SDK句柄。已有会话的选模/标签/跳转才是启动意图，复用原SID的信任、owner和启动日志，先由真实快照建立代际再认写回执。不能把“无运行器”当成全部入口禁用，也不能为了读树加载项目扩展。在线目录仍取CLI实际scope；全局候选不保证某个带临时scope的CLI一定能选。协议见网关§4.41，关键回归是首页目录切历史时也清缓存、只读复制绕过SDK初始化句柄、文件树忽略SDK旧手动叶子。
- 原生模型与思考通过公开 `ctx.scopedModels` / `modelRegistry.getAvailable()` 及 `ExtensionAPI.setModel/setThinkingLevel` 接入，不能拿宿主全局设置冒充该 CLI 的启动范围。模型目录绑定会话、实例、代际和 queryId，模型写另带 operationId 串行去重；客户端目录按会话清理，切模式清缓存。原生 model_select 可能含完整 Model 的地址与 headers，因此只发最小标识再补拉 state。切换仅会话内生效，不改全局默认；正在生成的上游请求保持 Pi 原生语义。身份绑定请求的离线、过期身份和认证缺失明确拒绝；离线候选与明确激活路径按上条区分，不创建 SDK 句柄。

- 状态水位与正文水位必须分开判：`state`/`history` 的拉式应答走独立请求 socket，订阅 socket 上较早捕获的 `bridge-snapshot` 可能晚到，因此快照接受后只能推进正文前缀（`eventSeq`），状态水位（`stateSeq`）只升不降；介于两者之间的旧快照仍投影 partial/工具前缀，但不改 busy/压缩/模型/队列，也不改抽屉的运行标记。同一条事件的 raw 状态字段与归一事件（`turn/start|end`、`compact/*`、`queue/update`）必须共用同一个"状态是否权威"结论，否则第二条入口会把旧事件状态写回去。细节见[桥接状态回退修复](../bug-fix/2026-10-23-bridge-status-rollback.md)。

- 原生上下文用量来自 `ctx.getContextUsage()`，只投影 tokens/window/percent；压缩后 tokens/percent 的合法 null 直接显示未知。压缩通过 `ctx.compact` 的完成/错误回调收尾，保留原生扩展钩子；公开 session_compact 事件早于原生最终清理，手机请求不把该钩子当作回调终态。压缩与普通流式活动分别携带，不能把 busy 一律解释为 isStreaming；事件只在状态边界补占用，文本 delta 保留快速路径。细节见[手动压缩记录](../feature/2026-09-16-manual-compact.md)。

## 考虑过的方案

停止降级中“只交回电脑”的决定由[手机队列副本](../bug-fix/2026-10-23-native-stop-phone-queue.md)部分取代；独立dequeue的编辑器归属闸门仍有效。

**手机端把桥接快照拼成完整会话状态。** 快照没有结构化队列文本或历史条目，硬拼会让 UI 显示与真实状态不一致；运行/在线和模型档位取实际字段，pending 单独驱动“有消息等待执行”与整批撤回，不冒充队列明细。语义与清理条件见[忙时队列记录](../feature/2026-09-16-busy-dispatch-and-queue.md)。

**在 `bridge-offline` 后自动切回 `sdk` 或重建会话。** 这会把“用户电脑上的进程退出”变成“手机悄悄换了一个会话”，历史与所有权都对不上；保持只读并让用户显式选择。

**把单写者当作全宿主硬锁。** 锁只约束加载了扩展的实例；未加载扩展的 CLI 仍能写同一会话。客户端不宣称独占，也不做最后写入者获胜。

**把 1 MiB 当 IPC 单帧上限，或按 chunk 直接 `toString("utf8")`。** 前者会断掉手机 WS 允许的合法图片，后者在中文跨 socket 分块时插入替换符；两者都会把可恢复的业务失败伪装成实例离线。改为按字节分帧、完整行解码并把上限对齐到 24 MiB。

**把提交回执当成运行完成，或用正文匹配确认。** 公开接口没有逐操作的原生受理事件，也不会为普通 send 产生 settled。曾采用“保留到 settled”的方案，真机发现回复已出现而未确认条永久保留。现以匹配的 submitted 结束提交等待，实际运行和失败仍取 Pi 事件；没有回执才保留恢复入口，不按文本猜身份。

**快照状态未知时按空闲放行切换。** 注册表进程存活但快照请求失败，不能推出它没有运行；这时切到 SDK 会让两个运行器操作同一会话，因此把 `owner-unknown` 当作阻止条件。

**桥接回执沿用字段名却换了类型。** `branch-switched.queued` 必须是字符串数组，结构化队列只能放 `queuedMessages`。曾把空队列发成对象，原生已跳转而 Android 整帧解码失败；路由断言和 `GatewayConformanceTest.nativeBranchResponseUsesTheAndroidQueueWireShape` 必须贯通生产路由与手机解码器，仅各端自测不能证明兼容。

**交互终端的补全正常就认为用户服务可用。** @ 候选由网关进程调用 `fd`/`fdfind`；原生 Skills/Prompt 正常不代表网关 PATH 有搜索工具。服务应使用同一启动环境实测文件候选，缺依赖不降级为读取另一个 SDK 会话。

## 影响

- 跨仓协议见网关仓 `PROTOCOL.md` §4.30–§4.36；Android 只依赖已宣告能力与稳定错误码。
- `androidApp/src/test/.../GatewayConformanceTest.kt` 的 `hostModeSwitchAndBridgeOfflineUseProductionWire` 用真实网关进程验证能力宣告、模式切换、桥接列表与离线写入拒绝；没有真实原生 Pi 时，这只能证明协议通路，不能替代真机与真实 Pi 验收。
- 网关 `tests/bridge-regressions.test.ts` 覆盖 worktree 拒绝、busy 保持、owner-unknown 阻止切换、900 KiB 图片、UTF-8 跨块、写请求去重、`operation-status=unknown`、`frame-too-large`（含超大 history 响应）、stale epoch、editor 冲突与 abort 取队列门槛，以及 abort 失败保留 `queuedText` 和操作身份；`tests/bridge-launch.test.ts` 覆盖启动去重、并发等待、operationId 冲突和超时未知；`tests/bridge.test.ts` 覆盖 sdk→bridge→sdk 后重新订阅会再次 attach；`tests/server.test.ts` 用真实 loopback WebSocket 贯通 `host-mode`→`subscribe`→快照/事件→写入→拒绝。真实 `tmux` + Pi 启动已人工跑通一次，未纳入自动测试。
- Android `AndroidGatewayRuntimeIntegrationTest` 覆盖快照 partial/thinking/tool 投影、工具事件不清 partial、迟到 `bridgeSeq` 丢弃、旧 epoch 事件丢弃、同代际重放快照重置 normalizer 与 stream 位置、同代际低水位快照不回退正文、epoch 只由快照建立且保留已加载历史、操作 ID/实例代际随发送帧下发、`sent` ACK 四元匹配、abort 失败 `queuedText` 与错配回执不消费 lane、`operation-status` 与 `bridge-start` 关联、迟到状态帧不消费下一条 lane；`AndroidSharedStateHolderTest` 还覆盖 submitted ACK 必须匹配等待队头、abort 失败只为匹配操作回填一次；`AndroidGatewayProjectionTest` 覆盖快照前缀与后续 delta 接成同一流、历史页晚到后前缀和已收 delta 仍保留、bridge-offline 后重拉历史不复活死实例 partial；`AndroidSharedStateHolderTest` 覆盖匹配 submitted 结束提交等待、错配/重复回执保护下一条草稿、后写新稿不被清空、缺回执显式恢复，以及桥接离线清压缩态与队列。网关 `tests/bridge-lifecycle.test.ts` 覆盖跨连接在途写阻断切换、SDK openSession 在途阻断切换、bridge-start 从读取会话列表起的启动窗口阻断切换、桥接模式未配置 hub 的 subscribe 错误码、切换后旧连接事件停止、旧身份状态查询不投新实例、`/new` 后旧 journal 不回放和同 operationId 并发只启动一个目标。
- 未加载桥接扩展的会话、同一会话的第二个实例、socket 短暂不可达仍是已知边界；真机验证需覆盖“手机中途接入电脑正在跑的会话”和“回到原 CLI 继续”。
