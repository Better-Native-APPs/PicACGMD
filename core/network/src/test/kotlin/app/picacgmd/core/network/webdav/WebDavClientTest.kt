package app.picacgmd.core.network.webdav

import java.net.ProxySelector
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.Dispatcher
import okhttp3.Credentials
import okhttp3.OkHttpClient

class WebDavClientTest {
    private val tls = TestTls()
    private val server = MockWebServer().apply { useHttps(tls.context.socketFactory); start() }
    private val client = OkHttpWebDavClient(tls.context.socketFactory, tls.trustManager)
    private val account = WebDavAccount(WebDavEndpoint.parse(server.url("/dav/").toString()).getOrThrow(),
        "test-reader", "private-webdav-password")

    @AfterTest fun close() { client.close(); server.close() }

    @Test fun createsTheMissingFolderAfterProbingTheParent() = runTest {
        enqueue(207); enqueue(201)
        assertEquals(WebDavConnection(true), client.testConnection(account).getOrThrow())
        val probe = request()
        assertEquals("PROPFIND", probe.method)
        assertEquals("/dav/", probe.url.encodedPath)
        assertEquals("0", probe.headers["Depth"])
        assertTrue(probe.body!!.utf8().contains("DAV:"))
        assertNull(probe.headers["Authorization"])
        val create = request()
        assertEquals("MKCOL", create.method)
        assertEquals("/dav/PicACGMD/", create.url.encodedPath)
    }

    @Test fun anExistingFolderIsVerifiedAfterMkcol405() = runTest {
        enqueue(207); enqueue(405); enqueue(207)
        assertEquals(WebDavConnection(false), client.testConnection(account).getOrThrow())
        assertEquals(listOf("PROPFIND", "MKCOL", "PROPFIND"), List(3) { request().method })
    }

    @Test fun ordinaryHttpSuccessIsNotDavSupport() = runTest {
        enqueue(200)
        assertFailure(WebDavError.NOT_WEBDAV, client.testConnection(account))
        assertEquals(1, server.requestCount)
    }

    @Test fun parentMissingAndQuotaAreTyped() = runTest {
        enqueue(404)
        assertFailure(WebDavError.PARENT_MISSING, client.testConnection(account))
        enqueue(207); enqueue(409)
        assertFailure(WebDavError.PARENT_MISSING, client.testConnection(account))
        enqueue(507)
        assertFailure(WebDavError.QUOTA, client.getHistory(account))
        enqueue(403)
        assertFailure(WebDavError.PERMISSION, client.getHistory(account))
    }

    @Test fun get404IsAnEmptyRemoteAndFirstPutIsConditional() = runTest {
        enqueue(404); enqueue(201)
        val file = client.getHistory(account).getOrThrow()
        assertNull(file.content)
        assertSame(WebDavRevision.Missing, file.revision)
        client.putHistory(account, "whole file".toByteArray(), file.revision).getOrThrow()
        request()
        val put = request()
        assertEquals("PUT", put.method)
        assertEquals("*", put.headers["If-None-Match"])
        assertNull(put.headers["If-Match"])
        assertEquals("whole file", put.body!!.utf8())
    }

    @Test fun getEtagIsSentBackWithoutAnyApiHeaders() = runTest {
        server.enqueue(MockResponse.Builder().body("history").addHeader("ETag", "\"revision-1\"").build())
        enqueue(204)
        val file = client.getHistory(account).getOrThrow()
        assertEquals("history", file.content!!.toString(Charsets.UTF_8))
        assertEquals(WebDavRevision.Existing("\"revision-1\""), file.revision)
        client.putHistory(account, "merged".toByteArray(), file.revision).getOrThrow()
        request()
        val put = request()
        assertEquals("\"revision-1\"", put.headers["If-Match"])
        assertEquals("application/json; charset=utf-8", put.headers["Content-Type"])
        listOf("api-key", "signature", "app-channel", "authorization").forEach { assertNull(put.headers[it]) }
    }

    @Test fun aServerWithoutAnEtagUsesPlainPut() = runTest {
        enqueue(200, "history"); enqueue(204)
        val file = client.getHistory(account).getOrThrow()
        assertEquals(WebDavRevision.Existing(null), file.revision)
        client.putHistory(account, "merged".toByteArray(), file.revision).getOrThrow()
        request()
        val put = request()
        assertNull(put.headers["If-Match"])
        assertNull(put.headers["If-None-Match"])
    }

