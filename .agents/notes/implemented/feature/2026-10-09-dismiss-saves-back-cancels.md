# Agent Note: 点外部保存、返回取消，离线草稿可取回

Status: implemented

## 问题

设置类卡片（Provider、API Key、模型范围、网关地址、会话改名、品牌签名）原来只有按钮能保存，点卡片外部或按返回都直接丢弃编辑。用户希望点外部即保存，同时保留一条明确放弃的路径。Compose 的遮罩和返回共用 `onDismiss`，`AlertDialog` 的外部点击和返回也共用 `onDismissRequest`，无法区分。

## 决定

- 普通选择和编辑类卡片：点外部 = 应用（复用原校验与提交），返回 = 取消。配对、删除、恢复默认仍要求明确操作。已即时生效的选择（例如模型选择）未编辑时外部点击只关闭，不重复写。
- 设置整页作为覆盖层，使用 `BackHandler(onBack = onBack)` 复用标题箭头的关闭逻辑；在内部弹卡之前注册，让后组合的弹卡先处理返回。关闭页面只改变设置页可见性，保留底层首页或会话。
- 正文与树内的瞬态提示会被不透明设置页遮住；根部前景提示同时覆盖抽屉和设置页，绘制在它们之后。回归走真实设置入口并检查独立的 `finecat.noticeOverlay`，不能只查背景中的同文节点。该层属于同一 Compose 根，独立 Dialog/Popup 的表单错误仍由各自窗口呈现。
- `FcSheet` 给遮罩单独的 `onOutsideClick`，返回继续取消；卡片内的留白也要接收触控，否则点击漏到遮罩上触发保存。focusable Popup 同样区分外部应用与返回放弃。
- AlertDialog 开启外部保存时禁用内置返回关闭，在 Dialog 内容中给它自己的 `ComponentDialog` dispatcher 挂取消回调；挂在 Activity dispatcher 上不生效。API 33+ 的 Popup 返回手势不走 KeyEvent，需要独立 dispatcher 回调并在 attach / detach / dispose 时清理，最新回调用 `rememberUpdatedState`。
- 外部保存要等真实结果：Provider 按 token 与回显指纹，模型范围按 UUID，改名复用 name 单发 lane 并匹配 sessionId 与名称。改名回包名称错误或缺失时 holder 立即清 pending 并回到“未确认”；pending 非空时拒绝第二次改名。签名两字段一次 `updateFields` 落盘。
- 保存期间用户继续编辑时，旧的成功回执不能带走新稿：Provider 另比较提交时的 id、name、连接指纹和完整模型草稿快照；网关地址只有当前输入仍等于提交值时才清空并关闭。
- 编辑原值必须取持久配置，不能取恢复后的草稿，否则外部点击误判为“未编辑”而丢稿。
- 离线时 Provider 卡片的关闭、返回或外部点击，把完整表单暂存到 holder 内存，按入口与编辑 ID 隔离；Key 不进 Bundle 或磁盘，恢复的草稿不带旧测试许可；在线时明确取消仍丢弃。远端已经不存在的对象不能靠旧草稿复活。
- 校验失败或异步失败时卡片保持打开；请求未确认不能宣称未保存。

## 考虑过的方案

**只用按钮保存。** 与用户习惯不符，点外部丢稿的投诉多。

**外部点击直接关闭不等结果。** 失败时用户已经离开表单，编辑内容丢失。

**在 Activity 上抢占返回或持续抢输入焦点作为兜底。** 依赖假设，影响其他页面。

## 影响

- 外部点击变成有副作用的操作，测试要分别验证返回与外部点击的取消 / 应用及节点卸载；返回回归要用合法新稿，否则空值校验挡住保存时测试会假绿。
- 依赖 Compose UI 窗口 callback 与 dispatcher 的内部归属，升级时要复查。

## 核对入口

- `FcSheet` / `FcSheetHost` 的 `onOutsideClick`、`FineCatProviderForm` 与离线草稿缓存、改名 pending 处理。
