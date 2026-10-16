package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.PiAndroidApplication
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

/** Explicit copy only; closing retains the report for the next launch. */
@Composable
internal fun FineCatCrashReportDialog(darkTheme: Boolean) {
    val application = LocalContext.current.applicationContext as? PiAndroidApplication ?: return
    val store = application.crashReport
    var report by remember(store) { mutableStateOf(store.read()) }
    var visible by remember(store) { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    val text = report ?: return
    if (!visible) return
    val clipboard = LocalClipboardManager.current
    FineCatTheme(darkTheme = darkTheme) {
        val type = LocalFineCatType.current
        val colors = LocalFineCatColors.current
        AlertDialog(
            onDismissRequest = { visible = false },
            modifier = Modifier.testTag("finecat.crashReport"),
            title = { Text("上次异常退出", style = type.title) },
            text = {
                Column {
                    Text("仅包含异常类型和代码堆栈，未自动上传。", style = type.xs, color = colors.text2)
                    Text(
                        text,
                        style = type.xs.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    )
                    if (deleteFailed) Text("删除失败，请重试", style = type.xs, color = colors.err)
                }
            },
            confirmButton = {
                FcTextButton(
                    if (copied) "已复制" else "复制诊断",
                    onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
                    modifier = Modifier.testTag("finecat.crashReport.copy"),
                )
            },
            dismissButton = {
                FcTextButton(
                    "删除记录",
                    onClick = {
                        if (store.clear()) report = null else deleteFailed = true
                    },
                    modifier = Modifier.testTag("finecat.crashReport.delete"),
                )
            },
        )
    }
}
