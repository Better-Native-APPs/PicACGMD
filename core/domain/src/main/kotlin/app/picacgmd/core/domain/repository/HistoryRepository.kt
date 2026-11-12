package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.db.ViewHistoryDao
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.ComicInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ReadingProgress(val epOrder: Int, val epTitle: String, val pictureIndex: Int, val chapterPages: Int = 0)

/** Disabling history stops new writes; existing progress remains readable and is not erased. */
class HistoryRepository(
    private val dao: ViewHistoryDao,
    private val settings: Flow<AppSettings>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    val history: Flow<List<ViewHistory>> = dao.observeAll()
    fun observe(comicId: String): Flow<ViewHistory?> = dao.observe(comicId)
    suspend fun get(comicId: String): ViewHistory? = dao.get(comicId)

    fun observeProgress(comicId: String): Flow<ReadingProgress?> = dao.observe(comicId).map { it?.progress() }
    suspend fun getProgress(comicId: String): ReadingProgress? = dao.get(comicId)?.progress()

    /**
     * Live 「已读」 marks for the comics a list shows: comicId → last chapter read (0 = only the details
     * were opened); comics without history are absent. One narrow query per [READ_MARK_CHUNK] ids keeps
     * long infinite-scroll lists under the 999-variable limit of older SQLite builds.
     */
    fun observeReadChapters(comicIds: Collection<String>): Flow<Map<String, Int>> {
        val chunks = comicIds.distinct().chunked(READ_MARK_CHUNK)
        if (chunks.isEmpty()) return flowOf(emptyMap())
        return combine(chunks.map(dao::observeReadChapters)) { parts ->
            buildMap { parts.forEach { part -> part.forEach { put(it.comicId, it.lastEpOrder) } } }
        }.distinctUntilChanged()
    }

    suspend fun recordDetailView(comic: ComicInfo) {
        val preferences = settings.first()
        if (!preferences.historyEnabled || !preferences.recordDetailViews) return
        mutex.withLock { dao.upsert(snapshot(comic, dao.get(comic.id))) }
    }

    suspend fun saveProgress(comic: ComicInfo, epOrder: Int, epTitle: String, pictureIndex: Int, chapterPages: Int) {
        require(epOrder >= 1 && pictureIndex >= 1) { "阅读章节和页码必须从 1 开始" }
        if (!settings.first().historyEnabled) return
        mutex.withLock {
            dao.upsert(
                snapshot(comic, dao.get(comic.id)).copy(
                    lastEpOrder = epOrder, lastEpTitle = epTitle, lastPictureIndex = pictureIndex,
                    lastChapterPages = chapterPages,
                ),
            )
        }
    }

    suspend fun setPinned(comicId: String, pinned: Boolean): Int = dao.setPinned(comicId, pinned)
    suspend fun delete(comicIds: List<String>): Int = dao.delete(comicIds)
    suspend fun clear() = dao.clear()

    private fun snapshot(comic: ComicInfo, previous: ViewHistory?): ViewHistory {
        val timestamp = now()
        return (previous ?: ViewHistory(comic.id, comic.title, createdAt = timestamp)).copy(
            title = comic.title,
            author = comic.author,
            thumbFileServer = comic.thumb?.fileServer.orEmpty(),
            thumbPath = comic.thumb?.path.orEmpty(),
            categoriesJson = Json.encodeToString(comic.categories),
            updatedAt = timestamp,
        )
    }

    private fun ViewHistory.progress(): ReadingProgress? =
        if (lastEpOrder < 1 || lastPictureIndex < 1) null
        else ReadingProgress(lastEpOrder, lastEpTitle, lastPictureIndex, lastChapterPages)

    private companion object {
        const val READ_MARK_CHUNK = 500
    }
}
