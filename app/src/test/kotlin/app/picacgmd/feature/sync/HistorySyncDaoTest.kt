package app.picacgmd.feature.sync

import androidx.room.Room
import androidx.room.withTransaction
import app.picacgmd.core.data.db.HistoryTombstone
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncDocument
import app.picacgmd.core.domain.sync.HistorySyncRecord
import app.picacgmd.core.domain.sync.RoomHistorySyncStore
import app.picacgmd.core.domain.sync.HistoryMerge
import app.picacgmd.core.domain.sync.HistorySyncTombstone
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HistorySyncDaoTest {
    private lateinit var database: PicaDatabase
    private val dao get() = database.viewHistoryDao()

    @BeforeTest fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PicaDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @AfterTest fun closeDatabase() { database.close() }

    @Test fun deleteRecordsAndMissingSelectedIdsBothWriteTombstones() = runBlocking {
        dao.upsertAll(listOf(record("a", 2), record("b", 3)))
        assertEquals(1, dao.delete(listOf("a", "a", "missing"), deletedAt = 10))
        assertEquals(listOf(record("b", 3)), dao.getAll())
        assertEquals(listOf(HistoryTombstone("a", 10), HistoryTombstone("missing", 10)), dao.getTombstones())
    }

    @Test fun clearRemembersEveryRemovedRecordAndAdvancesPastFutureRecordTimes() = runBlocking {
        dao.upsertAll(listOf(record("a", 2), record("b", 30)))
        dao.upsertTombstones(listOf(HistoryTombstone("old", 5)))
        dao.clear(deletedAt = 20)
        assertTrue(dao.getAll().isEmpty())
        assertEquals(listOf(HistoryTombstone("a", 20), HistoryTombstone("b", 31), HistoryTombstone("old", 5)), dao.getTombstones())
    }

    @Test fun aDeleteFailureRollsBackItsTombstonesInTheSameDaoTransaction() = runBlocking {
        val original = record("a", 2)
        dao.upsert(original)
        rejectDeletes()
        assertFailsWith<Exception> { dao.delete(listOf("a"), 10) }
        assertEquals(original, dao.get("a"))
        assertNull(dao.getTombstone("a"))
    }

    @Test fun aClearFailureRollsBackEveryTombstoneInTheSameDaoTransaction() = runBlocking {
        val original = listOf(record("a", 2), record("b", 3))
        dao.upsertAll(original)
        rejectDeletes()
        assertFailsWith<Exception> { dao.clear(10) }
        assertEquals(original, dao.getAll())
        assertTrue(dao.getTombstones().isEmpty())
    }

    @Test fun pinProgressAndUndoAreNewEventsEvenWhenTheClockMovesBackwards() = runBlocking {
        dao.upsert(record("a", 100).copy(lastEpOrder = 3, lastPictureIndex = 7, pinned = true))
        dao.setPinned("a", false, 1)
        assertEquals(101L, dao.get("a")!!.modifiedAt)
        assertEquals(100L, dao.get("a")!!.updatedAt)
        dao.updateProgress("a", 4, "Four", 8, 20, 1)
        assertEquals(102L, dao.get("a")!!.modifiedAt)
        assertEquals(1L, dao.get("a")!!.updatedAt)
        val removed = dao.get("a")!!
        dao.delete(listOf("a"), 1)
        assertEquals(103L, dao.getTombstone("a")!!.deletedAt)
        dao.restore(listOf(removed), 1)
        assertEquals(removed.copy(modifiedAt = 104), dao.get("a"))
        assertNull(dao.getTombstone("a"))
    }

    @Test fun detailRecordingPreservesProgressAndPinnedAndARevisitBeatsItsDeletion() = runBlocking {
        dao.upsert(record("a", 100).copy(lastEpOrder = 3, lastPictureIndex = 7, lastChapterPages = 20, pinned = true))
        dao.record(record("a", 1).copy(title = "New title"), updateReadingProgress = false)
        val updated = dao.get("a")!!
        assertEquals("New title", updated.title)
        assertEquals(3, updated.lastEpOrder); assertEquals(7, updated.lastPictureIndex)
        assertEquals(20, updated.lastChapterPages); assertTrue(updated.pinned)
        assertEquals(101L, updated.modifiedAt)
        assertEquals(1L, updated.updatedAt)
        dao.delete(listOf("a"), 1)
        dao.record(record("a", 1), updateReadingProgress = false)
        assertEquals(103L, dao.get("a")!!.modifiedAt)
        assertEquals(1L, dao.get("a")!!.updatedAt)
        assertNull(dao.getTombstone("a"))
    }

    @Test fun theRoomTransactionUsedBySyncRollsBackBothTablesOnCancellation() = runBlocking {
        val original = record("a", 2)
        dao.upsert(original)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            database.withTransaction {
                dao.clearRows()
                dao.upsertTombstones(listOf(HistoryTombstone("a", 3)))
                entered.complete(Unit)
                gate.await()
            }
        }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(original, dao.get("a"))
        assertTrue(dao.getTombstones().isEmpty())
    }

    @Test fun syncStorePublishesBothTablesAndRollsBackAFailedReplacement() = runBlocking {
        val original = record("a", 2)
        dao.upsert(original)
        dao.upsertTombstones(listOf(HistoryTombstone("deleted", 3)))
        val store = RoomHistorySyncStore(database)
        val before = store.snapshot()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_history_insert BEFORE INSERT ON view_history BEGIN SELECT RAISE(ABORT, 'insert blocked'); END")
        val remote = HistorySyncDocument(1, listOf(HistorySyncRecord("remote", "Remote", createdAt = 1, updatedAt = 4)), emptyList())
        assertFailsWith<Exception> { store.apply(remote, HistorySyncDirection.MERGE, 10) }
        assertEquals(before, store.snapshot())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_history_insert")
        val merged = store.apply(remote, HistorySyncDirection.MERGE, 10)
        assertEquals(listOf("a", "remote"), merged.records.map { it.comicId })
        assertEquals(merged, store.snapshot())
        assertEquals(listOf(HistoryTombstone("deleted", 3)), dao.getTombstones())
    }

    @Test fun unsupportedPeerTimestampsAreRejectedBeforeAnyRoomReplacement() = runBlocking {
        val store = RoomHistorySyncStore(database)
        dao.upsert(record("a", 10))
        dao.upsertTombstones(listOf(HistoryTombstone("deleted", 20)))
        val original = store.snapshot()
        val peer = HistorySyncDocument(1, emptyList(), listOf(HistorySyncTombstone("a", Long.MAX_VALUE)))
        for (direction in HistorySyncDirection.entries) {
            assertEquals(WebDavError.DAMAGED_FILE, assertFailsWith<WebDavException> {
                store.apply(peer, direction, 30)
            }.error)
            assertEquals(original, store.snapshot())
        }
        dao.restore(listOf(record("a", 10)), 30)
        val restored = store.snapshot()
        assertEquals(WebDavError.DAMAGED_FILE, assertFailsWith<WebDavException> {
            HistoryMerge.merge(restored, peer)
        }.error)
        assertEquals(restored, store.snapshot())
    }

    private fun record(id: String, updatedAt: Long) = ViewHistory(id, "Title $id", createdAt = 1, updatedAt = updatedAt)
    private fun rejectDeletes() {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_history_delete BEFORE DELETE ON view_history BEGIN SELECT RAISE(ABORT, 'delete blocked'); END")
    }
}
