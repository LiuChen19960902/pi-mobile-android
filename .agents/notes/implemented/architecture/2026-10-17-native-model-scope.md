# Agent Note: 模型范围以 Pi 官方解析为准

Status: implemented

## 问题

Pi 允许不同 Provider 有相同模型 ID。只差大小写的 Provider 在注册时独立，文本引用却不区分大小写；网关自行增加逐字匹配优先级会使同一份 settings 在手机和 CLI 得到不同范围。SDK 版本不同也会改变裸 ID 的歧义处理，不能把文件落盘当作选择结果一致。

## 决定

网关固定 Pi SDK 与直接使用的 pi-tui 为 1.3.0。模型菜单、scope 读取、保存后会话循环范围和新会话初始化直接调用公开 `resolveModelScopeWithDiagnostics`，不预过滤候选或实现另一套名称解析。保存仍写具体 `provider/model[:thinking]`，不改变当前会话模型，持久化与表单合并语义见[Provider 配置](../feature/2026-09-17-provider-settings.md)。

SDK 对裸 ID 的跨 Provider 歧义返回全部候选和 `ambiguous` 诊断；它不是无匹配，不进入 `unmatchedPatterns`。完整模型 ID 的冒号优先于思考后缀，通配符与模糊匹配由 SDK 处理。空或全部无匹配范围保持 Pi 的全部可用模型回退。

Provider 前缀在此 SDK 中仍忽略大小写，取可用目录中首个匹配的 Provider，顺序不代表创建时间；面板目录仍可列出两家，仅大小写不同的 ID 不保证能分别选择。网关不为解决该限制改写宿主 Provider、凭据、历史或 settings。需要独立通道时，用户应配置确实不同的 Provider ID。

### 注册身份与范围引用

聊天模型以 Provider ID 与模型 ID 组合区分，展示名不参与身份；不同 Provider 的同名模型可共存。`models.json` 中重复的同名 Provider 键按JSON解析保留最后一个值，不产生两个 Provider。与内置 Provider 同 ID 的自定义 `models` 按模型 ID 添加或替换，保留未列出的内置模型；替换时未声明的元数据按自定义默认值生成，不能当局部补丁。`modelOverrides` 才是保留未声明字段的局部覆盖，未知模型 ID 被忽略。网关沿用 SDK 组合配置，不另设同名去重或合并规则。

## 考虑过的方案

**网关逐字匹配优先。** 可以修复手机丢选，但改变官方解析结果。SDK 升级后撤除该兼容逻辑，回归直接比较网关与公开解析器的结果。

**自动重命名冲突 Provider。** 会改变配置、已有会话的模型引用及认证身份，不属于依赖升级，应按独立配置迁移处理。

## 影响

模型范围遵循锁定 SDK 的实际行为；升级需重新核对解析与会话契约，不能仅修改版本号。官方尚存的大小写限制也会体现在手机中，不宣称客户端已额外修复。

## 核对入口

- 网关 `src/pi-runtime.ts` 的 `listModels`、`modelScope`、`saveModelScope`、`createRealSession`。
- `tests/pi-runtime.test.ts` 的官方结果对照，以及真实 SDK 隔离新会话用例。
- Pi SDK 的 `resolveModelScopeWithDiagnostics`、`resolveModelReference` 和 `splitProviderPrefix`。
