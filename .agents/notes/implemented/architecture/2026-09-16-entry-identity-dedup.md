# Agent Note: 消息去重与列表身份使用 Pi 条目 ID

Status: implemented

## 问题

同一条消息会从两条路径到达手机：实时事件流和历史分页。客户端必须把两份合成一条，又不能误删用户后来发送的同文消息。早期按 `seq` 或正文推断身份：`seq` 是网关句柄内的传输序号，重启会重置；历史 `seq` 由客户端合成，每次整窗重拉都重新分配，导致列表 key 变化、滚动位置和折叠状态整片丢失。

## 决定

- 身份使用 Pi SessionManager 的条目 ID `entryId`。历史帧的平行 `entryIds` 与实时 `message_end` 帧顶层的 `entryId` 同源。`SharedHistoryStore` 按 `(sessionId, entryId, type, callId)` 合并；缺 ID 时保留消息，不按正文去重。旧网关在重叠区可能出现重复，这是可接受的代价。
- 列表项 ID 由 `sessionEventIdentity`（entryId + type + callId）派生；缺 entryId 时（旧网关、assistant chunk、tool call）退回 `"$sessionId-$seq"`。`seq` 的分配本身不变，补丁的严格递增校验不受影响。
- 网关取条目 ID 的时序：Pi SDK 的订阅回调先于同步 `appendMessage`，在回调里当场查条目会拿不到 ID。网关对全部事件统一用微任务 FIFO 发布，以原 message 对象引用匹配公开 `getEntries()`，下发内容和时间先拍快照。不能只延后 `message_end` 让其他事件超车；查不到 ID 时省略 ID，不丢事件。
- 实时记录若与缓冲中同身份的合成记录冲突，走 `replaceSession` 整窗重建而不是增量合并，否则同 ID 重复会让整批替换被拒绝。
- 图片发送预览用的 `attachmentId` 与 `entryId` 用途不同，不能互相替代。

## 考虑过的方案

**在 `normalizeHistoryMessages` 中让新页按 entryId 继承旧 seq。** 历史使用的负 seq 空间是稠密的，没有空档；继承后新插入的条目要么违反顺序约束，要么撞进实时记录的正 seq 空间。

**按正文和时间去重。** 会吞掉用户先后发送的同文消息。

## 影响

- 整窗重拉后列表 key 稳定，滚动和折叠状态保留；实时正式记录与重建后的同身份记录不再瞬时双份显示。
- 稳定条目 ID 不能解决所有 seq 生命周期窗口：重启和重连时清缓冲、重新拉历史的逻辑仍然需要。

## 核对入口

- `SharedHistoryStore`、`ConversationProjection.eventId`、`sessionEventIdentity`、`SharedConversationStore.replaceSession`。
- 测试：`SharedHistoryStoreTest` 的同文新旧消息、双向重叠与重复页用例；`SharedConversationStoreTest.entryIdentityKeepsItemIdsAcrossBaselineReplacement`；`GatewayConformanceTest` 中两次同文发送与 history 对照。
- 网关 `src/pi-runtime.ts` 的事件发布队列。
