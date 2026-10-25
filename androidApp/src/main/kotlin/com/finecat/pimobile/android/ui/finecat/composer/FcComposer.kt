package com.finecat.pimobile.android.ui.finecat.composer

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.platform.AndroidPreparedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatMotion
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.shared.protocol.GatewayContextUsage
import kotlinx.coroutines.isActive

internal fun composerBottomGapPx(imeBottom: Int, navBottom: Int, keyboardGap: Int, restGap: Int): Int =
    maxOf(imeBottom + keyboardGap, navBottom + restGap) - maxOf(imeBottom, navBottom)

/**
 * 底部输入区（P2 · composer）：胶囊（idle）→ 卡片（聚焦/有草稿，圆角 18、
 * 输入独占一行、控件落到下排）→ 卡片 + 选图条（components.md §2）。
 * 状态完全由入参决定（expanded/withShots/running/hasText），语义回调上抛，
 * 本层不创建网关/队列逻辑；发送·停止共用同一位置（同一颗按钮三种语义）。
 * [dispatchWhileRunning] 为真时，运行中且有内容仍走发送（门面把草稿吸附成
 * 待派发条），只有无内容（或压缩中等 pi 会拒发的状态）才保持方点停止。
 */
@Composable
fun FcComposer(
    text: String,
    onTextChange: (String) -> Unit,
    running: Boolean,
    expanded: Boolean,
    withShots: Boolean,
    modelName: String,
    thinkingLabel: String,
    photos: List<Uri>,
    photoThumbnails: Map<String, ImageBitmap>,
    selectedPhotoUris: Set<String>,
    onToggleShotsTray: () -> Unit,
    onTogglePhoto: (Uri) -> Unit,
    onMorePhotos: () -> Unit,
    onModelClick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "描述你的任务",
    showAttach: Boolean = true,
    preparedCount: Int = 0,
    onFocusedChange: (Boolean) -> Unit = {},
    dispatchWhileRunning: Boolean = false,
    homeStyle: Boolean = false,
    preparedImages: List<AndroidPreparedImage> = emptyList(),
    preparedPhotoUris: List<String> = emptyList(),
    preparingPhotoUris: Set<String> = emptySet(),
    onRemovePreparedImage: (Int) -> Unit = {},
    contextUsage: GatewayContextUsage? = null,
    onContextClick: (IntRect) -> Unit = {},
    onContextBoundsChange: (IntRect) -> Unit = {},
    onBoundsChange: (Rect) -> Unit = {},
    onModelBoundsChange: (IntRect) -> Unit = {},
    showDirectory: Boolean = false,
    directory: String? = null,
    directoryBranch: String? = null,
    onDirectoryClick: (() -> Unit)? = null,
    inputAssist: ComposerInputAssist? = null,
    sendReady: Boolean = true,
    sendEnabled: Boolean = true,
) {
    val colors = LocalFineCatColors.current
    val radius = LocalFineCatRadius.current
    val spacing = LocalFineCatSpacing.current
    val motion = LocalFineCatMotion.current
    val homeVerticalInset = if (homeStyle) with(LocalDensity.current) { 1.toDp() } else 0.dp
    val attachmentCount = maxOf(preparedCount, preparedImages.size, selectedPhotoUris.size)
    val showControlsRow = expanded || withShots || attachmentCount > 0
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val statusInsets = WindowInsets.statusBars
    var panelSpace by remember { mutableStateOf(280.dp) }
    val bottomGap = remember(density, imeInsets, navInsets, spacing.s2, spacing.s8) {
        object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
            override fun calculateTopPadding() = 0.dp
            override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
            // 与外层 ime/nav padding 在同次测量读取 inset；提前托住最终落点，
            // 避免 IME 归零才把 8dp 改成 32dp 所造成的下探再回弹。
            override fun calculateBottomPadding(): Dp = with(density) {
                composerBottomGapPx(
                    imeInsets.getBottom(this), navInsets.getBottom(this),
                    spacing.s2.roundToPx(), spacing.s8.roundToPx(),
                ).toDp()
            }
        }
    }
    // 焦点是瞬时状态：remember 即可，saveable 会在重建后出现假边框
    var focused by remember { mutableStateOf(false) }
    val expansionProgress by animateFloatAsState(
        targetValue = if (showControlsRow) 1f else 0f,
        animationSpec = tween(motion.base, easing = motion.baseEasing),
        label = "composerExpansion",
    )
    val horizontalInset = spacing.s10 + (spacing.s4 - spacing.s10) * expansionProgress
    val shape = remember(expansionProgress, radius.composerExpanded) {
        RoundedCornerShape(object : CornerSize {
            override fun toPx(shapeSize: Size, density: Density): Float {
                // 胶囊取当前短边的一半；向999dp插值会过早被裁成胶囊。
                val capsule = shapeSize.minDimension / 2f
                val expandedCorner = with(density) { radius.composerExpanded.toPx() }
                return capsule + (expandedCorner - capsule) * expansionProgress
            }
        })
    }
    // dock 的渐变底：底部 62% 实色 bg、向上淡出（CSS .dock linear-gradient）
    val dockBrush = Brush.verticalGradient(0f to Color.Transparent, 0.38f to colors.bg, 1f to colors.bg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(dockBrush)
            .padding(
                start = horizontalInset,
                end = horizontalInset,
            )
            .padding(bottomGap)
            // 目录行也属于输入区，外部轻点收键盘不能先移除它、吞掉目录点击。
            .onGloballyPositioned { onBoundsChange(it.boundsInWindow()) },
    ) {
        if (showDirectory && inputAssist?.panel == null) {
            FcComposerDirectory(directory, onDirectoryClick, directoryBranch)
        }

        // 与输入卡同宽，零高度，不占用 dock 的测量高度。
        BoxWithConstraints(Modifier.fillMaxWidth().height(0.dp).onGloballyPositioned {
            // 零面积的 boundsInWindow 返回 Rect.Zero；取锚点位置才能保留可用高度。
            panelSpace = with(density) { (it.positionInWindow().y - statusInsets.getTop(this)).coerceAtLeast(0f).toDp() }
        }) { inputAssist?.panel?.invoke(maxWidth, panelSpace) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("fc.composer.card")
                // 放在动画裁切之外，保留胶囊四周完整的柔和投影。
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 10.dp,
                        spread = 0.dp,
                        color = colors.shadow.copy(alpha = if (colors.isDark) 0.45f else 0.16f),
                        offset = DpOffset(0.dp, 2.dp),
                    ),
                )
                // 背景/裁切/边框使用动画后的尺寸，不能在内侧先跳到目标高度。
                .clip(shape)
                .background(colors.surface, shape)
                .then(if (focused) Modifier.border(1.dp, colors.brandLine, shape) else Modifier)
                .animateContentSize(
                    // 边距逐帧改变宽度时连续接续速度，避免反复重启tween拖住高度。
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
                .padding(
                    top = 5.dp + 4.dp * expansionProgress + homeVerticalInset,
                    bottom = 5.dp + 2.dp * expansionProgress + homeVerticalInset,
                    start = 6.dp + 2.dp * expansionProgress,
                    end = 4.dp + 4.dp * expansionProgress,
                ),
        ) {
            if (!withShots && (preparedImages.isNotEmpty() || preparingPhotoUris.isNotEmpty())) {
                FcPreparedImageStrip(preparedImages, preparingPhotoUris, onRemovePreparedImage, onTogglePhoto)
            } else if (preparedCount > 0 && !withShots) {
                Text(
                    text = "已添加 $preparedCount 张图片",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.text3,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            if (withShots) {
                FcShotStrip(
                    photos = photos,
                    photoThumbnails = photoThumbnails,
                    selected = selectedPhotoUris,
                    preparedImages = preparedImages,
                    preparedPhotoUris = preparedPhotoUris,
                    preparingPhotoUris = preparingPhotoUris,
                    onRemovePreparedImage = onRemovePreparedImage,
                    onTogglePhoto = onTogglePhoto,
                    onMorePhotos = onMorePhotos,
                    modifier = Modifier
                        .fillMaxWidth()
                        // .shots margin-bottom=3，加 expanded flex 的 2dp 行间距。
                        .padding(bottom = 5.dp),
                )
            }
            // 文本控件固定在同一个组合位置；展开只改变周边控件，保留输入连接和选区。
            Row(
                modifier = if (showControlsRow) Modifier.testTag("fc.composer.inputRow") else Modifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!showControlsRow && showAttach) {
                    FcComposerIconButton(
                        onClick = onToggleShotsTray,
                        icon = FineCatIcons.Image,
                        contentDescription = "添加图片",
                    )
                }
                FcComposerTextField(
                    text = text,
                    onTextChange = onTextChange,
                    editorValue = inputAssist?.value,
                    onEditorValueChange = inputAssist?.onChange,
                    inputVisualTransformation = inputAssist?.transformation ?: VisualTransformation.None,
                    onInputKey = inputAssist?.onKey ?: { false },
                    placeholder = placeholder,
                    minHeight = 22.dp + 25.dp * expansionProgress,
                    minLines = if (showControlsRow) 2 else 1,
                    onFocusChanged = {
                        focused = it
                        onFocusedChange(it)
                    },
                    modifier = Modifier
                        .weight(1f)
                        // 展开文字区至少两行，触控和高度随文字行数一起保留。
                        .padding(start = 4.dp + 4.dp * expansionProgress),
                )
                if (!showControlsRow) {
                    FcSendButton(
                        running = running,
                        hasText = text.isNotBlank() || attachmentCount > 0,
                        onSend = onSend,
                        onStop = onStop,
                        dispatchWhileRunning = dispatchWhileRunning,
                        sendReady = sendReady,
                        enabled = sendEnabled,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (showControlsRow) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showAttach) {
                        FcComposerIconButton(
                            onClick = onToggleShotsTray,
                            icon = FineCatIcons.Image,
                            contentDescription = if (!withShots && attachmentCount > 0) "添加图片，已选 $attachmentCount 张" else "添加图片",
                            badgeCount = if (withShots) 0 else attachmentCount,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        FcModelChip(
                            modelName = modelName,
                            thinkingLabel = thinkingLabel,
                            onClick = onModelClick,
                            onBoundsChange = onModelBoundsChange,
                            modifier = Modifier.padding(start = 2.dp).widthIn(max = 200.dp),
                        )
                    }
                    FcContextRing(usage = contextUsage, onClick = onContextClick, onBoundsChange = onContextBoundsChange)
                    FcSendButton(
                        running = running,
                        hasText = text.isNotBlank() || attachmentCount > 0,
                        onSend = onSend,
                        onStop = onStop,
                        dispatchWhileRunning = dispatchWhileRunning,
                        sendReady = sendReady,
                        enabled = sendEnabled,
                    )
                }
            }
        }
    }
}

