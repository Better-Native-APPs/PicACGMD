package app.picacgmd.core.domain.image

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.domain.session.NetworkConfigProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

class ImageLoadProgressInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var registry: ImageLoadProgressRegistry
    private lateinit var client: OkHttpClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        registry = ImageLoadProgressRegistry()
        val progress = ImageLoadProgressInterceptor(registry)
        client = OkHttpClient.Builder()
            .addNetworkInterceptor(progress)
            .eventListenerFactory(progress.eventListenerFactory())
            .retryOnConnectionFailure(false)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @AfterTest
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.close()
    }

    @Test
    fun reportsContentLengthFractionsAndReleasesTheFinishedBody() {
        val body = "x".repeat(16_384)
        server.enqueue(MockResponse.Builder().body(body).setHeader("Content-Type", "image/jpeg").build())
        val url = server.url("/page").toString()
        val progress = assertNotNull(registry.observe(url.replace("http://localhost", "HTTP://LOCALHOST")))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(body.length.toLong(), response.body.contentLength())
            assertEquals("image/jpeg", response.body.contentType().toString())
            assertEquals(0f, progress.value)
            val received = Buffer()
            val firstRead = response.body.source().read(received, 8_192)
            assertTrue(firstRead > 0 && firstRead < body.length)
            assertEquals(firstRead.toFloat() / body.length, progress.value)
            received.writeAll(response.body.source())
            assertEquals(body, received.readUtf8())
            assertEquals(1f, progress.value)
            assertEquals(0, registry.trackedUrlCount)
        }
    }

    @Test
    fun chunkedBodiesRemainIndeterminateUntilTheyFinish() {
        val body = "x".repeat(16_384)
        server.enqueue(MockResponse.Builder().chunkedBody(body, 1_024).build())
        val url = server.url("/chunked").toString()
        val progress = assertNotNull(registry.observe(url))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(-1L, response.body.contentLength())
            assertTrue(response.body.source().read(Buffer(), 1_024) > 0)
            assertNull(progress.value)
            response.body.source().readByteArray()
            assertEquals(1f, progress.value)
            assertEquals(0, registry.trackedUrlCount)
        }
    }

    @Test
    fun closingAnUnreadBodyReleasesItsEntry() {
        server.enqueue(MockResponse.Builder().body("image").build())
        val url = server.url("/closed").toString()
        val progress = assertNotNull(registry.observe(url))
        client.newCall(Request.Builder().url(url).build()).execute().close()
        assertEquals(0, registry.trackedUrlCount)
        assertNull(progress.value)
    }

    @Test
    fun truncatedBodiesReleaseTheirEntryWhenReadingFails() {
        server.enqueue(
            MockResponse.Builder().body("short").setHeader("Content-Length", 50)
                .onResponseEnd(SocketEffect.CloseSocket()).build(),
        )
        val url = server.url("/truncated").toString()
        val progress = assertNotNull(registry.observe(url))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertFailsWith<IOException> { response.body.bytes() }
        }
        assertEquals(0, registry.trackedUrlCount)
        assertNull(progress.value)
    }

    @Test
    fun failuresBeforeAResponseBodyReleasePendingEntries() {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        val url = server.url("/failed").toString()
        val progress = assertNotNull(registry.observe(url))
        assertFailsWith<IOException> { client.newCall(Request.Builder().url(url).build()).execute() }
        assertEquals(0, registry.trackedUrlCount)
        assertNull(progress.value)
    }

    @Test
    fun redirectsStayKeyedByTheOriginalReaderUrl() {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/actual").build())
        server.enqueue(MockResponse.Builder().body("image").build())
        val url = server.url("/redirect").toString()
        val progress = assertNotNull(registry.observe(url))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals("image", response.body.string())
        }
        assertEquals(1f, progress.value)
        assertEquals(0, registry.trackedUrlCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unrelatedImageClientTrafficDoesNotRegisterUrls() {
        for (path in listOf("/download", "/cover", "/avatar")) {
            server.enqueue(MockResponse.Builder().body("image").build())
            client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.body.bytes() }
            assertEquals(0, registry.trackedUrlCount)
        }
    }

    @Test
    fun apiTransportOptionKeepsProgressOffTheApiClient() = runTest {
        val provider = NetworkConfigProvider(
            MutableStateFlow(AppSettings(useApiClientForImages = true, apiRoutingMode = RoutingMode.NONE)), backgroundScope,
        )
        try {
            provider.awaitReady()
            assertTrue(provider.current.imageClient.networkInterceptors.any { it is ImageLoadProgressInterceptor })
            assertFalse(provider.current.apiClient.networkInterceptors.any { it is ImageLoadProgressInterceptor })
            val url = server.url("/api-route-image").toString()
            val progress = assertNotNull(provider.imageLoadProgress.observe(url))
            server.enqueue(MockResponse.Builder().body("image").build())
            provider.current.imageClient.newCall(Request.Builder().url(url).build()).execute().use { it.body.bytes() }
            assertEquals(1f, progress.value)
            assertEquals(0, provider.imageLoadProgress.trackedUrlCount)
        } finally {
            provider.close()
        }
    }
}
