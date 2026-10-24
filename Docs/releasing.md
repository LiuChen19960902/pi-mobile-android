# 构建渠道与正式升级

正式版普通启动进入 FineCat，使用 R8 优化，不包含调试宿主。版本号集中在 `androidApp/build.gradle.kts`；升级需递增 `versionCode`。

默认 `main` 维护经过验证的配套源码；实验性功能须在 README 标明性质、接入条件与边界，未完成验证的改动留在开发分支。GitHub Latest 只指向完成最终签名验收的正式版，Preview 使用预发布标记，不能因代码合入而自动提升为正式版。Release 草稿不会隐藏已进入 `main` 的代码；需要恢复主线时使用正常 PR 和新增恢复提交，不改写公共历史。两仓生产输入与跨仓构建引用需一起核对，依据见[默认分支记录](../.agents/notes/implemented/process/2026-10-23-stable-default-branch.md)。

## Preview 调试渠道

正式发版前的日常开发、联调和真机验收统一使用 **Pi Mobile Preview**。Gradle 的 `debug` 构建类型就是 Preview，保留调试能力；非正式安装包只有这个渠道。

| 项目 | Preview | Release |
| --- | --- | --- |
| 应用名称 | Pi Mobile Preview | Pi Mobile |
| 包名 | `com.finecat.pimobile.android.preview` | `com.finecat.pimobile.android` |
| 版本名 | 基础版本追加 `-preview` | 基础版本 |
| 签名 | Android 调试签名 | 发布者的长期签名 |
| 本地数据与配对 | 独立保存 | 独立保存 |

两者可以同时安装；首次使用 Preview 需要单独配对，不能用它覆盖正式版来继承数据。Preview 只提供一个桌面入口，默认进入 FineCat；`src/debug/` 保留仅供开发的测试宿主。两种构建都关闭开发仓版本控制元数据。

```bash
./gradlew :androidApp:assembleDebug
```

构建产物位于 `androidApp/build/outputs/apk/debug/`；对外分发为 `pi-mobile-v<基础版本>-preview.apk`，文件校验值随产物保留。Preview 保持同一调试签名时可覆盖升级，调试密钥变更后不能覆盖安装；不要通过卸载或清数据绕过签名检查。

固定下载入口按渠道分开：`preview.apk` 指向 Preview，`latest.apk` 指向正式版。更新时只切换对应别名，保留已核验的版本文件，并完整下载核对 SHA-256。

最终发布前仍需用最终签名、启用 R8 的 Release 完成下文的受影响路径验证，Preview 验收不替代该检查。

## 发布签名

发布者自备长期签名密钥，保存在仓库外。私钥、密码及本机签名配置不得提交或放进 APK 下载目录；离线备份密钥与密码，后续升级必须使用同一签名。

Gradle 接受以下项目属性，可通过同名的 `ORG_GRADLE_PROJECT_` 环境变量提供，避免把密码放在命令参数中：

| 属性 | 含义 |
| --- | --- |
| `piReleaseStoreFile` | 仓库外 keystore 的绝对路径 |
| `piReleaseStorePassword` | keystore 密码 |
| `piReleaseKeyAlias` | 签名密钥别名 |
| `piReleaseKeyPassword` | 密钥密码 |

由本机私密配置或 CI secret 设置这些变量后执行：

```bash
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleRelease
```

APK 位于 `androidApp/build/outputs/apk/release/`。未配置发布密钥时只生成未签名 Release，不回退 debug 签名。

用 Android SDK build-tools 的 `apksigner verify --verbose --print-certs <APK>` 核实签名，用 `aapt dump badging <APK>` 核实包名、版本和入口；保留 APK SHA-256、签名证书 SHA-256 与对应源码版本。检查 merged manifest 中 `debuggable=false` 且没有 debug Activity。

Release 已通过 `vcsInfo.include = false` 关闭开发仓版本控制元数据。检查实际 APK 中不存在 `META-INF/version-control-info.textproto`，避免自动携带私有提交标识；发布页记录的源码版本应指向公开仓库。

## 安装与最终验证

同包名、同签名的正式升级可覆盖安装。正式签名与 debug 测试签名不同，不能互相覆盖；不要在自动化中卸载测试包或清数据来绕过签名差异。本地偏好与配对凭据没有通用导出迁移功能，重新安装后需要重新配对；宿主上的 Pi 会话文件不受手机重装影响。

发布前用最终签名 APK 核对：普通启动、扫码/手动配对、多地址连接、历史分页、发消息与停止、模型/思考选择、图片附件、`/` 与 `@` 补全、tree 导航、Provider 和 scope-model 的保存，以及亮暗模式和键盘布局。R8 构建成功及 JVM 通过不替代这些真机检查。

发布源码只包含产品源码与公开文档，不含开发历史、内部原型、TODO/审查记录、本机配置、密钥或会话。发布 tag 必须指向已合入 `main` 的源码提交；正式 APK、发行源码附件与该提交必须对应同一生产输入。源码快照核验后才创建公开仓库并发布。

## 发布文件

- 源码：手机端与网关分别建立公开仓库，按配套版本发布；两仓 README 填入实际仓库链接。
- 安装包：分发包含签名 APK、根目录 `LICENSE`、`Docs/third-party-notices.md` 与 `Docs/licenses/Apache-2.0.txt` 的 ZIP。若另提供 APK 直链，发布页同时附上这些声明和许可文件。
- 命名：APK 为 `pi-mobile-v<versionName>.apk`，ZIP 为 `pi-mobile-v<versionName>-release.zip`，Git tag 为 `v<versionName>`。对外文件名不带 `versionCode` 或构建哈希；`versionCode` 每次发布递增，记录在包内和发布说明中。改名时复用已验证 APK 的原字节，不重新构建、签名或重置版本号，并同步更新压缩包内的文件名、安装说明、链接和 `SHA256SUMS`，回下载后完整核对。
- 校验：提供下载文件的 SHA-256；私有构建路径、内部清单和开发提交历史不放入发行附件。
- 产品截图：使用当前版本的合成会话与示例目录，放在 `Docs/images/` 并更新 README；检查完整图片及元数据，不含配对码、密钥、真实会话、私人目录、网关地址或通知栏私人信息。优先重新截图，必须遮挡时使用不透明覆盖并重编码。

源码归档移除本机 owner 名称、用户 ID、绝对路径和归档备注。首次公开使用筛选副本建立全新 Git 历史，显式设置公开署名与邮箱；不要把私有开发历史合并或推送进公开仓。GitHub noreply 邮箱仍关联对应账号，不能用于隐藏账号身份。

截图与文档更新不改变生产输入时可复用已验收 APK；源码或资源发生变化则重新构建，并复验受影响路径。
