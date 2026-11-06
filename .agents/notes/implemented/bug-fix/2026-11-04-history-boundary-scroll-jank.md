# Agent Note: 历史分页边界的滚动跳变与硬卡

Status: implemented

## 问题

上滑到已加载内容的尽头有两处手感缺陷。一是「硬卡」，惯性一头撞在边界停死、等下一页加载才继续。二是「主体提高」，「加载更早消息…」行插进最旧回合顶部、落地后收起，内容随之上跳。二者都是滚动位置不连续，不是掉帧。真机帧耗时实测中位约 6ms 很顺，曾据此误判「没复现」，量错了对象（应量位置稳定性，不是帧耗时）。

## 决定

- **预取按像素、由手势触发。** 离顶估算距离不足 `HISTORY_PREFETCH_VIEWPORTS`（2）个视口高度，且用户手势朝更早方向滚动，就调 `loadOlderHistory`。距离用可见项平均高度乘首个可见下标再加滚动偏移估算，回合高矮悬殊时提前量仍稳定；旧版按回合数（5 个）会随回合高度漂移。触发点放在列表的嵌套滚动连接里：嵌套滚动只接收手势派发的位移（拖动与其后的惯性），程序化贴底、跳转不经过，所以不会在用户没碰屏幕时静默拉页。每帧重复调用安全，`loadOlderHistory` 自带 hasMore/loading/会话去重守卫；prepend 把距离推远而自然停止连预。撞顶是预取区的特例，不另设兜底分支。
- **估算的已知失灵方向与封顶。** 可见项平均高度会被超高回合放大：视口里只有一个 18372px 的回合（视口 1848px）时，不封顶会把离顶三个条目估成 5 万多像素而错过预取。平均高度因此封顶为半个视口，封顶只偏向早预取（多拉一页，prepend 后自限）。反方向（可见项偏矮、更早内容偏高）本来就是低估，同样偏向早预取。
- **到顶等待用盲文点阵，至少驻留 1 秒。** 只有真正到顶（`!canScrollBackward`）且在加载时，才在列表外的浮层显示 `PiSpinner` 盲文点阵（tag `finecat.loadingOlder`），不占布局高度，出现收起不抬高首项。`rememberMinimumDwell` 保证点阵一旦出现至少展示 1 秒，回包瞬间到达也不一闪而过。预取在更远处完成时点阵不出现，也就不会遮住正在读的正文。空列表的独立 loading item 保留。驻留状态按会话重置（`rememberMinimumDwell` 的 `resetKey`），切会话不残留上一会话的点阵。

## 考虑过的方案

**重审锚点补偿的 `!isScrollInProgress` 守卫、把瞬时 `scrollBy` 换成动画补偿。** 补偿只在锚点条目视口位置变了时修正，动画化会让用户正在读的内容滑动，是新的可见位移；暂不做。

**页落地时从顶部滑出新内容的揭示动画。** 撞边界时新页落在视口上方，需要用户再滑一次才可见。是否在落地后自动滑入一小段仍待真机手感确认，自动滚动可能与用户下一次手势冲突。

**细化 LazyColumn item 粒度。** 症状是位置跳变而非组合耗时（解析整页实测约 1–10ms、展示管线重算约 1ms），不需要动回合级 item 结构。

**换二进制编码。** 与本症状无关，解析占比极小，排除。

## 已知残余

- 点阵驻留期间新页已落地时，点阵仍浮在顶部最多约 1 秒，压住顶部约一行；这是「至少驻留 1 秒」的直接代价。
- 驻留期间每帧申请一次帧（最多 1 秒），Compose 测试里自动推进的 `waitForIdle` 会把它快进，点阵可见时做时间敏感断言须冻结虚拟时钟。
- 惯性（抬手后）路径靠嵌套滚动的 `onPostScroll`（已在模拟器日志里确认手势与惯性帧都经过该连接），没有单独的用例锁定，真机手感复验时留意。

## 影响

近顶手势滚动会提前拉一页。预取区大小是手感参数，真机复验后可调。点阵 1 秒驻留是刻意的：宁可在极快回包时多停一会儿，也不要闪。

## 核对入口

- [FineCatApp](../../../../androidApp/src/main/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatApp.kt)：`HISTORY_PREFETCH_VIEWPORTS`、`isInHistoryPrefetchZone`、嵌套滚动连接 `historyScroll`、`rememberMinimumDwell`、`finecat.loadingOlder` 浮层。
- [边界回归用例](../../../../androidApp/src/androidTest/kotlin/com/finecat/pimobile/android/ui/finecat/FineCatHistoryBoundaryDeviceTest.kt)：远处手势不预取、程序化滚动不预取、预取区内手势才预取且点阵不显示；点阵出现后驻留约 1 秒；首回合高度不变。三例先红后绿。
- [历史分页决定](../architecture/2026-09-16-history-paging.md)：翻页触发与游标语义。
- 回归说明：FineCatConversationF02 与 FineCatTimeSeparatorF10 的失败在基线与修复后完全一致，属预先存在或环境敏感；AndroidAppGraphFakeIntegration 期望首页 limit 60 实得 20，该用例不含 UI，修复未触及状态持有层，同属预先存在。时间线回归用例改按点阵 tag 等待。
