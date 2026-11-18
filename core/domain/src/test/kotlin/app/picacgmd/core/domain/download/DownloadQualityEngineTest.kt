package app.picacgmd.core.domain.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.network.PicaApi
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQualityEngineTest {
    private lateinit var root: File
    private lateinit var client: OkHttpClient
    private lateinit var dao: FakeDownloadDao
    private lateinit var preferences: DownloadQualityEnginePreferences
    private val requests = CopyOnWriteArrayList<Request>()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("picacgmd-download-quality-").toFile()
        dao = FakeDownloadDao()
        preferences = DownloadQualityEnginePreferences()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (body, type) = when (request.url.encodedPath) {
                "/comics/comic/order/1/pages" -> {
                    val quality = request.header("image-quality")
                    """{"code":200,"data":{"pages":{"docs":[{"_id":"picture-1","media":{"fileServer":"https://download-quality.test/","path":"$quality.gif"}}],"page":1,"pages":1,"total":1}}}""" to "application/json"
                }
                "/images/original.gif" -> originalImage to "image/gif"
                "/images/low.gif" -> lowImage to "image/gif"
                else -> error("Unexpected download request: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()
    }

    @AfterTest
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        root.deleteRecursively()
    }

    @Test
    fun originalDownloadsRequestOriginalWhileOnlineQualityIsLow() = runTest {
        preferences.edit { it[stringPreferencesKey("download_quality")] = "ORIGINAL" }
        val engine = engine(this)
        enqueue(engine)

        engine.runUntilIdle()

        assertEquals("original", pageRequests().single().header("image-quality"))
        assertEquals(originalImage, File(root, "comic/1/1").readText())
        assertEquals(DownloadStatus.DONE, dao.getComic("comic")?.status)
    }

    @Test
    fun followingOnlineQualityDownloadsLowWhenOnlineQualityIsLow() = runTest {
        preferences.edit { it[stringPreferencesKey("download_quality")] = "SAME_AS_IMAGE" }
        val engine = engine(this)
        enqueue(engine)

        engine.runUntilIdle()

        assertEquals("low", pageRequests().single().header("image-quality"))
        assertEquals(lowImage, File(root, "comic/1/1").readText())
        assertEquals(DownloadStatus.DONE, dao.getComic("comic")?.status)
    }

    @Test
    fun aChoiceMadeAfterEnqueueAppliesToNewPageListsButDoesNotRedownloadCompletedPages() = runTest {
        val engine = engine(this)
        enqueue(engine)
        preferences.edit { it[stringPreferencesKey("download_quality")] = "ORIGINAL" }

        engine.runUntilIdle()

        assertEquals("original", pageRequests().single().header("image-quality"))
        val completed = dao.getPicture("picture-1")
        preferences.edit { it[stringPreferencesKey("download_quality")] = "SAME_AS_IMAGE" }
        enqueue(engine)
        engine.runUntilIdle()

        assertEquals(1, pageRequests().size)
        assertEquals(1, requests.count { it.url.encodedPath.startsWith("/images/") })
        assertEquals(completed, dao.getPicture("picture-1"))
        assertEquals(originalImage, File(root, "comic/1/1").readText())
    }

    private fun engine(scope: TestScope) = DownloadEngine(
        apiProvider = { PicaApi(baseUrl = "https://download-quality.test/", client = client) },
        imageClientProvider = { client },
        dao = dao,
        settings = SettingsRepository(preferences).settings,
        network = flowOf(DownloadNetworkState(connected = true, unmetered = true)),
        files = DownloadFileStore(root),
        imageUrlProvider = { "https://download-quality.test/images/${it.path}" },
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        nowMillis = { scope.testScheduler.currentTime },
        progressClockMillis = { scope.testScheduler.currentTime },
    )

    private suspend fun enqueue(engine: DownloadEngine) = engine.enqueue(listOf(
        DownloadRequest(DownloadComic("comic", "测试漫画"), listOf(DownloadEp("ep-1", "comic", 1, "第一话"))),
    ))

    private fun pageRequests() = requests.filter { it.url.encodedPath.endsWith("/pages") }

    companion object {
        private val originalImage = "GIF89a" + "original".repeat(1_024)
        private val lowImage = "GIF89a" + "low".repeat(1_024)
    }
}

private class DownloadQualityEnginePreferences : DataStore<Preferences> {
    override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(stringPreferencesKey("image_quality") to "LOW"))
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}
