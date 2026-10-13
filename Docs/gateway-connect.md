# 手机 ↔ 网关连接手册

面向新用户的连接说明：启动网关 → 配对手机 → 按场景选入口。

> 地址口径（全文不出现真实 IP）：`<LAN_IP>` = 宿主机局域网地址，`<TS_HOST>` = 宿主机
> Tailscale（或同类 mesh VPN）地址。怎么取：宿主侧 `ip -4 addr show`（或 Windows
> `ipconfig`）取局域网地址，`tailscale ip -4` 取 tailnet 地址。回环地址（`127.0.0.1`）
> 与端口不是身份信息，按字面出现。

## 端口与地址口径

| 名称 | 默认值 | 说明 |
| --- | --- | --- |
| 数据面 | `3082`（`--data-port` 可改） | App 实际连接的 WebSocket 端口 |
| 管理面 | `3083`（`--mgmt-port` 可改） | 电脑本机的 HTTP 管理 API |
| App 内默认 endpoint | `ws://127.0.0.1:3080/ws/mobile` | **仅供 `scripts/deploy.sh` 的 `adb reverse` 开发回环**，不是网关默认端口 |

本手册的部署示例统一用默认数据面端口 3082。改过端口的，把示例里的端口同步替换即可；
`--public-url` 参数也要与实际暴露给手机的地址一致（`npm start -- --public-url "ws://<LAN_IP>:3082/ws/mobile"`）。

## 前提：启动网关

网关与 pi 跑在同一台电脑上，安装与启动见网关仓库 `README.md`。要点：

- 需要**设备鉴权**：每次启动建议带上 `--pair "我的手机"`，终端会打印配对二维码与配对文本；
- 配对码是一次性的，30 分钟过期，只存在于网关进程内存——网关重启后旧配对码作废（已配对设备不受影响）；
- **不重启网关铸新配对码**（推荐）：用管理面 API 在本机签发一个新码，还可指定 App 配完落在哪个地址：

```bash
curl -s -X POST http://127.0.0.1:3083/mgw/pair \
  -H 'Content-Type: application/json' -H 'Host: 127.0.0.1' \
  -d '{"name":"我的手机","publicUrl":"ws://<LAN_IP>:3082/ws/mobile"}' | jq -r .pairingText
```

- 以 systemd 用户服务等常驻方式部署时，配对码打印在该服务日志里（unit 名按你的实际配置替换）：

```bash
journalctl --user -u pi-mobile-gateway.service -o cat --no-pager | tail -40
```

## 在手机上配对（一次性）

首次使用、从未配对过的设备或要换新设备身份时才需要配对。**同一网关的其他入口地址
（局域网 ↔ Tailscale）不需要再配**：token 是设备级的，跨地址有效。

1. 网关侧取得配对文本或二维码（见上）；
2. 手机 App 首页左上角打开会话抽屉 → 顶部**设置图标** → 设置页**网关卡** → **手动配对**（或「扫码配对」，效果相同）；
3. 粘贴 Base64URL 配对字符串 → 点**确认** → 显示配对成功 → 设置页应显示已连接。

注意：

- 配对码用过一次或过期即作废，重新取一个即可；粘了旧码 App 会报 `pairing-code-invalid`；
- 配对成功后 App 保存长期 token，此后用 `Authorization: Bearer` 重连，不再需要配对码；
- 清除 App 数据、卸载重装或换机后**本地 token 丢了**，但这**不等于**服务端吊销：该设备
  记录在网关侧仍然有效。想彻底停用一台设备要用管理面吊销（见下）。

## 入口选择：在家走局域网，外出回落 Tailscale

App 设置页可加入多个备用地址并排序。私网输入框只需 `IP:端口`，列表显示同一简写；完整地址用于配对载荷或公网 `wss://` 入口。推荐入口顺序：

1. 第 1 位：`ws://<LAN_IP>:3082/ws/mobile`（在家时最快、不依赖手机上的 Tailscale）
2. 备用：`ws://<TS_HOST>:3082/ws/mobile`（外出时自动回落）

App 按列表顺序自动选路：非末位候选约 2.5 秒握手预算（不可达地址快速跳过），末位候选
约 10 秒（容忍 mesh VPN 冷启动较慢）。连上哪个都不会重排列表，网络切换或回前台后重新
从头尝试。

想知道当前走的是哪条路：

```bash
# 在宿主上看本地地址列：<LAN_IP> = 裸局域网，<TS_HOST> = Tailscale
ss -tnp | grep 3082
```

## 吊销设备

```bash
curl -s -X POST http://127.0.0.1:3083/mgw/devices/<设备id>/revoke -H 'Host: 127.0.0.1'
```

会删除该设备记录并以 close `4003` 踢掉其在线连接。设备列表（含设备 id 与在线状态）：
`curl -s http://127.0.0.1:3083/mgw/devices -H 'Host: 127.0.0.1'`。注意吊销不追溯该设备
此前触发的操作；同一安装重新配对是**轮换** token，不算新增设备。

## 排错速查

| 现象 | 处置 |
| --- | --- |
| App「连接失败」，logcat 出现 `httpStatus=401` | 看 App 诊断里的 `lastError`：`pairing-code-invalid` = 粘了用过/过期的配对码（重新铸码）；`authentication-required` = 本地 token 失效或丢失（换机/清了 App 数据/被吊销），需要重新配对 |
| 网关重启后 App 连不上 | 长期 token 不受网关重启影响，force-stop 后重开 App 即可自动重连；持续失败再按上一行排查 401 |
| App 一直连某个入口不换 | 检查设置里入口列表顺序：第 1 位放局域网地址，否则出门在外会先等到末位候选 |
| Tailscale 入口不通 | 手机上打开 Tailscale App 确认在线（宿主侧 `tailscale status` 看该设备是否 `offline`） |
| 局域网入口突然不通 | 多为宿主防火墙放行问题：确认数据面端口 TCP 已放行入站；换路由器/AP 隔离后也可能变化 |
| 网关侧无新日志但端口通 | 确认网关在监听：`ss -tlnp | grep 3082` 应显示 `0.0.0.0:3082`（或你自定义的绑定） |
| App 状态彻底乱，想重来 | `adb shell pm clear com.finecat.pimobile.android` 后重新配对（这会清除全部手机本地数据，包括地址、偏好和 token；服务端设备记录还在，重配会轮换 token；不想留旧设备记录就先吊销） |

## 验证手法（可选）

```bash
ADB="$ANDROID_HOME/platform-tools/adb"; T=<手机serial>
# TCP 层连通性：应回 HTTP/1.1 400 Bad Request（nc 发的不是合法 WS 升级，400 = 已到达网关）
$ADB -s $T shell "echo test | nc -w 3 <LAN_IP> 3082"
# App 侧真实错误：点连接后抓
$ADB -s $T logcat -d | grep -E 'PiGateway: (transport|runtime)' | tail -14
# 当前走的入口：见上文「入口选择」一节的 ss 命令
```
