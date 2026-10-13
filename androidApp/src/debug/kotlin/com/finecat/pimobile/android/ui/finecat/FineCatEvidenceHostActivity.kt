package com.finecat.pimobile.android.ui.finecat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** F14 参考视口宿主：在挂载 Compose 前建立与生产一致的窗口和 IME 配置。 */
class FineCatEvidenceHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}
