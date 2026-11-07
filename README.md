# Pikapika Android

哔咔漫画第三方客户端，用 Kotlin + Jetpack Compose（Material 3 Expressive）编写，只支持 Android 10 及以上。

- 所有功能直接可用，没有付费限制；不含遥测，只连接哔咔官方接口、图片服务器，以及用户自己配置的代理。
- 下载线程 1–16 可调。
- 设计稿见 `design/`（`design/SPEC.md`、`design/index.html`）。

## 开发环境

```bash
nix develop   # JDK 21 · Android SDK 37 · Gradle · Kotlin · Node · Python 3
./gradlew :app:assembleDebug
```

## 使用说明

本项目以 GNU GPL-3.0-or-later 许可开源，完整文本见 [LICENSE](LICENSE)。
