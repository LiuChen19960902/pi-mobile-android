package com.finecat.pimobile.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.domain.SessionGrouping
import com.finecat.pimobile.shared.domain.SessionSummary

/** 空白首页的品牌文案默认值；用户自定义 heroTitle/heroTagline 时优先用自定义。 */
internal const val DEFAULT_HERO_TITLE = "FineCat"
internal const val DEFAULT_HERO_TAGLINE = "探索未至之境"

/** 品牌只属于真正的空白首页，加载/错误状态不能与欢迎内容叠放。 */
internal fun isBlankHome(stateHolder: AndroidSharedStateHolder): Boolean {
    val snapshot = stateHolder.snapshot
    return shouldShowHomeBrand(
        hasSession = snapshot.selectedSessionId != null,
        hasContent = snapshot.conversation.isNotEmpty(),
        hasPreview = stateHolder.outgoingPreviews.any {
            it.isVisibleFor(snapshot.selectedSessionId, stateHolder.newSessionEpoch)
        },
        isLoading = snapshot.selectedHistoryIsLoading,
        hasError = snapshot.selectedHistoryError != null
    )
}

internal fun shouldShowHomeBrand(
    hasSession: Boolean,
    hasContent: Boolean,
    hasPreview: Boolean,
    isLoading: Boolean,
    hasError: Boolean
): Boolean = !hasSession && !hasContent && !hasPreview && !isLoading && !hasError

/** 首页品牌的排布档位：键盘或低高度时收起/缩小，把空间让给输入区。 */
internal enum class HomeBrandLayout { Full, Compact, Hidden }

/**
 * 品牌排布档位的判定（纯函数，JVM 可测）。
 *
 * [viewportHeightDp] 是会话页内容区高度（顶栏与系统栏已被 Scaffold 扣掉），
 * [composerHeightDp] 是首页底部输入区（含工作区行、吸附条）的实测高度——两者
 * 都取 ConversationPage 的实际测量值，不写死。剩余高度放不下品牌就收起，
 * 勉强放得下用缩小档；键盘弹出时一律收起。
 */
internal fun homeBrandLayout(
    viewportHeightDp: Float,
    composerHeightDp: Float,
    imeVisible: Boolean,
    compactMinDp: Float = 120f,
    fullMinDp: Float = 260f
): HomeBrandLayout {
    if (imeVisible) return HomeBrandLayout.Hidden
    // 顶栏/系统栏已从 viewport 里扣过，这里只再扣输入区，避免重复留白。
    val freeDp = viewportHeightDp - composerHeightDp
    return when {
        freeDp < compactMinDp -> HomeBrandLayout.Hidden
        freeDp < fullMinDp -> HomeBrandLayout.Compact
        else -> HomeBrandLayout.Full
    }
}

/** 展示用的工作区名：取目录末段；没有路径时给占位文案。 */
internal fun workspaceDisplayName(path: String?): String {
    if (path.isNullOrBlank()) return "选择工作区"
    return path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
}

/** 工作区选项：path 是选中后写回 holder 的 cwd，label 是展示名。 */
internal data class WorkspaceOption(val path: String, val label: String)

/**
 * 最近工作区：按已有会话的 cwd 聚合，组间按最新活动时间倒序（复用会话分组的
 * 口径，重名目录自动补父级段）。跳过空 cwd 与本地隐藏目录——整组被隐藏的目录
 * 不该从「最近工作区」这条后门漏回界面。
 */
internal fun recentWorkspaceOptions(
    sessions: List<SessionSummary>,
    hiddenDirectoryPaths: List<String>,
    limit: Int = 6
): List<WorkspaceOption> =
    SessionGrouping.group(sessions.filterNot { it.cwd.orEmpty() in hiddenDirectoryPaths })
        .mapNotNull { group -> group.cwd?.takeIf { it.isNotBlank() }?.let { it to group.label } }
        .take(limit)
        .map { (path, label) -> WorkspaceOption(path, label) }

/** 品牌点击区（含内部 padding）的最大宽度：长标题/标语在这里省略，不再顶到屏幕两侧。 */
internal val HomeBrandHitMaxWidth = 280.dp

