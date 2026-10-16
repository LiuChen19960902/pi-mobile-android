# Agent Note: 凭证盲客户端：需要上游凭据的操作都经网关

Status: implemented

## 问题

Provider 的 API Key 保存在电脑上 Pi 的 `models.json`。手机端编辑一个已保存的 Provider 时，表单里的 Key 是空的。早期“拉取模型清单”由客户端直连上游，用的是表单里的 Key，于是请求带着空的 `Authorization: Bearer ` 返回 401；而“测试连接”本来就走网关，所以出现了“测试能过、拉清单 401”这种很误导的症状。要求用户为拉一次清单重新填 Key 也不合理，获取 Key 的成本很高。

## 决定

- 手机不保存 API Key。凡是需要用 Key 访问上游的功能（连接测试、拉取模型清单）都通过网关请求完成。
- 网关按“表单值优先，缺省时回退 `models.json` 已存值”合并字段，两种请求共用同一个合并函数。Key 只在宿主求值，不回传手机。
- 客户端直连上游的实现已删除。

## 考虑过的方案

**客户端直连上游，编辑时要求重新输入 Key。** 用户成本高，而且手机会持有和传输 Key，扩大泄露面。

**网关把已存 Key 下发给手机。** 与凭证盲的安全目标相反。

## 影响

- 新增任何需要上游凭据的功能都必须设计成网关请求，并在 `PROTOCOL.md` 中定义帧。
- 连接测试比较的“连接指纹”只在内存中使用，不落盘、不回显凭据。

## 核对入口

- 网关 `src/router.ts` 的 `resolveProviderFormFields`，`provider-test` 与 `provider-models` 两个请求。
- 相关 Provider 规则见 [Provider 配置的合并与测试语义](../feature/2026-09-17-provider-settings.md)。
