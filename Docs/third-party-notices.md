# 第三方软件声明

KMP 架构与部分移动端实现参考了 [dsh-mobile](https://github.com/Clarklevis1995/dsh-mobile)（MIT，Copyright (c) 2026 Chaofan Li）；本项目不是该仓库的 fork。上游版权声明与 MIT 许可全文保留在根目录 [LICENSE](../LICENSE)。本仓库不包含上游 iOS 工程与 Swift 源码，如需参考请回到上游仓库。

## 通过 Maven 依赖的库

| 库 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| [Backdrop / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Kyant0） | `io.github.kyant0:backdrop:1.0.6` | Apache License 2.0 | Compose 背景采样、模糊、色彩增强与折射渲染 |
| [Markwon](https://github.com/noties/Markwon)（noties 及贡献者） | `io.noties.markwon:4.6.2`（core、ext-tables、ext-strikethrough、ext-tasklist、html） | Apache License 2.0 | 会话正文 Markdown 渲染。**未启用远程图片加载扩展**，图片只经网关附件校验与自有缓存进入界面 |
| [OkHttp](https://github.com/square/okhttp)（Square） | `com.squareup.okhttp3:okhttp:4.12.0` | Apache License 2.0 | Android 侧 WebSocket transport |
| [Kotlinx Coroutines / Serialization](https://github.com/Kotlin)（JetBrains） | 见 `gradle/libs.versions.toml` | Apache License 2.0 | 并发与线协议 JSON |
| [AndroidX](https://developer.android.com/jetpack/androidx)（Google，含 Compose、DataStore、Activity、Lifecycle） | 见 `gradle/libs.versions.toml` | Apache License 2.0 | UI、偏好与安全存储载体（凭据密文由 Android Keystore 保护） |
| [CameraX](https://developer.android.com/training/camerax)（Google） | `androidx.camera:*:1.6.2`（camera2、lifecycle、mlkit-vision、view） | Apache License 2.0 | 扫码页相机预览与帧分析 |
| [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)（Google） | `com.google.mlkit:barcode-scanning:17.3.0` | Apache License 2.0 | 二维码识别（端侧，不联网） |
| [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx)（Google） | `androidx.exifinterface:exifinterface:1.4.1` | Apache License 2.0 | 照片方向纠正 |
| [Kotlin](https://github.com/JetBrains/kotlin)（JetBrains） | 2.3.20 | Apache License 2.0 | 语言与 KMP 工具链 |

许可证全文：[Apache License 2.0](licenses/Apache-2.0.txt)，来源为 [Apache 官方文本](https://www.apache.org/licenses/LICENSE-2.0.txt)。分发 APK 时同时提供本声明、根目录 MIT 许可与此许可全文，保留相关版权声明。

## 素材与品牌

- 桌面彩色图标按 [Pi 官方 Press Kit](https://pi.dev/press-kit) 的三色 SVG 转为 Android VectorDrawable；来源页面标示 MIT，Pi 项目许可声明为 Copyright (c) 2025 Mario Zechner，见 [上游 LICENSE](https://github.com/earendil-works/pi/blob/main/LICENSE)。图形路径与色值保留，布局适配 Android 图标画布。
- 首页及系统单色图标的 π 字形来自 [lobehub/lobe-icons](https://github.com/lobehub/lobe-icons)（MIT，Copyright (c) 2023 LobeHub）。上述 MIT 许可与版权声明一并保留在根目录 [LICENSE](../LICENSE)。
- pi 等名称、商标归各自所有者；代码或素材的 MIT 许可不转让商标。本项目是社区客户端，与上游没有隶属关系。
