# Agent Note: 原生会话输入辅助

Status: implemented

## 问题

原生 CLI 的资源来自它的启动参数、项目与运行中扩展。网关另开 SDK 句柄或重新扫描 loader 得到的 Skill/Prompt 清单可能不同，也会破坏桥接单写者边界。只显示候选而不启用原生展开，会把命令原文误当普通消息发送。

## 决定

- 通过公开 getCommands 与 getSettings 读取当前原生清单和合并后的 enableSkillCommands。此设置只控制候选显示；显式 skill 调用不受显示开关阻止。命令优先级与 Pi 一致：扩展遮蔽同名资源，skill 遮蔽同名模板。手机只投影 Skill/Prompt 的名称、描述、来源与公开 scope，省略公开 API 没有的可选信息，不传路径与正文。
- 发送时仅为当前已加载且未被扩展遮蔽的 Skill/Prompt 开启 expandPromptTemplates，由 Pi 处理参数与内容。第三方扩展命令拒绝，未知文本保持普通输入。submitted 仍不证明输入处理器未消费或模型已经执行。
- 原生扩展/Skill 命令按半角空格拆分，而 Prompt 模板支持 Unicode 空白。必须分别核对，不能统一分词后误路由命令。公开资源发现可晚于 reload 首快照，因此打开斜杠候选时再查当前清单。
- 查询与成功/错误绑定 query/session/instance/epoch。Runtime 拒绝错代际结果，模式帧先写门面模式再刷新，避免两个收集器的顺序让 SDK 刷新被误拦；切换上下文时记录已有实例身份，避免下一快照误当换代。离线或换代释放对应 lane，门面清候选并在新快照后重新查询。不能复用写操作的 operationId 包装器来覆盖 queryId。
- @ 使用在线实例 cwd 和 Pi TUI 公开补全器，复用现有取消、超时和插入片段规则；资源查询取消也关闭独立 IPC socket，不能只丢弃结果却一直占连接，不读文件正文，也不把 cwd 误称为沙箱。

## 考虑过的方案

重新扫描资源可做首页预览，但不能冒充原生实例已经加载的资源；只用 getCommands 会漏掉显示开关。桥接基线的统一拒绝在这些公开接口足够时仅局部取代，见[桥接基线](2026-10-20-mobile-bridge-mode.md)。

## 影响

契约由网关 PROTOCOL §4.37 维护。网关回归、共享层身份关联测试与真实 Pi CLI 合成展开验证分别覆盖不同边界，不能代替手机输入候选与真实 Provider 验收。没有配套能力时提示更新，不回落 SDK。
