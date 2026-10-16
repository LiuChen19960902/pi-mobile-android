# Agent Note: 公开的协作规范、Agent Notes 格式与文档检查

Status: implemented

## 问题

开发阶段的 `AGENTS.md` 和经验笔记只服务一位维护者：写满了本机网关、调试端口、分发方式、个人模型偏好、授权流程和内部编号，笔记按症状堆叠，同一机制的多轮修改按时间追加。项目开源后，协作者既读不懂这些上下文，也不应看到它们；同时需要一套所有人和所有 coding agent 都会自动遵循、并且不会在多人修改中逐渐走样的规范。

## 决定

### 规范文件

- 根 [AGENTS.md](../../../../AGENTS.md) 只写常驻规则：仓库结构、命令、与 Pi 原生对齐、协议与分层、代码约定、验证结论、文档与 Agent Note 要求、提交与隐私。机制说明链接到 Agent Note 或 ARCHITECTURE。
- [Docs/AGENTS.md](../../../../Docs/AGENTS.md) 是文档规范：各文档负责的内容（一事一处）、写作规则、废话清单、字数预算。[.agents/notes/AGENTS.md](../../AGENTS.md) 规定查重与保持记录同步。
- `CLAUDE.md` 是指向 `AGENTS.md` 的符号链接，让 Claude Code 加载同一份规范；首发导出脚本只放行这一个符号链接并原样导出，检查脚本要求它保持为指向 `AGENTS.md` 的链接。Pi、Codex、opencode 直接读 `AGENTS.md`。
- 个人环境、授权流程和个人偏好不进入仓库规范，写在被忽略的 `AGENTS.override.md` / `AGENTS.local.md`，或个人的全局配置与 skills 中。Pi 和 Codex 遇到 `AGENTS.override.md` 时会替代同目录的 `AGENTS.md`，所以该文件开头必须要求先读 `AGENTS.md`。

### 项目技能

项目级技能随源码提供：[记录笔记](../../../skills/project-notes/SKILL.md)、[编写文档](../../../skills/write-project-docs/SKILL.md)、[审查文字](../../../skills/review-project-prose/SKILL.md)和[Herdr](../../../skills/herdr/SKILL.md)。前三者引用项目规范，Herdr 保留原控制规则。发布导出只放行这些技能的正文与已有许可证，目录中的其他文件继续排除，避免把运行状态或配置一并带出。

### Agent Notes

- 目录为 `{proposed,implemented,rejected}/{feature,bug-fix,simplification,architecture,process,testing}/yyyy-mm-dd-topic.md`，固定头部与章节，`考虑过的方案` 必填，规则见 [README](../../README.md)。只用中文，不维护集中索引。
- 非平凡改动必须在同一提交或 PR 中新增或更新 Agent Note。
- 开发期的旧笔记不公开。仍有效的规则逐条改写进 `implemented/`：去掉内部编号、私有证据链接和过程叙述，保留机制、条件、取舍和核对入口；已删除功能的记录没有迁移。
- 私有旧笔记目录由根 `.gitignore` 排除；已跟踪文件从 Git 索引移除但保留本机副本。忽略规则不清除旧提交，首次公开仍须使用[筛选后的无历史快照](2026-09-19-public-release-snapshot.md)。

### 机械检查

`scripts/check-docs.py`（只用 Python 标准库和 git）检查：Agent Note 的目录、文件名、头部、Status 与目录一致、必备章节和禁止章节；公开文档的相对链接与 `#锚点`，以及是否指向不公开的路径；IP、本机路径、tailnet 主机名、邮箱和凭据形态；规范文件与 Agent Notes 中的内部编号和过程用词；常驻规范文件的字数预算；个人补充规则文件是否被提交。合理的命中用行内 `<!-- check-docs: allow <规则名> -->` 豁免。脚本有回归测试 `scripts/test_check_docs.py`，CI 与 PR 自查都运行它们。

## 考虑过的方案

**保留按症状组织的主题笔记，只做脱敏。** 当前规则与已被替代的口径混在一起，协作者无法判断哪段仍然有效；也没有“何时必须写”的约束。

**只靠文字规则与评审维持格式，不加检查脚本。** 最初的选择。协作者的 coding agent 不一定读完所有规则，隐私和链接问题在评审中容易漏看；目标是规范在多人修改下不被破坏，因此改为机械检查加回归测试。

**完整照搬参考项目的门禁体系（双语配对、冻结归档清单、文档站点、类型检查的代码块）。** 这些服务于更大的文档量和多语言发布；本项目只保留格式、链接、隐私、预算几项，需要时再加。

**集中维护一张索引表。** 每次改动都要同步，且容易与正文不一致；目录结构加 `rg` 检索足够。

**`CLAUDE.md` 写成普通文件，内容为 `@AGENTS.md` 导入。** 导出无需特殊处理，但多一个需要维护的文件；符号链接更直接，Claude Code 读到的就是 `AGENTS.md` 本身。代价是 Windows 未开启 `core.symlinks` 的检出中它会变成只含 `AGENTS.md` 文字的普通文件。

## 影响

- 协作者和各类 coding agent 读同一份规范，规则的格式、链接和隐私问题在提交前就能发现。
- 隐私检查只覆盖可机械识别的形态，截图、普通文字中的个人信息仍需人工检查。
- 旧笔记迁移时只核对了源码符号的存在性，没有重新运行测试；细节与代码不一致时按 `implemented/` 的规则就地修正事实。
- 规范文件变长时会触发字数预算，需要先迁移或精简。

## 核对入口

- `scripts/check-docs.py`、`scripts/test_check_docs.py`、[CONTRIBUTING.md](../../../../CONTRIBUTING.md) 的 PR 自查与 CI 定义、`.gitignore`。
- Pi SDK 的项目指令加载：`dist/core/resource-loader.js` 的 `loadContextFileFromDir`，候选顺序 `AGENTS.override.md`、`AGENTS.md`、`CLAUDE.md`，命中第一个即停止。
