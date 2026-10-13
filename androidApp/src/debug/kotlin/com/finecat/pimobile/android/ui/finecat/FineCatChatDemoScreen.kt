package com.finecat.pimobile.android.ui.finecat

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.ui.finecat.components.FcHeader
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FcViewSeg
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.composer.FcComposer
import com.finecat.pimobile.android.ui.finecat.conversation.FcAiMessage
import com.finecat.pimobile.android.ui.finecat.conversation.FcJumpButton
import com.finecat.pimobile.android.ui.finecat.conversation.FcStep
import com.finecat.pimobile.android.ui.finecat.conversation.FcChatMessage
import com.finecat.pimobile.android.ui.finecat.conversation.FcStepsBlock
import com.finecat.pimobile.android.ui.finecat.conversation.FcTimeSeparator
import com.finecat.pimobile.android.ui.finecat.conversation.FcUserBubble
import com.finecat.pimobile.android.ui.finecat.conversation.FcWorking
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import kotlinx.coroutines.launch

/**
 * P2 对话骨架 demo（仅 debug 宿主）：S4 的静态/交互验证面。
 * 数据是固定 fixture（F2 对话场景）；正文渲染是 P2 临时占位，P3/P4 接生产
 * Markdown；发送/停止/模型点击只回调查看状态，不接网关。
 */
@Composable
fun FineCatChatDemoScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onBackToComponents: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val messages = remember { chatFixture() }

    // 弹卡选择状态与正文列表独立。
    var expandedGroups by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var running by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var shotsTray by rememberSaveable { mutableStateOf(false) }
    val selectedShots = remember { mutableStateListOf<String>() }
    val demoPhotos = remember { List(10) { index -> android.net.Uri.parse("demo://photo/$index") } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val jumpVisible by derivedStateOf { listState.canScrollForward }
    Box(modifier = Modifier.fillMaxSize().background(colors.bg).testTag("chat.root")) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FcTextButton(text = "组件", onClick = onBackToComponents, modifier = Modifier.testTag("chat.backToComponents"))
                FcTextButton(text = if (isDark) "切浅色" else "切深色", onClick = onToggleTheme, modifier = Modifier.testTag("chat.themeToggle"))
                FcTextButton(text = if (running) "停止demo" else "运行demo", onClick = { running = !running }, modifier = Modifier.testTag("chat.runToggle"))
            }
            FcHeader(
                title = "重构工作台",
                showConnectionDot = true,
                directory = "/home/demo/work/pi-demo",
                onDirectoryClick = {},
                leading = {
                    FcIconButton(onClick = {}, icon = FineCatIcons.Menu, contentDescription = "会话列表", solid = true)
                },
                trailing = {
                    FcViewSeg(
                        icons = listOf(FineCatIcons.Chat, FineCatIcons.Branch),
                        labels = listOf("对话", "tree"),
                        selected = 0,
                        onSelect = { },
                    )
                },
            )
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chat.list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp,
                    ),
                ) {
                    items(messages, key = { it.id }) { message ->
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                            if (message.timeSeparator != null) {
                                FcTimeSeparator(text = message.timeSeparator)
                            }
                            when {
                                message.isUser -> FcUserBubble(message = message)
                                message.steps.isNotEmpty() -> {
                                    FcStepsBlock(
                                        messageId = message.id,
                                        steps = message.steps.map { step ->
                                            step.copy(label = if (step.isThought) "深度思考" else step.label,
                                                detail = if (step.isThought) step.label else step.detail,
                                                command = step.arg, completed = step.detail != null)
                                        },
                                        expanded = expandedGroups.containsKey(message.id),
                                        expandedStepId = expandedGroups[message.id],
                                        onOpenGroup = { id -> expandedGroups = if (expandedGroups.containsKey(id))
                                            expandedGroups - id else expandedGroups + (id to null) },
                                        onOpenStep = { id -> expandedGroups = expandedGroups +
                                            (message.id to if (expandedGroups[message.id] == id) null else id) },
                                    )
                                    if (message.text.isNotEmpty()) {
                                        FcAiMessage(
                                            message = message,
                                            onCopy = { text -> copyToClipboard(context, text) },
                                            modifier = Modifier.padding(top = 12.dp),
                                        )
                                    }
                                }
                                else -> FcAiMessage(
                                    message = message,
                                    onCopy = { text -> copyToClipboard(context, text) },
                                )
                            }
                        }
                    }
                    if (running) {
                        item(key = "working") {
                            FcWorking(text = "Working…", modifier = Modifier.testTag("chat.working"))
                        }
                    }
                }
                if (jumpVisible) {
                    FcJumpButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .testTag("chat.jump"),
                    )
                }
            }
            FcComposer(
                text = draft,
                onTextChange = { draft = it },
                running = running,
                expanded = draft.isNotEmpty() || shotsTray,
                withShots = shotsTray,
                modelName = "glm-5.3-flash",
                thinkingLabel = "off",
                photos = demoPhotos,
                photoThumbnails = emptyMap(),
                selectedPhotoUris = selectedShots.toSet(),
                onToggleShotsTray = { shotsTray = !shotsTray },
                onTogglePhoto = { uri ->
                    val key = uri.toString()
                    if (selectedShots.contains(key)) selectedShots.remove(key) else selectedShots.add(key)
                },
                onMorePhotos = {},
                onModelClick = {},
                onSend = { draft = "" },
                onStop = { running = false },
                modifier = Modifier.testTag("chat.composer"),
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
}

