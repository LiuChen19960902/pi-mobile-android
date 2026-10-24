# 贡献指南

## 环境

- JDK 17（`sourceCompatibility`/`jvmTarget` 都是 17）
- Android SDK：`compileSdk` / `targetSdk` 36、`minSdk` 24（需要 platform 36 与 build-tools 36）
- Gradle Wrapper 自带，无需本机 Gradle；Kotlin 2.3.20 + KMP

配置 SDK 路径（二选一）：

```bash
export ANDROID_HOME=/path/to/android-sdk
# 或写 local.properties（已被 .gitignore 忽略）：sdk.dir=/path/to/android-sdk
```

## 构建与运行

```bash
./gradlew :androidApp:assembleDebug        # Preview APK → androidApp/build/outputs/apk/debug/
./gradlew :androidApp:installDebug         # 安装 Preview 到已连接设备
./scripts/deploy.sh                        # 开发循环：构建安装 → adb reverse 端口 → 启动 App → 清 logcat
```

`scripts/deploy.sh` 假设网关跑在本机（含 WSL）并监听 3080，通过 `adb reverse` 让手机的 `127.0.0.1:3080` 回到电脑——这也是 App 里默认 endpoint 取 `ws://127.0.0.1:3080/ws/mobile` 的原因。真机走局域网或 tailnet 地址时不需要 `adb reverse`。

普通启动默认是 FineCat。日常调试与真机验收使用 Preview，`debug` 是其 Gradle 构建类型，包名、数据隔离和分发规则见 [Preview 调试渠道](Docs/releasing.md#preview-调试渠道)。Preview 可通过 `debug_finecat_ui=false` 启动参数切回旧界面对照；正式版不开放此参数。

## 测试

```bash
./gradlew :shared:testAndroidHostTest      # commonTest 在 JVM 上跑（Linux/WSL 快测，首选）
./gradlew :androidApp:testDebugUnitTest    # androidApp 单测（含跨仓库 GatewayConformanceTest）
./gradlew :shared:check                    # shared 完整检查（host 测试 + lint）
```

跨仓测试要求网关源码与依赖可用：先在同级 `pi-mobile-gateway` 执行 `npm ci`，或用 `PI_GATEWAY_DIR` 指向它。缺少网关或 Node 时，一致性测试会跳过；检查报告中的 skipped，不能把跳过当成验证通过。

要求：

- 改 `GatewayRuntime`（连接编排、lane、重连、多入口选路、凭据处理）必须带 `commonTest` 用例——这层用假 transport 与虚拟时钟就能覆盖，不需要真机
- 改协议相关（帧类型、字段、错误码）必须**先改网关仓库的 `PROTOCOL.md` 与跨仓库一致性测试**，再动本仓库客户端；两端一起提交才能保证不漂移
- UI 改动优先用现有 Compose 单测覆盖显示投影逻辑；只有生命周期、Keystore、真机手势这类才用 `androidTest` 或手工验证

## 代码风格与约定

- `kotlin.code.style=official`；仓库当前**没有**配 ktlint/detekt/spotless，请保持与周边文件一致的缩进与换行，别顺手引入新的格式工具
- 注释、文档、提交信息用中文；代码标识符与协议字段保持英文精确
- 分层依赖单向：`androidApp → shared → shared/platform 契约`。`shared` 的 `commonMain` 不得 import 任何 Android 类型；需要平台能力时加接口到 `GatewayPlatformContracts.kt`
- 禁止过度抽象：能在现有 `GatewayRuntime` / 门面里加一个参数解决的，不要新建一层包装
- 敏感值纪律：**禁止**把 endpoint、token、配对码、原始帧文本写进日志或 `toString()`；诊断只允许输出白名单内的稳定错误码（见 `AndroidGatewayDiagnostics`）

## 提交

- 提交信息用中文，一句话说清"改了什么 + 为什么"，有取舍时写进行文
- 一个提交只做一件事；协议变更、UI 变更、依赖升级分开提
- 不要在提交里带本机环境信息（真实内网 IP、tailnet 地址、主机名、设备型号、绝对家目录）。示例地址一律用 `<LAN_IP>`/`<TS_HOST>` 记号并给出获取命令，不用形似真 IP 的假地址

## 提 PR 前自查

1. `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest` 全绿
2. 涉及连接/凭据的行为变化已在 `Docs/gateway-connect.md` 或 `SECURITY.md` 更新，且没有留下与代码不符的旧说法
3. 新增依赖已登记到 `Docs/third-party-notices.md`（含版本与许可证）
4. 真机跑过一次受影响的主链路（配对 / 发消息 / 历史分页 / 重连）
5. 非平凡改动已新增或更新 Agent Note，见 [AGENTS.md](AGENTS.md) 与 [.agents/notes/README.md](.agents/notes/README.md)
6. 改了文档或 Agent Note：`uv run --no-project python scripts/check-docs.py` 通过；改了该脚本还要跑 `uv run --no-project python -m unittest discover -s scripts -p 'test_*.py'`

## 持续集成

[Android CI](.github/workflows/ci.yml)在GitHub托管Linux上运行文档与脚本检查、共享层和Android JVM测试、Preview构建，并要求网关一致性测试实际执行且没有跳过。配套网关固定到工作流中的提交，CI先安装其依赖。网关私有时需要名为GATEWAY_CI_SSH_KEY的只读单仓deploy key；公开后可删除该secret，使用普通公开检出。

网关仓的CI执行类型检查、lint与串行单测。两端有协议变化时同步维护配套提交与一致性用例。真实模型、真机、正式签名APK不属于普通PR检查；CI通过不代替这些验证。

工作流与权限取舍见[GitHub协作记录](.agents/notes/implemented/process/2026-10-16-github-ci-and-maintenance.md)。
