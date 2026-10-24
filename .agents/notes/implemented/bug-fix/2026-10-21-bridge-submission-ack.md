# Agent Note: 桥接提交回执结束发送等待

Status: implemented

## 问题

桥接真机验收中，手机发送的合成文字已经在同一个原生 Pi 中得到完整回复，但仍显示“消息提交未确认”。客户端收到 submitted 后把完整原稿另存到桥接收据，等待 operation-status=settled；普通 send 调用公开的 void sendUserMessage，没有逐操作 settled，因此正常消息也无法收尾，原稿与附件还会持续占用内存。

## 决定

- 匹配的 submitted 结束本次提交等待，释放对应原稿和附件。它只证明桥接实例调用了提交入口，不证明已入模型、排队成功或运行完成；Working、失败和完成仍以 Pi 事件及快照为准。
- Runtime 的 operationId/sessionId/instanceId/epoch 匹配不变，Holder 还要求回执属于等待队头。复用既有提交清理与草稿版本保护，不清后来编辑的内容或其他会话草稿。
- 没收到回执、明确拒绝或断线歧义继续走原有有界恢复路径，不自动重发。旧网关未提供 disposition 的附件回显兼容逻辑保持。
- 移除已提交发送的专属恢复条、永久收据与自动对账队列；operation-status 的协议和 Runtime 查询 API 保留，不再用它等待普通 send 的不存在终态。

## 考虑过的方案

**等待原生逐条受理。** Pi 公开 UserMessage 和消息事件没有 operationId，sendUserMessage 也不返回受理结果；当前接口无法建立这条确认链。不能把 agent_end 伪装成某个操作的 settled。

**按正文、时间或下一条事件确认。** 输入处理器可以改写或处理消息，同文消息和并发输入也会误认；因此不新增匹配猜测或隐藏标识，不修改 Pi 私有接口。

## 影响

该修复改变发送等待的结束条件，不改变实际执行与所有权语义。用户已提交后仍可能遇到模型或扩展错误，由真实事件显示。真机复验需确认完整回复后不再挂未确认提示，不能以 JVM 或独审通过替代真机确认。

## 核对入口

- AndroidSharedStateHolder.recordBridgeSubmitted / recordOutgoingSent，OutgoingImagePreviewTracker.consumeSent。
- AndroidSharedStateHolderTest 的 bridgeSubmitted* 和 bridgeSendWithMissingAcknowledgementKeepsExplicitRecovery；Runtime 既有四元回执关联回归。
- 网关 PROTOCOL 的桥接 message / operation-status；[桥接模式决定](../architecture/2026-10-20-mobile-bridge-mode.md)。

## 发送后的补拉

Runtime 在序列锁内向有界事件队列发布帧。唯一消费者处理 `sent` 时若等待 `requestSessions`、`subscribe` 或 `requestState`，生产者可能已持锁等待队列空位，双方都无法继续；原生模型即使完成，手机也收不到后面的用户回显与收尾。

`handleSentSession` 使用非挂起入口，在独立 `gatewayScope` 任务里补拉。当前选择检查与订阅入锁仍在 Main 发起，保留迟到回执的导航保护及连接/模式订阅凭据。不能用放大队列、丢帧或计时清 Working 代替解除等待环。

真实 Runtime 的突发帧对照复现同步消费者停滞、独立任务模式排空并空闲；结构测试约束该生产入口不接受 Continuation。两者分别验证等待机制与接线边界，不替代 FineCat 根入口的真机复验。

桥接 `sent` 后不重建网关已经建立的事件订阅：重建时的新快照可能越过旧 socket 尚未转发的 user 或错误终态，而快照不含这些历史条目。Runtime 只登记本次会话作为断线重连目标；显式选会话、模式切换和重连仍发订阅。SDK 模式继续原来的订阅路径。该提示由 Runtime 在已经过操作/会话/实例/代际校验的桥接提交回执上携带连接与模式代际；排队消费时连接或模式已变化则补发订阅，不能据此把一般发送成功当作订阅成功。
