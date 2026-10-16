# Agent Note: 软键盘与窗口 inset 的处理方式

Status: implemented

## 问题

键盘相关的缺陷反复出现：键盘弹出后末条消息被输入栏盖住、浮层被键盘整块遮住、收键盘时底部先下探再回弹、点输入框后正文变成空白。排查时又多次把“两处 imePadding”误判为重复计入。

## 决定

### 吸底时跟随 inset 数值重滚

- 用一个按会话生命周期存活的 `LaunchedEffect(listState, selectedSessionId, density, imeInsets)`，内部 `snapshotFlow { imeInsets.getBottom(density) }` 逐帧监听 IME 高度；每个变化值先 `withFrameNanos`，再以 `onlyIfLastHidden = false` 滚到底。吸底状态、条目数量等用 `rememberUpdatedState` 提供，避免长生命周期 collector 读到旧值。只在吸底时执行，用户翻历史时不抢位置。
- 不用“`isImeVisible` 边沿 + 末条可见即跳过”：边沿只在动画第一帧触发，那时 layoutInfo 可能还是旧的；“部分可见”也会被当成无需滚动。
- 流式内容跟随只在末项隐藏时定位，已可见时补尾部差值；Markdown 换行后的高度在后续测量才更新，贴底时观察实际尾部布局。

### 先看拓扑，再判断 inset 是否重复

- **同一 modifier 链**上的 inset padding 会自动消费：内层读到的是祖先消费后的剩余量（官方 “Set up window insets” 的 Inset consumption 一节；Compose foundation-layout 1.10.6 中 `imePadding()` 直接调用 `windowInsetsPadding`）。嵌套的内层 `imePadding()` 是冗余而非有害。
- **兄弟节点之间**不传递消费。Column 流内的输入栏避让 IME 后，`weight` 列表拿到的已是剩余空间；列表再加 `imePadding` 会重复扣高，键盘弹出时正文变成空白。Box 叠层中列表与输入栏才需要各自避让。
- 底部常驻区高度在 `navigationBarsPadding` 内侧测得，已含导航栏；列表侧不能再叠 `navigationBarsPadding`。
- 收键盘时的回弹曾来自离散切换底部间距（IME > 0 取 8dp、归零取 32dp）；改为测量期同时读取 IME 与导航栏，用“导航栏 + 32dp”作为下降底限。

### 浮层与底部卡片

- Compose `Popup` 是独立窗口，宿主为 `adjustPan` 时 `WindowInsets.ime` 在宿主组合中为 0。在 Popup **内容内部**取 `LocalView.current.parent`（挂到 WindowManager 的 `PopupLayout`），在 `SideEffect` 中把其 `softInputMode` 设为 `SOFT_INPUT_ADJUST_RESIZE` 并 `updateViewLayout`；幂等，窗口随 dismiss 销毁。依赖 Compose 私有视图层级，两处 `as?` 落空时静默退化，因此需要设备断言兜底；`ADJUST_RESIZE` 自 API 30 废弃，换机或换 API 需复验。
- `PopupProperties(focusable=false)` 不阻止外部点击关闭：Compose UI 1.10.6 的 `PopupLayout.onTouchEvent` 按 `dismissOnClickOutside` 处理，不检查 focusable。focusable=false 用于保留输入框焦点。
- 底部卡片 `FcSheet` 把导航栏 padding 放在背景之内，背景画到屏幕底而内容避开导航区；IME padding 在背景之外。
- 首页品牌先按自然高度测量，再按留白公式放置，避免固定底部 padding 把品牌挤到截字。

### 输入框

折叠与展开不能各创建一个 `BasicTextField`：首次获得焦点会切换分支、替换编辑控件并中断输入连接。保持同一控件，只调整周边按钮和尺寸。

## 考虑过的方案

**`reverseLayout=true`。** 需要同时反转数据、历史行、初始定位和翻页逻辑，改动范围过大，未采用；不是试验失败，也不是永久禁止。

**只在消息变化时滚底。** 键盘改变的是视口而不是内容。

## 影响

- 键盘问题必须在设备上验证：截图中的键盘、`mInputShown=true` 或焦点为 true 都不能单独证明可以输入，要确认实际提交的文字。取证方法见 [IME 与窗口几何的设备取证](../testing/2026-10-06-ime-window-geometry-evidence.md)。
- Popup 修正依赖内部实现，升级 Compose 时要复查。

## 核对入口

- `ConversationScreen` / `FineCatApp` 的 IME collector、`bottomContentHeight`、`scrollToTimelineBottom`；`FcPopover` 的 SideEffect；`FcSheet` 的 modifier 顺序。
