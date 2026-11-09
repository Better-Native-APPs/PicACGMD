package app.picacgmd.feature.comics

import app.picacgmd.feature.routing.RouteMenuButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.ListLayout
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.ComicSort
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ComicListViewModel(
    private val container: AppContainer,
    val category: String?,
    val tag: String?,
    val author: String?,
    val chineseTeam: String? = null,
    val creatorId: String? = null,
) : ViewModel() {
    private val mutableSort = MutableStateFlow(ComicSort.DEFAULT)
    val sort: StateFlow<ComicSort> = mutableSort.asStateFlow()
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val pager = ComicPager(viewModelScope) { page ->
        container.comicRepository.comics(
            category = category, tag = tag, author = author,
            creatorId = creatorId, chineseTeam = chineseTeam,
            sort = mutableSort.value, page = page,
        )
    }

    init {
        viewModelScope.launch {
            // Pagination mode and shield rules both change what is on screen; reload when they change.
            container.settingsRepository.settings
                .distinctUntilChangedBy { Triple(it.paginationMode, it.shieldedCategories to it.categoryShieldMode, it.hiddenKeywords to it.hideViewedComics) }
                .collect { pager.mode = it.paginationMode; pager.reload() }
        }
    }

    fun setSort(value: ComicSort) {
        if (value == mutableSort.value) return
        mutableSort.value = value
        pager.reload()
    }

    fun setLayout(value: ListLayout) {
        viewModelScope.launch { container.settingsRepository.setListLayout(value) }
    }
}

/**
 * Boards 08–09: a category / tag / author / team comic list.
 *
 * Wire as `composable<ComicListRoute> { ComicListScreen(category = it.category, tag = it.tag, author = it.author, …) }`.
 */
@Composable
fun ComicListScreen(
    category: String?,
    tag: String?,
    author: String?,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit,
    onBatchDownload: (List<ComicSimple>) -> Unit,
    modifier: Modifier = Modifier,
    chineseTeam: String? = null,
    creatorId: String? = null,
    title: String? = null,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "comics:$category:$tag:$author:$chineseTeam:$creatorId") {
        ComicListViewModel(container, category, tag, author, chineseTeam, creatorId)
    }
    val displayTitle = title ?: category ?: tag ?: author ?: chineseTeam ?: stringResource(R.string.comics_all)
    ComicListContent(viewModel, displayTitle, onBack, onComicClick, onBatchDownload, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicListContent(
    viewModel: ComicListViewModel,
    title: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit,
    onBatchDownload: (List<ComicSimple>) -> Unit,
    modifier: Modifier,
) {
    val state by viewModel.pager.state.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = selection.isNotEmpty(),
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "listTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(
                        count = selection.size,
                        onClear = { selection = emptyList() },
                        onSelectAll = { selection = state.comics.map(ComicSimple::id) },
                        onDownload = {
                            val chosen = selection.toSet()
                            onBatchDownload(state.comics.filter { it.id in chosen })
                            selection = emptyList()
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            app.picacgmd.core.designsystem.component.BackButton(onBack)
                        },
                        actions = {
                            IconButton(onClick = { state.comics.firstOrNull()?.let { selection = listOf(it.id) } }, enabled = state.comics.isNotEmpty()) {
                                Icon(Icons.Outlined.Checklist, stringResource(R.string.comics_select))
                            }
                            LayoutMenuButton(settings.listLayout, viewModel::setLayout)
                            RouteMenuButton()
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
    ) { padding ->
        ComicResults(
            state = state,
            layout = settings.listLayout,
            columnCount = settings.listColumnCount,
            paginationMode = settings.paginationMode,
            selection = selection.toSet(),
            onSelectionChange = { selection = it.toList() },
            onComicClick = onComicClick,
            onLoadNext = viewModel.pager::loadNext,
            onRetryAppend = viewModel.pager::retryAppend,
            onRetry = viewModel.pager::reload,
            onJumpTo = viewModel.pager::jumpTo,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            header = {
                Column {
                    SortChips(sort, viewModel::setSort)
                    if (state.page > 0) {
                        Text(
                            foundLabel(state.total, state.hiddenCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
fun SortChips(sort: ComicSort, onSort: (ComicSort) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ComicSort.entries.forEach { option ->
            FilterChip(
                selected = option == sort,
                onClick = { onSort(option) },
                label = { Text(stringResource(option.labelRes())) },
            )
        }
    }
}

@Composable
fun LayoutMenuButton(layout: ListLayout, onLayout: (ListLayout) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(layout.icon(), stringResource(R.string.comics_layout))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        LayoutMenuItems(layout) { open = false; onLayout(it) }
    }
}

/** The layout choices, also embedded in the search overflow menu. */
@Composable
fun LayoutMenuItems(layout: ListLayout, onLayout: (ListLayout) -> Unit) {
    ListLayout.entries.forEach { option ->
        DropdownMenuItem(
            text = { Text(stringResource(option.labelRes())) },
            leadingIcon = { Icon(option.icon(), null) },
            onClick = { onLayout(option) },
            trailingIcon = if (option == layout) {
                { Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
        )
    }
}

private fun ListLayout.icon() = when (this) {
    ListLayout.DETAILS -> Icons.Outlined.ViewAgenda
    ListLayout.COVER -> Icons.Outlined.GridView
    ListLayout.COVER_AND_TITLE -> Icons.Outlined.ViewModule
}

/** Contextual app bar of multi-select: 「已选 N 本」, select all, batch download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClear: () -> Unit, onSelectAll: () -> Unit, onDownload: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.comics_selected_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, stringResource(R.string.comics_clear_selection)) }
        },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.comics_select_all)) }
            IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, stringResource(R.string.comics_batch_download)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}
