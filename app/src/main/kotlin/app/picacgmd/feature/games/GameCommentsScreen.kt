package app.picacgmd.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.Comment
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private class GameCommentsViewModel(
    private val repository: GameRepository,
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
        if (sending || loading || content.isBlank()) return
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
                error = exception.localizedMessage ?: "发送失败，内容已保留"
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
                error = exception.localizedMessage ?: "点赞失败，请重试"
            } finally {
                liking -= comment.id
            }
        }
    }
}

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
        factory = viewModelFactory { initializer { GameCommentsViewModel(container.gameRepository, gameId, parentCommentId) } },
    )
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings.DEFAULT)
    val infinite = settings.paginationMode == PaginationMode.INFINITE
    val list = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(list, infinite) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (infinite && model.comments.isNotEmpty() && last >= model.comments.size - 3 &&
                !model.loading && model.error == null && model.page < model.pages) {
                model.load(model.page + 1, true)
            }
        }
    }
    LaunchedEffect(model.page, infinite) { if (!infinite && model.page > 0) list.scrollToItem(0) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                    actions = { IconButton({ model.load(1, false) }, enabled = !model.loading) { Icon(Icons.Outlined.Refresh, "刷新") } },
                )
                if (parentCommentId == null) PrimaryTabRow(selectedTabIndex = 1) {
                    Tab(false, onBack, text = { Text("详情") })
                    Tab(true, {}, text = { Text("评论") })
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(if (parentCommentId == null) "我有话要讲" else "写下回复") },
                        maxLines = 4, enabled = !model.sending,
                    )
                    IconButton({ model.post(draft.trim()) { draft = "" } }, enabled = draft.isNotBlank() && !model.sending && !model.loading) {
                        Icon(Icons.AutoMirrored.Outlined.Send, if (model.sending) "正在发送" else "发送")
                    }
                }
            }
        },
    ) { padding ->
        if (model.comments.isEmpty()) GameLoadState(model.loading, model.error, "还没有评论，来聊聊吧", Modifier.padding(padding)) { model.load(1, false) }
        else LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(model.comments, key = { it.id }) { comment ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GameImage(comment.user?.avatar, null, Modifier.size(40.dp).clip(MaterialShapes.Cookie9Sided.toShape()))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(comment.user?.name ?: "匿名用户", style = MaterialTheme.typography.titleSmall)
                                Text("Lv.${comment.user?.level ?: 0}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (comment.isTop) Text("置顶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Text(if (comment.hide) "这条评论已被隐藏" else comment.content)
                            Text(comment.createdAt.orEmpty().replace('T', ' ').take(16), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (onReplies != null) TextButton({ onReplies(comment.id) }) { Text(if (comment.commentsCount > 0) "${comment.commentsCount} 条回复" else "回复") }
                                TextButton({ model.toggleLike(comment) }, enabled = comment.id !in model.liking) {
                                    Icon(if (comment.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "喜欢", Modifier.size(18.dp))
                                    Text(" ${comment.likesCount}")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (model.loading) LoadingIndicator()
                    model.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        FilledTonalButton({ model.load(if (infinite && model.page < model.pages) model.page + 1 else model.page.coerceAtLeast(1), infinite) }) { Text("重试") }
                    }
                    if (!infinite) GamePagination(model.page, model.pages, model.loading) { model.load(it, false) }
                    else if (!model.loading && model.error == null && model.page < model.pages) TextButton({ model.load(model.page + 1, true) }) { Text("加载更多") }
                }
            }
        }
    }
}
