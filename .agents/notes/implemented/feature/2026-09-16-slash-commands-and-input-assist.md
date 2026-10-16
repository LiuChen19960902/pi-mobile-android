# Agent Note: 斜杠命令路由与输入联想

Status: implemented

## 问题

手机经网关调用 SDK 的 `session.prompt()`，而 Pi 的内置命令（`/name`、`/compact`、`/model` 等）由 CLI 的 TUI 编辑器在提交时拦截，并不经过 SDK。照搬 TUI 命令系统不可行；一律吞掉未知命令又会阻断扩展命令和 skill / 模板展开。联想列表还要回答“列出来的命令发出去会不会有反应”。

## 决定

### 发送路由

- 发送前先经过 `consumeSlashCommand`。已知的内置命令不由输入框发起网关操作，只提示对应 UI 入口：`/name` 点标题改名，`/compact` 从上下文占用圆环进入，`/model`、`/thinking` 用输入框旁的模型和档位入口，`/copy` 用消息上的复制按钮，`/settings` 在抽屉底部；其余内置命令（`/tree`、`/fork`、`/export` 等）提示暂不支持命令入口，不代表同名 UI 功能不存在。
- 其他 `/xxx` 按普通文本交给 SDK，由扩展、skill 或模板处理。

### SDK 中各类命令的结果

按 Pi SDK `prompt()` 全路径（`agent-session.js`）：

- 扩展命令由 `_tryExecuteExtensionCommand` 执行 handler，可能真正生效、no-op 或失败，**都不产生 turn**。
- 输入 hook 消费（`emitInput` 返回 handled）同样不产生 turn。
- skill（`_expandSkillCommand`）和 Prompt 模板（`expandPromptTemplate`）展开成文本，通常产生 turn；但输入 hook 或同名扩展可能先消费，不能承诺“展开后必有 turn”。
- RPC / headless 模式下扩展没有 UI 上下文：`notify` 静默丢弃，`custom()` 返回 undefined，`select` / `confirm` / `input` / `editor` 返回 undefined。过滤 TUI-only 命令的责任在扩展作者（官方建议在 handler 中检查 `ctx.mode`）；Pi 官方 `get_commands` 刻意不按 mode 过滤。

### 联想

- 网关 `commands` 帧按 Pi RPC `get_commands` 列出三个来源（扩展、模板、skill），执行复用 message 原文透传。
- 联想层只列出确定可用的命令：模板和 skill 保留；扩展命令（`source == "extension"`）不进联想，因为 RPC 下无法静态判断 handler 是否依赖 TUI；内置命令不在清单里。手动输入全名仍原样交给 Pi，真正生效的扩展命令不受影响。
- 已加载的 `skill:name` 会遮蔽同名 Prompt；“显示 Skill 命令”开关和 `disableModelInvocation` 都不禁止手动显式调用。
- 补全按 `TextFieldValue` 的光标片段替换，保留 composition 和 selection；高亮只加颜色 Span，`OffsetMapping.Identity`，不换字体或字重，否则显示与光标错位。
- `@` 文件补全复用 Pi TUI 公开的补全与 `applyCompletion` 的引号和目录光标语义，在宿主侧查询路径候选，不接入启动参数的附件读取器。查询带 capability、queryId 和上下文；最新查询立即替换并取消旧查询，迟到结果按 queryId 丢弃；可严格关联的查询超时只结束自身，不回收整条连接，否则会打断生成。重载会话后 token 不变时由独立的 reset generation 触发补查。
- `@` 补全依赖宿主上的 `fd`。后台服务的 PATH 可能与交互 shell 不同，终端里能找到 `fd` 不代表 systemd 网关能找到；改服务 PATH 后必须重启进程。
- 联想清单按会话缓存，选中、重连、restart 后重拉；旧网关没有此能力时隐藏联想。

### 预览不触发安装

SDK 1.0.4 的 `DefaultResourceLoader` 即使 `noExtensions=true` 仍会解析 Package，缺包时可能自动安装。首页预览用公开 `SettingsManager.fromStorage` 取全局与项目规则的内存快照，把 packages 置空后再解析本地资源，并明确标记 `complete=false` 和被省略的来源；`@` 查询只用 SessionManager 取 cwd，不创建模型或会话。

## 考虑过的方案

**让 `/name`、`/compact` 直接发网关帧。** 早期实现过，后来统一让位于已有 UI 入口，避免维护两套入口。

**发送后若无 turn 就提示“命令无反应”（哨兵检测）。** 无法区分 no-op 与静默生效，已否决。

**网关转发 Pi RPC 的 `extension_ui_request`，让扩展对话框在手机上显示。** 协议已有该帧，但改动大，暂不做。若开源后反馈强烈，更合适的方向是请 Pi 在 `get_commands` 中加入作者可声明的 mode 元数据。

**协议层按 mode 过滤命令清单。** 与 Pi 官方 `get_commands` 不过滤的语义不一致。

## 影响

- 扩展命令需要用户记住全名手动输入。
- 依赖 TUI 的扩展命令在手机上无反应且无提示，与 Pi RPC 模式一致。

## 核对入口

- `AndroidSharedStateHolder.consumeSlashCommand`、`BUILTIN_SLASH_COMMAND_NAMES`；联想与补全的 FineCat 输入组件。
- 网关 `commands` 帧（`PROTOCOL.md` §4.21）与输入辅助后端。
- Pi SDK：`agent-session.js` 的 `_tryExecuteExtensionCommand`、`_expandSkillCommand`，`rpc-mode.js` 的 `get_commands`，`runner.js` 的 `noOpUIContext`，扩展 `types.d.ts` 中 `ctx.mode` 的说明。
