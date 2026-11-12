# PicACGMD 文档索引

欢迎查阅 PicACGMD 项目文档。本项目为基于 Kotlin + Jetpack Compose（Material 3 Expressive）构建的原生 Android 哔咔漫画客户端，支持 Android 8.0（API 26）及以上系统。

## 文档导航

| 文档 | 路径 | 说明 |
| :--- | :--- | :--- |
| **项目主文档** | [`../README.md`](../README.md) | 项目概览、功能特性、本地开发环境配置、编译打包与签名发布说明 |
| **版本更新日志** | [`../CHANGELOG.md`](../CHANGELOG.md) | 遵循语义化补丁版本（`1.0.0` → `1.0.1` …）的详细发布记录与变动细节 |
| **API 协议参考** | [`picacg-protocol.md`](picacg-protocol.md) | 哔咔漫画官方 HTTP 接口规范、请求头约束、网络拓扑与 HMAC-SHA256 签名算法 |
| **本地数据层架构** | [`../core/data/README.md`](../core/data/README.md) | Room 数据库（Schema v4）、DataStore 偏好设置及 DAO 接口规范 |

---

## 模块架构

工程划分为六个核心模块，职责边界清晰：

```text
├── :app                   # 应用主入口、顶层导航与界面承载（MainActivity, NavHost）
├── :feature:reader        # 核心阅读器模块（翻页、长条、自由缩放、双页、预加载）
├── :core:domain           # 业务逻辑层（会话管理、下载引擎、分流测速、历史与订阅）
├── :core:data             # 本地持久化层（Room 数据库 pikapika.db、DataStore 偏好）
├── :core:network          # 纯 JVM 哔咔接口客户端（Retrofit/OkHttp, HMAC-SHA256 签名）
└── :core:designsystem     # 设计系统组件与主题（Material 3 Expressive, Loading Sheen）
```

## 核心设计与实现原则

1. **纯粹 Android 原生**：
   - 采用纯 Kotlin + Jetpack Compose 与 Material 3 Expressive 构建，适配 Android 8.0+。
   - 不引入跨平台层，无冗余多平台抽象。
2. **零遥测与直接连接**：
   - 不集成任何统计 SDK、追踪埋点或作者私有服务器逻辑。
   - 仅与哔咔官方 API 节点、图片服务器或用户自行配置的代理进行直接通信。
3. **全面开放与无障碍体验**：
   - 彻底删除原版的所有付费特权门槛、发电限制与功能封锁。
   - 下载并发支持 1–16 线程自由配置（默认 4 线程，低内存设备默认 2 线程）。
4. **轻量与性能优化**：
   - 统一的分流测速选择器（接口与图片合并测速、自动选优、官方同步）。
   - 预测性返回轻量化（90% 中心缩放与 28 dp 圆角，无昂贵图层阴影与多余重排）。
   - 图片预加载调度（列表与阅读器预读）搭配柔和流动光泽（Loading Sheen）占位。
