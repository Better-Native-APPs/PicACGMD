package app.picacgmd.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LocalFavouriteDao {
    @Query("SELECT * FROM local_folders ORDER BY sortOrder, createdAt, folderId")
    abstract fun observeFolders(): Flow<List<LocalFavouriteFolder>>

    @Query("SELECT * FROM local_folder_entries ORDER BY addedAt DESC, comicId")
    abstract fun observeEntries(): Flow<List<LocalFavouriteEntry>>

    @Query("SELECT folderId FROM local_folder_entries WHERE comicId = :comicId")
    abstract fun observeFolderId(comicId: String): Flow<String?>

    @Query("SELECT * FROM local_folders WHERE folderId = :folderId")
    abstract suspend fun getFolder(folderId: String): LocalFavouriteFolder?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM local_folders")
    abstract suspend fun nextFolderOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFolder(folder: LocalFavouriteFolder): Long

    @Query("UPDATE local_folders SET name = :name WHERE folderId = :folderId")
    abstract suspend fun renameFolder(folderId: String, name: String): Int

    @Query("UPDATE local_folders SET lastSelectedAt = :timestamp WHERE folderId = :folderId")
    abstract suspend fun markSelected(folderId: String, timestamp: Long)

    @Query("SELECT * FROM local_folder_entries WHERE comicId = :comicId")
    abstract suspend fun getComicEntry(comicId: String): LocalFavouriteEntry?

    @Query("SELECT * FROM local_folder_entries WHERE folderId = :folderId")
    abstract suspend fun getFolderEntries(folderId: String): List<LocalFavouriteEntry>

    @Upsert
    abstract suspend fun upsertEntries(entries: List<LocalFavouriteEntry>)

    @Query("DELETE FROM local_folder_entries WHERE comicId = :comicId")
    abstract suspend fun deleteComicEntry(comicId: String)

    @Query("DELETE FROM local_folder_entries WHERE comicId IN (:comicIds) AND (:folderId IS NULL OR folderId = :folderId)")
    abstract suspend fun removeEntries(comicIds: List<String>, folderId: String?): Int

    @Query("DELETE FROM local_folders WHERE folderId = :folderId")
    abstract suspend fun deleteFolderRow(folderId: String): Int

    /** Replace the comic's only membership, preserving its original local-favourite added time. */
    @Transaction
    open suspend fun setFolder(snapshot: LocalFavouriteEntry, folderId: String?) {
        if (folderId == null) {
            deleteComicEntry(snapshot.comicId)
            return
        }
        require(getFolder(folderId) != null) { "收藏夹已不存在，请重新选择" }
        val previous = getComicEntry(snapshot.comicId)
        upsertEntries(listOf(snapshot.copy(folderId = folderId, addedAt = previous?.addedAt ?: snapshot.addedAt)))
        markSelected(folderId, snapshot.addedAt)
    }

    /** Deleting a folder moves its contents instead of silently deleting local favourites. */
    @Transaction
    open suspend fun moveAndDeleteFolder(folderId: String, targetFolderId: String) {
        require(folderId != LocalFavouriteFolder.DEFAULT_ID) { "默认收藏夹不能删除" }
        require(folderId != targetFolderId) { "请选择其他收藏夹" }
        require(getFolder(folderId) != null && getFolder(targetFolderId) != null) { "收藏夹已不存在，请重新选择" }
        upsertEntries(getFolderEntries(folderId).map { it.copy(folderId = targetFolderId) })
        deleteFolderRow(folderId)
    }
}
