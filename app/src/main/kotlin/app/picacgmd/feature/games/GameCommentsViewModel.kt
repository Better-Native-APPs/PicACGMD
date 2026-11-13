package app.picacgmd.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.Comment
import app.picacgmd.feature.comments.commentLength
import app.picacgmd.feature.comments.commentSendable
import app.picacgmd.paging.Dedupe
import app.picacgmd.paging.PageAccumulator
import app.picacgmd.paging.PageData
import app.picacgmd.paging.PageWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class GameCommentsViewModel(
    private val repository: GameRepository,
    private val reports: CommentRepository,
    private val gameId: String,
    private val parentId: String?,
) : ViewModel() {
    var comments by mutableStateOf<List<Comment>>(emptyList())
        private set
    var page by mutableIntStateOf(0)
        private set
    var pages by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var liking by mutableStateOf<Set<String>>(emptySet())
        private set
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off results for the snackbar, such as a report going through. */
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()
    private val accumulator = PageAccumulator<Comment, String, Unit>(
        initial = PageData(emptyList(), 0, 0, 0, Unit),
        keyOf = Comment::id,
        replaceDedupe = Dedupe.ALL,
        appendDedupe = Dedupe.ALL,
        appendMeta = { _, _ -> },
    )

    init { load(1, false) }

    fun load(target: Int, append: Boolean) {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = if (parentId == null) repository.comments(gameId, target) else null
                val pageResult = result?.comments ?: repository.replies(requireNotNull(parentId), target)
                val data = accumulator.commit(
                    PageData(result?.topComments.orEmpty() + pageResult.docs, pageResult.page, pageResult.pages, pageResult.total, Unit),
                    if (append) PageWrite.APPEND else PageWrite.REPLACE,
                ).data
                comments = data.items
                page = data.page
                pages = data.pages
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.localizedMessage ?: "加载评论失败"
            } finally {
                loading = false
            }
        }
    }

    fun post(content: String, onSent: () -> Unit) {
        if (sending || loading || !commentSendable(commentLength(content))) return
        sending = true
        error = null
        viewModelScope.launch {
            try {
                if (parentId == null) repository.post(gameId, content) else repository.reply(parentId, content)
                onSent()
                load(1, false)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.localizedMessage ?: "发送失败"
            } finally {
                sending = false
            }
        }
    }

    fun toggleLike(comment: Comment) {
        if (comment.id in liking) return
        liking += comment.id
        viewModelScope.launch {
            try {
                val liked = repository.toggleLikeComment(comment.id)
                val data = accumulator.update { current ->
                    current.copy(items = current.items.map {
                        if (it.id == comment.id) it.copy(
                            isLiked = liked,
                            likesCount = (it.likesCount + if (liked == it.isLiked) 0 else if (liked) 1 else -1).coerceAtLeast(0),
                        ) else it
                    })
                }
                comments = data.items
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.localizedMessage ?: "点赞失败"
            } finally {
                liking -= comment.id
            }
        }
    }

    /**
     * Game comments and replies live in the same comment store as comic ones (likes and replies already
     * go through the comment endpoints), so the comment report endpoint takes their ids too.
     */
    fun report(commentId: String, confirmation: String) {
        viewModelScope.launch {
            try {
                reports.report(commentId)
                mutableMessages.tryEmit(confirmation)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableMessages.tryEmit(exception.localizedMessage ?: "举报失败")
            }
        }
    }
}
