# 哔咔漫画 (PicACG) 协议与数据规范参考文档

本文档为基于 Kotlin + Jetpack Compose 的 Android 原生重构提供底层协议规范、数据格式及技术选型参考。内容严格基于源码进行整理，所有接口、字段及逻辑均标注源码引用路径（`reference/...:line`）。未在公开源码中实现的高级私有特性均明确标记为「仅私有内核」或「无公开实现」，不确定的行为标记为「未确认」。

---

## 1. HTTP 基础

### 1.1 Base URL 与网络拓扑
- **主接口 Base URL**: `https://picaapi.picacomic.com/`（源码位置：`reference/pica-go/client.go:30`）。
- **初始化服务器 IP**: `http://68.183.234.72/init`（源码位置：`reference/pica-go/client.go:675`），用于早期动态拉取分流地址列表与应用初始状态。
- **静态图片文件服务器**: 由 API 接口返回的 `fileServer` 决定，默认前缀格式为 `fileServer + "/static/" + path`（源码位置：`reference/pikapika-go-core-2022/pikapika/image.go:92`）。常用服务器域名包括 `https://storage.wikawika.xyz`、`https://s2.picacomic.com`、`https://s3.picacomic.com`。

### 1.2 HTTP 请求头规范
在向主 API 发起请求时，必须附加以下所有固定的请求头信息（源码位置：`reference/pica-go/client.go:41-73`）：

| Header 名称 | 默认值 / 格式 | 含义与说明 | 源码位置 |
| :--- | :--- | :--- | :--- |
| `api-key` | `C69BAF41DA5ABD1FFEDC6D2FEA56B` | 官方 Android 客户端公共 API 密钥 | `reference/pica-go/client.go:42` |
| `accept` | `application/vnd.picacomic.com.v1+json` | 声明使用 PicACG v1 版本的 JSON 协议 | `reference/pica-go/client.go:47` |
| `app-channel` | `2` | 客户端发布渠道标识（2 代表 Android 官方发布版） | `reference/pica-go/client.go:48` |
| `time` | 10 位十进制时间戳（如 `"1582250000"`） | 请求发起时的 Unix 时间戳（秒级），参与 HMAC 签名 | `reference/pica-go/client.go:49` |
| `nonce` | 32 位 UUID 字符串（无连字符） | 随机 Nonce，默认可用 `b1ab87b4800d4d4590a11701b8551afa` 或动态生成 | `reference/pica-go/client.go:43` |
| `signature` | 64 位十六进制小写字符串 | 请求签名，基于 HMAC-SHA256 计算生成 | `reference/pica-go/client.go:51,66` |
| `app-version` | `2.2.1.2.3.3` | 模拟的上游客户端版本号 | `reference/pica-go/client.go:52` |
| `app-uuid` | `defaultUuid` | 客户端设备唯一标识符（固定值或随机 UUID） | `reference/pica-go/client.go:53` |
| `app-platform` | `android` | 客户端操作系统平台 | `reference/pica-go/client.go:54` |
| `app-build-version` | `44` | 内部构建版本号 | `reference/pica-go/client.go:55` |
| `Content-Type` | `application/json; charset=UTF-8` | 请求体格式编码 | `reference/pica-go/client.go:56` |
| `User-Agent` | `okhttp/3.8.1` | 客户端 UA 标识 | `reference/pica-go/client.go:57` |
| `authorization` | JWT Token 字符串（未登录为空字符串） | 登录成功后由服务端签发的认证 Token | `reference/pica-go/client.go:58` |
| `image-quality` | `original` \| `low` \| `medium` \| `high` | 图片分流与清晰度控制头，默认 `original` | `reference/pica-go/client.go:59-60` |

### 1.3 签名算法 (Signing Algorithm)
上游所有发往 API 的请求均通过 HMAC-SHA256 进行数字签名，防止篡改与重放（源码位置：`reference/pica-go/client.go:61-66`）。

#### 算法细节
1. **待签名 URL 路径**:
   取请求的相对路径与 Query 参数（即 HTTP Request-URI，去除协议与域名），并**移除最开头的斜杠 `/`**。
   例如：对于 `https://picaapi.picacomic.com/comics?page=1`，取 `comics?page=1`；对于 `auth/sign-in`，取 `auth/sign-in`。
2. **待签名原始串拼接规则**:
   $$\text{raw} = \text{url\_path\_and\_query} + \text{time} + \text{nonce} + \text{method} + \text{apiKey}$$
   - 注意各个参数的先后顺序：先路径参数，再 `time`，再 `nonce`，再 HTTP 请求方法（大写如 `POST` 或 `GET`），最后为 `apiKey`。
3. **大小写统一处理（关键步骤）**:
   拼接完成后的 `raw` 字符串必须**全部转换为全小写**：
   $$\text{raw} = \text{raw.toLowerCase()}$$
4. **HMAC 密钥**:
   公开源码中的密钥字符串为（UTF-8 编码，包含转义反斜杠，长度共 63 字节）：
   `~d}$Q7$eIni=V)9\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn`
   （注：Go 字符串字面量中写作 `"~d}$Q7$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"`，中间的双斜杠是单个反斜杠字符 `\` 的转义）。
5. **散列计算与格式化**:
   使用 `HmacSHA256` 算法计算哈希值，并将输出的 32 字节摘要编码为 **64 位小写十六进制字符串** (`hex.EncodeToString`)。

#### 实测验证样例 (Worked Example)
选取固定输入参数如下：
- **Path & Query**: `auth/sign-in`
- **Method**: `POST`
- **Time**: `1582250000`
- **Nonce**: `b1ab87b4800d4d4590a11701b8551afa`
- **API Key**: `C69BAF41DA5ABD1FFEDC6D2FEA56B`
- **转为小写前**: `auth/sign-in1582250000b1ab87b4800d4d4590a11701b8551afaPOSTC69BAF41DA5ABD1FFEDC6D2FEA56B`
- **转为小写后 (`raw`)**: `auth/sign-in1582250000b1ab87b4800d4d4590a11701b8551afapostc69baf41da5abd1ffedc6d2fea56b`

运行以下 Python 3 代码即可验证签名输出：
```python
import hashlib
import hmac

