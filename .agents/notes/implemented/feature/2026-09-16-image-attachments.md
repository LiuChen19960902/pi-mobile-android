# Agent Note: 图片发送、确认与回显

Status: implemented

## 问题

图片消息要经过选图、压缩、上传、模型读取、实时回显和历史恢复几段路径。历史上出现过“单图看不到、多图正常、模型却能读图”（零尺寸布局）、发送后到正式回显之间没有反馈、迟到回执把下一条消息的预览确认掉、大图在历史中被剥离后无法补回等问题。纯图消息还曾被 OpenAI 兼容上游以 400 拒绝。

## 决定

### 尺寸与布局

- Pi 的图片可能没有尺寸，归一后 width / height 为 0。不能把未知尺寸钳成 1dp：先用消息或已解码缩略图的宽高比，都未知时用可见的方形占位。
- 图片按比例算对了也可能撑宽用户气泡：附件层若用 `BoxWithConstraints(Modifier.fillMaxWidth())`，黑色气泡会被撑到宽度上限。图片上限从最外层约束计算（气泡最大宽度减两侧内距），附件 Row 按内容收缩。至少 48dp 的触控区不等于可见图宽。比例函数的 JVM 测试抓不到父布局回归，要看真实气泡 bounds。

### 发送确认

- 空闲带图发送时先在 Main 同步建立 UI 预览，再写缓存和发送；每次实际发送换一组 `attachmentId`，避免重发同一张图时认错消息。预览不进入共享历史。所有实际发送（包括纯文本、忙时派发）都登记请求 token，避免无预览请求的回执占用图片预览。
- Pi SDK 1.0.4 的 `_normalizePromptImages` 把输入重建为 `{type, data, mimeType}`，丢弃手机附加的 `attachmentId`。旧 SDK 原样回显的行为不再是契约。有效且匹配请求的 `sent.disposition`（started / queued / handled）已表明 SDK 受理，客户端按发送 token 结束本次预览等待，不再要求附件 ID 回显。旧网关没有 disposition 时才保留原来的回显等待。
- `sent` 帧没有请求级关联，会话是唯一可用的关联。超时或断连把队头 token 弹出后，它的迟到 `sent` 不能消费下一个请求：队头目标会话与帧会话都已知且不相等时丢弃该帧；目标会话未知（新会话首条）时仍按 FIFO 消费，与 Runtime 的 sent lane 一致。生产路径上 Runtime 已用 `sessionMatches` 拒绝跨会话的迟到 `sent`，这一层是纵深防御。同一连接、同一会话的重复合法 ACK 在旧协议下无法彻底区分。
- 失败或未确认的预览有界保留：当前可见上下文最近 2 条、全局共 6 条，发送中和已确认的不参与淘汰；已回填进草稿的载荷另有引用，淘汰预览不清用户正在编辑的稿。

### 回显、缓存与补图

- 正式消息缺附件 ID 时，按 Pi `entryId` 与图片序号生成稳定的缓存身份 `hist-<entryId>-<imageIndex>`，live 与 history 共用；不按图片内容或 AI 回复猜测是哪次发送。
- 预览要同时响应实时事件和历史恢复：history 的 normalizedEvents 为空，需从 `frame.messages` 中的 user content 提取图片。在正式快照的 Main 发布回调中按会话和整组 ID 收走预览，覆盖断线漏回显后由 history 恢复的场景。
- 选图成功和发送前都写本地附件缓存；新会话还没有 sessionId 时先按全局 key 写入，读取时先查会话级 key 再查全局 key。
- 网关实时事件预算 1 MiB、history 单消息 512 KiB，超限时用 `dataOmitted` / `dataBytes` 元数据替代 base64。宣告 `attachment` 能力的网关可按 `(sessionId, entryId, imageIndex)` 取回 user 图片（`imageIndex` 只数 image 块），原图上限 4 MiB，不放宽历史预算或 8 MiB 出站背压。取图先查 live SessionManager，再读当前文件；只读条目不创建 AgentSession；路径和 attachmentId 都不能作为服务端寻址依据。取回后校验类型、编码长度和实际字节数再写缓存，大 base64 应答不进入 UI 事件队列。
- 取图 lane 的 busy 判定早于替换等待者；取消只清理自己拥有的请求；取图超时只结束自己的等待，不回收其他消息所在的连接。
- 未裁剪的内嵌 base64 在 IO 协程解码，避免主线程掉帧。

### 纯图消息

Pi 的 `prompt()` 无条件构造 `[{type:"text", text}]`，空字符串也会生成空 text 块，被 OpenAI 兼容上游拒绝（`[{text:""},{image_url}]` 返回 400，去掉空块或改成 `" "` 返回 200）。网关在“文本全空白且带图片”时传 `" "`。客户端渲染正文用 `isBlank` 判断，避免历史中出现空气泡；会话列表的 `firstMessage` 对空白值回退为 `(no messages)`。桌面 Pi 不受影响，因为 TUI 粘贴图片插入的是临时文件路径，走 `read` 工具。若 Pi 以后自行去掉空块，可移除此注入。

## 考虑过的方案

**网关维护 pendingImageIds 队列，向 user `message_end` 注入 ID。** 一次遗漏导入导致事件处理抛错，被事件泵吞掉后整条 user 事件消失，还被误判为 Pi 不发事件；确认 ID 可随消息落盘后移除了注入。SDK 1.0.4 之后 ID 也不再保留。

**为新会话首条的迟到 `sent` 增加“已收敛 token 吸收器”。** 帧内没有字段能区分两个新会话请求，同会话重试时吸收器反而会吞掉有效确认。

**放宽 history 的图片预算。** 单帧过大会触发背压断开；按需取图更稳。

## 影响

- 换设备或清缓存后，旧图片依赖网关的取图能力；网关缺能力、缺条目身份或原图超限时无法补齐，需要用户手动重试。
- 尺寸、发送回显时序和跨设备补图要分别验证，一项的真机确认不能代替另外两项。

## 核对入口

- `OutgoingImagePreviewTracker`（`consumeSent`、`pruneRestorable`）、`AndroidSharedStateHolder` 的 `prepareImage` / `appGraphSend`、`GatewayRuntime.readCachedAttachment` / `fetchAttachment`、`PiEventNormalizer.imageAttachmentsOf`。
- 网关 `src/router.ts`、`src/wire.ts` 的字段校验与预算，`src/pi-runtime.ts` 的 `prompt` 占位注入。
- 测试：`UserAttachmentPreviewSizeTest`（含零尺寸）、`lateSentFromAnotherSessionDoesNotConsumeNextRequest`、`sameSessionRetryAfterTimeoutIsConfirmedByNextSent`、`GatewayConformanceTest` 的剥图后取回用例、网关 `tests/pi-runtime.test.ts` 的注入用例。
