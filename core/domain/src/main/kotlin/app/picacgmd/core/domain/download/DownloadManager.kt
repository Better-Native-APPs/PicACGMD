package app.picacgmd.core.domain.download

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadCounts
import app.picacgmd.core.data.db.DownloadDao
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.R
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Ep
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaJson
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient

/** Application-scoped facade. Enqueue persists snapshots; it never waits for chapter API requests. */
class DownloadManager(
    apiProvider: () -> PicaApi,
    imageClientProvider: () -> OkHttpClient,
    private val dao: DownloadDao,
    private val settingsRepository: SettingsRepository,
    context: Context,
    scope: CoroutineScope,
    imageUrlProvider: ((PicaImage) -> String)? = null,
    notificationSmallIcon: Int = R.drawable.ic_download_notification,
) {
    private val context = context.applicationContext
    val files = DownloadFileStore(
        rootProvider = {
            val external = this.context.getExternalFilesDir(null) ?: throw IOException("下载失败：存储空间不可用")
            File(external, "downloads")
        },
        // Same place as every download made before custom locations existed; app-internal storage only
        // when shared external app storage is unavailable.
        internalRootProvider = {
            this.context.getExternalFilesDir(null)?.let { File(it, "downloads") } ?: File(this.context.filesDir, "downloads")
        },
        stagingRootProvider = { File(this.context.cacheDir, "download-staging") },
        treeBackendProvider = { tree -> TreeDownloadBackend(AndroidDownloadTreeAccess(this.context.contentResolver, Uri.parse(tree))) },
    )
    private val engine = DownloadEngine(
        apiProvider = apiProvider,
        imageClientProvider = imageClientProvider,
        dao = dao,
        settings = settingsRepository.settings,
        network = observeDownloadNetwork(this.context),
        files = files,
        imageUrlProvider = imageUrlProvider,
        onStorageFallback = settingsRepository::clearDownloadTreeUriIfMatches,
    )
    val progress: StateFlow<DownloadProgress> = engine.progress
    val waitingForWifi: Flow<Boolean> = combine(
        settingsRepository.settings.map { it.downloadWifiOnly },
        observeDownloadNetwork(this.context),
    ) { wifiOnly, network -> wifiRuleBlocks(wifiOnly, network) }.distinctUntilChanged()
    internal val notifications = DownloadNotifications(this.context, notificationSmallIcon)
    @Volatile private var userActionAt: Long? = null

    init {
        scope.launch {
            settingsRepository.settings.map { it.downloadWifiOnly }.distinctUntilChanged().drop(1).collect {
                cancelWifiWaiter()
                start()
            }
        }
    }

    suspend fun enqueue(comic: DownloadComic, eps: List<DownloadEp>? = null) {
        enqueueBatch(listOf(DownloadRequest(comic, eps)))
    }

    suspend fun enqueue(comic: ComicInfo, eps: List<Ep>? = null, folder: String = "") {
        enqueue(comic.downloadSnapshot(folder), eps?.map { it.downloadSnapshot(comic.id) })
    }

    suspend fun enqueue(comic: ComicSimple, eps: List<Ep>? = null, folder: String = "") {
        enqueue(comic.downloadSnapshot(folder), eps?.map { it.downloadSnapshot(comic.id) })
    }

    suspend fun enqueueBatch(comics: List<ComicSimple>, folder: String = "") {
        val requests: Collection<DownloadRequest> = comics.map { DownloadRequest(it.downloadSnapshot(folder)) }
        enqueueBatch(requests)
    }

    suspend fun enqueueBatch(requests: Collection<DownloadRequest>) {
        if (requests.isEmpty()) return
        engine.enqueue(requests)
        start()
    }

    suspend fun pause(comicId: String) {
        engine.pause(comicId)
        cancelWifiWaiterWhenIdle()
    }

    suspend fun resume(comicId: String) {
        engine.resume(comicId)
        start()
    }

    suspend fun delete(comicId: String) {
        engine.delete(comicId)
        cancelWifiWaiterWhenIdle()
    }

    suspend fun deleteChapter(epId: String) {
        engine.deleteChapter(epId)
        cancelWifiWaiterWhenIdle()
    }

    suspend fun retryChapter(epId: String): Int = engine.retryChapter(epId).also { start() }

    /** Changing this setting never moves existing downloads or releases their retained tree grants. */
    suspend fun setDownloadLocation(treeUri: String?) = withContext(Dispatchers.IO) {
        if (treeUri != null) files.validateTree(treeUri)
        settingsRepository.setDownloadTreeUri(treeUri)
    }

    suspend fun validateDownloadLocation() = withContext(Dispatchers.IO) {
        val tree = settingsRepository.settings.first().downloadTreeUri ?: return@withContext
        files.selectStorageRoot(tree).unavailableTree?.let { settingsRepository.clearDownloadTreeUriIfMatches(it) }
    }

    suspend fun pauseAll() {
        engine.pauseAll()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        cancelWifiWaiter()
    }

    suspend fun resumeAll() {
        engine.resumeAll()
        start()
    }

    /** A null ID retries the whole queue; paused comics are never implicitly resumed. */
    suspend fun retryFailed(comicId: String? = null): Int = engine.retryFailed(comicId).also { start() }

    fun observeQueue(folder: String? = null, search: String = ""): Flow<List<DownloadComic>> =
        dao.observeComics(folder, search)

    fun observeCounts(comicId: String): Flow<DownloadCounts?> = dao.observeCounts(comicId)

    fun observeComic(comicId: String): Flow<DownloadComic?> = dao.observeComic(comicId)

    fun observeChapters(comicId: String): Flow<List<DownloadEp>> = dao.observeEps(comicId)

    /** Call on application startup as well as after enqueue to recover work after process death. */
    suspend fun start() {
        if (!hasWork()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && blockedByWifiRule()) {
            enqueueWifiWaiter()
            return
        }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_NAME)
            .build()
        // KEEP can lose an enqueue racing with the last worker's exit; append a serialized successor.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun enqueueWifiWaiter() {
        val network = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWifiWaitWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkRequest(network, NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WIFI_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WIFI_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelWifiWaiter() {
        WorkManager.getInstance(context).cancelUniqueWork(WIFI_WORK_NAME)
    }

    private suspend fun cancelWifiWaiterWhenIdle() {
        if (!hasWork()) cancelWifiWaiter()
    }

    internal suspend fun blockedByWifiRule(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        wifiRuleBlocks(settingsRepository.settings.first().downloadWifiOnly, currentDownloadNetwork(context))

    /** A notification action lets the next run start its foreground service from the background. */
    internal fun noteUserAction() { userActionAt = System.nanoTime() }

    internal fun userActionRecent(): Boolean =
        userActionAt?.let { System.nanoTime() - it < USER_ACTION_GRANT_NANOS } ?: false

    internal suspend fun runUntilIdle() { engine.runUntilIdle() }

    internal suspend fun hasWork(): Boolean = dao.getNextComic() != null || dao.getDeletingComics().isNotEmpty()

    companion object {
        internal const val WORK_NAME = "pikapika-download-queue"
        internal const val WIFI_WORK_NAME = "pikapika-download-wifi-wait"
        // Shorter than the retry backoff, so a refused start is not attempted twice.
        private const val USER_ACTION_GRANT_NANOS = 10_000_000_000L
        const val ACTION_OPEN_DOWNLOADS = "app.picacgmd.action.OPEN_DOWNLOADS"
    }
}

private fun ComicInfo.downloadSnapshot(folder: String) = DownloadComic(
    comicId = id, title = title, author = author, thumbFileServer = thumb?.fileServer.orEmpty(),
    thumbPath = thumb?.path.orEmpty(), categoriesJson = PicaJson.encodeToString(categories),
    tagsJson = PicaJson.encodeToString(tags), description = description, folder = folder,
)

private fun ComicSimple.downloadSnapshot(folder: String) = DownloadComic(
    comicId = id, title = title, author = author, thumbFileServer = thumb?.fileServer.orEmpty(),
    thumbPath = thumb?.path.orEmpty(), categoriesJson = PicaJson.encodeToString(categories), folder = folder,
)

private fun Ep.downloadSnapshot(comicId: String) = DownloadEp(epId = id, comicId = comicId, epOrder = order, title = title)