/** F2 对话 fixture：多轮、仅思考、纯工具、失败步骤、未知工具、纯图、长短文本。 */
private fun chatFixture(): List<FcChatMessage> = listOf(
    FcChatMessage(
        id = "m1", isUser = true, timeSeparator = "昨天 14:02",
        text = "帮我看看 table-pager 分支的表格首帧问题，super.onDraw 后续重绘没有走到表格 span 的绘制路径",
    ),
    FcChatMessage(
        id = "m2", isUser = false,
        steps = listOf(
            FcStep(id = "m2s0", isThought = true, label = "先复现：固定 fixture 打开长表，观察首帧与滚动后的像素变化；怀疑 invalidate 区域没有覆盖表格 span。"),
            FcStep(id = "m2s1", label = "读取", arg = "PiMarkdownText.kt", durationText = "1.2s", detail = "fun onDraw(canvas: Canvas) {\n  super.onDraw(canvas)\n  tableSpan.draw(canvas)\n}"),
            FcStep(id = "m2s2", label = "运行测试", arg = "PiMarkdownTextDeviceTest", durationText = "3.4s", failed = true, detail = "AssertionError: 期望首帧表格区域像素变化，实际与基线一致\nat TableFirstLayoutTest.assertFirstFrame(TableFirstLayoutTest.kt:88)"),
        ),
        text = "定位到：表格 span 的绘制挂在 `onDraw` 之后的延迟布局回调里，首帧之前没有触发 invalidate；同帧手动 layout.draw 会调用。下一步补一个首帧检查。",
    ),
    FcChatMessage(id = "m3", isUser = true, text = "好，先补首帧检查"),
    FcChatMessage(
        id = "m4", isUser = false,
        text = "首帧检查加在 fixture 测试里：进入界面第一帧采样表格区域，与滚动后同一区域对比。这个用例同时覆盖宽度与主题变化，不需要真机也能跑。",
    ),
    FcChatMessage(id = "m5", isUser = true, timeSeparator = "今天 09:15", text = "继续", imageCount = 2),
    FcChatMessage(
        id = "m6", isUser = false,
        steps = listOf(
            FcStep(id = "m6s0", isThought = true, label = "这两张图是同一张表在深色下的两次截图：颜色没有跟随主题更新，说明 style 读取发生在布局前。"),
        ),
        text = "深色下的颜色确实没刷新：span 的 paint 在构造时读了一次主题。改成绘制时读取即可。",
    ),
    FcChatMessage(
        id = "m7", isUser = false,
        steps = listOf(
            FcStep(id = "m7s0", label = "agent_spawn", arg = "sub=table-fix"),
            FcStep(id = "m7s1", label = "编辑", arg = "TableSpan.kt +12 -3", durationText = "0.8s", detail = "- paint.color = theme.color\n+ paint.color = themeColor()\n+ private fun themeColor(): Int =\n+   MaterialColors.surface2(context)"),
        ),
        text = "改完了，你可以直接在 fixture 里验证两个表的独立滚动。",
    ),
)