/**
 * 品牌块外侧的排版留宽（不可点）：宽度 ≤280dp（分屏/窄屏）时点击区已贴到边，
 * 这层 16dp 保证长标题下两侧仍有不可点的留白。
 */
internal val HomeBrandSideGutter = 16.dp

/**
 * 空白首页的品牌区：logo 居中，下方标题/标语/版本号。
 *
 * 点击热区只有中央内容块（logo/标题/标语/版本 + 少量 padding）：外层先挖掉 16dp
 * 不可点留宽，再把热区 widthIn(max=280dp) 包到内容宽。先前整行 fillMaxWidth 可点，
 * 在窄屏上点输入框上方任意空白都会误进自定义文案（沿用旧首页的 hero 偏好，
 * 不新增设置项）。
 */
@Composable
internal fun HomeBrand(
    title: String,
    tagline: String,
    versionName: String,
    layout: HomeBrandLayout,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (layout == HomeBrandLayout.Hidden) return
    val onHero = piWorkspaceForeground()
    val compact = layout == HomeBrandLayout.Compact
    // 外层只负责排版：保留调用方传入的居中与 bottom padding（避让输入区），挖掉
    // 两侧留宽，再把宽度包到内容宽；不 fillMaxWidth，否则热区又铺满整行。
    Column(
        modifier = modifier.padding(horizontal = HomeBrandSideGutter).wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = HomeBrandHitMaxWidth)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("home-brand-hit"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.pi_logo),
                contentDescription = "PiMobile",
                colorFilter = ColorFilter.tint(if (LocalPiDark.current) Color.White else PiColors.Ocean),
                modifier = Modifier.size(if (compact) 32.dp else 52.dp).testTag("home-brand-logo")
            )
            Text(
                title,
                color = onHero,
                fontSize = if (compact) 18.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier.semantics { heading() }.testTag("home-brand-title")
            )
            Text(
                tagline,
                color = onHero.copy(alpha = 0.55f),
                fontSize = if (compact) 12.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                "v$versionName",
                color = onHero.copy(alpha = 0.34f),
                fontSize = if (compact) 10.sp else 11.sp,
                modifier = Modifier.testTag("home-brand-version")
            )
        }
    }
}

/** 输入框上方的一行轻量工作区指示：`工作区名 ▾`，点开底部选择面板。
 *  已绑定会话传 enabled=false 作只读 cwd 展示（不可点、不改会话），禁用态
 *  保持 0.85 可读而非灰暗，行高与首页一致，发送绑定前后底部高度不跳。 */
@Composable
internal fun HomeWorkspaceLine(
    directoryPath: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onSurface = piWorkspaceForeground()
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.85f)
            .testTag("home-workspace-line"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_folder_outline),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            colorFilter = ColorFilter.tint(onSurface.copy(alpha = 0.55f))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            if (!enabled && directoryPath.isNullOrBlank()) "当前工作区" else workspaceDisplayName(directoryPath),
            color = onSurface.copy(alpha = 0.72f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(4.dp))
        Text("▾", color = onSurface.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}

