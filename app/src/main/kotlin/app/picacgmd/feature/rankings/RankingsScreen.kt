package app.picacgmd.feature.rankings

import app.picacgmd.feature.routing.RouteMenuButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Knight
import app.picacgmd.core.network.LeaderboardType
import app.picacgmd.feature.comics.rememberReadMarks
import app.picacgmd.feature.discover.ComicSimpleRow
import app.picacgmd.feature.discover.LoadFailure
import app.picacgmd.feature.discover.LoadState
import app.picacgmd.feature.discover.RankBadge
import app.picacgmd.feature.discover.appViewModel
import app.picacgmd.feature.discover.loadCatching
import app.picacgmd.feature.discover.rememberImageUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

enum class RankingTab(val label: Int) {
    H24(R.string.rankings_tab_day),
    D7(R.string.rankings_tab_week),
    D30(R.string.rankings_tab_month),
    KNIGHT(R.string.rankings_tab_knight),
}

sealed interface RankingData {
    data class Comics(val items: List<ComicSimple>, val hiddenCount: Int) : RankingData
    data class Knights(val items: List<Knight>) : RankingData
}

class RankingsViewModel(private val container: AppContainer) : ViewModel() {
    var tab by mutableStateOf(RankingTab.H24)
        private set
    /** Each range is cached once loaded, so flipping between them is instant. */
    val results = mutableStateMapOf<RankingTab, LoadState<RankingData>>()
    var refreshingTab by mutableStateOf<RankingTab?>(null)
        private set
    private val jobs = mutableMapOf<RankingTab, Job>()

    init { load(RankingTab.H24, pull = false) }

    fun select(target: RankingTab) {
        tab = target
        if (results[target] !is LoadState.Ready) load(target, pull = false)
    }

    fun refresh() = load(tab, pull = true)
    fun retry() = load(tab, pull = false)

    private fun load(target: RankingTab, pull: Boolean) {
        if (jobs[target]?.isActive == true) return
        if (pull) refreshingTab = target else if (results[target] !is LoadState.Ready) results[target] = LoadState.Loading
        jobs[target] = viewModelScope.launch {
            val result = loadCatching<RankingData> {
                val comics = container.comicRepository
                when (target) {
                    RankingTab.H24 -> comics.rankings(LeaderboardType.H24).let { RankingData.Comics(it.items, it.hiddenCount) }
                    RankingTab.D7 -> comics.rankings(LeaderboardType.D7).let { RankingData.Comics(it.items, it.hiddenCount) }
                    RankingTab.D30 -> comics.rankings(LeaderboardType.D30).let { RankingData.Comics(it.items, it.hiddenCount) }
                    RankingTab.KNIGHT -> RankingData.Knights(comics.knights())
                }
            }
            // A failed pull keeps the list that is already on screen.
            if (result is LoadState.Ready || results[target] !is LoadState.Ready) results[target] = result
            if (refreshingTab == target) refreshingTab = null
        }
    }
}

/**
 * 排行榜 (board 07): connected ButtonGroup for 24 小时 / 7 天 / 30 天 / 骑士榜, Sunny badges on the podium.
 * Wire into `RankingsRoute`.
 *
 * @param onOpenKnight uploader id and name; the uploader's comics (board 06 「上传者」).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RankingsScreen(
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    onOpenKnight: (id: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = appViewModel { RankingsViewModel(it) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rankings_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = { RouteMenuButton() },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            RangeSelector(selected = viewModel.tab, onSelect = viewModel::select)
            val tab = viewModel.tab
            PullToRefreshBox(
                isRefreshing = viewModel.refreshingTab == tab,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when (val state = viewModel.results[tab] ?: LoadState.Loading) {
                    LoadState.Loading -> LoadingState(Modifier.fillMaxSize())
                    is LoadState.Failed -> ScrollableStatus { LoadFailure(state, onRetry = viewModel::retry) }
                    is LoadState.Ready -> when (val data = state.data) {
                        is RankingData.Comics -> ComicRanking(data, onOpenComic)
                        is RankingData.Knights -> KnightRanking(data.items, onOpenKnight)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RangeSelector(selected: RankingTab, onSelect: (RankingTab) -> Unit) {
    val tabs = RankingTab.entries
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        tabs.forEachIndexed { index, tab ->
            ToggleButton(
                checked = tab == selected,
                onCheckedChange = { onSelect(tab) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    tabs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(tab.label), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ComicRanking(data: RankingData.Comics, onOpenComic: (String) -> Unit) {
    if (data.items.isEmpty()) {
        ScrollableStatus { EmptyState(title = stringResource(R.string.rankings_empty)) }
        return
    }
    val readMarks = rememberReadMarks(remember(data.items) { data.items.map(ComicSimple::id) })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(data.items, key = { _, comic -> comic.id }) { index, comic ->
            ComicSimpleRow(
                comic = comic,
                onClick = { onOpenComic(comic.id) },
                readChapter = readMarks.chapterOf(comic.id),
                leadingContent = { RankBadge(rank = index + 1) },
            )
        }
        if (data.hiddenCount > 0) {
            item(key = "hidden") { HiddenFooter(data.hiddenCount) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KnightRanking(knights: List<Knight>, onOpenKnight: (String, String) -> Unit) {
    if (knights.isEmpty()) {
        ScrollableStatus { EmptyState(title = stringResource(R.string.rankings_empty)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(knights, key = { index, knight -> knight.id.ifEmpty { "knight-$index" } }) { index, knight ->
            ListItem(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onOpenKnight(knight.id, knight.name) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RankBadge(rank = index + 1)
                        KnightAvatar(knight)
                    }
                },
                headlineContent = { Text(knight.name, maxLines = 1) },
                supportingContent = {
                    val title = knight.title.ifBlank { null }
                    Text(
                        listOfNotNull(stringResource(R.string.rankings_knight_level, knight.level), title)
                            .joinToString(" · "),
                        maxLines = 1,
                    )
                },
                trailingContent = {
                    Text(
                        stringResource(R.string.rankings_knight_uploads, knight.comicsUploaded),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KnightAvatar(knight: Knight) {
    val url = rememberImageUrl(knight.avatar)
    Surface(
        modifier = Modifier.size(48.dp),
        shape = MaterialShapes.Cookie9Sided.toShape(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
internal fun HiddenFooter(count: Int) {
    Text(
        text = stringResource(R.string.rankings_hidden_count, count),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/** Status panes inside a scrollable list so pull-to-refresh still works on them. */
@Composable
internal fun ScrollableStatus(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { content() }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    app.picacgmd.core.designsystem.component.BackButton(onBack)
}
