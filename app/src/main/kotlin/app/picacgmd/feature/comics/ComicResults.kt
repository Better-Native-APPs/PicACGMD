package app.picacgmd.feature.comics

import app.picacgmd.core.designsystem.window.secureDialogProperties

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import app.picacgmd.core.designsystem.component.ExpressivePullToRefreshBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.R
import app.picacgmd.core.data.settings.ListLayout
import app.picacgmd.core.data.settings.FailedReloadAction
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.designsystem.component.ComicCoverCard
import app.picacgmd.core.designsystem.component.ComicCoverAspectRatio
import app.picacgmd.core.designsystem.component.ComicListItem
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.SkeletonBlock
import app.picacgmd.core.designsystem.component.rememberCoverGridState
import app.picacgmd.core.designsystem.component.StatusPane
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.ComicSort
import app.picacgmd.core.network.PicaImage
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.flow.distinctUntilChanged

/** Resolves a PicACG image to a URL on the configured image server. */
@Composable
fun rememberImageUrl(image: PicaImage?): String? {
    val provider = LocalAppContainer.current.networkConfigProvider
    return remember(image) { image?.takeIf { it.path.isNotEmpty() }?.let(provider::imageUrl) }
}

/** 12.4k-style counts for the dense meta rows. */
fun formatCount(value: Long): String = when {
    value >= 10_000_000 -> "%.0fm".format(value / 1_000_000.0)
    value >= 1_000_000 -> "%.1fm".format(value / 1_000_000.0)
    value >= 10_000 -> "%.0fk".format(value / 1_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

fun ComicSort.labelRes(): Int = when (this) {
    ComicSort.DEFAULT -> R.string.comics_sort_default
    ComicSort.NEWEST -> R.string.comics_sort_newest
    ComicSort.OLDEST -> R.string.comics_sort_oldest
    ComicSort.MOST_LIKED -> R.string.comics_sort_liked
    ComicSort.MOST_VIEWED -> R.string.comics_sort_viewed
}

fun ListLayout.labelRes(): Int = when (this) {
    ListLayout.DETAILS -> R.string.comics_layout_details
    ListLayout.COVER -> R.string.comics_layout_cover
    ListLayout.COVER_AND_TITLE -> R.string.comics_layout_cover_title
}

/** 「找到 N 本 · 已隐藏 M 本」 */
@Composable
fun foundLabel(total: Int, hidden: Int): String =
    if (hidden > 0) stringResource(R.string.comics_found_hidden_count, total, hidden)
    else stringResource(R.string.comics_found_count, total)

/**
 * The shared result surface of boards 06 / 08 / 09: details list or cover grid, a full-span [header],
 * hidden-comic slots, infinite scroll or button paging, long-press multi-select, and the live 「已读」
 * mark on every comic with view history.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ComicResults(
    state: ComicPageState,
    layout: ListLayout,
    columnCount: Int,
    paginationMode: PaginationMode,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onComicClick: (String) -> Unit,
    onLoadNext: () -> Unit,
    onRetryAppend: () -> Unit,
    onRetry: () -> Unit,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberCoverGridState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    emptyTitle: String = stringResource(R.string.comics_empty_title),
    emptyDescription: String? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val preferences by LocalAppContainer.current.settingsRepository.settings.collectAsStateWithLifecycle(null)
    val pullToRetry = preferences?.failedReloadAction == FailedReloadAction.PULL_DOWN
    var jumpDialog by rememberSaveable { mutableStateOf(false) }
    val comicIds = remember(state.entries) { state.entries.mapNotNull { (it as? ComicEntry.Comic)?.comic?.id } }
    val readMarks = rememberReadMarks(comicIds)
    val selecting = selection.isNotEmpty()
    fun toggle(id: String) = onSelectionChange(if (id in selection) selection - id else selection + id)

    if (paginationMode == PaginationMode.INFINITE) {
        LaunchedEffect(gridState, state.entries.size) {
            snapshotFlow {
                val info = gridState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
            }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onLoadNext() }
        }
    }
    // A page jump starts from the top.
    LaunchedEffect(state.page, paginationMode) {
        if (paginationMode == PaginationMode.BUTTONS) gridState.scrollToItem(0)
    }

    // The one sign of a reload over a list that is already there, whether it was pulled, retried or
    // a page jump: this indicator. Nothing else is drawn for it.
    ExpressivePullToRefreshBox(
        isRefreshing = state.loading && state.entries.isNotEmpty(),
        onRefresh = { if (state.appendError != null) onRetryAppend() else onRetry() },
        modifier = modifier.fillMaxSize(),
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cells = when {
            columnCount > 0 && layout != ListLayout.DETAILS -> GridCells.Fixed(columnCount)
            layout == ListLayout.DETAILS -> if (maxWidth < 600.dp) GridCells.Fixed(1) else GridCells.Adaptive(380.dp)
            layout == ListLayout.COVER -> GridCells.Adaptive(104.dp)
            else -> GridCells.Adaptive(116.dp)
        }
        val gridPadding = if (layout == ListLayout.DETAILS) 0.dp else 12.dp
        LazyVerticalGrid(
            columns = cells,
            state = gridState,
            contentPadding = PaddingValues(
                start = gridPadding, end = gridPadding,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + if (paginationMode == PaginationMode.BUTTONS) 88.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (layout == ListLayout.DETAILS) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout == ListLayout.DETAILS) 0.dp else 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
            }
            when {
                state.loading && state.entries.isEmpty() -> skeleton(layout)
                state.error != null && state.entries.isEmpty() -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    if (pullToRetry) StatusPane(title = "加载失败", description = "${state.error}\n下拉重试")
                    else ErrorState(onRetry = onRetry, description = state.error, modifier = Modifier.clickable(onClick = onRetry))
                }
                state.isEmpty -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = if (state.hiddenCount > 0) stringResource(R.string.comics_empty_hidden) else emptyTitle,
                        description = emptyDescription,
                    )
                }
                else -> {
                    items(
                        state.entries,
                        key = { it.key },
                        span = { if (it is ComicEntry.Hidden) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        contentType = { it::class },
                    ) { entry ->
                        when (entry) {
                            is ComicEntry.Hidden -> HiddenSlot(entry.count)
                            is ComicEntry.Comic -> ComicCell(
                                comic = entry.comic,
                                layout = layout,
                                readChapter = readMarks.chapterOf(entry.comic.id),
                                selected = entry.comic.id in selection,
                                selecting = selecting,
                                onClick = { if (selecting) toggle(entry.comic.id) else onComicClick(entry.comic.id) },
                                onLongClick = { toggle(entry.comic.id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (paginationMode == PaginationMode.INFINITE) {
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                            AppendFooter(state, onRetryAppend, pullToRetry)
                        }
                    }
                }
            }
        }
        if (paginationMode == PaginationMode.BUTTONS && state.pages > 0) {
            PageBar(
                page = state.page,
                pages = state.pages,
                enabled = !state.loading,
                onJumpTo = onJumpTo,
                onOpenJump = { jumpDialog = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            )
        }
    }
    }
    if (jumpDialog) {
        JumpToPageDialog(
            pages = state.pages.coerceAtLeast(1),
            current = state.page,
            onDismiss = { jumpDialog = false },
            onJump = { jumpDialog = false; onJumpTo(it) },
        )
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.skeleton(layout: ListLayout) {
    items(12, key = { "sk-$it" }, contentType = { "skeleton" }) {
        if (layout == ListLayout.DETAILS) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SkeletonBlock(Modifier.width(84.dp).aspectRatio(ComicCoverAspectRatio))
                Spacer(Modifier.width(12.dp))
                SkeletonBlock(Modifier.fillMaxWidth().height(18.dp), shape = MaterialTheme.shapes.small)
            }
        } else {
            SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f / 1.4f))
        }
    }
}

@Composable
private fun ComicCell(
    comic: ComicSimple,
    layout: ListLayout,
    readChapter: Int?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cover = rememberImageUrl(comic.thumb)
    val mark = readMarkText(readChapter, comic.epsCount)
    when (layout) {
        ListLayout.DETAILS -> ComicListItem(
            title = comic.title,
            coverUrl = cover,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            subtitle = comic.author.takeIf { it.isNotBlank() },
            tags = comic.categories.take(3),
            finished = comic.finished,
            likes = formatCount(comic.likesCount.toLong()),
            pages = stringResource(R.string.comics_pages_eps, comic.pagesCount, comic.epsCount),
            progressLabel = mark?.label,
            progressDetail = mark?.detail,
            progressDetailShort = mark?.detailShort,
            selected = selected,
            trailingContent = if (selecting) {
                { SelectionMark(selected) }
            } else {
                null
            },
        )
        else -> Box(modifier) {
            ComicCoverCard(
                title = if (layout == ListLayout.COVER_AND_TITLE) comic.title else "",
                coverUrl = cover,
                onClick = onClick,
                onLongClick = onLongClick,
                author = if (layout == ListLayout.COVER_AND_TITLE) comic.author.takeIf { it.isNotBlank() } else null,
                badge = if (comic.finished) stringResource(R.string.comics_finished) else null,
                progressLabel = mark?.label,
                // While selecting, the bare 「已读」 keeps clear of the check in the opposite corner.
                progressDetail = mark?.detail?.takeUnless { selecting },
                progressDetailShort = mark?.detailShort?.takeUnless { selecting },
            )
            AnimatedVisibility(
                visible = selecting,
                enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
                exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) { SelectionMark(selected) }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(26.dp)
            .background(if (selected) colors.primary else colors.surface.copy(alpha = 0.85f), CircleShape)
            .border(BorderStroke(2.dp, if (selected) colors.primary else colors.outline), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** Board 09: the gap left by shielded comics, dashed-quiet rather than alarming. */
@Composable
private fun HiddenSlot(count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.comics_page_hidden, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppendFooter(state: ComicPageState, onRetry: () -> Unit, pullToRetry: Boolean) {
    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        when {
            state.appending -> LoadingIndicator()
            state.appendError != null -> if (pullToRetry) Text("加载失败，下拉重试")
                else TextButton(onClick = onRetry) { Text(stringResource(R.string.comics_load_more_failed)) }
            !state.hasNextPage && state.page > 0 -> Text(
                stringResource(R.string.comics_end_of_list),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Floating button-paging pill: ‹ 3 / 12 页 ›, tapping the label opens the jump dialog. */
@Composable
private fun PageBar(
    page: Int,
    pages: Int,
    enabled: Boolean,
    onJumpTo: (Int) -> Unit,
    onOpenJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onJumpTo(page - 1) }, enabled = enabled && page > 1) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.comics_prev_page))
            }
            Box(
                modifier = Modifier
                    .clickable(enabled = enabled, onClickLabel = stringResource(R.string.comics_jump_to_page), onClick = onOpenJump)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.comics_page_indicator, page, pages),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            FilledTonalIconButton(onClick = { onJumpTo(page + 1) }, enabled = enabled && page < pages) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.comics_next_page))
            }
        }
    }
}

@Composable
fun JumpToPageDialog(pages: Int, current: Int, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.takeIf { it > 0 }?.toString().orEmpty()) }
    val target = text.toIntOrNull()?.takeIf { it in 1..pages }
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comics_jump_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(6) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.comics_jump_hint, pages)) },
                supportingText = { Text(stringResource(R.string.comics_jump_hint, pages)) },
                isError = text.isNotEmpty() && target == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { target?.let(onJump) }, enabled = target != null) {
                Text(stringResource(R.string.comics_jump_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.comics_cancel)) } },
    )
}
