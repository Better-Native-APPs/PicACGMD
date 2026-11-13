package app.picacgmd.feature.announcements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import app.picacgmd.core.designsystem.component.ExpressivePullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.component.TonalAsyncImage
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.network.Announcement
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.bookshelf.safeImageUrl
import app.picacgmd.feature.settings.BackButton
import app.picacgmd.core.designsystem.component.PredictiveBackContent
import app.picacgmd.paging.LoadMoreEffect
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun formatAnnouncementDate(value: String?): String? = value?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DateFormat) }.getOrNull()
}

private val HtmlTag = Regex("<\\s*/?\\s*[a-zA-Z][^>]*>")

/** Plain text keeps its line breaks; content that looks like HTML is rendered (links, bold, breaks). */
internal fun announcementText(content: String): AnnotatedString =
    if (HtmlTag.containsMatchIn(content) || content.contains("&nbsp;") || content.contains("&amp;")) {
        AnnotatedString.fromHtml(content.replace("\r\n", "\n").replace("\n", "<br>"))
    } else {
        AnnotatedString(content.trim())
    }

/** One-line preview without markup. */
private fun preview(content: String): String =
    announcementText(content).text.replace(Regex("\\s+"), " ").trim()

/**
 * 公告: official announcements, newest first, infinite scroll; tapping one opens its full text in
 * place (system back returns to the list). Opening the list marks the newest one as seen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnnouncementsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val holder = remember {
        AnnouncementsStateHolder(scope, container.announcementRepository, container.settingsRepository::setLastSeenAnnouncementId)
    }
    val state = holder.state
    val refreshing = holder.refreshing
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(Unit) { if (holder.state.page == 0) holder.load(1) }

    val opened = state.items.firstOrNull { it.id == openId }
    // An open announcement takes the list off the screen, so nothing more is loaded behind it.
    val endKeys = remember(state.items) { state.items.takeLast(2).map { it.id } }
    LoadMoreEffect(listState, state.page, opened == null && state.canLoadNext, endKeys, holder::loadNext)
    PredictiveBackContent(
        current = opened?.id ?: "list",
        previous = "list",
        onBack = { openId = null },
        enabled = opened != null,
        modifier = modifier,
    ) { key, back ->
        val detail = state.items.firstOrNull { it.id == key }
        if (detail != null) {
            AnnouncementDetail(detail, onBack = back)
            return@PredictiveBackContent
        }
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { Text("公告") },
                    navigationIcon = { BackButton(onBack) },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            ExpressivePullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = holder::refresh,
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                when {
                    // A pulled refresh already shows its own indicator.
                    state.items.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!refreshing) LoadingState(Modifier.fillMaxSize())
                    }
                    state.items.isEmpty() && state.error != null -> ErrorState(onRetry = holder::retryFirstLoad, description = state.error, modifier = Modifier.fillMaxSize())
                    state.items.isEmpty() -> EmptyState(
                        title = "暂无公告",
                        icon = Icons.Outlined.Campaign,
                        shape = MaterialShapes.Sunny,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.id }) { announcement ->
                            AnnouncementCard(announcement, onClick = { openId = announcement.id })
                        }
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                when {
                                    refreshing -> Unit
                                    state.loading -> LoadingIndicator(Modifier.size(40.dp))
                                    state.error != null -> AppTextButton(onClick = holder::retryTail) { Text("加载失败，重试") }
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
}

@Composable
private fun AnnouncementCard(announcement: Announcement, onClick: () -> Unit) {
    val container = LocalAppContainer.current
    val thumb = remember(announcement.thumb) { container.safeImageUrl(announcement.thumb) }
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(announcement.title.ifBlank { "公告" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                formatAnnouncementDate(announcement.createdAt)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(top = 2.dp))
                }
                val text = remember(announcement.content) { preview(announcement.content) }
                if (text.isNotEmpty()) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (thumb != null) {
                TonalAsyncImage(
                    model = thumb,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementDetail(announcement: Announcement, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val thumb = remember(announcement.thumb) { container.safeImageUrl(announcement.thumb) }
    val body = remember(announcement.content) { announcementText(announcement.content) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("公告") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            Text(announcement.title.ifBlank { "公告" }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            formatAnnouncementDate(announcement.createdAt)?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            if (thumb != null) {
                TonalAsyncImage(
                    model = thumb,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.large),
                )
            }
            SelectionContainer {
                Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}