/** 输入框（.composer textarea）：15sp、lh 1.55、max 高 120（约 5 行）、占位 text-3；
 *  展开态至少两行，胶囊态一行。 */
@Composable
private fun FcComposerTextField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    minHeight: Dp,
    minLines: Int,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    editorValue: TextFieldValue? = null,
    onEditorValueChange: ((TextFieldValue) -> Unit)? = null,
    inputVisualTransformation: VisualTransformation = VisualTransformation.None,
    onInputKey: (KeyEvent) -> Boolean = { false },
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    var fieldFocused by remember { mutableStateOf(false) }
    // 旧调用方仍传 String；保留它的选区/组合状态，不在每次重组重新创建输入连接。
    var localValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = editorValue ?: if (localValue.text == text) localValue else TextFieldValue(text, TextRange(text.length))
    SideEffect { if (editorValue == null && localValue != value) localValue = value }
    LaunchedEffect(interaction, view) {
        // 仅文本点击触发。等焦点处理完成，再绑定真实编辑连接；不依赖已显示标志。
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Release) {
                view.post {
                    if (fieldFocused && view.isAttachedToWindow && view.hasWindowFocus()) {
                        runCatching {
                            if (!view.hasFocus()) view.requestFocus()
                            val insets = ViewCompat.getRootWindowInsets(view)
                            val imeHeight = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                            val navHeight = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                            val inputMethod = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as InputMethodManager
                            // 标志为可见但只有导航区高度时，也必须重新绑定编辑连接。
                            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) != true || imeHeight <= navHeight) {
                                inputMethod.restartInput(view)
                            }
                            inputMethod.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        }.onFailure { Log.w("FcComposer", "Could not activate text input", it) }
                    }
                }
            }
        }
    }
    BasicTextField(
        value = value,
        onValueChange = {
            if (onEditorValueChange != null) onEditorValueChange(it) else {
                localValue = it
                onTextChange(it.text)
            }
        },
        visualTransformation = inputVisualTransformation,
        textStyle = type.bubble.copy(color = colors.text),
        cursorBrush = SolidColor(colors.brand),
        minLines = minLines,
        maxLines = 5,
        interactionSource = interaction,
        modifier = modifier
            .onPreviewKeyEvent(onInputKey)
            .fillMaxWidth()
            .heightIn(min = minHeight, max = 120.dp)
            .onFocusChanged {
                fieldFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .testTag("fc.composer.input"),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
                contentAlignment = if (minLines > 1) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = type.bubble,
                        color = colors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** 已选附件来自真实待发列表，与系统选择器或最近照片的来源无关。 */
@Composable
private fun FcPreparedImageStrip(
    images: List<AndroidPreparedImage>,
    preparingUris: Set<String>,
    onRemove: (Int) -> Unit,
    onTogglePhoto: (Uri) -> Unit,
) {
    val colors = LocalFineCatColors.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 3.dp)
            .testTag("fc.preparedStrip"),
        // 48dp 命中区内居中放 44dp 图，52dp 节距保持图片之间 8dp 的视觉间距。
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        images.forEachIndexed { index, image ->
            key(image.outgoing.attachmentId) {
                FcPickedPhoto("移除图片 ${index + 1}", { onRemove(index) }) {
                    FcPreparedPhoto(image, Modifier.size(44.dp))
                }
            }
        }
        preparingUris.forEach { uri ->
            key(uri) {
                FcPickedPhoto("取消正在添加的图片", { onTogglePhoto(Uri.parse(uri)) }) {
                    Box(Modifier.size(44.dp).background(colors.surface2), contentAlignment = Alignment.Center) {
                        Text("…", color = colors.text3)
                    }
                }
            }
        }
    }
}

