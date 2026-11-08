package app.picacgmd.feature.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.ComicCoverCard
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.component.SectionHeader
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.CollectionGroup
import app.picacgmd.core.domain.repository.FilteredComics
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.feature.comics.ReadMarks
import app.picacgmd.feature.comics.readMarkText
import app.picacgmd.feature.comics.rememberReadMarks
import app.picacgmd.feature.discover.ComicSimpleRow
import app.picacgmd.feature.discover.LoadFailure
import app.picacgmd.feature.discover.LoadState
import app.picacgmd.feature.discover.appViewModel
import app.picacgmd.feature.discover.loadCatching
import app.picacgmd.feature.discover.rememberImageUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shared loader: first load shows a pane, later reloads keep the old data until new data arrives. */
abstract class ReloadableViewModel<T> : ViewModel() {
    var state by mutableStateOf<LoadState<T>>(LoadState.Loading)
        private set
    var isPulling by mutableStateOf(false)
        private set
    var isReloading by mutableStateOf(false)
        private set
    private var job: Job? = null

    protected abstract suspend fun fetch(force: Boolean): T

    fun load(force: Boolean = false, pull: Boolean = false) {
        if (job?.isActive == true) return
        isPulling = pull
        isReloading = true
        if (state !is LoadState.Ready) state = LoadState.Loading
        job = viewModelScope.launch {
            val result = loadCatching { fetch(force) }
            if (result is LoadState.Ready || state !is LoadState.Ready) state = result
            isPulling = false
            isReloading = false
        }
    }
}

class RandomViewModel(private val container: AppContainer) : ReloadableViewModel<FilteredComics>() {
    init { load() }
    override suspend fun fetch(force: Boolean): FilteredComics = container.comicRepository.random()
}

class CollectionsViewModel(private val container: AppContainer) : ReloadableViewModel<List<CollectionGroup>>() {
    init { load() }
    override suspend fun fetch(force: Boolean): List<CollectionGroup> = container.comicRepository.collections(force)
}

/** 随机 (board 07): pull to refresh, plus the 「换一批」 FAB as the page's single main action. Wire into `RandomRoute`. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RandomScreen(
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = appViewModel { RandomViewModel(it) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.random_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.load(force = true) },
                icon = {
                    if (viewModel.isReloading && !viewModel.isPulling) {
                        LoadingIndicator(Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Filled.Casino, contentDescription = null)
                    }
                },
                text = { Text(stringResource(R.string.random_reroll)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = viewModel.isPulling,
            onRefresh = { viewModel.load(force = true, pull = true) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (val state = viewModel.state) {
                LoadState.Loading -> LoadingState(Modifier.fillMaxSize())
                is LoadState.Failed -> ScrollableStatus { LoadFailure(state, onRetry = { viewModel.load() }) }
                is LoadState.Ready -> if (state.data.items.isEmpty()) {
                    ScrollableStatus { EmptyState(title = stringResource(R.string.random_empty)) }
                } else {
                    val readMarks = rememberReadMarks(remember(state.data.items) { state.data.items.map(ComicSimple::id) })
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        items(state.data.items, key = { it.id }) { comic ->
                            ComicSimpleRow(
                                comic = comic,
                                onClick = { onOpenComic(comic.id) },
                                readChapter = readMarks.chapterOf(comic.id),
                            )
                        }
                        if (state.data.hiddenCount > 0) item(key = "hidden") { HiddenFooter(state.data.hiddenCount) }
                    }
                }
            }
        }
    }
}

/** 推荐 (board 07): official collections as titled horizontal rows. Wire into `CollectionsRoute`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    onOpenCollection: (index: Int, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = appViewModel { CollectionsViewModel(it) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collections_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = viewModel.isPulling,
            onRefresh = { viewModel.load(force = true, pull = true) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (val state = viewModel.state) {
                LoadState.Loading -> LoadingState(Modifier.fillMaxSize())
                is LoadState.Failed -> ScrollableStatus { LoadFailure(state, onRetry = { viewModel.load(force = true) }) }
                is LoadState.Ready -> {
                    val groups = state.data.withIndex().filter { it.value.comics.isNotEmpty() }
                    val readMarks = rememberReadMarks(remember(state.data) { state.data.flatMap { group -> group.comics.map(ComicSimple::id) } })
                    if (groups.isEmpty()) {
                        ScrollableStatus { EmptyState(title = stringResource(R.string.collections_empty)) }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            groups.forEach { (index, group) ->
                                item(key = "header-$index", contentType = "header") {
                                    SectionHeader(
                                        title = group.title,
                                        actionLabel = stringResource(R.string.discover_action_all),
                                        onActionClick = { onOpenCollection(index, group.title) },
                                    )
                                }
                                item(key = "row-$index", contentType = "row") {
                                    CollectionRow(group, readMarks, onOpenComic)
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
private fun CollectionRow(group: CollectionGroup, readMarks: ReadMarks, onOpenComic: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(group.comics, key = { it.id }) { comic ->
            val mark = readMarkText(readMarks.chapterOf(comic.id), comic.epsCount)
            ComicCoverCard(
                title = comic.title,
                coverUrl = rememberImageUrl(comic.thumb),
                onClick = { onOpenComic(comic.id) },
                author = comic.author.ifBlank { null },
                progressLabel = mark?.label,
                progressDetail = mark?.detail,
                progressDetailShort = mark?.detailShort,
                modifier = Modifier.width(120.dp),
            )
        }
    }
    if (group.hiddenCount > 0) {
        Box(Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.rankings_hidden_count, group.hiddenCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
