# PicACGMD

[![Android CI](https://github.com/Better-Native-APPs/PicACGMD/actions/workflows/android.yml/badge.svg)](https://github.com/Better-Native-APPs/PicACGMD/actions/workflows/android.yml)

哔咔漫画第三方客户端，用 Kotlin + Jetpack Compose（Material 3 Expressive）编写，支持 Android 8.0 及以上。

- 所有功能直接可用，没有付费限制；不含遥测，只连接哔咔官方接口、图片服务器，以及用户自己配置的代理。
- 下载线程 1–16 可调。
- 设计稿与规范详见 [design/SPEC.md](design/SPEC.md) 及 [design/index.html](design/index.html)。
- 更多协议与架构文档参见 [docs/ 目录](docs/README.md)，更新历史见 [CHANGELOG.md](CHANGELOG.md)。

## 功能

- **浏览**：分类（含全分类）、排行榜、随机、搜索、游戏专区、公告；常用分类可固定到首页。
- **书架**：在线收藏、追更、浏览记录、标签收藏。
- **阅读器**：翻页、长条和自由缩放等模式，可自定义点按区域、滤镜和背景，支持墨水屏翻页步长；顶栏控制精简，全屏沉浸阅读。
- **下载**：章节级管理，可离线阅读；下载位置可选应用内部存储或系统文件夹；支持仅 Wi-Fi 下载、收藏时自动下载。
- **分流**：接口分流与图片分流同时并发测速，一键自动选择最快线路；支持从官方同步最新分流。
- **隐私**：应用锁（系统生物识别或锁屏密码）、防止截屏；应用内调用系统选择器不重复触发锁屏。
- **系统适配**：预测性返回、三种全屏模式、动态取色、深色主题。

## 下载安装

- 每次推送到 `main` 都会在 GitHub Actions 自动构建，APK 在对应运行记录的 Artifacts 里。
- 本项目遵循依次递进的补丁版本号（`1.0.0` → `1.0.1` → `1.0.2` …）。推送 `v` 开头的标签（如 `v1.0.1`，须与 `versionName` 一致）会自动触发 Release 发布并附上打包好的 release APK。

日常使用请安装 release 包：它关闭调试，启用代码优化、无用代码与资源压缩，并附带 ART 基线配置，适合低性能手机。

## 文档导航

| 文档 | 说明 |
| --- | --- |
| [README.md](README.md) | 项目简介、快速开始、开发构建与签名说明 |
| [CHANGELOG.md](CHANGELOG.md) | 版本发布历史与详细变更日志 |
| [docs/README.md](docs/README.md) | 文档导航索引与整体技术架构概述 |
| [docs/picacg-protocol.md](docs/picacg-protocol.md) | 哔咔漫画 HTTP 接口协议、请求头与 HMAC-SHA256 签名规范 |
| [docs/upstream-inventory.md](docs/upstream-inventory.md) | 原始功能与接口清单（页面 56 个、设置 81 项、接口 106 个） |
| [design/SPEC.md](design/SPEC.md) | 权威设计规范（Material 3 Expressive）、组件映射与历次决策日志 |
| [design/index.html](design/index.html) | 设计画板总览（01–33 号画板视觉预览与状态说明） |
| [core/data/README.md](core/data/README.md) | 本地数据层（Room schema v4、DataStore 偏好设置、DAO） |

## 开发环境

推荐使用 Nix：

```bash
nix develop   # JDK 21 · Android SDK 37 · Gradle · Kotlin · Node · Python 3
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

不用 Nix 时需要自行准备 JDK 21，以及 Android SDK 的 `platforms;android-37.0` 和 `build-tools;37.0.0`，并设置 `ANDROID_HOME`。

输出位置：

- Debug 包：`app/build/outputs/apk/debug/app-debug.apk`
- Release 包：`app/build/outputs/apk/release/app-release.apk`

完整检查（与 CI 相同）：

```bash
./gradlew test lintDebug assembleDebug assembleRelease
```

模拟器使用单独的环境：`nix develop .#emulator`（AVD `pika37`，API 37）。

### 模块

| 模块 | 内容 |
| --- | --- |
| `:app` | 界面、导航、各功能页 |
| `:feature:reader` | 阅读器 |
| `:core:domain` | 会话、分流、下载等业务逻辑 |
| `:core:data` | Room 数据库与 DataStore 设置 |
| `:core:network` | 纯 JVM 的哔咔接口客户端 |
| `:core:designsystem` | 主题与共用组件 |

## 签名

本地构建默认使用当前开发环境的 debug 密钥签名。保留同一把密钥即可直接覆盖安装，保留数据；`nix develop` 中的密钥位于项目的 `.android/debug.keystore`。新工作树或新机器构建时，需先复制原密钥，否则无法覆盖原安装。

需要自有签名时，在项目根目录创建 `keystore.properties`（已被 Git 忽略），格式如下；`storeFile` 相对项目根目录解析，也可使用绝对路径：

```properties
storeFile=release.keystore
storePassword=你的密钥库密码
keyAlias=你的密钥别名
keyPassword=你的密钥密码
```

密钥文件与密码不要提交。切换签名密钥后，不能直接覆盖旧签名的安装。

### CI 签名

本仓库已在 GitHub Actions 里配置签名密钥，与本地开发环境用的是同一把，所以 CI 构建的 release 包可以直接覆盖本地构建的安装，反过来也一样。

没有配置密钥的仓库（例如 fork）构建出的 release 包用一次性的 debug 密钥签名：可以全新安装，但不能覆盖已有安装，每次构建的签名也互不相同。

签名密钥通过仓库 Settings › Secrets and variables › Actions 里的四个 secret 提供：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库文件的 Base64（`base64 -w0 release.keystore`） |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

## 使用说明与免责声明

- **技术交流**：本项目仅供 Android 原生开发（Kotlin + Jetpack Compose）与网络协议技术学习交流使用。
- **无内容托管**：本客户端不包含、不托管、亦不分发任何漫画内容；使用过程中产生的所有网络流量与内容均通过官方接口或用户自行配置的节点直接获取。
- **许可说明**：本项目代码以 GNU GPL-3.0-or-later 许可开源，可自由使用、修改与再分发，完整文本见 [LICENSE](LICENSE)。
