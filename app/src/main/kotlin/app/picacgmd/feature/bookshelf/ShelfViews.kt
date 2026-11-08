package app.picacgmd.feature.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.designsystem.component.ComicCover
import app.picacgmd.core.designsystem.component.ComicListItem
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.domain.repository.SubscriptionCheckState
import app.picacgmd.core.network.ComicSort
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comics.ComicResults

private val ShelfBottomPadding = PaddingValues(bottom = 112.dp)

/** 收藏: server favourites, newest/oldest sort, no page cap (board 21). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineFavouritesView(
    viewModel: BookshelfViewModel,
    settings: AppSettings,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onOpenComic: (String) -> Unit,
) {
    val state by viewModel.onlinePager.state.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    if (refreshing && !state.loading) refreshing = false
    PullToRefreshBox(
        isRefreshing = refreshing && state.loading,
        onRefresh = {
            refreshing = true
            viewModel.onlinePager.reload()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        ComicResults(
            state = state,
            layout = settings.listLayout,
            columnCount = settings.listColumnCount,
            paginationMode = settings.paginationMode,
            selection = selection,
            onSelectionChange = onSelectionChange,
            onComicClick = onOpenComic,
            onLoadNext = viewModel.onlinePager::loadNext,
            onRetryAppend = viewModel.onlinePager::retryAppend,
            onRetry = viewModel.onlinePager::reload,
            onJumpTo = viewModel.onlinePager::jumpTo,
            contentPadding = ShelfBottomPadding,
            emptyTitle = "还没有在线收藏",
            emptyDescription = "在漫画详情里点「收藏」，会同步到你的哔咔账号。",
            header = {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.total > 0) "共 ${state.total} 本" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (sort == ComicSort.OLDEST) "旧到新" else "新到旧")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            listOf(ComicSort.NEWEST to "新到旧", ComicSort.OLDEST to "旧到新").forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { menu = false; viewModel.setSort(value) },
                                    trailingIcon = if (value == sort) {
                                        { Icon(Icons.Outlined.Check, contentDescription = "当前") }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

/** 追更: updated first with 「新 N 话」; pull to check every subscription (board 22). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FollowView(
    subscriptions: List<Subscription>?,
    checkState: SubscriptionCheckState,
    onRefresh: () -> Unit,
    selection: Set<String>,
    onToggle: (String) -> Unit,
    onOpenComic: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val checking = checkState is SubscriptionCheckState.Checking
    PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        val sorted = subscriptions.orEmpty().sortedWith(compareByDescending<Subscription> { it.unseen }.thenByDescending { it.updatedAt })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ShelfBottomPadding) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val text = when (checkState) {
                        is SubscriptionCheckState.Checking -> "正在检查 ${checkState.completed}/${checkState.total}"
                        is SubscriptionCheckState.Done -> {
                            val report = checkState.report
                            buildString {
                                append("${sorted.count { it.unseen }} 本有更新 · 检查了 ${report.checkedCount} 本")
                                if (report.failures.isNotEmpty()) append(" · ${report.failures.size} 本失败")
                            }
                        }
                        is SubscriptionCheckState.Failed -> "检查失败：${checkState.message}"
                        SubscriptionCheckState.Idle -> "${sorted.size} 本 · ${sorted.count { it.unseen }} 本有更新"
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (checkState is SubscriptionCheckState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (checking) {
                        ContainedLoadingIndicator(Modifier.size(36.dp))
                    } else {
                        TextButton(onClick = onRefresh, enabled = sorted.isNotEmpty()) { Text("检查更新") }
                    }
                }
                if (checkState is SubscriptionCheckState.Checking && checkState.total > 0) {
                    LinearWavyProgressIndicator(
                        progress = { checkState.completed.toFloat() / checkState.total },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            if (subscriptions != null && sorted.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有追更",
                        description = "在漫画详情里点「追更」，有新章节时这里和书架图标会提示。",
                        icon = Icons.Outlined.NotificationsActive,
                        shape = MaterialShapes.Cookie9Sided,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }
            items(sorted, key = { it.comicId }) { subscription ->
                val cover = remember(subscription.comicId, subscription.thumbPath) {
                    container.safeImageUrl(subscription.thumbFileServer, subscription.thumbPath)
                }
                ComicListItem(
                    title = subscription.title,
                    coverUrl = cover,
                    subtitle = subscription.author.ifBlank { null },
                    pages = "共 ${subscription.lastKnownEpsCount} 话",
                    badge = if (subscription.unseen) {
                        if (subscription.newEpCount > 0) "新 ${subscription.newEpCount} 话" else "有更新"
                    } else {
                        null
                    },
                    selected = subscription.comicId in selection,
                    onClick = {
                        if (selection.isNotEmpty()) onToggle(subscription.comicId) else onOpenComic(subscription.comicId)
                    },
                    onLongClick = { onToggle(subscription.comicId) },
                    trailingContent = if (subscription.unseen) {
                        { Box(Modifier.padding(end = 8.dp).size(10.dp).background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.extraLarge)) }
                    } else {
                        null
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/**
 * 记录: a big 「接着读」 card for the latest comic, then 置顶 / 今天 / 昨天 / 更早 groups with swipe
 * to delete (undo via snackbar) and a ⋮ action menu (board 22).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HistoryView(
    history: List<ViewHistory>?,
    selection: Set<String>,
    onToggle: (String) -> Unit,
    onOpenComic: (String) -> Unit,
    onContinue: (ViewHistory) -> Unit,
    onDelete: (ViewHistory) -> Unit,
    onPin: (ViewHistory, Boolean) -> Unit,
) {
    val container = LocalAppContainer.current
    if (history != null && history.isEmpty()) {
        EmptyState(
            title = "还没有浏览记录",
            description = "看过的漫画会出现在这里，下次一按就能接着读。",
            icon = Icons.Outlined.History,
            shape = MaterialShapes.Sunny,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val items = history.orEmpty()
    val latest = items.maxByOrNull { it.updatedAt }
    val rest = items.filter { it.comicId != latest?.comicId }
    val groups = linkedMapOf(
        "置顶" to rest.filter { it.pinned },
        "今天" to rest.filter { !it.pinned && dayBucket(it.updatedAt) == 0 },
        "昨天" to rest.filter { !it.pinned && dayBucket(it.updatedAt) == 1 },
        "更早" to rest.filter { !it.pinned && dayBucket(it.updatedAt) == 2 },
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ShelfBottomPadding) {
        if (latest != null) {
            item(key = "continue") {
                ContinueCard(
                    history = latest,
                    coverUrl = remember(latest.comicId, latest.thumbPath) { container.safeImageUrl(latest.thumbFileServer, latest.thumbPath) },
                    onContinue = { onContinue(latest) },
                    onOpen = { onOpenComic(latest.comicId) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        groups.forEach { (title, entries) ->
            if (entries.isEmpty()) return@forEach
            stickyHeader(key = "h-$title") {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(entries, key = { it.comicId }) { entry ->
                HistoryRow(
                    entry = entry,
                    selected = entry.comicId in selection,
                    selecting = selection.isNotEmpty(),
                    onToggle = { onToggle(entry.comicId) },
                    onOpen = { onOpenComic(entry.comicId) },
                    onContinue = { onContinue(entry) },
                    onDelete = { onDelete(entry) },
                    onPin = { onPin(entry, !entry.pinned) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

internal fun ViewHistory.progressText(): String = when {
    lastEpOrder >= 1 && lastPictureIndex >= 1 ->
        "读到 ${lastEpTitle.ifBlank { "第 $lastEpOrder 话" }} · 第 $lastPictureIndex 页"
    else -> "看过详情，还没开始读"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContinueCard(
    history: ViewHistory,
    coverUrl: String?,
    onContinue: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val canContinue = history.lastEpOrder >= 1 && history.lastPictureIndex >= 1
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ComicCover(coverUrl = coverUrl, contentDescription = null, modifier = Modifier.width(76.dp), shape = MaterialTheme.shapes.large)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("接着读", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Text(
                    history.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(history.progressText(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = if (canContinue) onContinue else onOpen) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (canContinue) "继续阅读" else "打开详情")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HistoryRow(
    entry: ViewHistory,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val dismiss = rememberSwipeToDismissBoxState()
    var menu by remember { mutableStateOf(false) }
    val cover = remember(entry.comicId, entry.thumbPath) { container.safeImageUrl(entry.thumbFileServer, entry.thumbPath) }
    val categories = remember(entry.categoriesJson) { decodeCategories(entry.categoriesJson).take(3) }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !selecting,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
        modifier = modifier,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
            ComicListItem(
                title = entry.title,
                coverUrl = cover,
                subtitle = entry.progressText(),
                tags = categories,
                selected = selected,
                onClick = if (selecting) onToggle else onOpen,
                onLongClick = onToggle,
                leadingContent = if (entry.pinned) {
                    { Icon(Icons.Outlined.PushPin, contentDescription = "已置顶", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
                trailingContent = if (selecting) null else {
                    {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("继续阅读") },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                    onClick = { menu = false; onContinue() },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (entry.pinned) "取消置顶" else "置顶") },
                                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                    onClick = { menu = false; onPin() },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除这条记录") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menu = false; onDelete() },
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}