@Composable
private fun FcPickedPhoto(description: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalFineCatColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.size(48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
            content()
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(15.dp)
                    .background(colors.ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", fontSize = 10.sp, lineHeight = 10.sp, color = colors.onInk)
            }
        }
    }
}

@Composable
private fun FcPreparedPhoto(image: AndroidPreparedImage, modifier: Modifier) {
    val colors = LocalFineCatColors.current
    val thumbnail by produceState<ImageBitmap?>(null, image.outgoing.attachmentId, image.outgoing.base64Data) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = android.util.Base64.decode(image.outgoing.base64Data, android.util.Base64.NO_WRAP)
                var sample = 1
                while (image.width / sample > 256 || image.height / sample > 256) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(colors.surface2), contentAlignment = Alignment.Center) {
        thumbnail?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
            ?: Icon(FineCatIcons.Image, null, tint = colors.text3)
    }
}

/** composer 内的图标按钮：视觉 30×30/图标 18（components.md §4）；
 *  布局槽保持原型高度，触控区 48×48 垂直溢出胶囊（上下无相邻交互件，不重叠）。 */
@Composable
private fun FcComposerIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    badgeCount: Int = 0,
) {
    val colors = LocalFineCatColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .scale(if (pressed) 0.94f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(18.dp))
                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).offset(2.dp, (-2).dp)
                            .height(14.dp).widthIn(min = 14.dp).background(colors.brand, CircleShape)
                            .padding(horizontal = 3.dp).testTag("fc.attachmentBadge"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$badgeCount", color = Color.White, fontSize = 9.sp, lineHeight = 9.sp)
                    }
                }
            }
        }
    }
}

