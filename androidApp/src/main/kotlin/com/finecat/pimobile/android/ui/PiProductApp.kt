package com.finecat.pimobile.android.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.view.WindowCompat
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.domain.SessionSummary
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.launch

private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PiProductApp(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val onChatRoute = route == null || route == ROUTE_CHAT
    val isDark = effectivePiDark(stateHolder.appearanceMode, isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // 系统栏图标只在这里决定：空白首页与会话页都跟随主题（旧 workspace 常暗
        // 的特例随首页一起消失了），设置页同样是主题底色。
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    // 抽屉里长按改名、恢复隐藏、扫码/手动配对的弹窗状态放在宿主层：lazy 列表
    // 回收行时不会把弹窗连带组合掉。
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var showHiddenSessions by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showManualPairing by rememberSaveable { mutableStateOf(false) }

    // 会话列表住在抽屉里，但抽屉不是常驻组合：拉取放在宿主层，连上刷一次
    //（旧 workspace 页自带这条，页面搬走后必须补上，否则抽屉永远是空列表）。
    LaunchedEffect(stateHolder.gatewayState.connection) { stateHolder.refreshProductState() }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 恢复项来自本地隐藏记录；网关列表未加载或不完整时仍保留入口，只缺标题。
    val hiddenRestores = remember(
        stateHolder.snapshot.sessions, stateHolder.hiddenSessionIds, stateHolder.hiddenDirectoryPaths
    ) {
        buildHiddenRestoreLists(
            stateHolder.hiddenSessionIds, stateHolder.snapshot.sessions, stateHolder.hiddenDirectoryPaths
        )
    }
    // 主页面推开的距离 = 抽屉目标宽度（与 SessionDrawer 的 piDrawerWidth 同一公式），
    // 与 drawerState.currentOffset 公开读数联动（见 pushContentForDrawer）。
    val screenWidthDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val drawerWidth = piDrawerWidth(screenWidthDp)

    ModalNavigationDrawer(
        drawerContent = {
            SessionDrawerContent(
                stateHolder = stateHolder,
                onOpenSession = { id ->
                    stateHolder.selectSession(id)
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                },
                onScanPairing = {
                    // 扫码/手动配对是全屏或底部面板：先把抽屉合上，不让它挡在后面。
                    scope.launch { drawerState.close() }
                    stateHolder.clearPlatformError()
                    showQrScanner = true
                },
                onManualPairing = {
                    scope.launch { drawerState.close() }
                    stateHolder.clearPlatformError()
                    showManualPairing = true
                },
                onRename = { renameTarget = it },
                onShowHiddenSessions = { showHiddenSessions = true }
            )
        },
        drawerState = drawerState,
        // 闭合态不用 M3 的全屏横滑开抽屉（会抢 HorizontalPager 与树页的横向手势），
        // 改由下面的窄边缘触发；抽屉已打开时才允许整页滑关。
        gesturesEnabled = drawerState.isOpen
    ) {
        // 根宿主常驻玻璃环境：点阵/流体背景只在这里画一份，跨首页/会话/设置页与
        // 抽屉滑出持续存在；各页面不再自带不透明整页底色，发送/切会话/进设置
        // 都不重建根或换底，消除「发送后像跳到另一页」的突变。
        PiLiquidGlassHost(
            modifier = Modifier.fillMaxSize().pushContentForDrawer(drawerState, drawerWidth),
            background = { backgroundModifier ->
                // 点阵网格/底色常驻；巨大点阵 logo 只在首页空白页出现（历史加载
                // 不再透巨大 Pi 字）。空白判定归 HomeScreen 的 isBlankHome。
                PiAnimatedBackground(
                    backgroundModifier.testTag("pi-animated-background"),
                    dark = isDark,
                    showBrandDecoration = onChatRoute && isBlankHome(stateHolder)
                )
            }
        ) {
            Box(
                Modifier.fillMaxSize()
                    .edgeSwipeToOpenDrawer(drawerState, enabled = onChatRoute && !drawerState.isOpen)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_CHAT,
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() }
                ) {
                    composable(ROUTE_CHAT) {
                        ConversationScreen(
                            stateHolder = stateHolder,
                            onPickImage = onPickImage,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onNewSession = { cwd -> stateHolder.prepareNewSession(cwd) },
                            drawerOpen = drawerState.isOpen
                        )
                    }
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            stateHolder = stateHolder,
                            onBack = navController::popBackStack
                        )
                    }
                }

                stateHolder.transientNotice?.let { notice ->
                    TransientNoticeBar(notice, stateHolder::clearTransientNotice)
                }
            }
        }
    }

    stateHolder.platformError?.let { error ->
        AlertDialog(
            onDismissRequest = stateHolder::clearPlatformError,
            containerColor = piGlassTint(strong = true),
            title = { Text("Pi Mobile") },
            text = { Text(error) },
            confirmButton = {
                when (stateHolder.platformErrorAction) {
                    AndroidSharedStateHolder.PlatformErrorAction.RETRY_DIRECTORY -> TextButton(shape = RoundedCornerShape(6.dp), onClick = {
                        stateHolder.clearPlatformError()
                        stateHolder.browseDirectories(stateHolder.directoryPath)
                    }) { Text("重试") }
                    AndroidSharedStateHolder.PlatformErrorAction.DISMISS_ONLY -> Unit
                    AndroidSharedStateHolder.PlatformErrorAction.RECONNECT -> TextButton(shape = RoundedCornerShape(6.dp), onClick = {
                        stateHolder.clearPlatformError()
                        stateHolder.connect()
                    }) { Text("重新连接") }
                }
            },
            dismissButton = {
                TextButton(onClick = stateHolder::clearPlatformError, shape = RoundedCornerShape(6.dp)) { Text("好") }
            }
        )
    }
    renameTarget?.let { target ->
        SessionRenameDialog(
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                stateHolder.renameSession(target.id, name)
            }
        )
    }
    if (showHiddenSessions) {
        HiddenSessionsDialog(
            sessionEntries = hiddenRestores.sessionEntries,
            directories = hiddenRestores.directoryPaths,
            onRestore = { stateHolder.restoreHiddenSession(it) },
            onRestoreDirectory = { stateHolder.restoreHiddenDirectory(it) },
            onRestoreAll = { stateHolder.restoreAllHiddenSessions() },
            onDismiss = { showHiddenSessions = false }
        )
    }
    if (showQrScanner) {
        GatewayQrScannerScreen(
            onCode = { payload ->
                showQrScanner = false
                stateHolder.pair(payload)
            },
            onCancel = { showQrScanner = false },
            // 顶栏不再有扫码入口以外的路，配对仍在扫码页内保留手动输入这条。
            onManualEntry = {
                showQrScanner = false
                stateHolder.clearPlatformError()
                showManualPairing = true
            },
            onFailure = { message ->
                showQrScanner = false
                stateHolder.showPlatformError(message)
            }
        )
    }
    if (showManualPairing) {
        ManualGatewayPairingSheet(stateHolder) { showManualPairing = false }
    }
}

