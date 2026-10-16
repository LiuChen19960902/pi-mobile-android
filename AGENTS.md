# AGENTS.md

Pi Mobile 的 Android 客户端：把电脑上 [Pi](https://github.com/earendil-works/pi) 的会话、实时输出和执行轨迹带到手机。手机只连接姊妹仓库 `pi-mobile-gateway`，网关是哑管道加 Pi SDK 协议客户端，Android 只做客户端。

本文件面向人类协作者和 coding agent（Pi、Codex、opencode 等读取本文件，Claude Code 通过指向本文件的 CLAUDE.md 符号链接读取）。改代码前先读 [README.md](README.md)（产品能力）、[ARCHITECTURE.md](ARCHITECTURE.md)（分层与数据流）和 [CONTRIBUTING.md](CONTRIBUTING.md)（环境、构建、提交）；改动涉及某个机制时，再到 [Agent Notes](.agents/notes/README.md) 查对应决策记录。

## 仓库结构

```
shared/       KMP 模块：协议、领域逻辑、连接运行时（commonMain 不依赖 Android）
  src/commonMain/   facade/ projection/ sync/ domain/ gateway/ protocol/ platform/
  src/commonTest/   JVM 上运行的共享逻辑测试
androidApp/   Compose UI 与 Android 平台能力
  src/main/         ui/（页面）、android/（状态门面）、platform/（transport、Keystore、DataStore）
  src/test/         JVM 单测，含跨仓库 GatewayConformanceTest
  src/androidTest/  设备用例
  src/debug/        仅 debug 构建的测试宿主与试用入口
Docs/         连接拓扑、Pi 兼容边界、发布、第三方声明
scripts/      开发脚本
.agents/notes/  Agent Notes：设计决策与易错机制的记录
```

## 常用命令

```sh
./gradlew :shared:testAndroidHostTest      # 共享逻辑测试（首选，快）
./gradlew :androidApp:testDebugUnitTest    # Android JVM 单测，含跨仓库一致性测试
./gradlew :shared:check                    # shared 完整检查
./gradlew :androidApp:assembleDebug        # 出 debug 包
```

环境前提、跨仓库测试的网关位置（同级目录或 `PI_GATEWAY_DIR`）和 CI 定义见 [CONTRIBUTING.md](CONTRIBUTING.md)。只运行与改动相关的检查，报告中只列实际执行过的命令。

## 与 Pi 原生行为保持一致

- **涉及行为语义的改动，先核对 Pi 原生在同一场景下的实现**：失败处理、降级/容错、超时、重试、模型与协议选择、会话创建顺序、队列与压缩等。手机端必须与 Pi 一致，或对服务化形态做如实映射（例如 CLI 的“进程失败退出”映射为“拒绝单条消息”）；差异要能说清为什么合理，并写进 Agent Note。
- 以**网关实际锁定的 Pi SDK 版本**为准，不以本机 CLI 版本或记忆为准。源码位置：网关 `node_modules` 中的 Pi 包，或 `readlink -f "$(command -v pi)"` 向上找到包根的 `dist/`，按符号检索（升级后 chunk 文件名会变）。
- 区分 TUI 拦截与 SDK 行为：TUI 编辑器里的内置命令、对话框和快捷键不一定存在于 SDK 的 `prompt()` 路径。
- 只对齐 Pi 公开 SDK 的通用消息、工具与会话契约，不为某个第三方扩展、私有状态或 TUI 面板做专用适配，也不依赖任何个人本地扩展或配置。边界见 [Docs/pi-compatibility.md](Docs/pi-compatibility.md)。
- 不修改 Pi 源码。测试不读写真实的 `~/.pi`：子进程的 `PI_CODING_AGENT_DIR` 必须指向临时目录。

## 协议与分层

- 协议真源是网关仓库的 `PROTOCOL.md`。改帧类型、字段、错误码或能力宣告时，**先改网关协议与跨仓库一致性测试，再改客户端**，两端一起提交。
- 新能力通过 hello capabilities 宣告；客户端在旧网关上隐藏对应入口，不猜测能力。
- 依赖方向单向：`ui → android 门面 → shared → platform 契约`。`commonMain` 不得 import Android 类型；需要平台能力时在 `GatewayPlatformContracts.kt` 加接口。
- 需要上游凭据的操作（连接测试、拉取模型清单等）一律经网关；手机不保存 API Key。

## 代码约定

- 代码保持简单：能在现有 `GatewayRuntime` / 门面里加一个参数解决的，不新建一层包装；不为测试在生产代码里加测试缝。
- 新增请求类型时，同时接入超时、取消、拒绝三种终态的状态释放路径，否则 loading 会永久卡住（见对应 Agent Note）。
- 敏感值纪律：不把 endpoint、token、配对码、原始帧文本或上游原始错误 message 写进日志、`toString()` 或界面；界面错误按请求类型和稳定错误码映射成短文案。
- `kotlin.code.style=official`，与周边文件保持一致；仓库未配置格式化工具，不顺手引入。
- 注释、文档、提交信息用中文；标识符与协议字段保持英文。注释只写代码本身表达不出的约束和原因，密度与周边一致。

## 测试与验证结论

- 改 `GatewayRuntime`（连接编排、lane、重连、多入口、凭据）必须带 `commonTest` 用例；UI 显示逻辑优先用 JVM / Compose 单测覆盖。
- 验证结论按实际执行的层级写：静态核对、JVM 测试、设备用例实际运行、真实网关与 Pi 调用、真机人工检查各自独立。**编译通过的设备用例不等于已运行；被跳过的跨仓库测试不等于通过**。
- 跨组件的并发与终态问题要走真实 `FineCatApp` 根入口验证，组件级 harness 可能整类假绿。断言要能被反例打红：改坏一次生产代码，确认用例会失败。
- 依赖设备、密度或字号的像素值不写成断言阈值或文档数字；文档只写几何关系。
- 详细的测量与取证经验见 testing 类 Agent Notes。

## 文档与 Agent Notes

- 写或改文档前读 [Docs/AGENTS.md](Docs/AGENTS.md)：一事一处、写现状不写变更史、只写公开信息。改动文档后运行 `uv run --no-project python scripts/check-docs.py`，它检查 Agent Note 格式、链接与锚点、隐私形态和字数预算，CI 也会运行。
- [.agents/notes/](.agents/notes/README.md) 记录代码和文档承载不了的部分：为什么这样做、放弃了什么、哪些条件下会重犯。改动涉及某个机制时先用 `rg` 查对应记录。
- **非平凡改动必须在同一提交或 PR 中新增或更新 Agent Note**：改变行为、协议或持久化格式、跨文件约定、测试策略、流程，或做了维护者以后可能重新讨论的取舍。纯机械或局部修改可以不写。同一决策更新原记录；决策被推翻时新建记录并互相链接（[规则](.agents/notes/AGENTS.md)）。

## 提交与隐私

- 一个提交只做一件事，协议、UI、依赖升级分开提交；提交信息说清“改了什么 + 为什么”。
- 不提交本机环境信息；示例地址用 `<LAN_IP>`、`<TS_HOST>` 这类记号并给出获取方法。
- 用 `git status --short -- <文件>` 核对文件是否已跟踪，`git diff` 无输出不能证明已提交。暂存时明确文件范围，不用 `git add -A` 把临时探针带进提交。
- 新增依赖登记到 [Docs/third-party-notices.md](Docs/third-party-notices.md)；连接与凭据行为变化同步更新 [Docs/gateway-connect.md](Docs/gateway-connect.md) 或 [SECURITY.md](SECURITY.md)。

## 个人补充规则与编辑本文件

- 个人环境、设备与授权流程写在被 `.gitignore` 忽略的 `AGENTS.override.md` 或 `AGENTS.local.md`，不提交，也不写个人偏好进本仓库的规范文件。Pi 与 Codex 遇到 `AGENTS.override.md` 会**替代**而不是追加 `AGENTS.md`，该文件开头应要求先读本文件。
- `CLAUDE.md` 是指向本文件的符号链接，规则只改这里。每条规则自成一句并链接详细文档；机制说明属于 Agent Note 或 ARCHITECTURE.md。
