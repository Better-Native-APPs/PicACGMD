package app.picacgmd.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.FilteredPage
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.ComicSort
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comics.ComicPager
import app.picacgmd.feature.comics.ComicResults
import app.picacgmd.feature.comics.LayoutMenuButton
import app.picacgmd.feature.comics.SelectionTopBar
import app.picacgmd.feature.comics.foundLabel
import app.picacgmd.feature.comics.labelRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchFilter(val sort: ComicSort = ComicSort.DEFAULT, val categories: List<String> = emptyList()) {
    val isActive: Boolean get() = sort != ComicSort.DEFAULT || categories.isNotEmpty()
}

class SearchViewModel(private val container: AppContainer, initialKeyword: String?) : ViewModel() {
    private val repository = container.comicRepository
    private val mutableKeyword = MutableStateFlow(initialKeyword?.trim()?.takeIf { it.isNotEmpty() })
    /** The submitted keyword; null shows history and hot keywords. */
    val keyword: StateFlow<String?> = mutableKeyword.asStateFlow()
    private val mutableFilter = MutableStateFlow(SearchFilter())
    val filter: StateFlow<SearchFilter> = mutableFilter.asStateFlow()
    private val mutableHot = MutableStateFlow<List<String>?>(null)
    val hotKeywords: StateFlow<List<String>?> = mutableHot.asStateFlow()
    private val mutableCategories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = mutableCategories.asStateFlow()

    val history: StateFlow<List<String>> = repository.searchHistory
        .map { rows -> rows.sortedByDescending { it.updatedAt }.map { it.query } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val pager = ComicPager(viewModelScope) { page ->
        val query = mutableKeyword.value ?: return@ComicPager FilteredPage(emptyList(), 0, 1, 0, 0, 0)
        val current = mutableFilter.value
        repository.search(query, current.sort, page, current.categories)
    }

    init {
        viewModelScope.launch { mutableHot.value = runCatching { repository.hotKeywords() }.getOrDefault(emptyList()) }
        viewModelScope.launch { mutableCategories.value = runCatching { repository.categories().map { it.title } }.getOrDefault(emptyList()) }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { pager.mode = it.paginationMode }
        }
        if (mutableKeyword.value != null) pager.reload()
    }

    fun submit(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        mutableKeyword.value = trimmed
        pager.reload()
    }

    fun clearKeyword() { mutableKeyword.value = null }

    fun applyFilter(value: SearchFilter) {
        mutableFilter.value = value
        if (mutableKeyword.value != null) pager.reload()
    }

    fun deleteHistory(query: String) { viewModelScope.launch { repository.deleteSearchHistory(listOf(query)) } }
    fun clearHistory() { viewModelScope.launch { repository.clearSearchHistory() } }
    fun setLayout(value: app.picacgmd.core.data.settings.ListLayout) {
        viewModelScope.launch { container.settingsRepository.setListLayout(value) }
    }
}

/**
 * Board 06: search. Wire as `composable<SearchRoute> { SearchScreen(initialKeyword = it.keyword, …) }`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialKeyword: String?,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit,
    onBatchDownload: (List<ComicSimple>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "search:$initialKeyword") { SearchViewModel(container, initialKeyword) }
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val hot by viewModel.hotKeywords.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.pager.state.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf(initialKeyword.orEmpty()) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    fun submit(query: String) {
        text = query
        selection = emptyList()
        keyboard?.hide()
        viewModel.submit(query)
    }
    LaunchedEffect(Unit) { if (initialKeyword.isNullOrBlank()) focus.requestFocus() }

    Column(modifier.fillMaxSize()) {
        val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = selection.isNotEmpty(),
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "searchTopBar",
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
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.comics_back)) }
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        shape = CircleShape,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = if (text.isNotEmpty()) {
                            {
                                IconButton(onClick = { text = ""; viewModel.clearKeyword(); focus.requestFocus() }) {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.search_clear))
                                }
                            }
                        } else {
                            null
                        },
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                    )
                    BadgedBox(badge = { if (filter.isActive) Badge() }) {
                        IconButton(onClick = { showFilter = true }) { Icon(Icons.Outlined.Tune, stringResource(R.string.search_filter)) }
                    }
                    if (keyword != null) LayoutMenuButton(settings.listLayout, viewModel::setLayout)
                }
            }
        }

        if (keyword == null) {
            SearchSuggestions(
                history = history,
                hot = hot,
                onPick = ::submit,
                onDelete = viewModel::deleteHistory,
                onClear = viewModel::clearHistory,
            )
        } else {
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
                emptyTitle = stringResource(R.string.search_no_result),
                emptyDescription = stringResource(R.string.search_no_result_hint),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f),
                header = {
                    if (state.page > 0) {
                        Text(
                            foundLabel(state.total, state.hiddenCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                },
            )
        }
    }

    if (showFilter) {
        SearchFilterSheet(
            filter = filter,
            categories = categories,
            onDismiss = { showFilter = false },
            onApply = { showFilter = false; viewModel.applyFilter(it) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SearchSuggestions(
    history: List<String>,
    hot: List<String>?,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (history.isNotEmpty()) {
            SuggestionHeader(Icons.Outlined.History, stringResource(R.string.search_history)) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_history)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                history.take(30).forEach { query ->
                    InputChip(
                        selected = false,
                        onClick = { onPick(query) },
                        label = { Text(query) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                stringResource(R.string.search_delete_history, query),
                                modifier = Modifier.combinedClickable(onClick = { onDelete(query) }),
                            )
                        },
                    )
                }
            }
        }
        SuggestionHeader(Icons.Outlined.LocalFireDepartment, stringResource(R.string.search_hot))
        when {
            hot == null -> Unit
            hot.isEmpty() -> Text(
                stringResource(R.string.search_hot_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hot.forEachIndexed { index, word ->
                    val lead = index < 3
                    AssistChip(
                        onClick = { onPick(word) },
                        label = { Text(word) },
                        colors = if (lead) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                        border = if (lead) null else AssistChipDefaults.assistChipBorder(enabled = true),
                    )
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun SuggestionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        action?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheet(
    filter: SearchFilter,
    categories: List<String>,
    onDismiss: () -> Unit,
    onApply: (SearchFilter) -> Unit,
) {
    var sort by remember { mutableStateOf(filter.sort) }
    var chosen by remember { mutableStateOf(filter.categories.toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.search_filter), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.comics_sort), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComicSort.entries.forEach { option ->
                    FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(stringResource(option.labelRes())) })
                }
            }
            Text(stringResource(R.string.search_filter_categories), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.search_filter_categories_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { name ->
                    FilterChip(
                        selected = name in chosen,
                        onClick = { chosen = if (name in chosen) chosen - name else chosen + name },
                        label = { Text(name) },
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = { sort = ComicSort.DEFAULT; chosen = emptySet() }) {
                        Text(stringResource(R.string.search_filter_reset))
                    }
                    Button(onClick = { onApply(SearchFilter(sort, categories.filter { it in chosen })) }) {
                        Text(stringResource(R.string.search_filter_apply))
                    }
                }
            }
        }
    }
}
