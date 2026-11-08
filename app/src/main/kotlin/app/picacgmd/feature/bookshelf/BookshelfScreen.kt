package app.picacgmd.feature.bookshelf

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.core.data.db.LocalFavourite
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.downloads.rememberDownloadManager
import app.picacgmd.feature.settings.TextInputDialog
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
 * Bookshelf tab (boards 21–23): 收藏 / 本地 / 追更 / 记录 in one connected switcher, long-press
 * multi-select with a floating action toolbar, unlimited local folders and undoable removals.
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
    val folders by remember(container) { container.localFavouriteRepository.folders }.collectAsStateWithLifecycle(initialValue = emptyList())
    val favourites by remember(container) { container.localFavouriteRepository.favourites }
        .collectAsStateWithLifecycle(initialValue = null)
    val subscriptions by remember(container) { container.subscriptionRepository.subscriptions }
        .collectAsStateWithLifecycle(initialValue = null)
    val history by remember(container) { container.historyRepository.history }.collectAsStateWithLifecycle(initialValue = null)
    val checkState by container.subscriptionRepository.checkState.collectAsStateWithLifecycle()
    val unseen = rememberBookshelfUnseenCount()
    val batch by viewModel.batch.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    var viewName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selection = selectionList.toSet()
    var folderFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<ShelfDialog?>(null) }
    var menu by remember { mutableStateOf(false) }

    val current = settings ?: return
    val views = current.visibleShelfViews()
    val view = viewName?.let(ShelfView::valueOf)?.takeIf { it in views } ?: views.first()
    val selecting = selection.isNotEmpty()

    LaunchedEffect(view) { if (view == ShelfView.Online) viewModel.ensureOnline() }
    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.consumeMessage(message)
        snackbar.showSnackbar(message)
    }
    LaunchedEffect(folders, folderFilter) {
        if (folderFilter != null && folders.none { it.folderId == folderFilter }) folderFilter = null
    }
    BackHandler(enabled = selecting) { selectionList = emptyList() }

    fun setSelection(ids: Set<String>) {
        selectionList = ids.toList()
    }

    fun toggle(id: String) = setSelection(if (id in selection) selection - id else selection + id)

    fun selectedComics(): List<ComicSimple> = when (view) {
        ShelfView.Online -> viewModel.onlineComics(selection)
        ShelfView.Local -> favourites.orEmpty().filter { it.comicId in selection }.map { it.toComic() }
        ShelfView.Follow -> subscriptions.orEmpty().filter { it.comicId in selection }.map { it.toComic() }
        ShelfView.History -> history.orEmpty().filter { it.comicId in selection }.map { it.toComic() }
    }

    fun undoable(message: String, undo: () -> Unit) {
        scope.launch {
            val result = snackbar.showSnackbar(message, actionLabel = "撤销", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }

    fun removeLocal(items: List<LocalFavourite>) {
        if (items.isEmpty()) return
        viewModel.run("移除失败") { localFavouriteRepository.remove(items.map { it.comicId }) }
        undoable("已从本地收藏移除 ${items.size} 本") { viewModel.restoreLocal(items) }
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
                                    ShelfView.Local -> setSelection(
                                        favourites.orEmpty().filter { folderFilter == null || it.folderId == folderFilter }.map { it.comicId }.toSet(),
                                    )
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
                            if (view == ShelfView.Local || view == ShelfView.History) {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        if (view == ShelfView.Local) {
                                            DropdownMenuItem(
                                                text = { Text("新建文件夹") },
                                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                                onClick = { menu = false; dialog = ShelfDialog.CreateFolder(null) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("管理文件夹") },
                                                leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                                enabled = folders.isNotEmpty(),
                                                onClick = { menu = false; dialog = ShelfDialog.ManageFolders },
                                            )
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text("清空浏览记录") },
                                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                                enabled = !history.isNullOrEmpty(),
                                                onClick = { menu = false; dialog = ShelfDialog.ClearHistory },
                                            )
                                        }
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
                            settings = current,
                            folders = folders,
                            favourites = favourites,
                            folderFilter = folderFilter,
                            onFolderFilter = { folderFilter = it; selectionList = emptyList() },
                            onCreateFolder = { dialog = ShelfDialog.CreateFolder(null) },
                            selection = selection,
                            onToggle = ::toggle,
                            onOpenComic = onOpenComic,
                        )
                        ShelfView.Follow -> FollowView(
                            subscriptions = subscriptions,
                            checkState = checkState,
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
                            ShelfView.Local -> ToolbarAction(Icons.AutoMirrored.Outlined.DriveFileMove, "移动") {
                                dialog = ShelfDialog.PickFolder(move = true)
                            }
                            else -> ToolbarAction(Icons.Outlined.Folder, "存到文件夹") {
                                dialog = ShelfDialog.PickFolder(move = false)
                            }
                        }
                        when (view) {
                            ShelfView.Online -> ToolbarAction(Icons.Outlined.BookmarkRemove, "取消收藏", destructive = true) {
                                dialog = ShelfDialog.ConfirmUnfavourite(selection.toList())
                            }
                            ShelfView.Local -> ToolbarAction(Icons.Outlined.Delete, "移除", destructive = true) {
                                removeLocal(favourites.orEmpty().filter { it.comicId in selection })
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
        is ShelfDialog.CreateFolder -> TextInputDialog(
            title = "新建文件夹",
            label = "名称",
            initial = "",
            validate = { name ->
                when {
                    name.isBlank() -> "请填写名称"
                    folders.any { it.name == name.trim() } -> "已经有同名文件夹"
                    else -> null
                }
            },
            onSave = { name ->
                val pending = active.thenSave
                viewModel.run("新建失败") {
                    val id = localFavouriteRepository.createFolder(name.trim())
                    if (pending != null) {
                        if (pending.move) localFavouriteRepository.move(pending.ids, id)
                        else pending.comics.forEach { localFavouriteRepository.add(it, id) }
                    }
                }
                if (pending != null) selectionList = emptyList()
            },
            onDismiss = { dialog = null },
        )
        is ShelfDialog.PickFolder -> FolderPickerDialog(
            title = if (active.move) "移动到文件夹" else "存到文件夹",
            folders = folders,
            exclude = if (active.move) folderFilter else null,
            onPick = { folder ->
                if (active.move) {
                    val ids = selection.toList()
                    viewModel.run("移动失败", "已移动 ${ids.size} 本到「${folder.name}」") { localFavouriteRepository.move(ids, folder.folderId) }
                } else {
                    viewModel.copyToFolder(selectedComics(), folder.folderId, folder.name)
                }
                selectionList = emptyList()
                dialog = null
            },
            onCreate = {
                dialog = ShelfDialog.CreateFolder(
                    PendingSave(move = active.move, ids = selection.toList(), comics = if (active.move) emptyList() else selectedComics()),
                )
            },
            onDismiss = { dialog = null },
        )
        is ShelfDialog.ConfirmUnfavourite -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("取消收藏 ${active.ids.size} 本？") },
            text = {
                Text(
                    if (current.autoDeleteDownloadOnUnfavourite) "会从哔咔账号的收藏里移除，已下载的内容也会一起删除。"
                    else "会从哔咔账号的收藏里移除，本地收藏和下载不受影响。",
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
            onDismissRequest = { dialog = null },
            title = { Text("清空浏览记录？") },
            text = { Text("所有阅读进度都会被删除，不能撤销。收藏和下载不受影响。") },
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
        ShelfDialog.ManageFolders -> ManageFoldersSheet(
            folders = folders,
            counts = favourites.orEmpty().groupingBy { it.folderId }.eachCount(),
            onRename = { dialog = ShelfDialog.RenameFolder(it) },
            onDelete = { folder ->
                val count = favourites.orEmpty().count { it.folderId == folder.folderId }
                if (count == 0) {
                    viewModel.run("删除失败", "已删除「${folder.name}」") { localFavouriteRepository.deleteEmptyFolder(folder.folderId) }
                } else {
                    dialog = ShelfDialog.DeleteFolder(folder, count)
                }
            },
            onReorder = { ids -> viewModel.run("排序失败") { localFavouriteRepository.reorderFolders(ids) } },
            onDismiss = { dialog = null },
        )
        is ShelfDialog.RenameFolder -> TextInputDialog(
            title = "重命名文件夹",
            label = "名称",
            initial = active.folder.name,
            validate = { name ->
                when {
                    name.isBlank() -> "请填写名称"
                    folders.any { it.name == name.trim() && it.folderId != active.folder.folderId } -> "已经有同名文件夹"
                    else -> null
                }
            },
            onSave = { name -> viewModel.run("重命名失败") { localFavouriteRepository.renameFolder(active.folder.folderId, name.trim()) } },
            onDismiss = { dialog = ShelfDialog.ManageFolders },
        )
        is ShelfDialog.DeleteFolder -> DeleteFolderDialog(
            folder = active.folder,
            count = active.count,
            others = folders.filter { it.folderId != active.folder.folderId },
            onMoveAndDelete = { target ->
                viewModel.run("删除失败", "已把 ${active.count} 本移到「${target.name}」并删除文件夹") {
                    localFavouriteRepository.moveAndDeleteFolder(active.folder.folderId, target.folderId)
                }
                dialog = ShelfDialog.ManageFolders
            },
            onDeleteAll = {
                viewModel.run("删除失败", "已删除「${active.folder.name}」和其中 ${active.count} 本收藏") {
                    localFavouriteRepository.deleteFolderAndFavourites(active.folder.folderId)
                }
                dialog = ShelfDialog.ManageFolders
            },
            onDismiss = { dialog = ShelfDialog.ManageFolders },
        )
        null -> Unit
    }
}

internal data class PendingSave(val move: Boolean, val ids: List<String>, val comics: List<ComicSimple>)

internal sealed interface ShelfDialog {
    data class CreateFolder(val thenSave: PendingSave?) : ShelfDialog
    data class PickFolder(val move: Boolean) : ShelfDialog
    data class ConfirmUnfavourite(val ids: List<String>) : ShelfDialog
    data object ClearHistory : ShelfDialog
    data object ManageFolders : ShelfDialog
    data class RenameFolder(val folder: LocalFavouriteFolder) : ShelfDialog
    data class DeleteFolder(val folder: LocalFavouriteFolder, val count: Int) : ShelfDialog
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
            )
        }
    }
}

