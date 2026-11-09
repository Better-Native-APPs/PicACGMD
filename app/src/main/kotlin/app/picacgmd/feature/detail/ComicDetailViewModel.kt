package app.picacgmd.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.ReadingProgress
import app.picacgmd.core.domain.repository.containsKeyword
import app.picacgmd.core.domain.repository.isComicHidden
import app.picacgmd.core.domain.repository.minusKeywords
import app.picacgmd.core.domain.repository.plusKeywords
import app.picacgmd.core.domain.repository.toSimple
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Comment
import app.picacgmd.core.network.Ep
import app.picacgmd.feature.comics.readable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val comic: ComicInfo? = null,
    val hiddenByRule: Boolean = false,
    val eps: List<Ep>? = null,
    val epsError: String? = null,
    val recommendations: List<ComicSimple> = emptyList(),
    val comments: List<Comment> = emptyList(),
    val commentsLoaded: Boolean = false,
    val liking: Boolean = false,
    val favouriting: Boolean = false,
)

/** Library-side state that lives in the local database and updates live. */
data class LibraryState(
    val progress: ReadingProgress? = null,
    val subscribed: Boolean = false,
    /** Chapters whose download has finished. */
    val downloadedEpIds: Set<String> = emptySet(),
    /** Pictures in the chapter being read, the 22 of 「看到 14/22」; null until the API has answered. */
    val currentEpPages: Int? = null,
)

/** A tag or category that 屏蔽模式 has just put into the hidden keywords ([blocked]) or taken out. */
data class KeywordChange(val keyword: String, val blocked: Boolean)

