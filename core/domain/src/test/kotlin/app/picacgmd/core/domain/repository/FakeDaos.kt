package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ApiCache
import app.picacgmd.core.data.db.ApiCacheDao
import app.picacgmd.core.data.db.LocalFavouriteDao
import app.picacgmd.core.data.db.LocalFavouriteEntry
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.data.db.ReadChapter
import app.picacgmd.core.data.db.SearchHistory
import app.picacgmd.core.data.db.SearchHistoryDao
import app.picacgmd.core.data.db.TagFavourite
import app.picacgmd.core.data.db.TagFavouriteDao
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.db.ViewHistoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakeApiCacheDao : ApiCacheDao {
    val entries = MutableStateFlow<Map<String, ApiCache>>(emptyMap())
    override fun observe(key: String): Flow<ApiCache?> = entries.map { it[key] }
    override suspend fun getFresh(key: String, now: Long): ApiCache? = entries.value[key]?.takeUnless { it.isExpired(now) }
    override suspend fun upsert(cache: ApiCache) { entries.value += cache.key to cache }
    override suspend fun delete(key: String): Int {
        val existed = key in entries.value
        entries.value -= key
        return if (existed) 1 else 0
    }
    override suspend fun deletePrefix(prefix: String): Int = deleteWhere { it.key.startsWith(prefix) }
    override suspend fun deleteExpired(now: Long): Int = deleteWhere { it.isExpired(now) }
    override suspend fun clear() { entries.value = emptyMap() }
    private fun deleteWhere(predicate: (ApiCache) -> Boolean): Int {
        val removed = entries.value.values.filter(predicate).map(ApiCache::key)
        entries.value -= removed.toSet()
        return removed.size
    }
}

internal class FakeSearchHistoryDao : SearchHistoryDao {
    val entries = MutableStateFlow<List<SearchHistory>>(emptyList())
    override fun observeAll(): Flow<List<SearchHistory>> = entries
    override suspend fun upsert(history: SearchHistory) {
        entries.value = (entries.value.filterNot { it.query == history.query } + history).sortedByDescending { it.updatedAt }
    }
    override suspend fun delete(queries: List<String>): Int {
        val previous = entries.value
        entries.value = previous.filterNot { it.query in queries }
        return previous.size - entries.value.size
    }
    override suspend fun clear() { entries.value = emptyList() }
}

internal class FakeViewHistoryDao : ViewHistoryDao {
    val entries = MutableStateFlow<Map<String, ViewHistory>>(emptyMap())
    override fun observeAll(): Flow<List<ViewHistory>> = entries.map { it.values.toList() }
    override fun observePage(limit: Int, offset: Int): Flow<List<ViewHistory>> = observeAll().map { it.drop(offset).take(limit) }
    override fun observe(comicId: String): Flow<ViewHistory?> = entries.map { it[comicId] }
    override fun observeViewedIds(comicIds: List<String>): Flow<List<String>> = entries.map { values -> comicIds.filter { it in values } }
    /** Largest id list a single read-mark query received; SQLite on API 29–30 caps it at 999. */
    var largestReadChapterQuery = 0
        private set
    override fun observeReadChapters(comicIds: List<String>): Flow<List<ReadChapter>> {
        largestReadChapterQuery = maxOf(largestReadChapterQuery, comicIds.size)
        return entries.map { values -> comicIds.mapNotNull { id -> values[id]?.let { ReadChapter(it.comicId, it.lastEpOrder) } } }
    }
    override suspend fun get(comicId: String): ViewHistory? = entries.value[comicId]
    override suspend fun upsert(history: ViewHistory) { entries.value += history.comicId to history }
    override suspend fun updateProgress(comicId: String, epOrder: Int, epTitle: String, pictureIndex: Int, updatedAt: Long): Int {
        val previous = get(comicId) ?: return 0
        upsert(previous.copy(lastEpOrder = epOrder, lastEpTitle = epTitle, lastPictureIndex = pictureIndex, updatedAt = updatedAt))
        return 1
    }
    override suspend fun setPinned(comicId: String, pinned: Boolean): Int {
        val previous = get(comicId) ?: return 0
        upsert(previous.copy(pinned = pinned))
        return 1
    }
    override suspend fun delete(comicIds: List<String>): Int {
        val count = comicIds.distinct().count { it in entries.value }
        entries.value -= comicIds.toSet()
        return count
    }
    override suspend fun clear() { entries.value = emptyMap() }
}

