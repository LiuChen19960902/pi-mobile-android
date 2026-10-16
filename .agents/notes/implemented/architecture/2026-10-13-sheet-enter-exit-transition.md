# Agent Note: 底部卡片的入场与退场动画由 Host 保留组合

Status: implemented

## 问题

`FcSheet` 源码里写着 340ms 的滑入和淡入，但所有入口都是条件挂载并在首次就传 `visible=true`，`AnimatedVisibility` 直接从可见初态渲染，入场动画从未发生；关闭时调用方 `if (flag)` 立即卸载，退场动画也被截断。debug gallery 中常驻、先 false 再 true 的组件看起来一切正常，掩盖了问题。

## 决定

- 入场：共享组件使用初始为 false 的 `MutableTransitionState` 与 `rememberTransition`，遮罩和卡片作为同一 Transition 的两个 `AnimatedVisibility` 子动画，保留原有时长。各页面不加延时或重复的可见状态。
- 退场：`FcSheetHost` 保留实际内容，直到同一 Transition 完成；不以固定 delay 代替动画状态。所有使用中的入口都要迁移到 Host，漏一个仍会直接消失。关闭标志与保存回调即时执行，校验或异步失败时卡片保持；退场期间遮罩屏蔽重复触摸和返回。
- 退场期间参数会先被清空（可空的 Provider ID、模型详情参数），Host 保留最后一个非空值供退场绘制；删除模型行后不能再用旧索引从变短的列表取值。旧快照只用于关闭中或等待收起回调的一帧，打开态不为动画保留可交互的旧表单。
- 嵌套 Host 同时读父级 target，父卡关闭时子卡同时退场。
- Material3 `ModalBottomSheet` 有库内的 Hidden 初态和 `show()`，不存在此问题。

## 考虑过的方案

**在 `FcSheet` 中加 exit 动画即可。** 调用方立即卸载时 exit 没有机会执行。

**每个页面延迟卸载。** 重复代码且时长与动画脱节。

## 影响

- 卡片关闭后组合多存活约 340ms，期间的参数必须来自保留的快照。
- 动画回归要关闭 `autoAdvance`，在受控帧时钟下检查卡片坐标在早期、中途、结束时的实际位移，不能只断言节点存在或 visible 标志。

## 核对入口

- `FcSheet`、`FcSheetHost` 及其调用方。
