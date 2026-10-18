# Agent Note: Provider 按连接改动决定测试要求

Status: implemented

## 问题

编辑名称、容量或思考映射时要求重新测通，会把普通配置保存绑在上游的可用性上；聚合成功也不能证明另一条刚改地址的模型可用。测试应在使用模型前发现基础连接问题，同时给出可操作的失败原因。

## 决定

连接测试是手机编辑器的保存校验，Pi SDK 1.3.0 的配置文件写入没有这一前置要求。网关 `provider-put` 保持独立的格式校验与合并语义，不把诊断状态用作模型启用范围。

| 改动 | 保存所需测试 |
| --- | --- |
| 新建 Provider | 当前模型中一项通过 |
| 更换 API Key、改变公共有效地址 | 受影响模型中一项通过 |
| 已有 Provider 新增模型，或修改模型 ID、协议、模型有效地址 | 每个受改模型分别通过 |
| 名称、顺序、删除、容量、输出上限、思考映射、能力与 compat | 仅字段校验，无需测试记录 |

有效地址按模型覆盖优先；全部模型都有覆盖时，改公共地址不改变这些模型的连接。公共地址留空沿用宿主值。既有 Provider 删除全部模型后无受影响连接，可以保存；新建 Provider 仍需至少一个可测试模型。模型详情的测试只请求当前模型，Provider 按视图顺序请求尚需验证的模型；满足全部必要条件后停止。测试后改普通参数、删除其他模型不使已有连接结果失效。

结果绑定 Provider ID、模型 ID、协议、有效地址与本次输入的 Key，只在当前表单内存保留。每次单模型请求生成独立 `providerTestId`，网关在成功、失败与有归属的错误中回显。Runtime 与门面均匹配该标识；带标识的探针超时只结束自身，避免切断其他业务连接。超时重试的旧成功不能认证新连接；仅匹配 Provider ID 或丢弃固定条数的旧回执无法应对乱序。历史聚合状态不授予新连接许可。测试前拒绝重复模型 ID，防止反馈串行。网关未声明 `provider-test-id-v1` 时提示升级并禁用测试，普通编辑仍可保存；新网关兼容不带标识的旧客户端。未测、失败、中断均不算通过。离线不能保存，保存请求仍须等自己的成功回执，测试成功不会自动保存。

探针按协议发最小非流式生成请求，收到有效文本或思考输出即成功；HTTP 200、任意 JSON 或模型目录不足以证明模型能响应。不验证流式、图片、工具与思考档位能力。请求有超时、响应体上限，不跟随重定向。失败只映射白名单诊断，显示 HTTP 状态、耗时与排查建议；上游原文、URL 与凭据不进入测试反馈。

字段合并和 Pi URL 规则见[Provider 配置语义](2026-09-17-provider-settings.md)。

## 考虑过的方案

**整张表依赖至少一次测通。** 会阻止未测试过的既有配置改名或改容量，也可能用另一模型的成功放行新地址；本决定部分取代上述记录中的保存门槛。

**测试完整聊天能力。** 流式、图片、工具与每个思考档位需要不同请求，超出基础连通目的，也会增加耗时和调用成本。

**用 SDK 会话或流式运行时完成探针。** 会引入更多生命周期、认证来源与流式判定。本探针保留有界的单次 HTTP 请求；协议拼接按锁定 SDK 核对，不声称已验证完整 SDK 请求或自定义 headers。

**直接展示上游错误原文。** 可能带地址或凭据；稳定分类与 HTTP 状态提供排查线索，同时避免泄露。

## 影响

测试通过说明对应基础请求可用，不保证普通参数或未测能力组合一定可用；上游后来故障也可能使聊天失败。修改 Key 即使输入了相同值也需验证，因为手机读不到已保存的原值。关闭表单会丢弃当前测试许可，连接改动草稿重新打开后须重测；普通修改仍可直接保存。

## 核对入口

- `FineCatProviderForm.requiredTestGroups`、`connectionFingerprint`、`untestedGroups`；`ProviderSavePolicyTest`。
- `testProviderModelsUntilSuccess`、`providerTestSummary`、`providerTestHint`；`ProviderConnectionTestTest`。
- `ProviderEditorForm`、`FineCatModelDetailSheet`；`FineCatProviderDirectEntryDeviceTest`。
- 网关 `src/provider-test.ts` 的 `testProvider`、`hasModelResponse`；`tests/provider-test.test.ts`。
- `GatewayConformanceTest.providerProbeCarriesSafeDiagnosticsAndRequiresModelResponse` 通过回环 HTTP 与真实网关验证诊断契约，不调用真实模型。

- `GatewayRuntimeIntegrationTest.providerProbeTimeoutThenRetryCannotConsumeLateSuccessOrError` 覆盖迟到成功、错误、重复与缺标识响应；根入口设备用例另验证表单不会借用旧成功。
