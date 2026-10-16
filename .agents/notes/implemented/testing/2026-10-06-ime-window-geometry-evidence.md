# Agent Note: IME 与窗口几何的设备取证

Status: implemented

## 问题

键盘与浮层的问题需要在设备上量窗口几何。默认的 Compose 测试宿主与生产窗口行为不同，很多“看到的现象”其实是测试环境造成的；从外部采样系统状态又常常拿到动画中的混杂帧或恒真的判据。

## 决定

### 生产等效的测试宿主

- Compose 测试规则默认使用 `ui-test-manifest` 中的 `androidx.activity.ComponentActivity`：没有 `windowSoftInputMode`（默认 adjustPan），也没有 `enableEdgeToEdge()`，`WindowInsets.ime` 恒为 0。在这种宿主上末行落在键盘下方不是产品缺陷。
- 需要量 IME 几何时使用 debug 源集中的隔离 Activity，逐条对齐生产 `MainActivity`：清单声明 `adjustResize`、`onCreate` 调用 `enableEdgeToEdge()`、在 `onCreate` 中 `setContent`（Activity 因旋转重建后组合能重新挂上）。
- 测试侧用 `createEmptyComposeRule()` 加自管 `ActivityScenario.launch`。stateHolder 由测试注入，旋转后不清空。`createComposeRule()` 会先启动自己的 Activity，旋转或改尺寸后报 `No compose hierarchies found`；设备配置放在 `@BeforeClass`，或改用自管宿主。

### 判据

- 在同一份 `dumpsys window windows` 快照内解析，不跨命令采样。目标浮层：`Pop-Up Window` 标题、排除 `EXITING`、按 frame 实宽筛选（选择手柄也是 Pop-Up 但很窄），候选必须恰好一个；不用 mAttrs 的请求尺寸选窗。键盘顶边取 `InputMethod` 窗口 `touchable region` 的上沿。判据：浮层底边不低于键盘顶边，且 `sim={adjust=resize}`。
- 键盘收起后 `touchable region` 仍保留上次位置，必须与 `mInputShown=true` 联用，再加“键盘顶边位于屏幕下半部”的关系判据。
- `dumpsys window displays` 的 `app=` 高度在键盘上下屏时不变，不能用来证明 adjustResize 生效。
- IME 窗口自身的 frame 与 content inset 不是应用 root view 的 `ime.bottom`；需要定位时读应用当前的 native insets。
- 断言写实际观测值，判定由单独的检查承担；先判定后记录会让断言退化成恒真。
- 键盘展开窗口很短，判据在测试进程内用 `uiAutomation.executeShellCommand` 采集。
- 全屏卡片的底边本来就在屏幕底，不以“卡片被抬到键盘之上”为判据；要验证的是滚动后末行能否到达键盘上方。上滚先 `performScrollTo`，再小步上滑并复查节点仍在。

### 截图与配置

- `uiAutomation.takeScreenshot()` 只截应用窗口，不含 IME；拍键盘用 shell `screencap`，二进制经 base64 回传。
- `ActivityInfo.windowSoftInputMode` 自 API 29 起是 `@hide`，不用它取证。

### 横屏的三种强度

部分模拟器的物理显示不随 `user_rotation` 等方式旋转，但这不代表无法验证横屏：

1. 真横屏：Activity 设置 `requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE`，与显示能否旋转无关，`Configuration.orientation` 真正变为 landscape。
2. 宽视口：`wm size` 改窗口几何，orientation 仍为 portrait。
3. 显示旋转：部分模拟器不支持。

判断旋转时比较枚举名（`ROTATION_90` / `ROTATION_270`），不要把 `ROTATION_90` 中的 90（角度）与 `Surface.ROTATION_90` 的枚举值 1 比较。取证中记录用的是哪一种强度，不混称“横屏”。

## 考虑过的方案

**在默认测试宿主上量 IME。** 宿主不派发 inset，结论无效。

**从外部轮询 dumpsys。** 容易错过键盘展开窗口，且跨命令采样混入动画帧。

## 影响

- debug 源集多了测试专用的 Activity，只在 debug 构建中存在。
- 键盘、焦点等系统标志都不能单独证明可以输入；最终要确认实际提交的文字与草稿显示。

## 核对入口

- `FineCatCompatHostActivity`（debug）、`FineCatCompatF12WindowDeviceTest`、`FineCatThinkingMapF11DeviceTest` 中的 `scanWindowSnapshot`。