/** 模型胶囊（.model-btn）：Effective 值（模型名 · 当前档 + 细箭头），点开模型 sheet。 */
@Composable
fun FcModelChip(
    modelName: String,
    thinkingLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBoundsChange: (IntRect) -> Unit = {},
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(modelName) }
        withStyle(SpanStyle(color = colors.text3)) { append(" · ") }
        append(thinkingLabel)
    }
    // 文字视觉高约27；触控盒48，与相邻图标同高，水平留距不重叠。
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .testTag("finecat.modelChip")
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onBoundsChange(IntRect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
            }
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FcMarqueeLabel(
            text = label, style = type.xs, color = colors.text2, pressed = pressed,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = FineCatIcons.Down,
            contentDescription = null,
            tint = colors.text3,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(13.dp),
        )
    }
}

/**
 * 发送位动作判据（纯逻辑，供 JVM 测试）：运行中且本次点击没有可派发内容时才走停止；
 * 忙时带文本/图片（hasText 含 preparedCount>0）走发送——门面把草稿吸附成待派发条。
 * 压缩中等 pi 会拒 prompt 的状态不传 dispatchWhileRunning，保持方点停止语义。
 */
internal fun sendButtonStops(
    running: Boolean,
    hasText: Boolean,
    dispatchWhileRunning: Boolean
): Boolean = running && !(dispatchWhileRunning && hasText)

