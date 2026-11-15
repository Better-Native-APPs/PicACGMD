package app.picacgmd.testing

import app.picacgmd.core.domain.repository.AnnouncementRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class TestSessionTest {
    private val pica = TestPicaServer()

    @AfterTest
    fun tearDown() = pica.close()

    @Test
    fun repositoriesBuiltOnTheTestSessionReachTheLocalServer() = runTest {
        pica.enqueue("""{"announcements":{"docs":[{"_id":"a1","title":"t"}],"page":2,"pages":3,"total":21,"limit":10}}""")
        val page = AnnouncementRepository(pica.session(backgroundScope)).page(2)
        assertEquals("a1", page.docs.single().id)
        assertEquals(3, page.pages)
        val request = pica.takeRequest()
        assertEquals("GET /announcements?page=2", request.line())
        assertEquals("token", request.headers["authorization"])
        assertNull(pica.pollRequest(100))
        assertEquals(1, pica.requestCount)
    }
}
