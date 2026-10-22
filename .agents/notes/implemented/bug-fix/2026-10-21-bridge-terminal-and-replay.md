# Agent Note: 桥接终态身份与快照重放

Status: implemented

## 问题

桥接中同一消息可能同时出现两份完整回复和孤立流式片段。不能靠正文去重：同文可以是两条独立消息。

## 决定

- Pi 公开扩展 message_end 在 appendMessage 之前发生，回调内同步查 SessionManager 必然漏掉当前条目。终态与后续事件按有界 FIFO 暂存，到后续公开边界或快照读取时按原 message 引用补真实 entryId。不能等待回调内的持久化 Promise，否则会阻塞 Pi 自己的 append；SDK public subscribe 的微任务经验也不能直接搬到扩展回调。
- 终态实际发出前保留对应 partial，避免新订阅落在终态等待身份的窗口时丢正文；按本地 assistant 流代次清 partial，不能误清后续新消息；update 的 partial 与终态 message 并不保证引用相同，不能用该引用判断是否清流。无法关联时在 settled、生命周期收尾或队列上限保守释放，不猜身份、不无限积压。
- 桥接 wire seq 每次订阅归零，normalizer 的 turn 也随快照重置。本地事件序号独立单调，每份快照分配新的流键范围，避免覆盖旧记录或撞上已完成流的键。instance/epoch/bridgeSeq 的原接收门槛不放宽。
- 快照替代活动流时，ConversationStore 可见流与 HistoryStore 原始 chunk 必须一起清理；只清前者，settled 后补拉历史会重放旧 chunk。内部 discard-streams patch 只允许删除正文/思考 chunk，保留已消费水位与其余消息、工具和其他流，不放宽一般 replace 的验序。
- 换代前必须先取旧位置并清流，再清代际映射；顺序相反会遗漏旧实例尚未落盘的 chunk。清 chunk 后重建 ConversationStore 时，显式传入桥接已消费的本地序号下限，否则剩余历史尾号回退会让后续快照前缀被验序拒绝。普通 SDK 重建不传此下限，显式清会话和收到 SDK 帧也清桥接本地时钟。
- 后台漏收结束事件时，完整历史本身不足以让旧快照退场：最新历史明确空闲，且代际与本地缓存前缀对象仍属于该请求，才同步清旧前缀、后缀、工具叠层和原始流 chunk，再重建正文。请求标记只在真实历史请求 effect 发出时捕获；翻旧页、仍在运行、替换后的快照或新实例不借旧空闲结果清流。这里比较同一个缓存槽的不可变 JSON 对象引用，不在原生 Pi 的 update/terminal 消息间猜引用；也不按文本匹配。不能仅用 bridgeSeq 不变作条件，同轮正常 delta 也会推进它；错误、取消、超时及重置清标记。不能在发起补历史时直接清全部前缀，否则仍在生成的回复会丢开头。

## 考虑过的方案

**按文本或时间戳对消。** 会吞掉合法同文消息，也无法证明是同一个 Pi 条目；保留条目 ID 与原消息引用。

**快照固定使用 0/0，或直接复用重订阅后的 wire seq。** 会撞上旧流终态键或旧正序列；单测只看当前最后一段会漏掉旧片段在历史重建后复活。

## 影响

只改变桥接投影与终态身份补全，不改变 SDK 模式、提交回执语义或 Pi 源码。无法关联的旧帧仍保守保留，不承诺按正文消重。

## 核对入口

- bridge-extension 的真实 Pi 合成 E2E terminal-entry-identity，对比实时终态和原生历史的真实 entryId。
- AndroidGatewayRuntimeIntegrationTest.bridgeResubscribeCompletionAndHistoryDoNotDuplicateRepliesOrReviveChunks：生产 Runtime 到投影，订阅 seq 归零、快照、完成、补拉历史、合法同文多条及新代际。
- SharedHistoryStoreTest.bridgeStreamDiscardKeepsMessagesToolsAndOtherStreams：只清目标文本/思考 chunk，不删除正式消息或其他流。
- [桥接模式设计](../architecture/2026-10-20-mobile-bridge-mode.md)。
