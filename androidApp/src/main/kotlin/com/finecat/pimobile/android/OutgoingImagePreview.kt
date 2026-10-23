package com.finecat.pimobile.android

import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment

/**
 * 带图发送的本地预览状态：点击空闲发送图片后、正式回显（user
 * message_end）到达前，对话尾部先显示一张本地占位气泡，标明发送状态。
 *
 * 边界（与 Pi 原生 TUI 的 message_start(user) 展示对齐，但实现为客户端本地态）：
 * - 只存在于 UI 层：不写共享 history / 持久化会话，不参与投影。
 * - 归属用"整组实发 attachmentId + 会话"精确匹配，禁止用文本/内容猜身份；
 *   attachmentId 每次实发重新分配，同图重复发送各自独立。
 * - sent 的 started/queued/handled 表示 Pi SDK 明确受理，结束本次本地预览；
 *   正式消息照常投影，不要求 SDK 图片归一保留客户端的 attachmentId。
 *   旧网关未带 disposition 时保留原回显匹配路径。
 * - 请求身份独立于预览维护（[AwaitingOutgoingSend] 队列）：echo 先到移除
 *   预览后，迟到的旧 sent 帧仍按队头 token 正确消费，不会串到后续请求。
 * - 失败（本地拒绝/超时/断连/服务端 error）收敛为 FAILED，不自动重发；
 *   旧网关无 disposition 且 sent 后长期无回显时转为 UNCONFIRMED；
 *   可恢复态不覆盖发送后新编辑的草稿，已知 SDK 受理后不再降级。
 * - 网络失败不证明服务端未收到：迟到的正式回显允许移除 FAILED 预览，
 *   避免失败占位与正式消息双气泡。
 * - 终态（FAILED/UNCONFIRMED）预览有界保留：见 [pruneRestorable]——每条都持着
 *   整组图片 base64，不可恢复不到的预览不能无限期驻留。
 */
internal enum class OutgoingImagePreviewState { SENDING, CONFIRMED, UNCONFIRMED, FAILED }

internal data class OutgoingImagePreview(
    /** 一次实发的唯一令牌（预览内单调递增）；UI 动作按它寻址。 */
    val sendToken: String,
    /** 归属会话；null=尚未绑定（新会话实发后 sent/echo 帧未到达）。 */
    val sessionId: String?,
    /** 创建时的新会话代：未绑定期只在同一"新建对话页"显示，重开新会话不串。 */
    val newSessionEpoch: Long,
    /** 实发帧携带的整组 attachmentId（有序），与回显 image 块逐一同序比对。 */
    val attachmentIds: List<String>,
    /** UI 渲染数据：内嵌 base64/尺寸来自已准备图片，正式回显前直接解码显示。 */
    val images: List<GatewayImageAttachment>,
    /** 发送时草稿文本；失败/未确认恢复草稿用。 */
    val draftText: String,
    /** 恢复草稿用原图快照（MessageSubmission 原图片，非实发新 id 副本）。 */
    val restoreImages: List<AndroidPreparedImage>,
    /** 与 [restoreImages] 同序的来源 uri（托盘勾选态真源）；不伪造，只能从发送时刻捕获。 */
    val restoreUris: List<String> = emptyList(),
    val state: OutgoingImagePreviewState,
    /** CONFIRMED 起点（elapsedRealtime ms）；未确认收敛计时用。 */
    val sentAtMs: Long = 0L
) {
    /** UI 可见性：已绑定按会话过滤；未绑定只在同一新建对话页显示。 */
    fun isVisibleFor(selectedSessionId: String?, currentNewSessionEpoch: Long): Boolean =
        if (sessionId != null) {
            sessionId == selectedSessionId
        } else {
            selectedSessionId == null && newSessionEpoch == currentNewSessionEpoch
        }
}

/** 在途实发的请求身份：与预览列表独立，sent/error/timeout 帧按队头消费。 */
internal data class AwaitingOutgoingSend(val token: String, val sessionId: String?)

/**
 * 预览状态机（纯 Kotlin，可单测）：生产由 AndroidSharedStateHolder 驱动，
 * 测试注入发送边界后调用同一批真实方法。
 */
internal class OutgoingImagePreviewTracker {

    private val items = mutableListOf<OutgoingImagePreview>()
    val previews: List<OutgoingImagePreview> get() = items.toList()
    private var tokenSequence = 0L

