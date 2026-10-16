# Agent Note: Provider 配置的合并与测试语义

Status: implemented

## 问题

手机可以新增和编辑 Pi 的 Provider（写宿主 `models.json`）以及启用范围（`settings.json.enabledModels`）。这些文件也可能在电脑上被手改，而且包含手机不认识的键。需要定义：连接测试怎么拼 URL、测试结果能授权什么、字段“留空”“删除”“恢复默认”分别意味着什么、删除时如何清理启用范围。历史问题包括：合法配置被测成不通、拉清单 404、删掉的模型 pattern 永久残留、电脑上新增的 compat 键被静默删除。

## 决定

### 连接测试按协议复刻 Pi 的 URL 语义

| 协议 | 测试端点 | 模型清单端点 | baseUrl 约定 |
| --- | --- | --- | --- |
| `openai-completions` | `baseUrl + /chat/completions` | `GET {baseUrl}/models` | 必须含 `/v1` |
| `anthropic-messages` | `baseUrl + /v1/messages` | `GET {baseUrl}/v1/models` | 不含 `/v1`，SDK 自己拼 |
| `google-generative-ai` | `baseUrl + /models/{id}:generateContent` | `GET {baseUrl}/models` | 已含版本段 |

- 测试按请求的模型 ID 找宿主模型，不用数组首项的 API 或 Base URL 代替。存在模型级 baseUrl 覆盖时用覆盖值；漏用会产生 `/v1/v1/messages`。不按站点或协议自动增删 `/v1`，任意中转路径都保留。无覆盖时才测表单公共地址，新模型不借旧模型的覆盖。Provider 级模型清单只用公共地址。
- 请求必须带所测模型的 api；新建的 anthropic / google 模型没有宿主回退，不能被默认的 openai 探针测。
- 测试按草稿顺序串行，首个成功即停；transient（超时等）继续测下一项，不发放保存许可。聚合终态按测试顺序保留每个模型的 ID、结果和摘要，不能只留最后一项（否则末项成功会盖住前面的失败）。

### 测试结果能授权什么

- 测试结果绑定发起时的连接指纹（baseUrl、apiKey、模型 id@api）；编辑后不能借用旧结果，回执时也不按新草稿重新授予。
- 保存门槛：至少一个模型实际测试成功；transient 不能给改过的草稿发新许可；未改动且已有成功状态的可以沿用。
- 保存后不自动测试。写前写后地址、API、Key、headers 或模型集合发生变化时清除过期诊断；只改名称或数值元数据时保留。草稿测通不等于盘上另一份配置已认证；测试应答后重读宿主配置，只有有效地址、API、Key 仍与实际探针一致才更新存储的诊断。config 类失败只在认证当前已存配置时落盘；transient 保留旧的成功、只清过期失败。
- 连接指纹不包含模型名称、容量、推理 / 图片开关和 compat，不能用它判断整份表单是否有改动；dirty 判断按打开时的持久配置与完整草稿比较。

### 字段的三态语义

- **模型级 `baseUrl`**：省略 = 保留，字符串 = 覆盖，显式 `null` = 删除键、恢复继承 Provider；宿主 JSON 中不保留 null。草稿探针的 `modelBaseUrl` 同样区分省略与 null，测试前先 trim、空值转 null。旧网关没有该能力时隐藏编辑。
- **compat**：整个字段省略 = 保留原配置；发对象但省略某个手机管理的键 = 清除该项覆盖；发 `{}` = 清空全部手机管理的键、保留宿主私有键（网关按 `{...宿主私有键, ...phoneCompat}` 合并）。`null` 值被网关跳过，手机不发显式 null。只有用户动过这一层（`compatTouched`）才把它当权威整份下发；换协议时只在确实有键被过滤时才置 touched。
- **`thinkingLevelMap`**：键是 Pi 的七个档位，值是 string 或 null。网关对非空 map 整体替换，遗漏旧键会删除覆盖；省略或空 map 保留旧值。改键时保留其取值和所有未编辑的键；显式 null 与“恢复默认”不同。目前不提供用空 map 清除最后一个映射。编辑器显示全部标准键，未改的显示“默认”并省略；换键候选按草稿实际占用判断。
- **容量**：留空 = 不下发，网关 `put` 按 `{...priorModel, ...model}` 合并，所以留空 = 保留旧值；网关只写入正整数。只有新模型 ID 的空容量写入 Pi SDK 的缺省值（上下文 128000、输出 16384），使新主机不必先从模型目录读出参数就能保存；已有 ID 的空容量省略。SDK 缺省不是厂商能力，SDK 升级后复核 `provider-composer` 的 `modelFromJson`。
- 数字缩写 K = 1000、M = 1000000；旧整数不能被新倍率整除时原样显示，只改显示和输入解释，不自动转换宿主已存的整数。

