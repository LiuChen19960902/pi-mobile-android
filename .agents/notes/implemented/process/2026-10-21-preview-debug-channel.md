# Agent Note: Preview 作为唯一调试渠道

Status: implemented

## 问题

调试包与正式版使用相同应用身份时，签名差异会阻止覆盖安装；即使能覆盖，也会把实验网关配对和日常数据混在一起。分别维护 debug 与 preview 两个非正式渠道又会增加安装入口和验证组合。

## 决定

现有 `debug` 构建类型承载唯一 Preview 渠道，保留调试能力与现有测试任务。通过 `applicationIdSuffix` 隔离正式版，应用名称和版本后缀显式标注 Preview。桌面只保留 MainActivity 入口，测试宿主继续放在 `src/debug/`。使用方式和签名要求集中在[构建说明](../../../../Docs/releasing.md#preview-调试渠道)。

源码 namespace 和 Activity 类名保持稳定，运行时应用身份以变体的 applicationId 为准；开发启动脚本要用 Preview 包名加完整 Activity 类名，设备取证路径从目标应用目录取得，不能写死正式包名。两种构建都排除开发仓版本控制元数据。

## 考虑过的方案

**用同包名调试 APK 覆盖正式版。** 签名不一致时无法安装，而且配对与草稿不能隔离。

**单独增加 preview 构建类型，同时保留可安装的 debug 渠道。** 两个非正式渠道职责重复；保留 Gradle 的 debug 类型及任务，统一用户看到的安装包身份即可。

## 影响

实验性功能与构建渠道分别标记：正式版也可提供明确声明边界的实验性能力，不能因此把整包当成 Preview。正式版和 Preview 可以并存，Preview 首次使用需要独立配对。Gradle 任务及内部输出目录仍叫 debug，分发文件按 Preview 命名。最终签名与 R8 的行为仍须用 Release 验证，不能由 Preview 的结果代替。

## 核对入口

- `androidApp/build.gradle.kts` 的 `buildTypes.debug`。
- `androidApp/src/debug/AndroidManifest.xml` 和 `src/debug/res/values/strings.xml`。
- `scripts/deploy.sh`、`FineCatCompatF12WindowDeviceTest.saveShot`。
