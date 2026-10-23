package com.finecat.pimobile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** 让 debug 真机试用可从桌面进入；普通 MainActivity 启动方式保持原样。 */
class FineCatTrialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("debug_finecat_ui", true)
                // 重建 Activity 栈，确保已有旧 UI 任务也会读取试用参数。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
