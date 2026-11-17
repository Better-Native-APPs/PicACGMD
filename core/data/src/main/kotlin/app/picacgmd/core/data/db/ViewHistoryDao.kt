package app.picacgmd.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A viewed comic and the last chapter read in it (one-based; 0 = only the details were opened). */
data class ReadChapter(val comicId: String, val lastEpOrder: Int)

@Dao
interface ViewHistoryDao {
    @Query("SELECT * FROM view_history ORDER BY pinned DESC, updatedAt DESC, comicId")
    fun observeAll(): Flow<List<ViewHistory>>

    @Query("SELECT * FROM view_history ORDER BY pinned DESC, updatedAt DESC, comicId LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int): Flow<List<ViewHistory>>

    @Query("SELECT * FROM view_history WHERE comicId = :comicId")
    fun observe(comicId: String): Flow<ViewHistory?>

    @Query("SELECT * FROM view_history WHERE comicId = :comicId")
    suspend fun get(comicId: String): ViewHistory?

    @Query("SELECT comicId FROM view_history WHERE comicId IN (:comicIds)")
    fun observeViewedIds(comicIds: List<String>): Flow<List<String>>

    /** Two narrow columns for the 「已读」 marks on comic lists; keep [comicIds] under 999 entries. */
    @Query("SELECT comicId, lastEpOrder FROM view_history WHERE comicId IN (:comicIds)")
    fun observeReadChapters(comicIds: List<String>): Flow<List<ReadChapter>>

    /** Raw snapshot primitive; local user actions use [record] or [restore]. */
    @Upsert
    suspend fun upsert(history: ViewHistory)

    @Upsert
    suspend fun upsertAll(histories: List<ViewHistory>)

    @Query("SELECT * FROM view_history ORDER BY comicId")
    suspend fun getAll(): List<ViewHistory>

    @Query("SELECT * FROM history_tombstones ORDER BY comicId")
    suspend fun getTombstones(): List<HistoryTombstone>

    @Query("SELECT * FROM history_tombstones WHERE comicId = :comicId")
    suspend fun getTombstone(comicId: String): HistoryTombstone?

    @Upsert
    suspend fun upsertTombstones(tombstones: List<HistoryTombstone>)

    @Query("DELETE FROM history_tombstones WHERE comicId = :comicId")
    suspend fun removeTombstone(comicId: String)

    @Query("DELETE FROM history_tombstones WHERE deletedAt < :before")
    suspend fun pruneTombstones(before: Long)

    @Query("DELETE FROM history_tombstones")
    suspend fun clearTombstones()

    @Transaction
    suspend fun snapshot(): HistorySnapshot = HistorySnapshot(getAll(), getTombstones())

    /** Reading/detail writes preserve the latest pinned flag and advance past any observed deletion. */
    @Transaction
    suspend fun record(history: ViewHistory, updateReadingProgress: Boolean) {
        val previous = get(history.comicId)
        val tombstone = getTombstone(history.comicId)
        val next = history.copy(
            createdAt = previous?.createdAt ?: history.createdAt,
            pinned = previous?.pinned ?: history.pinned,
            modifiedAt = HistoryMutationTime.next(history.modifiedAt, previous?.modifiedAt, tombstone?.deletedAt),
            lastEpOrder = if (updateReadingProgress) history.lastEpOrder else previous?.lastEpOrder ?: history.lastEpOrder,
            lastEpTitle = if (updateReadingProgress) history.lastEpTitle else previous?.lastEpTitle ?: history.lastEpTitle,
            lastPictureIndex = if (updateReadingProgress) history.lastPictureIndex else previous?.lastPictureIndex ?: history.lastPictureIndex,
            lastChapterPages = if (updateReadingProgress) history.lastChapterPages else previous?.lastChapterPages ?: history.lastChapterPages,
        )
        upsert(next)
        removeTombstone(history.comicId)
    }

    /** Undo advances the sync version past deletions, but preserves the snapshot's viewing time. */
    @Transaction
    suspend fun restore(histories: List<ViewHistory>, updatedAt: Long) {
        histories.forEach { history ->
            val previous = get(history.comicId)
            val tombstone = getTombstone(history.comicId)
            upsert(history.copy(modifiedAt = HistoryMutationTime.next(maxOf(updatedAt, history.modifiedAt), previous?.modifiedAt, tombstone?.deletedAt)))
            removeTombstone(history.comicId)
        }
    }

    /** Both tables are published together; cancellation rolls back the entire replacement. */
    @Transaction
    suspend fun replaceSnapshot(snapshot: HistorySnapshot) {
        clearRows()
        clearTombstones()
        upsertAll(snapshot.records)
        upsertTombstones(snapshot.tombstones)
    }

    @Transaction
    suspend fun updateProgress(
        comicId: String,
        epOrder: Int,
        epTitle: String,
        pictureIndex: Int,
        pageCount: Int,
        updatedAt: Long,
    ): Int {
        val previous = get(comicId) ?: return 0
        upsert(previous.copy(
            lastEpOrder = epOrder, lastEpTitle = epTitle, lastPictureIndex = pictureIndex,
            lastChapterPages = pageCount, updatedAt = updatedAt,
            modifiedAt = HistoryMutationTime.next(updatedAt, previous.modifiedAt),
        ))
        return 1
    }

    @Transaction
    suspend fun setPinned(comicId: String, pinned: Boolean, updatedAt: Long = System.currentTimeMillis()): Int {
        val previous = get(comicId) ?: return 0
        upsert(previous.copy(pinned = pinned, modifiedAt = HistoryMutationTime.next(updatedAt, previous.modifiedAt)))
        return 1
    }

    @Query("DELETE FROM view_history WHERE comicId IN (:comicIds)")
    suspend fun deleteRows(comicIds: List<String>): Int

    @Query("DELETE FROM view_history")
    suspend fun clearRows()

    @Transaction
    suspend fun delete(comicIds: List<String>, deletedAt: Long = System.currentTimeMillis()): Int {
        val ids = comicIds.distinct()
        val tombstones = ids.map { id ->
            HistoryTombstone(id, HistoryMutationTime.next(deletedAt, get(id)?.modifiedAt, getTombstone(id)?.deletedAt))
        }
        upsertTombstones(tombstones)
        return ids.chunked(500).sumOf { deleteRows(it) }
    }

    @Transaction
    suspend fun clear(deletedAt: Long = System.currentTimeMillis()) {
        val previousTombstones = getTombstones().associateBy(HistoryTombstone::comicId)
        upsertTombstones(getAll().map {
            HistoryTombstone(it.comicId, HistoryMutationTime.next(deletedAt, it.modifiedAt, previousTombstones[it.comicId]?.deletedAt))
        })
        clearRows()
    }
}
