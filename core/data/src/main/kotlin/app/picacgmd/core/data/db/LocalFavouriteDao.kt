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

    @Query("SELECT * FROM local_folder_entries ORDER BY addedAt DESC, comicId, folderId")
    abstract fun observeEntries(): Flow<List<LocalFavouriteEntry>>

    @Query("SELECT folderId FROM local_folder_entries WHERE comicId = :comicId ORDER BY folderId")
    abstract fun observeFolderIds(comicId: String): Flow<List<String>>

    @Query("SELECT * FROM local_folders WHERE folderId = :folderId")
    abstract suspend fun getFolder(folderId: String): LocalFavouriteFolder?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM local_folders")
    abstract suspend fun nextFolderOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFolder(folder: LocalFavouriteFolder): Long

    @Query("UPDATE local_folders SET name = :name WHERE folderId = :folderId")
    abstract suspend fun renameFolder(folderId: String, name: String): Int

    @Query("UPDATE local_folders SET lastSelectedAt = :timestamp WHERE folderId IN (:folderIds)")
    abstract suspend fun markSelected(folderIds: List<String>, timestamp: Long)

    @Query("SELECT * FROM local_folder_entries WHERE comicId = :comicId")
    abstract suspend fun getComicEntries(comicId: String): List<LocalFavouriteEntry>

    @Query("SELECT * FROM local_folder_entries WHERE folderId = :folderId")
    abstract suspend fun getFolderEntries(folderId: String): List<LocalFavouriteEntry>

    @Upsert
    abstract suspend fun upsertEntries(entries: List<LocalFavouriteEntry>)

    @Query("DELETE FROM local_folder_entries WHERE comicId = :comicId")
    abstract suspend fun deleteComicEntries(comicId: String)

    @Query("DELETE FROM local_folder_entries WHERE comicId IN (:comicIds) AND (:folderId IS NULL OR folderId = :folderId)")
    abstract suspend fun removeEntries(comicIds: List<String>, folderId: String?): Int

    @Query("DELETE FROM local_folders WHERE folderId = :folderId")
    abstract suspend fun deleteFolderRow(folderId: String): Int

    /** Validate the destination before changing memberships; preserve each folder's original added time. */
    @Transaction
    open suspend fun setFolders(snapshot: LocalFavouriteEntry, folderIds: List<String>) {
        require(folderIds.all { getFolder(it) != null }) { "收藏夹已不存在，请重新选择" }
        val previous = getComicEntries(snapshot.comicId).associateBy { it.folderId }
        deleteComicEntries(snapshot.comicId)
        upsertEntries(folderIds.map { id ->
            snapshot.copy(folderId = id, addedAt = previous[id]?.addedAt ?: snapshot.addedAt)
        })
        markSelected(folderIds, snapshot.addedAt)
    }

    /** Deleting a folder moves its contents instead of silently deleting local favourites. */
    @Transaction
    open suspend fun moveAndDeleteFolder(folderId: String, targetFolderId: String) {
        require(folderId != LocalFavouriteFolder.DEFAULT_ID) { "默认收藏夹不能删除" }
        require(folderId != targetFolderId) { "请选择其他收藏夹" }
        require(getFolder(folderId) != null && getFolder(targetFolderId) != null) { "收藏夹已不存在，请重新选择" }
        val existing = getFolderEntries(targetFolderId).associateBy { it.comicId }
        upsertEntries(getFolderEntries(folderId).map { entry ->
            entry.copy(folderId = targetFolderId, addedAt = existing[entry.comicId]?.addedAt ?: entry.addedAt)
        })
        deleteFolderRow(folderId)
    }
}
