package app.picacgmd.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LocalFavouriteDao {
    @Query("SELECT * FROM local_favourite_folders ORDER BY sortOrder, createdAt, folderId")
    abstract fun observeFolders(): Flow<List<LocalFavouriteFolder>>

    @Query("SELECT * FROM local_favourite_folders ORDER BY sortOrder, createdAt, folderId")
    abstract suspend fun getFolders(): List<LocalFavouriteFolder>

    @Query("SELECT * FROM local_favourite_folders WHERE folderId = :folderId")
    abstract suspend fun getFolder(folderId: Long): LocalFavouriteFolder?

    @Insert
    abstract suspend fun insertFolder(folder: LocalFavouriteFolder): Long

    @Query("UPDATE local_favourite_folders SET name = :name, updatedAt = :updatedAt WHERE folderId = :folderId")
    abstract suspend fun renameFolder(folderId: Long, name: String, updatedAt: Long): Int

    @Query("""
        UPDATE local_favourite_folders SET sortOrder = :sortOrder, updatedAt = :updatedAt
        WHERE folderId = :folderId
    """)
    protected abstract suspend fun setFolderOrder(folderId: Long, sortOrder: Int, updatedAt: Long)

    /** The supplied list must contain all current folders exactly once; there is no folder-count limit. */
    @Transaction
    open suspend fun reorderFolders(folderIds: List<Long>, updatedAt: Long) {
        require(folderIds.distinct().size == folderIds.size) { "Folder order contains duplicate IDs" }
        require(folderIds.toSet() == getFolders().map { it.folderId }.toSet()) {
            "Folder order must contain all current folders"
        }
        folderIds.forEachIndexed { order, id -> setFolderOrder(id, order, updatedAt) }
    }

    @Query("SELECT * FROM local_favourites ORDER BY updatedAt DESC, comicId")
    abstract fun observeAll(): Flow<List<LocalFavourite>>

    @Query("SELECT * FROM local_favourites WHERE folderId = :folderId ORDER BY updatedAt DESC, comicId")
    abstract fun observeFolder(folderId: Long): Flow<List<LocalFavourite>>

    @Query("SELECT * FROM local_favourites WHERE comicId = :comicId")
    abstract fun observe(comicId: String): Flow<LocalFavourite?>

    @Query("SELECT * FROM local_favourites WHERE comicId = :comicId")
    abstract suspend fun get(comicId: String): LocalFavourite?

    @Upsert
    abstract suspend fun upsert(favourite: LocalFavourite)

    @Query("UPDATE local_favourites SET folderId = :folderId, updatedAt = :updatedAt WHERE comicId IN (:comicIds)")
    abstract suspend fun moveComics(comicIds: List<String>, folderId: Long, updatedAt: Long): Int

    @Query("DELETE FROM local_favourites WHERE comicId IN (:comicIds)")
    abstract suspend fun deleteComics(comicIds: List<String>): Int

    @Query("UPDATE local_favourites SET folderId = :targetFolderId, updatedAt = :updatedAt WHERE folderId = :folderId")
    protected abstract suspend fun moveFolderContents(folderId: Long, targetFolderId: Long, updatedAt: Long): Int

    /** Only empty folders can be deleted directly. Nonempty folders are protected by their foreign key. */
    @Query("DELETE FROM local_favourite_folders WHERE folderId = :folderId")
    abstract suspend fun deleteEmptyFolder(folderId: Long): Int

    /** Moves every comic first, then removes the source folder in the same transaction. */
    @Transaction
    open suspend fun moveAndDeleteFolder(folderId: Long, targetFolderId: Long, updatedAt: Long): Int {
        require(folderId != targetFolderId) { "Destination folder must differ from source folder" }
        require(getFolder(targetFolderId) != null) { "Destination folder does not exist" }
        val moved = moveFolderContents(folderId, targetFolderId, updatedAt)
        deleteEmptyFolder(folderId)
        return moved
    }

    @Query("DELETE FROM local_favourites WHERE folderId = :folderId")
    protected abstract suspend fun deleteFolderContents(folderId: Long): Int

    /** Explicit destructive alternative for a UI that has confirmed removal of the folder's favourites. */
    @Transaction
    open suspend fun deleteFolderAndFavourites(folderId: Long): Int {
        val removed = deleteFolderContents(folderId)
        deleteEmptyFolder(folderId)
        return removed
    }
}
