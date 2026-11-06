# Agent Note: 分页切断操作组，上滑只变数字像没反应

Status: implemented

## 问题

上滑到页顶，看到一行残缺的「完成了 N 个操作」，加载很快落地，点阵出现又消失，却没有任何新内容滑出来，只有 N 变大。用户感觉「滑了没反应」。

## 根因

页顶这一组的开头在更旧的页里。分页边界落在操作组中间，只有条目分页会这样：

- 桥接会话：gateway 的 `bridge.history` 按条目取页，没有 `turnLimit`。
- 单轮历史超过 2 MiB：gateway 返回 `history-turn-too-large`，客户端对该会话永久回退条目分页（`entryHistorySessionIds`）。
- 旧 gateway：没有 `history-turns-v1` 能力。

条目页只有 `HISTORY_PAGE_MESSAGE_LIMIT`（20）条，一个 130 步的组要拉十几页，每页只给折叠的组加几步。整轮分页（`history-turns-v1`）每页都从真实用户消息开始，不会切组，所以不受影响。

推断，未在线上逐条核实：用户的主要会话走桥接，页大小观测约 20–180 KB，与条目分页吻合；网关日志里没有 `history-turn-too-large` 的直接记录。修复不依赖具体是哪一条路径。

## 决定

- **页顶缺头的组先不显示，补全后一起出现。** `fineCatLeadingProcessRunLength` 找展示条目最前面「空正文助手消息 + 步骤组」这一段，含步骤组就视为缺头；`fineCatHoldsLeadingGroup` 在还有更早历史、有游标、没有历史错误、没超过页数上限时让这一段不进展示列表，其余照常。
- **自动续拉，不等手势。** 一个效应在没有请求在途、已连接、在对话页时立刻发下一页，直到页顶是真正的消息。这是对「不静默加载」约定的有意例外：残缺的步骤数本身就是误导。隐藏期间 `showHistoryLoadingRow` 照常为真，页顶是点阵或加载行，不会空白。
- **续拉用大页。** `loadOlderHistory(limit = 100)` 只覆盖这一次请求，网关实现里默认 100、上限 400，页字节仍受 2 MiB 约束。130 步的组大约三页拉完，而不是十几页。覆盖值按会话记录（`resolveHistoryPageLimit`：只对同会话的更早页请求生效，最新页请求不消耗），请求发出时在投影回调取走，`loadOlderHistory` 在投影处理返回后兜底清空。
- **有上限，失败与停滞都不隐藏。** 连续最多 8 页（`GROUP_COMPLETION_MAX_PAGES`）；拉取出错或没有游标时不隐藏；补全 15 秒没有请求或进展（`GROUP_COMPLETION_STALL_MS`，请求丢失、断线、后台挂起）也放弃隐藏，显示已加载的缺头组。兜底只记下「在哪个页顶上停滞」，不耗尽页数预算：慢页随后落地、页顶变化，组头仍缺就重新隐藏并继续续拉。兜底计时与续拉同门控，只在对话页且已连接时运行，树页停留或断线不消耗。页数计数只在 `loadOlderHistory` 返回已受理时递增，页顶不再缺头后清零。
- **一个有意的取舍。** 慢于 15 秒但没丢的请求会先露出缺头的组、落地后又隐藏，有一次可见的变化；换来的是请求真的丢了时页顶内容不会一直消失。

## 考虑过的方案

**只自动续拉、不隐藏。** 实现更小，但用户会看到 N 一页页往上涨，仍是一个会变的残缺数字。

**改 gateway 让桥接历史也按整轮分页。** 单轮超限时本来就无法整轮传，客户端仍要处理条目分页；改动也跨仓库、跨协议版本。

**调大全局页大小。** 会改变所有会话的首屏和翻页成本，只为一个局部问题，不做。

## 影响

- 整轮分页会话的页首总是用户消息，这条路径不触发，行为不变。
- 条目分页会话打开或翻页后，页顶缺头的组会在后台多拉几页，期间页顶可能是点阵或「加载更早消息…」。极少数会话真实开头就是步骤，会白拉最多 8 页后放弃。
- 补全后组 id（`process:<前驱消息 id>:<序号>`）随前驱变化，已展开的状态不跟随；缺头的组本来就不展示，用户没机会展开它。

## 核对入口

- [FcConversation](../../../../androidApp/src/main/kotlin/com/finecat/pimobile/android/ui/finecat/conversation/FcConversation.kt)：`fineCatLeadingProcessRunLength`、`fineCatHoldsLeadingGroup`。
- [FineCatApp](../../../../androidApp/src/main/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatApp.kt)：`completingLeadingGroup`、续拉效应、停滞兜底、`GROUP_COMPLETION_*` 常量。
- [AndroidSharedStateHolder](../../../../androidApp/src/main/kotlin/com/finecat/pimobile/android/android/AndroidSharedStateHolder.kt)：`loadOlderHistory(limit)`、`olderPageLimitOverride`。
- [纯函数用例](../../../../androidApp/src/test/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatLeadingGroupTest.kt)与[设备用例](../../../../androidApp/src/androidTest/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatHistoryBoundaryDeviceTest.kt)的 `partialOperationGroupAtTheTop_isHeldBackAndCompletedBeforeItAppears`、`hiddenPartialGroup_isRevealedWhenCompletionStalls`、`stallTimerDoesNotRunWhileTheTreeIsShown`，先红后绿。
- [同批边界手感修复](2026-11-04-history-boundary-scroll-jank.md)：预取与点阵。
- 回归说明：HomeParity 3 例、F02 2 例、TimeSeparator 8 例失败在修复前基线同样存在，不是这次引入；F02 整类偶发多出一例动画相关用例，单跑 3 次、整类再跑 3 次均不复现。
