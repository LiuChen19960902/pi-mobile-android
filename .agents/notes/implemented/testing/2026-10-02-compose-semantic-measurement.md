# Agent Note: Compose 语义测量与断言的判别力

Status: implemented

## 问题

设备用例大量用语义树测量尺寸、位置和层级。多次出现断言“恒过”：量到的不是想量的那一层、两份同 tag 节点命中被遮住的那份、主题根本没切换却两张截图都通过。还有用例因为视口高度不同而时红时绿，被误判为回归。

## 决定

### 量的是哪一层

- `testTag` 在 modifier 链中的位置决定节点 bounds：放在 `padding()` 之后只量到内层内容盒，放在 `padding()` / `background()` 之前才是整盒。加 tag 前先确定要量哪一层。
- 触控区直接读 `SemanticsNode.touchBoundsInRoot`，不从视觉尺寸推断。相邻控件的扩张触控区可能几何重叠，但每个落点只投递给一个节点；“48dp 不重叠”按“每个落点恰好一次投递”验收，不按矩形不相交。
- 在 LazyColumn 内，`boundsInWindow` 不能单独代表可见：缓存但未放置的条目可能保留旧 bounds。判定要求目标及其 `parentInfo` 祖先链都 `isAttached && isPlaced`，再用 `layoutInfo.coordinates.boundsInWindow(clipBounds = true)` 与列表视口求正面积交集。不要因语义树多出一个未绘制节点就给生产列表加滚动补偿。
- 零面积节点的 `boundsInWindow` 返回 `Rect.Zero`，用它当 Popup 锚点计算可用空间会得到 0；取位置用 `positionInWindow`。

### 识别恒过的断言

- Row 垂直居中时，两个子项 `bottom − top` 的差只由它们的高度决定，与行高无关，拿它当“行高上限”恒过。断言直接量被守卫的量。
- 同一 testTag 出现两个节点时，`onNodeWithTag` 可能命中被浮层盖住的那份；需要画两次的控件用不同 tag。
- 判断浮层层级不要用像素采样（同色元素、抗锯齿、布局位移都会污染）。用结构序：按根节点先序 DFS 收集 testTag，无 `zIndex` 且不在独立 Popup / Dialog 窗口时，兄弟声明顺序就是绘制顺序。
- `fillMaxWidth` 的行容器按构造不可能横向越界，不能用它判断“长文本没有挤掉右侧控件”；判据落在会被挤掉的控件上，同时断言“在卡内”且“宽度 > 0”。
- **主题维度最容易整段假绿**：`FineCatTheme` 的 `darkTheme` 默认取 `isSystemInDarkTheme()`，只有 `FineCatApp` 会把偏好映射进去。组件级 harness 直接挂 `FineCatTheme { … }` 时，改偏好一个像素都不会变。harness 显式传 `darkTheme`；加一个主题探针节点，语义中带 `surface=<hex>`，期望值由 `lightFineCatColors()` / `darkFineCatColors()` 推导；截图要打开看，字节不同只证明不是同一张图。
- 每个断言都做一次判别力演练：临时改坏生产代码，确认断言失败，再还原。

### 操作与环境

- `performClick()` 前没有 `performScrollTo()` 时，滚动容器外的节点 bounds 为零，点击落在 (0,0)，后续等待超时而不是“找不到节点”。排查这类红先打印 `boundsInRoot` 和窗口实际高度；三键导航与手势导航的应用区高度不同，取证前固定导航模式。
- 改设备配置后按进入时的基线还原，不用 `wm size reset` / `wm density reset`：设备基线可能本身带覆盖值。在 `@BeforeClass` 保存 `Override size` / `Override density`，`@AfterClass` 原样写回，没有覆盖项才 reset。
- 破坏状态的滚动实验放在用例最后，先做不破坏状态的位置和可操作断言。
- 依赖设备、密度或字号的像素值不登记为门禁断言，也不抄进文档。文档只写关系（例如“末行底边在键盘顶边之上”），具体数值留在测量输出里。

## 考虑过的方案

**按截图哈希判断主题是否切换。** 字节不同不代表颜色正确。

**冻结测量到的像素值作为期望。** 换设备就红，或诱导修改期望凑绿；同一测量曾因导航模式和尺寸覆盖的脏状态漂移，导致多处文档数字互相矛盾。

## 影响

- 写设备断言的成本更高，但通过的结论可信。

## 核对入口

- `FineCatDrawerF07DeviceTest`（`touchBoundsInRoot` 探针、结构序判据）、`FineCatThinkingMapF11DeviceTest`（`themeProbe`）。LazyColumn 可见性判定原用于已随轨迹页删除的轨迹回归用例，需要时按上文条件重新实现。
