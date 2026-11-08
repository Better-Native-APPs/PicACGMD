package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.session.Credentials
import app.picacgmd.core.domain.session.MemoryCredentialStore
import app.picacgmd.core.domain.session.MemorySessionPreferences
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.PicaApi
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class RemoteFeatureRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var preferences: MemorySessionPreferences

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        preferences = MemorySessionPreferences(AppSettings(accountEmail = "reader@example.com", authToken = "old", autoPunchIn = false))
    }

    @AfterTest
    fun tearDown() { server.close() }

    @Test
    fun announcementPageRelogsInAfter401AndKeepsPagingMetadata() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":401}""").build())
        enqueue("""{"token":"fresh"}""")
        enqueue("""{"announcements":{"docs":[{"_id":"notice","title":"公告"}],"page":2,"pages":3,"total":21,"limit":10}}""")
        val repository = AnnouncementRepository(session(backgroundScope))
        val page = repository.page(2)
        assertEquals("notice", page.docs.single().id)
        assertEquals(2, page.page)
        assertEquals(3, page.pages)
        assertEquals(21, page.total)
        assertEquals(10, page.limit)
        val first = takeRequest()
        assertEquals("/announcements", first.url.encodedPath)
        assertEquals("2", first.url.queryParameter("page"))
        assertEquals("old", first.headers["authorization"])
        assertEquals("/auth/sign-in", takeRequest().url.encodedPath)
        assertEquals("fresh", takeRequest().headers["authorization"])
        assertEquals("fresh", preferences.settings.value.authToken)
    }

    @Test
    fun gameLikesAndCommentReportsUseTheSharedSession() = runTest {
        val session = session(backgroundScope)
        val games = GameRepository(session)
        val comments = CommentRepository(session)
        enqueue("""{"action":"like"}""")
        enqueue("""{"action":"unlike"}""")
        enqueue("{}")
        assertTrue(games.switchLike("game"))
        assertFalse(games.switchLike("game"))
        assertEquals(Unit, comments.report("comment"))
        listOf("/games/game/like", "/games/game/like", "/comments/comment/report").forEach { path ->
            val request = takeRequest()
            assertEquals(path, request.url.encodedPath)
            assertEquals("POST", request.method)
            assertEquals("old", request.headers["authorization"])
        }
    }

    private fun session(scope: CoroutineScope): SessionManager {
        lateinit var session: SessionManager
        val api = PicaApi(tokenProvider = { session.token }, baseUrl = server.url("/").toString())
        session = SessionManager(
            preferences, MemoryCredentialStore(Credentials("reader@example.com", "saved-password")), { api }, scope,
        )
        return session
    }

    private fun enqueue(data: String) {
        server.enqueue(MockResponse.Builder().body("""{"code":200,"data":$data}""").build())
    }

    private fun takeRequest(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
}
