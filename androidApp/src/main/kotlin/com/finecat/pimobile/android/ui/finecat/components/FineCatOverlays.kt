package com.finecat.pimobile.android.ui.finecat.components

import android.view.View
import android.view.WindowManager
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatMotion
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

private val LocalSheetVisibility = staticCompositionLocalOf<MutableTransitionState<Boolean>?> { null }

/** 关闭标志先驱动退场；共享动画结束后才移除面板及其草稿状态。 */
@Composable
internal fun FcSheetHost(visible: Boolean, content: @Composable () -> Unit) {
    val parent = LocalSheetVisibility.current
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible && (parent?.targetState ?: true)
    if (visibility.targetState || visibility.currentState || !visibility.isIdle) {
        CompositionLocalProvider(LocalSheetVisibility provides visibility, content = content)
    }
}

/** 关闭时保留最后的非空参数，避免id清空或删除行后退场内容突然消失。 */
@Composable
internal fun <T : Any> FcSheetHost(value: T?, content: @Composable (T) -> Unit) {
    val retained = remember { mutableStateOf(value) }
    SideEffect { if (value != null) retained.value = value }
    val item = value ?: retained.value
    FcSheetHost(visible = value != null) { if (item != null) content(item) }
}

/**
 * 底部弹卡（.sheet）：max-height 74%、顶圆角 20、scrim + 上滑动画（--t-sheet 340ms）。
 * 层级语义 Drawer < Sheet < Popover：Sheet 盖在页面内容上，Popover 用独立
 * Popup window 悬在其上。内容区在长内容时占满剩余高度、内部滚动区能拿到高度约束——
 * 对应原型 `.sheet-body` flex + min-height:0（components.md §7 记录过的滚动 bug）。
 */
@Composable
fun FcSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    /** 标题后的状态标签（原型 `sheet-head h3` 内 `stTag`：完成/进行中/有失败）；缺省不占位。 */
    statusTag: Pair<String, FcTagStyle>? = null,
    /** 二级弹卡标题左侧的返回箭头（原型 `.mv-back`）：非空则可返回上一张卡。 */
    onBack: (() -> Unit)? = null,
    showDone: Boolean = false,
    doneText: String = "完成",
    /** 仅获批头部确认键可增加布局高；一般文本按钮保留原型自然高。 */
    doneMinHeight: Dp = 0.dp,
    bottomPadding: Dp = 24.dp,
    onDone: () -> Unit = {},
    /** 点遮罩可确认草稿；系统返回仍走 onDismiss，明确取消不变。 */
    onOutsideClick: (() -> Unit)? = null,
    /** 编辑长表单可提高上限；其余弹层保留原高度。 */
    maxHeightFraction: Float = 0.74f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFineCatColors.current
    val motion = LocalFineCatMotion.current
    val spacing = LocalFineCatSpacing.current
    val type = LocalFineCatType.current
    // 条件挂载的弹卡首次传入 true 也必须从屏幕下方进入。
    val hostVisibility = LocalSheetVisibility.current
    val visibilityState = hostVisibility ?: remember { MutableTransitionState(false) }
    if (hostVisibility == null) visibilityState.targetState = visible
    val interactive = visible && visibilityState.targetState
    val transition = rememberTransition(visibilityState, label = "FineCatSheet")
    if (interactive || visibilityState.currentState || !visibilityState.isIdle) {
        BackHandler { if (interactive) onDismiss() }
    }
    Box(modifier = modifier.fillMaxSize()) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = fadeIn(tween(motion.base, easing = motion.baseEasing)),
            exit = fadeOut(tween(motion.base, easing = motion.baseEasing)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.overlay)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { if (interactive) (onOutsideClick ?: onDismiss)() }
                    .semantics { this.contentDescription = if (onOutsideClick != null) "确认并关闭" else "关闭" }
                    .testTag("finecat.sheetScrim"),
            )
        }
        transition.AnimatedVisibility(
            visible = { it },
            enter = slideInVertically(tween(motion.sheet, easing = motion.baseEasing)) { it },
            exit = slideOutVertically(tween(motion.sheet, easing = motion.baseEasing)) { it },
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val sheetMaxHeight = maxHeight * maxHeightFraction
                val sheetShape = RoundedCornerShape(
                    topStart = LocalFineCatRadius.current.sheetTop,
                    topEnd = LocalFineCatRadius.current.sheetTop,
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                        // sheet 里有输入框的（网关地址、配对码、目录搜索、模型搜索）：
                        // 键盘弹起时整张卡上移，否则底部输入框被键盘盖住。统一在这里加，
                        // 各 sheet 不必各自处理。
                        .imePadding()
                        .shadow(FineCatElevation.Floating, sheetShape, clip = true, ambientColor = colors.shadow, spotColor = colors.shadow)
                        .background(colors.surface, sheetShape)
                        // 卡内留白也属于卡片，不能把点击漏给下面的保存遮罩。
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .testTag("finecat.sheetCard")
                        .semantics { if (title != null) paneTitle = title }
                        // 内容避开导航区，背景仍画到屏幕底；放在背景外会留下透明空隙。
                        .navigationBarsPadding(),
                ) {
                    // grab：38×4 圆条
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = spacing.s3, bottom = spacing.s2)
                            .size(width = 38.dp, height = 4.dp)
                            .background(colors.surface3, CircleShape),
                    )
                    if (title != null || onBack != null) {
                        Row(
                            modifier = Modifier
                                .testTag("finecat.sheetHeader")
                                .fillMaxWidth()
                                .padding(start = spacing.s5, end = spacing.s5, top = spacing.s2, bottom = spacing.s3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onBack != null) {
                                // .mv-back：15px 视觉槽位；48dp 触控由 clickable 外扩。
                                Box(
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clip(RoundedCornerShape(LocalFineCatRadius.current.chip))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = onBack,
                                        )
                                        .semantics { this.contentDescription = "返回" }
                                        .testTag("finecat.sheetBack"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = FineCatIcons.Back,
                                        contentDescription = null,
                                        tint = colors.text3,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(spacing.s2))
                            }
                            Text(
                                text = title.orEmpty(),
                                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.text,
                            )
                            statusTag?.let { (tagText, tagStyle) ->
                                Spacer(modifier = Modifier.width(8.dp))
                                FcStatusTag(text = tagText, style = tagStyle)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (showDone) {
                                FcTextButton(
                                    text = doneText,
                                    onClick = onDone,
                                    modifier = Modifier.heightIn(min = doneMinHeight).testTag("finecat.sheetDone"),
                                )
                            }
                        }
                    }
                    // weight(1f, fill=false)：短内容收起，长内容占满剩余并给内部滚动区边界
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        content()
                    }
                    Spacer(modifier = Modifier.height(bottomPadding))
                }
            }
        }
        if (!interactive && (visibilityState.currentState || !visibilityState.isIdle)) {
            // 退场中的旧表单不能重复保存，也不能把触摸漏到下面的页面。
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ).testTag("finecat.sheetClosing"))
        }
    }
}

