package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.LocalFavourite
import app.picacgmd.core.data.db.LocalFavouriteDao
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalFavouriteRepository(
    private val dao: LocalFavouriteDao,
    private val settings: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    val folders: Flow<List<LocalFavouriteFolder>> = dao.observeFolders()
    val favourites: Flow<List<LocalFavourite>> = dao.observeAll()
    fun folder(folderId: Long): Flow<List<LocalFavourite>> = dao.observeFolder(folderId)
    fun observe(comicId: String): Flow<LocalFavourite?> = dao.observe(comicId)

    suspend fun createFolder(name: String): Long = mutex.withLock {
        require(name.isNotBlank()) { "收藏夹名称不能为空" }
        val timestamp = now()
        val order = (dao.getFolders().maxOfOrNull(LocalFavouriteFolder::sortOrder) ?: -1) + 1
        dao.insertFolder(LocalFavouriteFolder(name.trim(), sortOrder = order, createdAt = timestamp))
    }

    suspend fun renameFolder(folderId: Long, name: String): Int {
        require(name.isNotBlank()) { "收藏夹名称不能为空" }
        return dao.renameFolder(folderId, name.trim(), now())
    }

    suspend fun reorderFolders(folderIds: List<Long>) = dao.reorderFolders(folderIds, now())

    suspend fun add(comic: ComicInfo, folderId: Long? = null) = add(comic.toSimple(), folderId)

    suspend fun add(comic: ComicSimple, folderId: Long? = null) = mutex.withLock {
        val destination = if (folderId != null) {
            requireNotNull(dao.getFolder(folderId)) { "收藏夹不存在" }.folderId
        } else {
            val lastFolder = settings.settings.first().lastFavouriteFolderId?.let { dao.getFolder(it) }
            lastFolder?.folderId ?: dao.getFolders().firstOrNull()?.folderId
                ?: dao.insertFolder(LocalFavouriteFolder("默认收藏夹", createdAt = now()))
        }
        val timestamp = now()
        val previous = dao.get(comic.id)
        dao.upsert(
            LocalFavourite(
                comicId = comic.id, folderId = destination, title = comic.title, author = comic.author,
                thumbFileServer = comic.thumb?.fileServer.orEmpty(), thumbPath = comic.thumb?.path.orEmpty(),
                categoriesJson = Json.encodeToString(comic.categories),
                createdAt = previous?.createdAt ?: timestamp, updatedAt = timestamp,
            ),
        )
        settings.setLastFavouriteFolderId(destination)
    }

    suspend fun move(comicIds: List<String>, folderId: Long): Int {
        require(dao.getFolder(folderId) != null) { "收藏夹不存在" }
        return dao.moveComics(comicIds, folderId, now())
    }

    suspend fun remove(comicIds: List<String>): Int = dao.deleteComics(comicIds)
    suspend fun deleteEmptyFolder(folderId: Long): Int = dao.deleteEmptyFolder(folderId)
    suspend fun moveAndDeleteFolder(folderId: Long, targetFolderId: Long): Int =
        dao.moveAndDeleteFolder(folderId, targetFolderId, now())

    /** Destructive alternative: call only after the user confirms removal of the contained favourites. */
    suspend fun deleteFolderAndFavourites(folderId: Long): Int = dao.deleteFolderAndFavourites(folderId)
}
