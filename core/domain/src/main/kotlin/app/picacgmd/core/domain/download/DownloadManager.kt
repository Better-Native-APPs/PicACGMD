package app.picacgmd.core.domain.download

import android.content.Context
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient

/** Application-scoped facade. Enqueue persists snapshots; it never waits for chapter API requests. */
class DownloadManager(
    apiProvider: () -> PicaApi,
    imageClientProvider: () -> OkHttpClient,
    private val dao: DownloadDao,
    settingsRepository: SettingsRepository,
    context: Context,
    imageUrlProvider: ((PicaImage) -> String)? = null,
    notificationSmallIcon: Int = R.drawable.ic_download_notification,
) {
    private val context = context.applicationContext
    val files = DownloadFileStore {
        val external = this.context.getExternalFilesDir(null) ?: throw IOException("下载失败：存储空间不可用")
        File(external, "downloads")
    }
    private val engine = DownloadEngine(
        apiProvider = apiProvider,
        imageClientProvider = imageClientProvider,
        dao = dao,
        settings = settingsRepository.settings,
        network = observeDownloadNetwork(this.context),
        files = files,
        imageUrlProvider = imageUrlProvider,
    )
    val progress: StateFlow<DownloadProgress> = engine.progress
    internal val notifications = DownloadNotifications(this.context, notificationSmallIcon)

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

    suspend fun pause(comicId: String) { engine.pause(comicId) }

    suspend fun resume(comicId: String) {
        engine.resume(comicId)
        start()
    }

    suspend fun delete(comicId: String) { engine.delete(comicId) }

    suspend fun deleteChapter(epId: String) { engine.deleteChapter(epId) }

    suspend fun retryChapter(epId: String): Int = engine.retryChapter(epId).also { start() }

    suspend fun pauseAll() {
        engine.pauseAll()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
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
    fun start() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_NAME)
            .build()
        // KEEP can lose an enqueue racing with the last worker's exit; append a serialized successor.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    internal suspend fun runUntilIdle() { engine.runUntilIdle() }

    internal suspend fun hasWork(): Boolean = dao.getNextComic() != null || dao.getDeletingComics().isNotEmpty()

    companion object {
        internal const val WORK_NAME = "pikapika-download-queue"
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