secret = b"~d}$Q7$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"
raw = "auth/sign-in1582250000b1ab87b4800d4d4590a11701b8551afapostc69baf41da5abd1ffedc6d2fea56b"
signature = hmac.new(secret, raw.encode("utf-8"), hashlib.sha256).hexdigest()
print("Signature:", signature)
# 实测输出 (2026-11-07, python3): 5e0d8becedd8af6302e0c52efc43ad7040ff36feff68b2983c84e5141143992e
```

### 1.4 响应信封格式与错误处理
哔咔 API 所有响应体均为统一样式的 JSON 信封（源码位置：`reference/pica-go/dto.go:6-11`，`reference/pica-go/client.go:138-146`）：
```json
{
  "code": 200,
  "error": "",
  "message": "success",
  "detail": "",
  "data": { ... }
}
```
- **业务状态码**: `code == 200` 代表成功；若 `code != 200`，上游直接抛出 `error(response.Message)`。
- **通用分页包装**: 分页接口的数据包装在 `data` 下的对应对象中（如 `data.comics`），结构包含 `PageData` 属性（`total`, `limit`, `page`, `pages`, `docs: [...]`）（`reference/pica-go/dto.go:14-19`）。

### 1.5 认证生命周期 (Authentication Lifecycle)
- **获取 Token**: 通过 `POST auth/sign-in` 提交 `email`（实际可为账号名或邮箱）与 `password`，在成功响应 `LoginResponse` 的 `data.token` 中取得 JWT 字符串（`reference/pica-go/client.go:155-171`）。
- **Token 注入**: 后续所有需要登录的 API 请求必须在请求头 `authorization` 中携带该 Token。
- **持久化与有效期控制**:
  2022 核心在 `properties.db` 中分别持久化 `token` 与 `token_time`（毫秒时间戳）（`reference/pikapika-go-core-2022/pikapika/database/properties/properties.go:109-127`）。
  上游预登录逻辑 `preLogin()`（`reference/pikapika-go-core-2022/pikapika/pikapika.go:166-180`）：
  - 判定条件：若 `token` 存在且 `token_time > 0`，且当前时间距离记录时间不超过 24 小时（`Timestamp() - (1000*60*60*24) < tokenTime`），则复用本地 Token 视为已登录。
  - 自动重登：若超时或 Token 为空，则自动读取本地保存的 `username` 与 `password` 静默重新调用 `Login()` 刷新 Token。
  - 登出：`clearToken()` 将 `token_time` 置为 0，`token` 置空（`reference/pikapika-go-core-2022/pikapika/pikapika.go:206-210`）。

### 1.6 超时与重试策略
- **客户端全局超时**: Go 客户端设置 `client.Timeout = 60 * time.Second`（`reference/pikapika-go-core-2022/pikapika/client.go:28`）。
- **底层连接超时**:
  - `net.Dialer`: `Timeout = 30s`, `KeepAlive = 30s`（`reference/pikapika-go-core-2022/pikapika/client.go:36-39`）。
  - `http.Transport`: `TLSHandshakeTimeout = 10s`, `ResponseHeaderTimeout = 10s`, `ExpectContinueTimeout = 10s`, `IdleConnTimeout = 10s`（`reference/pikapika-go-core-2022/pikapika/client.go:54-57`）。
- **下载重试**: 章节图片地址拉取和图片本体下载在上游均采用**最多连续重试 5 次**的简单循环重试（`reference/pikapika-go-core-2022/pikapika/download.go:122,307`）。

---

## 2. 分流 / 代理 / 图片服务器

### 2.1 API 分流机制与底层网络实现
国内访问 `picaapi.picacomic.com` 经常遭遇 DNS 污染或 SNI 阻断。上游的核心解决方案是通过自定义 IP 直连（分流）绕过 DNS 解析（源码位置：`reference/pikapika-go-core-2022/pikapika/client.go:41-95`，`reference/pica-go/examples/examples.go:44-64`）。

#### 分流 IP 映射表
在 2022 核心中硬编码的分流 IP 为 Cloudflare Anycast 节点（端口均为 443）：
- **分流 1**: `172.67.7.24:443`
- **分流 2**: `104.20.180.50:443`
- **分流 3**: `172.67.208.169:443`
- **分流 0**: 不分流，使用系统默认 DNS 解析（`reference/pikapika-flutter/lib/basic/config/Address.dart:15-27`）。

#### Host 与 TLS/SNI 处理机制
在 Go 原生实现中，上游替换了 `http.Transport` 的 `DialContext`：
- 当请求 URL 匹配 `switchAddressPattern`（`^.+picacomic\.com:\d+$`）或当前选定了 `switchAddress` 时，底层 TCP 拨号的目标 IP 直接被硬编码重定向为对应的 `switchAddresses[switchAddress]`（例如 `104.20.180.50:443`）。
- **TLS 握手不受影响**: 由于请求的 `req.URL.Host` 依然为 `picaapi.picacomic.com`，Go 标准库在建立 TLS 握手时发送的 **SNI (Server Name Indication)** 以及 HTTP 请求头的 **`Host` 字段均保持为 `picaapi.picacomic.com`**，证书校验亦按该域名执行，从而实现了合法的 Cloudflare CDN 回源。
- **Kotlin 移植方案**: 在 OkHttp 中，最优雅且无侵入的实现方式是自定义 `okhttp3.Dns` 接口：当查询域名为 `picaapi.picacomic.com` 时，直接返回分流配置对应的 `InetAddress` 列表，OkHttp 会自动完成正确的 SNI 和 Host 处理。

### 2.2 图片服务器与图片分流 (ImageAddress)
图片加载逻辑位于 `reference/pikapika-go-core-2022/pikapika/image.go`。
- **图片域名覆盖**:
  API 接口在返回图片实体时包含 `fileServer` 字段（如 `https://storage1.picacomic.com`）。但上游提供了图片分流地址映射（`reference/pikapika-go-core-2022/pikapika/image.go:29-33`）：
  - **分流 1**: `https://storage.wikawika.xyz`
  - **分流 2**: `https://s2.picacomic.com`
  - **分流 3**: `https://s3.picacomic.com`
  - **分流 0**: 使用 API 原生下发的 `fileServer`。
- **URL 组装规则**:
  最终下载 URL 为：
  $$\text{targetUrl} = \text{fileServer} + \text{"/static/"} + \text{path}$$
  （若选定了图片分流，则 `fileServer` 替换为分流域名）。
- **并发互斥限制**:
  在 2022 公开核心中，由于担心图片并发加载触发封禁，定义了一个长度为 5 的互斥锁数组 `subMutexes`（`reference/pikapika-go-core-2022/pikapika/image.go:39-41,56-61`），导致即时浏览图片时最多仅允许 5 个图片连接同时下载。

### 2.3 `UseApiLoadImage` 参数含义
- **参数来源**: `getUseApiClientLoadImage` / `setUseApiClientLoadImage`（`reference/pikapika-flutter/lib/basic/config/UseApiLoadImage.dart`，`reference/pikapika-go-core-2022/pikapika/image.go:83-85`）。
- **核心行为**:
  - `false` (默认): 使用独立的轻量级 HTTP 客户端 `imageHttpClient` 下载图片。该客户端不携带 API 签名、Token 等业务 Header。
  - `true`: 强制复用 API 主客户端 `client.Client` 下载图片。此时下载图片会经过 API 的分流规则、代理配置，并携带全部 API Header（如 `api-key`、`signature`、`image-quality`、`authorization` 等），用于在图片 CDN 发生防盗链限制或直连网络中断时尝试借由 API 链路加载。

