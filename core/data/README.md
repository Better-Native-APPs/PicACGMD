# 本地数据层

本模块只依赖 Android、Room 和 DataStore，不依赖网络模块。网络请求、下载前台服务、磁盘读写、缓存文件删除和云同步由后续业务层负责。

## 初始化

```kotlin
import app.picacgmd.core.data.db.PikapikaDatabase
import app.picacgmd.core.data.settings.SettingsRepository

val database = PikapikaDatabase.create(applicationContext)
val settingsRepository = SettingsRepository.create(applicationContext)
```

应用生命周期内保留一个数据库实例；退出时可 `close()`。数据库名为 `pikapika.db`，版本 5，使用 WAL，不启用破坏性迁移。自动迁移链完整覆盖版本升级：
- 1 → 2：使用 `DropObsoleteFoldersSpec` 移除历史废弃的旧收藏与文件夹表；
- 2 → 3：自动为下载表追加 `storageRoot` 字段；
- 3 → 4：使用 `DropLocalLibrarySpec` 完全移除本地书架与本地文件夹表（`local_folders` 与 `local_folder_entries`），仅保留浏览记录、阅读进度、下载、订阅追更与缓存；
- 4 → 5：自动为浏览记录表追加 `lastChapterPages` 字段（上次阅读章节的总页数，0 表示未知）。

KSP 将当前 schema 导出至 `core/data/schemas/app.picacgmd.core.data.db.PikapikaDatabase/5.json`；版本 1–4 的 schema 完整保留用于单元测试与升级校验。

`SettingsRepository.create` 使用共享的 `app_settings` Preferences DataStore；测试或注入也可使用 `SettingsRepository(dataStore: DataStore<Preferences>)`。读取入口是 `val settings: Flow<AppSettings>`。所有 setter 返回 `Unit`，读写失败直接传播。

## 实体

包名：`app.picacgmd.core.data.db`。时间戳统一使用毫秒。章节序号和页码从 1 开始；浏览记录里的 0 表示尚未阅读。分类、标签快照以 JSON 字符串保存，实体不引用网络 DTO。

| 实体 | 主键 / 必填构造参数 | 其他字段 |
|---|---|---|
| `ViewHistory` | `comicId: String`, `title: String` | `author`, `thumbFileServer`, `thumbPath`, `categoriesJson`, `lastEpOrder`, `lastEpTitle`, `lastPictureIndex`, `lastChapterPages`, `pinned`, `createdAt`, `updatedAt` |
| `DownloadComic` | `comicId: String`, `title: String` | 漫画元数据，`status`, `selectedEpCount`, `completedEpCount`, `selectedPictureCount`, `completedPictureCount`, `downloadedBytes`, `folder`, `filePath`, `thumbFilePath`, `thumbFormat`, `error`, `createdAt`, `updatedAt`, `completedAt` |
| `DownloadEp` | `epId: String`, `comicId: String`, `epOrder: Int`, `title: String` | `picturesFetched`, `status`, `selectedPictureCount`, `completedPictureCount`, `downloadedBytes`, `filePath`, `error`, `createdAt`, `updatedAt`, `completedAt` |
| `DownloadPicture` | `pictureId: String`, `comicId: String`, `epId: String`, `epOrder: Int`, `pictureIndex: Int`, `fileServer: String`, `path: String` | `originalName`, `status`, `downloadedBytes`, `fileSizeBytes`, `format`, `width`, `height`, `filePath`, `attemptCount`, `error`, `createdAt`, `updatedAt`, `completedAt` |
| `SearchHistory` | `query: String` | `updatedAt` |
| `ApiCache` | `key: String`, `json: String`, `expiresAt: Long` | `updatedAt`；`categories(json: String, now: Long)` / `collections(json: String, now: Long)` 默认缓存 3 小时 |
| `RemoteImage` | 联合主键 `fileServer: String`, `path: String`；`filePath: String` | `fileSizeBytes`, `format`, `width`, `height`, `createdAt`, `updatedAt` |
| `TagFavourite` | `tag: String` | `sortOrder`, `createdAt` |
| `Subscription` | `comicId: String`, `title: String` | `author`, 封面，`lastKnownEpsCount`, `lastKnownUpdatedAt`, `unseen`, `newEpCount`, `lastCheckedAt`, `createdAt`, `updatedAt` |

完整构造参数及默认值见 `core/data/src/main/kotlin/app/picacgmd/core/data/db/{LibraryEntities,DownloadEntities,CacheEntities}.kt`。

下载状态为 `DownloadStatus.QUEUED / RUNNING / PAUSED / FAILED / DONE / DELETING`，库内保存小写字符串。纯逻辑接口：

