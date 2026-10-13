package com.finecat.pimobile.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 先测量工具栏；正文按剩余高度显示，附件、文字和候选栏超出时可滚动。 */
@Composable
internal fun ComposerLayout(
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
    toolbar: @Composable () -> Unit
) {
    Column(
        modifier.padding(horizontal = 14.dp).padding(top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("composer-body"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = body
        )
        Box(Modifier.fillMaxWidth().testTag("composer-toolbar")) {
            toolbar()
        }
    }
}