/**
 * 贴锚点小浮层（.mpop）：min 132 / max 280 宽、max 340 高、surface-2 底、
 * 圆角 12、阴影 --sh-3，内容可滚动。独立 Popup window 天然盖住 Sheet；
 * focusable 弹窗外点击/Back 触发 onDismissRequest。
 *
 * 需在根级调用（Popup 的 composition 点即窗口左上）；`anchorBoundsInWindow`
 * 用 `onGloballyPositioned { it.boundsInWindow() }` 从锚点取。
 */
@Composable
fun FcPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBoundsInWindow: IntRect,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    elevation: Dp = FineCatElevation.Floating,
    onOutsideClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFineCatColors.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    if (!expanded || anchorBoundsInWindow.width == 0 || anchorBoundsInWindow.height == 0) return
    val insets = WindowInsets.safeDrawing
    // 软键盘弹出时它才是真正挡住浮层的东西，而 safeDrawing 不含 IME：只用 safeDrawing 的话，
    // 浮层会落在键盘后面，底部的选项点不到（F11 取值浮层带输入框才会触发）。
    // 取二者较大值：没有键盘时 ime=0，行为与原来完全一致。
    // 注：这是双层防护的 app 侧一层——不接收 IME insets 的宿主里该值为 0，此时落位由下方
    // SideEffect 给浮层窗口设的 SOFT_INPUT_ADJUST_RESIZE（WMS clamp）承担；F11 实测生效的
    // 是 WMS 那层（浮层 frame 底边=WMS display 下界=键盘触摸区顶边），app 侧作为前挡保留。
    val imeBottom = WindowInsets.ime.getBottom(density)
    val margin = with(density) { 8.dp.roundToPx() }
    val safeBounds = remember(anchorBoundsInWindow, windowSize, direction, imeBottom) {
        val left = (insets.getLeft(density, direction) + margin).coerceIn(0, windowSize.width)
        val top = (insets.getTop(density) + margin).coerceIn(0, windowSize.height)
        IntRect(
            left, top,
            (windowSize.width - insets.getRight(density, direction) - margin).coerceAtLeast(left),
            (windowSize.height - maxOf(insets.getBottom(density), imeBottom) - margin).coerceAtLeast(top),
        )
    }
    val gap = with(density) { 6.dp.roundToPx() }
    val handleBackSeparately = onOutsideClick != null && focusable
    Popup(
        popupPositionProvider = remember(anchorBoundsInWindow, safeBounds, gap) {
            FcPopoverPositionProvider(anchorBoundsInWindow, safeBounds, gap)
        },
        onDismissRequest = onOutsideClick ?: onDismissRequest,
        // 兼容参数浮层不能抢输入框焦点，否则 Android 会把仍在输入的 IME 收起；
        // 需要在浮层内继续编辑的 F11 浮层保留默认的可聚焦行为。
        properties = PopupProperties(focusable = focusable, dismissOnBackPress = !handleBackSeparately),
    ) {
        val focusRequester = remember { FocusRequester() }
        // 有编辑草稿时区分外点确认与Back取消；窗口内的根焦点承接硬件返回。
        LaunchedEffect(handleBackSeparately) {
            if (handleBackSeparately) focusRequester.requestFocus()
        }
        val backModifier = if (handleBackSeparately) Modifier
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyUp) onDismissRequest()
                    true
                } else false
            }.focusRequester(focusRequester).focusable() else Modifier
        val popupView = LocalView.current
        val currentDismiss by rememberUpdatedState(onDismissRequest)
        // API33+的返回手势走窗口dispatcher，不经过上面的硬件按键处理。
        DisposableEffect(popupView, handleBackSeparately) {
            if (handleBackSeparately && Build.VERSION.SDK_INT >= 33) {
                val view = popupView.parent as? View ?: popupView
                var dispatcher: OnBackInvokedDispatcher? = null
                val callback = OnBackInvokedCallback { currentDismiss() }
                fun unregister() {
                    dispatcher?.unregisterOnBackInvokedCallback(callback)
                    dispatcher = null
                }
                fun register() {
                    val next = view.findOnBackInvokedDispatcher() ?: return
                    if (next === dispatcher) return
                    unregister()
                    next.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                    dispatcher = next
                }
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = register()
                    override fun onViewDetachedFromWindow(v: View) = unregister()
                }
                view.addOnAttachStateChangeListener(listener)
                if (view.isAttachedToWindow) register()
                onDispose { view.removeOnAttachStateChangeListener(listener); unregister() }
            } else onDispose { }
        }
        SideEffect {
            val popupWindowView = popupView.parent as? View ?: return@SideEffect
            val params = popupWindowView.layoutParams as? WindowManager.LayoutParams ?: return@SideEffect
            if (params.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                (popupWindowView.context.getSystemService(WindowManager::class.java) as WindowManager)
                    .updateViewLayout(popupWindowView, params)
            }
        }
        Column(
            modifier = modifier.then(backModifier)
                .widthIn(min = 132.dp, max = 280.dp)
                .width(IntrinsicSize.Max)
                .heightIn(max = 340.dp)
                .shadow(
                    elevation, RoundedCornerShape(LocalFineCatRadius.current.inner),
                    ambientColor = colors.shadow, spotColor = colors.shadow,
                )
                .background(colors.surface2, RoundedCornerShape(LocalFineCatRadius.current.inner))
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

private class FcPopoverPositionProvider(
    private val anchor: IntRect,
    private val safe: IntRect,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // 根级 Popup 的 composition 点在窗口左上，返回的 offset 即窗口坐标。
        val x = anchor.left.coerceIn(safe.left, (safe.right - popupContentSize.width).coerceAtLeast(safe.left))
        val below = anchor.bottom + gap
        val above = anchor.top - gap - popupContentSize.height
        val preferred = when {
            below + popupContentSize.height <= safe.bottom -> below
            above >= safe.top -> above
            else -> if (below >= above) below else above
        }
        // 「贴着锚点翻到上方」之后仍要收进安全区：锚点本身被键盘盖住时（soft IME 弹起、
        // 锚点所在内容行在可视区之下），above 依然落在键盘后面，必须夹一次。
        // 三个分支统一夹 safe 区是有意收敛：below 分支在锚点被滚进状态栏（anchor.bottom 已
        // 低于 safe.top）时也会把浮层拉回可视区，与锚点脱开但保证可见可点。
        val y = preferred.coerceIn(safe.top, (safe.bottom - popupContentSize.height).coerceAtLeast(safe.top))
        return IntOffset(x, y)
    }
}

