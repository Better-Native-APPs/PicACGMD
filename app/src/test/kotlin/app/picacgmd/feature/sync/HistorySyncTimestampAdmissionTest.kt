package app.picacgmd.feature.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import app.picacgmd.core.data.db.HistoryMutationTime
import app.picacgmd.core.data.db.HistoryTombstone
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncDocument
import app.picacgmd.core.domain.sync.HistorySyncFormat
import app.picacgmd.core.domain.sync.HistorySyncRecord
import app.picacgmd.core.domain.sync.HistorySyncTombstone
import app.picacgmd.core.domain.sync.RoomHistorySyncStore
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HistorySyncTimestampAdmissionTest {
    private lateinit var database: PicaDatabase
    private lateinit var peerDatabase: PicaDatabase
    private val dao get() = database.viewHistoryDao()
    private val store get() = RoomHistorySyncStore(database)
    private val peer get() = RoomHistorySyncStore(peerDatabase)
    private val boundary = NOW + HistorySyncFormat.INCOMING_HORIZON_MILLIS

    @BeforeTest fun createDatabases() {
        database = openDatabase(LOCAL_DATABASE)
        peerDatabase = openDatabase(PEER_DATABASE)
    }

    @AfterTest fun closeDatabases() {
        database.close()
        peerDatabase.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(LOCAL_DATABASE)
        context.deleteDatabase(PEER_DATABASE)
    }

    @Test fun boundaryRecordImportDeleteUndoEditAndOverwriteSurviveAPeerRoundTrip() = runBlocking {
        val incoming = HistorySyncDocument(1, listOf(
            HistorySyncRecord("a", "Title", createdAt = 1, updatedAt = boundary, modifiedAt = boundary),
        ), emptyList())
        assertEquals(incoming, store.apply(roundTrip(incoming), HistorySyncDirection.MERGE, NOW))
        val removed = dao.get("a")!!
        assertEquals(1, dao.delete(listOf("a"), NOW))
        assertEquals(boundary + 1, dao.getTombstone("a")!!.deletedAt)
        peer.apply(roundTrip(store.snapshot()), HistorySyncDirection.MERGE, NOW + 1_000)

        dao.restore(listOf(removed), NOW)
        assertEquals(boundary + 2, dao.get("a")!!.modifiedAt)
        assertEquals(boundary, dao.get("a")!!.updatedAt)
        assertEquals(1, dao.setPinned("a", true, NOW))
        assertEquals(1, dao.updateProgress("a", 4, "Four", 8, 20, NOW))
        assertEquals(boundary + 4, dao.get("a")!!.modifiedAt)
        val exported = store.apply(peer.snapshot(), HistorySyncDirection.LOCAL_TO_REMOTE, NOW + 1_000)
        assertEquals(boundary + 5, exported.records.single().modifiedAt)
        assertTrue(exported.records.single().pinned)
        assertEquals(4, exported.records.single().lastEpOrder)
        assertEquals(8, exported.records.single().lastPictureIndex)
        assertEquals(NOW, exported.records.single().updatedAt)
        assertNull(dao.getTombstone("a"))
        assertEquals(exported, peer.apply(roundTrip(exported), HistorySyncDirection.MERGE, NOW + 2_000))
        assertEquals(exported, store.apply(roundTrip(peer.snapshot()), HistorySyncDirection.MERGE, NOW + 2_000))
    }

    @Test fun boundaryTombstoneImportRestoreAndFurtherEditsSurviveAPeerRoundTrip() = runBlocking {
        val incoming = HistorySyncDocument(1, emptyList(), listOf(HistorySyncTombstone("a", boundary)))
        store.apply(roundTrip(incoming), HistorySyncDirection.MERGE, NOW)
        peer.apply(roundTrip(incoming), HistorySyncDirection.MERGE, NOW)
        val chosen = record("a", NOW - 10)
        dao.restore(listOf(chosen), NOW)
        assertEquals(chosen.copy(modifiedAt = boundary + 1), dao.get("a"))
        assertNull(dao.getTombstone("a"))
        assertEquals(1, dao.setPinned("a", true, NOW))
        assertEquals(1, dao.setPinned("a", false, NOW))
        assertEquals(1, dao.updateProgress("a", 2, "Two", 3, 12, NOW))
        val exported = store.snapshot()
        assertEquals(boundary + 4, exported.records.single().modifiedAt)
        assertEquals(NOW, exported.records.single().updatedAt)
        assertEquals(3, exported.records.single().lastPictureIndex)
        assertEquals(exported, peer.apply(roundTrip(exported), HistorySyncDirection.MERGE, NOW + 1_000))
        assertEquals(exported, store.apply(roundTrip(peer.snapshot()), HistorySyncDirection.MERGE, NOW + 1_000))
    }

    @Test fun oneUnitPastAdmissionBoundaryRejectsTheWholeFileWithoutChangingRoomBytes() = runBlocking {
        dao.upsert(record("local", NOW))
        dao.upsertTombstones(listOf(HistoryTombstone("deleted", NOW)))
        val before = store.snapshot()
        val beforeBytes = databaseBytes()
        assertTrue(beforeBytes.isNotEmpty())
        val valid = HistorySyncRecord("valid", "Valid", createdAt = 1, updatedAt = NOW)
        val invalid = valid.copy(comicId = "invalid")
        val timestamp = boundary + 1
        val incoming = listOf(
            HistorySyncDocument(1, listOf(valid, invalid.copy(createdAt = timestamp)), emptyList()),
            HistorySyncDocument(1, listOf(valid, invalid.copy(updatedAt = timestamp)), emptyList()),
            HistorySyncDocument(1, listOf(valid, invalid.copy(modifiedAt = timestamp)), emptyList()),
            HistorySyncDocument(1, listOf(valid), listOf(HistorySyncTombstone("invalid", timestamp))),
        )
        for (direction in HistorySyncDirection.entries) for (document in incoming) {
            val failure = assertFailsWith<WebDavException> { store.apply(roundTrip(document), direction, NOW) }
            assertEquals(WebDavError.INVALID_TIMESTAMP, failure.error)
            assertEquals(before, store.snapshot())
            assertContentEquals(beforeBytes, databaseBytes())
        }
    }

    @Test fun theReviewersCeilingMinusOneRecordIsRejectedBeforeItCanCreateATerminalDeletion() = runBlocking {
        dao.upsert(record("a", NOW))
        dao.upsertTombstones(listOf(HistoryTombstone("deleted", NOW)))
        val before = store.snapshot()
        val beforeBytes = databaseBytes()
        val incoming = HistorySyncDocument(1, listOf(HistorySyncRecord("a", "Title", createdAt = 1,
            updatedAt = NOW, modifiedAt = HistoryMutationTime.MAX_EPOCH_MILLIS - 1)), emptyList())
        for (direction in HistorySyncDirection.entries) {
            assertEquals(WebDavError.INVALID_TIMESTAMP, assertFailsWith<WebDavException> {
                store.apply(roundTrip(incoming), direction, NOW)
            }.error)
            assertEquals(before, store.snapshot())
            assertContentEquals(beforeBytes, databaseBytes())
        }
    }

    @Test fun clockRollbackKeepsLocalSuccessorsEditableAndPeerRejectionEndsWhenItsClockCatchesUp() = runBlocking {
        val incoming = HistorySyncDocument(1, listOf(HistorySyncRecord("a", "Title", createdAt = 1,
            updatedAt = NOW, modifiedAt = boundary)), emptyList())
        store.apply(roundTrip(incoming), HistorySyncDirection.MERGE, NOW)
        val removed = dao.get("a")!!
        val correctClock = NOW - 60_000
        dao.delete(listOf("a"), correctClock)
        dao.restore(listOf(removed), correctClock)
        dao.setPinned("a", true, correctClock)
        dao.updateProgress("a", 2, "Two", 3, 12, correctClock)
        val exported = store.snapshot()
        assertEquals(boundary + 4, exported.records.single().modifiedAt)
        assertEquals(exported, store.apply(HistorySyncDocument.EMPTY, HistorySyncDirection.MERGE, correctClock))
        val peerBefore = peer.snapshot()
        assertEquals(WebDavError.INVALID_TIMESTAMP, assertFailsWith<WebDavException> {
            peer.apply(roundTrip(exported), HistorySyncDirection.MERGE, correctClock)
        }.error)
        assertEquals(peerBefore, peer.snapshot())
        // The 60-second skew plus four 1-ms actions, not the ten-year horizon, bounds this rejection.
        assertEquals(exported, peer.apply(roundTrip(exported), HistorySyncDirection.MERGE, NOW + 4))
    }

    private fun openDatabase(name: String): PicaDatabase =
        Room.databaseBuilder(RuntimeEnvironment.getApplication(), PicaDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()

    private fun databaseBytes(): ByteArray = RuntimeEnvironment.getApplication().getDatabasePath(LOCAL_DATABASE).readBytes()
    private fun roundTrip(document: HistorySyncDocument): HistorySyncDocument = HistorySyncFormat.decode(HistorySyncFormat.encode(document))
    private fun record(id: String, updatedAt: Long) = ViewHistory(id, "Title $id", createdAt = 1, updatedAt = updatedAt)

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val LOCAL_DATABASE = "history-sync-admission-local.db"
        private const val PEER_DATABASE = "history-sync-admission-peer.db"
    }
}
