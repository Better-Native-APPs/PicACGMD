package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.UnauthorizedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class SessionManagerTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() { server.close() }

    @Test
    fun loadsSavedTokenWithoutLoggingIn() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.awaitReady()
        assertEquals("expired-token", fixture.session.token)
        assertEquals(SessionState.SignedIn(EMAIL), fixture.session.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun loginPersistsCredentialsAndAutomaticallyPunchesIn() = runTest {
        val fixture = fixture(backgroundScope, initial = AppSettings(autoPunchIn = true), saved = null)
        enqueue("""{"token":"fresh-token"}""")
        enqueue("""{"res":{"status":"ok"}}""")
        fixture.session.login(EMAIL, PASSWORD)
        assertEquals("fresh-token", fixture.session.token)
        assertEquals("fresh-token", fixture.preferences.settings.value.authToken)
        assertEquals(1_000L, fixture.preferences.settings.value.authTokenUpdatedAt)
        assertEquals(Credentials(EMAIL, PASSWORD), fixture.store.credentials)
        assertIs<PunchInState.Done>(fixture.session.punchInState.value)
        assertEquals("/auth/sign-in", takeRequest().url.encodedPath)
        val punch = takeRequest()
        assertEquals("/users/punch-in", punch.url.encodedPath)
        assertEquals("fresh-token", punch.headers["authorization"])
    }

    @Test
    fun rememberingCanBeDisabledAndPunchSettingIsHonoured() = runTest {
        val fixture = fixture(backgroundScope)
        enqueue("""{"token":"fresh-token"}""")
        fixture.session.login(EMAIL, PASSWORD, rememberCredentials = false)
        assertNull(fixture.store.credentials)
        assertEquals(1, server.requestCount)
        assertEquals(PunchInState.Idle, fixture.session.punchInState.value)
    }

    @Test
    fun optionalPunchFailureDoesNotFailSuccessfulLogin() = runTest {
        val fixture = fixture(backgroundScope, initial = AppSettings(autoPunchIn = true), saved = null)
        enqueue("""{"token":"fresh-token"}""")
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        fixture.session.login(EMAIL, PASSWORD)
        assertEquals(SessionState.SignedIn(EMAIL), fixture.session.state.value)
        assertEquals("fresh-token", fixture.session.token)
        assertIs<PunchInState.Failed>(fixture.session.punchInState.value)
    }

    @Test
    fun appOpenWithSavedTokenPerformsEnabledPunchIn() = runTest {
        val fixture = fixture(backgroundScope, initial = savedSettings().copy(autoPunchIn = true))
        enqueue("""{"res":{"status":"ok"}}""")
        fixture.session.onAppOpen()
        assertEquals("/users/punch-in", takeRequest().url.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun http401RelogsInAndRetriesWithNewToken() = runTest {
        val fixture = fixture(backgroundScope)
        unauthorized(httpStatus = true)
        enqueue("""{"token":"fresh-token"}""")
        enqueue("""{"categories":[{"_id":"c","title":"日常"}]}""")
        assertEquals("日常", fixture.session.withApi { it.categories() }.single().title)
        assertEquals("expired-token", takeRequest().headers["authorization"])
        assertEquals("/auth/sign-in", takeRequest().url.encodedPath)
        assertEquals("fresh-token", takeRequest().headers["authorization"])
        assertEquals("fresh-token", fixture.preferences.settings.value.authToken)
    }

    @Test
    fun business401WithoutCredentialsExpiresTheSession() = runTest {
        val fixture = fixture(backgroundScope, saved = null)
        unauthorized()
        assertFailsWith<UnauthorizedException> { fixture.session.withApi { it.categories() } }
        assertNull(fixture.session.token)
        assertNull(fixture.preferences.settings.value.authToken)
        assertIs<SessionState.Expired>(fixture.session.state.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun failedReloginClearsExpiredTokenAndDoesNotRetryForever() = runTest {
        val fixture = fixture(backgroundScope)
        unauthorized()
        unauthorized()
        assertFailsWith<UnauthorizedException> { fixture.session.withApi { it.categories() } }
        assertNull(fixture.session.token)
        assertIs<SessionState.Expired>(fixture.session.state.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retry401DoesNotStartASecondRelogin() = runTest {
        val fixture = fixture(backgroundScope)
        unauthorized()
        enqueue("""{"token":"fresh-token"}""")
        unauthorized()
        assertFailsWith<UnauthorizedException> { fixture.session.withApi { it.categories() } }
        assertNull(fixture.session.token)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun concurrent401ResponsesShareASingleRelogin() = runTest {
        val oldRequests = CountDownLatch(2)
        val logins = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/auth/sign-in" -> {
                    logins.incrementAndGet()
                    success("""{"token":"fresh-token"}""")
                }
                request.headers["authorization"] == "expired-token" -> {
                    oldRequests.countDown()
                    check(oldRequests.await(5, TimeUnit.SECONDS))
                    unauthorizedResponse()
                }
                else -> success("""{"categories":[]}""")
            }
        }
        val fixture = fixture(backgroundScope)
        val first = async { fixture.session.withApi { it.categories() } }
        val second = async { fixture.session.withApi { it.categories() } }
        assertTrue(first.await().isEmpty())
        assertTrue(second.await().isEmpty())
        assertEquals(1, logins.get())
        assertEquals(5, server.requestCount)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun logoutDuringARequestCannotAutomaticallySignBackIn() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return unauthorizedResponse()
            }
        }
        val fixture = fixture(backgroundScope)
        val request = async { runCatching { fixture.session.withApi { it.categories() } } }
        runCurrent()
        assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        try {
            fixture.session.logout()
        } finally {
            release.countDown()
        }
        assertIs<UnauthorizedException>(request.await().exceptionOrNull())
        assertEquals(SessionState.SignedOut, fixture.session.state.value)
        assertNull(fixture.store.credentials)
        assertNull(fixture.session.token)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun changedPasswordUpdatesOnlyAlreadyRememberedCredentials() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.updateStoredPassword("changed-password")
        assertEquals("changed-password", fixture.store.credentials?.password)
        fixture.session.logout()
        fixture.session.updateStoredPassword("not-remembered")
        assertNull(fixture.store.credentials)
        assertNull(fixture.preferences.settings.value.authToken)
    }

    @Test
    fun credentialsDiagnosticsDoNotExposeThePassword() {
        assertFalse(Credentials(EMAIL, PASSWORD).toString().contains(PASSWORD))
    }

    private data class Fixture(
        val session: SessionManager,
        val preferences: MemorySessionPreferences,
        val store: MemoryCredentialStore,
    )

    private fun fixture(
        scope: CoroutineScope,
        initial: AppSettings = savedSettings(),
        saved: Credentials? = Credentials(EMAIL, PASSWORD),
    ): Fixture {
        val preferences = MemorySessionPreferences(initial)
        val store = MemoryCredentialStore(saved)
        lateinit var session: SessionManager
        val api = PicaApi(tokenProvider = { session.token }, baseUrl = server.url("/").toString())
        session = SessionManager(preferences, store, { api }, scope, now = { 1_000L })
        return Fixture(session, preferences, store)
    }

    private fun enqueue(data: String) { server.enqueue(success(data)) }
    private fun unauthorized(httpStatus: Boolean = false) {
        server.enqueue(if (httpStatus) MockResponse.Builder().code(401).body("expired").build() else unauthorizedResponse())
    }
    private fun takeRequest(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private companion object {
        const val EMAIL = "reader@example.com"
        const val PASSWORD = "test-secret-password"
        fun savedSettings(): AppSettings = AppSettings(accountEmail = EMAIL, authToken = "expired-token", autoPunchIn = false)
        fun success(data: String): MockResponse = MockResponse.Builder().body("""{"code":200,"data":$data}""").build()
        fun unauthorizedResponse(): MockResponse = MockResponse.Builder().body("""{"code":401,"message":"expired"}""").build()
    }
}