/** 一行一个候选（.mi）：等宽 12sp、7/16 内边距、高至少 48dp；on=品牌色；
 *  oth=顶部单根发丝线的次级动作（UI 字体，对应 CSS .mi.oth）。 */
@Composable
fun FcPopoverItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    secondary: Boolean = false,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    val style = when {
        // .mi.oth 用继承的 UI 字体，不保持等宽
        secondary -> type.xs
        else -> type.xs.copy(fontFamily = type.mono, fontWeight = if (selected) FontWeight(550) else FontWeight.Normal)
    }
    val color = when {
        secondary -> colors.text3
        selected -> colors.brand
        else -> colors.text
    }
    val lineColor = colors.line
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (secondary) Modifier.padding(top = spacing.s1) else Modifier)
            .drawBehind {
                if (secondary) {
                    // .mi.oth 的 border-top：单根顶线，不是四边框
                    drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                }
            }
            .clip(RoundedCornerShape(LocalFineCatRadius.current.chip))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(
                // 单字进位造成 mono 自然宽比 CSS 多约2.6dp；左右各收1dp，保留完整标签。
                start = spacing.s4 - 1.dp,
                end = spacing.s4 - 1.dp,
                top = if (secondary) spacing.s2 else 7.dp,
                bottom = 7.dp,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
    }
}