```kotlin
fun DownloadStatus.canTransitionTo(next: DownloadStatus): Boolean
fun DownloadStatus.transitionTo(next: DownloadStatus): DownloadStatus
fun DownloadStatus.Companion.fromStoredValue(value: String): DownloadStatus
fun DownloadPicture.acceptsResult(result: DownloadPicture): Boolean
fun ApiCache.isExpired(now: Long): Boolean
fun Subscription.withLatest(epsCount: Int, comicUpdatedAt: Long?, checkedAt: Long): Subscription
```

## DAO 接口

以下接口同属 `.db` 包。`observe*` 返回 Flow，其余查询和写入是 `suspend`。集合删除接受 ID 列表，单条删除传 `listOf(id)`。

### 数据库入口

```kotlin
fun viewHistoryDao(): ViewHistoryDao
fun downloadDao(): DownloadDao
fun searchHistoryDao(): SearchHistoryDao
fun apiCacheDao(): ApiCacheDao
fun remoteImageDao(): RemoteImageDao
fun tagFavouriteDao(): TagFavouriteDao
fun subscriptionDao(): SubscriptionDao
```

### ViewHistoryDao

```kotlin
fun observeAll(): Flow<List<ViewHistory>>
fun observePage(limit: Int, offset: Int): Flow<List<ViewHistory>>
fun observe(comicId: String): Flow<ViewHistory?>
fun observeViewedIds(comicIds: List<String>): Flow<List<String>>
suspend fun get(comicId: String): ViewHistory?
suspend fun upsert(history: ViewHistory)
suspend fun updateProgress(comicId: String, epOrder: Int, epTitle: String, pictureIndex: Int, updatedAt: Long): Int
suspend fun setPinned(comicId: String, pinned: Boolean): Int
suspend fun delete(comicIds: List<String>): Int
suspend fun clear()
```

记录按置顶、最近阅读时间排序。更新封面等元数据时应保留已有进度，或只调用 `updateProgress` 更新阅读位置。

### DownloadDao

```kotlin
fun observeComics(folder: String? = null, search: String = ""): Flow<List<DownloadComic>>
fun observeFolders(): Flow<List<String>>
fun observeComic(comicId: String): Flow<DownloadComic?>
fun observeDeletingComics(): Flow<List<DownloadComic>>
fun observeEps(comicId: String): Flow<List<DownloadEp>>
fun observePictures(epId: String): Flow<List<DownloadPicture>>
fun observeNextPendingPictures(comicId: String, limit: Int = 100): Flow<List<DownloadPicture>>
fun observeCounts(comicId: String): Flow<DownloadCounts?>
suspend fun getComic(comicId: String): DownloadComic?
suspend fun getNextComic(): DownloadComic?
suspend fun getDeletingComics(): List<DownloadComic>
suspend fun getEp(epId: String): DownloadEp?
suspend fun getNextUnfetchedEp(comicId: String): DownloadEp?
suspend fun getPicture(pictureId: String): DownloadPicture?
suspend fun nextPendingPictures(comicId: String, limit: Int = 100): List<DownloadPicture>
suspend fun getCounts(comicId: String): DownloadCounts?
suspend fun upsertComic(comic: DownloadComic)
suspend fun upsertEps(eps: List<DownloadEp>)
suspend fun upsertPictures(pictures: List<DownloadPicture>)
suspend fun enqueue(comic: DownloadComic, eps: List<DownloadEp>)
suspend fun claimPicture(pictureId: String, updatedAt: Long): Int
suspend fun savePictureResults(pictures: List<DownloadPicture>, updatedAt: Long): Int
suspend fun refreshProgress(comicId: String, updatedAt: Long)
suspend fun transitionComic(comicId: String, next: DownloadStatus, error: String? = null, updatedAt: Long = System.currentTimeMillis()): Boolean
suspend fun transitionEp(epId: String, next: DownloadStatus, error: String? = null, updatedAt: Long = System.currentTimeMillis()): Boolean
suspend fun retryFailed(updatedAt: Long): Int
suspend fun recoverInterrupted(updatedAt: Long): Int
suspend fun moveComics(comicIds: List<String>, folder: String, updatedAt: Long): Int
suspend fun deleteComic(comicId: String): Int
```