### 2.4 代理配置 (Proxy)
- **协议支持**: 支持 `http://host:port` 与 `socks5://host:port`（`reference/pika-go/examples/examples.go:24-39`）。
- **配置持久化**: 保存于 `properties.db` 中的 `proxy` 字段（`reference/pikapika-go-core-2022/pikapika/database/properties/properties.go:85-91`）。
- **底层联动**: 当代理字符串非空时，重新构造带有代理拨号器的 `http.Transport`（`reference/pikapika-go-core-2022/pikapika/client.go:76-113`）。若同时配置了代理与分流 IP，则优先通过代理连接分流 IP。

### 2.5 图片质量控制 (`image-quality`)
- **可选代码**: `original` (原图), `low` (低), `medium` (中), `high` (高)（`reference/pica-go/enum.go:14-17`，`reference/pikapika-flutter/lib/basic/config/Quality.dart:7-10`）。
- **作用机制**: 客户端在调用 `comicPicturePageWithQuality` 获取章节分页，或部分服务端图片缩放请求时，通过设置 HTTP Header `image-quality` 将质量参数传递给服务端（`reference/pica-go/client.go:123`）。

---

## 3. 接口清单

本节梳理 Flutter 前端 `reference/pikapika-flutter/lib/basic/Method.dart` 中与 PicACG 服务端直接通信的全部网络方法，对照 `pica-go` 与 2022 核心的 DTO 及实现建立映射表。

| Method.dart 方法名 | HTTP 方法 | 请求 Path 与 Query / Body | 请求关键参数 | 响应核心字段 / DTO | 状态与源码位置 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `login` | `POST` | `auth/sign-in` | Body: `email`, `password` | `data.token` (`LoginResponse`) | 公开实现 (`pica-go/client.go:155`) |
| `register` | `POST` | `auth/register` | Body: `RegisterDto` (email, password, name, birthday, gender, question1~3, answer1~3) | `code`, `message` | 公开实现 (`pica-go/client.go:149`) |
| `userProfile` | `GET` | `users/profile` | 无 | `data.user`: `_id`, `name`, `birthday`, `email`, `isPunched`, `avatar` (`UserProfileResponse`) | 公开实现 (`pica-go/client.go:173`) |
| `punchIn` | `POST` | `users/punch-in` | 无 (空 Body) | `data.res`: `status`, `punchInLastDay` (`PunchResponse`) | 公开实现 (`pica-go/client.go:187`) |
| `updatePassword` | `PUT` | `users/password` | Body: `{"old_password": "...", "new_password": "..."}` | `code`, `message` | 公开实现 (`pica-go/client.go:598`) |
| `updateSlogan` | `PUT` | `users/profile` | Body: `{"slogan": "..."}` | `code`, `message` | 公开实现 (`pica-go/client.go:608`) |
| `updateAvatar` | `PUT` | `users/avatar` | Body: `{"avatar": "data:image/jpeg;base64,..."}` (要求 200x200 JPEG) | `code`, `message` | 公开实现 (`pica-go/client.go:617`) |
| `forgotPassword` | `POST` | `auth/forgot-password` | Body: `{"email": "..."}` | `data`: `question1`, `question2`, `question3` (`ForgotPasswordResponse`) | 公开实现 (`pica-go/client.go:640`) |
| `resetPassword` | `POST` | `auth/reset-password` | Body: `{"email": "...", "questionNo": int, "answer": "..."}` | `data`: `password` (新生成的重置密码) (`ResetPasswordResponse`) | 公开实现 (`pica-go/client.go:656`) |
| `categories` | `GET` | `categories` | 无 | `data.categories`: `_id`, `title`, `description`, `thumb`, `isWeb`, `active`, `link` | 公开实现 (`pica-go/client.go:201`) |
| `comics` | `GET` | `comics?c=&t=&a=&ca=&ct=&s=&page=` | Query: `c`(分类), `t`(标签), `a`(作者), `ca`(上传者ID), `ct`(汉化组), `s`(排序), `page` | `data.comics`: `total`, `page`, `pages`, `docs`: `_id`, `title`, `author`, `thumb`, `likesCount`, `categories` | 公开实现 (`pica-go/client.go:215`) |
| `searchComics` / `searchComicsInCategories` | `POST` | `comics/advanced-search?page=` | Query: `page`; Body: `{"keyword": "...", "sort": "...", "categories": [...]}` | `data.comics`: `docs` (注：此接口官方不返回 `pagesCount` 与 `epsCount`) | 公开实现 (`pica-go/client.go:246,268`) |
| `randomComics` | `GET` | `comics/random` | 无 | `data.comics`: `[]ComicSimple` | 公开实现 (`pica-go/client.go:286`) |
| `leaderboard` | `GET` | `comics/leaderboard?tt=&ct=VC` | Query: `tt` (`H24` \| `D7` \| `D30`), `ct=VC` | `data.comics`: `[]ComicSimple` | 公开实现 (`pica-go/client.go:300`) |
| `leaderboardOfKnight` | `GET` | `comics/knight-leaderboard` | 无 | `data.users`: `_id`, `name`, `level`, `avatar`, `role`, `comicsUploaded` (`Knight`) | 公开实现 (`pica-go/client.go:520`) |
| `collections` | `GET` | `collections` | 无 | `data.collections`: `title`, `comics: []ComicSimple` | 公开实现 (`pica-go/client.go:627`) |
| `comicInfo` / `comicInfoA` | `GET` | `comics/{comicId}` | 路径参数: `comicId` (`comicInfoA` 仅在本地不记录历史) | `data.comic`: `_id`, `title`, `description`, `_creator`, `chineseTeam`, `tags`, `isFavourite`, `isLiked` | 公开实现 (`pica-go/client.go:314`) |
| `comicEpPage` | `GET` | `comics/{comicId}/eps?page=` | 路径参数: `comicId`; Query: `page` | `data.eps`: `page`, `pages`, `docs`: `_id`, `title`, `order`, `updated_at` | 公开实现 (`pica-go/client.go:328`) |
| `comicPicturePageWithQuality` | `GET` | `comics/{comicId}/order/{epOrder}/pages?page=` | Header: `image-quality`; Query: `page` | `data.pages`: `docs`: `_id`, `media`: `fileServer`, `path`, `originalName`; `data.ep` | 公开实现 (`pica-go/client.go:342,356`) |
| `switchLike` | `POST` | `comics/{comicId}/like` | 路径参数: `comicId` | `data.action`: `"like"` \| `"unlike"` | 公开实现 (`pica-go/client.go:370`) |
| `switchFavourite` | `POST` | `comics/{comicId}/favourite` | 路径参数: `comicId` | `data.action`: `"favourite"` \| `"un_favourite"` | 公开实现 (`pica-go/client.go:385`) |
| `favouriteComics` | `GET` | `users/favourite?s=&page=` | Query: `s` (排序代码), `page` | `data.comics`: `PageData` 与 `[]ComicSimple` | 公开实现 (`pica-go/client.go:401`) |
| `recommendation` | `GET` | `comics/{comicId}/recommendation` | 路径参数: `comicId` | `data.comics`: `[]ComicSimple` (看了此漫画的人还在看) | 公开实现 (`pica-go/client.go:492`) |
| `comments` | `GET` | `comics/{comicId}/comments?page=` | 路径参数: `comicId`; Query: `page` | `data.comments`: `[]Comment`, `data.topComments`: `[]Comment` | 公开实现 (`pica-go/client.go:414`) |
| `commentChildren` | `GET` | `comments/{commentId}/childrens?page=` | 路径参数: `commentId`; Query: `page` | `data.comments`: `[]CommentChild` (`_parent` 指向父评论) | 公开实现 (`pica-go/client.go:455`) |
| `postComment` | `POST` | `comics/{comicId}/comments` | 路径参数: `comicId`; Body: `{"content": "..."}` | `code`, `message` (评论发表后官方不支持用户端删除) | 公开实现 (`pica-go/client.go:441`) |
| `postChildComment` | `POST` | `comments/{commentId}` | 路径参数: `commentId`; Body: `{"content": "..."}` | `code`, `message` | 公开实现 (`pica-go/client.go:469`) |
| `switchLikeComment` | `POST` | `comments/{commentId}/like` | 路径参数: `commentId` | `data.action`: `"like"` \| `"unlike"` | 公开实现 (`pica-go/client.go:477`) |
| `myComments` | `GET` | `users/my-comments?page=` | Query: `page` | `data.comments`: `[]MyComment` (包含所评论的 `_comic._id` 与标题) | 公开实现 (`pica-go/client.go:428`) |
| `games` | `GET` | `games?page=` | Query: `page` | `data.games`: `docs`: `_id`, `title`, `version`, `icon`, `publisher`, `adult`, `android`, `ios` | 公开实现 (`pica-go/client.go:534`) |
| `game` | `GET` | `games/{gameId}` | 路径参数: `gameId` | `data.game`: `description`, `updateContent`, `videoLink`, `screenshots`, `androidLinks`, `downloadsCount` | 公开实现 (`pica-go/client.go:548`) |
| `gameComments` | `GET` | `games/{gameId}/comments?page=` | 路径参数: `gameId`; Query: `page` | `data.comments`: `[]GameComment`, `topComments` | 公开实现 (`pica-go/client.go:562`) |
| `postGameComment` | `POST` | `games/{gameId}/comments` | 路径参数: `gameId`; Body: `{"content": "..."}` | `code`, `message` | 公开实现 (`pica-go/client.go:576`) |
| `gameCommentChildren` | `GET` | `comments/{commentId}/childrens?page=` | 路径参数: `commentId`; Query: `page` | `data.comments`: `[]GameCommentChild` (与漫画共用子评论接口) | 公开实现 (`pica-go/client.go:584`) |
| `postGameChildComment` | `POST` | `comments/{commentId}` | 路径参数: `commentId`; Body: `{"content": "..."}` | `code`, `message` (同 `postChildComment`) | 公开实现 (`pikapika-go-core-2022/pikapika/client.go:454`) |
| `switchLikeGameComment`| `POST` | `comments/{commentId}/like` | 路径参数: `commentId` | `data.action`: `"like"` \| `"unlike"` | 公开实现 (`pikapika-go-core-2022/pikapika/client.go:574`) |
| `hotKeywords` (底层库) | `GET` | `keywords` | 无 | `data.keywords`: `[]string` (大家都在搜) | 公开实现 (`pica-go/client.go:506`) |
| `hideComment` (底层库) | `POST` | `comments/{commentId}/delete` | 路径参数: `commentId` | `code`, `message` (管理端删除接口，普通用户受限) | 公开实现 (`pica-go/client.go:449`) |
| `initInfo` (底层库) | `GET` | `http://68.183.234.72/init` | 无 | `status`, `addresses`, `waka`, `adKeyword` | 公开实现 (`pica-go/client.go:674`) |

