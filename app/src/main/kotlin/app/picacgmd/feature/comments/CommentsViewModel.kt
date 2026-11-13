package app.picacgmd.feature.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.network.Comment
import app.picacgmd.feature.comics.readable
import app.picacgmd.paging.Dedupe
import app.picacgmd.paging.PageAccumulator
import app.picacgmd.paging.PageData
import app.picacgmd.paging.PageWrite
import app.picacgmd.util.suspendRunCatching
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
    /** Scrolling may ask for the next page: nothing is in flight, and a failed page waits for a retry. */
    val canLoadNext: Boolean get() = !loading && !appending && hasNext && appendError == null
}

data class ReplyThread(
    val parent: Comment,
    val replies: List<Comment> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val loading: Boolean = true,
    val sending: Boolean = false,
    /** Why the last page failed; loading more then waits for 重试. */
    val error: String? = null,
) {
    /** After a failure, scrolling must not fire the same request again and again; 重试 does it once. */
    val canLoadMore: Boolean get() = !loading && error == null && page < pages
}

class CommentsViewModel(private val repository: CommentRepository, private val comicId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(CommentsState())
    val state: StateFlow<CommentsState> = mutableState.asStateFlow()
    private val mutableReplies = MutableStateFlow<ReplyThread?>(null)
    val replies: StateFlow<ReplyThread?> = mutableReplies.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()
    private val pages = PageAccumulator<Comment, String, List<Comment>>(
        initial = PageData(emptyList(), 0, 0, 0, emptyList()),
        keyOf = Comment::id,
        replaceDedupe = Dedupe.NONE,
        appendDedupe = Dedupe.AGAINST_OLD,
        appendMeta = { old, _ -> old },
    )
    private var replyPages: PageAccumulator<Comment, String, Unit>? = null

    init { reload() }

    fun reload() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            suspendRunCatching { repository.comments(comicId, 1) }
                .onSuccess { page ->
                    val data = pages.commit(
                        PageData(page.comments.docs, page.comments.page, page.comments.pages, page.comments.total, page.topComments),
                        PageWrite.REPLACE,
                    ).data
                    mutableState.update {
                        it.copy(top = data.meta, items = data.items, page = data.page, pages = data.pages, loading = false)
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(loading = false, error = error.readable()) } }
        }
    }

    fun loadNext() {
        if (!mutableState.value.canLoadNext) return
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
            suspendRunCatching { repository.comments(comicId, next) }
                .onSuccess { page ->
                    val data = pages.commit(
                        PageData(page.comments.docs, page.comments.page, page.comments.pages, page.comments.total, page.topComments),
                        PageWrite.APPEND,
                    ).data
                    mutableState.update { s ->
                        s.copy(items = data.items, page = data.page, pages = data.pages, appending = false)
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(appending = false, appendError = error.readable()) } }
        }
    }

    fun post(content: String, onDone: () -> Unit) {
        if (content.isBlank() || mutableState.value.sending) return
        mutableState.update { it.copy(sending = true) }
        viewModelScope.launch {
            suspendRunCatching { repository.post(comicId, content.trim()) }
                .onSuccess { onDone(); mutableState.update { it.copy(sending = false) }; reload() }
                .onFailure { error -> mutableState.update { it.copy(sending = false) }; mutableMessages.tryEmit(error.readable()) }
        }
    }

    fun toggleLike(commentId: String) {
        // Optimistic flip everywhere the comment appears.
        flip(commentId, null)
        viewModelScope.launch {
            suspendRunCatching { repository.toggleLike(commentId) }
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
        val data = pages.update { page -> page.copy(meta = page.meta.map { it.apply() }, items = page.items.map { it.apply() }) }
        mutableState.update { s -> s.copy(top = data.meta, items = data.items) }
        replyPages?.let { accumulator ->
            val replies = accumulator.update { page -> page.copy(items = page.items.map { it.apply() }) }
            mutableReplies.update { t -> t?.copy(parent = t.parent.apply(), replies = replies.items) }
        }
    }

    /** [confirmation] is the already localized text shown once the report has gone through. */
    fun report(commentId: String, confirmation: String) {
        viewModelScope.launch {
            suspendRunCatching { repository.report(commentId) }
                .onSuccess { mutableMessages.tryEmit(confirmation) }
                .onFailure { error -> mutableMessages.tryEmit(error.readable()) }
        }
    }

    fun openReplies(comment: Comment) {
        replyPages = PageAccumulator(
            initial = PageData(emptyList(), 0, 1, 0, Unit),
            keyOf = Comment::id,
            replaceDedupe = Dedupe.NONE,
            appendDedupe = Dedupe.AGAINST_OLD,
            appendMeta = { _, _ -> },
        )
        mutableReplies.value = ReplyThread(comment)
        loadReplies(1)
    }

    fun closeReplies() {
        replyPages = null
        mutableReplies.value = null
    }

    fun loadMoreReplies() {
        val t = mutableReplies.value ?: return
        if (!t.canLoadMore) return
        loadReplies(t.page + 1)
    }

    /** Asks again for the page that failed, the first one included. */
    fun retryReplies() {
        val t = mutableReplies.value ?: return
        if (t.loading) return
        loadReplies(t.page + 1)
    }

    private fun loadReplies(page: Int) {
        val parentId = mutableReplies.value?.parent?.id ?: return
        mutableReplies.update { it?.copy(loading = true, error = null) }
        viewModelScope.launch {
            suspendRunCatching { repository.replies(parentId, page) }
                .onSuccess { result ->
                    val t = mutableReplies.value
                    if (t == null || t.parent.id != parentId) return@onSuccess
                    val data = requireNotNull(replyPages).commit(
                        PageData(result.docs, result.page, result.pages, result.total, Unit),
                        if (page == 1) PageWrite.REPLACE else PageWrite.APPEND,
                    ).data
                    mutableReplies.update { thread ->
                        thread?.copy(replies = data.items, page = data.page, pages = data.pages, loading = false)
                    }
                }
                .onFailure { error ->
                    // Shown in the sheet with a retry; the page count stays, so the retry knows where to go on.
                    mutableReplies.update { t ->
                        if (t == null || t.parent.id != parentId) t else t.copy(loading = false, error = error.readable())
                    }
                }
        }
    }

    fun reply(content: String, onDone: () -> Unit) {
        val t = mutableReplies.value ?: return
        if (content.isBlank() || t.sending) return
        mutableReplies.update { it?.copy(sending = true) }
        viewModelScope.launch {
            suspendRunCatching { repository.reply(t.parent.id, content.trim()) }
                .onSuccess {
                    onDone()
                    mutableReplies.update { it?.copy(sending = false, parent = it.parent.copy(commentsCount = it.parent.commentsCount + 1)) }
                    loadReplies(1)
                }
                .onFailure { error -> mutableReplies.update { it?.copy(sending = false) }; mutableMessages.tryEmit(error.readable()) }
        }
    }
}
