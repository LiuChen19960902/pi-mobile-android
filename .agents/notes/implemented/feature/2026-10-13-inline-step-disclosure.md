# Agent Note: 思考与工具步骤在正文内联展开

Status: implemented

## 问题

一轮回答可能包含几十个思考和工具调用。它们既要能查看完整内容，又不能挤占正文。这个区域经历了多次调整：先是外部间距过大，再缩小行高，然后改为近全屏底部卡片，再在卡片内套一层详情卡，最后回到正文内联。每一版都暴露了新问题，记录它们是为了不再走回头路。

## 决定

- 正文中每组步骤显示一行摘要（步骤数与失败统计），点击在正文内展开：左侧细线、语义化的工具行；点某一行在其下展开完整详情，同一组一次只展开一项。步骤与详情都使用主对话的滚动，没有内嵌的纵向列表。
- 展开状态存在 `FineCatApp` 中按会话 remember 的 Map：key 存在表示组已展开，value 是当前详情 ID。列表行离屏不丢选择；删除组时清理，删除某一步时清空 value，避免同 ID 重新出现时自动展开。收起组后再展开不继承旧详情。
- 用户点开组或详情时暂停贴底跟随，保留阅读位置；显式发送或“回到底部”恢复跟随。
- 摘要状态不能只看步骤数：未结束的工具用中性计数，当前尾组运行中用进行态；思考和上下文步骤没有 `completed` 字段，不能用默认值把它们显示成“工具等待中”。
- 单项详情最大 240dp，短内容按自然高度。`heightIn` 必须在 `verticalScroll` 之前，为同向嵌套滚动提供有限视口。工具详情中的代码块不再单独封顶，命令和结果共用详情这一层滚动。
- 详情内起手的手势在到达边缘后不传给会话列表：在内部 `verticalScroll` 外侧加局部 `nestedScroll`，`onPostScroll` 消费剩余的 y、`onPostFling` 消费剩余速度。会话列表的“到顶加载更早历史”从 `onPreScroll` 移到 `onPostScroll`，只看子项消费后真正到达列表的剩余量，否则详情内的下拉会误触历史加载。
- 连续的步骤组在展示层合并（`mergeFineCatProcessEntries`）：`makeConversationDisplayEntries` 会在每条 ASSISTANT 处切开批次，即使正文为空。合并保留第一组 ID，相邻步骤按原序合并；空的助手消息对象保留，供定位、历史加载和时间分隔使用。可见正文、图片、错误、USER、SYSTEM、STATUS 是合并边界。合并必须在 `groupAssistantRounds` 之前做：该函数会把回合内的步骤排到所有消息之前，排序后再按视觉相邻合并会越过真实正文。
- 正在生成且尾项仍是步骤的组直接显示，进入正文或结束后恢复为折叠的计数头；直显不修改显式展开集合。底部的 Working 由真实忙状态控制，步骤组内不再重复显示 Working。
- 紧凑行高时，局部 `LocalViewConfiguration.minimumTouchTargetSize` 同时作用于组头和条目，否则默认 48dp 的触控扩张会让边缘点击命中相邻组；用 `defaultMinSize` 让大字号自然撑高，不用固定 height 截字。

## 考虑过的方案

**近全屏底部卡片列出步骤。** 卡片挂在 LazyColumn 的条目内时，正文自动跟随可能让条目离场，弹卡和选择随之丢失；挂到根部又需要按会话重建选择。

**卡片内再开一层详情卡。** 两层卡片各自进入、隐藏、改变高度，交互不连贯；用户的目的就是把步骤收进一个地方。

**详情无限高度。** 长思考和工具输出会把正文推得很远。

**只缩小外部间距。** 48dp 的标题和条目本身才是主要留白。

## 影响

- 合并后被吸收的后续组 ID 会消失；空消息之后出现正文时会重新拆组，后续组和思考的 ID 不保证不变。
- 内外滚动的归属只能靠真实手势验证，编译和 JVM 测试不能代替手感。

## 核对入口

- `FcStepsBlock`、`FcStepDetail`、`FcCodeBlock`（`constrainHeight`）、`mergeFineCatProcessEntries`、`makeConversationDisplayEntries`、`groupAssistantRounds`、`FineCatApp` 中的展开状态 Map 与 `historyScroll` 连接。