*(注：本节覆盖 `Method.dart` 中与 PicACG 直接通信的 36 个方法及 `pica-go` 中暴露的全部 3 个底层协议接口，共计 39 个服务端接口定义。)*

---

## 4. 本地数据与持久化设计

在 2022 公开核心（`reference/pikapika-go-core-2022/pikapika/database/`）中，采用 SQLite 数据库配合 GORM 框架，拆分为 3 个独立的数据库文件。

### 4.1 属性配置库 (`properties.db`)
源码位置：`reference/pikapika-go-core-2022/pikapika/database/properties/properties.go`。
- **表结构 `properties`**:
  - `id` (INTEGER, Primary Key, Auto Increment)
  - `created_at`, `updated_at`, `deleted_at` (TIMESTAMP)
  - `k` (TEXT, Unique Index `uk_k`): 配置键名称。
  - `v` (TEXT): 对应的值。
- **存储的关键字段**:
  - `username` / `password`: 凭据。
  - `token` / `token_time`: JWT 认证串及更新时间戳。
  - `proxy`: HTTP / SOCKS5 代理串。
  - `switchAddress` / `imageSwitchAddress`: API 及图片分流代号。
  - `downloadThreadCount`: 并发下载数（上游默认为 `"2"`）。
  - `downloadAndExportPath`: 边下边导出的外部文件夹路径。
  - `downloadCachePath`: 下载缓存路径。
  - `quality`: 默认图片质量。
  - `timeOffsetHour`: 时区偏移（默认 8）。

### 4.2 网络缓存库 (`network_cache.db`)
源码位置：`reference/pikapika-go-core-2022/pikapika/database/network_cache/cache.go`。
- **表结构 `network_caches`**:
  - `id`, `created_at`, `updated_at`, `deleted_at`
  - `k` (TEXT, Unique Index `uk_k`): 缓存键。
  - `v` (TEXT): 服务端下发的完整响应 JSON 字符串。
- **缓存策略与过期机制**:
  - `CATEGORIES` / `COLLECTIONS`: 缓存有效期 **3 小时**（`client.go:126,144`）。
  - `COMICS$...` / `SEARCH$...`: 缓存有效期 **2 小时**（`client.go:171,200`）。
  - `COMIC_INFO${comicId}`: 缓存有效期 **7 天**（`client.go:232`）。
  - `COMIC_EP_PAGE$...` / `COMMENTS$...`: 缓存有效期 **2 小时**。
  - `LEADERBOARD$...`: 缓存有效期 **200 秒**。
  - `RANDOM`: 缓存有效期 **1 毫秒**（相当于禁用缓存）。
  - **联动失效**: 在用户执行 `switchLike` / `switchFavourite` 时，主动移除 `COMIC_INFO${comicId}` 缓存；在 `postComment` 时通过 `RemoveCaches("COMMENTS$" + comicId + "$%")` 模糊匹配清空该漫画的评论分页缓存（`client.go:342,433`）。
  - **自动清理**: `autoClean(expireSec)` 查询 `updated_at < now - expireSec` 的旧缓存，执行 `DELETE` 并调用 `VACUUM` 压缩数据库。

