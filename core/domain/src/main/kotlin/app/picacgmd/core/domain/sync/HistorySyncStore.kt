package app.picacgmd.core.domain.sync

import androidx.room.withTransaction
import app.picacgmd.core.data.db.PicaDatabase

interface HistorySyncStore {
    suspend fun snapshot(): HistorySyncDocument
    suspend fun apply(remote: HistorySyncDocument, direction: HistorySyncDirection, now: Long): HistorySyncDocument
    suspend fun pruneTombstones(before: Long)
}

/** Reads the latest local events inside the write transaction, not an earlier network-time snapshot. */
class RoomHistorySyncStore(private val database: PicaDatabase) : HistorySyncStore {
    private val dao = database.viewHistoryDao()
    override suspend fun snapshot(): HistorySyncDocument = HistorySyncDocument.fromSnapshot(dao.snapshot())

    override suspend fun apply(remote: HistorySyncDocument, direction: HistorySyncDirection, now: Long): HistorySyncDocument {
        HistorySyncFormat.validateIncomingEventTimes(remote, now)
        return database.withTransaction {
            val local = HistorySyncDocument.fromSnapshot(dao.snapshot())
            val merged = when (direction) {
                HistorySyncDirection.MERGE -> HistoryMerge.merge(local, remote)
                HistorySyncDirection.LOCAL_TO_REMOTE -> HistoryMerge.overwrite(local, remote, now)
                HistorySyncDirection.REMOTE_TO_LOCAL -> HistoryMerge.overwrite(remote, local, now)
            }
            dao.replaceSnapshot(merged.toSnapshot())
            merged
        }
    }

    override suspend fun pruneTombstones(before: Long) = dao.pruneTombstones(before)
}