    @Test fun aWeakEtagUsesPlainPutOnAServerThatRequiresStrongComparison() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "GET" -> MockResponse.Builder().body("history").addHeader("ETag", "W/\"revision\"").build()
                "PUT" -> MockResponse.Builder().code(
                    if (request.headers["If-Match"] != null || request.headers["If-None-Match"] != null) 412 else 204,
                ).build()
                else -> MockResponse.Builder().code(405).build()
            }
        }
        val file = client.getHistory(account).getOrThrow()
        assertEquals(WebDavRevision.Existing(null), file.revision)
        client.putHistory(account, "merged".toByteArray(), file.revision).getOrThrow()
        request()
        val put = request()
        assertEquals("PUT", put.method)
        assertNull(put.headers["If-Match"])
        assertNull(put.headers["If-None-Match"])
        assertEquals(2, server.requestCount)
    }

    @Test fun evenAnExplicitWeakRevisionIsNeverSentOrPretendedToBeStrong() = runTest {
        enqueue(204)
        client.putHistory(account, "merged".toByteArray(), WebDavRevision.Existing("W/\"revision\"")).getOrThrow()
        val put = request()
        assertNull(put.headers["If-Match"])
        assertNull(put.headers["If-None-Match"])
    }

    @Test fun a412CanBeRetriedAfterGettingTheNewEtag() = runTest {
        server.enqueue(MockResponse.Builder().body("first").addHeader("ETag", "\"one\"").build())
        enqueue(412)
        server.enqueue(MockResponse.Builder().body("second").addHeader("ETag", "\"two\"").build())
        enqueue(204)
        val first = client.getHistory(account).getOrThrow()
        assertFailure(WebDavError.CHANGED_ELSEWHERE, client.putHistory(account, "merged first".toByteArray(), first.revision))
        val second = client.getHistory(account).getOrThrow()
        client.putHistory(account, "merged second".toByteArray(), second.revision).getOrThrow()
        val requests = List(4) { request() }
        assertEquals(listOf("GET", "PUT", "GET", "PUT"), requests.map { it.method })
        assertEquals("\"one\"", requests[1].headers["If-Match"])
        assertEquals("\"two\"", requests[3].headers["If-Match"])
    }

    @Test fun basicAuthIsChallengeOnlyAndSecretsNeverReachDiagnostics() = runTest {
        val authorization = Credentials.basic(account.username, account.password, Charsets.UTF_8)
        val logs = mutableListOf<String>()
        val logger = Logger.getLogger("okhttp3")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { synchronized(logs) { logs += record.message.orEmpty() } }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"history\"").build())
            server.enqueue(MockResponse.Builder().code(401).body("echo $authorization ${account.password}").build())
            val failure = client.getHistory(account)
            assertFailure(WebDavError.AUTHENTICATION, failure)
            assertNull(request().headers["Authorization"])
            assertEquals(authorization, request().headers["Authorization"])
            val diagnostics = listOf(failure.toString(), account.toString(), account.endpoint.toString(),
                WebDavException(WebDavError.AUTHENTICATION).toString()) + synchronized(logs) { logs.toList() }
            diagnostics.forEach { assertFalse(it.contains(account.password)); assertFalse(it.contains(authorization)) }
        } finally { logger.removeHandler(handler) }
    }

    @Test fun aDigestOnlyChallengeDoesNotSendBasicCredentials() = runTest {
        server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Digest realm=\"history\"").build())
        assertFailure(WebDavError.AUTHENTICATION, client.getHistory(account))
        assertNull(request().headers["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test fun sameOriginHttpsRedirectPreservesChallengedAuth() = runTest {
        server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"history\"").build())
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/moved/history.json").build())
        enqueue(200, "history")
        assertEquals("history", client.getHistory(account).getOrThrow().content!!.toString(Charsets.UTF_8))
        assertNull(request().headers["Authorization"])
        val challenged = request()
        val redirected = request()
        assertEquals("/moved/history.json", redirected.url.encodedPath)
        assertEquals(challenged.headers["Authorization"], redirected.headers["Authorization"])
    }

    @Test fun redirectsCannotDowngradeToCleartext() = runTest {
        val cleartext = server.url("/elsewhere").newBuilder().scheme("http").build()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", cleartext.toString()).build())
        assertFailure(WebDavError.UNSAFE_REDIRECT, client.getHistory(account))
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsNeverSendCredentialsToAnotherHostOrPort() = runTest {
        MockWebServer().use { other ->
            other.useHttps(tls.context.socketFactory); other.start()
            listOf(other.url("/history.json"), other.url("/history.json").newBuilder().host("127.0.0.1").build()).forEach { target ->
                server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"history\"").build())
                server.enqueue(MockResponse.Builder().code(307).addHeader("Location", target.toString()).build())
                assertFailure(WebDavError.UNSAFE_REDIRECT, client.getHistory(account))
            }
            assertEquals(0, other.requestCount)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun oversizedContentLengthAndChunkedBodiesAreRejected() = runTest {
        server.enqueue(MockResponse.Builder().body("x").setHeader("Content-Length", OkHttpWebDavClient.MAX_HISTORY_BYTES + 1).build())
        assertFailure(WebDavError.FILE_TOO_LARGE, client.getHistory(account))
        server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(OkHttpWebDavClient.MAX_HISTORY_BYTES + 1), 8_192).build())
        assertFailure(WebDavError.FILE_TOO_LARGE, client.getHistory(account))
    }

    @Test fun cleartextAndMalformedAddressesAreRejectedBeforeAnyRequest() {
        assertFailure(WebDavError.HTTPS_REQUIRED, WebDavEndpoint.parse("http://localhost/dav"))
        listOf("localhost/dav", "https://user:private@localhost/dav", "https://localhost/dav?token=private",
            "https://localhost/dav#fragment", "https://local host/dav", "https:\\localhost\\dav").forEach {
            assertFailure(WebDavError.INVALID_ADDRESS, WebDavEndpoint.parse(it))
        }
        listOf("", "..", "folder/child", "folder\\child").forEach {
            assertFailure(WebDavError.INVALID_FOLDER, WebDavEndpoint.parse("https://localhost/dav", it))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun collectionAndFolderAreEncodedAsSeparatePathSegments() {
        val endpoint = WebDavEndpoint.parse("https://localhost/dav", "阅读记录 ?#").getOrThrow()
        assertEquals("/dav/", endpoint.collection.encodedPath)
        assertEquals("阅读记录 ?#", endpoint.folder.pathSegments[1])
        assertEquals("history.json", endpoint.history.pathSegments.last())
    }

    @Test fun tlsAndTimeoutFailuresAreTyped() = runTest {
        OkHttpWebDavClient().use { untrusted -> assertFailure(WebDavError.TLS, untrusted.getHistory(account)) }
        server.enqueue(MockResponse.Builder().body("history").headersDelay(1, TimeUnit.SECONDS).build())
        OkHttpWebDavClient(tls.context.socketFactory, tls.trustManager, 100).use {
            assertFailure(WebDavError.TIMEOUT, it.getHistory(account))
        }
    }

    @Test fun cancellationCancelsAnInFlightBodyRead() = runTest {
        server.enqueue(MockResponse.Builder().body("history").bodyDelay(2, TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.Default) { client.getHistory(account) }
        request()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun clientHasNoApiWiringAndFollowsTheSystemProxy() {
        val field = OkHttpWebDavClient::class.java.getDeclaredField("client").apply { isAccessible = true }
        val http = field.get(client) as OkHttpClient
        assertTrue(http.interceptors.isEmpty()); assertTrue(http.networkInterceptors.isEmpty())
        assertNull(http.proxy); assertSame(ProxySelector.getDefault(), http.proxySelector)
        assertSame(okhttp3.Dns.SYSTEM, http.dns)
        assertFalse(http.followRedirects); assertFalse(http.followSslRedirects)
        assertFalse(http.retryOnConnectionFailure)
        assertEquals(15_000, http.connectTimeoutMillis); assertEquals(30_000, http.readTimeoutMillis)
        assertEquals(30_000, http.writeTimeoutMillis); assertEquals(60_000, http.callTimeoutMillis)
    }

    private fun enqueue(code: Int, body: String = "") { server.enqueue(MockResponse.Builder().code(code).body(body).build()) }
    private fun request(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun assertFailure(error: WebDavError, result: WebDavResult<*>) { assertEquals(error, assertIs<WebDavResult.Failure>(result).error) }

    private class TestTls {
        val context: SSLContext
        val trustManager: X509TrustManager
        init {
            val password = "test-only".toCharArray()
            val store = KeyStore.getInstance("PKCS12").apply {
                WebDavClientTest::class.java.getResourceAsStream("/webdav-test.p12").use { load(assertNotNull(it), password) }
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            trustManager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
            context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        }
    }
}
