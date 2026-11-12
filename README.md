# PicACGMD

> **PicACGMD** 是哔咔漫画（PicACG）的第三方 Android 客户端，使用 Kotlin 与 Jetpack Compose（Material 3 Expressive）原生编写，支持 Android 8.0 及以上。

[![Android CI](https://github.com/Better-Native-APPs/PicACGMD/actions/workflows/android.yml/badge.svg)](https://github.com/Better-Native-APPs/PicACGMD/actions/workflows/android.yml)
[![License: GPL v3+](https://img.shields.io/badge/License-GPL%20v3%2B-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg)](#系统要求)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF.svg)](#模块)
[![Material 3](https://img.shields.io/badge/Material%203-Expressive-6750A4.svg)](#功能特性)

---

## 项目简介

PicACGMD 为哔咔漫画提供完整可用的原生 Android 体验：所有功能直接可用，没有付费限制；不含遥测，只连接哔咔官方接口、图片服务器，以及用户自己配置的代理。下载线程 1–16 可调。

界面与交互遵循 Material 3 Expressive 设计语言。协议与架构文档见 [docs/](docs/README.md)，更新历史见 [CHANGELOG.md](CHANGELOG.md)。

---

## 功能特性

- **浏览与发现**：分类（含全分类）、排行榜、随机、搜索、游戏专区、公告；常用分类可固定到首页。
- **书架**：在线收藏、追更、浏览记录、标签收藏。
- **阅读器**：翻页（从右到左 / 从左到右）、长条（连续滚动）与自由缩放模式；自定义点按区域、背景与滤镜；墨水屏按屏滚动与残影优化；全屏沉浸阅读。
- **下载**：章节级多线程下载（1–16 线程可调），可选应用内部存储或系统文件夹；支持仅 Wi-Fi 下载与收藏时自动下载；下载内容供应用内离线阅读。
- **分流**：接口分流与图片分流并发测速，一键自动选择最快线路；支持自定义代理与从官方同步分流。
- **隐私**：应用锁（系统生物识别或锁屏密码）、防止截屏；不收集使用数据，记录仅保存在本机。
- **系统适配**：预测性返回、动态取色、深色与纯黑主题；「全屏显示」可隐藏导航栏或全部系统栏。
- **关于**：内置 GitHub 仓库入口；可手动检查新版本（仅手动触发，不做后台检查）。

---

## 系统要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Android 8.0（API 26）及以上 |
| 构建环境 | JDK 21；Android SDK `platforms;android-37.0` 与 `build-tools;37.0.0`（推荐直接使用 Nix，见下文） |

---

## 下载安装

- 在 [Releases](https://github.com/Better-Native-APPs/PicACGMD/releases) 下载最新 APK；推送 `v` 开头的标签（如 `v1.0.2`，须与 `versionName` 一致）会自动构建并发布 Release，Release 说明取自 [CHANGELOG.md](CHANGELOG.md) 中该版本的条目。
- 分支模型：`main` 只保留已发布的版本；开发提交进入 `dev` 分支。
- 版本号依次递进（`1.0.0` → `1.0.1` → `1.0.2` …）。
- 日常使用请安装 release 包：它关闭调试，启用代码优化、无用代码与资源压缩，并附带 ART 基线配置，适合低性能手机。

---

## 构建与运行

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

---

## 文档索引

| 文档 | 说明 |
| --- | --- |
| [CHANGELOG.md](CHANGELOG.md) | 版本发布历史与详细变更日志 |
| [docs/README.md](docs/README.md) | 文档导航索引与整体技术架构概述 |
| [docs/picacg-protocol.md](docs/picacg-protocol.md) | 哔咔漫画 HTTP 接口协议、请求头与 HMAC-SHA256 签名规范 |
| [docs/upstream-inventory.md](docs/upstream-inventory.md) | 原始功能与接口清单（页面 56 个、设置 81 项、接口 106 个） |
| [core/data/README.md](core/data/README.md) | 本地数据层（Room schema v4、DataStore 偏好设置、DAO） |

---

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

本仓库已在 GitHub Actions 里配置固定的发布密钥，所以每次 CI 构建和每个 Release 的安装包签名一致，可以直接覆盖安装、保留数据。

没有配置密钥的仓库（例如 fork）构建出的 release 包用一次性的 debug 密钥签名：可以全新安装，但不能覆盖已有安装，每次构建的签名也互不相同。

签名密钥通过仓库 Settings › Secrets and variables › Actions 里的四个 secret 提供：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库文件的 Base64（`base64 -w0 release.keystore`） |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

---

## 使用说明与免责声明

- **技术交流**：本项目仅供 Android 原生开发（Kotlin + Jetpack Compose）与网络协议技术学习交流使用。
- **无内容托管**：本客户端不包含、不托管、亦不分发任何漫画内容；使用过程中产生的所有网络流量与内容均通过官方接口或用户自行配置的节点直接获取。

---

## 开源许可与版权

- 本项目源码遵循 [GNU General Public License v3.0 或更高版本（GPL-3.0-or-later）](LICENSE)，可自由使用、修改与再分发。
- 本项目与哔咔漫画官方无隶属关系；漫画内容版权归原作者与平台所有。
