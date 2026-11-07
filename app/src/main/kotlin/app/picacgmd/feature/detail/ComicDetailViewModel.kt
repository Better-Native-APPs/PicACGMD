package app.picacgmd.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.ReadingProgress
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Comment
import app.picacgmd.core.network.Ep
import app.picacgmd.feature.comics.readable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val localFolderId: Long? = null,
    val folders: List<LocalFavouriteFolder> = emptyList(),
    val subscribed: Boolean = false,
) {
    val localFolderName: String? get() = folders.firstOrNull { it.folderId == localFolderId }?.name
}

class ComicDetailViewModel(private val container: AppContainer, val comicId: String) : ViewModel() {
    private val comics = container.comicRepository
    private val mutableState = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    val library: StateFlow<LibraryState> = combine(
        container.historyRepository.observeProgress(comicId),
        container.localFavouriteRepository.observe(comicId),
        container.localFavouriteRepository.folders,
        container.subscriptionRepository.observe(comicId).map { it != null },
    ) { progress, local, folders, subscribed ->
        LibraryState(progress, local?.folderId, folders, subscribed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    init { load() }

    fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val comic = comics.comicInfo(comicId)
                val hidden = runCatching { comics.isHidden(comic) }.getOrDefault(false)
                mutableState.update { it.copy(loading = false, comic = comic, hiddenByRule = hidden) }
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

    fun putInFolder(folderId: Long) {
        val comic = mutableState.value.comic ?: return
        viewModelScope.launch {
            runCatching { container.localFavouriteRepository.add(comic, folderId) }
                .onFailure { mutableMessages.tryEmit(it.readable()) }
        }
    }

    fun createFolderAndPut(name: String) {
        val comic = mutableState.value.comic ?: return
        viewModelScope.launch {
            runCatching {
                val id = container.localFavouriteRepository.createFolder(name.trim())
                container.localFavouriteRepository.add(comic, id)
            }.onFailure { mutableMessages.tryEmit(it.readable()) }
        }
    }

    fun removeLocal() {
        viewModelScope.launch { runCatching { container.localFavouriteRepository.remove(listOf(comicId)) } }
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
