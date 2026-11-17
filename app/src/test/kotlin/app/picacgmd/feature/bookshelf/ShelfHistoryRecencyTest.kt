package app.picacgmd.feature.bookshelf

import androidx.room.Room
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.domain.sync.HistoryMerge
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncFormat
import app.picacgmd.core.domain.sync.RoomHistorySyncStore
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ShelfHistoryRecencyTest {
    private lateinit var database: PicaDatabase
    private val dao get() = database.viewHistoryDao()
    private val today = LocalDate.of(2026, 11, 15)
    private val originalOrder = listOf("latest", "old-b", "old-a")
    private val rows = listOf(
        history("old-a", -2), history("old-b", -1), history("latest", 0),
    )
    private val actionTime = viewedAt(0) + 1_000

    @BeforeTest fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PicaDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @AfterTest fun closeDatabase() { database.close() }

    @Test fun pinUnpinAndUndoKeepLegacyOrderDateBucketsAndContinueReadingWhileWinningOnThePeer() = runBlocking {
        dao.upsertAll(rows)
        val store = RoomHistorySyncStore(database)
        val peer = store.snapshot()
        assertViewingState(originalOrder)

        dao.setPinned("old-a", true, actionTime)
        val pinned = store.snapshot()
        assertViewingState(listOf("old-a", "latest", "old-b"))
        assertTrue(pinned.records.single { it.comicId == "old-a" }.modifiedAt >
            peer.records.single { it.comicId == "old-a" }.modifiedAt)
        assertEquals(pinned, HistoryMerge.merge(peer, pinned))

        dao.setPinned("old-a", false, actionTime)
        val unpinned = store.snapshot()
        assertViewingState(originalOrder)
        assertTrue(unpinned.records.single { it.comicId == "old-a" }.modifiedAt >
            pinned.records.single { it.comicId == "old-a" }.modifiedAt)
        assertEquals(unpinned, HistoryMerge.merge(pinned, unpinned))

        val saved = dao.get("old-a")!!
        dao.delete(listOf("old-a"), actionTime)
        val deletionPeer = store.snapshot()
        dao.restore(listOf(saved), actionTime)
        val restored = store.snapshot()
        assertViewingState(originalOrder)
        assertTrue(restored.records.single { it.comicId == "old-a" }.modifiedAt > deletionPeer.tombstones.single().deletedAt)
        assertEquals(restored, HistoryMerge.merge(deletionPeer, restored))
        assertEquals(restored, HistorySyncFormat.decode(HistorySyncFormat.encode(restored)))
    }

    @Test fun overwritePromotionKeepsTheSelectedViewingTimeAndContinueReadingChoiceAfterPeerRoundTrip() = runBlocking {
        dao.upsertAll(rows)
        val store = RoomHistorySyncStore(database)
        val local = store.snapshot()
        val peer = local.copy(records = local.records.map {
            if (it.comicId == "old-a") it.copy(updatedAt = actionTime, modifiedAt = actionTime + 100, pinned = true) else it
        })
        val forced = store.apply(peer, HistorySyncDirection.LOCAL_TO_REMOTE, actionTime)
        assertViewingState(originalOrder)
        assertTrue(forced.records.single { it.comicId == "old-a" }.modifiedAt > actionTime + 100)
        val roundTrip = HistorySyncFormat.decode(HistorySyncFormat.encode(forced))
        assertEquals(forced, HistoryMerge.merge(peer, roundTrip))
        assertEquals(forced, store.snapshot())
    }

    private suspend fun assertViewingState(order: List<String>) {
        val history = dao.observeAll().first()
        assertEquals(order, history.map { it.comicId })
        assertEquals(order, dao.observePage(100, 0).first().map { it.comicId })
        assertEquals(rows.associate { it.comicId to it.updatedAt }, history.associate { it.comicId to it.updatedAt })
        assertEquals(mapOf("old-a" to 2, "old-b" to 1, "latest" to 0), history.associate { it.comicId to dayBucket(it.updatedAt, today) })
        assertEquals("latest", history.maxByOrNull { it.updatedAt }?.comicId)
    }

    private fun viewedAt(day: Long): Long = today.plusDays(day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun history(id: String, day: Long) = ViewHistory(id, "Title $id", lastEpOrder = 1,
        lastPictureIndex = 3, lastChapterPages = 20, createdAt = viewedAt(day), updatedAt = viewedAt(day))
}