- `DownloadCounts` 包含 `comicId`, `totalEps`, `completedEps`, `totalPictures`, `queuedPictures`, `runningPictures`, `pausedPictures`, `failedPictures`, `completedPictures`, `deletingPictures`, `downloadedBytes`。漫画存在、图片尚未解析时返回零计数；漫画不存在时返回 null。
- 获取待下载图片会排除暂停、删除中的父任务以及尚未完整获取图片清单的章节。先 `claimPicture`，返回 1 才可下载；随后 `getPicture` 获取该次 claim 的 `attemptCount`，以此快照构造结果。
- `savePictureResults` 只接受 done/failed 结果，并批量更新章节、漫画计数。已暂停、删除或 claim 过期的结果返回未接受；业务层负责清理这些结果的临时文件。
- 暂停、恢复、标记删除通过 `transitionComic` 完成，子任务在同一事务更新。恢复保留 done 图片；非法状态跳转抛出 `IllegalArgumentException`，重复状态是无副作用操作。
- `refreshProgress` 只更新计数，不决定任务完成/失败状态；业务层在清单完整并检查结果后设置章节、漫画状态。
- `recoverInterrupted` 只在服务启动、没有旧 worker 活动时调用。`retryFailed` 不唤醒用户已暂停的漫画。两者返回重排队的图片数。
- 文件路径相对于应用自己的下载根目录：`{comicId}/logo`、`{comicId}/{epOrder}/{pictureIndex}`，无扩展名。先标记 deleting，取消 worker 并删掉文件，再调用 `deleteComic` 级联删除表记录。

### SearchHistoryDao / TagFavouriteDao

```kotlin
// SearchHistoryDao
fun observeAll(): Flow<List<SearchHistory>>
suspend fun upsert(history: SearchHistory)
suspend fun delete(queries: List<String>): Int
suspend fun clear()

// TagFavouriteDao
fun observeAll(): Flow<List<TagFavourite>>
suspend fun upsert(favourite: TagFavourite)
suspend fun delete(tags: List<String>): Int
suspend fun clear()
```

### SubscriptionDao

```kotlin
fun observeAll(): Flow<List<Subscription>>
fun observe(comicId: String): Flow<Subscription?>
fun observeUnseenCount(): Flow<Int>
suspend fun get(comicId: String): Subscription?
suspend fun upsert(subscription: Subscription)
suspend fun applyUpdate(comicId: String, epsCount: Int, comicUpdatedAt: Long?, checkedAt: Long): Boolean
suspend fun markSeen(comicId: String): Int
suspend fun delete(comicIds: List<String>): Int
suspend fun clear()
```

更新检查只接收已经获取的数据，不发请求、不调度后台任务。未读更新优先排序；重复检查不会清除 `unseen`，新增章节数累计到 `markSeen`。

### ApiCacheDao / RemoteImageDao

```kotlin
// ApiCacheDao
fun observe(key: String): Flow<ApiCache?>
suspend fun getFresh(key: String, now: Long): ApiCache?
suspend fun upsert(cache: ApiCache)
suspend fun delete(key: String): Int
suspend fun deletePrefix(prefix: String): Int
suspend fun deleteExpired(now: Long): Int
suspend fun clear()

// RemoteImageDao
fun observeAll(): Flow<List<RemoteImage>>
fun observe(fileServer: String, path: String): Flow<RemoteImage?>
suspend fun get(fileServer: String, path: String): RemoteImage?
suspend fun upsert(image: RemoteImage)
suspend fun delete(fileServer: String, path: String): Int
suspend fun deleteOlderThan(before: Long): Int
suspend fun clear()
```

分类和合集分别通过 `ApiCache.categories` / `collections` 写入，TTL 为 10,800,000 毫秒。其他缓存的过期时间由调用者明确指定。`observe` 可用于带过期标记的离线内容，但 Flow 不是定时器；在线缓存读取使用 `getFresh`，边界 `expiresAt <= now` 即失效。清理接口缓存、图片缓存不会涉及下载表；数据库删除不代替业务层删文件。

## 设置接口

包名：`app.picacgmd.core.data.settings`。`AppSettings` 的字段、枚举及默认值完整列于 `core/data/src/main/kotlin/app/picacgmd/core/data/settings/AppSettings.kt`。偏好键集中在 `SettingsKeys`（internal）；枚举保存 `name`，有序列表保存 JSON。

所有 setter 均为 `suspend fun ...: Unit`：