### 4.3 漫画中心库 (`comic_center.db`)
源码位置：`reference/pikapika-go-core-2022/pikapika/database/comic_center/entities.go`。
该库是核心业务枢纽，涵盖浏览历史、阅读进度、本地图片索引及完整下载任务状态。

#### 1. 浏览历史与进度表 (`comic_views`)
- `id` (TEXT, PK): 漫画 `comicId`。
- `created_at`, `updated_at`: 记录创建与最后更新时间。
- `title`, `author`, `description`, `chinese_team`: 漫画标题、作者、简介、汉化组。
- `pages_count`, `eps_count`: 总页数、总章节数。
- `finished`: 是否完结。
- `categories`: 分类数组序列化 JSON。
- `tags`: 标签数组序列化 JSON。
- `thumb_original_name`, `thumb_file_server`, `thumb_path`: 封面元数据。
- `likes_count`, `views_count`, `comments_count`: 互动计数。
- `is_favourite`, `is_liked`: 点赞/收藏标记。
- `last_view_time` (TIMESTAMP): 最后阅读时间，用于历史排序。
- `last_view_ep_order` (INTEGER): 最后阅读章节序号。
- `last_view_ep_title` (TEXT): 最后阅读章节名称。
- `last_view_picture_rank` (INTEGER): 最后阅读章节内的图片页码（从 1 开始）。

#### 2. 远端图片缓存索引表 (`remote_images`)
用于记录通过 `remoteImageData` 下载并缓存到本地磁盘的图片（`image.go:223-241`）：
- `id`, `created_at`, `updated_at`: 记录时间。
- `file_server`, `path` (TEXT, 联合唯一索引 `uk_fp`): 图片网络标识。
- `file_size` (BIGINT): 文件字节大小。
- `format` (TEXT): 图像格式（`jpeg`, `png`, `webp`, `gif`）。
- `width`, `height` (INTEGER): 图片像素尺寸。
- `local_path` (TEXT): 存储在本地图片缓存目录的相对文件名（命名规则为 `MD5(fileServer + "$" + path)`）。

#### 3. 漫画下载主表 (`comic_downloads`)
- `id` (TEXT, PK): 漫画 `comicId`。
- `title`, `author`, `categories`, `tags`, `description`, `chinese_team`: 基本信息。
- `selected_ep_count`, `selected_picture_count`: 用户选择下载的总章节数、总图片数。
- `download_ep_count`, `download_picture_count`: 已完成下载的章节数、图片数。
- `download_finished` (BOOLEAN): 全部章节与图片是否均已成功下载。
- `download_finished_time` (TIMESTAMP): 下载完成时间。
- `download_failed` (BOOLEAN): 任务是否失败（遇到不可恢复错误）。
- `deleting` (BOOLEAN): 软删除标记。用户在 UI 点击删除后先标记此字段，后台线程空闲时执行物理删除。
- `thumb_file_size`, `thumb_format`, `thumb_width`, `thumb_height`, `thumb_local_path`: 封面缓存信息（存储于 `{comicId}/logo`）。
- `pause` (BOOLEAN): 单任务暂停标记。

#### 4. 下载章节表 (`comic_download_eps`)
- `id` (TEXT, PK): 章节 `epId`。
- `comic_id` (TEXT, 索引 `idx_comic_id`): 所属漫画 ID。
- `ep_order` (INTEGER): 章节序号（官方序号，如 1, 2, 3）。
- `title` (TEXT): 章节标题。
- `fetched_pictures` (BOOLEAN): 是否已从服务端分页拉取过该章节的全部图片列表。
- `selected_picture_count`, `download_picture_count`: 本章节总图片数、已完成下载数。
- `download_finished`, `download_finished_time`, `download_failed`: 章节完成与失败状态。

#### 5. 下载图片表 (`comic_download_pictures`)
- `id` (TEXT, PK): 图片对象 `pictureId`。
- `comic_id` (TEXT, 索引 `idx_comic_id`): 所属漫画 ID。
- `ep_id` (TEXT, 索引 `idx_ep_id`): 所属章节 ID。
- `ep_order` (INTEGER, 索引 `idx_ep_order`): 章节序号。
- `rank_in_ep` (INTEGER): 图片在章节内的排列序号（从 1 开始）。
- `original_name`, `file_server`, `path` (TEXT, 联合索引 `idx_fp`): 服务端图片元数据。
- `file_size`, `format`, `width`, `height`: 图片字节大小、格式和尺寸。
- `local_path` (TEXT): 本地存储路径，固定格式为 `{comicId}/{epOrder}/{rankInEp}`（磁盘无后缀，依靠 `format` 字段读取）。
- `download_finished`, `download_finished_time`, `download_failed`: 单张图片完成与失败标记。

---

## 5. 下载引擎

2022 核心下载管道由后台 Goroutine 循环驱动（源码位置：`reference/pikapika-go-core-2022/pikapika/download.go`）。

### 5.1 队列状态机与工作流程
下载调度按照以下状态循环流转：
```
downloadBackground() (启动入口)
  ↓
downloadBegin() (休眠 3 秒防频闪)
  ↓
downloadDelete() (检查并清理 deleting=true 的物理文件与库记录)
  ↓
downloadLoadComic() (查询首个未完成的 ComicDownload)
  ↓
downloadInitComic() (检查 ep.FetchedPictures; 若为 false 则分页请求 API 拉取图片列表并入库)
  ↓
downloadLoadEp() (查询首个未完成的 ComicDownloadEp)
  ↓
downloadLoadPicture() (从数据库取出当前 Ep 待下载的图片切片，交给并发池)
  ↓
downloadPicture() (执行单图下载与落盘，每张图重试至多 5 次)
  ↓
downloadSummaryEp() (汇总章节状态：全部成功则标记 EpSuccess，否则标记 EpFailed)
  ↓
downloadSummaryDownload() (汇总漫画状态：全部章节成功则标记 DownloadSuccess，否则 DownloadFailed)
  ↓
回到 downloadLoadComic() 执行下一个任务
```

### 5.2 磁盘文件布局
所有漫画保存在 `downloadDir` 目录下：
```
{downloadDir}/
  └── {comicId}/
        ├── logo                # 漫画封面缩略图 (无后缀，由数据库 thumb_format 确定)
        ├── 1/                  # 章节序号 epOrder = 1
        │   ├── 1               # 第 1 页图片本体
        │   ├── 2               # 第 2 页图片本体
        │   └── ...
        └── 2/                  # 章节序号 epOrder = 2
            ├── 1
            └── ...
```
- **注意**: 本地存储的文件名不带扩展名，图像的真实格式由 `comic_download_pictures.format` 提供。
- **边下边导出**: 若配置了 `downloadAndExportPath`，每张图片下载落盘时，会同步在指定目录写入可读命名的图片：`{downloadAndExportPath}/{comicTitle}/{epOrder - epTitle}/{rankInEp:03d}.{jpg/png}`（`reference/pikapika-go-core-2022/pikapika/download.go:407-454`）。

