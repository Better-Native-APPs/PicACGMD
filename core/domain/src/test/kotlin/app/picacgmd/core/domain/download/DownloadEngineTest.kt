package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadPicture
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.PicaClientFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadEngineTest {
    private lateinit var apiServer: MockWebServer
    private lateinit var imageServer: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var root: File
    private lateinit var dao: FakeDownloadDao
    private lateinit var settings: MutableStateFlow<AppSettings>
    private lateinit var network: MutableStateFlow<DownloadNetworkState>

    @BeforeTest
    fun setUp() {
        apiServer = MockWebServer().apply { start() }
        imageServer = MockWebServer().apply { start() }
        client = PicaClientFactory.imageClient()
        root = Files.createTempDirectory("pikapika-download-test-").toFile()
        dao = FakeDownloadDao()
        settings = MutableStateFlow(AppSettings.DEFAULT.copy(imageRoutingMode = RoutingMode.NONE))
        network = MutableStateFlow(DownloadNetworkState(connected = true, wifi = true))
    }

    @AfterTest
    fun tearDown() {
        imageServer.close()
        apiServer.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        root.deleteRecursively()
    }

    @Test
    fun boundedPoolNeverStartsMoreThanTheLiveLimit() = runTest {
        seed(32)
        val release = CountDownLatch(1)
        val firstWave = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        imageServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                if (entered.incrementAndGet() == 4) firstWave.complete(Unit)
                try {
                    check(release.await(10, TimeUnit.SECONDS))
                    return imageResponse()
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val engine = engine(this)
        val worker = launch { engine.runUntilIdle() }
        try {
            firstWave.await()
            runCurrent()
            assertEquals(4, imageServer.requestCount)
        } finally {
            release.countDown()
        }
        worker.join()
        assertEquals(4, peak.get())
        assertEquals(32, imageServer.requestCount)
        assertEquals(DownloadStatus.DONE, dao.getComic(COMIC)?.status)
        assertEquals(32, dao.getCounts(COMIC)?.completedPictures)
        assertTrue(dao.resultBatchSizes.any { it > 1 }, "Results should be batched, not one transaction per image")
    }

    @Test
    fun retryExhaustionLeavesThePictureAndComicFailed() = runTest {
        seed(1)
        repeat(5) { imageServer.enqueue(MockResponse.Builder().code(503).body("busy").build()) }
        val engine = engine(this)
        engine.runUntilIdle()
        assertEquals(5, imageServer.requestCount)
        assertEquals(DownloadStatus.FAILED, dao.getPicture("picture-1")?.status)
        assertEquals(DownloadStatus.FAILED, dao.getEp(EP)?.status)
        assertEquals(DownloadStatus.FAILED, dao.getComic(COMIC)?.status)
        assertEquals(1, engine.progress.value.failedPictures)
        assertFalse(File(root, "$COMIC/1/1").exists())
        assertEquals(1, engine.retryFailed(COMIC))
        assertEquals(DownloadStatus.QUEUED, dao.getPicture("picture-1")?.status)
    }

    @Test
    fun pauseCancelsInflightTransferAndDropsItsLateResult() = runTest {
        seed(1)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        imageServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
                return imageResponse()
            }
        }
        val engine = engine(this)
        val worker = launch { engine.runUntilIdle() }
        try {
            entered.await()
            engine.pause(COMIC)
        } finally {
            release.countDown()
        }
        worker.join()
        assertEquals(DownloadStatus.PAUSED, dao.getComic(COMIC)?.status)
        assertEquals(DownloadStatus.PAUSED, dao.getPicture("picture-1")?.status)
        assertEquals(0, dao.acceptedResults)
        assertFalse(File(root, "$COMIC/1/1").exists())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun wifiOnlyCanBeChangedLiveWithoutRestartingTheWorker() = runTest {
        seed(1)
        settings.value = settings.value.copy(downloadWifiOnly = true)
        network.value = DownloadNetworkState(connected = true, wifi = false)
        imageServer.enqueue(imageResponse())
        val engine = engine(this)
        val worker = launch { engine.runUntilIdle() }
        runCurrent()
        assertEquals(0, imageServer.requestCount)
        assertTrue(engine.progress.value.waitingForWifi)
        settings.value = settings.value.copy(downloadWifiOnly = false)
        worker.join()
        assertEquals(1, imageServer.requestCount)
        assertEquals(DownloadStatus.DONE, dao.getPicture("picture-1")?.status)
    }

    @Test
    fun aRunCancelledWhileWaitingDoesNotLeaveTheWaitFlagBehind() = runTest {
        seed(1)
        settings.value = settings.value.copy(downloadWifiOnly = true)
        network.value = DownloadNetworkState(connected = true, wifi = false)
        val engine = engine(this)
        val worker = launch { engine.runUntilIdle() }
        runCurrent()
        assertTrue(engine.progress.value.waitingForWifi)
        worker.cancelAndJoin()
        assertFalse(engine.progress.value.waitingForWifi)
        assertEquals(0, imageServer.requestCount)
    }

    @Test
    fun batchEnqueueReturnsBeforePagedManifestsAndCoverAreResolved() = runTest {
        val engine = engine(this)
        engine.enqueue(listOf(DownloadRequest(DownloadComic(
            comicId = COMIC, title = "测试漫画", thumbFileServer = imageServer.url("/").toString(), thumbPath = "cover.gif",
        ))))
        assertNotNull(dao.getComic(COMIC))
        assertEquals(0, apiServer.requestCount)
        assertNotNull(engine.files.allEpisodesRequest(COMIC))
        enqueueApi("""{"eps":{"docs":[{"_id":"$EP","order":1,"title":"第一话"}],"pages":2}}""")
        enqueueApi("""{"eps":{"docs":[{"_id":"ep-2","order":2,"title":"第二话"}],"pages":2}}""")
        enqueuePictures("picture-1", page = 1, pages = 2)
        enqueuePictures("picture-2", page = 2, pages = 2)
        enqueuePictures("picture-3", page = 1, pages = 1)
        repeat(4) { imageServer.enqueue(imageResponse()) }
        engine.runUntilIdle()
        assertEquals(5, apiServer.requestCount)
        assertEquals(4, imageServer.requestCount)
        assertEquals(DownloadStatus.DONE, dao.getComic(COMIC)?.status)
        assertEquals(2, dao.getComic(COMIC)?.completedEpCount)
        assertEquals(3, dao.getComic(COMIC)?.completedPictureCount)
        assertEquals("gif", dao.getComic(COMIC)?.thumbFormat)
        listOf("$COMIC/logo", "$COMIC/1/1", "$COMIC/1/2", "$COMIC/2/1").forEach {
            assertEquals(IMAGE, File(root, it).readText(), "Bytes must be preserved without decoding: $it")
        }
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") || it.name == ".all-episodes" })
    }

    @Test
    fun allChapterAndComicManifestsPrecedePageProgressWithAStableDenominator() = runTest {
        val engine = engine(this)
        engine.enqueue(listOf(
            DownloadRequest(DownloadComic(COMIC, "测试漫画"), listOf(
                DownloadEp(EP, COMIC, 1, "第一话"),
                DownloadEp("ep-2", COMIC, 2, "第二话"),
            )),
            DownloadRequest(DownloadComic("comic-2", "另一部漫画"), listOf(
                DownloadEp("ep-3", "comic-2", 1, "第一话"),
            )),
        ))
        enqueuePictures("picture-1", page = 1, pages = 2)
        enqueuePictures("picture-2", page = 2, pages = 2)
        enqueuePictures("picture-3", page = 1, pages = 1)
        enqueuePictures("picture-4", page = 1, pages = 2)
        enqueuePictures("picture-5", page = 2, pages = 2)
        val manifestRequestsAtFirstImage = AtomicInteger(-1)
        imageServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                manifestRequestsAtFirstImage.compareAndSet(-1, apiServer.requestCount)
                return imageResponse()
            }
        }
        val snapshots = mutableListOf<DownloadProgress>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.progress.collect { snapshots.add(it) }
        }
        engine.runUntilIdle()

        assertEquals(5, manifestRequestsAtFirstImage.get(), "All queued manifests must be fetched before transferring pages")
        assertEquals(5, imageServer.requestCount)
        val pageProgress = snapshots.filter { it.pictureTotalsKnown }
        assertTrue(pageProgress.isNotEmpty())
        assertTrue(pageProgress.all { it.totalPictures == 5 })
        assertTrue(pageProgress.zipWithNext().all { (before, after) -> after.completedPictures >= before.completedPictures })
        assertEquals(5, pageProgress.last().completedPictures)
        assertEquals(2, engine.progress.value.completedComics)
    }

    @Test
    fun failedChapterManifestNeverAdvertisesAPartialPageTotalAsComplete() = runTest {
        val engine = engine(this)
        engine.enqueue(listOf(DownloadRequest(DownloadComic(COMIC, "测试漫画"), listOf(
            DownloadEp(EP, COMIC, 1, "第一话"),
            DownloadEp("ep-2", COMIC, 2, "第二话"),
        ))))
        enqueuePictures("picture-1", page = 1, pages = 1)
        apiServer.enqueue(MockResponse.Builder().code(404).body("""{"code":404,"message":"missing"}""").build())
        imageServer.enqueue(imageResponse())
        engine.runUntilIdle()

        assertEquals(2, apiServer.requestCount)
        assertEquals(1, imageServer.requestCount)
        assertEquals(1, engine.progress.value.completedPictures)
        assertFalse(engine.progress.value.pictureTotalsKnown)
        assertEquals(DownloadStatus.FAILED, dao.getComic(COMIC)?.status)
    }

    @Test
    fun restartRecoversInterruptedClaimsAndRemovesTemporaryFiles() = runTest {
        seed(1)
        dao.transitionComic(COMIC, DownloadStatus.RUNNING, updatedAt = 1)
        dao.transitionEp(EP, DownloadStatus.RUNNING, updatedAt = 1)
        assertEquals(1, dao.claimPicture("picture-1", 1))
        val interrupted = File(root, "$COMIC/1/.1.interrupted.part")
        interrupted.parentFile!!.mkdirs()
        interrupted.writeText("partial")
        imageServer.enqueue(imageResponse())
        engine(this).runUntilIdle()
        assertFalse(interrupted.exists())
        assertEquals(DownloadStatus.DONE, dao.getPicture("picture-1")?.status)
        assertEquals(2, dao.getPicture("picture-1")?.attemptCount)
        assertEquals(IMAGE, File(root, "$COMIC/1/1").readText())
    }

    @Test
    fun retryingOneChapterLeavesOtherFailuresAndFinishedFilesUntouched() = runTest {
        seed(1)
        dao.upsertEps(listOf(DownloadEp("ep-2", COMIC, 2, "第二话", picturesFetched = true, status = DownloadStatus.FAILED)))
        dao.upsertPictures(listOf(DownloadPicture("picture-2", COMIC, "ep-2", 2, 1, "server", "image", status = DownloadStatus.FAILED)))
        dao.upsertEps(listOf(DownloadEp("ep-3", COMIC, 3, "第三话", picturesFetched = true, status = DownloadStatus.DONE)))
        dao.upsertPictures(listOf(DownloadPicture("picture-3", COMIC, "ep-3", 3, 1, "server", "image", status = DownloadStatus.DONE)))
        val finished = File(root, "$COMIC/3/1")
        finished.parentFile!!.mkdirs()
        finished.writeText(IMAGE)
        dao.transitionComic(COMIC, DownloadStatus.RUNNING, updatedAt = 1)
        dao.transitionEp(EP, DownloadStatus.RUNNING, updatedAt = 1)
        dao.claimPicture("picture-1", 1)
        dao.savePictureResults(listOf(checkNotNull(dao.getPicture("picture-1")).copy(status = DownloadStatus.FAILED)), 1)
        dao.transitionEp(EP, DownloadStatus.FAILED, updatedAt = 1)
        dao.transitionComic(COMIC, DownloadStatus.FAILED, updatedAt = 1)

        assertEquals(1, engine(this).retryChapter(EP))
        assertEquals(DownloadStatus.QUEUED, dao.getEp(EP)?.status)
        assertEquals(DownloadStatus.QUEUED, dao.getPicture("picture-1")?.status)
        assertEquals(DownloadStatus.FAILED, dao.getEp("ep-2")?.status)
        assertEquals(DownloadStatus.FAILED, dao.getPicture("picture-2")?.status)
        assertEquals(DownloadStatus.DONE, dao.getEp("ep-3")?.status)
        assertEquals(IMAGE, finished.readText())
    }

    @Test
    fun deletingTheLastChapterDoesNotRequeueAllChapters() = runTest {
        seed(1)
        val chapterFile = File(root, "$COMIC/1/1")
        chapterFile.parentFile!!.mkdirs()
        chapterFile.writeText(IMAGE)
        val cover = File(root, "$COMIC/logo").apply { writeText(IMAGE) }
        val engine = engine(this)

        engine.deleteChapter(EP)

        assertNull(dao.getEp(EP))
        assertNull(dao.getPicture("picture-1"))
        assertFalse(chapterFile.exists())
        assertTrue(cover.exists())
        assertEquals(DownloadStatus.DONE, dao.getComic(COMIC)?.status)
        assertEquals(0, dao.getComic(COMIC)?.selectedEpCount)
        engine.runUntilIdle()
        assertEquals(0, apiServer.requestCount)
    }

    @Test
    fun treeDownloadsPersistDocumentUrisAndKeepTheirRootWhenAddingChapters() = runTest {
        val provider = FakeDownloadTreeAccess()
        val files = DownloadFileStore({ root }, { root }, { File(root, "staging") }) { TreeDownloadBackend(provider) }
        settings.value = settings.value.copy(downloadTreeUri = FakeDownloadTreeAccess.TREE)
        val engine = engine(this, files)
        engine.enqueue(listOf(DownloadRequest(DownloadComic(COMIC, "漫画",
            thumbFileServer = imageServer.url("/").toString(), thumbPath = "cover.gif"), listOf(DownloadEp(EP, COMIC, 1, "第一话")))))
        enqueuePictures("picture-1", 1, 1)
        repeat(2) { imageServer.enqueue(imageResponse()) }
        engine.runUntilIdle()
        val comic = checkNotNull(dao.getComic(COMIC))
        val picture = checkNotNull(dao.getPicture("picture-1"))
        assertEquals(FakeDownloadTreeAccess.TREE, comic.storageRoot)
        assertTrue(comic.thumbFilePath.startsWith("content://"))
        assertTrue(picture.filePath.startsWith("content://"))
        assertEquals(IMAGE, provider.openRead(provider.documentId(picture.filePath)).reader().use { it.readText() })
        assertFalse(File(root, "$COMIC/1/1").exists())

        settings.value = settings.value.copy(downloadTreeUri = null)
        engine.enqueue(listOf(DownloadRequest(DownloadComic(COMIC, "漫画"), listOf(DownloadEp("ep-2", COMIC, 2, "第二话")))))
        enqueuePictures("picture-2", 1, 1)
        imageServer.enqueue(imageResponse())
        engine.runUntilIdle()
        assertEquals(FakeDownloadTreeAccess.TREE, dao.getComic(COMIC)?.storageRoot)
        assertTrue(checkNotNull(dao.getPicture("picture-2")).filePath.startsWith("content://"))
        assertEquals(DownloadStatus.DONE, dao.getComic(COMIC)?.status)
    }

    private fun engine(scope: TestScope, files: DownloadFileStore = DownloadFileStore(root)) = DownloadEngine(
        apiProvider = { PicaApi(baseUrl = apiServer.url("/").toString(), client = client) },
        imageClientProvider = { client }, dao = dao, settings = settings, network = network,
        files = files,
        retryPolicy = DownloadRetryPolicy(jitter = { 0 }),
        limiter = AdaptiveDownloadLimiter(nowMillis = { scope.testScheduler.currentTime }, jitter = { 0 }),
        nowMillis = { scope.testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        progressClockMillis = { scope.testScheduler.currentTime },
    )

    private suspend fun seed(count: Int) {
        dao.enqueue(DownloadComic(COMIC, "测试漫画"), listOf(DownloadEp(EP, COMIC, 1, "第一话", picturesFetched = true)))
        dao.upsertPictures((1..count).map {
            DownloadPicture("picture-$it", COMIC, EP, 1, it, imageServer.url("/").toString(), "$it.gif")
        })
        dao.refreshProgress(COMIC, 0)
    }

    private fun enqueueApi(data: String) { apiServer.enqueue(MockResponse.Builder().body("""{"code":200,"data":$data}""").build()) }

    private fun enqueuePictures(id: String, page: Int, pages: Int) {
        enqueueApi("""{"pages":{"docs":[{"_id":"$id","media":{"fileServer":"${imageServer.url("/")}","path":"$id.gif"}}],"page":$page,"pages":$pages}}""")
    }

    private fun imageResponse() = MockResponse.Builder().addHeader("Content-Type", "image/gif").body(IMAGE).build()

    companion object {
        private const val COMIC = "comic"
        private const val EP = "ep-1"
        private val IMAGE = "GIF89a" + "data".repeat(8_192)
    }
}
