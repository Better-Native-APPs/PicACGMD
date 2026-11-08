package app.picacgmd.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.network.MyComment
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.settings.BackButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class CommentsState(
    val items: List<MyComment> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val loading: Boolean = true,
    val error: String? = null,
)

private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatDate(value: String?): String? = value?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DateFormat) }.getOrNull()
}

/** 我的评论: every comment the account posted, newest first; tap the comic title to open it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MyCommentsScreen(
    onBack: () -> Unit,
    onOpenComic: (comicId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CommentsState()) }
    var refreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun load(page: Int) {
        state = state.copy(loading = true, error = null)
        scope.launch {
            try {
                val result = container.commentRepository.myComments(page)
                state = CommentsState(
                    items = if (page == 1) result.docs else state.items + result.docs,
                    page = result.page,
                    pages = result.pages.coerceAtLeast(1),
                    loading = false,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                state = state.copy(loading = false, error = exception.message ?: "加载失败")
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { load(1) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.distinctUntilChanged().collect { nearEnd ->
            val current = state
            if (nearEnd && !current.loading && current.error == null && current.page in 1 until current.pages) load(current.page + 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("我的评论") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; load(1) },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            when {
                state.items.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                state.items.isEmpty() && state.error != null -> ErrorState(onRetry = { load(1) }, description = state.error, modifier = Modifier.fillMaxSize())
                state.items.isEmpty() -> EmptyState(
                    title = "还没有评论",
                    description = "在漫画详情页发表评论",
                    icon = Icons.AutoMirrored.Outlined.Comment,
                    shape = MaterialShapes.Clover4Leaf,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { comment ->
                        CommentCard(comment, onOpenComic)
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.loading -> LoadingIndicator(Modifier.size(40.dp))
                                state.error != null -> TextButton(onClick = { load(state.page + 1) }) { Text("加载失败，重试") }
                                state.page >= state.pages -> Text(
                                    "没有更多",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentCard(comment: MyComment, onOpenComic: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val comic = comment.comic
    Card(
        onClick = { if (comic != null && comic.id.isNotBlank()) onOpenComic(comic.id) },
        enabled = comic != null && comic.id.isNotBlank(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerLow,
            disabledContainerColor = colors.surfaceContainerLow,
            disabledContentColor = colors.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (comic != null) {
                Text(
                    comic.title.ifBlank { "漫画" },
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(comment.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatDate(comment.createdAt).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (comment.hide) {
                    Icon(Icons.Outlined.VisibilityOff, contentDescription = "已隐藏", tint = colors.error, modifier = Modifier.size(16.dp))
                }
                Meta(Icons.Outlined.FavoriteBorder, comment.likesCount, "赞")
                Meta(Icons.Outlined.ChatBubbleOutline, comment.commentsCount, "回复")
            }
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
