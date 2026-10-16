package com.finecat.pimobile.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.os.Build
import android.os.Process
import com.finecat.pimobile.android.platform.CrashRecordingHandler
import com.finecat.pimobile.android.platform.LocalCrashReport
import com.finecat.pimobile.android.platform.GatewayLifecycleEvent
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PiAndroidApplication : Application(), DefaultLifecycleObserver {
    lateinit var graph: AndroidAppGraph
        private set
    internal lateinit var crashReport: LocalCrashReport
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        crashReport = LocalCrashReport(
            File(noBackupFilesDir, "last-crash.txt"),
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
        )
        Thread.setDefaultUncaughtExceptionHandler(CrashRecordingHandler(
            crashReport, Thread.getDefaultUncaughtExceptionHandler(),
            terminate = { Process.killProcess(Process.myPid()); exitProcess(10) },
        ))
        graph = AndroidAppGraph(this)
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.APPLICATION_CREATED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        graph.applicationScope.launch {
            graph.attachmentCache.removeExpired()
        }
        graph.applicationScope.launch {
            graph.gatewayRuntime.state
                .map { it.shouldKeepAliveInBackground }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        graph.diagnostics.lifecycle(GatewayLifecycleEvent.KEEP_ALIVE_START, keepAlive = true)
                        // Android 12+ 后台起前台服务直接抛：吞掉并记日志，
                        // 否则收集协程死掉，此后保活启停永久失效且无日志。
                        runCatching { GatewayConnectionService.start(this@PiAndroidApplication) }
                            .onFailure {
                                graph.diagnostics.lifecycle(GatewayLifecycleEvent.KEEP_ALIVE_START_FAILED)
                            }
                    }
                    // 空闲时由服务在完成前台注册后自行停止。此处 stopService
                    // 会与尚未收到 onStartCommand 的前台启动请求竞争并触发系统崩溃。
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        graph.gatewayRuntime.applicationDidBecomeActive()
    }

    override fun onStop(owner: LifecycleOwner) {
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.BACKGROUND)
        graph.gatewayScope.launch { graph.gatewayRuntime.applicationDidEnterBackground() }
    }
}