### 5.3 并发模型与控制
- **分批获取**: 上游每次从数据库中拉取 `downloadThreadFetch = 100` 张图片（`download.go:22,265`）。
- **线程池控制**: 使用带有容量限制的 Go Channel 作为信号量（`make(chan int, downloadThreadCount)`）配合 `sync.WaitGroup` 执行并发限流（`download.go:276-296`）。
- **任务暂停**: `setDownloadRunning(false)` 修改全局状态标志，每个下载步骤通过 `downloadHasStop()` 检测到后直接退出当前批次并调用 `downloadBegin()` 进入挂起等待（`download.go:48-57`）。
- **失败重置**: `resetAllDownloads()`（Flutter 端为 `resetFailed()`）调用 `comic_center.ResetAll()`，将数据库中所有 `download_failed = true` 的标记重置为 `false`，以便引擎重新处理。

### 5.4 允许 16 线程并发的技术考量与风险
用户需求要求将上游的 1–5 线程上限扩展至 **1–16 线程**。在工程实现上必须注意以下源码隐含的瓶颈：
1. **Cloudflare WAF / 速率限制**:
   PicACG 静态资源主要挂载于 Cloudflare CDN 之后。当单一 IP 在高并发下以 16 线程瞬间并发发起 HTTP 请求时，极易触发 HTTP 429 (Too Many Requests)、HTTP 503 验证页面或 TCP RST 连接重置。
   *应对*: 必须引入智能退避策略（指数退避 Exponential Backoff + Jitter），并在连接池满载错误时自动降低并发梯度。
2. **连接池复用**:
   OkHttp 默认的 `ConnectionPool` 仅保留 5 个空闲连接。16 并发下载必须将 `ConnectionPool` 扩容至 `maxIdleConnections >= 32`，避免频繁握手消耗服务端配额。
3. **SQLite 数据库写入锁争用**:
   在 2022 核心中，每下载完一张图片就会执行一次包含事务更新的数据库操作 `comic_center.PictureSuccess`，并在内存中更新计数后向 Flutter 发送通知（`download.go:318,363`）。在 16 线程下，SQLite 的写入锁（B-Tree Lock）将产生极大的锁争用。
   *应对*: 必须为 SQLite 开启 **WAL 模式 (`PRAGMA journal_mode=WAL;`)**，并在内存中通过协程 Channel 聚合图片下载完成状态，采用批量入库（Batch Update）降低事务提交频率。
4. **内存 OOM 隐患**:
   2022 核心的 `decodeFromUrl` 在将图片写入磁盘前，会在内存中调用 `image.Decode` 将整张图片解码为内存位图，以此读取图片的宽、高与格式（`image.go:108`）。16 个大图同时解码瞬间会消耗上百兆堆内存，在 Android 设备上极易发生 OOM。
   *应对*: 严禁在下载时完整解码位图，应仅解析图像文件头（例如利用 `BitmapFactory.Options.inJustDecodeBounds = true`）获取宽高，或者直接流式边下边存。

---

## 6. 导出 / 导入 / 局域网传输

### 6.1 2022 核心已公开的导出导入格式
源码位置：`reference/pikapika-go-core-2022/pikapika/export.go`，`import.go`。

#### 1. HTML+ZIP 导出 (`exportComicDownload`)
- **生成文件**: `{comicTitle}-{timestamp}.zip`。
- **压缩包内部结构**:
  - `index.html`: 内嵌样式与 JavaScript 的离线漫画阅读器（`export.go:227-370`）。左侧为目录抽屉，右侧为漫画长图滚动展示。
  - `data.js`: 变量定义格式 `data = { ... }`，将整部漫画的元数据（标题、章节列表、图片相对路径及尺寸）直接序列化为 JavaScript 全局变量（`export.go:196`）。
  - `logo`: 封面缩略图二进制流。
  - `pictures/`: 图片文件夹，按 `pictures/{epOrder:04d}_{rankInEp:04d}` 存储图片文件。
- **导入支持 (`importComicDownload`)**:
  读取 ZIP 内的 `data.js`，剥离 `data = ` 前缀解析为 `JsonComicDownload` 对象；开启数据库事务，清空旧数据后全量写入 `comic_downloads`、`comic_download_eps` 和 `comic_download_pictures`，并将图片提取至 `{downloadDir}/{comicId}/{epOrder}/{rankInEp}`。

#### 2. HTML+JPG 文件夹导出 (`exportComicDownloadToJPG`)
- **生成文件**: 包含完整静态资源的独立文件夹。
- **结构差异**: 与 ZIP 版本完全相同，但图片路径带扩展名：`pictures/{epOrder:04d}_{rankInEp:04d}.{format}`。

#### 3. 局域网 Socket 传输协议
源码位置：`reference/pikapika-go-core-2022/pikapika/export.go:26-61`，`import.go:20-76`。
- **导出端 (`exportComicUsingSocket`)**:
  - 启动 TCP 监听器：`net.Listen("tcp", ":0")`，随机绑定空闲端口并返回端口号。
  - 等待客户端接入后，在 TCP 连接流上直接叠加两层包装：`gzip.NewWriter(conn)` $\rightarrow$ `tar.NewWriter(gw)`。
  - 将 `pictures/...`、`logo`、`data.js`、`index.html` 流式写入 tar 包，完成后关闭连接。
- **导入端 (`importComicDownloadUsingSocket`)**:
  - 客户端通过 TCP 拨号连接导出端指定的 `ip:port`。
  - 建立 `gzip.NewReader(conn)` 与 `tar.NewReader(gr)` 流式读取 tar entry。
  - 客户端将解开的文件流实时重组写入本地的临时 zip 文件 `tmp.zip`。
  - 传输完毕后调用 `importComicDownload("tmp.zip")` 执行标准导入。

### 6.2 Flutter 当前 UI 支持的格式与私有内核实现状态
对比 `Method.dart` 与 2022 核心代码，大量高版本导出的实现存在于私有闭源内核中：

