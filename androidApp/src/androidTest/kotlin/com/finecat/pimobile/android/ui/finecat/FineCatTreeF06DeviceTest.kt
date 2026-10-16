package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.onMain
import com.finecat.pimobile.android.waitUntil
import com.finecat.pimobile.android.ui.finecat.conversation.formatClockLabel
import java.time.Instant
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * tree 真实内容、导轨、当前叶、分支切换与横纵滚动的设备回归。
 *
 * 组装与 F04/F05 同口径：AndroidAppGraph 全套 fake + 真实 FineCatApp；数据只从锁定的
 * wire 帧进入（PROTOCOL §4.18/§4.19）。fixture 会话甲（f06-tree）：44 节点树（≥40）、
 * 3 处分叉（t02/t05/t25）、8 层深链（t01→…→t08）、超长 preview（t06，末端「横滑末端
 * 标记F06」）、同文异 ID 对（t13/t31，会话乙 b07 三方同文）、用户节点 5 个、44 行超出
 * 视口；leafId=t08。会话乙（f06-tree-b）：8 节点树（b02 分叉、b04 当前叶、b07 与甲同文）。
 *
 * 判据纪律：横/纵滚动只用真实触摸（performTouchInput swipe），offset 从语义滚动轴读
 * 变化量，末端可达用「文本真实显示（assertIsDisplayed）」证明，不用 performScrollTo /
 * 语义存在冒充手势证据；分支切换断言**发出去的请求帧**里的 entryId，同文异 ID 分别点
 * 击分别核对，不按正文文本定位。几何/颜色按 DEC-TOLERANCE（≤2dp / 逐通道 ≤3）。
 * 定位用 performScrollTo 只是测试编排手段，不作为滚动手势证据（F04/F05 先例）。
 *
 * TREE05（2026-10-15）交互口径变更：点行只**浏览选中**并弹动作卡，不再立即切分支；
 * 跳转走卡内「跳到此处」（仍用既有 switch-branch 路径）。本文件所有“点击→切分支”
 * 的用例改走 [jumpRow]（点行 + 点跳转）；浏览选中底色由 surface2 改 surface3，
 * active leaf 改为深色实心行。行首「当前」标记保留（TREE02 已验收口径）。
 * 注意：本文件仍保留一组按旧“泳道/导轨”渲染写的像素断言（分叉实心点/空心点/底线段），
 * 那套渲染已在前几轮删除、改为网关 prefix 等宽文本；这些断言在 TREE05 之前就已与主源码
 * 不同步，本轮未重instrument，不能当作当前页面的验收结论。
 */