    /** 在途实发队列（FIFO=实发顺序；runtime 单 message lane + sendInFlight
     *  保证同一时刻至多一个"等待 sent"请求，队头即当前请求身份）。 */
    private val awaiting = ArrayDeque<AwaitingOutgoingSend>()

    /** token → sent 帧绑定的真实 sessionId；预览被 echo 移除后仍可查询。 */
    private val boundSessionsByToken = mutableMapOf<String, String>()

    /** 请求身份：所有实发（含纯文本/吸附派发，无预览）都分配并进等待队列。 */
    fun newSendToken(): String = "send-${++tokenSequence}"

    /** 建立一条发送中预览（Main 同步调用，先于附件缓存/网络）。仅带图且普通发送。 */
    fun begin(
        token: String,
        sessionId: String?,
        newSessionEpoch: Long,
        draftText: String,
        images: List<GatewayImageAttachment>,
        restoreImages: List<AndroidPreparedImage>,
        restoreUris: List<String> = emptyList(),
        state: OutgoingImagePreviewState = OutgoingImagePreviewState.SENDING,
    ) {
        items += OutgoingImagePreview(
            sendToken = token,
            sessionId = sessionId,
            newSessionEpoch = newSessionEpoch,
            attachmentIds = images.map(GatewayImageAttachment::attachmentId),
            images = images,
            draftText = draftText,
            restoreImages = restoreImages,
            restoreUris = restoreUris,
            state = state
        )
    }

    /** 实发登记：token 进入"等待 sent"队列（sendMessage 调用前，Main 同步）。 */
    fun trackAwaiting(token: String, sessionId: String?) {
        awaiting.addLast(AwaitingOutgoingSend(token, sessionId))
    }

    /** 等待队头的请求 token（只读；无预览的纯文本发送也在队里）。 */
    fun headAwaitingToken(): String? = awaiting.firstOrNull()?.token

    /**
     * 本地发送结局（runtime.sendMessage 返回值，权威信号）：按自己的 token
     * 精确收敛——拒绝/失败（sent=false）置 FAILED（无预览仅出队）并移出
     * 等待队列。request-busy 拒绝只伤本次 token，不碰仍在途的队头请求。
     * 返回本次收敛的预览（纯文本/无预览返回 null）。
     */
    fun settleLocalFailure(token: String): OutgoingImagePreview? {
        awaiting.removeAll { it.token == token }
        boundSessionsByToken.remove(token)
        return failByToken(token)
    }

    /** 按 token 收敛 SENDING → FAILED（CONFIRMED 之后不误判，见状态注释）。 */
    private fun failByToken(token: String): OutgoingImagePreview? {
        val index = items.indexOfFirst {
            it.sendToken == token && it.state == OutgoingImagePreviewState.SENDING
        }
        if (index < 0) return null
        val failed = items[index].copy(state = OutgoingImagePreviewState.FAILED)
        items[index] = failed
        return failed
    }

    /**
     * sent 帧（preflight 受理）：消费队头等待 token（请求身份），将其预览置
     * CONFIRMED 并尽量绑定真实 sessionId。预览已先被 echo 移除时照样消费
     * 队头（否则旧 sent 会串绑后续请求），只是无预览可更新。
     *
     * 唯一可用的请求级关联是 sessionId（`GatewayRequests.message` 不带
     * correlationId，sent 帧也只有 kind/sessionId/mode）：队头请求的目标会话与
     * 帧会话都是已知会话且不相等时，这一帧不可能是队头的应答（只会是已被
     * 超时/断连收敛掉的上一请求的迟到 sent），直接丢弃：否则会把下一个请求的
     * 预览确认并绑到错误会话上（跨会话串绑）。
     * 目标会话未知（新会话首条）时仍按 FIFO 消费：这与 runtime 对 "sent" lane
     * 的 FIFO 完成一致，帧内没有任何字段能区分两个新会话请求。
     * 帧 sessionId 缺失或空白时不消费队头；runtime 已拦截，此处保留同样守卫。
     *
     * SDK已明确受理时完成预览；SDK1.0.4归一图片会移除attachmentId，不能再
     * 要求同ID回显来证明发送。旧网关未带disposition则保留原匹配等待。
     * 返回本次发生变化的预览，供调用方刷新列表；sdkAccepted时返回已移除项。
     * 无变化返回null，返回值不表示该项仍保留在列表中。
     */
    fun consumeSent(frameSessionId: String?, nowMs: Long, sdkAccepted: Boolean = false): OutgoingImagePreview? {
        val head = awaiting.firstOrNull() ?: return null
        val frameSession = frameSessionId?.takeIf(String::isNotBlank) ?: return null
        if (head.sessionId != null && head.sessionId != frameSession) {
            return null
        }
        awaiting.removeFirst()
        // 绑定记录保留供发送回写查询（区分自动选中与换会话）；等待队列仅
        // 有限增长（受理即消费），取超出上限丢最旧的有界清理。
        boundSessionsByToken[head.token] = frameSession
        if (boundSessionsByToken.size > BOUND_SESSION_HISTORY_LIMIT) {
            boundSessionsByToken.remove(boundSessionsByToken.keys.first())
        }
        val index = items.indexOfFirst {
            it.sendToken == head.token && it.state == OutgoingImagePreviewState.SENDING
        }
        if (index < 0) return null
        val preview = items[index]
        // 结束本次本地等待；只按已匹配的token清理，不认领别的消息或图片。
        if (sdkAccepted) return items.removeAt(index)
        val confirmed = preview.copy(
            state = OutgoingImagePreviewState.CONFIRMED,
            sentAtMs = nowMs,
            sessionId = preview.sessionId ?: frameSession
        )
        items[index] = confirmed
        return confirmed
    }

