package com.finecat.pimobile.android.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * pi TUI 同款盲文 spinner（pi-tui Loader DEFAULT_FRAMES，80ms/帧）。
 *
 * 全仓"进行中"语义统一用它：Working、压缩上下文、加载更早记录、provider 列表
 * 加载/测试/拉取模型、目录读取与创建、会话树加载、连接中、工具运行中……
 * 与 pi 的终端风格保持一致（用户要求统一，不要圆圈）。
 *
 * **确定性进度**（如上下文占用环 `CircularProgressIndicator(progress = {…})`）
 * 表达的是"完成百分比"而非"进行中"，语义不同，继续用进度环。
 */
internal val PI_SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/**
 * @param color 与相邻文案/图标同色，避免 spinner 跳色。
 * @param fontSize 视觉尺寸由字号决定（盲文字形高度≈字号）；原 dp 尺寸按
 *   ×0.73 折算成字号，保持各处相对大小关系。
 */
@Composable
internal fun PiSpinner(
    color: Color,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier
) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            frame = (frame + 1) % PI_SPINNER_FRAMES.size
        }
    }
    Text(
        PI_SPINNER_FRAMES[frame],
        color = color,
        fontSize = fontSize,
        modifier = modifier
    )
}
