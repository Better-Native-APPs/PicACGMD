package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.ComicInfo
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

    @Test
    fun savedProgressCarriesTheChapterPageCount() = runTest {
        repository.saveProgress(ComicInfo(id = "c", title = "T"), epOrder = 2, epTitle = "第二话",
            pictureIndex = 5, chapterPages = 24)

        assertEquals(ReadingProgress(2, "第二话", 5, 24), repository.getProgress("c"))
    }

    @Test
    fun progressWithoutAStoredPageCountReportsZero() = runTest {
        // Records written before the page-count field existed must not fake a fraction.
        dao.upsert(ViewHistory("old", "Old", lastEpOrder = 1, lastEpTitle = "一", lastPictureIndex = 3))

        assertEquals(ReadingProgress(1, "一", 3, 0), repository.getProgress("old"))
    }

    @Test
    fun restorePreservesSnapshotFieldsAndAdvancesEventTimesEvenWhenHistoryIsDisabled() = runTest {
        val first = ViewHistory("b", "Original", "Author", "server", "cover", "[\"Category\"]",
            lastEpOrder = 8, lastEpTitle = "Chapter eight", lastPictureIndex = 12, lastChapterPages = 30,
            pinned = true, createdAt = 123L, updatedAt = 456L)
        val items = listOf(first, first.copy(comicId = "a", pinned = false, createdAt = 111L),
            first.copy(lastPictureIndex = 13, updatedAt = 457L))
        val repository = HistoryRepository(dao, flowOf(AppSettings(historyEnabled = false)),
            now = { 9_000L })

        repository.restore(items)

        val expected = listOf(items[0].copy(modifiedAt = 9_000L), items[1].copy(modifiedAt = 9_000L), items[2].copy(modifiedAt = 9_001L))
        assertEquals(expected, dao.upserts)
        assertEquals(expected.associateBy(ViewHistory::comicId), dao.entries.value)
    }

    @Test
    fun restoringAnEmptyHistorySnapshotMakesNoWrites() = runTest {
        repository.restore(emptyList())
        assertTrue(dao.upserts.isEmpty())
    }

    @Test
    fun deleteClearAndUndoKeepMonotonicDeletionEvents() = runTest {
        val repository = HistoryRepository(dao, flowOf(AppSettings()), now = { 10 })
        val first = ViewHistory("a", "A", createdAt = 1, updatedAt = 20, pinned = true)
        dao.upsert(first)
        dao.upsert(ViewHistory("b", "B", createdAt = 1, updatedAt = 2))
        repository.delete(listOf("a"))
        assertEquals(21L, dao.getTombstone("a")!!.deletedAt)
        repository.restore(listOf(first))
        assertEquals(first.copy(modifiedAt = 22), dao.get("a"))
        assertEquals(null, dao.getTombstone("a"))
        repository.clear()
        assertTrue(dao.entries.value.isEmpty())
        assertEquals(23L, dao.getTombstone("a")!!.deletedAt)
        assertEquals(10L, dao.getTombstone("b")!!.deletedAt)
    }

    @Test
    fun pinningAndReadingAdvancePastTheLatestStoredEvent() = runTest {
        val repository = HistoryRepository(dao, flowOf(AppSettings()), now = { 10 })
        dao.upsert(ViewHistory("a", "A", createdAt = 1, updatedAt = 20))
        repository.setPinned("a", true)
        assertEquals(21L, dao.get("a")!!.modifiedAt)
        assertEquals(20L, dao.get("a")!!.updatedAt)
        repository.recordDetailView(ComicInfo(id = "a", title = "New title"))
        assertEquals(22L, dao.get("a")!!.modifiedAt)
        assertEquals(10L, dao.get("a")!!.updatedAt)
        assertTrue(dao.get("a")!!.pinned)
        repository.saveProgress(ComicInfo(id = "a", title = "New title"), 1, "One", 2, 3)
        assertEquals(23L, dao.get("a")!!.modifiedAt)
        assertEquals(10L, dao.get("a")!!.updatedAt)
    }
}