    /**
     * 异步失败帧收敛，按来源分两种语义：
     * - asUnconfirmed=true（请求超时/断连取消）：只意味着未确认——请求可能
     *   已达网关，网络失败不证明服务端未收到，预览转 UNCONFIRMED（手动
     *   恢复入口，不判死失败）；迟到 echo 不限状态收走。
     * - asUnconfirmed=false（服务端 error 帧，明确 preflight 拒绝）：预览转
     *   FAILED。本地同步拒绝（runtime 返回 false）另有按 token 的
     *   [settleLocalFailure] 权威收敛，不走这里。
     * runtime 单 message lane 保证队头即事件主体。返回被收敛的预览。
     *
     * 这里出队后该请求可能还会有迟到 sent：由 [consumeSent] 的会话守卫按
     * “会话不同则不是队头应答”丢弃，不需要为已收敛 token 再记额外状态。
     */
    fun consumeFailure(asUnconfirmed: Boolean): OutgoingImagePreview? {
        val head = awaiting.removeFirstOrNull() ?: return null
        boundSessionsByToken.remove(head.token)
        val index = items.indexOfFirst {
            it.sendToken == head.token && it.state == OutgoingImagePreviewState.SENDING
        }
        if (index < 0) return null
        val converged = items[index].copy(
            state = if (asUnconfirmed) {
                OutgoingImagePreviewState.UNCONFIRMED
            } else {
                OutgoingImagePreviewState.FAILED
            }
        )
        items[index] = converged
        return converged
    }

    /**
     * 正式回显：echo image 块的 attachmentId 与某条预览的整组 id 同序精确
     * 一致、会话兼容即命中并移除（不限状态——超时/断连标失败后回显仍可
     * 迟到，网络失败不证明服务端未收到；不限状态避免失败占位与正式消息
     * 双气泡）。未绑定预览借首个回显绑定会话。返回被移除的预览（未命中 null）。
     */
    fun noteEcho(frameSessionId: String?, attachmentIds: List<String>): OutgoingImagePreview? {
        if (attachmentIds.isEmpty()) return null
        val index = items.indexOfFirst {
            it.attachmentIds == attachmentIds &&
                sessionsCompatible(it.sessionId, frameSessionId)
        }
        if (index < 0) return null
        return items.removeAt(index)
    }

    /** 查询预览绑定到的真实 sessionId（未绑定/不存在返回 null）；供发送回写
     *  区分"sent 帧先到的自动选中"与"用户换会话"。 */
    fun boundSessionId(token: String): String? = boundSessionsByToken[token]

    /** CONFIRMED 超过 [unconfirmedAfterMs] 仍无回显 → UNCONFIRMED（有界收敛）。 */
    fun markStaleUnconfirmed(nowMs: Long, unconfirmedAfterMs: Long): Boolean {
        var changed = false
        for (index in items.indices) {
            val preview = items[index]
            if (
                preview.state == OutgoingImagePreviewState.CONFIRMED &&
                nowMs - preview.sentAtMs >= unconfirmedAfterMs
            ) {
                items[index] = preview.copy(state = OutgoingImagePreviewState.UNCONFIRMED)
                changed = true
            }
        }
        return changed
    }

