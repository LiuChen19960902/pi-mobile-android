# Agent Note: 明文 ws:// 只允许回环、私网、tailnet 与单标签主机

Status: implemented

## 问题

配对令牌和会话内容通过 WebSocket 传输。用户可能手动填写网关地址；如果允许 `ws://` 连到公网地址，令牌和对话会以明文经过公网。

## 决定

- `ws://` 只放行回环地址、私网地址、tailnet 地址和单标签主机名，公网地址必须使用 `wss://`。
- 连接、配对、修改地址三处统一调用 `requireWebSocketEndpoint`；判定逻辑在 `isPrivateWebSocketHost`。
- 私网判定依赖网关 `net-guards` 的逐段校验，修改任一处都要连带核对另一端和对应单测。

## 考虑过的方案

原始记录未保留其他方案。这是产品级安全决定，放宽白名单需要单独评审。

## 影响

- 在局域网和 Tailscale 内可以直接用 `ws://`，跨公网必须配置 TLS。
- 修改白名单属于安全面变更，需要同步更新 [SECURITY.md](../../../../SECURITY.md) 和 [Docs/gateway-connect.md](../../../../Docs/gateway-connect.md)。

## 核对入口

- 客户端 `GatewayProtocol.kt` 的 `requireWebSocketEndpoint`、`isPrivateWebSocketHost` 及守卫单测。
- 网关 `src/net-guards.ts`。
