package app.picacgmd.testing

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.session.CredentialStore
import app.picacgmd.core.domain.session.Credentials
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.domain.session.SessionPreferences
import app.picacgmd.core.network.PicaApi
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.rules.TestWatcher
import org.junit.runner.Description

internal class MemorySessionPreferences(initial: AppSettings) : SessionPreferences {
    override val settings = MutableStateFlow(initial)
    override suspend fun setAccountEmail(value: String) { settings.value = settings.value.copy(accountEmail = value) }
    override suspend fun setAuthToken(token: String?, updatedAt: Long) {
        settings.value = settings.value.copy(authToken = token, authTokenUpdatedAt = updatedAt.takeIf { token != null })
    }
}

internal class MemoryCredentialStore(var credentials: Credentials? = null) : CredentialStore {
    override suspend fun read(): Credentials? = credentials
    override suspend fun save(credentials: Credentials) { this.credentials = credentials }
    override suspend fun clear() { credentials = null }
}

/**
 * A signed-in session whose API talks to a local [MockWebServer], so a test can assert the exact
 * requests a screen's logic sends. Responses are served in the order they were enqueued.
 */
internal class TestPicaServer : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val preferences = MemorySessionPreferences(
        AppSettings(accountEmail = "reader@example.com", authToken = "token", autoPunchIn = false),
    )

    fun session(scope: CoroutineScope): SessionManager {
        lateinit var session: SessionManager
        val api = PicaApi(tokenProvider = { session.token }, baseUrl = server.url("/").toString())
        session = SessionManager(
            preferences, MemoryCredentialStore(Credentials("reader@example.com", "saved-password")), { api }, scope,
        )
        return session
    }

    /** Enqueues a success envelope around [data], the JSON of the `data` member. */
    fun enqueue(data: String, delayMillis: Long = 0) {
        val response = MockResponse.Builder().body("""{"code":200,"message":"success","data":$data}""")
        if (delayMillis > 0) response.headersDelay(delayMillis, TimeUnit.MILLISECONDS)
        server.enqueue(response.build())
    }

    fun enqueueFailure(httpCode: Int = 500, body: String = """{"code":$httpCode,"message":"failure"}""") {
        server.enqueue(MockResponse.Builder().code(httpCode).body(body).build())
    }

    fun takeRequest(timeoutSeconds: Long = 5): RecordedRequest =
        assertNotNull(server.takeRequest(timeoutSeconds, TimeUnit.SECONDS), "no request arrived")

    /** The next request, or null when none arrives within [timeoutMillis]: use it to assert that nothing was sent. */
    fun pollRequest(timeoutMillis: Long = 300): RecordedRequest? = server.takeRequest(timeoutMillis, TimeUnit.MILLISECONDS)

    val requestCount: Int get() = server.requestCount

    override fun close() = server.close()
}

/** `METHOD /path?query`, the form in which tests compare requests. */
internal fun RecordedRequest.line(): String =
    "$method ${url.encodedPath}${url.encodedQuery?.let { "?$it" }.orEmpty()}"

/** Installs a test dispatcher as `Dispatchers.Main`, which `viewModelScope` needs on the JVM. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
