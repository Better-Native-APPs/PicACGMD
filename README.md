# Pikapika Android

用 Kotlin + Jetpack Compose（Material 3 Expressive）原生重写的 Pikapika（哔咔漫画第三方客户端），只支持 Android。

- 上游项目：<https://github.com/ComicSparks/pikapika>（Flutter + 私有 Go 内核）。本项目参考其功能与实现，代码独立编写。
- 与上游的差异：去掉所有发电 / Pro / 付费限制，全部功能直接可用；去掉遥测和对上游作者服务器的调用；下载线程上限从 5 提到 16。
- 当前阶段：设计稿（`design/`）。设计确认后再开始写应用代码。

## 开发环境

```bash
nix develop   # JDK 21 · Android SDK 36 · Gradle · Kotlin · Node · Python 3
```

## 使用说明

本项目代码以 GNU GPL-3.0-or-later 许可开源，可自由使用、修改与再分发，完整文本见 [LICENSE](LICENSE)。