/**
 * 发送/停止共用黑底白图标：空闲箭头、运行中无可派发内容时方点停止。
 * 运行中且已有内容且 [dispatchWhileRunning] 时点的是发送（草稿吸附成待派发条），
 * 按原型无内容才保持方点停止；按钮形态随实际动作变，避免停止图标执行发送。
 * 500ms 停止冷却在门面 abortCurrent 内。
 */
@Composable
fun FcSendButton(
    running: Boolean,
    hasText: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    dispatchWhileRunning: Boolean = false,
    sendReady: Boolean = true,
    enabled: Boolean = true,
) {
    val stopping = sendButtonStops(running, hasText, dispatchWhileRunning)
    val connectionBlocked = !enabled || (!stopping && !sendReady)
    val content = Color.White
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val description = if (stopping) "停止生成" else "发送"
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 32.dp), // 布局槽保持原型高度
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = interaction, indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = if (stopping) onStop else onSend,
                )
                .semantics {
                    this.contentDescription = description
                    this.stateDescription = when {
                        !enabled -> "不可发送"
                        connectionBlocked -> "尚未连接"
                        else -> ""
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .scale(if (pressed) 0.92f else 1f)
                .background(if (connectionBlocked) LocalFineCatColors.current.text3 else Color.Black, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (stopping) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(content, RoundedCornerShape(3.dp)),
                )
            } else {
                Icon(
                    imageVector = FineCatIcons.SendUp,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        }
    }
}

/**
 * 选图条（.shots）：横向可滑的最近 10 张 + 第 11 格「更多」；点格子多选打勾，
 * 不收起、不插入。真实照片来自门面 recentPhotos/MediaStore（P4c），
 * 缩略图由 recentPhotoThumbnails 提供；选中态以 uri 记录。
 * 右缘 18dp 渐隐线索（原型 `.shots` 的 mask-image linear-gradient(to left,
 * transparent, #000 18px)，styles.css:1194）：内容溢出时给出"还能横滑"的提示；
 * 卡面底色均匀，用 surface 色渐变覆盖与遮罩等效。
 */