class ComicDetailViewModel(private val container: AppContainer, val comicId: String) : ViewModel() {
    private val comics = container.comicRepository
    private val mutableState = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    val localFolders: StateFlow<List<LocalFavouriteFolder>?> = container.localFavouriteRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val localFolderIds: StateFlow<Set<String>?> = container.localFavouriteRepository.folderIds(comicId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun createLocalFolder(name: String): String = container.localFavouriteRepository.createFolder(name).folderId
    suspend fun saveLocalFavourite(folderIds: Set<String>) {
        val comic = checkNotNull(mutableState.value.comic) { "漫画信息尚未加载" }
        container.localFavouriteRepository.setFolders(comic, folderIds)
    }

    /** Chapter order → picture count, so coming back from the reader does not ask again. */
    private val epPageCounts = HashMap<Int, Int>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val library: StateFlow<LibraryState> = combine(
        container.historyRepository.observeProgress(comicId),
        container.subscriptionRepository.observe(comicId).map { it != null },
        // Download progress rewrites these rows constantly; only the set of finished chapters matters here.
        container.database.downloadDao().observeEps(comicId)
            .map { eps -> eps.filter { it.status == DownloadStatus.DONE }.mapTo(HashSet(), DownloadEp::epId) }
            .distinctUntilChanged(),
    ) { progress, subscribed, downloaded ->
        LibraryState(progress, subscribed, downloaded)
    }.transformLatest { library ->
        // The count travels with the progress it belongs to, so a new chapter never shows the old total.
        val epOrder = library.progress?.epOrder
        val known = epOrder?.let { epPageCounts[it] }
        emit(library.copy(currentEpPages = known))
        if (epOrder != null && known == null) {
            fetchEpPages(epOrder)?.let { emit(library.copy(currentEpPages = it)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    /** The hidden keywords, for marking the tag and category chips that are blocked. */
    val hiddenKeywords: StateFlow<List<String>> = container.settingsRepository.settings
        .map { it.hiddenKeywords }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableKeywordChanges = MutableSharedFlow<KeywordChange>(extraBufferCapacity = 4)
    val keywordChanges: SharedFlow<KeywordChange> = mutableKeywordChanges.asSharedFlow()
    private val keywordWrites = Mutex()

    init {
        load()
        // Follows the settings, so a tag blocked in 屏蔽模式 changes the notice straight away. The
        // 「隐藏看过的漫画」 rule is left out: it is about lists, and every comic opened here counts as viewed.
        viewModelScope.launch {
            combine(mutableState.map { it.comic }.distinctUntilChanged(), container.settingsRepository.settings) { comic, settings ->
                comic != null && isComicHidden(comic.toSimple(), settings)
            }.distinctUntilChanged().collect { hidden -> mutableState.update { it.copy(hiddenByRule = hidden) } }
        }
    }

    /** 屏蔽模式: puts [word] into the hidden keywords, or takes it out when it is in there already. */
    fun toggleHiddenKeyword(word: String) {
        val keyword = word.trim()
        if (keyword.isEmpty()) return
        viewModelScope.launch {
            try {
                // Read and written under one lock, so quick taps on several chips never undo each other.
                val blocked = keywordWrites.withLock {
                    val current = container.settingsRepository.settings.first().hiddenKeywords
                    val blocking = !current.containsKeyword(keyword)
                    container.settingsRepository.setHiddenKeywords(
                        if (blocking) current.plusKeywords(listOf(keyword)) else current.minusKeywords(listOf(keyword)),
                    )
                    blocking
                }
                mutableKeywordChanges.tryEmit(KeywordChange(keyword, blocked))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableMessages.tryEmit(error.readable())
            }
        }
    }

    /** Best effort: without an answer the chapter row just says 「看到第 N 页」. */
    private suspend fun fetchEpPages(epOrder: Int): Int? = try {
        comics.pictures(comicId, epOrder).pages.total.takeIf { it > 0 }?.also { epPageCounts[epOrder] = it }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val comic = comics.comicInfo(comicId)
                mutableState.update { it.copy(loading = false, comic = comic) }
                runCatching { container.historyRepository.recordDetailView(comic) }
                val eps = async { runCatching { comics.allEps(comicId) } }
                val recommend = async { runCatching { comics.recommendations(comicId).items } }
                val comments = async { runCatching { container.commentRepository.comments(comicId) } }
                eps.await().fold(
                    { list -> mutableState.update { it.copy(eps = list, epsError = null) } },
                    { error -> mutableState.update { it.copy(epsError = error.readable()) } },
                )
                recommend.await().onSuccess { list -> mutableState.update { it.copy(recommendations = list) } }
                comments.await().onSuccess { page ->
                    val preview = (page.topComments + page.comments.docs).distinctBy(Comment::id).take(3)
                    mutableState.update { it.copy(comments = preview, commentsLoaded = true) }
                }.onFailure { mutableState.update { it.copy(commentsLoaded = true) } }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = error.readable()) }
            }
        }
    }

    fun retryEps() {
        mutableState.update { it.copy(epsError = null) }
        viewModelScope.launch {
            runCatching { comics.allEps(comicId) }.fold(
                { list -> mutableState.update { it.copy(eps = list) } },
                { error -> mutableState.update { it.copy(epsError = error.readable()) } },
            )
        }
    }

    fun toggleLike() {
        val comic = mutableState.value.comic ?: return
        if (mutableState.value.liking) return
        // Optimistic: flip now, settle on the server answer.
        mutableState.update { it.copy(liking = true, comic = comic.copy(isLiked = !comic.isLiked, likesCount = comic.likesCount + if (comic.isLiked) -1 else 1)) }
        viewModelScope.launch {
            runCatching { comics.toggleLike(comicId) }
                .onSuccess { liked ->
                    mutableState.update { s ->
                        val c = s.comic ?: comic
                        s.copy(liking = false, comic = if (c.isLiked == liked) c else c.copy(isLiked = liked))
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(liking = false, comic = comic) }
                    mutableMessages.tryEmit(error.readable())
                }
        }
    }

    fun toggleFavourite() {
        val comic = mutableState.value.comic ?: return
        if (mutableState.value.favouriting) return
        mutableState.update { it.copy(favouriting = true, comic = comic.copy(isFavourite = !comic.isFavourite)) }
        viewModelScope.launch {
            runCatching { comics.toggleFavourite(comicId) }
                .onSuccess { fav -> mutableState.update { s -> s.copy(favouriting = false, comic = s.comic?.copy(isFavourite = fav)) } }
                .onFailure { error ->
                    mutableState.update { it.copy(favouriting = false, comic = comic) }
                    mutableMessages.tryEmit(error.readable())
                }
        }
    }

    fun toggleSubscribe() {
        val comic = mutableState.value.comic ?: return
        viewModelScope.launch {
            runCatching {
                if (library.value.subscribed) container.subscriptionRepository.unsubscribe(listOf(comicId))
                else container.subscriptionRepository.subscribe(comic)
            }.onFailure { mutableMessages.tryEmit(it.readable()) }
        }
    }
}
