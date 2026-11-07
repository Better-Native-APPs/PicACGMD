package app.picacgmd.feature.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.ComicCoverCard
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.feature.discover.LoadFailure
import app.picacgmd.feature.discover.LoadState
import app.picacgmd.feature.discover.appViewModel
import app.picacgmd.feature.discover.rememberImageUrl

/** One recommendation collection, without selection or download controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    index: Int,
    title: String,
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = appViewModel(key = "collection:$index") { CollectionsViewModel(it) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                is LoadState.Failed -> ScrollableStatus {
                    LoadFailure(state, onRetry = { viewModel.load(force = true) })
                }
                is LoadState.Ready -> {
                    val group = state.data.getOrNull(index)
                    if (group == null || group.comics.isEmpty()) {
                        ScrollableStatus { EmptyState(title = stringResource(R.string.collections_empty)) }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(116.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(group.comics, key = { it.id }) { comic ->
                                ComicCoverCard(
                                    title = comic.title,
                                    coverUrl = rememberImageUrl(comic.thumb),
                                    author = comic.author.ifBlank { null },
                                    onClick = { onOpenComic(comic.id) },
                                )
                            }
                            if (group.hiddenCount > 0) {
                                item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) {
                                    HiddenFooter(group.hiddenCount)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