@Composable
fun FcShotStrip(
    photos: List<Uri>,
    photoThumbnails: Map<String, ImageBitmap>,
    selected: Set<String>,
    preparedImages: List<AndroidPreparedImage> = emptyList(),
    preparedPhotoUris: List<String> = emptyList(),
    preparingPhotoUris: Set<String> = emptySet(),
    onRemovePreparedImage: (Int) -> Unit = {},
    onTogglePhoto: (Uri) -> Unit,
    onMorePhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("fc.shotStrip"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        photos.forEachIndexed { index, photoUri ->
            val key = photoUri.toString()
            val isSelected = selected.contains(key)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(LocalFineCatRadius.current.shot))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.surface3,
                                colors.text3.copy(alpha = 0.35f),
                            ),
                        ),
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, colors.brand, RoundedCornerShape(LocalFineCatRadius.current.shot))
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.Button) { onTogglePhoto(photoUri) }
                    .semantics {
                        this.contentDescription = "最近照片 ${index + 1}"
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                photoThumbnails[key]?.let { thumbnail ->
                    androidx.compose.foundation.Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(56.dp),
                    )
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .size(16.dp)
                            .background(colors.brand, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FineCatIcons.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
        }
        // 非最近的系统选择器图片也留在同一条内可取消，索引保持为原待发列表索引。
        val recentUris = photos.map(Uri::toString).toSet()
        preparedImages.forEachIndexed { index, image ->
            if (preparedPhotoUris.getOrNull(index) !in recentUris) {
                key(image.outgoing.attachmentId) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(LocalFineCatRadius.current.shot))
                            .clickable(role = Role.Button) { onRemovePreparedImage(index) }
                            .semantics { contentDescription = "移除图片 ${index + 1}"; this.selected = true },
                    ) {
                        FcPreparedPhoto(image, Modifier.size(56.dp))
                        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(16.dp)
                            .background(colors.brand, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(FineCatIcons.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }
        }
        (preparingPhotoUris - recentUris).forEach { uri ->
            key(uri) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(LocalFineCatRadius.current.shot))
                        .background(colors.surface2).clickable(role = Role.Button) { onTogglePhoto(Uri.parse(uri)) }
                        .semantics { contentDescription = "取消正在添加的图片"; this.selected = true },
                    contentAlignment = Alignment.Center,
                ) { Text("…", color = colors.text3) }
            }
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(LocalFineCatRadius.current.shot))
                .background(colors.surface2)
                .clickable(role = Role.Button, onClick = onMorePhotos)
                .semantics { this.contentDescription = "打开相册" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Icon(
                    imageVector = FineCatIcons.Image,
                    contentDescription = null,
                    tint = colors.text3,
                    modifier = Modifier.size(16.dp),
                )
                Text(text = "更多", style = type.xs2, color = colors.text3)
            }
        }
        }
        // 右缘渐隐线索：matchParentSize 跟随内容行的实际高度（不能 fillMaxHeight——
        // 会把外层 wrap-content Box 撑满可用高度）；只画右缘 18dp，无 pointerInput
        // 不拦截触摸；无溢出时该区域是空白卡面，不可见。
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val w = 18.dp.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, colors.surface),
                            startX = size.width - w,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - w, 0f),
                        size = Size(w, size.height),
                    )
                },
        )
    }
}

@Composable
private fun FcComposerDirectory(directory: String?, onDirectoryClick: (() -> Unit)?, branch: String?) {
    val colors = LocalFineCatColors.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(onDirectoryClick?.let { click ->
                        Modifier.clickable(role = Role.Button, onClick = click)
                    } ?: Modifier)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp)
                    .testTag("finecat.composerDirectory"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(FineCatIcons.Folder, null, tint = colors.text3, modifier = Modifier.size(14.dp))
                if (directory == null) {
                    Text("待选择目录", style = LocalFineCatType.current.xs, color = colors.text3,
                        modifier = Modifier.weight(1f))
                } else {
                    val path = directory.trimEnd('/').ifEmpty { "/" }
                    val leaf = path.substringAfterLast('/').ifEmpty { "/" }
                    val parent = path.removeSuffix(leaf)
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (parent.isNotEmpty()) {
                            Text(parent, style = LocalFineCatType.current.xs.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                        }
                        Text(leaf, style = LocalFineCatType.current.xs.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (branch != null) {
                    Row(Modifier.weight(0.52f, fill = false).background(colors.surface2, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(FineCatIcons.Branch, null, tint = colors.text3, modifier = Modifier.size(12.dp))
                        Text(branch, style = LocalFineCatType.current.xs, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("finecat.worktreeChip"))
                    }
                } else if (onDirectoryClick != null) {
                    Icon(FineCatIcons.Right, null, tint = colors.text3, modifier = Modifier.size(16.dp))
                }
            }
}