```kotlin
setAccountEmail(value: String)
setAuthToken(token: String?, updatedAt: Long = System.currentTimeMillis())
setApiRouteAddress(address: String?)
setSyncedApiAddresses(addresses: List<String>, syncedAt: Long)
setImageRouting(mode: RoutingMode)
setProxyUrl(value: String)
setImageQuality(value: ImageQuality)
setConnectTimeoutSeconds(value: Int)
setReadTimeoutSeconds(value: Int)
setTimeOffsetHours(value: Int)
setUseApiClientForImages(value: Boolean)
setTheme(value: AppTheme)
setDarkMode(value: DarkMode)
setPureBlack(value: Boolean)
setAppOrientation(value: AppOrientation)
setDisplayModeId(value: Int?)
setFullScreenDisplay(value: FullScreenDisplay)
setDoubleBackToExit(value: Boolean)
setStartupImageUri(value: String?)
setLastSeenAnnouncementId(value: String?)
setListLayout(value: ListLayout)
setListColumnCount(value: Int)
setCategoryColumnCount(value: Int)
setPaginationMode(value: PaginationMode)
setFailedReloadAction(value: FailedReloadAction)
setShieldedCategories(values: List<String>)
setCategoryShieldMode(value: CategoryShieldMode)
setHiddenKeywords(values: List<String>)
setHideViewedComics(value: Boolean)
setHideAiComics(value: Boolean)
setHiddenCategories(values: List<String>)
setCategoryOrder(values: List<String>)
setPinnedHomeCategories(values: List<String>)
setShowAuthorSearch(value: Boolean)
setShowSubscriptions(value: Boolean)
setShowTagFavourites(value: Boolean)
setShowOnlineFavourites(value: Boolean)
setCopyNameTemplate(value: String)
setConfirmCopy(value: Boolean)
setReaderMode(value: ReaderMode)
setReaderDirection(value: ReaderDirection)
setReaderTapZones(value: ReaderTapZones)
setVolumeKeyPaging(value: Boolean)
setVolumeKeyChapterPaging(value: Boolean)
setReaderBackground(value: ReaderBackground)
setReaderFilter(value: ReaderFilter)
setKeepScreenOn(value: Boolean)
setReaderFullScreen(value: Boolean)
setFullScreenOnForward(value: Boolean)
setReaderPageAnimation(value: Boolean)
setThreeZoneNextOnRight(value: Boolean)
setReaderScrollMode(value: ReaderScrollMode)
setScrollScreenFraction(value: Float)
setReaderSliderPosition(value: ReaderSliderPosition)
setTwoPagePlacement(value: TwoPagePlacement)
setTwoPageCoverAlone(value: Boolean)
setReaderPreloadAhead(value: Int)
setReaderAutoTurnSeconds(value: Int)
setReaderAutoScrollSpeed(value: Int)
setReaderAutoScrollPauseOnTouch(value: Boolean)
setReaderAutoNextChapter(value: Boolean)
setGestureSpeed(value: Float)
setReaderZoom(minimum: Float, maximum: Float, doubleTap: Float)
setLockDragBounds(value: Boolean)
setEInkReduceAnimations(value: Boolean)
setEInkScrollByScreen(value: Boolean)
setEInkScrollAmount(value: Float)
setEInkTriggerDistanceCm(value: Float)
setDownloadThreads(value: Int)
setDownloadWifiOnly(value: Boolean)
setDownloadTreeUri(value: String?)
clearDownloadTreeUriIfMatches(expected: String)
setAutoDownloadOnFavourite(value: Boolean)
setAutoDownloadWifiOnly(value: Boolean)
setAutoDeleteDownloadOnUnfavourite(value: Boolean)
setShowCommentsInDownloads(value: Boolean)
setAutoPunchIn(value: Boolean)
setAppLockEnabled(value: Boolean)
setBiometricUnlockEnabled(value: Boolean)
setAppLockTimeoutSeconds(value: Int)
setPreventScreenshots(value: Boolean)
setHistoryEnabled(value: Boolean)
setRecordDetailViews(value: Boolean)
setAutoCleanCacheInterval(value: CacheCleanupInterval)
```

- 下载线程 1–16，默认 4（低内存设备默认 2）；列数 0（自动）或 2–7；连接/读取超时 10/15/30/60 秒；应用锁超时 0/1/10/60/180/600/3600 秒。
- 默认首页固定分类为有序列表 `["大家都在看", "大濕推薦"]`，可清空。分类字符串保持接口拼写，重复项保留第一次出现的位置；空白项不能写入。
- 黑/白名单分类、隐藏分类和首页固定分类互不替代。过滤、菜单入口显示、历史记录开关和自动化设置由业务层执行，设置仓库只保存值。
- 分流支持从官方同步并记录同步时间戳；分流选择与测速由上层管理，数据层持久化当前生效地址及同步所得候选地址。
- 邮箱和 token 可保存；`setAuthToken(null)` 同时删除 token 和时间戳。没有密码字段，代码保留将来使用 EncryptedFile 的 TODO；日志输出不包含 token。
- 数据存储不包含伪装、导入导出、局域网传输、迁移、文件备份、升级检查、付费或遥测设置。WebDAV 功能留待后续实现。

## 验证

```sh
nix develop -c ./gradlew :core:data:assembleDebug :core:data:testDebugUnitTest --no-daemon
```

JVM 测试使用纯 Preferences 映射和内存 DataStore，覆盖设置默认值/写入/顺序/范围、旧版废弃偏好的兼容、状态跳转、过期边界、追更累计及过期 claim 结果。升级测试检查 Room 生成的自动迁移：1 → 2 仅删除两张旧表；2 → 3 补全存储字段；3 → 4 移除已废弃的本地书架表且不改写其余表。没有新增测试依赖；现有设置不包含 Room 测试 helper、Android 测试 runner 或 JVM SQLite driver，未运行真实 SQLite 数据库升级测试。