    /** 查询可恢复预览（FAILED/UNCONFIRMED）但不消费；调用方先做输入区/归属
     *  校验，全部通过后再用 [restoreDraft] 消费，避免校验失败时丢载荷。 */
    fun peekRestorable(token: String): OutgoingImagePreview? =
        items.firstOrNull {
            it.sendToken == token &&
                (it.state == OutgoingImagePreviewState.FAILED || it.state == OutgoingImagePreviewState.UNCONFIRMED)
        }

    /**
     * 手动恢复草稿：仅 FAILED/UNCONFIRMED 可恢复；返回载荷并移除预览。
     * 调用方负责"输入区为空才回填"（不覆盖新草稿）。
     */
    fun restoreDraft(token: String): OutgoingImagePreview? {
        val index = items.indexOfFirst {
            it.sendToken == token &&
                (it.state == OutgoingImagePreviewState.FAILED || it.state == OutgoingImagePreviewState.UNCONFIRMED)
        }
        if (index < 0) return null
        return items.removeAt(index)
    }

    /** 调试重置：清空全部预览与在途请求身份。 */
    fun clearAll() {
        items.clear()
        awaiting.clear()
        boundSessionsByToken.clear()
    }

    /**
     * 终态预览（FAILED/UNCONFIRMED）的有界保留：每条预览都挂着一整组图片 base64
     * （渲染用 [OutgoingImagePreview.images] + 恢复用 restoreImages，各是 MB 级
     * UTF-16 字符），而只有用户主动点“恢复草稿”才会消费它们——从未被恢复的
     * 失败发送不能无限期占着内存。
     *
     * 保留规则（按插入序，越靠后越新）：
     * - 当前可见上下文（当前会话 / 当前新建对话页）最近 [VISIBLE_RESTORABLE_KEEP] 条一定保留：
     *   界面里刚失败的发送随时可恢复；
     * - 其余终态预览共最多 [TOTAL_RESTORABLE_KEEP] 条，超出部分从最旧淘汰；
     * - SENDING/CONFIRMED 不参与淘汰（还在等 sent/回显/未确认收敛）。
     * 返回是否淘汰了预览，调用方据此刷新列表。
     */
    fun pruneRestorable(currentSessionId: String?, currentNewSessionEpoch: Long): Boolean {
        val restorableIndexes = items.indices.filter { index ->
            items[index].state == OutgoingImagePreviewState.FAILED ||
                items[index].state == OutgoingImagePreviewState.UNCONFIRMED
        }
        if (restorableIndexes.isEmpty()) return false
        val keep = linkedSetOf<Int>()
        restorableIndexes.asReversed()
            .filter { items[it].isVisibleFor(currentSessionId, currentNewSessionEpoch) }
            .take(VISIBLE_RESTORABLE_KEEP)
            .forEach { keep += it }
        restorableIndexes.asReversed().forEach {
            if (keep.size < TOTAL_RESTORABLE_KEEP) keep += it
        }
        val drop = restorableIndexes.filterNot { it in keep }.toSet()
        if (drop.isEmpty()) return false
        // token → 会话的绑定记录不跟着清：它另有 16 条上限，且仅供在途回写查询。
        // 按下标重建，不用对象相等（data class 同内容预览会被误删）。
        val retained = items.filterIndexed { index, _ -> index !in drop }
        items.clear()
        items.addAll(retained)
        return true
    }

    /** 会话兼容：预览未绑定（null）视为可绑定任意帧会话；否则必须相等。 */
    private fun sessionsCompatible(previewSession: String?, frameSession: String?): Boolean =
        previewSession == null || frameSession == null || previewSession == frameSession

    private companion object {
        /** sent 绑定记录的保留上限（受理即入队、回写查询后即无意义）。 */
        const val BOUND_SESSION_HISTORY_LIMIT = 16

        /** 当前可见上下文保留的终态预览数（刚失败的发送可恢复）。 */
        const val VISIBLE_RESTORABLE_KEEP = 2

        /** 全局终态预览上限（含上面的可见保留）。 */
        const val TOTAL_RESTORABLE_KEEP = 6
    }
}
