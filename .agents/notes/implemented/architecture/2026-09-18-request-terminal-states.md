# Agent Note: 请求终态必须释放状态，读写终态分开归属

Status: implemented

## 问题

客户端对每个请求维护 loading 或“等待回执”状态。漏掉任一终态，loading 就永久卡住，后续点击被 `if (xxxLoading) return` 静默丢弃。更隐蔽的是多个请求共用一个结果槽位：保存成功后自动刷新列表，刷新请求取消了排队中的旧请求，取消通知与保存成功回执在同一帧写进同一槽位，表单只看到最后一条，把真实的成功判成“请求已取消”。

## 决定

### 终态释放

- 新增请求类型时，除了协议和 handler，必须同步接入 loading 释放集合（例如 `clearProviderRequestLoading` 与 `PROVIDER_REQUEST_TYPES`）。超时 `RequestTimedOut`、取消 `RequestCancelled`、拒绝 `RequestRejected` 三种终态都要结束 loading。
- 不要只看原始 error 帧的 handler：`GatewayRuntime.handleIncomingFrameLocked` 消费关联的网关 error 后转成 `RequestRejected` 并 return，UI 不会再收到原帧。
- `clearProviderRequestLoading(null)` 是全清，而 error 帧的 `requestType` 可空；无归属的帧不能抹掉其他请求的等待位。
- 取消原因要核对当前平台的实际值。Android 默认 OkHttp 的普通关闭 / 失败发布 `websocket-failure`，Runtime 转成 `RequestCancelled`，并不统一是 `connection-closed`；只匹配旧原因会让面板静默停住。`connection-replaced` 是主动换连接，不报断网。

### 读写终态分槽

- 读请求（`providers`、`provider-test`、`provider-models`）与写请求（`provider-put`、`provider-delete`）的终态走不同通道：保存终态在独立的 `providerSaveTerminal`，不受列表结果槽的自动清除影响。成功只认 `provider-saved` 帧，失败或未确认只认 `requestType == "provider-put"` 的终态。
- `RequestCancelled` 本身带 `requestType`，分槽不需要扩协议。
- 归属两道关：保存单发时铸造本地 token 随终态发布（不在终态发布时清 token，否则同一请求的后续终态失去归属）；再用回显内容与本次提交特征比对，只用于反证，不臆测。比对字段按“本次提交了具体值则回显必须等于它”与“客户端无条件下发、原样回显则直接比”两类处理；网关会归一或深合并的字段（`thinkingLevelMap`、`compat`）不纳入，否则误伤真实成功。
- 取消 / 超时是“未确认”，与真失败“已拒绝”分开显示，文案不能说成“没保存”：请求发出后的断连只代表未确认，宿主可能已经落盘。
- `provider-saved` 没有 requestId，迟到回执仍可能把全局提示写成“已保存”；保证的是表单不被错误收起。

### 错误文案

- 界面错误按请求类型和稳定错误码映射为短文案（`GatewayErrorText`），不回显上游原始 message 或堆栈，其中可能含端点或私人内容。不能用“包含中文就透传”区分本地与远端文案；平台错误入口只接受已知键。
- 恢复动作按来源选择：目录读取失败重试原目标路径；目录写入的取消 / 超时是“未确认”，不盲目重发；相机 / 图片失败不触发重连。映射成中文后不再靠中文字面量判断失败类别。
- 网关把某些不同原因归到同一个码（例如 `requireAbsoluteDirectory` 把 stat 异常都当作不存在，权限错误落入 `internal`），客户端不能从 `internal` 反推权限不足；需要细分时由网关提供可信的细分类码。

## 考虑过的方案

**因为结果槽没有 requestId 归属，就扩展协议。** 判断错误：取消事件已带 `requestType`，客户端分槽即可，扩协议会无谓扩大范围。

**只在原始 error 帧 handler 中释放。** 关联的 error 帧在 Runtime 层就被消费，handler 根本收不到。

## 影响

- 新增请求类型时多一处必改点。
- 这类问题只有走真实 `FineCatApp` 根入口才能复现：组件级 harness 没有根带来的额外请求，也就没有 lane 合并取消，同场景会整类假绿。见 [设备回归用例的写法](../testing/2026-10-06-device-regression-fixtures.md)。

## 核对入口

- `GatewayRuntime.handleIncomingFrameLocked`、`AndroidSharedStateHolder` 的 `clearProviderRequestLoading`、`providerSaveTerminal`、`handleProviderFrame`、`handleWorkspaceRequestCancelled`，`GatewayErrorText`。
- 测试：`FineCatProviderDirectEntryDeviceTest`（真实根入口）。
