package com.finecat.pimobile.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceDirectoryBrowserSheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit,
    /**
     * 首页「新建工作区」入口：目录加载完就自动弹“新建文件夹”，不让用户去找
     * 右上角的小图标。面板本身与选目录流程共用同一套实现。
     */
    startWithCreatePrompt: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val foreground = MaterialTheme.colorScheme.onSurface
    var showCreateDirectoryPrompt by rememberSaveable { mutableStateOf(false) }
    var newDirectoryName by rememberSaveable { mutableStateOf("") }
    var highlightedPath by remember { mutableStateOf<String?>(null) }
    val parentPath = stateHolder.directoryCrumbs
        .takeIf { it.size > 1 }
        ?.dropLast(1)
        ?.lastOrNull()
        ?.path

    // 列表滚到底后，剩余的上滑 delta 会被 ModalBottomSheet 接管去拖动面板，
    // 表现为列表与面板来回拉锯、持续上下跳动。这里在列表消费完滚动后把
    // 「向上的剩余量」全部吃掉，不再传给 sheet（顶部下拉关闭面板的能力保留：
    // 向下的剩余量仍然放行给 sheet）。
    val sheetDragIsolation = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = if (available.y < 0f) available else Offset.Zero
        }
    }

    LaunchedEffect(Unit) {
        stateHolder.beginDirectoryBrowsing()
    }
    // 新建模式：等当前目录真读到了再弹命名框（路径未定时点“创建”会直接 return）。
    var createPromptShown by remember { mutableStateOf(false) }
    LaunchedEffect(startWithCreatePrompt, stateHolder.directoryPath) {
        if (startWithCreatePrompt && !createPromptShown && stateHolder.directoryPath != null) {
            createPromptShown = true
            newDirectoryName = ""
            showCreateDirectoryPrompt = true
        }
    }
    LaunchedEffect(stateHolder.createdDirectoryPathToReveal, stateHolder.directoryEntries) {
        val createdPath = stateHolder.createdDirectoryPathToReveal ?: return@LaunchedEffect
        val index = stateHolder.directoryEntries.indexOfFirst { it.path == createdPath }
        if (index < 0) return@LaunchedEffect
        highlightedPath = createdPath
        stateHolder.acknowledgeCreatedDirectoryReveal(createdPath)
        listState.animateScrollToItem(index + 1)
        delay(1_600)
        if (highlightedPath == createdPath) highlightedPath = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 独立窗口不跨窗口采样：strong 玻璃底保证可读。
        containerColor = piGlassTint(strong = true),
        contentColor = foreground,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 38.dp,
                height = 5.dp,
                color = foreground.copy(alpha = 0.30f)
            )
        },
        modifier = Modifier.testTag("directory-browser-sheet")
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding()
                .imePadding()
        ) {
            DirectoryBrowserHeader(
                enabled = stateHolder.directoryPath != null &&
                    !stateHolder.directoryIsLoading &&
                    !stateHolder.directoryCreationIsLoading,
                onCreateDirectory = {
                    newDirectoryName = ""
                    showCreateDirectoryPrompt = true
                },
                onDismiss = onDismiss,
                onConfirm = {
                    stateHolder.directoryPath?.let(stateHolder::selectDirectory)
                    onDismiss()
                }
            )

            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(
                    "当前目录",
                    color = foreground.copy(alpha = 0.52f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stateHolder.directoryPath ?: "正在读取…",
                    color = foreground.copy(alpha = 0.40f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 18.dp),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = piGlassTint(),
                border = piGlassOutline()
            ) {
                if (stateHolder.directoryIsLoading && stateHolder.directoryEntries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PiSpinner(color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                            Text("正在读取远程目录…", color = foreground.copy(alpha = 0.58f))
                        }
                    }
                } else if (!stateHolder.directoryIsLoading && stateHolder.directoryEntries.isEmpty() &&
                    stateHolder.directoryError != null
                ) {
                    // 失败不是空目录：面板内提示+重试。
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stateHolder.directoryError.orEmpty(),
                                color = foreground.copy(alpha = 0.72f),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "重试",
                                color = PiColors.Ocean,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    stateHolder.browseDirectories(stateHolder.directoryPath)
                                }.padding(10.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = !stateHolder.directoryIsLoading &&
                            !stateHolder.directoryCreationIsLoading,
                        // 底部多留余量抵消卡片圆角裁切，最后一条目录完整可见，「到底」有明确视觉终点。
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp),
                        modifier = Modifier.nestedScroll(sheetDragIsolation)
                    ) {
                        item(key = "parent") {
                            DirectoryRow(
                                title = "..",
                                subtitle = "返回上一级",
                                icon = DirectoryIcon.Parent,
                                enabled = parentPath != null,
                                highlighted = false,
                                onClick = { parentPath?.let(stateHolder::browseDirectories) }
                            )
                            DirectoryDivider()
                        }
                        itemsIndexed(
                            items = stateHolder.directoryEntries,
                            key = { _, entry -> entry.path }
                        ) { index, entry ->
                            DirectoryRow(
                                title = entry.name,
                                subtitle = if (entry.hidden) "隐藏目录" else null,
                                icon = if (entry.hidden) DirectoryIcon.HiddenFolder else DirectoryIcon.Folder,
                                enabled = true,
                                highlighted = highlightedPath == entry.path,
                                modifier = Modifier.testTag("directory-row-${entry.path}"),
                                onClick = { stateHolder.browseDirectories(entry.path) }
                            )
                            if (index != stateHolder.directoryEntries.lastIndex) DirectoryDivider()
                        }
                    }
                }
            }

            if (stateHolder.directoryCreationIsLoading) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PiSpinner(color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在创建文件夹…", color = foreground.copy(alpha = 0.52f), fontSize = 12.sp)
                }
            }
        }
    }

    if (showCreateDirectoryPrompt) {
        AlertDialog(
            onDismissRequest = { showCreateDirectoryPrompt = false },
            // 独立窗口不跨窗口采样：strong 玻璃底保证可读。
            containerColor = piGlassTint(strong = true),
            title = { Text("新建文件夹", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("将在当前目录中创建一个新的子文件夹。")
                    OutlinedTextField(
                        value = newDirectoryName,
                        onValueChange = { newDirectoryName = it },
                        label = { Text("文件夹名称") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth().testTag("new-directory-name")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    shape = RoundedCornerShape(6.dp),
                    enabled = newDirectoryName.trim().isNotEmpty(),
                    onClick = {
                        val parent = stateHolder.directoryPath ?: return@TextButton
                        showCreateDirectoryPrompt = false
                        stateHolder.createDirectory(parent, newDirectoryName)
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDirectoryPrompt = false }, shape = RoundedCornerShape(6.dp)) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DirectoryBrowserHeader(
    enabled: Boolean,
    onCreateDirectory: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // 标题占中段弹性宽度，避免窄屏上与右侧「取消/选定」按钮重叠。
    Row(
        Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCreateDirectory,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(piGlassTint())
                .testTag("create-directory")
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_folder_outline),
                    contentDescription = "新建文件夹",
                    modifier = Modifier.size(26.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                )
                Box(
                    Modifier.size(11.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "选择工作目录",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(piGlassTint())
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            TextButton(
                onClick = onConfirm,
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f))
                    .testTag("confirm-directory")
            ) {
                Text("选定", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
            }
        }
    }
}

private enum class DirectoryIcon { Parent, Folder, HiddenFolder }

@Composable
private fun DirectoryRow(
    title: String,
    subtitle: String?,
    icon: DirectoryIcon,
    enabled: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val foreground = MaterialTheme.colorScheme.onSurface
    val rowModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(if (highlighted) PiColors.Ocean.copy(alpha = 0.20f) else Color.Transparent)
        .clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
        .alpha(if (enabled) 1f else 0.30f)
        .padding(horizontal = 8.dp, vertical = 12.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterStart) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(
                        if (icon == DirectoryIcon.Parent) R.drawable.ic_parent_directory
                        else R.drawable.ic_folder_outline
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                    colorFilter = ColorFilter.tint(PiColors.Ocean)
                )
                if (icon == DirectoryIcon.HiddenFolder) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_question_badge),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).size(13.dp)
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = foreground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, color = foreground.copy(alpha = 0.46f), fontSize = 12.sp)
            }
        }
        Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(PiColors.Mist.copy(alpha = 0.88f))
        )
    }
}

@Composable
private fun DirectoryDivider() {
    HorizontalDivider(
        Modifier.padding(start = 58.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    )
}
