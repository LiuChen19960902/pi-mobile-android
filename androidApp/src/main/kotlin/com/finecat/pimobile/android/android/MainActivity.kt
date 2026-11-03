package com.finecat.pimobile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 启动窗口已由 Starting 主题绘制，Activity 创建后切回常规主题。
        setTheme(R.style.Theme_PiMobile)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 默认进入 FineCat；只有 debug 构建可显式选择旧界面作对照。
        if (BuildConfig.DEBUG) {
            (application as PiAndroidApplication).graph.stateHolder.debugUseFineCatUi =
                intent.getBooleanExtra("debug_finecat_ui", true)
        }
        openSessionFromIntent(intent)
        setContent {
            PiDebugScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSessionFromIntent(intent)
    }

    /** 通知点击只带 sessionId；进程冷启动和已有实例都走同一条选择入口。 */
    private fun openSessionFromIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        intent.removeExtra(EXTRA_SESSION_ID)
        (application as PiAndroidApplication).graph.stateHolder.selectSession(sessionId)
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.finecat.pimobile.android.extra.SESSION_ID"
    }
}
