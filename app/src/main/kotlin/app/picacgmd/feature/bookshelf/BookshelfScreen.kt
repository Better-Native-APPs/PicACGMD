package app.picacgmd.feature.bookshelf

import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.designsystem.window.securePopupProperties

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.core.data.db.LocalFavouriteEntry
import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.domain.repository.toSimple
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.downloads.rememberDownloadManager
import app.picacgmd.feature.comics.ComicListScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Unseen 追更 count for the bookshelf navigation badge (0 when 追更 is turned off). */
@Composable
fun rememberBookshelfUnseenCount(): Int {
    val container = LocalAppContainer.current
    val count by remember(container) { container.subscriptionRepository.unseenCount }
        .collectAsStateWithLifecycle(initialValue = 0)
    return count
}

/**
 * Bookshelf tab (boards 21–23): 收藏 / 本地收藏 / 追更 / 记录 in one connected switcher, long-press
 * multi-select with a floating action toolbar and undoable history/subscription removals.
 *
 * [onContinueReading] receives a 0-based [page] (history stores 1-based picture indexes).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookshelfScreen(
    onOpenComic: (comicId: String) -> Unit,
    onContinueReading: (comicId: String, episodeOrder: Int, page: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "bookshelf") { BookshelfViewModel(container) }
    val downloads = rememberDownloadManager()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val subscriptions by remember(container) { container.subscriptionRepository.subscriptions }
        .collectAsStateWithLifecycle(initialValue = null)
    val history by remember(container) { container.historyRepository.history }.collectAsStateWithLifecycle(initialValue = null)
    val unseen = rememberBookshelfUnseenCount()
    val batch by viewModel.batch.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val localFolders by viewModel.localFolders.collectAsStateWithLifecycle()
    val localComics by viewModel.localComics.collectAsStateWithLifecycle()
    val localFolder by viewModel.localFolder.collectAsStateWithLifecycle()
    val localNewestFirst by viewModel.localNewestFirst.collectAsStateWithLifecycle()
    val favouriteTags by viewModel.favouriteTags.collectAsStateWithLifecycle()

    var viewName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selection = selectionList.toSet()
    var dialog by remember { mutableStateOf<ShelfDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var newLocalFolder by rememberSaveable { mutableStateOf(false) }
    var manageLocalFolders by rememberSaveable { mutableStateOf(false) }
    var movingLocalComic by remember { mutableStateOf<LocalFavouriteEntry?>(null) }
    var tagFavouritesOpen by rememberSaveable { mutableStateOf(false) }
    var tagSearch by rememberSaveable { mutableStateOf<String?>(null) }

    val current = settings ?: return
    val views = current.visibleShelfViews()
    val view = views.firstOrNull { it.name == viewName } ?: views.first()
    val selecting = selection.isNotEmpty()

    LaunchedEffect(current.showTagFavourites) {
        if (!current.showTagFavourites) {
            tagFavouritesOpen = false
            tagSearch = null
        }
    }

    LaunchedEffect(view) {
        menu = false
        if (viewName != null && viewName != view.name) {
            viewName = view.name
            selectionList = emptyList()
        }
        if (view == ShelfView.Online) viewModel.ensureOnline()
    }
    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.consumeMessage(message)
        snackbar.showSnackbar(message)
    }
    BackHandler(enabled = selecting) { selectionList = emptyList() }

    fun setSelection(ids: Set<String>) {
        selectionList = ids.toList()
    }

    fun toggle(id: String) = setSelection(if (id in selection) selection - id else selection + id)

    fun selectedComics(): List<ComicSimple> = when (view) {
        ShelfView.Online -> viewModel.onlineComics(selection)
        ShelfView.Local -> localComics.orEmpty().filter { it.comicId in selection }.map { it.toSimple() }
        ShelfView.Follow -> subscriptions.orEmpty().filter { it.comicId in selection }.map { it.toComic() }
        ShelfView.History -> history.orEmpty().filter { it.comicId in selection }.map { it.toComic() }
    }

    fun undoable(message: String, undo: () -> Unit) {
        scope.launch {
            val result = snackbar.showSnackbar(message, actionLabel = "撤销", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }

    fun removeHistory(items: List<ViewHistory>) {
        if (items.isEmpty()) return
        viewModel.run("删除失败") { historyRepository.delete(items.map { it.comicId }) }
        undoable("已删除 ${items.size} 条记录") { viewModel.restoreHistory(items) }
    }

    fun unsubscribe(items: List<Subscription>) {
        if (items.isEmpty()) return
        viewModel.run("取消追更失败") { subscriptionRepository.unsubscribe(items.map { it.comicId }) }
        undoable("已取消追更 ${items.size} 本") { viewModel.restoreSubscriptions(items) }
    }

    fun removeLocal(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModel.removeLocal(ids) { removed ->
            undoable("已移出 ${ids.distinct().size} 本本地收藏") { viewModel.restoreLocal(removed) }
        }
    }

    fun enqueueDownloads(comics: List<ComicSimple>) {
        if (comics.isEmpty()) return
        scope.launch {
            val message = try {
                downloads.enqueueBatch(comics)
                "已加入下载 ${comics.size} 本"
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                "加入下载失败：${exception.message ?: "请重试"}"
            }
            snackbar.showSnackbar(message)
        }
    }

    // This secondary tag list reuses the existing comic-list screen without changing app routing.
    val openedTag = tagSearch
    if (openedTag != null && current.showTagFavourites) {
        fun closeTagSearch() { tagSearch = null; tagFavouritesOpen = true }
        BackHandler(onBack = ::closeTagSearch)
        Box(modifier.fillMaxSize()) {
            ComicListScreen(
                category = null,
                tag = openedTag,
                author = null,
                onBack = ::closeTagSearch,
                onComicClick = onOpenComic,
                onBatchDownload = ::enqueueDownloads,
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        return
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(targetState = selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "shelfTopBar") { selectMode ->
                if (selectMode) {
                    TopAppBar(
                        title = { Text("已选 ${selection.size} 本") },
                        navigationIcon = {
                            IconButton(onClick = { selectionList = emptyList() }) { Icon(Icons.Outlined.Close, contentDescription = "退出多选") }
                        },
                        actions = {
                            IconButton(onClick = {
                                when (view) {
                                    ShelfView.Online -> viewModel.selectAllOnline { setSelection(it) }
                                    ShelfView.Local -> setSelection(localComics.orEmpty().map { it.comicId }.toSet())
                                    ShelfView.Follow -> setSelection(subscriptions.orEmpty().map { it.comicId }.toSet())
                                    ShelfView.History -> setSelection(history.orEmpty().map { it.comicId }.toSet())
                                }
                            }) { Icon(Icons.Outlined.SelectAll, contentDescription = "全选") }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text("书架") },
                        scrollBehavior = scrollBehavior,
                        actions = {
                            if (current.showTagFavourites) {
                                IconButton(onClick = { tagFavouritesOpen = true }) { Icon(Icons.Outlined.Label, "标签收藏") }
                            }
                            if (view == ShelfView.Local) {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = securePopupProperties(focusable = true)) {
                                        DropdownMenuItem(
                                            text = { Text("管理收藏夹") },
                                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                            enabled = localFolders != null,
                                            onClick = { menu = false; manageLocalFolders = true },
                                        )
                                    }
                                }
                            }
                            if (view == ShelfView.History) {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = securePopupProperties(focusable = true)) {
                                        DropdownMenuItem(
                                            text = { Text("清空浏览记录") },
                                            leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                            enabled = !history.isNullOrEmpty(),
                                            onClick = { menu = false; dialog = ShelfDialog.ClearHistory },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.fillMaxSize()) {
                if (views.size > 1) {
                    ViewSwitcher(
                        options = views,
                        selected = view,
                        onSelect = {
                            viewName = it.name
                            selectionList = emptyList()
                        },
                        label = { it.label },
                        badge = { if (it == ShelfView.Follow) unseen else 0 },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                AnimatedContent(
                    targetState = view,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "shelfView",
                    modifier = Modifier.weight(1f),
                ) { shown ->
                    when (shown) {
                        ShelfView.Online -> OnlineFavouritesView(
                            viewModel = viewModel,
                            settings = current,
                            selection = selection,
                            onSelectionChange = ::setSelection,
                            onOpenComic = onOpenComic,
                        )
                        ShelfView.Local -> LocalFavouritesView(
                            folders = localFolders,
                            comics = localComics,
                            folderId = localFolder,
                            newestFirst = localNewestFirst,
                            selection = selection,
                            onFolderSelect = { viewModel.selectLocalFolder(it); selectionList = emptyList() },
                            onNewFolder = { newLocalFolder = true },
                            onSort = viewModel::setLocalNewestFirst,
                            onToggle = ::toggle,
                            onOpenComic = onOpenComic,
                            onMove = { movingLocalComic = it },
                            onRemove = ::removeLocal,
                        )
                        ShelfView.Follow -> FollowView(
                            subscriptions = subscriptions,
                            onRefresh = { viewModel.run("检查更新失败") { subscriptionRepository.refresh() } },
                            selection = selection,
                            onToggle = ::toggle,
                            onOpenComic = { id ->
                                viewModel.run("操作失败") { subscriptionRepository.markSeen(id) }
                                onOpenComic(id)
                            },
                        )
                        ShelfView.History -> HistoryView(
                            history = history,
                            selection = selection,
                            onToggle = ::toggle,
                            onOpenComic = onOpenComic,
                            onContinue = { entry ->
                                if (entry.lastEpOrder >= 1 && entry.lastPictureIndex >= 1) {
                                    onContinueReading(entry.comicId, entry.lastEpOrder, entry.lastPictureIndex - 1)
                                } else {
                                    onOpenComic(entry.comicId)
                                }
                            },
                            onDelete = { removeHistory(listOf(it)) },
                            onPin = { entry, pinned -> viewModel.run("置顶失败") { historyRepository.setPinned(entry.comicId, pinned) } },
                        )
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(visible = batch != null, enter = fadeIn(), exit = fadeOut()) {
                    batch?.let { BatchCard(it, Modifier.padding(bottom = 12.dp)) }
                }
                AnimatedVisibility(
                    visible = selecting,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    HorizontalFloatingToolbar(expanded = true) {
                        ToolbarAction(Icons.Outlined.Download, "下载") {
                            enqueueDownloads(selectedComics())
                            selectionList = emptyList()
                        }
                        when (view) {
                            ShelfView.Online -> ToolbarAction(Icons.Outlined.BookmarkRemove, "取消收藏", destructive = true) {
                                dialog = ShelfDialog.ConfirmUnfavourite(selection.toList())
                            }
                            ShelfView.Local -> ToolbarAction(Icons.Outlined.BookmarkRemove, "移出收藏", destructive = true) {
                                removeLocal(selection.toList())
                                selectionList = emptyList()
                            }
                            ShelfView.Follow -> ToolbarAction(Icons.Outlined.NotificationsOff, "取消追更", destructive = true) {
                                unsubscribe(subscriptions.orEmpty().filter { it.comicId in selection })
                                selectionList = emptyList()
                            }
                            ShelfView.History -> ToolbarAction(Icons.Outlined.Delete, "删除", destructive = true) {
                                removeHistory(history.orEmpty().filter { it.comicId in selection })
                                selectionList = emptyList()
                            }
                        }
                    }
                }
            }
        }
    }

    when (val active = dialog) {
        is ShelfDialog.ConfirmUnfavourite -> AlertDialog(
            properties = secureDialogProperties(),
            onDismissRequest = { dialog = null },
            title = { Text("取消收藏 ${active.ids.size} 本？") },
            text = {
                Text(
                    if (current.autoDeleteDownloadOnUnfavourite) "移出哔咔账号收藏，已下载的文件会一并删除"
                    else "移出哔咔账号收藏，下载不受影响",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.unfavourite(active.ids, current.autoDeleteDownloadOnUnfavourite) { downloads.delete(it) }
                        selectionList = emptyList()
                        dialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("取消收藏") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("保留") } },
        )
        ShelfDialog.ClearHistory -> AlertDialog(
            properties = secureDialogProperties(),
            onDismissRequest = { dialog = null },
            title = { Text("清空浏览记录？") },
            text = { Text("阅读进度会一并删除，无法撤销") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.run("清空失败", "已清空浏览记录") { historyRepository.clear() }
                        dialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        null -> Unit
    }
    if (newLocalFolder) LocalFolderNameDialog(
        title = "新建收藏夹",
        onDismiss = { newLocalFolder = false },
        onConfirm = { name -> viewModel.selectLocalFolder(viewModel.createLocalFolder(name)) },
    )
    if (manageLocalFolders && localFolders != null) LocalFolderManagerSheet(
        folders = localFolders.orEmpty(),
        onDismiss = { manageLocalFolders = false },
        onCreateFolder = viewModel::createLocalFolder,
        onRenameFolder = viewModel::renameLocalFolder,
        onDeleteFolder = viewModel::deleteLocalFolder,
    )
    movingLocalComic?.let { entry ->
        LocalFavouritePickerSheet(
            folders = localFolders.orEmpty(),
            selectedFolderId = entry.folderId,
            title = "移动到…",
            onDismiss = { movingLocalComic = null },
            onCreateFolder = viewModel::createLocalFolder,
            onSelectFolder = { viewModel.moveLocalComic(entry.comicId, it) },
        )
    }
    if (tagFavouritesOpen && current.showTagFavourites) TagFavouritesSheet(
        tags = favouriteTags,
        onDismiss = { tagFavouritesOpen = false },
        onOpenTag = { tag -> tagFavouritesOpen = false; tagSearch = tag },
        onRemoveTag = viewModel::removeFavouriteTag,
    )
}

internal sealed interface ShelfDialog {
    data class ConfirmUnfavourite(val ids: List<String>) : ShelfDialog
    data object ClearHistory : ShelfDialog
}

/** Icon over a short label: the floating toolbar must stay readable without tooltips. */
@Composable
private fun ToolbarAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .widthIn(min = 64.dp)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BatchCard(progress: BatchProgress, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 260.dp, max = 360.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("${progress.label} ${progress.done}/${progress.total}", style = MaterialTheme.typography.bodyMedium)
            LinearWavyProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                color = MaterialTheme.colorScheme.inversePrimary,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                // Still wave: a batch walks every favourite one request at a time and can take
                // minutes; a moving wave would redraw every frame until it ends.
                waveSpeed = 0.dp,
            )
        }
    }
}
