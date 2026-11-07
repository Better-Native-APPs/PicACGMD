package app.picacgmd.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY updatedAt DESC, query")
    fun observeAll(): Flow<List<SearchHistory>>

    @Upsert
    suspend fun upsert(history: SearchHistory)

    @Query("DELETE FROM search_history WHERE query IN (:queries)")
    suspend fun delete(queries: List<String>): Int

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface TagFavouriteDao {
    @Query("SELECT * FROM tag_favourites ORDER BY sortOrder, createdAt, tag")
    fun observeAll(): Flow<List<TagFavourite>>

    @Upsert
    suspend fun upsert(favourite: TagFavourite)

    @Query("DELETE FROM tag_favourites WHERE tag IN (:tags)")
    suspend fun delete(tags: List<String>): Int

    @Query("DELETE FROM tag_favourites")
    suspend fun clear()
}

@Dao
abstract class SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY unseen DESC, updatedAt DESC, comicId")
    abstract fun observeAll(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscriptions WHERE comicId = :comicId")
    abstract fun observe(comicId: String): Flow<Subscription?>

    @Query("SELECT COUNT(*) FROM subscriptions WHERE unseen = 1")
    abstract fun observeUnseenCount(): Flow<Int>

    @Query("SELECT * FROM subscriptions WHERE comicId = :comicId")
    abstract suspend fun get(comicId: String): Subscription?

    @Upsert
    abstract suspend fun upsert(subscription: Subscription)

    /** Applies an already fetched update; this DAO performs no network access or background scheduling. */
    @Transaction
    open suspend fun applyUpdate(comicId: String, epsCount: Int, comicUpdatedAt: Long?, checkedAt: Long): Boolean {
        val current = get(comicId) ?: return false
        upsert(current.withLatest(epsCount, comicUpdatedAt, checkedAt))
        return true
    }

    @Query("UPDATE subscriptions SET unseen = 0, newEpCount = 0 WHERE comicId = :comicId")
    abstract suspend fun markSeen(comicId: String): Int

    @Query("DELETE FROM subscriptions WHERE comicId IN (:comicIds)")
    abstract suspend fun delete(comicIds: List<String>): Int

    @Query("DELETE FROM subscriptions")
    abstract suspend fun clear()
}