@Composable
private fun FolderPickerDialog(
    title: String,
    folders: List<LocalFavouriteFolder>,
    exclude: Long?,
    onPick: (LocalFavouriteFolder) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = folders.filter { it.folderId != exclude }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choices.isEmpty()) {
                    Text("还没有其他文件夹，先新建一个吧。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                choices.forEach { folder ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = false, role = Role.Button, onClick = { onPick(folder) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(folder.name, modifier = Modifier.padding(start = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onCreate),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("新建文件夹", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageFoldersSheet(
    folders: List<LocalFavouriteFolder>,
    counts: Map<Long, Int>,
    onRename: (LocalFavouriteFolder) -> Unit,
    onDelete: (LocalFavouriteFolder) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("管理文件夹", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                "文件夹数量不限。删除有内容的文件夹时，可以先把漫画移到别处。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEachIndexed { index, folder ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${counts[folder.folderId] ?: 0} 本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val ids = folders.map { it.folderId }
                        IconButton(onClick = { onReorder(ids.swap(index, index - 1)) }, enabled = index > 0) {
                            Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移")
                        }
                        IconButton(onClick = { onReorder(ids.swap(index, index + 1)) }, enabled = index < folders.lastIndex) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移")
                        }
                        IconButton(onClick = { onRename(folder) }) { Icon(Icons.Outlined.Edit, contentDescription = "重命名 ${folder.name}") }
                        IconButton(onClick = { onDelete(folder) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除 ${folder.name}", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> =
    if (b !in indices) this else toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

@Composable
private fun DeleteFolderDialog(
    folder: LocalFavouriteFolder,
    count: Int,
    others: List<LocalFavouriteFolder>,
    onMoveAndDelete: (LocalFavouriteFolder) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf(others.firstOrNull()) }
    var deleteContents by remember { mutableStateOf(others.isEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「${folder.name}」？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("这个文件夹里有 $count 本收藏。")
                if (others.isNotEmpty()) {
                    Text(
                        "移到：",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    others.forEach { other ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = !deleteContents && target == other,
                                    role = Role.RadioButton,
                                    onClick = { target = other; deleteContents = false },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = !deleteContents && target == other, onClick = null)
                            Text(other.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = deleteContents, role = Role.RadioButton, onClick = { deleteContents = true }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = deleteContents, onClick = null)
                    Text("连同 $count 本收藏一起删除", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = target
                    if (deleteContents || chosen == null) onDeleteAll() else onMoveAndDelete(chosen)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("删除文件夹") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
