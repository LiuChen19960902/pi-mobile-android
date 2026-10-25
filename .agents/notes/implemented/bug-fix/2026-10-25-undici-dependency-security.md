# Agent Note: 通过根依赖覆盖修复 SDK 固定的 HTTP 库漏洞

Status: implemented

## 问题

锁定的 Pi SDK 精确依赖旧版 undici。只刷新锁文件仍会安装受影响版本；仅升级测试目录的依赖，也不能保护生产安装。

## 决定

- 网关显式依赖安全补丁版 undici，根 `overrides` 用 `$undici` 将整个依赖树统一到同一版本，SDK 版本保持。避免 SDK 与可选 peer 留下旧副本。
- 正式入口和跨仓夹具在加载 SDK 前，通过公开 `install()` 接入补丁版全局 HTTP 实现。Node 内置 fetch 的版本独立于 npm 包；仅更新依赖可能仍让实际请求走旧实现。初始化与依赖回归分别验证。
- 锁文件与根配置一起维护；干净的普通安装和 `npm ci --omit=dev` 都要检查实际解析版本及漏洞行为。只看版本声明或测试目录里的新包不作为部署成功。
- 回归调用 undici 公开 API：特制压缩响应在子进程验证不崩溃，EventSource 用小载荷验证状态上限，SRI 同时验证错误摘要拒绝与正确摘要接受。旧版应实际失败，避免耗尽宿主内存来证明缺陷。
- SDK 升级后重新核对其依赖；确认上游已固定安全版本且干净安装无旧副本时，删除临时覆盖，避免长期压住上游后续更新。

## 考虑过的方案

直接升级整个 SDK 会扩大运行语义和兼容性变化。围绕 fetch 添加 try/catch 也不能修复库内部孤立流的未处理错误，因此在依赖层修复，不修改 SDK 源码或复制它的私有实现。

## 影响

覆盖作用于网关根目录的 npm 安装，不改变原生 Pi CLI 的打包代码；无论 CLI 由终端还是后台启动，都不能据此宣称它已修复。SDK 会话工厂与 CLI 主入口对 HTTP 全局实现的初始化不同；库的行为回归不能证明所有漏洞在每条网关调用路径都可达。

依据：[异常响应导致进程退出](https://github.com/nodejs/undici/security/advisories/GHSA-xjj6-f33q-929c)、[EventSource 状态增长](https://github.com/nodejs/undici/security/advisories/GHSA-4qv8-2p9j-2685)、[SRI 算法名大小写](https://github.com/nodejs/undici/security/advisories/GHSA-f72v-6w45-q472)。
