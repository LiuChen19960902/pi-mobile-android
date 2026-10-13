package com.finecat.pimobile.android.ui

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Dialog禁用内置Back关闭后，仍由它自己的dispatcher处理明确取消，外点另走保存。 */
@Composable
internal fun CancelDialogOnBack(onCancel: () -> Unit) {
    val dialog = (LocalView.current.parent as? DialogWindowProvider)?.window?.callback
        as? OnBackPressedDispatcherOwner
    if (dialog != null) {
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dialog) {
            BackHandler(onBack = onCancel)
        }
    }
}
