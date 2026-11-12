package app.picacgmd.feature.games

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.designsystem.component.AppIconButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.Comment
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comments.CommentComposer
import app.picacgmd.feature.comments.CommentRow
import app.picacgmd.feature.comments.ReportCommentDialog
import app.picacgmd.feature.comments.commentLength
import app.picacgmd.feature.comments.commentSendable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private class GameCommentsViewModel(
    private val repository: GameRepository,
    private val reports: CommentRepository,
    private val gameId: String,
    private val parentId: String?,
) : ViewModel() {
    var comments by mutableStateOf<List<Comment>>(emptyList())
        private set
    var page by mutableStateOf(0)
        private set
    var pages by mutableStateOf(0)
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

    init { load(1, false) }

    fun load(target: Int, append: Boolean) {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = if (parentId == null) repository.comments(gameId, target) else null
                val pageResult = result?.comments ?: repository.replies(requireNotNull(parentId), target)
                val incoming = (result?.topComments.orEmpty() + pageResult.docs).distinctBy { it.id }
                comments = if (append) (comments + incoming).distinctBy { it.id } else incoming
                page = pageResult.page
                pages = pageResult.pages
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
                comments = comments.map {
                    if (it.id == comment.id) it.copy(
                        isLiked = liked,
                        likesCount = (it.likesCount + if (liked == it.isLiked) 0 else if (liked) 1 else -1).coerceAtLeast(0),
                    ) else it
                }
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

/**
 * Board 29's comment section: the same rows, reply flow, like toggle, report menu and composer as the
 * comic comments (board 17), only the data source differs. [GameCommentsViewModel] keeps its own
 * pagination because the game endpoints are not Paging 3.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GameCommentsScreen(
    gameId: String,
    title: String,
    onBack: () -> Unit,
    parentCommentId: String? = null,
    onReplies: ((String) -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val model: GameCommentsViewModel = viewModel(
        key = "game-comments-$gameId-$parentCommentId",
        factory = viewModelFactory {
            initializer { GameCommentsViewModel(container.gameRepository, container.commentRepository, gameId, parentCommentId) }
        },
    )
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings.DEFAULT)
    val infinite = settings.paginationMode == PaginationMode.INFINITE
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var reportTarget by remember { mutableStateOf<Comment?>(null) }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(list, infinite) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (infinite && model.comments.isNotEmpty() && last >= model.comments.size - 3 &&
                !model.loading && model.error == null && model.page < model.pages) {
                model.load(model.page + 1, true)
            }
        }
    }
    LaunchedEffect(model.page, infinite) { if (!infinite && model.page > 0) list.scrollToItem(0) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { app.picacgmd.core.designsystem.component.BackButton(onBack) },
                actions = { AppIconButton({ model.load(1, false) }, enabled = !model.loading) { Icon(Icons.Outlined.Refresh, "刷新") } },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            CommentComposer(
                hint = if (parentCommentId == null) stringResource(R.string.comments_input_hint) else "输入回复",
                // The original bar also refused to send while a page was still loading.
                sending = model.sending || model.loading,
                onSend = { content, done -> model.post(content.trim(), done) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (model.comments.isEmpty()) {
            GameLoadState(model.loading, model.error, stringResource(R.string.comments_empty), Modifier.padding(padding)) { model.load(1, false) }
        } else {
            val paging = !infinite && model.pages > 1
            Box(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = list,
                    // Room for the paging toolbar so the last comment can clear it.
                    contentPadding = PaddingValues(bottom = if (paging) 104.dp else 0.dp),
                ) {
                    items(model.comments, key = { it.id }) { comment ->
                        CommentRow(
                            comment = comment,
                            pinned = comment.isTop,
                            likeEnabled = comment.id !in model.liking,
                            onLike = { model.toggleLike(comment) },
                            onReplies = onReplies?.let { open -> { open(comment.id) } },
                            onReport = { reportTarget = comment },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 64.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                model.loading -> LoadingIndicator()
                                model.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(model.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                    AppTextButton({
                                        if (infinite && model.page < model.pages) model.load(model.page + 1, true)
                                        else model.load(model.page.coerceAtLeast(1), false)
                                    }) { Text(stringResource(R.string.comics_load_more_failed)) }
                                }
                                infinite && model.page < model.pages -> AppTextButton({ model.load(model.page + 1, true) }) { Text("加载更多") }
                                infinite -> Text(
                                    stringResource(R.string.comics_end_of_list),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (paging) {
                    GamePagination(
                        page = model.page,
                        pages = model.pages,
                        loading = model.loading,
                        onPage = { model.load(it, false) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    )
                }
            }
        }
    }

    reportTarget?.let { target ->
        val confirmation = stringResource(R.string.comments_reported)
        ReportCommentDialog(
            comment = target,
            onDismiss = { reportTarget = null },
            onConfirm = {
                reportTarget = null
                model.report(target.id, confirmation)
            },
        )
    }
}