/** The inherited transaction bodies are exercised with the same keys and folder-delete cascade as Room. */
internal class FakeLocalFavouriteDao : LocalFavouriteDao() {
    val folders = MutableStateFlow<Map<String, LocalFavouriteFolder>>(emptyMap())
    val entries = MutableStateFlow<Map<String, LocalFavouriteEntry>>(emptyMap())

    override fun observeFolders(): Flow<List<LocalFavouriteFolder>> = folders.map {
        it.values.sortedWith(compareBy<LocalFavouriteFolder> { folder -> folder.sortOrder }.thenBy { folder -> folder.folderId })
    }
    override fun observeEntries(): Flow<List<LocalFavouriteEntry>> = entries.map {
        it.values.sortedWith(compareByDescending<LocalFavouriteEntry> { entry -> entry.addedAt }.thenBy { entry -> entry.comicId })
    }
    override fun observeFolderId(comicId: String): Flow<String?> = entries.map { it[comicId]?.folderId }
    override suspend fun getFolder(folderId: String): LocalFavouriteFolder? = folders.value[folderId]
    override suspend fun nextFolderOrder(): Int = (folders.value.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
    override suspend fun insertFolder(folder: LocalFavouriteFolder): Long {
        if (folder.folderId in folders.value) return -1
        folders.value += folder.folderId to folder
        return folders.value.size.toLong()
    }
    override suspend fun renameFolder(folderId: String, name: String): Int {
        val folder = getFolder(folderId) ?: return 0
        folders.value += folderId to folder.copy(name = name)
        return 1
    }
    override suspend fun markSelected(folderId: String, timestamp: Long) {
        folders.value = folders.value.mapValues { (id, folder) ->
            if (id == folderId) folder.copy(lastSelectedAt = timestamp) else folder
        }
    }
    override suspend fun getComicEntry(comicId: String): LocalFavouriteEntry? = entries.value[comicId]
    override suspend fun getFolderEntries(folderId: String): List<LocalFavouriteEntry> = entries.value.values.filter { it.folderId == folderId }
    override suspend fun upsertEntries(entries: List<LocalFavouriteEntry>) {
        require(entries.all { it.folderId in folders.value }) { "FOREIGN KEY constraint failed" }
        this.entries.value += entries.associateBy { it.comicId }
    }
    override suspend fun deleteComicEntry(comicId: String) {
        entries.value -= comicId
    }
    override suspend fun removeEntries(comicIds: List<String>, folderId: String?): Int {
        val previous = entries.value
        entries.value = previous.filterValues { it.comicId !in comicIds || (folderId != null && it.folderId != folderId) }
        return previous.size - entries.value.size
    }
    override suspend fun deleteFolderRow(folderId: String): Int {
        if (folderId !in folders.value) return 0
        folders.value -= folderId
        entries.value = entries.value.filterValues { it.folderId != folderId }
        return 1
    }
}

internal class FakeTagFavouriteDao : TagFavouriteDao {
    private val entries = MutableStateFlow<List<TagFavourite>>(emptyList())
    override fun observeAll(): Flow<List<TagFavourite>> = entries
    override suspend fun upsert(favourite: TagFavourite) {
        entries.value = (entries.value.filterNot { it.tag == favourite.tag } + favourite)
            .sortedWith(compareBy<TagFavourite> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.tag })
    }
    override suspend fun delete(tags: List<String>): Int {
        val previous = entries.value
        entries.value = previous.filterNot { it.tag in tags }
        return previous.size - entries.value.size
    }
    override suspend fun clear() { entries.value = emptyList() }
}
