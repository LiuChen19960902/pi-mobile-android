package com.finecat.pimobile.android.ui.finecat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme

/**
 * F12 条件 6 的**生产窗口配置等效宿主**（仅 debug 变体）。
 *
 * ## 为什么需要它
 *
 * `createAndroidComposeRule()` / `createEmptyComposeRule()` 默认用
 * `androidx.compose.ui.test:ui-test-manifest` 里的 `androidx.activity.ComponentActivity`。
 * 它的清单声明只有：
 *
 * ```xml
 * <activity android:name="androidx.activity.ComponentActivity"
 *           android:theme="@android:style/Theme.Material.Light.NoActionBar" />
 * ```
 *
 * 即：**没有 `windowSoftInputMode`**（默认 `adjustPan`，窗口不 resize）、
 * **没有 `enableEdgeToEdge()`**（不消费也不派发系统 insets）。所以在那个宿主上：
 *
 * - 键盘展开后拿不到 IME insets，sheet 不会被抬起，末行滚不到键盘线以上——
 *   这不是产品缺陷，是宿主没有生产的那套窗口行为。
 * - 旋转/改尺寸会重建 Activity，`setContent` 挂上去的组合随之丢失。
 *
 * 本宿主把生产（`MainActivity` + `androidApp/src/main/AndroidManifest.xml`）的三项窗口行为
 * 逐条对齐：
 *
 * | 生产配置 | 本宿主 |
 * | --- | --- |
 * | `android:windowSoftInputMode="adjustResize"` | 同样在 `androidApp/src/debug/AndroidManifest.xml` 声明 |
 * | `onCreate` 里 `enableEdgeToEdge()` | 同样在 `onCreate` 里调 |
 * | `onCreate` 里 `setContent { … }` | 同样在 `onCreate` 里挂载 |
 *
 * 最后一条是旋转能取证的关键：Activity 因方向变化被重建时，`onCreate` 会重新跑，
 * 组合重新挂上，界面还能显示（用 `setContent` 从**测试体**里挂的话，
 * 重建后就只剩一个空壳，实测报 "No compose hierarchies found in the app"）。
 *
 * ## 用法
 *
 * 测试体用 [androidx.compose.ui.test.junit4.createEmptyComposeRule] 而不是
 * `createAndroidComposeRule<T>()`：本类不持有 stateHolder 实例，
 * 由测试通过 [install] 注入（夹具都在 androidTest 源集，debug 源集引用不到）。
 */
class FineCatCompatHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 MainActivity 同一行：边到边 + 系统栏 insets 派发。
        enableEdgeToEdge()
        val stateHolder = checkNotNull(injectedHolder) {
            "FineCatCompatHostActivity 必须在启动前由测试 install() 注入 stateHolder"
        }
        setContent {
            FineCatTheme(darkTheme = stateHolder.appearanceMode == "dark") {
                Box(modifier = Modifier.fillMaxSize().testTag(ROOT_TAG)) {
                    FineCatProviderSheet(stateHolder = stateHolder, onDismiss = {})
                }
            }
        }
    }

    companion object {
        const val ROOT_TAG = "finecat.compatHost.root"

        /**
         * 宿主不自己建图：夹具（`FakeTransport` 等）在 androidTest 源集，
         * debug 源集引用不到，由测试在启动前注入。
         *
         * 刻意**不**在旋转后清空：Activity 重建会重跑 `onCreate`，此时仍需拿到同一个
         * stateHolder，否则握手状态全丢、界面会空转在 CONNECTING。
         */
        @Volatile
        private var injectedHolder: AndroidSharedStateHolder? = null

        /** 测试启动宿主前调用。 */
        fun install(graph: AndroidAppGraph) {
            injectedHolder = graph.stateHolder
        }

        /** 测试收尾复位，避免同进程内后续用例继承上一个 stateHolder。 */
        fun reset() {
            injectedHolder = null
        }
    }
}