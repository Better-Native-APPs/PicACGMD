package app.picacgmd.feature.categories

import app.picacgmd.feature.routing.RouteMenuButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.designsystem.component.AppSwitch
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.component.SectionHeader
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.ALL_COMICS_CATEGORY
import app.picacgmd.core.domain.repository.homeCategoryCatalogue
import app.picacgmd.core.network.Category
import app.picacgmd.feature.discover.ALL_COMICS_THUMB_URL
import app.picacgmd.feature.discover.LoadFailure
import app.picacgmd.feature.discover.LoadState
import app.picacgmd.feature.discover.appViewModel
import app.picacgmd.feature.discover.loadCatching
import app.picacgmd.feature.discover.rememberImageUrl
import app.picacgmd.feature.rankings.BackButton
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Same aliasing the home model uses: the default pin is spelled two ways by the server. */
private fun categoryKey(name: String): String = when (val trimmed = name.trim()) {
    "大湿推荐", "大濕推薦", "大濕推荐", "大湿推薦" -> "大湿推荐"
    else -> trimmed
}

class CategoriesViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.settingsRepository
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT)
    var categories by mutableStateOf<LoadState<List<Category>>>(LoadState.Loading)
        private set

    init { load() }

    fun load() {
        categories = LoadState.Loading
        viewModelScope.launch {
            categories = loadCatching { homeCategoryCatalogue(container.comicRepository.categories()) }
        }
    }

    fun setPinned(name: String, pinned: Boolean) = viewModelScope.launch {
        val current = settings.value.pinnedHomeCategories.filterNot { categoryKey(it) == categoryKey(name) }
        repository.setPinnedHomeCategories(if (pinned) current + name else current)
    }

    fun setVisible(name: String, visible: Boolean) = viewModelScope.launch {
        val current = settings.value.hiddenCategories.filterNot { categoryKey(it) == categoryKey(name) }
        repository.setHiddenCategories(if (visible) current else current + name)
    }

    fun setColumnCount(value: Int) = viewModelScope.launch { repository.setCategoryColumnCount(value) }
    fun reorderPinned(names: List<String>) = viewModelScope.launch { repository.setPinnedHomeCategories(names) }
    fun reorderCategories(names: List<String>) = viewModelScope.launch { repository.setCategoryOrder(names) }
}

/**
 * 「分类管理」 (boards 04 §0 / 26): one page for both upstream screens. A 「每行列数」 connected
 * ButtonGroup sits on top, then drag-to-reorder pinned shortcuts and the home grid (with move
 * up / move down accessibility actions on each drag handle), 固定到首页 pin toggles and per-category
 * visibility switches. Reached from the home grid and from 设置 → 浏览设置. Wire into `CategoriesRoute`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onOpenAllComics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = appViewModel { CategoriesViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categories_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = { RouteMenuButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val state = viewModel.categories) {
                LoadState.Loading -> LoadingState(Modifier.fillMaxSize())
                is LoadState.Failed -> LoadFailure(state, onRetry = viewModel::load)
                is LoadState.Ready -> ManageContent(
                    categories = state.data,
                    settings = settings,
                    onPin = viewModel::setPinned,
                    onVisible = viewModel::setVisible,
                    onReorderPinned = viewModel::reorderPinned,
                    onReorderCategories = viewModel::reorderCategories,
                    onColumnCount = viewModel::setColumnCount,
                    onOpenAllComics = onOpenAllComics,
                )
            }
        }
    }
}

private val RowHeight = 64.dp

/** 自动 (0) or a fixed 3–5 columns for the home category grid; the repository also allows 2 / 6 / 7. */
private val ColumnCountOptions = listOf(0, 3, 4, 5)

