package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.LocalFavouriteDao
import app.picacgmd.core.data.db.LocalFavouriteEntry
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaImageUrls
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-only favourites. Neither reads nor writes depend on the session or the network. */
class LocalFavouriteRepository(
    private val dao: LocalFavouriteDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    val folders: Flow<List<LocalFavouriteFolder>> = flow {
        ensureDefaultFolder()
        emitAll(dao.observeFolders())
    }
    val entries: Flow<List<LocalFavouriteEntry>> = dao.observeEntries()
    fun folderId(comicId: String): Flow<String?> = dao.observeFolderId(comicId)

    /** IGNORE makes first access idempotent and never resets a renamed default folder. */
    suspend fun ensureDefaultFolder() {
        dao.insertFolder(LocalFavouriteFolder(LocalFavouriteFolder.DEFAULT_ID, LocalFavouriteFolder.DEFAULT_NAME, createdAt = now()))
    }

    suspend fun createFolder(name: String): LocalFavouriteFolder = mutex.withLock {
        val trimmed = folderName(name)
        ensureDefaultFolder()
        val folder = LocalFavouriteFolder(newId(), trimmed, sortOrder = dao.nextFolderOrder(), createdAt = now())
        check(dao.insertFolder(folder) != -1L) { "创建收藏夹失败，请重试" }
        folder
    }

    suspend fun renameFolder(folderId: String, name: String) = mutex.withLock {
        check(dao.renameFolder(folderId, folderName(name)) != 0) { "收藏夹已不存在" }
    }

    suspend fun deleteFolder(folderId: String, targetFolderId: String) = mutex.withLock {
        dao.moveAndDeleteFolder(folderId, targetFolderId)
    }

    suspend fun setFolder(comic: ComicInfo, folderId: String?) = setFolder(comic.toSimple(), folderId)

    suspend fun setFolder(comic: ComicSimple, folderId: String?) = mutex.withLock {
        require(comic.id.isNotBlank()) { "漫画 ID 不能为空" }
        ensureDefaultFolder()
        dao.setFolder(
            LocalFavouriteEntry(
                folderId = "",
                comicId = comic.id,
                title = comic.title,
                author = comic.author,
                coverUrl = comic.thumb?.takeIf { it.fileServer.isNotBlank() && it.path.isNotBlank() }?.let { PicaImageUrls.build(it) }.orEmpty(),
                thumbFileServer = comic.thumb?.fileServer.orEmpty(),
                thumbPath = comic.thumb?.path.orEmpty(),
                categoriesJson = Json.encodeToString(comic.categories),
                pagesCount = comic.pagesCount,
                epsCount = comic.epsCount,
                finished = comic.finished,
                addedAt = now(),
            ),
            folderId,
        )
    }

    /** Move the saved snapshot without needing to reload the comic from the network. */
    suspend fun move(comicId: String, folderId: String) = mutex.withLock {
        val snapshot = requireNotNull(dao.getComicEntry(comicId)) { "漫画已移出本地收藏" }
        dao.setFolder(snapshot.copy(addedAt = now()), folderId)
    }

    /** A selected folder only removes comics still in that folder; null removes by comic ID. */
    suspend fun remove(comicIds: List<String>, folderId: String? = null): Int = mutex.withLock {
        comicIds.distinct().chunked(500).sumOf { dao.removeEntries(it, folderId) }
    }

    suspend fun restore(entries: List<LocalFavouriteEntry>) = mutex.withLock {
        require(entries.all { dao.getFolder(it.folderId) != null }) { "收藏夹已删除，无法撤销" }
        dao.upsertEntries(entries)
    }

    private fun folderName(name: String): String = name.trim().also {
        require(it.isNotEmpty()) { "收藏夹名称不能为空" }
    }
}

fun LocalFavouriteEntry.toSimple(): ComicSimple = ComicSimple(
    id = comicId,
    title = title,
    author = author,
    pagesCount = pagesCount,
    epsCount = epsCount,
    finished = finished,
    categories = Json.decodeFromString(categoriesJson),
    thumb = if (thumbPath.isBlank()) null else PicaImage(thumbFileServer, thumbPath),
)