@RunWith(AndroidJUnit4::class)
class FineCatTreeF06DeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** 失败证据：语义树入 logcat + 失败瞬间截图。 */
    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("FineCatF06") }
            runCatching {
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                saveShotNamed(bitmap, "${description.methodName}.png")
            }
        }
    }

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null

    // ------------------------------------------------------------------ S8 结构

    @Test
    fun s8_tree_structure_railsForkLeafSameRow() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        // 像素采样前置：首组合 statusBarsPadding 异步应用（229→301）需等 bounds 收敛
        awaitStableBounds(rowTag("t01"))

        // 代表行组合在位（非懒列全组合；逐行可见性由纵滑用例证明）
        assertTrue(hasTag(rowTag("t01")))
        assertTrue(hasTag(rowTag("t22")))
        assertTrue(hasTag(rowTag("t44")))

        // 同排：t01 行内 角色「我」/ 时间 / 正文 三段的垂直区间必须重叠。
        // 时间列期望按 wire 真实形状（ISO/UTC）经本机时区折算，不按字符截取（审查 B1）
        val row1 = tagBounds(rowTag("t01"))
        val who = requireDescendant(rowTag("t01"), "我")
        val tsText = expectedClock("09:01")
        val ts = requireDescendant(rowTag("t01"), tsText)
        val body = requireDescendant(rowTag("t01"), "用户原话甲F06")
        assertTrue(
            "角色/时间/正文应同排：who=$who ts=$ts body=$body",
            who.top < body.bottom && body.top < who.bottom &&
                ts.top < body.bottom && body.top < ts.bottom,
        )
        // gut 右 padding 12dp：ts→正文间距 = 8(gap)+12 = 20dp（审查 m6）
        assertDpEquals("ts→正文 间距", (body.left - ts.right) / density, 20f)
        // 行定宽（原型 .tree{width:max-content;min-width:100%}；horizontalScroll 内
        // fillMaxWidth 单独无效，审查 M2）。语义 bounds 被视口剪裁测不到真实宽度，
        // 判据改用「剪裁到缘」：t03（内容约 300dp 真短行；t01 加 md 标记段后 ≈ 视口
        // 宽、剪裁后两态同真无判别力，R2-F1）定宽生效时铺满到视口右缘，退化态
        //（行=内容宽）右缘远早于视口——width 低于视口-left 即为退化。
        val row3m2 = tagBounds(rowTag("t03"))
        val vpWidth = tagBounds("finecat.treeContent").width
        assertTrue(
            "短行应铺满到视口右缘（定宽生效，剪裁宽=视口-行 left）：w=${row3m2.width / density}dp " +
                "left=${row3m2.left / density}dp 视口=${vpWidth / density}dp",
            row3m2.width >= vpWidth - row3m2.left - px(2f),
        )
        // 行内 md 按原型 md() 剥定界符（R-m8）：内容段在、定界符不在渲染文本
        assertTrue("t01 应渲染 code 内容段", descendantBounds(rowTag("t01"), "npm test") != null)
        assertTrue("t01 应渲染 bold 内容段", descendantBounds(rowTag("t01"), "重试") != null)
        assertTrue("定界符 ` 不应出现在渲染文本", descendantBounds(rowTag("t01"), "`") == null)
        assertTrue("定界符 ** 不应出现在渲染文本", descendantBounds(rowTag("t01"), "**") == null)

        // 缩进 = depth×16dp：t02=16、t08=112（8 层深链）
        val row2 = tagBounds(rowTag("t02"))
        val row8 = tagBounds(rowTag("t08"))
        assertDpEquals("t02 缩进", (row2.left - row1.left) / density, 16f)
        assertDpEquals("t08 缩进", (row8.left - row1.left) / density, 112f)

        // 导轨线：t01/t02 行内 x=+7dp 处 = line 色（非末位全段）
        val bmp = rootBitmap()
        assertPixelNear(bmp, row1.left.roundToInt() + px(7f), row1.top.roundToInt() + px(4f), lightLine, "t01 导轨线=line")
        assertPixelNear(bmp, row2.left.roundToInt() + px(7f), row2.top.roundToInt() + px(4f), lightLine, "t02 导轨线=line")

        // 点：t01/t02 分叉=实心 ink；t08 深链叶=空心（环 text3、心 bg）
        assertPixelNear(bmp, row1.left.roundToInt() + px(7.5f), row1.top.roundToInt() + px(18.5f), lightInk, "t01 分叉实心点=ink")
        assertPixelNear(bmp, row2.left.roundToInt() + px(7.5f), row2.top.roundToInt() + px(18.5f), lightInk, "t02 分叉实心点=ink")
        val dot8X = row8.left.roundToInt() + px(7.5f)
        val dot8Y = row8.top.roundToInt() + px(18.5f)
        assertPixelNear(bmp, dot8X, dot8Y, lightBg, "t08 空心点心=bg")
        assertPixelNear(bmp, dot8X - px(2.75f), dot8Y, lightText3, "t08 空心点环=text3")

        // 当前活动叶：leafId=t08 行带「当前」标签，非叶行没有
        assertTrue("t08 行应有「当前」标签", requireDescendant(rowTag("t08"), "当前") != null)
        assertTrue("t02 行不应有「当前」标签", descendantBounds(rowTag("t02"), "当前") == null)

        // 测量取证：缩进 16dp 实测 + 导轨 x + 点心（check 按 measures.tsv 在列校验）
        saveMeasure(
            "CAPTURE-MEASURE: tree.indent.t02 placed=1 maxDeltaDp=0 " +
                "actual=${(row2.left - row1.left).roundToInt()},${row2.top.roundToInt()},${row2.width.roundToInt()},${row2.height.roundToInt()}",
        )
        saveMeasure(
            "CAPTURE-MEASURE: tree.rail.t02 placed=1 maxDeltaDp=0 " +
                "actual=${row2.left.roundToInt() + px(7f)},${row2.top.roundToInt() + px(4f)},${px(1f)},${row2.height.roundToInt()}",
        )
        saveShotNamed(bmp, "f06-s8-struct.png")
    }

    @Test
    fun s8_tree_deepForkLeaf_visibleEvidence() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        // 真实纵滑把 8 层深链滚入视口，fork 点/当前叶标签必须是真实绘制+显示
        realSwipeUpUntilVisible(rowTag("t05"))
        realSwipeUpUntilVisible(rowTag("t08"))
        val bmp = rootBitmap()
        val row5 = tagBounds(rowTag("t05"))
        assertPixelNear(bmp, row5.left.roundToInt() + px(7.5f), row5.top.roundToInt() + px(18.5f), lightInk, "t05 分叉实心点=ink")
        assertTrue("t08「当前」标签应真实显示", requireDescendant(rowTag("t08"), "当前") != null)
        compose.onNodeWithTag(rowTag("t08"), useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        saveShotNamed(rootBitmap(), "f06-s8-fork-deep.png")
    }

    // ------------------------------------------------------------------ 横向真实手势

    @Test
    fun s8_tree_horizontalRealDrag_longPreviewEndReached() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        // 基线：横轴 0；拖前像素帧
        val h0 = hAxisValue()
        assertEquals("初始横滚应为 0", 0f, h0, 0.5f)
        val before = rootBitmap()
        val panBounds = tagBounds("finecat.treeContent")

        // 真实横向拖动（只允许触摸管线），拖到尽头
        var drags = 0
        while (drags < 24 && hAxisValue() < hAxisMax() - 1f) {
            compose.onNodeWithTag("finecat.treeContent").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            drags++
        }
        val h1 = hAxisValue()
        assertTrue("横向拖动后 offset 必须变化：before=$h0 after=$h1 drags=$drags", h1 > 0f)
        // 内容区域像素必须变化
        assertTrue(
            "横向拖动后内容区域像素必须变化",
            pixelDiffers(before, rootBitmap(), panBounds),
        )
        // 长 preview 末端标记真实显示（t06 行内，非仅语义存在）
        val end = requireDescendant(rowTag("t06"), LONG_TAIL)
        val viewport = tagBounds("finecat.treeContent")
        assertTrue(
            "末端标记应在视口内真实绘制：$end viewport=$viewport",
            end.width > 0f && end.height > 0f && end.right > viewport.left && end.left < viewport.right,
        )
        saveShotNamed(rootBitmap(), "f06-s8-pan-end.png")
    }

    // ------------------------------------------------------------------ 纵向真实手势

    @Test
    fun s8_tree_verticalRealDrag_reachesLastNode() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        // 非末位探出对照在滚动前采（首屏 t03：t02 的孩子、后面还有 t11；单链里除
        // 末梢全是 lastChild，滚到底后 t03 已被视口顶剪裁不能当对照）
        val bmpTop = rootBitmap()
        val row3 = tagBounds(rowTag("t03"))
        // 线画到内容盒底+6dp（=行底整，与下一行 -6dp 起点搭接）；bottom+3px 已出线界
        assertPixelNear(bmpTop, row3.left.roundToInt() + px(7f), row3.bottom.roundToInt() - px(3f), lightLine, "t03 非末位行底线段应在")
        val v0 = vAxisValue()
        realSwipeUpUntilVisible(rowTag("t44"))
        val v1 = vAxisValue()
        assertTrue("纵向拖动后 offset 必须变化：before=$v0 after=$v1", v1 > v0)
        // 末节点真实放置且进入可见区域（显示断言，不是语义存在）
        compose.onNodeWithTag(rowTag("t44"), useUnmergedTree = true).assertIsDisplayed()
        // 末梢导轨：t44（整树最后一行、同级末位）行底下方无搭接线
        val bmp = rootBitmap()
        val row44 = tagBounds(rowTag("t44"))
        assertNotPixel(bmp, row44.left.roundToInt() + px(7f), row44.bottom.roundToInt() + px(3f), lightLine, "t44 末位行底不应有导轨探出")
        saveShotNamed(bmp, "f06-s8-scroll-end.png")
    }

    // ------------------------------------------------------------------ 分支切换与身份

    @Test
    fun s8_tree_clickSwitch_sendsRealEntryId() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        jumpRow(rowTag("t02"))
        awaitSwitchRequest(transport, "t02")
        // 重拉树计数快照必须在 receive(branch-switched) 之前：自动重拉由该帧触发，
        // 可能早于下方 waitUntil 取快照已发出（竞态 F06 实测）
        val beforeReload = transport.payloadsOfType("tree").size
        transport.receive(branchSwitchedFrame(SESSION, "t02"))
        compose.waitUntil(5_000) { transport.payloadsOfType("tree").size > beforeReload }
        transport.receive(treeFrameA("t02"))
        compose.waitUntil(5_000) { holder!!.sessionTree?.second == "t02" }
        compose.waitForIdle()
        // 成功：回对话（treeContent 消失、对话列表出现）
        awaitCond(dump = ::treeStateDump) { !hasTag("finecat.treeContent") && hasTag("finecat.list") }
        // 回树：新树 leafId=t02，「当前」落在 t02
        gotoTreeAndLoad(transport, treeFrameA("t02"), "t02")
        assertTrue("切换后 t02 应带「当前」", requireDescendant(rowTag("t02"), "当前") != null)
        assertTrue("旧叶 t08 不应再带「当前」", descendantBounds(rowTag("t08"), "当前") == null)
        saveShotNamed(rootBitmap(), "f06-s8-jump.png")
        // 卡条件4另一半：editorText/queued 恢复。**口径**：queued 双空格合并是移动端
        // 历史取舍（notes/tree.md:19），pi 原生用空行 join（interactive-mode.js:3761），
        // PROTOCOL §4.19 的"双空格"措辞待网关侧修正（本轮无网关授权）；"草稿空才覆盖
        // editorText"与 pi 一致。此前恒传 null/[]，该路径零覆盖（审查 m10/R-m10）
        // 段1：已有草稿 → queued 前置合并；成功切换回对话（tick→onBack）
        holder!!.messageDraft = "草稿F06"
        jumpRow(rowTag("t05"))
        awaitSwitchRequest(transport, "t05")
        val beforeQueued = transport.payloadsOfType("tree").size
        transport.receive(branchSwitchedFrame(SESSION, "t05", queued = listOf("q1", "q2")))
        compose.waitUntil(5_000) { transport.payloadsOfType("tree").size > beforeQueued }
        transport.receive(treeFrameA("t05"))
        compose.waitUntil(5_000) { holder!!.sessionTree?.second == "t05" }
        // 成功切换尾部自动 loadHistory(SESSION)：应答释放 history lane。等待阈值 >1
        // 可能被段1 之前那次成功切换（t02）的未应答请求提前满足——注入落到旧请求上
        // 结果无害（history 落位、lane 释放，与草稿断言无关），故不做快照口径
        //（复审 R2-F7）
        compose.waitUntil(5_000) {
            transport.payloadsOfType("history").count {
                it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION
            } > 1
        }
        transport.receive(historyFrame(SESSION, listOf(Msg("user", "h-1", "历史用户F06甲")), "h-1", "h-1"))
        assertEquals("queued 应前置合并进既有草稿（双空格=移动端取舍）", "q1  q2  草稿F06", holder!!.messageDraft)
        // 段2：assistant/current leaf不返回editorText，alreadyAt保持输入、不tick。
        gotoTreeAndLoad(transport, treeFrameA("t05"), "t05")
        holder!!.messageDraft = ""
        jumpRow(rowTag("t02"))
        awaitSwitchRequest(transport, "t02")
        val tickBeforeAlready = holder!!.branchSwitchedTick
        transport.receive(
            branchSwitchedFrame(SESSION, "t02", alreadyAt = true),
        )
        compose.waitUntil(5_000) { hasAnyText("已在此处") }
        assertEquals("assistant/alreadyAt应保持输入框为空", "", holder!!.messageDraft)
        assertEquals("alreadyAt 不得计 tick", tickBeforeAlready, holder!!.branchSwitchedTick)
    }

    @Test
    fun s8_tree_clickSwitchFailure_leafKept() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        val tickBefore = holder!!.branchSwitchedTick
        jumpRow(rowTag("t20"))
        awaitSwitchRequest(transport, "t20")
        transport.receive(errorFrame("运行中，暂不能切换分支"))
        compose.waitForIdle()
        // 服务端拒绝按瞬态提示呈现，不接全局错误面（F06 契约：失败保持原叶）
        compose.waitUntil(5_000) { hasAnyText(SWITCH_REJECT_NOTICE) }
        assertTrue("服务端拒绝不得进全局错误面", holder!!.platformError == null)
        Thread.sleep(300)
        compose.waitForIdle()
        // 失败：无 tick、留在树页、活动叶保持 t08、目标行未变成当前
        assertEquals("失败不得计入切换 tick", tickBefore, holder!!.branchSwitchedTick)
        assertTrue("失败应留在树页", hasTag("finecat.treeContent"))
        assertTrue("活动叶应保持 t08", requireDescendant(rowTag("t08"), "当前") != null)
        assertTrue("t20 不应变成当前", descendantBounds(rowTag("t20"), "当前") == null)
        // 失败后链路仍可用：再点 t02 仍发真实切换请求（重拉快照同前，先于 receive）
        jumpRow(rowTag("t02"))
        awaitSwitchRequest(transport, "t02")
        val beforeReload = transport.payloadsOfType("tree").size
        transport.receive(branchSwitchedFrame(SESSION, "t02"))
        compose.waitUntil(5_000) { transport.payloadsOfType("tree").size > beforeReload }
        transport.receive(treeFrameA("t02"))
        compose.waitUntil(5_000) { holder!!.sessionTree?.second == "t02" }
        compose.waitForIdle()
        saveShotNamed(rootBitmap(), "f06-s8-jump-fail.png")
    }

    @Test
    fun s8_tree_clickLeaf_alreadyAt_staysWithNotice() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        val tickBefore = holder!!.branchSwitchedTick
        jumpRow(rowTag("t08"))
        awaitSwitchRequest(transport, "t08")
        transport.receive(branchSwitchedFrame(SESSION, "t08", alreadyAt = true))
        compose.waitUntil(5_000) { hasAnyText("已在此处") }
        assertEquals("alreadyAt 不得计入切换 tick", tickBefore, holder!!.branchSwitchedTick)
        assertTrue("已在此处应留在树页", hasTag("finecat.treeContent"))
        saveShotNamed(rootBitmap(), "f06-s8-already.png")
    }

    @Test
    fun s8_tree_sameTextDifferentIds_clicksSendRespectiveIds() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        // 同文异 ID 两节点逐个真实点击：请求帧里的 entryId 必须各归各（不按文本定位）。
        // 对子放首屏深链（t07/t08）：深行定位通道有系统性坑（fling 跨行/scrollToNode
        // 滚错轴，见 realSwipeUntilVisible 注释），身份验证本身不需要深处。
        jumpRow(rowTag("t07"))
        awaitSwitchRequest(transport, "t07")
        transport.receive(branchSwitchedFrame(SESSION, "t07", alreadyAt = true))
        compose.waitUntil(5_000) { hasAnyText("已在此处") }
        jumpRow(rowTag("t08"))
        awaitSwitchRequest(transport, "t08")
        transport.receive(branchSwitchedFrame(SESSION, "t08", alreadyAt = true))
        compose.waitForIdle()
        saveShotNamed(rootBitmap(), "f06-s8-same-text.png")
    }

    // ------------------------------------------------------------------ tab / 会话隔离

    @Test
    fun s8_tree_tabSwitch_keepsScrollSelectionLeaf() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        // 点 t20（失败回复保持选中高亮、留在树页），记录滚动基线
        jumpRow(rowTag("t20"))
        awaitSwitchRequest(transport, "t20")
        transport.receive(errorFrame("运行中，暂不能切换分支"))
        compose.waitForIdle()
        compose.waitUntil(5_000) { hasAnyText(SWITCH_REJECT_NOTICE) }
        assertTrue("服务端拒绝不得进全局错误面", holder!!.platformError == null)
        compose.onNodeWithTag("finecat.treeContent").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val hKeep = hAxisValue()
        val vKeep = vAxisValue()
        assertTrue("前置：横滚应已移动", hKeep > 0f)
        // 切对话再切回（点击偶发不生效，两击重试）
        gotoChatTab()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        // 滚动位置保持
        assertEquals("切 tab 后横滚应保持", hKeep, hAxisValue(), 0.5f)
        assertEquals("切 tab 后纵滚应保持", vKeep, vAxisValue(), 0.5f)
        // 选中高亮保持（TREE05：浏览选中行背景 = surface3，深色 active leaf 行是另一回事）。
        // 采样取行中心：高亮底从 depth*16dp
        // 缩进后才开始铺（background 挂在 start padding 之后），左上角采样会落在
        // 缩进前的 surface 底上（F06 实测 t20 采到 fffafafa）；读数前先等 bounds
        // 收敛（切回树后重组过渡态行 bounds 会短暂 (0,0)，F06 实测）
        realSwipeUntilVisible(rowTag("t20"))
        awaitStableBounds(rowTag("t20"))
        val row20 = tagBounds(rowTag("t20"))
        assertPixelNear(
            rootBitmap(), (row20.left + row20.width / 2f).roundToInt(), (row20.top + row20.height / 2f).roundToInt(),
            lightSurface3, "切 tab 后 t20 选中高亮应保持",
        )
        // 当前叶不受影响
        assertTrue("切 tab 后 t08「当前」应保持", requireDescendant(rowTag("t08"), "当前") != null)
        saveShotNamed(rootBitmap(), "f06-s8-tab-keep.png")
    }

    @Test
    fun s8_tree_sessionSwitch_noInheritance() {
        val transport = setupSession()
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        compose.onNodeWithTag("finecat.treeContent").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val hA = hAxisValue()
        assertTrue(hA > 0f)
        // 切会话乙（停在 tree tab，LaunchedEffect 立即按新会话拉树）：树请求快照
        // 必须在 selectSession 之前取（审查 B1 同款竞态）
        val treeBBefore = transport.payloadsOfType("tree").size
        val subscribeBBefore = transport.payloadsOfType("subscribe").count {
            it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION_B
        }
        onMain { holder!!.selectSession(SESSION_B) }
        serveSubscribed(transport, SESSION_B, subscribeBBefore)
        serveHistoryB(transport)
        gotoTreeAndLoad(transport, treeFrameB(LEAF_B), LEAF_B, treeCountBefore = treeBBefore)
        compose.waitForIdle()
        // 不继承：滚动归零、无甲的选中高亮、「当前」= 乙叶 b04
        assertEquals("切会话后横滚不得继承", 0f, hAxisValue(), 0.5f)
        assertEquals("切会话后纵滚不得继承", 0f, vAxisValue(), 0.5f)
        val b01 = tagBounds(rowTag("b01"))
        assertPixelNear(
            rootBitmap(), b01.left.roundToInt() + px(12f), b01.top.roundToInt() + px(4f),
            lightBg, "乙树首行不应有甲的选中高亮",
        )
        assertTrue("乙当前叶应为 b04", requireDescendant(rowTag("b04"), "当前") != null)
        // 乙的同文异 ID 节点点击发乙自己的 id（跨会话不串）
        jumpRow(rowTag("b07"))
        awaitSwitchRequest(transport, "b07")
        transport.receive(branchSwitchedFrame(SESSION_B, "b07", alreadyAt = true))
        compose.waitUntil(5_000) { hasAnyText("已在此处") }
        // alreadyAt 帧尾部会自动 loadHistory+loadSessionTree（branch-switched 处理）：
        // 逐笔应答释放 lane，否则切回甲的请求在 lane 排队不发送、计数永不增（F06 实测）
        compose.waitUntil(5_000) {
            transport.payloadsOfType("history").count {
                it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION_B
            } > 1
        }
        transport.receive(historyFrame(SESSION_B, listOf(Msg("user", "hb-1", "历史用户F06乙")), "hb-1", "hb-1"))
        compose.waitUntil(5_000) {
            transport.payloadsOfType("tree").count {
                it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION_B
            } > 1
        }
        transport.receive(treeFrameB(LEAF_B))
        // 点击波纹淡出后再采样（Material3 clickable 波纹残留会给选中底混色，
        // F06 实测采到 e0e0de）
        Thread.sleep(700)
        compose.waitForIdle()
        // defaultMinSize 判别力：乙树最宽行窄于视口，定宽=min 生效，b07 选中底应铺到
        // 内容盒右缘（视口-20dp 页边距内取 -24dp；退化态行=内容宽，此处会是 bg，R-M2）
        val b07Row = tagBounds(rowTag("b07"))
        val bVpRight = tagBounds("finecat.treeContent").right.roundToInt()
        assertPixelNear(
            rootBitmap(), bVpRight - px(24f), (b07Row.top + b07Row.height / 2f).roundToInt(),
            lightSurface3,
            "乙树短行选中底应铺到视口右缘-页边距内（b07=$b07Row vp=${tagBounds("finecat.treeContent")} " +
                "sel=${holder?.let { it.sessionTree?.second }} all=" +
                compose.onAllNodesWithTag(rowTag("b07"), useUnmergedTree = true)
                    .fetchSemanticsNodes().map { it.boundsInRoot } + "）",
        )
        // 切回甲：树瞬态按会话键控（remember(selectedSessionId)）——甲拿到新实例，
        // 不得继承乙的滚动；甲自己的横滚不跨会话往返保留（与对话页切会话重置滚动同语义）
        // 甲 history 请求计数快照必须在 selectSession 之前：切回的自动拉取一旦发出，
        // "存在性"匹配会被 setup 期的旧请求提前满足、注入被 STALE 丢弃（F06 实测）
        val historyABefore = transport.payloadsOfType("history").count {
            it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION
        }
        val treeABefore = transport.payloadsOfType("tree").size
        val subscribeABefore = transport.payloadsOfType("subscribe").count {
            it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION
        }
        onMain { holder!!.selectSession(SESSION) }
        serveSubscribed(transport, SESSION, subscribeABefore)
        // 切回甲的自动 history 请求偶发注入被 STALE 丢（F06 flake 实测，与树请求同源）：
        // 重试一轮等待+注入（增量仍按 historyABefore，迟到请求同样被计数捕获；首轮已
        // 成功落位时第二轮的重复帧会被 runtime 作无在途请求拒绝，不影响已落位状态）
        runCatching { serveHistoryA(transport, historyABefore) }.recoverCatching {
            compose.waitForIdle()
            serveHistoryA(transport, historyABefore)
        }.getOrThrow()
        // 切回甲的自动树请求偶发不发（treeLoading 守卫时序，F06 flake 实测）：超时后
        // 显式再调一次 loadSessionTree——受 treeLoading 守卫，守卫已释放才真发，未释放
        // 时由首跑那次迟到请求被计数捕获兜住（复审 R-兜底），再等落位
        runCatching {
            gotoTreeAndLoad(transport, treeFrameA(LEAF_A), LEAF_A, treeCountBefore = treeABefore)
        }.recoverCatching {
            onMain { holder!!.loadSessionTree() }
            gotoTreeAndLoad(transport, treeFrameA(LEAF_A), LEAF_A, treeCountBefore = treeABefore)
        }.getOrThrow()
        compose.waitForIdle()
        assertEquals("切回甲不得继承乙的横滚", 0f, hAxisValue(), 0.5f)
        assertTrue("切回甲当前叶应为 t08", requireDescendant(rowTag("t08"), "当前") != null)
        saveShotNamed(rootBitmap(), "f06-s8-session-b.png")
    }

    // ------------------------------------------------------------------ 适配矩阵

    @Test
    fun matrix_390dp_dark_s8() {
        val transport = setupSession()
        holder!!.updateAppearanceMode("dark")
        runBlocking { waitUntil { holder!!.appearanceMode == "dark" } }
        gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
        awaitTreeScreenStable()
        // 暗色导轨/点换暗 token；采样必须在滚动前（滚到底后 t02 已在视口上缘之外，
        // bounds 为负、采样点被 clamp 到左上角背景色，F06 实测 (21,12)=ff0c0c0e）
        val bmp = rootBitmap()
        val row2 = tagBounds(rowTag("t02"))
        assertPixelNear(bmp, row2.left.roundToInt() + px(7f), row2.top.roundToInt() + px(4f), darkLine, "暗色导轨=line")
        assertPixelNear(bmp, row2.left.roundToInt() + px(7.5f), row2.top.roundToInt() + px(18.5f), darkInk, "暗色分叉点=ink")
        // 末节点纵滑可达 + 当前叶在位
        realSwipeUpUntilVisible(rowTag("t44"))
        compose.onNodeWithTag(rowTag("t44"), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(requireDescendant(rowTag("t08"), "当前") != null)
        saveShotNamed(rootBitmap(), "f06-s8-dark.png")
    }

    @Test
    fun matrix_360dp_fs10_light_s8() {
        applyDeviceConfig(520, null)
        matrixStep("f06-s8-360l", dark = false)
    }

    @Test
    fun matrix_360dp_fs10_dark_s8() {
        applyDeviceConfig(520, null)
        matrixStep("f06-s8-360d", dark = true)
    }

    @Test
    fun matrix_360dp_fs13_light_s8() {
        applyDeviceConfig(520, "1.3")
        matrixStep("f06-s8-fs13l", dark = false, fs13 = true)
    }

    @Test
    fun matrix_360dp_fs13_dark_s8() {
        applyDeviceConfig(520, "1.3")
        matrixStep("f06-s8-fs13d", dark = true, fs13 = true)
    }

    /**
     * 360 口径（DEC-MATRIX 按受影响范围）：S8 真实树结构、长行横滑可达、末节点纵滑
     * 可达、当前叶在位；相对断言不做绝对坐标对照；finally 恢复设备配置并读回断言。
     */
    private fun matrixStep(prefix: String, dark: Boolean, fs13: Boolean = false) {
        try {
            val transport = setupSession()
            if (dark) {
                holder!!.updateAppearanceMode("dark")
                runBlocking { waitUntil { holder!!.appearanceMode == "dark" } }
            }
            gotoTreeAndLoad(transport, treeFrameA(LEAF_A))
            awaitTreeScreenStable()
            assertTrue("t01 行应组合", hasTag(rowTag("t01")))
            assertTrue("t08「当前」应在位", requireDescendant(rowTag("t08"), "当前") != null)
            if (fs13) {
                // who 是 widthIn(min=18dp)：fs1.3 下 assistant 行 "AI"（等宽 2 字符
                // ≈18.7dp）超下限，断其不被 18dp 定宽裁切；"我"单字符 15.6dp 无判别力
                //（复审 R-m7）
                val ai13 = requireDescendant(rowTag("t02"), "AI")
                assertTrue(
                    "fs1.3 下 AI 不应被定宽裁切：w=${ai13.width / density}dp",
                    ai13.width / density >= 18.3f,
                )
            }
            // 长行横滑：末端标记可达
            var drags = 0
            while (drags < 24 && hAxisValue() < hAxisMax() - 1f) {
                compose.onNodeWithTag("finecat.treeContent").performTouchInput { swipeLeft() }
                compose.waitForIdle()
                drags++
            }
            assertTrue("360 下横滑应推进（drags=$drags）", hAxisValue() > 0f)
            requireDescendant(rowTag("t06"), LONG_TAIL)
            saveShotNamed(rootBitmap(), "$prefix-s8.png")
            // 末节点纵滑可达：真实手势推到底后，语义定位末行并断言真实放置在视口内
            //（360 相对口径；390 纵滑用例已用纯手势到达的 bounds 相交证据）
            realSwipeUpUntilVisible(rowTag("t44"))
            compose.onNodeWithTag(rowTag("t44"), useUnmergedTree = true).performScrollTo()
            awaitStableBounds(rowTag("t44"))
            val b44 = tagBounds(rowTag("t44"))
            val vp44 = tagBounds("finecat.treeContent")
            assertTrue(
                "t44 应放置于视口内：bounds=$b44 viewport=$vp44 v=${vAxisValue()}/${vAxisMax()}",
                b44.height > 0f && b44.bottom > vp44.top && b44.top < vp44.bottom,
            )
            saveShotNamed(rootBitmap(), "$prefix-s8-end.png")
        } finally {
            restoreDeviceConfig()
        }
    }

    /** 注入系统配置并轮询读回生效（固定 sleep 在密度切换上有竞态，两矩阵交替挂）。 */
    private fun applyDeviceConfig(densityDpi: Int, fontScale: String?) {
        shell("wm density $densityDpi")
        if (fontScale != null) shell("settings put system font_scale $fontScale")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val out = shell("wm density")
            if (out.contains("Override density: $densityDpi")) break
            Thread.sleep(200)
        }
        Thread.sleep(500)
        compose.waitForIdle()
    }

    private fun restoreDeviceConfig() {
        shell("wm density 480")
        shell("settings put system font_scale 1.0")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val out = shell("wm density")
            if (out.contains("Override density: 480")) break
            Thread.sleep(200)
        }
        val densityOut = shell("wm density")
        val fontScale = shell("settings get system font_scale")
        assertTrue(
            "矩阵步骤结束必须恢复设备配置：density=[$densityOut] fontScale=[$fontScale]",
            densityOut.contains("Override density: 480") && fontScale.trim() == "1.0",
        )
    }

    // ------------------------------------------------------------------ 滚动轴/手势 helper

    /** 纵轴读数（finecat.treeContent 语义）。 */
    private fun vAxisValue(): Float = compose.onNodeWithTag("finecat.treeContent")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun vAxisMax(): Float = compose.onNodeWithTag("finecat.treeContent")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    /** 横轴读数（treeContent 的子节点 = 横滑层，BoundedUi 同款结构读取）。 */
    private fun hAxisRange(): ScrollAxisRange? =
        compose.onNodeWithTag("finecat.treeContent").fetchSemanticsNode()
            .children.firstNotNullOfOrNull { child ->
                child.config.getOrElseNullable(SemanticsProperties.HorizontalScrollAxisRange) { null }
            }

    private fun hAxisValue(): Float =
        hAxisRange()?.value() ?: throw AssertionError("treeContent 子层无横滚轴语义")

    private fun hAxisMax(): Float =
        hAxisRange()?.maxValue() ?: throw AssertionError("treeContent 子层无横滚轴语义")

    /** 低速短拖一页（无 fling：moveBy 慢速 up 不触发惯性），停点精确不跨行。 */
    private fun slowDragUp() {
        compose.onNodeWithTag("finecat.treeContent").performTouchInput {
            val start = center
            down(start)
            repeat(6) { moveBy(Offset(0f, -100f), delayMillis = 40) }
            up()
        }
    }

    private fun slowDragDown() {
        compose.onNodeWithTag("finecat.treeContent").performTouchInput {
            val start = Offset(center.x, top + 220f)
            down(start)
            repeat(6) { moveBy(Offset(0f, 100f), delayMillis = 40) }
            up()
        }
    }

    /**
     * 真实触摸滚动定位目标行（双向）：行的 performScrollTo 会把纵向意图交给最近滚动
     * 祖先——本树里是横滚 Box，纵轴被静默吞掉（BoundedUi 已记录的坑），所以定位必须
     * 用真实手势。方向按目标 bounds 与视口的相对位置决定，滚不动即停。
     */
    private fun realSwipeUntilVisible(tag: String) {
        val viewport = tagBounds("finecat.treeContent")
        fun visible(): Boolean = tagBoundsOrNull(tag)?.let { b ->
            b.right > viewport.left && b.left < viewport.right &&
                b.bottom > viewport.top && b.top < viewport.bottom
        } ?: false
        if (visible()) return
        val b0 = tagBoundsOrNull(tag)
        var swipes = 0
        if (b0 != null && b0.top >= viewport.bottom) {
            while (swipes < 40 && !visible() && vAxisValue() < vAxisMax() - 1f) {
                slowDragUp()
                compose.waitForIdle()
                swipes++
            }
        } else {
            while (swipes < 40 && !visible() && vAxisValue() > 1f) {
                slowDragDown()
                compose.waitForIdle()
                swipes++
            }
        }
        // 手势到头仍不可见才兜底：**显式对纵向容器**执行 scrollToNode（fling 步长会跨行、
        // 行自己的 performScrollTo 会把纵轴交给横滚子层——两个坑都在 BoundedUi 有记录）
        runCatching {
            compose.onNodeWithTag("finecat.treeContent")
                .performScrollToNode(hasTestTag(tag))
        }
        compose.waitForIdle()
        val hits = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(
            "手势+兜底后 $tag 仍不可见：hits=${hits.size} bounds=${hits.map { it.boundsInRoot }} " +
                "viewport=$viewport v=${vAxisValue()}/${vAxisMax()}",
            visible(),
        )
    }

    /** 真实触摸上滑逐页推进：可见或已到底任一满足即出（到底时末行必在视口，调用方
     *  以 assertIsDisplayed 作权威显示判定；Compose 剪裁语义可能把行 bounds 清零）。 */
    private fun realSwipeUpUntilVisible(tag: String) {
        val viewport = tagBounds("finecat.treeContent")
        fun atBottom(): Boolean = vAxisValue() >= vAxisMax() - 1f
        // 行滚出视口时 Compose 剪裁语义会把 bounds 清零（tagBounds 会抛错），一律按不可见处理
        fun visible(): Boolean {
            val b = tagBoundsOrNull(tag) ?: return false
            return b.right > viewport.left && b.left < viewport.right &&
                b.bottom > viewport.top && b.top < viewport.bottom
        }
        var swipes = 0
        while (swipes < 40 && !visible() && !atBottom()) {
            slowDragUp()
            compose.waitForIdle()
            swipes++
        }
        assertTrue(
            "真实上滑 $swipes 页后 $tag 不可见且未到底：v=${vAxisValue()}/${vAxisMax()} " +
                "bounds=${tagBoundsOrNull(tag)} viewport=$viewport",
            visible() || atBottom(),
        )
    }

    /** 定位后语义点击行：先真实手势滚进视口再 OnClick（软渲染模拟器注入层点击系统性故障，登记取舍）。 */
    private fun clickRow(tag: String) {
        // 先真实手势把行滚进视口（performScrollTo 滚错轴的坑见 realSwipeUntilVisible），
        // 可见行再语义点击（OnClick 动作直达；注入层在软渲染模拟器上有系统性故障）
        realSwipeUntilVisible(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /** TREE05：点行只浏览选中并弹动作卡，跳转要再点卡内「跳到此处」。
     *  本文件下面所有“点击→切分支”的用例都走这个两步动作。 */
    private fun jumpRow(tag: String) {
        clickRow(tag)
        compose.onNodeWithTag("finecat.treeActionJump", useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /** 等真实切分支请求帧出现且 entryId 与点击行一致；超时带完整诊断（不裸等 compose）。
     *  payloadsOfType 返回 JsonObject 列表：匹配用键值，不要当字符串 contains（键序脆弱）。 */
    private fun awaitSwitchRequest(transport: FakeTransport, entryId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val hit = transport.payloadsOfType("switch-branch").any {
                it["entryId"]?.jsonPrimitive?.contentOrNull == entryId
            }
            if (hit) return
            Thread.sleep(100)
        }
        throw AssertionError(
            "5s 内未见 switch-branch($entryId) 请求：payloads=" +
                transport.payloadsOfType("switch-branch") +
                " sentTypes=" + transport.sentTypes +
                " treeContentIn=" + hasTag("finecat.treeContent"),
        )
    }

    // ------------------------------------------------------------------ 断言/取证 helper

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rowTag(id: String) = "finecat.treeNode.$id"

    private fun hasAnyText(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun tagBoundsOrNull(tag: String): Rect? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }?.boundsInRoot

    private fun tagBounds(tag: String): Rect =
        tagBoundsOrNull(tag) ?: throw AssertionError("节点 $tag 未找到或 bounds 为零")

    /** 节点子树内第一个含指定文本的后代 bounds；找不到返回 null。 */
    private fun descendantBounds(tag: String, text: String): Rect? {
        val root = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull() ?: return null
        var found: Rect? = null
        fun walk(n: SemanticsNode) {
            if (found == null &&
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
            ) {
                found = n.boundsInRoot
            }
            n.children.forEach { walk(it) }
        }
        walk(root)
        return found
    }

    private fun requireDescendant(tag: String, text: String): Rect =
        descendantBounds(tag, text) ?: throw AssertionError("$tag 子树内未找到文本「$text」")

    private fun assertDpEquals(what: String, actualDp: Float, expectedDp: Float) {
        assertTrue(
            "$what 应=$expectedDp dp ±2（实际 $actualDp）",
            abs(actualDp - expectedDp) <= 2f,
        )
    }

    private val density: Float get() = compose.density.density

    private fun px(dp: Float): Int = (dp * density).roundToInt()

    private fun rootBitmap(): Bitmap {
        // 软渲染模拟器偶发 capture 失败/空帧：重试 3 次（失败间隔 200ms 等稳帧）；
        // 存在第二窗口（弹层）时 onRoot 二义，退回活动窗口 decorView 直绘兜底。
        var last: Throwable? = null
        repeat(3) {
            runCatching {
                return compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            }.onFailure { last = it }
            runCatching {
                val decor = compose.activity.window.decorView
                val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(full))
                // decor 图原点=全窗（含状态栏），语义 boundsInRoot 原点=内容区（状态栏
                // 之下）——裁掉顶部 inset 使像素坐标与语义 bounds 同基准，否则像素断言
                // 系统性偏移一个状态栏高（F06 实测乙树采样错位 72px）。前提=宿主
                // ComponentActivity 非 edge-to-edge（compose 根原点=内容区顶）；若改
                // edge-to-edge 此裁剪会反向偏移（复审 R2-F6）
                val top = decor.rootWindowInsets?.systemWindowInsetTop ?: 0
                return if (top > 0) {
                    Bitmap.createBitmap(full, 0, top, full.width, full.height - top)
                } else {
                    full
                }
            }.onFailure { last = it }
            Thread.sleep(200)
            compose.waitForIdle()
        }
        val roots = runCatching {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        }.getOrNull()
        throw AssertionError("rootBitmap 三次采集均失败（roots=$roots）", last)
    }

    private fun sampleClamped(bmp: Bitmap, x: Int, y: Int) = bmp.getPixel(
        x.coerceIn(0, bmp.width - 1),
        y.coerceIn(0, bmp.height - 1),
    )

    private fun assertPixelNear(bmp: Bitmap, x: Int, y: Int, expected: Long, what: String, tol: Int = 3) {
        val c = sampleClamped(bmp, x, y)
        val dr = abs(AndroidColor.red(c) - AndroidColor.red(expected.toInt()))
        val dg = abs(AndroidColor.green(c) - AndroidColor.green(expected.toInt()))
        val db = abs(AndroidColor.blue(c) - AndroidColor.blue(expected.toInt()))
        assertTrue(
            "$what @($x,$y) 实测=${Integer.toHexString(c)} 期望=#${expected.toString(16)} " +
                "通道差 r=$dr g=$dg b=$db 超 $tol",
            dr <= tol && dg <= tol && db <= tol,
        )
    }

    private fun assertNotPixel(bmp: Bitmap, x: Int, y: Int, notExpected: Long, what: String) {
        val c = sampleClamped(bmp, x, y)
        val same = abs(AndroidColor.red(c) - AndroidColor.red(notExpected.toInt())) <= 3 &&
            abs(AndroidColor.green(c) - AndroidColor.green(notExpected.toInt())) <= 3 &&
            abs(AndroidColor.blue(c) - AndroidColor.blue(notExpected.toInt())) <= 3
        assertFalse("$what @($x,$y) 实测=${Integer.toHexString(c)} 不应等于 #${notExpected.toString(16)}", same)
    }

    /** 两帧在给定区域内是否存在实质不同像素（采集噪声底线 8，F05 同款）。 */
    private fun pixelDiffers(a: Bitmap, b: Bitmap, bounds: Rect): Boolean {
        val left = bounds.left.roundToInt().coerceIn(0, a.width - 1)
        val right = bounds.right.roundToInt().coerceIn(0, a.width - 1)
        val top = bounds.top.roundToInt().coerceIn(0, a.height - 1)
        val bottom = bounds.bottom.roundToInt().coerceIn(0, a.height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                val ca = a.getPixel(x, y)
                val cb = b.getPixel(x, y)
                if (abs(AndroidColor.red(ca) - AndroidColor.red(cb)) > 8 ||
                    abs(AndroidColor.green(ca) - AndroidColor.green(cb)) > 8 ||
                    abs(AndroidColor.blue(ca) - AndroidColor.blue(cb)) > 8
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun saveShotNamed(bmp: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        File(dir, name).outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private var measureFileReady = false

    /** CAPTURE-MEASURE 取证行落盘（F05 审查 Finding 7 同机制：外部 files 根）。 */
    private fun saveMeasure(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText(line.trimEnd() + "\n")
    }

    /** 测试进程内注入/读取系统配置（矩阵专用）。 */
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .bufferedReader().use { it.readText() }
    }

    // ------------------------------------------------------------------ graph 组装与 wire 帧

    private fun setupSession(): FakeTransport {
        val transport = FakeTransport()
        transportRef = transport
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            // 注入应答帧的 fixture 需要窗口 >> 模拟器冷启动/软渲染抖动：请求超时
            // （默认 30s，诊断名单里显示为 type=unknown）一触发就 connection-recycled，
            // 在途树请求连坐取消、同代注入帧全部 STALE 静默丢弃。
            requestTimeoutOverride = 120_000L,
        )
        val stateHolder = graph!!.stateHolder
        holder = stateHolder
        compose.setContent { FineCatApp(stateHolder = stateHolder) }
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
            transport.receive(SESSIONS_TWO)
            waitUntil { stateHolder.snapshot.sessions.any { it.id == SESSION_B } }
            onMain { stateHolder.selectSession(SESSION) }
            waitUntil { stateHolder.snapshot.selectedSessionId == SESSION }
            waitUntil { transport.sentTypes.count { it == "subscribe" } >= 1 }
        }
        serveSubscribed(transport, SESSION, 0)
        transport.receive(historyFrame(SESSION, listOf(Msg("user", "h-1", "历史用户F06甲")), "h-1", "h-1"))
        runBlocking { waitUntil { stateHolder.snapshot.conversation.any { it.id.endsWith("#h-1") } } }
        return transport
    }

    /** 会话乙历史（切会话对照面；等请求必须 compose.waitUntil，runBlocking 会卡住主线程）。 */
    private fun serveHistoryB(transport: FakeTransport) {
        // selectSession 的自动拉取可能早于快照已发出：按「乙会话的 history 请求已在
        // transport」等待（send 完成即在途），计数比较有竞态（F06 实测）
        compose.waitUntil(5_000) {
            transport.payloadsOfType("history").any {
                it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION_B
            }
        }
        transport.receive(historyFrame(SESSION_B, listOf(Msg("user", "hb-1", "历史用户F06乙")), "hb-1", "hb-1"))
        compose.waitUntil(5_000) { holder!!.snapshot.conversation.any { it.id.endsWith("#hb-1") } }
    }

    /** 会话甲历史重同步（从乙切回；beforeA=切回前甲请求计数，按增量等新请求）。 */
    private fun serveHistoryA(transport: FakeTransport, beforeA: Int) {
        compose.waitUntil(5_000) {
            transport.payloadsOfType("history").count {
                it["sessionId"]?.jsonPrimitive?.contentOrNull == SESSION
            } > beforeA
        }
        transport.receive(historyFrame(SESSION, listOf(Msg("user", "h-1", "历史用户F06甲")), "h-1", "h-1"))
        compose.waitUntil(5_000) { holder!!.snapshot.conversation.any { it.id.endsWith("#h-1") } }
    }

    /** 进入 tree tab 并等真实 tree 请求后注入应答；注入后按 **数据落位**（sessionTree
     *  的 leafId）确认，行 tag 恒存在不能作为新帧生效证据。 */
    private fun gotoTreeAndLoad(
        transport: FakeTransport,
        frame: String,
        leafId: String = LEAF_A,
        treeCountBefore: Int = transport.payloadsOfType("tree").size,
    ) {
        compose.onNodeWithContentDescription(TREE_TAB).performClick()
        // 只认「计数实增」（treeRequestInFlight 在 sendRequest 之前就置位，此时注入帧
        // 会因 runtime 尚无在途请求被 STALE 静默丢弃；计数实增意味着 transport.send 已
        // 完成、runtime pending 已登记）。默认参数在调用时求值；切会话场景的自动请求
        // 发于 selectSession，调用方必须在 selectSession **之前**取快照传入，否则该
        // 会话的旧请求会让任何"存在性"匹配提前成立（审查 B1 同款竞态，F06 实测）。
        compose.waitUntil(5_000) { transport.payloadsOfType("tree").size > treeCountBefore }
        transport.receive(frame)
        compose.waitUntil(5_000) { holder!!.sessionTree?.second == leafId }
        compose.waitForIdle()
        // 像素采样类用例需自行 awaitStableBounds（横滚保留时 t01 可能被剪裁为 0，
        // 通用等待在此会误炸——采样前的稳定化是采样用例自己的前置）
    }

    /** 带状态 dump 的条件等待（compose.waitUntil 超时只有裸消息，不便定位）；每 100ms 轮询。 */
    private fun awaitCond(timeoutMs: Long = 5_000, dump: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(100)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）：" + dump())
    }

    /** 切回对话 tab：点击后等对话列表出现，未生效再点一次（ViewSeg 偶发吞点击）。 */
    private fun gotoChatTab() {
        fun clickAndAwait() {
            compose.onNodeWithContentDescription(CHAT_TAB).performClick()
            compose.waitUntil(5_000) { hasTag("finecat.list") && !hasTag("finecat.treeContent") }
        }
        runCatching { clickAndAwait() }.recoverCatching {
            compose.waitForIdle()
            clickAndAwait()
        }.getOrThrow()
    }

    private fun treeStateDump(): String =
        "leaf=" + holder!!.sessionTree?.second + " treeIn=" + hasTag("finecat.treeContent") +
            " listIn=" + hasTag("finecat.list") + " notice=" + holder!!.transientNotice +
            " tick=" + holder!!.branchSwitchedTick +
            " inflight=" + holder!!.treeRequestInFlight +
            " treePayloads=" + lastTreePayloadSummary()

    private fun lastTreePayloadSummary(): String {
        return transportRef?.let { t ->
            t.sentTypes.count { it == "tree" }.toString() + "/" + t.sentTypes.size
        } ?: "noTransport"
    }

    private var transportRef: FakeTransport? = null

    /**
     * 树屏稳定 gate：t01/t02 真实放置在树视口内且非零。树屏重组（切 tab 回树、暗色
     * 切换）的过渡态里行 bounds 会短暂为 (0,0) 或悬在视口上方，此态下像素采样/截图
     * 全部失真（F06 实测）；awaitStableBounds 只看两读一致，拦不住这种稳态假象。
     */
    private fun awaitTreeScreenStable() {
        val vp = runCatching { tagBounds("finecat.treeContent") }.getOrNull()
            ?: throw AssertionError("树视口未就绪")
        compose.waitUntil(5_000) {
            val a = runCatching { tagBounds(rowTag("t01")) }.getOrNull() ?: return@waitUntil false
            val b = runCatching { tagBounds(rowTag("t02")) }.getOrNull() ?: return@waitUntil false
            a.height > 0f && b.height > 0f &&
                a.top >= vp.top - 1f && b.top >= vp.top - 1f && b.left >= vp.left - 1f
        }
        compose.waitForIdle()
    }

    /** bounds 连续两次读数一致且非零（布局收敛，F05 awaitPlaced 同口径；失败带 tag）。 */
    private fun awaitStableBounds(tag: String) {
        val deadline = System.currentTimeMillis() + 6_000
        var last: Rect? = null
        while (System.currentTimeMillis() < deadline) {
            val a = runCatching { tagBounds(tag) }.getOrNull()
            if (a != null && a.width > 0f && a.height > 0f && a == last) {
                Thread.sleep(150)
                compose.waitForIdle()
                return
            }
            last = a
            Thread.sleep(80)
        }
        val all = runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
                .mapIndexed { i, n -> "#$i=${n.boundsInRoot}" }
        }.getOrDefault(emptyList())
        throw AssertionError("3s 内 bounds 未收敛：$tag last=$last all=$all")
    }

    // ------------------------------------------------------------------ fixture 装配

    private data class TreeNodeSpec(
        val id: String,
        val parent: String?,
        val role: String,
        val preview: String,
        val active: Boolean,
        val ts: String,
    )

    /** 会话甲树（44 节点、3 分叉 t02/t05/t25、8 层深链 t01→t08、同文对 t13/t31）。 */
    private fun treeANodes(leafId: String): List<TreeNodeSpec> {
        val activeIds = setOf("t01", "t02", "t03", "t04", "t05", "t06", "t07", "t08")
            .let { if (leafId == "t08") it else if (leafId == "t02") setOf("t01", "t02") else it }
        val specs = mutableListOf(
            TreeNodeSpec("t01", null, "user", "用户原话甲F06：树分支头（跑 `npm test` 与 **重试**）", true, "09:01"),
            TreeNodeSpec("t02", "t01", "assistant", "分叉点甲F06（两个孩子）", true, "09:02"),
            TreeNodeSpec("t03", "t02", "assistant", "深链F06第2层", true, "09:03"),
            TreeNodeSpec("t04", "t03", "assistant", "深链F06第3层", true, "09:04"),
            TreeNodeSpec("t05", "t04", "assistant", "深链分叉F06第4层", true, "09:05"),
            TreeNodeSpec("t06", "t05", "assistant", longPreview(), true, "09:06"),
            TreeNodeSpec("t07", "t06", "assistant", SAME_TEXT, true, "09:07"),
            TreeNodeSpec("t08", "t07", "assistant", SAME_TEXT, true, "09:08"),
            TreeNodeSpec("t09", "t05", "user", "侧枝用户F06", false, "09:09"),
            TreeNodeSpec("t10", "t09", "assistant", "侧枝回答F06", false, "09:10"),
            TreeNodeSpec("t11", "t02", "assistant", "第二子分支F06", false, "09:11"),
            TreeNodeSpec("t12", "t11", "user", "第二子用户F06", false, "09:12"),
            TreeNodeSpec("t13", "t12", "assistant", SAME_TEXT, false, "09:13"),
        )
        for (i in 14..19) {
            specs += TreeNodeSpec("t$i", "t${i - 1}", "assistant", "同文下链F06第${i - 13}层", false, "09:$i")
        }
        specs += TreeNodeSpec("t20", null, "assistant", "第二根分叉F06", false, "09:20")
        for (i in 21..25) {
            specs += TreeNodeSpec(
                "t$i", if (i == 21) "t20" else "t${i - 1}", "assistant", "第二根链F06第${i - 20}层", false, "09:$i",
            )
        }
        specs += TreeNodeSpec("t26", "t25", "user", "分叉侧用户F06", false, "09:26")
        specs += TreeNodeSpec("t27", "t25", "assistant", "分叉侧支F06", false, "09:27")
        for (i in 28..30) {
            specs += TreeNodeSpec("t$i", "t${i - 1}", "assistant", "侧支链F06第${i - 27}层", false, "09:$i")
        }
        specs += TreeNodeSpec("t31", "t30", "assistant", SAME_TEXT, false, "09:31")
        specs += TreeNodeSpec("t32", "t31", "assistant", "同文末梢F06", false, "09:32")
        specs += TreeNodeSpec("t33", null, "user", "第三根用户F06", false, "09:33")
        for (i in 34..39) {
            specs += TreeNodeSpec(
                "t$i", if (i == 34) "t33" else "t${i - 1}", "assistant", "第三根链F06第${i - 33}层", false, "09:$i",
            )
        }
        specs += TreeNodeSpec("t40", null, "assistant", "末根F06", false, "09:40")
        for (i in 41..44) {
            specs += TreeNodeSpec(
                "t$i", if (i == 41) "t40" else "t${i - 1}", "assistant", "末根链F06第${i - 40}层", false, "09:$i",
            )
        }
        return specs.map { it.copy(active = it.id in activeIds) }
    }

    /** t08 当前叶版 / t02 切换后版共用的帧装配。 */
    private fun treeFrameA(leafId: String): String = treeFrame(SESSION, leafId, treeANodes(leafId))

    /** 会话乙树（8 节点；b02 分叉；当前叶 b04；b07 与甲 t13/t31 同文）。 */
    private fun treeFrameB(leafId: String): String {
        val active = when (leafId) {
            "b04" -> setOf("b01", "b02", "b03", "b04")
            else -> setOf("b01", "b02", "b05", "b06", "b07", "b08")
        }
        val nodes = listOf(
            TreeNodeSpec("b01", null, "user", "乙树根F06", "b01" in active, "10:01"),
            TreeNodeSpec("b02", "b01", "assistant", "乙树分叉F06", "b02" in active, "10:02"),
            TreeNodeSpec("b03", "b02", "assistant", "乙链一F06", "b03" in active, "10:03"),
            TreeNodeSpec("b04", "b03", "assistant", "乙叶F06当前分支", "b04" in active, "10:04"),
            TreeNodeSpec("b05", "b02", "assistant", "乙侧枝F06", "b05" in active, "10:05"),
            TreeNodeSpec("b06", "b05", "assistant", "乙侧链F06", "b06" in active, "10:06"),
            TreeNodeSpec("b07", "b06", "assistant", SAME_TEXT, "b07" in active, "10:07"),
            TreeNodeSpec("b08", "b07", "assistant", "乙同文末梢F06", "b08" in active, "10:08"),
        )
        return treeFrame(SESSION_B, leafId, nodes)
    }

    /** wire 树帧（PROTOCOL §4.18 形状：prefix 由网关投影给出，客户端不再消费）。 */
    private fun treeFrame(sessionId: String, leafId: String, nodes: List<TreeNodeSpec>): String =
        "{\"kind\":\"tree\",\"sessionId\":" + jsonString(sessionId) +
            ",\"leafId\":" + jsonString(leafId) +
            ",\"nodes\":[" + nodes.joinToString(",") { n ->
                "{\"id\":" + jsonString(n.id) +
                    (n.parent?.let { ",\"parentId\":" + jsonString(it) } ?: "") +
                    ",\"prefix\":\"\",\"onActivePath\":" + n.active +
                    ",\"type\":\"message\",\"role\":" + jsonString(n.role) +
                    ",\"preview\":" + jsonString(n.preview) +
                    ",\"timestamp\":\"2026-09-30T" + n.ts + ":00.000Z\"}"
            } + "]}"

    /** 切分支应答（PROTOCOL §4.19；alreadyAt=已在此处；editorText/queued 供恢复取证）。 */
    private fun branchSwitchedFrame(
        sessionId: String,
        entryId: String,
        alreadyAt: Boolean = false,
        editorText: String? = null,
        queued: List<String> = emptyList(),
    ): String =
        "{\"kind\":\"branch-switched\",\"sessionId\":" + jsonString(sessionId) +
            ",\"entryId\":" + jsonString(entryId) +
            ",\"alreadyAt\":$alreadyAt,\"editorText\":" + (editorText?.let { jsonString(it) } ?: "null") +
            ",\"queued\":[" + queued.joinToString(",") { jsonString(it) } + "]}"

    /** 服务端 error 帧（协议 4.19：bad-request=运行/压缩中被拒；按瞬态提示呈现并保持原叶）。 */
    private fun errorFrame(message: String): String =
        "{\"kind\":\"error\",\"code\":\"bad-request\",\"requestType\":\"switch-branch\",\"message\":" + jsonString(message) + "}"

    private data class Msg(val role: String, val entryId: String, val text: String)

    private fun historyFrame(sessionId: String, msgs: List<Msg>, oldest: String, latest: String): String {
        val messages = msgs.joinToString(",") { m ->
            "{\"role\":" + jsonString(m.role) + ",\"content\":[" + textBlock(m.text) + "]" +
                ",\"timestamp\":" + (TIME_BASE + 1_000L) + "}"
        }
        val entries = msgs.joinToString(",") { jsonString(it.entryId) }
        return "{\"kind\":\"history\",\"sessionId\":" + jsonString(sessionId) +
            ",\"messages\":[$messages],\"entryIds\":[$entries],\"hasMore\":false" +
            ",\"oldestEntryId\":" + jsonString(oldest) + ",\"latestEntryId\":" + jsonString(latest) +
            ",\"isStreaming\":false,\"isCompacting\":false,\"bytes\":123}"
    }

    /** 应答 subscribe：等目标会话的 subscribe 请求**增量**出现再注入（beforeCount=
     *  selectSession 前快照；存在性匹配会被该会话的旧请求提前满足、注入被拒
     *  no-active-request → subscribe 5s 超时 connection-recycled 连坐取消在途
     *  history/tree，F06 实测）。 */
    private fun serveSubscribed(transport: FakeTransport, sessionId: String, beforeCount: Int) {
        compose.waitUntil(5_000) {
            transport.payloadsOfType("subscribe").count {
                it["sessionId"]?.jsonPrimitive?.contentOrNull == sessionId
            } > beforeCount
        }
        transport.receive("{\"kind\":\"subscribed\",\"sessionId\":" + jsonString(sessionId) + "}")
    }

    private fun textBlock(text: String): String =
        "{\"type\":\"text\",\"text\":" + jsonString(text) + "}"

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** fixture 时间（HH:mm）按 wire 真实 ISO 形状折算的本机时区 HH:mm（审查 B1）。 */
    private fun expectedClock(hhmm: String): String =
        formatClockLabel(Instant.parse("2026-09-30T$hhmm:00.000Z").epochSecond.toDouble())

    companion object {
        private const val SESSION = "f06-tree"
        private const val SESSION_B = "f06-tree-b"
        private const val LEAF_A = "t08"
        private const val LEAF_B = "b04"
        private const val CHAT_TAB = "对话"
        /** bad-request 拒绝切换分支的客户端映射文案（AndroidSharedStateHolder.switchBranchRejectionText）。 */
        private const val SWITCH_REJECT_NOTICE = "会话正忙，暂不能切换分支，稍后再试"
        private const val TREE_TAB = "tree"
        const val SAME_TEXT = "同文不同身份的树节点F06"
        const val LONG_TAIL = "横滑末端标记F06"
        const val TIME_BASE = 1_700_000_000_000L

        /** 超长 preview（≈400 字）：超出任何视口宽，末端标记只有横滑能带到。 */
        fun longPreview(): String =
            "长预览起点F06 " + buildString { repeat(34) { append("长行填充内容块；") } } + LONG_TAIL

        val HELLO =
            """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","message","history","state","attachment","tree","switch-branch"]}"""

        val SESSIONS_TWO =
            """{"kind":"sessions","items":[{"id":"$SESSION","cwd":"/tmp/synth-f06","messageCount":44,"firstMessage":"合成树F06","running":false},{"id":"$SESSION_B","cwd":"/tmp/synth-f06-b","messageCount":8,"firstMessage":"会话乙F06","running":false}]}"""

        // 亮色 token（FineCatTheme lightFineCatColors）
        const val lightLine = 0xFFE4E4E1L
        const val lightBg = 0xFFFAFAFAL
        const val lightText3 = 0xFF7F7F88L
        const val lightInk = 0xFF1A1A1CL
        const val lightSurface2 = 0xFFF0F0EEL
        /** TREE05 起浏览选中行的底色（surface3）；surface2 已不再用于树行选中。 */
        const val lightSurface3 = 0xFFE7E7E4L
        const val darkBg = 0xFF17171AL

        // 暗色 token（darkFineCatColors）
        const val darkLine = 0xFF26262BL
        const val darkInk = 0xFFF2F2F3L
    }
}
