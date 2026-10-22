# Agent Note: 原生模型配置的刷新边界

Status: implemented

## 问题

手机保存的是宿主模型配置文件，已经启动的原生 CLI 可能仍持有旧 Model。文件保存成功不能证明当前运行器已应用；直接重建 SDK 句柄则会破坏桥接所有权。scope 和扩展状态也不能从全局配置或别的运行器猜测。

## 决定

Provider 保存后，仅为当前选中且在线的原生实例请求显式 refresh-model。原生使用公开 ModelRegistry.refresh，再按同 provider/id 重新 setModel，读取实际思考；复用既有模型写队列和去重，不执行模型请求。要求空闲无队列/压缩/导航；刷新前后 context、Model 对象或思考发生变化就拒绝覆盖。模型写仍在飞时快照 controlPending 也为真，网关 IPC 超时不能把它当空闲放行模式切换。

保存终态与运行时刷新分开：文件已落盘就保留保存成功；忙、断线、旧扩展、未选中实例和刷新未知均不假称当前运行器已应用，也不自动切回 SDK。客户端按操作、会话、实例和代际关联，失败或超时收掉 lane 并给短提示。配置/认证原文不进回执，目录只投影已有白名单。

scope-model 保存到全局设置，仅供下一次原生启动解析。Extension API 的 scopedModels 是只读快照，保留 CLI --models；SDK AgentSession 虽有 setter，扩展并不持有该实例，不能导入私有路径或截获内部对象。ModelRegistry.refresh 只重读目录，原生 reload 也不重算当前 scope，均不能冒充 scope 已生效。

原生 setStatus 只有公开 setter，没有独立 getter 或状态事件。ReadonlyFooterDataProvider 虽能读，但获取它需安装并替换 footer；桥接不为读取状态抢终端 footer，也不把 SDK uiContext 的状态桥当成原生支持。该接口边界公开保留。模式切换清除SDK状态快照，桥接期间忽略旧SDK状态帧；原生首快照也清除同SID残留，不能把旧状态当原生状态。

## 考虑过的方案

每次打开模型目录都隐式重新设置模型，会反复追加会话配置并让只读查询产生副作用。把目录刷新作为同名配置 setter 的布尔参数，旧扩展可能忽略后仍报成功；显式新操作和能力门控能诚实拒绝。重启 CLI 或更改全局 enabledModels 来伪造当前范围，会丢失原生启动覆盖与交互状态。

## 影响

协议由网关 PROTOCOL §4.39 维护；[桥接基线](2026-10-20-mobile-bridge-mode.md)与[支持边界](../../../../Docs/pi-compatibility.md)保持区分。合成 Registry、真实 Pi 公开 API、共享层关联与真实设备/Provider 是不同验证层，不能相互代签。
