# Pikapika Android

哔咔漫画第三方客户端，用 Kotlin + Jetpack Compose（Material 3 Expressive）编写，只支持 Android 10 及以上。

- 所有功能直接可用，没有付费限制；不含遥测，只连接哔咔官方接口、图片服务器，以及用户自己配置的代理。
- 下载线程 1–16 可调。
- 设计稿见 `design/`（`design/SPEC.md`、`design/index.html`）。

## 开发环境

```bash
nix develop   # JDK 21 · Android SDK 37 · Gradle · Kotlin · Node · Python 3
./gradlew :app:assembleDebug --offline
```

Debug 包用于开发调试，输出为 `app/build/outputs/apk/debug/app-debug.apk`。

## 日常安装

日常使用请安装 release 包：它关闭调试，启用代码优化、无用代码与资源压缩，并附带 ART 基线配置，适合低性能手机。

```bash
nix develop
./gradlew :app:assembleRelease --offline
```

输出为 `app/build/outputs/apk/release/app-release.apk`。

默认使用当前开发环境的 debug 密钥签名。保留同一把密钥即可直接覆盖安装 debug 包，保留数据；`nix develop` 中的密钥位于项目的 `.android/debug.keystore`。新工作树或新机器构建时，需先复制原密钥，否则无法覆盖原安装。

需要自有签名时，在项目根目录创建 `keystore.properties`（已被 Git 忽略），格式如下；`storeFile` 相对项目根目录解析，也可使用绝对路径：

```properties
storeFile=release.keystore
storePassword=你的密钥库密码
keyAlias=你的密钥别名
keyPassword=你的密钥密码
```

密钥文件与密码不要提交。切换签名密钥后，不能直接覆盖旧签名的安装。

## 使用说明

本项目以 GNU GPL-3.0-or-later 许可开源，完整文本见 [LICENSE](LICENSE)。