/**
 * 工作区选择面板：最近工作区 / 选择其它目录 / 新建工作区。
 * 只改新会话的落点目录，不动任何已有会话的 cwd，也不清草稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerSheet(
    options: List<WorkspaceOption>,
    currentPath: String?,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit,
    onCreateWorkspace: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val foreground = MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 独立窗口不跨窗口采样 backdrop：用统一玻璃平价材质（大圆角随主题收小）。
        containerColor = piGlassTint(strong = true),
        contentColor = foreground,
        modifier = Modifier.testTag("workspace-picker-sheet")
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "工作区",
                color = foreground.copy(alpha = 0.85f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "新会话会落在所选工作区；已有会话的目录不变。",
                color = foreground.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            if (options.isEmpty()) {
                Text(
                    "还没有可用工作区，用下面的目录选择或新建一个。",
                    color = foreground.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            Text(
                "最近工作区",
                color = foreground.copy(alpha = 0.55f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
            options.forEach { option ->
                WorkspacePickerRow(
                    label = option.label,
                    detail = option.path,
                    selected = option.path == currentPath,
                    onClick = {
                        onPick(option.path)
                        onDismiss()
                    },
                    testTag = "workspace-pick-${option.path}"
                )
            }
            Text(
                "其它",
                color = foreground.copy(alpha = 0.55f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
            )
            WorkspacePickerRow(
                label = "选择其它目录",
                detail = "浏览宿主机目录后选定",
                selected = false,
                onClick = {
                    onBrowse()
                    onDismiss()
                },
                testTag = "workspace-browse"
            )
            WorkspacePickerRow(
                label = "新建工作区",
                detail = "在选定目录里新建文件夹再进入",
                selected = false,
                onClick = {
                    onCreateWorkspace()
                    onDismiss()
                },
                testTag = "workspace-create"
            )
        }
    }
}

@Composable
private fun WorkspacePickerRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PiColors.Ocean.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                label,
                color = foreground,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                color = foreground.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Text("当前", color = PiColors.Ocean, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 品牌文案编辑器（沿用旧首页的 hero 偏好，点品牌区进入）。 */
@Composable
internal fun HeroCopyEditorDialog(
    stateHolder: AndroidSharedStateHolder,
    containerColor: Color = piGlassTint(strong = true),
    confirmOnOutsideClick: Boolean = false,
    onDismiss: () -> Unit
) {
    var titleDraft by rememberSaveable { mutableStateOf(stateHolder.heroTitle ?: DEFAULT_HERO_TITLE) }
    var taglineDraft by rememberSaveable { mutableStateOf(stateHolder.heroTagline ?: DEFAULT_HERO_TAGLINE) }
    val initialTitle = remember { stateHolder.heroTitle ?: DEFAULT_HERO_TITLE }
    val initialTagline = remember { stateHolder.heroTagline ?: DEFAULT_HERO_TAGLINE }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val save = {
        if (!saving) {
            if (!confirmOnOutsideClick) {
                stateHolder.updateHeroTitle(titleDraft)
                stateHolder.updateHeroTagline(taglineDraft)
                onDismiss()
            } else if (titleDraft == initialTitle && taglineDraft == initialTagline) {
                onDismiss()
            } else {
                saving = true
                saveError = null
                stateHolder.saveHeroCopy(titleDraft, taglineDraft) { error ->
                    saving = false
                    saveError = error
                    if (error == null) onDismiss()
                }
            }
        }
    }
    val cancel = { if (!saving) onDismiss() }
    AlertDialog(
        onDismissRequest = if (confirmOnOutsideClick) save else onDismiss,
        properties = DialogProperties(dismissOnBackPress = !confirmOnOutsideClick),
        containerColor = containerColor,
        title = { Text("自定义首页品牌") },
        text = {
            if (confirmOnOutsideClick) CancelDialogOnBack(cancel)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("标题", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                BasicTextField(
                    value = titleDraft,
                    onValueChange = { if (it.length <= 40 && !saving) titleDraft = it },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("finecat.heroTitleField"),
                    textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions()
                )
                Text(
                    if (confirmOnOutsideClick) "留空隐藏" else "留空恢复默认「$DEFAULT_HERO_TITLE」",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(6.dp))
                Text("标语", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                BasicTextField(
                    value = taglineDraft,
                    onValueChange = { if (it.length <= 40 && !saving) taglineDraft = it },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("finecat.heroTaglineField"),
                    textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() })
                )
                Text(
                    if (confirmOnOutsideClick) "留空隐藏" else "留空恢复默认「$DEFAULT_HERO_TAGLINE」",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !saving, shape = RoundedCornerShape(6.dp)) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = cancel, enabled = !saving, shape = RoundedCornerShape(6.dp)) { Text("取消") }
        }
    )
}

/** 未自定义(null)使用默认；显式清空保留空白。 */
internal fun homeBrandCopy(
    heroTitle: String?,
    heroTagline: String?
): Pair<String, String> =
    (heroTitle?.trim() ?: DEFAULT_HERO_TITLE) to
        (heroTagline?.trim() ?: DEFAULT_HERO_TAGLINE)
