package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadDao
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadPicture
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.db.acceptsResult
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.session.toImageServer
import app.picacgmd.core.network.Ep
import app.picacgmd.core.network.ImageQuality
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.PicaException
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaImageUrls
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

private val activeStatuses = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)

/** Android-free orchestration; the worker owns its lifetime and the DAO owns persisted queue state. */
internal class DownloadEngine(
    private val apiProvider: () -> PicaApi,
    imageClientProvider: () -> OkHttpClient,
    private val dao: DownloadDao,
    private val settings: Flow<AppSettings>,
    private val network: Flow<DownloadNetworkState>,
    val files: DownloadFileStore,
    private val imageUrlProvider: ((PicaImage) -> String)? = null,
    private val retryPolicy: DownloadRetryPolicy = DownloadRetryPolicy(),
    private val limiter: AdaptiveDownloadLimiter = AdaptiveDownloadLimiter(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressClockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val runMutex = Mutex()
    private val comicLocks = ConcurrentHashMap<String, Mutex>()
    private val latestSettings = MutableStateFlow(AppSettings.DEFAULT)
    private val latestNetwork = MutableStateFlow(DownloadNetworkState.OFFLINE)
    private val networkAllowed = MutableStateFlow(false)
    private val trackedComics = ConcurrentHashMap.newKeySet<String>()
    private val failedPictures = ConcurrentHashMap<String, Int>()
    private val manifestTotals = ConcurrentHashMap<String, Int>()
    private val queue = MutableStateFlow<List<DownloadComic>>(emptyList())
    private val mutableProgress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = mutableProgress.asStateFlow()
    private val speedBytes = AtomicLong()
    private val speedWindowStart = AtomicLong(nowMillis())
    private val downloader = StreamingImageDownloader(imageClientProvider, files, ::recordBytes, nowMillis)

    @Volatile private var activeComic: ActiveComic? = null
    @Volatile private var activePictureResults: ActivePictureResults? = null
    @Volatile private var running = false

    suspend fun enqueue(requests: Collection<DownloadRequest>) = withContext(ioDispatcher) {
        requests.forEach { request ->
            val incoming = request.comic
            DownloadFileStore.requireComicId(incoming.comicId)
            require(request.eps == null || request.eps.isNotEmpty()) { "Select at least one chapter, or use null for all" }
            request.eps?.let { eps ->
                require(eps.all { it.comicId == incoming.comicId && it.epId.isNotBlank() && it.epOrder > 0 })
                require(eps.map { it.epId }.distinct().size == eps.size)
                require(eps.map { it.epOrder }.distinct().size == eps.size)
            }
            withComicLock(incoming.comicId) {
                val previous = dao.getComic(incoming.comicId)
                require(previous?.status != DownloadStatus.DELETING) { "Comic is being deleted" }
                if (request.eps == null) files.requestAllEpisodes(incoming.comicId)
                if (previous != null && previous.status !in activeStatuses) {
                    dao.transitionComic(incoming.comicId, DownloadStatus.QUEUED, updatedAt = nowMillis())
                }
                val current = dao.getComic(incoming.comicId)
                val comic = if (current == null) {
                    incoming.copy(status = DownloadStatus.QUEUED, error = null, completedAt = null,
                        filePath = incoming.comicId, thumbFilePath = "${incoming.comicId}/logo", updatedAt = nowMillis())
                } else {
                    current.copy(
                        title = incoming.title,
                        author = incoming.author.ifBlank { current.author },
                        thumbFileServer = incoming.thumbFileServer.ifBlank { current.thumbFileServer },
                        thumbPath = incoming.thumbPath.ifBlank { current.thumbPath },
                        categoriesJson = incoming.categoriesJson,
                        tagsJson = if (incoming.tagsJson == "[]") current.tagsJson else incoming.tagsJson,
                        description = incoming.description.ifBlank { current.description },
                        folder = incoming.folder,
                        updatedAt = nowMillis(),
                    )
                }
                val stored = dao.observeEps(incoming.comicId).first().associateBy { it.epId }
                val selected = request.eps.orEmpty().map { ep ->
                    stored[ep.epId]?.also { require(it.epOrder == ep.epOrder) } ?: ep.copy(
                        status = DownloadStatus.QUEUED,
                        picturesFetched = false,
                        selectedPictureCount = 0,
                        completedPictureCount = 0,
                        downloadedBytes = 0,
                        filePath = "${incoming.comicId}/${ep.epOrder}",
                        error = null,
                        completedAt = null,
                        updatedAt = nowMillis(),
                    )
                }
                manifestTotals.remove(comic.comicId)
                dao.enqueue(comic, selected)
                if (running) trackedComics.add(comic.comicId)
                updateQueueProgress()
            }
        }
    }

    suspend fun pause(comicId: String) {
        // Persist already completed transfers before the DAO invalidates unfinished claims.
        flushActivePictureResults(comicId)
        val job = withComicLock(comicId) {
            val comic = dao.getComic(comicId)
            if (comic?.status in activeStatuses || comic?.status == DownloadStatus.FAILED) {
                dao.transitionComic(comicId, DownloadStatus.PAUSED, updatedAt = nowMillis())
            }
            cancelActiveComic(comicId)
        }
        job?.join()
    }

    suspend fun resume(comicId: String) = withComicLock(comicId) {
        if (dao.getComic(comicId)?.status == DownloadStatus.PAUSED) {
            dao.transitionComic(comicId, DownloadStatus.QUEUED, updatedAt = nowMillis())
        }
    }

    suspend fun pauseAll() {
        dao.observeComics().first().filter { it.status in activeStatuses || it.status == DownloadStatus.FAILED }
            .forEach { pause(it.comicId) }
    }

    suspend fun resumeAll() {
        dao.observeComics().first().filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.comicId) }
    }

    suspend fun retryFailed(comicId: String? = null): Int {
        if (comicId == null) return dao.retryFailed(nowMillis())
        var retried = 0
        val job = withComicLock(comicId) {
            val comic = dao.getComic(comicId) ?: return@withComicLock null
            if (comic.status == DownloadStatus.PAUSED || comic.status == DownloadStatus.DELETING ||
                comic.status == DownloadStatus.DONE) return@withComicLock null
            val count = dao.getCounts(comicId)?.failedPictures ?: 0
            val hasFailedEp = dao.observeEps(comicId).first().any { it.status == DownloadStatus.FAILED }
            if (comic.status != DownloadStatus.FAILED && count == 0 && !hasFailedEp) return@withComicLock null
            retried = count
            if (comic.status == DownloadStatus.QUEUED) {
                dao.transitionComic(comicId, DownloadStatus.RUNNING, updatedAt = nowMillis())
            }
            dao.transitionComic(comicId, DownloadStatus.QUEUED, updatedAt = nowMillis())
            cancelActiveComic(comicId)
        }
        job?.join()
        return retried
    }

    suspend fun delete(comicId: String) = withContext(ioDispatcher) {
        val job = withComicLock(comicId) {
            if (dao.getComic(comicId) != null) {
                dao.transitionComic(comicId, DownloadStatus.DELETING, updatedAt = nowMillis())
            }
            cancelActiveComic(comicId)
        }
        job?.join()
        removeDeletingComic(comicId)
    }

    suspend fun retryChapter(epId: String): Int = withContext(ioDispatcher) {
        val ep = dao.getEp(epId) ?: return@withContext 0
        withComicLock(ep.comicId) {
            val count = dao.retryEp(epId, nowMillis())
            manifestTotals.remove(ep.comicId)
            refreshFailureCount(ep.comicId)
            count
        }
    }

    suspend fun deleteChapter(epId: String) = withContext(ioDispatcher) {
        val ep = dao.getEp(epId) ?: return@withContext
        flushActivePictureResults(ep.comicId)
        withContext(NonCancellable) { withComicLock(ep.comicId) {
            val current = dao.getEp(epId) ?: return@withComicLock
            if (dao.getComic(current.comicId)?.status == DownloadStatus.DELETING) return@withComicLock
            // Invalidate claims before removing files. Late transfers are discarded, never committed.
            dao.transitionEp(epId, DownloadStatus.DELETING, updatedAt = nowMillis())
            files.deleteChapter(current.comicId, current.epOrder)
            dao.deleteEp(epId)
            dao.refreshProgress(current.comicId, nowMillis())
            val remaining = dao.observeEps(current.comicId).first()
            val comic = dao.getComic(current.comicId)
            if (comic != null && remaining.all { it.status == DownloadStatus.DONE }) {
                dao.upsertComic(comic.copy(status = DownloadStatus.DONE, error = null, completedAt = nowMillis()))
            }
            manifestTotals.remove(current.comicId)
            refreshFailureCount(current.comicId)
        } }
    }

    /** Serialized restarts guarantee interrupted claims are recovered only after old workers exit. */
    suspend fun runUntilIdle() = runMutex.withLock {
        withContext(ioDispatcher) {
            dao.recoverInterrupted(nowMillis())
            files.removeInterruptedTemporaryFiles()
            trackedComics.clear()
            failedPictures.clear()
            manifestTotals.clear()
            queue.value = emptyList()
            mutableProgress.value = DownloadProgress()
            speedBytes.set(0)
            speedWindowStart.set(nowMillis())
            running = true
            try {
                coroutineScope {
                    val settingsReady = CompletableDeferred<Unit>()
                    val networkReady = CompletableDeferred<Unit>()
                    val queueReady = CompletableDeferred<Unit>()
                    val observers = listOf(
                        launch {
                            settings.collect {
                                latestSettings.value = it
                                limiter.setLimit(it.downloadThreads)
                                updateNetworkGate()
                                settingsReady.complete(Unit)
                            }
                        },
                        launch {
                            network.collect {
                                latestNetwork.value = it
                                updateNetworkGate()
                                networkReady.complete(Unit)
                            }
                        },
                        launch {
                            dao.observeComics().collect { comics ->
                                comics.filter { it.status in activeStatuses }.forEach { trackedComics.add(it.comicId) }
                                queue.value = comics
                                updateQueueProgress()
                                queueReady.complete(Unit)
                            }
                        },
                        launch {
                            limiter.state.collect { state ->
                                mutableProgress.update {
                                    it.copy(
                                        configuredThreads = state.configuredLimit,
                                        effectiveThreads = state.effectiveLimit,
                                        bytesPerSecond = if (state.inFlight == 0) 0 else it.bytesPerSecond,
                                    )
                                }
                            }
                        },
                    )
                    try {
                        settingsReady.await()
                        networkReady.await()
                        queueReady.await()
                        // Resolve the whole queued batch before the first page transfer. Otherwise
                        // each new chapter/comic enlarges the denominator after progress has begun.
                        for (comic in queue.value.filter { it.status in activeStatuses }) {
                            val job = launch(start = CoroutineStart.LAZY) { processComic(comic.comicId, manifestsOnly = true) }
                            activeComic = ActiveComic(comic.comicId, job)
                            job.start()
                            job.join()
                            activeComic = null
                        }
                        while (true) {
                            dao.getDeletingComics().forEach { removeDeletingComic(it.comicId) }
                            val next = dao.getNextComic() ?: break
                            val job = launch(start = CoroutineStart.LAZY) { processComic(next.comicId) }
                            activeComic = ActiveComic(next.comicId, job)
                            job.start()
                            job.join()
                            activeComic = null
                        }
                        queue.value = dao.observeComics().first()
                        updateQueueProgress()
                    } finally {
                        observers.forEach { it.cancel() }
                        observers.joinAll()
                    }
                }
            } finally {
                running = false
                activeComic = null
                mutableProgress.update { it.copy(bytesPerSecond = 0, currentComicTitle = null, currentEpTitle = null) }
            }
        }
    }

    private suspend fun processComic(comicId: String, manifestsOnly: Boolean = false) {
        try {
            coroutineScope {
                val comic = withComicLock(comicId) {
                    val current = dao.getComic(comicId) ?: return@withComicLock null
                    if (current.status !in activeStatuses) return@withComicLock null
                    dao.transitionComic(comicId, DownloadStatus.RUNNING, updatedAt = nowMillis())
                    current
                } ?: return@coroutineScope
                mutableProgress.update { it.copy(currentComicTitle = comic.title) }
                val ownerJob = coroutineContext.job
                val stateObserver = launch {
                    dao.observeComic(comicId).collect {
                        if (it == null || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.DELETING) {
                            ownerJob.cancel(ComicStopped())
                        }
                    }
                }
                try {
                    val allRequest = files.allEpisodesRequest(comicId)
                    if (allRequest != null || dao.observeEps(comicId).first().isEmpty()) {
                        resolveAllEpisodes(comicId, allRequest)
                    }
                    while (true) {
                        val ep = dao.getNextUnfetchedEp(comicId) ?: break
                        resolvePictures(ep)
                    }
                    val eps = dao.observeEps(comicId).first()
                    if (files.allEpisodesRequest(comicId) == null && eps.all { it.picturesFetched }) {
                        manifestTotals[comicId] = eps.sumOf { it.selectedPictureCount }
                    } else {
                        manifestTotals.remove(comicId)
                    }
                    updateQueueProgress()
                    if (!manifestsOnly) {
                        val coverError = downloadCover(comicId)
                        downloadPendingPictures(comicId)
                        summarize(comicId, coverError)
                    }
                } finally {
                    stateObserver.cancelAndJoin()
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            withComicLock(comicId) {
                val current = dao.getComic(comicId)
                if (current?.status in activeStatuses) {
                    // Release unfinished claims after an engine-level failure; successful files survive.
                    if (current?.status == DownloadStatus.RUNNING) {
                        dao.transitionComic(comicId, DownloadStatus.QUEUED, updatedAt = nowMillis())
                    }
                    dao.transitionComic(comicId, DownloadStatus.RUNNING, updatedAt = nowMillis())
                    dao.transitionComic(comicId, DownloadStatus.FAILED, downloadError(exception), nowMillis())
                }
            }
        } finally {
            refreshFailureCount(comicId)
        }
    }

    private suspend fun resolveAllEpisodes(comicId: String, request: String?) {
        val eps = mutableListOf<Ep>()
        var page = 1
        do {
            val result = retry { apiProvider().comicEpPage(comicId, page).getOrThrow() }
            eps.addAll(result.docs)
            page++
        } while (page <= maxOf(1, result.pages))
        require(eps.all { it.id.isNotBlank() && it.order > 0 }) { "获取章节失败：清单格式错误" }
        val unique = eps.distinctBy { it.id }
        require(unique.map { it.order }.distinct().size == unique.size) { "获取章节失败：序号重复" }
        withComicLock(comicId) {
            if (dao.getComic(comicId)?.status !in activeStatuses) return@withComicLock
            val existing = dao.observeEps(comicId).first().associateBy { it.epId }
            dao.upsertEps(unique.map { ep ->
                existing[ep.id]?.also { require(it.epOrder == ep.order) } ?: DownloadEp(
                    epId = ep.id, comicId = comicId, epOrder = ep.order, title = ep.title,
                    createdAt = nowMillis(),
                )
            })
            dao.refreshProgress(comicId, nowMillis())
            files.finishAllEpisodesRequest(comicId, request)
        }
    }

    private suspend fun downloadCover(comicId: String): String? {
        val comic = dao.getComic(comicId) ?: return null
        if (comic.thumbFileServer.isBlank() || comic.thumbPath.isBlank()) return null
        val destination = files.coverFile(comicId)
        return try {
            if (destination.isFile) {
                // Recover a cover renamed just before a process/storage failure in its metadata write.
                if (comic.thumbFormat == null) {
                    val format = imageFormat(destination)
                    withContext(NonCancellable) {
                        withComicLock(comicId) {
                            val current = dao.getComic(comicId)
                            if (current != null && current.status in activeStatuses) {
                                dao.upsertComic(current.copy(thumbFormat = format, updatedAt = nowMillis()))
                            }
                        }
                    }
                }
                return null
            }
            val staged = retry { downloader.download(imageUrl(PicaImage(comic.thumbFileServer, comic.thumbPath)), destination) }
            try {
                withContext(NonCancellable) {
                    withComicLock(comicId) {
                        val current = dao.getComic(comicId)
                        if (current?.status in activeStatuses && current?.thumbFileServer == comic.thumbFileServer &&
                            current.thumbPath == comic.thumbPath) {
                            staged.commit()
                            dao.upsertComic(current.copy(thumbFormat = staged.format, updatedAt = nowMillis()))
                        }
                    }
                }
            } finally {
                staged.discard()
            }
            null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            downloadError(exception)
        }
    }

    private suspend fun resolvePictures(ep: DownloadEp) {
        withComicLock(ep.comicId) {
            if (dao.getComic(ep.comicId)?.status !in activeStatuses || dao.getEp(ep.epId)?.status !in activeStatuses) return@withComicLock
            dao.transitionEp(ep.epId, DownloadStatus.RUNNING, updatedAt = nowMillis())
        }
        try {
            var page = 1
            var index = 0
            do {
                val result = retry {
                    apiProvider().comicPicturePageWithQuality(
                        ep.comicId, ep.epOrder, page, ImageQuality.valueOf(latestSettings.value.imageQuality.name),
                    ).getOrThrow()
                }.pages
                val pictures = result.docs.map { picture ->
                    require(picture.id.isNotBlank()) { "获取图片失败：清单格式错误" }
                    index++
                    DownloadPicture(
                        pictureId = picture.id, comicId = ep.comicId, epId = ep.epId,
                        epOrder = ep.epOrder, pictureIndex = index, fileServer = picture.media.fileServer,
                        path = picture.media.path, originalName = picture.media.originalName, createdAt = nowMillis(),
                    )
                }
                withComicLock(ep.comicId) {
                    if (dao.getComic(ep.comicId)?.status !in activeStatuses || dao.getEp(ep.epId)?.status !in activeStatuses) return@withComicLock
                    dao.upsertPictures(pictures.map { picture ->
                        dao.getPicture(picture.pictureId)?.also {
                            require(it.epId == ep.epId && it.pictureIndex == picture.pictureIndex)
                        } ?: picture
                    })
                }
                page++
            } while (page <= maxOf(1, result.pages))
            withComicLock(ep.comicId) {
                if (dao.getComic(ep.comicId)?.status !in activeStatuses) return@withComicLock
                val current = dao.getEp(ep.epId) ?: return@withComicLock
                if (current.status !in activeStatuses) return@withComicLock
                dao.upsertEps(listOf(current.copy(picturesFetched = true, status = DownloadStatus.QUEUED,
                    selectedPictureCount = index, updatedAt = nowMillis())))
                dao.refreshProgress(ep.comicId, nowMillis())
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            withComicLock(ep.comicId) {
                if (dao.getComic(ep.comicId)?.status in activeStatuses && dao.getEp(ep.epId)?.status in activeStatuses) {
                    dao.transitionEp(ep.epId, DownloadStatus.FAILED, downloadError(exception), nowMillis())
                }
            }
        }
    }

    private suspend fun downloadPendingPictures(comicId: String) = coroutineScope {
        val tasks = Channel<PictureTask>(100, onUndeliveredElement = { it.finished.cancel() })
        val results = Channel<PictureEvent>(32, onUndeliveredElement = { it.discard() })
        // Keep the bounded result drain alive until workers exit and their channel is closed.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { collectPictureResults(comicId, results) }
        }
        val collecting = ActivePictureResults(comicId, results, collector)
        activePictureResults = collecting
        val workers = List(16) {
            launch {
                for (task in tasks) {
                    try {
                        downloadPicture(task.picture, results)
                    } finally {
                        task.finished.complete(Unit)
                    }
                }
            }
        }
        var finishedNormally = false
        try {
            var previousEpId: String? = null
            while (dao.getComic(comicId)?.status in activeStatuses) {
                val pending = dao.nextPendingPictures(comicId, 100)
                if (pending.isEmpty()) break
                val epId = pending.first().epId
                if (previousEpId != null && previousEpId != epId) {
                    val flushed = CompletableDeferred<Unit>()
                    results.send(PictureEvent.Flush(flushed))
                    flushed.await()
                }
                previousEpId = epId
                withComicLock(comicId) {
                    if (dao.getEp(epId)?.status == DownloadStatus.QUEUED) {
                        dao.transitionEp(epId, DownloadStatus.RUNNING, updatedAt = nowMillis())
                    }
                }
                val batch = pending.takeWhile { it.epId == epId }.map { PictureTask(it, CompletableDeferred()) }
                batch.forEach { tasks.send(it) }
                batch.forEach { it.finished.await() }
            }
            finishedNormally = true
        } finally {
            tasks.close()
            if (!finishedNormally) workers.forEach { it.cancel() }
            withContext(NonCancellable) {
                try {
                    try {
                        workers.joinAll()
                    } finally {
                        results.close()
                    }
                    collector.join()
                } finally {
                    tasks.cancel()
                    results.cancel()
                    if (activePictureResults === collecting) activePictureResults = null
                }
            }
        }
    }

    private suspend fun downloadPicture(picture: DownloadPicture, results: Channel<PictureEvent>) {
        var claimed: DownloadPicture? = null
        val event = try {
            val staged = retry {
                if (claimed == null) {
                    // Keep the claim generation even if Wi-Fi disappears between the two DAO calls.
                    withContext(NonCancellable) {
                        if (dao.claimPicture(picture.pictureId, nowMillis()) != 1) throw PictureNotClaimed()
                        claimed = dao.getPicture(picture.pictureId) ?: throw PictureNotClaimed()
                    }
                }
                val current = checkNotNull(claimed)
                mutableProgress.update { it.copy(currentEpTitle = "第 ${current.epOrder} 话") }
                downloader.download(
                    imageUrl(PicaImage(current.fileServer, current.path, current.originalName)),
                    files.pictureFile(current.comicId, current.epOrder, current.pictureIndex),
                )
            }
            PictureEvent.Ready(checkNotNull(claimed).copy(
                status = DownloadStatus.DONE, downloadedBytes = staged.bytes, fileSizeBytes = staged.bytes,
                format = staged.format, error = null, updatedAt = nowMillis(),
            ), staged)
        } catch (_: PictureNotClaimed) {
            return
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val snapshot = claimed ?: throw exception
            PictureEvent.Ready(snapshot.copy(status = DownloadStatus.FAILED, error = downloadError(exception)))
        }
        try {
            results.send(event)
        } catch (exception: Throwable) {
            event.discard()
            throw exception
        }
    }

    private suspend fun collectPictureResults(comicId: String, results: Channel<PictureEvent>) {
        val pending = mutableListOf<PictureEvent.Ready>()
        val flushPolicy = DownloadProgressFlushPolicy(progressClockMillis)
        suspend fun flush() {
            if (pending.isEmpty()) return
            try {
                commitPictureResults(comicId, pending)
                flushPolicy.onFlushed()
            } finally {
                pending.clear()
            }
        }
        suspend fun flushAndNotify(event: PictureEvent.Flush) {
            try {
                flush()
                event.finished.complete(Unit)
            } catch (exception: Throwable) {
                event.finished.completeExceptionally(exception)
                throw exception
            }
        }
        try {
            while (true) {
                if (flushPolicy.shouldFlush(pending.size)) {
                    flush()
                    continue
                }
                val next = if (pending.isEmpty()) results.receiveCatching() else {
                    withTimeoutOrNull(flushPolicy.timeUntilFlushMillis()) { results.receiveCatching() }
                }
                if (next == null) {
                    flush()
                    continue
                }
                if (next.isClosed) break
                when (val event = next.getOrThrow()) {
                    is PictureEvent.Ready -> {
                        pending.add(event)
                        if (event.picture.status == DownloadStatus.FAILED) flush()
                    }
                    is PictureEvent.Flush -> flushAndNotify(event)
                }
            }
        } finally {
            try {
                // Completion, cancellation, engine failure and shutdown all persist buffered results.
                withContext(NonCancellable) {
                    while (true) {
                        when (val event = results.tryReceive().getOrNull() ?: break) {
                            is PictureEvent.Ready -> {
                                pending.add(event)
                                if (pending.size >= 16) flush()
                            }
                            is PictureEvent.Flush -> flushAndNotify(event)
                        }
                    }
                    flush()
                }
            } finally {
                pending.forEach { it.discard() }
                while (true) (results.tryReceive().getOrNull() ?: break).discard()
            }
        }
    }

    private suspend fun flushActivePictureResults(comicId: String) {
        val collecting = activePictureResults?.takeIf { it.comicId == comicId } ?: return
        if (!collecting.collector.isActive) {
            collecting.collector.join()
            return
        }
        val flushed = CompletableDeferred<Unit>()
        try {
            collecting.results.send(PictureEvent.Flush(flushed))
            flushed.await()
        } catch (_: ClosedSendChannelException) {
            collecting.collector.join()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            collecting.collector.join()
        }
    }

    private suspend fun commitPictureResults(comicId: String, events: List<PictureEvent.Ready>) {
        if (events.isEmpty()) return
        withContext(NonCancellable) {
            withComicLock(comicId) {
                val committed = mutableListOf<PictureEvent.Ready>()
                try {
                    val comic = dao.getComic(comicId)
                    val prepared = events.mapNotNull { event ->
                        val current = dao.getPicture(event.picture.pictureId)
                        val ep = dao.getEp(event.picture.epId)
                        if (comic?.status !in activeStatuses || ep?.status !in activeStatuses ||
                            current?.acceptsResult(event.picture) != true) return@mapNotNull null
                        if (event.staged != null) {
                            try {
                                event.staged.commit()
                                committed.add(event)
                            } catch (exception: IOException) {
                                return@mapNotNull event.picture.copy(status = DownloadStatus.FAILED, downloadedBytes = 0,
                                    fileSizeBytes = 0, error = downloadError(exception))
                            }
                        }
                        event.picture
                    }
                    dao.savePictureResults(prepared, nowMillis())
                    prepared.map { it.epId }.distinct().forEach { finishEpisodeIfReady(it) }
                    committed.forEach { event ->
                        val saved = dao.getPicture(event.picture.pictureId)
                        if (saved?.status != DownloadStatus.DONE || saved.attemptCount != event.picture.attemptCount) {
                            event.staged!!.destination.delete()
                        }
                    }
                } finally {
                    events.forEach { it.discard() }
                }
            }
        }
        refreshFailureCount(comicId)
    }

    private suspend fun summarize(comicId: String, coverError: String?) = withComicLock(comicId) {
        if (dao.getComic(comicId)?.status !in activeStatuses) return@withComicLock
        dao.observeEps(comicId).first().forEach { finishEpisodeIfReady(it.epId) }
        val eps = dao.observeEps(comicId).first()
        val counts = dao.getCounts(comicId) ?: return@withComicLock
        if (files.allEpisodesRequest(comicId) != null || eps.any { it.status in activeStatuses } ||
            counts.queuedPictures > 0 || counts.runningPictures > 0) return@withComicLock
        val error = coverError ?: eps.firstOrNull { it.status == DownloadStatus.FAILED }?.error
        val failed = coverError != null || eps.any { it.status == DownloadStatus.FAILED } || counts.failedPictures > 0
        dao.transitionComic(comicId, if (failed) DownloadStatus.FAILED else DownloadStatus.DONE, error, nowMillis())
        dao.refreshProgress(comicId, nowMillis())
    }

    /** Called under the comic lock, so a completed chapter is readable before its siblings finish. */
    private suspend fun finishEpisodeIfReady(epId: String) {
        val ep = dao.getEp(epId) ?: return
        if (ep.status !in activeStatuses || !ep.picturesFetched) return
        val pictures = dao.observePictures(epId).first()
        if (pictures.any { it.status != DownloadStatus.DONE && it.status != DownloadStatus.FAILED }) return
        if (ep.status == DownloadStatus.QUEUED) dao.transitionEp(epId, DownloadStatus.RUNNING, updatedAt = nowMillis())
        val failed = pictures.firstOrNull { it.status == DownloadStatus.FAILED }
        dao.transitionEp(epId, if (failed == null) DownloadStatus.DONE else DownloadStatus.FAILED, failed?.error, nowMillis())
    }

    private suspend fun <T> retry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return networkOperation(block).also { limiter.onSuccess() }
            } catch (exception: IOException) {
                val status = when (exception) {
                    is DownloadHttpException -> exception.status
                    is PicaException -> exception.httpStatus?.takeIf { it !in 200..299 } ?: exception.code
                    else -> null
                }
                if (status == 429 || status == 503) {
                    limiter.onHttpFailure(status, (exception as? DownloadHttpException)?.retryAfterMillis)
                }
                if (attempt >= retryPolicy.maxAttempts || status != null && status in 400..499 &&
                    status != 408 && status != 429) throw exception
                delay(retryPolicy.delayMillis(attempt++))
            }
        }
    }

    /** A Wi-Fi change cancels sockets, then retries the same attempt after an allowed network returns. */
    private suspend fun <T> networkOperation(block: suspend () -> T): T {
        while (true) {
            networkAllowed.first { it }
            val result = limiter.withPermit {
                supervisorScope {
                    val operation = async { block() }
                    val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                        networkAllowed.first { !it }
                        operation.cancel(NetworkConstraintChanged())
                    }
                    try {
                        NetworkResult.Value(operation.await())
                    } catch (exception: CancellationException) {
                        if (exception is NetworkConstraintChanged) NetworkResult.Interrupted else throw exception
                    } finally {
                        observer.cancelAndJoin()
                    }
                }
            }
            when (result) {
                is NetworkResult.Value -> return result.value
                NetworkResult.Interrupted -> Unit
            }
        }
    }

    private suspend fun updateNetworkGate() {
        val preferences = latestSettings.value
        val connection = latestNetwork.value
        networkAllowed.value = connection.connected && (!preferences.downloadWifiOnly || connection.wifi)
        limiter.setEnabled(networkAllowed.value)
        mutableProgress.update { it.copy(waitingForWifi = preferences.downloadWifiOnly && !connection.wifi) }
    }

    private fun imageUrl(image: PicaImage): String = imageUrlProvider?.invoke(image) ?: PicaImageUrls.build(
        image,
        latestSettings.value.toImageServer(),
    )

    private suspend fun removeDeletingComic(comicId: String) = withComicLock(comicId) {
        if (dao.getComic(comicId)?.status == DownloadStatus.DELETING) {
            files.deleteComic(comicId)
            dao.deleteComic(comicId)
        }
    }

    private suspend fun refreshFailureCount(comicId: String) {
        failedPictures[comicId] = dao.getCounts(comicId)?.failedPictures ?: 0
        updateQueueProgress()
    }

    private fun updateQueueProgress() {
        val comics = queue.value.filter { it.comicId in trackedComics }
        mutableProgress.update {
            it.copy(
                activeComics = comics.count { comic -> comic.status in activeStatuses },
                completedComics = comics.count { comic -> comic.status == DownloadStatus.DONE },
                failedComics = comics.count { comic -> comic.status == DownloadStatus.FAILED },
                totalPictures = comics.sumOf { comic -> manifestTotals[comic.comicId] ?: comic.selectedPictureCount },
                pictureTotalsKnown = comics.isNotEmpty() && comics.all { comic -> manifestTotals.containsKey(comic.comicId) },
                completedPictures = comics.sumOf { comic -> comic.completedPictureCount },
                downloadedBytes = comics.sumOf { comic -> comic.downloadedBytes },
                failedPictures = comics.sumOf { comic -> failedPictures[comic.comicId] ?: 0 },
            )
        }
    }

    private fun recordBytes(count: Int) {
        val total = speedBytes.addAndGet(count.toLong())
        val start = speedWindowStart.get()
        val now = nowMillis()
        if (now - start >= 250 && speedWindowStart.compareAndSet(start, now)) {
            speedBytes.addAndGet(-total)
            mutableProgress.update { it.copy(bytesPerSecond = total * 1_000 / (now - start)) }
        }
    }

    private suspend fun <T> withComicLock(comicId: String, block: suspend () -> T): T =
        comicLocks.computeIfAbsent(comicId) { Mutex() }.withLock { block() }

    private fun cancelActiveComic(comicId: String): Job? = activeComic?.takeIf { it.comicId == comicId }?.job?.also {
        it.cancel(ComicStopped())
    }

    private data class ActiveComic(val comicId: String, val job: Job)
    private data class ActivePictureResults(val comicId: String, val results: Channel<PictureEvent>, val collector: Job)
    private data class PictureTask(val picture: DownloadPicture, val finished: CompletableDeferred<Unit>)
    private class PictureNotClaimed : Exception()
    private class ComicStopped : CancellationException("Comic paused or deleted")
    private class NetworkConstraintChanged : CancellationException("Download network constraint changed")

    private sealed interface NetworkResult<out T> {
        data class Value<T>(val value: T) : NetworkResult<T>
        data object Interrupted : NetworkResult<Nothing>
    }

    private sealed interface PictureEvent {
        data class Ready(val picture: DownloadPicture, val staged: StagedDownload? = null) : PictureEvent
        data class Flush(val finished: CompletableDeferred<Unit>) : PictureEvent

        fun discard() {
            when (this) {
                is Ready -> staged?.discard()
                is Flush -> finished.cancel()
            }
        }
    }
}

private fun downloadError(exception: Exception): String = when {
    exception is SocketTimeoutException -> "下载失败：连接超时"
    exception.message?.contains("ENOSPC") == true || exception.message?.contains("No space left on device") == true ->
        "下载失败：存储空间不足"
    exception is PicaException || exception is DownloadHttpException -> exception.message ?: "网络请求失败"
    exception.message?.any { it in '\u4e00'..'\u9fff' } == true -> exception.message!!
    else -> "下载失败"
}
