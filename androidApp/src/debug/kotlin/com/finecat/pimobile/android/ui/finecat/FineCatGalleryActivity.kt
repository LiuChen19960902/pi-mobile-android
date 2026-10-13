package com.finecat.pimobile.android.ui.finecat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme

/**
 * P1 测试宿主（仅 debug 变体）：固定亮暗、fixture 数据与容器大小，可真实点击
 * 切换状态（03 · P1）。不接业务门面，数据全是 debug 假数据。
 * 通过 `adb shell am start -n com.finecat.pimobile.android/.ui.finecat.FineCatGalleryActivity` 启动。
 */
class FineCatGalleryActivity : ComponentActivity() {

    private var darkTheme by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FineCatTheme(darkTheme = darkTheme) {
                FineCatGalleryScreen(
                    isDark = darkTheme,
                    onToggleTheme = { darkTheme = !darkTheme },
                )
            }
        }
    }
}