/**
 * 打开抽屉时把主页面向右推开（参考稿形态）：位移与 [DrawerState.currentOffset]
 * 公开读数联动（闭合 -drawerWidth → 打开 0），不读 M3 内部 API。推开的瞬间才
 * 裁出左侧圆角并加投影；闭合态 translationX = 0 且不裁剪，主页面完全原位，
 * 点击/IME/导航不受影响。位置在 draw 阶段读数，拖动只重绘不重组。
 */
private fun Modifier.pushContentForDrawer(drawerState: DrawerState, drawerWidth: Dp): Modifier =
    graphicsLayer {
        // 与 width() 实际测量的整像素一致，闭合后不残留亚像素位移。
        val widthPx = drawerWidth.roundToPx().toFloat()
        val offset = drawerState.currentOffset
        val pushed = if (offset.isNaN()) 0f else (widthPx + offset).coerceIn(0f, widthPx)
        translationX = pushed
        if (pushed > 0.5f) {
            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
            clip = true
            shadowElevation = 8.dp.toPx()
        } else {
            clip = false
            shadowElevation = 0f
        }
    }

/** 全局瞬时提示条（pi 内置命令的移动端回显提示）：盖在内容之上，不进列表。 */
@Composable
private fun BoxScope.TransientNoticeBar(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 3.dp
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(6.dp)) {
                Text(
                    "知道了",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inversePrimary
                )
            }
        }
    }
}

/** 首页深色/浅色下的前景色：深色模式白，浅色模式 Ink。 */
@Composable
internal fun piWorkspaceForeground(): Color =
    if (LocalPiDark.current) Color.White else PiColors.Ink

@Composable
internal fun PiLogoIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Image(
        painter = painterResource(R.drawable.pi_logo),
        contentDescription = "PiMobile",
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}

internal fun relativeTime(
    epochSeconds: Double,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    // 缺时间戳（网关未给 modified/created，还原为 0.0）：未知不显示，不算“20513 天前”。
    if (epochSeconds <= 0) return ""
    val eventMillis = (epochSeconds * 1_000).toLong().coerceAtMost(nowMillis)
    val elapsed = ((nowMillis - eventMillis) / 1_000).coerceAtLeast(0)
    val dayDifference = localDayIndex(nowMillis, timeZone) - localDayIndex(eventMillis, timeZone)
    return when {
        dayDifference == 1L -> "昨天"
        dayDifference == 2L -> "前天"
        dayDifference > 2L -> "$dayDifference 天前"
        elapsed < 60 -> "刚刚"
        elapsed < 3_600 -> "${elapsed / 60} 分钟前"
        else -> "${elapsed / 3_600} 小时前"
    }
}

private fun localDayIndex(epochMillis: Long, timeZone: TimeZone): Long {
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
    val previousYear = calendar.get(Calendar.YEAR).toLong() - 1
    return previousYear * 365 +
        previousYear / 4 -
        previousYear / 100 +
        previousYear / 400 +
        calendar.get(Calendar.DAY_OF_YEAR)
}
