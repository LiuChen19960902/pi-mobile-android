# Agent Note: FineCat 界面的视觉约定与易错点

Status: implemented

## 问题

FineCat 界面与旧 Pi 主题并存，部分组件复用旧实现；一些视觉缺陷（浅色模式黑底黑字、按压出现方形灰块、胶囊底角变成直角灰块）都来自主题来源或 modifier 顺序，而不是样式数值。

## 决定

### 进行中的指示

- 全仓“进行中”统一用 `PiSpinner`：与 pi-tui Loader 相同的盲文帧 `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`，80ms 一帧。不再引入 `CircularProgressIndicator`；确定性进度（例如上下文占用环）保留进度环。盲文宽度用 `widthIn(min=…)` 为大字号留空间。
- 流式思考用 `TailFollowText`：左对齐打字机效果，溢出后整体左移，左缘渐隐只在溢出时出现。不用 `TextAlign.End` 代替（短文本会从右往左“长出来”）。`wrapContentWidth(unbounded = true)` 的 `align` 默认是 `CenterHorizontally`，超宽时自带 -overflow/2 的左移，再叠加 `offset(-overflow)` 会推出左缘；必须写 `wrapContentWidth(Alignment.Start, unbounded = true)`。折叠后的预览取尾部。

### 主题来源

- `FineCatTheme` 不提供旧主题的 `LocalPiDark`，后者默认值为 true。复用旧组件时，容器和文字必须从当前同一主题取色；容器用读 `LocalPiDark` 的 `piGlassTint`、文字用 `MaterialTheme`，浅色模式就会得到深色容器与深色文字。共享弹窗显式接收当前主题颜色，不再套一层旧主题。
- 入口可达、取消成功都不能证明配色正确，要打开亮暗两种实际画面检查；背景匹配也不自动证明小字对比度合格。

### 按压反馈

- 圆角药丸的底色画在子 Text、`clickable` 在外层 48dp 矩形时，默认 ripple 覆盖整个矩形。FineCat 统一只把 Material3 的 pressedAlpha 设为 0（Material3 1.4.0 的 `RippleDefaults.RippleAlpha`），保留 focus / hover / drag 反馈和组件自身的选中底色；不整体关闭反馈，否则丢失硬件键盘焦点指示。
- 自绘的按压底色（`collectIsPressedAsState` 加 `background`）不受主题影响，要单独检索。Canvas 外层的 `combinedClickable` 默认 indication 会让整块区域变灰，可以禁用 indication 保留长按。

### 阴影与裁切

`animateContentSize` 内部先调用 `clipToBounds`。旧顺序 `animateContentSize → shadow(clip=true) → background` 把阴影裁在矩形内，胶囊底角成了灰块；改为 `dropShadow → animateContentSize → clip(shape) → background`。

### 其他

- 面板圆角最多 12dp，按钮 6–8dp，状态点和进度环保留圆形。除显式的 `RoundedCornerShape`，还要检查 `MaterialTheme.shapes` 和 `TextButton` 默认的全圆角。
- 首页品牌偏好中 null 表示未自定义，空字符串表示明确删除；保存时不能用 `trim().takeIf(isNotEmpty)` 把删除变回默认。两段都为空时只在原文字位置提供编辑入口。
- 用户要求的“1px”指物理像素，用 `with(LocalDensity) { 1.toDp() }`，不是 1dp。

## 考虑过的方案

**直接关闭所有按压反馈。** 丢失焦点指示，影响键盘与无障碍。

**调整 elevation 修阴影灰块。** 根因是裁切顺序。

**为共享弹窗再包一层旧主题。** 扩大影响面，且与 FineCat 配色不一致。

## 影响

- 依赖 Compose 与 Material3 具体版本的实现细节（`animateContentSize` 的裁切、ripple API 名称），升级时以实际字节码和编译结果为准。

## 核对入口

- `PiSpinner`、`TailFollowText`、`ProcessDisclosure(previewTail = true)`、`FineCatTheme`、`saveHeroCopy`、首页胶囊的 modifier 链。
