package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class HistoryRepositoryTest {
    private val dao = FakeViewHistoryDao()
    private val repository = HistoryRepository(dao, flowOf(AppSettings()))

    @Test
    fun readChaptersCoverOnlyTheRequestedComicsThatHaveHistory() = runTest {
        dao.upsert(ViewHistory("read", "Read", lastEpOrder = 3, lastPictureIndex = 7))
        dao.upsert(ViewHistory("opened", "Opened"))
        dao.upsert(ViewHistory("elsewhere", "Not on screen", lastEpOrder = 9, lastPictureIndex = 1))

        val marks = repository.observeReadChapters(listOf("read", "opened", "unread", "read")).first()

        assertEquals(mapOf("read" to 3, "opened" to 0), marks)
    }

    @Test
    fun readChaptersFollowNewProgressWithoutAReload() = runTest {
        val emissions = mutableListOf<Map<String, Int>>()
        val job = launch { repository.observeReadChapters(listOf("a", "b")).take(3).toList(emissions) }
        testScheduler.runCurrent()
        dao.upsert(ViewHistory("a", "A"))
        testScheduler.runCurrent()
        // A write to a comic that is not on screen must not produce a duplicate emission.
        dao.upsert(ViewHistory("z", "Z", lastEpOrder = 4, lastPictureIndex = 1))
        testScheduler.runCurrent()
        dao.upsert(ViewHistory("a", "A", lastEpOrder = 2, lastPictureIndex = 5))
        job.join()

        assertEquals(listOf(emptyMap(), mapOf("a" to 0), mapOf("a" to 2)), emissions)
    }

    @Test
    fun longListsAreQueriedInChunksBelowTheSqliteVariableLimit() = runTest {
        val ids = List(2_345) { "comic-$it" }
        dao.upsert(ViewHistory("comic-0", "First", lastEpOrder = 1, lastPictureIndex = 1))
        dao.upsert(ViewHistory("comic-2344", "Last", lastEpOrder = 12, lastPictureIndex = 3))

        val marks = repository.observeReadChapters(ids).first()

        assertEquals(mapOf("comic-0" to 1, "comic-2344" to 12), marks)
        assertTrue(dao.largestReadChapterQuery in 1..999, "largest query had ${dao.largestReadChapterQuery} ids")
    }

    @Test
    fun noIdsMeansNoQuery() = runTest {
        assertEquals(emptyMap(), repository.observeReadChapters(emptyList()).first())
        assertEquals(0, dao.largestReadChapterQuery)
    }
}
