package app.picacgmd.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.db.LocalFavouriteEntry
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.TagFavourite
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.ComicSort
import app.picacgmd.feature.comics.ComicPager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bookshelf views in their fixed order (board 21); 记录 is always shown. */
enum class ShelfView(val label: String) { Online("收藏"), Local("本地收藏"), Follow("追更"), History("记录") }

internal fun AppSettings.visibleShelfViews(): List<ShelfView> = buildList {
    if (showOnlineFavourites) add(ShelfView.Online)
    add(ShelfView.Local)
    if (showSubscriptions) add(ShelfView.Follow)
    add(ShelfView.History)
}

/** Progress of a batch action shown in the floating snackbar-style card (board 23). */
internal data class BatchProgress(val label: String, val done: Int, val total: Int)

/** Online favourites paging plus long-running batch actions that should survive recomposition. */
class BookshelfViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableSort = MutableStateFlow(ComicSort.NEWEST)
    val sort: StateFlow<ComicSort> = mutableSort.asStateFlow()
    val settings: StateFlow<AppSettings?> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val localFolders: StateFlow<List<LocalFavouriteFolder>?> = container.localFavouriteRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val favouriteTags: StateFlow<List<TagFavourite>?> = container.tagFavouriteRepository.favourites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val localEntries: StateFlow<List<LocalFavouriteEntry>?> = container.localFavouriteRepository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val mutableLocalFolder = MutableStateFlow<String?>(null)
    val localFolder: StateFlow<String?> = mutableLocalFolder.asStateFlow()
    private val mutableLocalNewestFirst = MutableStateFlow(true)
    val localNewestFirst: StateFlow<Boolean> = mutableLocalNewestFirst.asStateFlow()
    val localComics: StateFlow<List<LocalFavouriteEntry>?> = combine(
        localEntries, mutableLocalFolder, mutableLocalNewestFirst,
    ) { entries, folderId, newestFirst ->
        entries?.filter { folderId == null || it.folderId == folderId }
            ?.sortedWith(
                (if (newestFirst) compareByDescending<LocalFavouriteEntry> { it.addedAt } else compareBy { it.addedAt })
                    .thenBy { it.comicId },
            )?.distinctBy { it.comicId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every online favourite seen so far, so selected comics stay resolvable after "全选". */
    private val known = LinkedHashMap<String, ComicSimple>()

    val onlinePager = ComicPager(viewModelScope) { page ->
        container.comicRepository.favourites(mutableSort.value, page).also { result ->
            result.items.forEach { known[it.id] = it }
        }
    }

    private val mutableBatch = MutableStateFlow<BatchProgress?>(null)
    internal val batch: StateFlow<BatchProgress?> = mutableBatch.asStateFlow()

    private val mutableMessages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = mutableMessages.asStateFlow()

    private var onlineStarted = false

    init {
        viewModelScope.launch {
            container.settingsRepository.settings
                .distinctUntilChangedBy { Triple(it.paginationMode, it.shieldedCategories to it.categoryShieldMode, it.hiddenKeywords) }
                .collect {
                    onlinePager.mode = it.paginationMode
                    if (onlineStarted) onlinePager.reload()
                }
        }
    }

    /** Online favourites load lazily, the first time the 收藏 view is shown. */
    fun ensureOnline() {
        if (onlineStarted) return
        onlineStarted = true
        onlinePager.reload()
    }

    fun setSort(value: ComicSort) {
        if (value == mutableSort.value) return
        mutableSort.value = value
        onlinePager.reload()
    }

    fun selectLocalFolder(folderId: String?) { mutableLocalFolder.value = folderId }
    fun setLocalNewestFirst(newestFirst: Boolean) { mutableLocalNewestFirst.value = newestFirst }
    suspend fun removeFavouriteTag(tag: String) { container.tagFavouriteRepository.remove(listOf(tag)) }
    suspend fun createLocalFolder(name: String): String = container.localFavouriteRepository.createFolder(name).folderId
    suspend fun renameLocalFolder(folderId: String, name: String) = container.localFavouriteRepository.renameFolder(folderId, name)
    suspend fun deleteLocalFolder(folderId: String, targetFolderId: String) {
        container.localFavouriteRepository.deleteFolder(folderId, targetFolderId)
        if (mutableLocalFolder.value == folderId) mutableLocalFolder.value = targetFolderId
    }

    fun removeLocal(comicIds: List<String>, onRemoved: (List<LocalFavouriteEntry>) -> Unit) {
        val folderId = mutableLocalFolder.value
        val removed = localEntries.value.orEmpty().filter { it.comicId in comicIds && (folderId == null || it.folderId == folderId) }
        run("移出本地收藏失败") {
            localFavouriteRepository.remove(comicIds, folderId)
            onRemoved(removed)
        }
    }

    fun restoreLocal(entries: List<LocalFavouriteEntry>) = run("撤销失败") { localFavouriteRepository.restore(entries) }

    fun consumeMessage(message: String) = mutableMessages.update { it - message }

    private fun say(message: String) = mutableMessages.update { it + message }

    fun onlineComics(ids: Collection<String>): List<ComicSimple> {
        val loaded = onlinePager.state.value.comics.associateBy { it.id }
        return ids.mapNotNull { loaded[it] ?: known[it] }
    }

    /** "全选" covers every favourite, not only loaded pages: fetch remaining pages first. */
    fun selectAllOnline(onDone: (Set<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val first = container.comicRepository.favourites(mutableSort.value, 1)
                first.items.forEach { known[it.id] = it }
                val ids = LinkedHashSet(first.items.map { it.id })
                for (page in 2..first.pages) {
                    mutableBatch.value = BatchProgress("正在读取全部收藏", page - 1, first.pages)
                    val result = container.comicRepository.favourites(mutableSort.value, page)
                    result.items.forEach { known[it.id] = it; ids += it.id }
                }
                onDone(ids)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                say("读取收藏失败：${exception.message ?: "请重试"}")
            } finally {
                mutableBatch.value = null
            }
        }
    }

    /** Unfavourites one by one (never concurrently); failures stay in the list. */
    fun unfavourite(ids: List<String>, deleteDownloads: Boolean, onDownloadsDelete: suspend (String) -> Unit) {
        viewModelScope.launch {
            var failed = 0
            ids.forEachIndexed { index, id ->
                mutableBatch.value = BatchProgress("正在取消收藏", index, ids.size)
                try {
                    val stillFavourite = container.comicRepository.toggleFavourite(id)
                    if (stillFavourite) container.comicRepository.toggleFavourite(id)
                    if (deleteDownloads) runCatching { onDownloadsDelete(id) }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    failed++
                }
            }
            mutableBatch.value = null
            say(if (failed == 0) "已取消收藏 ${ids.size} 本" else "已取消收藏 ${ids.size - failed} 本，$failed 本失败")
            onlinePager.reload()
        }
    }

    fun run(failure: String, success: String? = null, block: suspend AppContainer.() -> Unit) {
        viewModelScope.launch {
            try {
                container.block()
                if (success != null) say(success)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                say("$failure：${exception.message ?: "请重试"}")
            }
        }
    }

    fun restoreHistory(items: List<ViewHistory>) = run("撤销失败") {
        items.forEach { database.viewHistoryDao().upsert(it) }
    }

    fun restoreSubscriptions(items: List<Subscription>) = run("撤销失败") {
        items.forEach { database.subscriptionDao().upsert(it) }
    }
}

internal fun ViewHistory.toComic(): ComicSimple =
    snapshotComic(comicId, title, author, thumbFileServer, thumbPath, categoriesJson)

internal fun Subscription.toComic(): ComicSimple =
    snapshotComic(comicId, title, author, thumbFileServer, thumbPath, "[]")
