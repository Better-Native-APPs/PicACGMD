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
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.component.TonalAsyncImage
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.network.Announcement
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.bookshelf.safeImageUrl
import app.picacgmd.feature.settings.BackButton
import app.picacgmd.core.designsystem.component.PredictiveBackContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class AnnouncementsState(
    val items: List<Announcement> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val loading: Boolean = true,
    val error: String? = null,
)

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
    var state by remember { mutableStateOf(AnnouncementsState()) }
    var refreshing by remember { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun load(page: Int) {
        state = state.copy(loading = true, error = null)
        scope.launch {
            try {
                val result = container.announcementRepository.page(page)
                state = AnnouncementsState(
                    items = if (page == 1) result.docs else (state.items + result.docs).distinctBy { it.id },
                    page = result.page,
                    pages = result.pages.coerceAtLeast(1),
                    loading = false,
                )
                if (page == 1) {
                    result.docs.firstOrNull()?.id?.let { newest ->
                        runCatching { container.settingsRepository.setLastSeenAnnouncementId(newest) }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                state = state.copy(loading = false, error = exception.message ?: "加载失败")
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { if (state.page == 0) load(1) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            val current = state
            if (nearEnd && !current.loading && current.error == null && current.page in 1 until current.pages) load(current.page + 1)
        }
    }

    val opened = state.items.firstOrNull { it.id == openId }
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
                onRefresh = { refreshing = true; load(1) },
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                when {
                    // A pulled refresh already shows its own indicator.
                    state.items.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!refreshing) LoadingIndicator()
                    }
                    state.items.isEmpty() && state.error != null -> ErrorState(onRetry = { load(1) }, description = state.error, modifier = Modifier.fillMaxSize())
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