| 格式名称 | Method.dart 方法名 | 核心实现状态 | 已知文件特征 / 参数 / UI 语义 |
| :--- | :--- | :--- | :--- |
| **HTML+ZIP** | `exportComicDownload`<br>`exportAnyComicDownloadsToZip` | **公开实现** (`export.go:73`) | `.zip` 压缩包，内含 `index.html`、`data.js` 及图片 |
| **HTML+JPG 目录** | `exportComicDownloadToJPG` | **公开实现** (`export.go:372`) | 包含 `index.html` 的独立图片文件夹 |
| **局域网传输** | `exportComicUsingSocket`<br>`importComicDownloadUsingSocket` | **公开实现** (`export.go:26`, `import.go:20`) | TCP Socket 直连传输 Gzip + Tar 流 |
| **未完成导出** | `exportComicJpegsEvenNotFinish` | **无公开实现** (仅私有内核) | 参数：`comicId`, `dir`, `name`。容许部分图片下载失败时导出 HTML+JPG |
| **PKI 文件** | `exportComicDownloadToPki`<br>`exportAnyComicDownloadsToPki`<br>`importComicDownloadPki` | **无公开实现** (仅私有内核) | 扩展名 `.pki`。UI 标注：“加密模式，防止网盘检测，能用 pikapika 导入；只支持导入，不支持直接阅读”。加密算法与密钥闭源。 |
| **PKZ 归档** | `exportComicDownloadToPkz`<br>`pkzInfo`<br>`loadPkzFile`<br>`viewPkz...` | **无公开实现** (仅私有内核) | 扩展名 `.pkz`。多合一归档格式，支持多本漫画打包。内含 `cover_path`、`author_avatar_path`、`comics` 列表元数据，前端通过 `pkzInfo` 读取目录，通过 `loadPkzFile` 解压单图字节流展示。 |
| **CBZ 漫画包** | `exportComicDownloadToCbzsZip` | **无公开实现** (仅私有内核) | 扩展名 `.zip` / `.cbz`。通用 Comic Book Archive 格式，每章节导出为一个 `.cbz`（本质为无压缩或轻度压缩的图片 zip），再整体打包为 zip。 |
| **纯图片 ZIP** | `exportComicDownloadJpegZip` | **无公开实现** (仅私有内核) | 纯净图片压缩包，不包含 HTML 查看器或 JS 脚本。 |
| **PDF 导出** | `exportComicDownloadToPDF`<br>`exportComicDownloadToPDFFolder` | **无公开实现** (仅私有内核) | 导出为单个 PDF 电子书文件，或每章节一个 PDF 文件的文件夹。 |
| **EPUB 电子书** | `exportComicDownloadToEpub` | **无公开实现** (仅私有内核) | 导出为标准 EPUB 格式电子书。 |
| **目录导入** | `importComicDownloadDir` | **无公开实现** (仅私有内核) | 遍历扫描指定文件夹下的所有 zip / pki 漫画文件并执行批量导入。 |
| **本地 Web 服务器** | `startWebServer`<br>`stopWebServer` | **无公开实现** (仅私有内核) | 在本地 `8080` 端口启动 HTTP 静态文件服务器，允许局域网其他设备通过浏览器直接访问已下载的漫画。 |
| **离线历史合并** | `importComicViewFormOff` | **无公开实现** (仅私有内核) | 从外部指定的 sqlite 数据库文件路径（`dbPath`）中合并导入历史记录。 |

---

## 7. 需删除的上游调用与门控清单

根据项目硬性指标，新版本重构必须彻底清除所有原作者自建基础设施调用、广告、遥测和商业化“发电/赞助”特权门控。

### 7.1 原作者基础设施与非官方外部调用（必须彻底删除）
1. **远程配置与推荐链接 (Pikapika Config)**:
   - `configLinks()`（`reference/pikapika-flutter/lib/basic/Method.dart:35`）: 调用上游作者的 `pikapika-config` 服务拉取外部引流与商业推广链接（`reference/pikapika-flutter/lib/basic/config/RecommendLinks.dart:13`）。
   - `appConfig()`（`reference/pikapika-flutter/lib/basic/Method.dart:45`）: 向上游服务器检查更新（`latestVersion`, `downloadUrl`, `changeLog`）（`reference/pikapika-flutter/lib/basic/config/Version.dart:69`）。
   - `defaultHttpClientGet(url)`（`reference/pikapika-flutter/lib/basic/Method.dart:891`）: 绕过 API 分流，通过默认 HTTPClient 发起的检查更新请求。
2. **爱发电 (Afdian) 赞助验证接口**:
   - `proInfoAll()`（`reference/pikapika-flutter/lib/basic/Method.dart:1013`）: 读取包含 `pro_info_af` 与 `pro_info_pat` 的赞助状态（`reference/pikapika-flutter/lib/basic/Entities.dart:1029-1071`）。
   - `reloadPro()`（`reference/pikapika-flutter/lib/basic/Method.dart:1017`）: 向上游服务器刷新爱发电订单赞助资格。
   - `inputCdKey(cdKey)`（`reference/pikapika-flutter/lib/basic/Method.dart:1029`）: 提交爱发电激活兑换码。
3. **Patreon 赞助绑定接口**:
   - `setPatAccessKey(accessKey)`（`reference/pikapika-flutter/lib/basic/Method.dart:1131`）: 上传 Patreon 访问令牌。
   - `reloadPatAccount()`（`reference/pikapika-flutter/lib/basic/Method.dart:1135`）: 校验 Patreon 会员资格。
   - `bindThisAccount()`（`reference/pikapika-flutter/lib/basic/Method.dart:1139`）: 向上游服务器将当前 PicACG 账号绑定到赞助者账号。
   - `clearPat()`（`reference/pikapika-flutter/lib/basic/Method.dart:1143`）: 解绑 Patreon。
4. **自建特权服务器 (Pro Server)**:
   - `getProServerName()` / `setProServerName(serverName)`（`reference/pikapika-flutter/lib/basic/Method.dart:1021-1025`）: 将特定流量路由到原作者自建的“专线服务器”。
5. **分流地址远程同步**:
   - `reloadSwitchAddress()` / `resetSwitchAddress()`（`reference/pikapika-flutter/lib/basic/Method.dart:1033-1037`）: 连接作者自建的 `http://68.183.234.72/init` 获取动态分流地址。建议在重构版本中将分流列表置于本地维护或允许用户自由配置自定义 IP。
6. **未验证的外部游戏地址爬虫**:
   - `downloadGame(url)`（`reference/pikapika-flutter/lib/basic/Method.dart:782`，`reference/pikapika-go-core-2022/pikapika/game.go:12-39`）: 正则匹配并请求 `https://game.eroge.xyz/hhh.php?id=\d+`，解析网页按钮跳转外部不可控站点。

