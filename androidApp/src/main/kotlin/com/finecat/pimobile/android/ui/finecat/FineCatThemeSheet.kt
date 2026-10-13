package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.finecat.pimobile.android.ui.finecat.components.FcRow
import com.finecat.pimobile.android.ui.finecat.components.FcSheet

/**
 * 外观三选（原型：抽屉 dbar 的主题入口 `data-act="theme-row"`，app.js:370/808）。
 *
 * 原型那个按钮是**直接切换**浅/深（`setTheme(S.theme === 'dark' ? 'light' : 'dark')`），
 * 移动端改成三选卡：原型没有独立外观页，而 F07 完成条件要求「主题三态入口可达」，
 * 且现有偏好真源 `appearanceMode` 本身就是 system/light/dark 三值——直接切换会把
 * `system` 这个真实状态吃掉。选择写回 `holder.updateAppearanceMode`（持久化、即时生效）。
 * 选中项以 value「当前」标记，不引入第二套勾选组件。
 */
@Composable
internal fun FineCatThemeSheet(
    appearanceMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    FcSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "外观",
        showDone = true,
        onDone = onDismiss,
        modifier = Modifier.testTag("finecat.themeSheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            listOf("跟随系统" to "system", "浅色" to "light", "深色" to "dark")
                .forEachIndexed { index, (label, mode) ->
                    FcRow(
                        label = label,
                        onClick = { onSelect(mode) },
                        value = if (appearanceMode == mode) "当前" else null,
                        dividerAbove = index > 0,
                        modifier = Modifier.testTag("finecat.themeOption.$mode"),
                    )
                }
        }
    }
}
