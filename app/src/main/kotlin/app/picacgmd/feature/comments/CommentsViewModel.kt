package app.picacgmd.feature.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.network.Comment
import app.picacgmd.feature.comics.readable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentsState(
    val top: List<Comment> = emptyList(),
    val items: List<Comment> = emptyList(),
    val page: Int = 0,
    val pages: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val appending: Boolean = false,
    val appendError: String? = null,
    val sending: Boolean = false,
) {
    val hasNext: Boolean get() = page < pages
}

data class ReplyThread(
    val parent: Comment,
    val replies: List<Comment> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val loading: Boolean = true,
    val sending: Boolean = false,
)

class CommentsViewModel(container: AppContainer, private val comicId: String) : ViewModel() {
    private val repository = container.commentRepository
    private val mutableState = MutableStateFlow(CommentsState())
    val state: StateFlow<CommentsState> = mutableState.asStateFlow()
    private val mutableReplies = MutableStateFlow<ReplyThread?>(null)
    val replies: StateFlow<ReplyThread?> = mutableReplies.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    init { reload() }

    fun reload() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.comments(comicId, 1) }
                .onSuccess { page ->
                    mutableState.update {
                        it.copy(top = page.topComments, items = page.comments.docs, page = page.comments.page, pages = page.comments.pages, loading = false)
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(loading = false, error = error.readable()) } }
        }
    }

    fun loadNext() {
        val s = mutableState.value
        if (s.loading || s.appending || !s.hasNext || s.appendError != null) return
        append()
    }

    fun retryAppend() {
        mutableState.update { it.copy(appendError = null) }
        append()
    }

    private fun append() {
        val next = mutableState.value.page + 1
        mutableState.update { it.copy(appending = true) }
        viewModelScope.launch {
            runCatching { repository.comments(comicId, next) }
                .onSuccess { page ->
                    mutableState.update { s ->
                        val known = s.items.mapTo(HashSet(), Comment::id)
                        s.copy(items = s.items + page.comments.docs.filter { it.id !in known }, page = page.comments.page, pages = page.comments.pages, appending = false)
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(appending = false, appendError = error.readable()) } }
        }
    }

    fun post(content: String, onDone: () -> Unit) {
        if (content.isBlank() || mutableState.value.sending) return
        mutableState.update { it.copy(sending = true) }
        viewModelScope.launch {
            runCatching { repository.post(comicId, content.trim()) }
                .onSuccess { onDone(); mutableState.update { it.copy(sending = false) }; reload() }
                .onFailure { error -> mutableState.update { it.copy(sending = false) }; mutableMessages.tryEmit(error.readable()) }
        }
    }

    fun toggleLike(commentId: String) {
        // Optimistic flip everywhere the comment appears.
        flip(commentId, null)
        viewModelScope.launch {
            runCatching { repository.toggleLike(commentId) }
                .onSuccess { liked -> flip(commentId, liked) }
                .onFailure { error -> flip(commentId, null); mutableMessages.tryEmit(error.readable()) }
        }
    }

    private fun flip(id: String, target: Boolean?) {
        fun Comment.apply(): Comment {
            if (this.id != id) return this
            val liked = target ?: !isLiked
            if (liked == isLiked) return this
            return copy(isLiked = liked, likesCount = (likesCount + if (liked) 1 else -1).coerceAtLeast(0))
        }
        mutableState.update { s -> s.copy(top = s.top.map { it.apply() }, items = s.items.map { it.apply() }) }
        mutableReplies.update { t -> t?.copy(parent = t.parent.apply(), replies = t.replies.map { it.apply() }) }
    }

    /** [confirmation] is the already localized text shown once the report has gone through. */
    fun report(commentId: String, confirmation: String) {
        viewModelScope.launch {
            runCatching { repository.report(commentId) }
                .onSuccess { mutableMessages.tryEmit(confirmation) }
                .onFailure { error -> mutableMessages.tryEmit(error.readable()) }
        }
    }

    fun openReplies(comment: Comment) {
        mutableReplies.value = ReplyThread(comment)
        loadReplies(1)
    }

    fun closeReplies() { mutableReplies.value = null }

    fun loadMoreReplies() {
        val t = mutableReplies.value ?: return
        if (t.loading || t.page >= t.pages) return
        loadReplies(t.page + 1)
    }

    private fun loadReplies(page: Int) {
        val parentId = mutableReplies.value?.parent?.id ?: return
        mutableReplies.update { it?.copy(loading = true) }
        viewModelScope.launch {
            runCatching { repository.replies(parentId, page) }
                .onSuccess { result ->
                    mutableReplies.update { t ->
                        if (t == null || t.parent.id != parentId) return@update t
                        val base = if (page == 1) emptyList() else t.replies
                        val known = base.mapTo(HashSet(), Comment::id)
                        t.copy(replies = base + result.docs.filter { it.id !in known }, page = result.page, pages = result.pages, loading = false)
                    }
                }
                .onFailure { error ->
                    mutableReplies.update { it?.copy(loading = false, pages = 0) }
                    mutableMessages.tryEmit(error.readable())
                }
        }
    }

    fun reply(content: String, onDone: () -> Unit) {
        val t = mutableReplies.value ?: return
        if (content.isBlank() || t.sending) return
        mutableReplies.update { it?.copy(sending = true) }
        viewModelScope.launch {
            runCatching { repository.reply(t.parent.id, content.trim()) }
                .onSuccess {
                    onDone()
                    mutableReplies.update { it?.copy(sending = false, parent = it.parent.copy(commentsCount = it.parent.commentsCount + 1)) }
                    loadReplies(1)
                }
                .onFailure { error -> mutableReplies.update { it?.copy(sending = false) }; mutableMessages.tryEmit(error.readable()) }
        }
    }
}