### 7.2 上游在 UI 层设立的特权门控（新版本全部无条件解开）
在上游 Flutter 源码中，大量实用功能被人为加上了 `if (!isPro)` 拦截（`reference/pikapika-flutter/lib/basic/config/IsPro.dart`）：
1. **高级导出格式锁**:
   - `HTML+ZIP` 导出被拦截（`DownloadExportToFileScreen.dart:257`：“请发电后使用”）。
   - `HTML+JPG` 导出被拦截（`DownloadExportToFileScreen.dart:313`）。
   - `PDF` 与 `PDFFolder` 导出被拦截（`DownloadExportToFileScreen.dart:367,475`）。
   - `EPUB` 导出被拦截（`DownloadExportToFileScreen.dart:421`）。
   - `JPEG ZIP` 导出被拦截（`DownloadExportToFileScreen.dart:529`）。
   - `未下载完成时导出` 被拦截（`DownloadExportToFileScreen.dart:585`）。
   - `CBZS.ZIP` 导出被拦截（`DownloadExportToFileScreen.dart:641`）。
   - 文件夹批量导入 `importComicDownloadDir` 被拦截（`DownloadImportScreen.dart`）。
2. **下载线程数锁**: 上游原设置上限为 5 线程，重构版本需放开至 **1–16 线程**。
3. **WebDAV 自动同步锁**: WebDAV 自动同步历史记录（`WebDav.dart:314`）在原版中被强制要求 Pro 权限。
4. **收藏时自动下载**: `AutoDownloadOnFavorite.dart` 在原版中亦受 Pro 特权限制。

---

## 8. 非网络方法与平台专属功能清单

为保证接口交叉验证完整闭环，以下列出 `Method.dart` 中剩余的 **17 个非 PicACG 网络通信方法**（本地文件操作、设备属性、原生平台通道），并指明其在 Android 原生重构中的处理方式：

| Method.dart 方法名 | 类型 / 原实现途径 | 说明与 Android 原生移植建议 |
| :--- | :--- | :--- |
| `clientIpSet` | 本地网络信息 (`net.InterfaceAddrs`) | 枚举设备局域网非回环 IPv4 地址，用于局域网传输/WebUI 界面展示 IP |
| `convertImageToJPEG100` | 本地图形转换 | 读取图片并以 100% 质量压缩另存为 JPEG 文件，Android 可使用 `Bitmap.compress` |
| `iosSaveFileToImage` | iOS 原生通道 | 仅 iOS 保存图片到相册（Android 端废弃） |
| `androidSaveFileToImage` | Android 原生通道 | 通过 MediaStore API 将指定路径图片存入系统相册 |
| `loadAndroidModes` | Android 原生通道 | 调用 `Display.getSupportedModes()` 读取屏幕刷新率列表（60Hz、90Hz、120Hz） |
| `setAndroidMode` | Android 原生通道 | 设置 `WindowManager.LayoutParams.preferredDisplayModeId` 实现高刷锁定 |
| `androidGetVersion` | Android 原生通道 | 读取 `Build.VERSION.SDK_INT` |
| `dataLocal` | 本地存储路径 | 获取内部数据存储目录（对应 `context.filesDir.absolutePath`） |
| `androidGetExtendDirs` | Android 原生通道 | 调用 `context.getExternalFilesDirs()` 获取 SD 卡/外置扩展存储目录 |
| `migrate` | 本地数据迁移 | 移动数据库及下载目录至新存储路径 |
| `androidSecureFlag` | Android 原生通道 | 切换 `FLAG_SECURE`（防录屏、防截图、任务视图隐藏） |
| `verifyAuthentication` | Android 原生通道 | 调用 `BiometricPrompt` 执行指纹/生物认证解锁 |
| `iosGetDocumentDir` | iOS 原生通道 | 仅 iOS 获取文档目录（Android 端废弃） |
| `androidStorageRoot` | Android 原生通道 | 获取外部存储根目录 (`Environment.getExternalStorageDirectory()`) |
| `androidDefaultExportsDir`| Android 原生通道 | 获取公共下载目录 (`Environment.DIRECTORY_DOWNLOADS`) |
| `getHomeDir` | 桌面端文件系统 | 获取系统用户目录（Android 端废弃） |
| `mkdirs` / `androidMkdirs` | 本地文件系统 | 创建本地层级目录 (`File.mkdirs()`) |
| `fontList` | 系统字体列表 | 查询系统已安装字体（供阅读器字体切换） |

---

## 9. 移植建议与技术选型

### 9.1 推荐技术栈 (Kotlin / Android)
- **网络与拦截器**:
  - `OkHttp 4.12+ / 5.x`:
    - 编写 `PicacgSignInterceptor`（`Interceptor`），在发送前统一计算时间戳、随机 Nonce 与 HMAC-SHA256 签名，动态追加全套请求头。
    - 实现 `okhttp3.Dns` 接口，拦截 `picaapi.picacomic.com` 域名的解析，优雅注入 Cloudflare 分流 IP（`172.67.7.24` 等），天然保留正确的 SNI 与 Host 校验。
    - 代理配置直接使用 `OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP/SOCKS, ...))`。
- **JSON 序列化**:
  - `kotlinx.serialization` 或 `Moshi`: 静态代码生成，无反射开销，针对模糊类型定义宽松解析策略。
- **本地持久化**:
  - `Jetpack Room`:
    - 建立 `PropertiesDao`, `ComicViewDao`, `DownloadDao`。
    - 强制开启 **WAL 模式 (`PRAGMA journal_mode=WAL;`)**，以应对 16 线程并发下载完成时的密集状态写入。
- **后台下载引擎**:
  - `Android Foreground Service` + `Kotlin Coroutines`:
    - 使用前台通知常驻进程，避免被 Android 系统杀死。
    - 并发控制由 `kotlinx.coroutines.sync.Semaphore(permits = threadCount)` 驱动，支持动态调整 1–16 协程通道。
    - 使用 Kotlin `Flow` 向 Compose UI 单向流式发射进度事件。
- **图片加载与缓存**:
  - `Coil 3`: 深度集成 Jetpack Compose，支持接入定制的 `OkHttpClient`（附带图片分流与缓存策略），自定义 `Fetcher` 支持直接从本地下载目录或 SQLite 索引流式呈现。
- **归档导出导入**:
  - 使用 `java.util.zip.ZipOutputStream` 或 `Apache Commons Compress` 处理 ZIP 与 Tar 归档。适配 Android 11+ Scoped Storage，通过 `Storage Access Framework (SAF)` 与 `DocumentFile` 导出到公共 Downloads 目录。

### 9.2 移植风险与已知断层 (Gaps)
1. **私有内核归档格式无法重现**:
   `PKI`（混淆/加密防网盘扫描）与 `PKZ`（自研多本漫画内嵌查看格式）在公开源码中没有任何算法规范或密钥泄露。新版本可选择暂不实现或改用业内通用标准（如 CBZ、压缩 ZIP 及带有 Web Viewer 的开源 HTML ZIP）。
2. **WebDAV 历史合并逻辑闭源**:
   `mergeHistoriesFromWebDav` 在 2022 核心中缺失，新版本需要自行设计简单的两端时间戳双向比较合并逻辑。
3. **高并发封禁风控**:
   16 线程下载必须配备请求间隔节流与错误重试退避，防止用户触发 Cloudflare IP 封禁。