### 启用范围

- `provider-put` 不自动追加 `providerId/*`：新增定义不扩大启用范围。
- 本次编辑中删掉的模型行调用 `removeEnabledModelPatterns(providerId, removedModelIds)` 清理具体 pattern；`provider-delete` 调用 `removeEnabledModelPatternsForProvider` 按前缀清理（`providerId/*` 与 `providerId/<模型>` 全清）。`enabledModels` 中常是具体 pattern，只拼 `${providerId}/*` 去删会因不匹配而残留。更早遗留的 pattern 需要手改 settings 或删除 Provider 后重建。
- 模型菜单读 `getGlobalSettings().enabledModels`，再走 SDK resolver 解析通配符、思考级别和顺序；SettingsManager 会合并项目设置，不能把网关工程目录的范围当成手机要读的全局范围。全部 pattern 无匹配时按 Pi 原生回到全部可用模型。
- 编辑全局范围时：显式保存才写具体 ID，保留已有 `thinkingLevel`；无匹配的既有项单列并默认保留；“恢复默认”写空数组（Pi 语义为全部可用）。读到坏 JSON 或类型无效的 `enabledModels` 拒绝编辑，不能降级后覆盖宿主。保存串入 settings 写队列，flush / reload 确认落盘。SDK 1.0.4 的 `setScopedModels` 只更新循环范围，不调用 `setModel`；恢复会话不重选首个模型。
- 模型调序只是手机进程内的展示顺序，编辑和删除映射回原索引，提交仍按原顺序，不持久化到 `models.json`。

## 考虑过的方案

**保存时自动追加 `providerId/*`。** “增”看着正常，“删”时暴露残留；也会在用户不知情时扩大启用范围。

**保存后自动重新测试。** 上游响应波动大，超时不代表配置错误；自动测试会产生额外真实调用。

**compat 每次整份下发。** 会删掉电脑上新增的私有键。

**把 `thinkingLevelMap` 和 compat 纳入保存回执指纹比对。** 网关会归一和深合并，回显可能多键，判否会误伤真实成功。

## 影响

- 自定义 headers 的认证方式尚未完整复刻 Pi 的探针行为。
- 多份规则依赖网关 `pi-store.ts` 的合并实现，网关改合并语义时必须同步本记录与客户端。
- 凭据相关的部分见 [凭证盲客户端](../architecture/2026-09-18-credential-blind-client.md)，保存回执的归属见 [请求终态](../architecture/2026-09-18-request-terminal-states.md)。

## 核对入口

- 网关 `src/provider-test.ts` 的 `joinProviderUrl` / `testRequest`，`src/pi-store.ts` 的 `put` 与 `normalizeCompat`，`src/pi-runtime.ts` 的 `removeEnabledModelPatterns*`，`src/router.ts` 的 provider 请求。
- 客户端 `FineCatProviderForm`、`FineCatThinkingMap`、`FineCatCompat`、`ModelNumberParsing`。
- Pi：`openai` 客户端的 `buildURL`、`@anthropic-ai/sdk` 的 `post('/v1/messages')`、pi-ai `api/google-generative-ai`（`apiVersion = ""`）、`settings-manager`、`provider-composer`。
