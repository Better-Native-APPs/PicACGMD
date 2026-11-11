package app.picacgmd.core.data.db

import androidx.room.Dao
import androidx.room.Query
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

    @Upsert
    suspend fun upsert(history: ViewHistory)

    @Query("""
        UPDATE view_history SET lastEpOrder = :epOrder, lastEpTitle = :epTitle,
            lastPictureIndex = :pictureIndex, lastChapterPages = :pageCount, updatedAt = :updatedAt
        WHERE comicId = :comicId
    """)
    suspend fun updateProgress(
        comicId: String,
        epOrder: Int,
        epTitle: String,
        pictureIndex: Int,
        pageCount: Int,
        updatedAt: Long,
    ): Int

    @Query("UPDATE view_history SET pinned = :pinned WHERE comicId = :comicId")
    suspend fun setPinned(comicId: String, pinned: Boolean): Int

    @Query("DELETE FROM view_history WHERE comicId IN (:comicIds)")
    suspend fun delete(comicIds: List<String>): Int

    @Query("DELETE FROM view_history")
    suspend fun clear()
}