@Composable
private fun ManageContent(
    categories: List<Category>,
    settings: AppSettings,
    onPin: (String, Boolean) -> Unit,
    onVisible: (String, Boolean) -> Unit,
    onReorderPinned: (List<String>) -> Unit,
    onReorderCategories: (List<String>) -> Unit,
    onColumnCount: (Int) -> Unit,
    onOpenAllComics: () -> Unit,
) {
    val byKey = remember(categories) { categories.associateBy { categoryKey(it.title) } }
    val pinned = remember(settings.pinnedHomeCategories, byKey) {
        settings.pinnedHomeCategories.mapNotNull { byKey[categoryKey(it)] }.distinctBy { categoryKey(it.title) }
    }
    val pinnedKeys = pinned.map { categoryKey(it.title) }.toSet()
    val hiddenKeys = settings.hiddenCategories.map(::categoryKey).toSet()
    val ordered = remember(categories, settings.categoryOrder) {
        val order = settings.categoryOrder.map(::categoryKey).withIndex().associate { it.value to it.index }
        categories.filterNot { it.id == ALL_COMICS_CATEGORY.id }
            .sortedBy { order[categoryKey(it.title)] ?: Int.MAX_VALUE }
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        SectionHeader(title = stringResource(R.string.categories_columns_title))
        Caption(stringResource(R.string.categories_columns_hint))
        SectionCard {
            ColumnCountGroup(
                selected = settings.categoryColumnCount,
                onSelect = onColumnCount,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        SectionHeader(title = stringResource(R.string.categories_pinned_title))
        Caption(stringResource(R.string.categories_pinned_hint))
        SectionCard {
            if (pinned.isEmpty()) {
                Text(
                    text = stringResource(R.string.categories_pinned_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
            } else {
                ReorderableColumn(
                    items = pinned,
                    keyOf = { categoryKey(it.title) },
                    nameOf = { it.title },
                    itemHeight = RowHeight,
                    onMoved = { list -> onReorderPinned(list.map { it.title }) },
                ) { category, handle ->
                    CategoryRow(
                        category = category,
                        handle = handle,
                        onClick = if (category.id == ALL_COMICS_CATEGORY.id) onOpenAllComics else null,
                    ) {
                        PinToggle(pinned = true, name = category.title, onPin = onPin)
                    }
                }
            }
        }
        SectionHeader(title = stringResource(R.string.categories_all_title))
        Caption(stringResource(R.string.categories_all_hint))
        SectionCard {
            CategoryRow(category = ALL_COMICS_CATEGORY, handle = null, onClick = onOpenAllComics) {
                PinToggle(pinned = categoryKey(ALL_COMICS_CATEGORY.title) in pinnedKeys, name = ALL_COMICS_CATEGORY.title, onPin = onPin)
            }
            ReorderableColumn(
                items = ordered,
                keyOf = { categoryKey(it.title) },
                nameOf = { it.title },
                itemHeight = RowHeight,
                onMoved = { list -> onReorderCategories(list.map { it.title }) },
            ) { category, handle ->
                val key = categoryKey(category.title)
                CategoryRow(category = category, handle = handle, dimmed = key in hiddenKeys) {
                    PinToggle(pinned = key in pinnedKeys, name = category.title, onPin = onPin)
                    val showLabel = stringResource(R.string.categories_show_on_home, category.title)
                    AppSwitch(
                        checked = key !in hiddenKeys,
                        onCheckedChange = { onVisible(category.title, it) },
                        modifier = Modifier.semantics { contentDescription = showLabel },
                    )
                }
            }
        }
    }
}

/** Connected single-choice ButtonGroup (M3 Expressive): 自动 / 3 / 4 / 5 columns for the grid. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnCountGroup(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ColumnCountOptions.forEachIndexed { index, value ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ColumnCountOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = if (value == 0) {
                        stringResource(R.string.categories_columns_auto)
                    } else {
                        stringResource(R.string.categories_columns_count, value)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun PinToggle(pinned: Boolean, name: String, onPin: (String, Boolean) -> Unit) {
    val label = stringResource(
        if (pinned) R.string.categories_unpin else R.string.categories_pin,
        name,
    )
    IconToggleButton(checked = pinned, onCheckedChange = { onPin(name, it) }) {
        Icon(
            imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = label,
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    handle: Modifier?,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(RowHeight).padding(end = 8.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle != null) {
            Box(modifier = handle.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            val url = if (category.id == ALL_COMICS_CATEGORY.id) ALL_COMICS_THUMB_URL else rememberImageUrl(category.thumb)
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = category.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            trailing()
        }
    }
}

/**
 * Fixed-height rows reordered by dragging the handle; the dragged row lifts above its neighbours and
 * swaps once it travels half a row. [onMoved] fires once, when the finger lifts.
 *
 * The handle is also the non-drag path (audit §A4): it carries 「上移」/「下移」 accessibility actions
 * that move one step, persist through [onMoved] and announce the new position for TalkBack, so the
 * order can be changed without a drag gesture.
 */
@Composable
private fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    nameOf: (T) -> String,
    itemHeight: Dp,
    onMoved: (List<T>) -> Unit,
    row: @Composable (item: T, handle: Modifier) -> Unit,
) {
    var order by remember(items) { mutableStateOf(items) }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var movedKey by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var moved by remember { mutableStateOf(false) }
    val heightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val haptics = LocalHapticFeedback.current
    val latestOnMoved by rememberUpdatedState(onMoved)
    val moveUpLabel = stringResource(R.string.categories_move_up)
    val moveDownLabel = stringResource(R.string.categories_move_down)
    Column {
        order.forEachIndexed { index, item ->
            val itemKey = keyOf(item)
            key(itemKey) {
                val dragging = draggingKey == itemKey
                val dragLabel = stringResource(R.string.categories_drag, nameOf(item))
                val position = stringResource(R.string.categories_position, index + 1, order.size)
                // 「上移」/「下移」 reorder the same way a drag does, then hand the new neighbour order
                // to [onMoved]; the moved handle is the only live region, so TalkBack reads its new
                // 「第 n 项，共 m 项」 once instead of every row that shifted.
                val move: (Int) -> Unit = { to ->
                    if (to in order.indices) {
                        order = order.toMutableList().apply { add(to, removeAt(index)) }
                        movedKey = itemKey
                        latestOnMoved(order)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
                val handle = Modifier
                    .pointerInput(itemKey) {
                        detectDragGestures(
                            onDragStart = {
                                draggingKey = itemKey
                                offset = 0f
                                moved = false
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragEnd = {
                                draggingKey = null
                                movedKey = null
                                offset = 0f
                                if (moved) latestOnMoved(order)
                                moved = false
                            },
                            onDragCancel = {
                                draggingKey = null
                                movedKey = null
                                offset = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            offset += amount.y
                            val current = order.indexOfFirst { keyOf(it) == itemKey }
                            if (offset > heightPx / 2 && current < order.lastIndex) {
                                order = order.toMutableList().apply { add(current + 1, removeAt(current)) }
                                offset -= heightPx
                                moved = true
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            } else if (offset < -heightPx / 2 && current > 0) {
                                order = order.toMutableList().apply { add(current - 1, removeAt(current)) }
                                offset += heightPx
                                moved = true
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                    }
                    .semantics {
                        contentDescription = dragLabel
                        stateDescription = position
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { move(index - 1); true })
                            if (index < order.lastIndex) add(CustomAccessibilityAction(moveDownLabel) { move(index + 1); true })
                        }
                    }
                    // Only the handle that just moved is a live region, so TalkBack reads its new
                    // 「第 n 项，共 m 项」 once instead of every row that shifted.
                    .then(
                        if (movedKey == itemKey) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    )
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) offset else 0f }
                        .then(
                            if (dragging) {
                                Modifier
                                    .shadow(6.dp, MaterialTheme.shapes.large)
                                    .clip(MaterialTheme.shapes.large)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Surface(color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent) {
                        row(item, handle)
                    }
                }
            }
        }
    }
}
