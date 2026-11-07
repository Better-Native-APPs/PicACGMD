package app.picacgmd.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiCacheDao {
    /** Includes expired entries for an explicitly labelled offline view; Flow itself is not a TTL timer. */
    @Query("SELECT * FROM api_cache WHERE `key` = :key")
    fun observe(key: String): Flow<ApiCache?>

    @Query("SELECT * FROM api_cache WHERE `key` = :key AND expiresAt > :now")
    suspend fun getFresh(key: String, now: Long): ApiCache?

    @Upsert
    suspend fun upsert(cache: ApiCache)

    @Query("DELETE FROM api_cache WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM api_cache WHERE substr(`key`, 1, length(:prefix)) = :prefix")
    suspend fun deletePrefix(prefix: String): Int

    @Query("DELETE FROM api_cache WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM api_cache")
    suspend fun clear()
}

@Dao
interface RemoteImageDao {
    @Query("SELECT * FROM remote_images ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RemoteImage>>

    @Query("SELECT * FROM remote_images WHERE fileServer = :fileServer AND path = :path")
    fun observe(fileServer: String, path: String): Flow<RemoteImage?>

    @Query("SELECT * FROM remote_images WHERE fileServer = :fileServer AND path = :path")
    suspend fun get(fileServer: String, path: String): RemoteImage?

    @Upsert
    suspend fun upsert(image: RemoteImage)

    @Query("DELETE FROM remote_images WHERE fileServer = :fileServer AND path = :path")
    suspend fun delete(fileServer: String, path: String): Int

    @Query("DELETE FROM remote_images WHERE updatedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM remote_images")
    suspend fun clear()
}
