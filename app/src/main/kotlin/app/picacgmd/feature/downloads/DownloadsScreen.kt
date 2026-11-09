package app.picacgmd.feature.downloads

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.designsystem.component.ComicCover
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.StatusPane
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.core.domain.download.DownloadProgress
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.bookshelf.SelectionMark
import app.picacgmd.feature.bookshelf.ViewSwitcher
import app.picacgmd.feature.bookshelf.safeImageUrl
import app.picacgmd.feature.settings.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

private enum class DownloadTab(val label: String) { Active("进行中"), Done("已完成"), Failed("失败") }
private enum class DoneSort(val label: String) { Recent("最近下载"), Title("按名称"), Size("按大小") }

private val DownloadComic.tab: DownloadTab?
    get() = when (status) {
        DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED -> DownloadTab.Active
        DownloadStatus.DONE -> DownloadTab.Done
        DownloadStatus.FAILED -> DownloadTab.Failed
        DownloadStatus.DELETING -> null
    }

/** Reads the application-owned download manager (it survives process death for WorkManager). */
@Composable
internal fun rememberDownloadManager(): DownloadManager {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as DownloadManagerOwner).downloadManager }
}

/**
 * Downloads tab (boards 18 / 19): 进行中 / 已完成 / 失败 with an engine card, per-comic progress and
 * actions, plus multi-select deletion of finished comics. Downloaded comics open offline through
 * [onReadDownloaded]; [onOpenSettings] opens [DownloadSettingsScreen]; [onBrowse] is the empty-state
 * exit to 发现.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen(
    onReadDownloaded: (comicId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDetail: (comicId: String) -> Unit = {},
) {
    val manager = rememberDownloadManager()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val comics by remember(manager, query) { manager.observeQueue(search = query.trim()) }
        .collectAsStateWithLifecycle(initialValue = null)
    var tabName by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<List<DownloadComic>?>(null) }
    var menu by remember { mutableStateOf(false) }

    val all = comics.orEmpty()
    val byTab = remember(comics) { all.groupBy { it.tab } }
    val defaultTab = when {
        !byTab[DownloadTab.Active].isNullOrEmpty() -> DownloadTab.Active
        !byTab[DownloadTab.Failed].isNullOrEmpty() && byTab[DownloadTab.Done].isNullOrEmpty() -> DownloadTab.Failed
        else -> DownloadTab.Done
    }
    val tab = tabName?.let(DownloadTab::valueOf) ?: defaultTab
    val selecting = selection.isNotEmpty()

    fun act(failure: String = "操作失败", block: suspend DownloadManager.() -> Unit) {
        scope.launch {
            try {
                manager.block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                snackbar.showSnackbar("$failure：${exception.message ?: "请重试"}")
            }
        }
    }

    BackHandler(enabled = selecting) { selection = emptySet() }
    BackHandler(enabled = searching && !selecting) {
        searching = false
        query = ""
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = when {
                    selecting -> 2
                    searching -> 1
                    else -> 0
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "downloadsTopBar",
            ) { mode ->
                when (mode) {
                    2 -> TopAppBar(
                        title = { Text("已选 ${selection.size} 本") },
                        navigationIcon = {
                            IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Outlined.Close, contentDescription = "退出多选") }
                        },
                        actions = {
                            IconButton(onClick = {
                                selection = byTab[DownloadTab.Done].orEmpty().map { it.comicId }.toSet()
                            }) { Icon(Icons.Outlined.SelectAll, contentDescription = "全选") }
                            IconButton(onClick = {
                                pendingDelete = all.filter { it.comicId in selection }
                            }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
                        },
                    )
                    1 -> TopAppBar(
                        title = {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("搜索已下载漫画") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.extraLarge,
                                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focus),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "关闭搜索") }
                        },
                    )
                    else -> TopAppBar(
                        title = { Text("下载") },
                        scrollBehavior = scrollBehavior,
                        actions = {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, contentDescription = "搜索") }
                            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "下载设置") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("全部暂停") },
                                        leadingIcon = { Icon(Icons.Outlined.Pause, null) },
                                        enabled = !byTab[DownloadTab.Active].isNullOrEmpty(),
                                        onClick = { menu = false; act { pauseAll() } },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("全部继续") },
                                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                        enabled = byTab[DownloadTab.Active].orEmpty().any { it.status == DownloadStatus.PAUSED },
                                        onClick = { menu = false; act { resumeAll() } },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("全部重试") },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                        enabled = !byTab[DownloadTab.Failed].isNullOrEmpty(),
                                        onClick = { menu = false; act { retryFailed() } },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                comics == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                all.isEmpty() && query.isNotBlank() -> EmptyState(
                    title = "未找到「${query.trim()}」",
                    description = null,
                    icon = Icons.Outlined.Search,
                    shape = MaterialShapes.Clover8Leaf,
                    modifier = Modifier.fillMaxSize(),
                )
                all.isEmpty() -> DownloadsEmpty(onBrowse, Modifier.fillMaxSize())
                else -> {
                    ViewSwitcher(
                        options = DownloadTab.entries,
                        selected = tab,
                        onSelect = {
                            tabName = it.name
                            selection = emptySet()
                        },
                        label = { "${it.label} ${byTab[it].orEmpty().size}" },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
                        label = "downloadTab",
                        modifier = Modifier.weight(1f),
                    ) { current ->
                        val items = byTab[current].orEmpty()
                        val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)
                        when (current) {
                            DownloadTab.Active -> ActiveList(
                                items = items,
                                manager = manager,
                                contentPadding = bottom,
                                onPause = { id -> act { pause(id) } },
                                onResume = { id -> act { resume(id) } },
                                onRemove = { comic -> pendingDelete = listOf(comic) },
                                onPauseAll = { act { pauseAll() } },
                                onResumeAll = { act { resumeAll() } },
                            )
                            DownloadTab.Done -> DoneList(
                                items = items,
                                selection = selection,
                                contentPadding = bottom,
                                onToggle = { id -> selection = if (id in selection) selection - id else selection + id },
                                onOpen = onReadDownloaded,
                                onOpenDetail = onOpenDetail,
                                onDelete = { comic -> pendingDelete = listOf(comic) },
                            )
                            DownloadTab.Failed -> FailedList(
                                items = items,
                                contentPadding = bottom,
                                onRetry = { id -> act { retryFailed(id) } },
                                onRetryAll = { act { retryFailed() } },
                                onRemove = { comic -> pendingDelete = listOf(comic) },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { targets ->
        val finished = targets.all { it.status == DownloadStatus.DONE }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (targets.size == 1) "删除「${targets.first().title}」？" else "删除 ${targets.size} 本漫画？") },
            text = {
                Text(
                    if (finished) "已下载的文件会一并删除"
                    else "移出下载队列并删除已下载的文件",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = targets.map { it.comicId }
                        pendingDelete = null
                        selection = emptySet()
                        act("删除失败") { ids.forEach { delete(it) } }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadsEmpty(onBrowse: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        StatusPane(
            title = "还没有下载",
            description = "在详情页点「下载」",
            icon = Icons.Outlined.Download,
            shape = MaterialShapes.Sunny,
            actions = {
                Button(onClick = onBrowse) {
                    Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("去发现")
                }
            },
        )
    }
}

@Composable
private fun rememberCover(comic: DownloadComic): String? {
    val container = LocalAppContainer.current
    val manager = rememberDownloadManager()
    return remember(comic.comicId, comic.thumbPath, comic.status) {
        val local = runCatching { manager.files.coverFile(comic.comicId) }.getOrNull()
        if (local != null && local.isFile && local.length() > 0) local.toURI().toString()
        else container.safeImageUrl(comic.thumbFileServer, comic.thumbPath)
    }
}

private fun DownloadComic.fraction(): Float =
    if (selectedPictureCount <= 0) 0f else (completedPictureCount.toFloat() / selectedPictureCount).coerceIn(0f, 1f)

private fun DownloadComic.progressText(): String = when {
    selectedPictureCount <= 0 && status == DownloadStatus.QUEUED -> "排队中 · 正在读取章节"
    selectedPictureCount <= 0 -> "正在读取章节"
    else -> "$completedEpCount/$selectedEpCount 话 · $completedPictureCount/$selectedPictureCount 张"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EngineCard(
    manager: DownloadManager,
    running: Boolean,
    hasPaused: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The engine re-measures speed every 250 ms. One refresh a second still reads as live and stops
    // the card from waking the screen four times a second for the whole queue; the latest value wins.
    val progressUpdates = remember(manager) { manager.progress.transform { emit(it); delay(1_000) } }
    @SuppressLint("StateFlowValueCalledInComposition") // Only the starting value; the flow delivers every later one.
    val firstProgress = manager.progress.value
    val progress by progressUpdates.collectAsStateWithLifecycle(firstProgress)
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val throttled = running && progress.effectiveThreads < progress.configuredThreads
    val container = when {
        progress.waitingForWifi -> colors.surfaceContainerHighest
        throttled -> colors.tertiaryContainer
        running -> colors.primaryContainer
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        progress.waitingForWifi -> colors.onSurfaceVariant
        throttled -> colors.onTertiaryContainer
        running -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    val speed = "${context.formatBytes(progress.bytesPerSecond)}/s"
    val title = when {
        progress.waitingForWifi -> "等待 Wi-Fi"
        throttled -> "服务器限流 · $speed"
        running -> "下载中 · $speed"
        hasPaused -> "已暂停"
        else -> "排队中"
    }
    val subtitle = when {
        progress.waitingForWifi -> "连接 Wi-Fi 后自动继续"
        throttled -> "当前 ${progress.effectiveThreads} 个线程 · 已设 ${progress.configuredThreads} 个"
        running -> buildString {
            append("${progress.effectiveThreads} 个线程 · 剩余 ${progress.remainingPictures} 张")
            eta(progress)?.let { append(" · 约 $it") }
        }
        hasPaused -> "点按继续全部下载"
        else -> "${progress.configuredThreads} 个线程"
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (running && !progress.waitingForWifi) {
                    // A still cookie badge, not the morphing LoadingIndicator: a queue can run for an
                    // hour, and the morph asked for a new frame on every vsync the whole time.
                    // The engine's picture total only covers chapters resolved so far, so a ring
                    // would refill to 100% after every chapter; the subtitle carries the numbers.
                    Box(
                        Modifier.size(32.dp).background(content, MaterialShapes.Cookie9Sided.toShape()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, tint = container, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Icon(if (progress.waitingForWifi) Icons.Outlined.WifiOff else Icons.Outlined.Pause, contentDescription = null)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            FilledIconButton(
                onClick = if (running || !hasPaused) onPauseAll else onResumeAll,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = content, contentColor = container),
            ) {
                val pause = running || !hasPaused
                Icon(
                    if (pause) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (pause) "全部暂停" else "全部继续",
                )
            }
        }
    }
}

private fun eta(progress: DownloadProgress): String? {
    if (progress.bytesPerSecond <= 0 || progress.completedPictures <= 0) return null
    val average = progress.downloadedBytes / progress.completedPictures
    val seconds = progress.remainingPictures * average / progress.bytesPerSecond
    return when {
        seconds < 60 -> "不到 1 分钟"
        seconds < 3_600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveList(
    items: List<DownloadComic>,
    manager: DownloadManager,
    contentPadding: PaddingValues,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (DownloadComic) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            title = "暂无进行中的下载",
            description = null,
            icon = Icons.Outlined.Download,
            shape = MaterialShapes.Sunny,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val running = items.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
    val hasPaused = items.any { it.status == DownloadStatus.PAUSED }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            EngineCard(
                manager = manager,
                running = running,
                hasPaused = hasPaused,
                onPauseAll = onPauseAll,
                onResumeAll = onResumeAll,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(items, key = { it.comicId }) { comic ->
            DownloadRow(
                comic = comic,
                modifier = Modifier.animateItem(),
                subtitle = when (comic.status) {
                    DownloadStatus.PAUSED -> "已暂停 · ${comic.progressText()}"
                    DownloadStatus.QUEUED -> if (comic.selectedPictureCount > 0) "排队中 · ${comic.progressText()}" else comic.progressText()
                    else -> comic.progressText()
                },
                progressBar = {
                    when (comic.status) {
                        // Wavy but still: the default wave speed redraws every frame for as long as a
                        // comic is RUNNING, including hours spent offline or waiting for Wi-Fi. Before
                        // the first chapter resolves, fraction() is 0 and the bar shows an empty track.
                        DownloadStatus.RUNNING -> LinearWavyProgressIndicator(
                            progress = { comic.fraction() },
                            modifier = Modifier.fillMaxWidth(),
                            waveSpeed = 0.dp,
                        )
                        else -> LinearProgressIndicator(progress = { comic.fraction() }, modifier = Modifier.fillMaxWidth())
                    }
                },
                trailing = {
                    when (comic.status) {
                        DownloadStatus.RUNNING -> FilledTonalIconButton(onClick = { onPause(comic.comicId) }) {
                            Icon(Icons.Outlined.Pause, contentDescription = "暂停")
                        }
                        DownloadStatus.PAUSED -> FilledTonalIconButton(onClick = { onResume(comic.comicId) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "继续")
                        }
                        else -> Row {
                            IconButton(onClick = { onPause(comic.comicId) }) { Icon(Icons.Outlined.Pause, contentDescription = "暂停") }
                            IconButton(onClick = { onRemove(comic) }) { Icon(Icons.Outlined.Close, contentDescription = "移出队列") }
                        }
                    }
                },
                onLongClick = { onRemove(comic) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    comic: DownloadComic,
    subtitle: String,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selected: Boolean = false,
    progressBar: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val cover = rememberCover(comic)
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .combinedClickable(enabled = onClick != null || onLongClick != null, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ComicCover(coverUrl = cover, contentDescription = null, modifier = Modifier.width(56.dp))
            SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(2.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(comic.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (progressBar != null) {
                Spacer(Modifier.height(8.dp))
                progressBar()
            }
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DoneList(
    items: List<DownloadComic>,
    selection: Set<String>,
    contentPadding: PaddingValues,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onDelete: (DownloadComic) -> Unit,
) {
    val context = LocalContext.current
    if (items.isEmpty()) {
        EmptyState(
            title = "暂无已完成的下载",
            description = null,
            icon = Icons.Outlined.Download,
            shape = MaterialShapes.Sunny,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var sortName by rememberSaveable { mutableStateOf(DoneSort.Recent.name) }
    val sort = DoneSort.valueOf(sortName)
    val folders = remember(items) { items.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted() }
    val collator = remember { Collator.getInstance(Locale.CHINA) }
    val visible = remember(items, folder, sort) {
        items
            .filter { folder == null || it.folder == folder }
            .let { list ->
                when (sort) {
                    DoneSort.Recent -> list.sortedByDescending { it.completedAt ?: it.updatedAt }
                    DoneSort.Title -> list.sortedWith { a, b -> collator.compare(a.title, b.title) }
                    DoneSort.Size -> list.sortedByDescending { it.downloadedBytes }
                }
            }
    }
    val selecting = selection.isNotEmpty()
    var sortMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    Box {
                        FilterChip(
                            selected = false,
                            onClick = { sortMenu = true },
                            label = { Text(sort.label) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DoneSort.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = { sortName = option.name; sortMenu = false })
                            }
                        }
                    }
                }
                if (folders.isNotEmpty()) {
                    item {
                        FilterChip(selected = folder == null, onClick = { folder = null }, label = { Text("全部文件夹") })
                    }
                    items(folders) { name ->
                        FilterChip(
                            selected = folder == name,
                            onClick = { folder = if (folder == name) null else name },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
        items(visible, key = { it.comicId }) { comic ->
            var rowMenu by remember { mutableStateOf(false) }
            DownloadRow(
                comic = comic,
                modifier = Modifier.animateItem().semantics { if (selecting) contentDescription = if (comic.comicId in selection) "已选中" else "未选中" },
                subtitle = buildString {
                    if (comic.author.isNotBlank()) append(comic.author).append(" · ")
                    append("${comic.completedEpCount} 话 · ${context.formatBytes(comic.downloadedBytes)}")
                },
                selected = comic.comicId in selection,
                onClick = { if (selecting) onToggle(comic.comicId) else onOpen(comic.comicId) },
                onLongClick = { onToggle(comic.comicId) },
                trailing = if (selecting) null else {
                    {
                        Box {
                            IconButton(onClick = { rowMenu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作") }
                            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                DropdownMenuItem(text = { Text("阅读") }, onClick = { rowMenu = false; onOpen(comic.comicId) })
                                DropdownMenuItem(text = { Text("查看详情") }, onClick = { rowMenu = false; onOpenDetail(comic.comicId) })
                                DropdownMenuItem(text = { Text("多选") }, onClick = { rowMenu = false; onToggle(comic.comicId) })
                                DropdownMenuItem(text = { Text("删除") }, onClick = { rowMenu = false; onDelete(comic) })
                            }
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FailedList(
    items: List<DownloadComic>,
    contentPadding: PaddingValues,
    onRetry: (String) -> Unit,
    onRetryAll: () -> Unit,
    onRemove: (DownloadComic) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            title = "没有失败的下载",
            icon = Icons.Outlined.Download,
            shape = MaterialShapes.Sunny,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    val failedPictures = items.sumOf { (it.selectedPictureCount - it.completedPictureCount).coerceAtLeast(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text(
                        if (failedPictures > 0) "${items.size} 本漫画 · $failedPictures 张下载失败" else "${items.size} 本漫画下载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Button(
                        onClick = onRetryAll,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    ) { Text("全部重试") }
                }
            }
        }
        items(items, key = { it.comicId }) { comic ->
            DownloadRow(
                comic = comic,
                modifier = Modifier.animateItem(),
                subtitle = listOfNotNull(comic.progressText(), comic.error?.takeIf { it.isNotBlank() }).joinToString(" · "),
                subtitleColor = colors.error,
                progressBar = {
                    LinearProgressIndicator(
                        progress = { comic.fraction() },
                        color = colors.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                trailing = {
                    Row {
                        IconButton(onClick = { onRetry(comic.comicId) }) { Icon(Icons.Outlined.Refresh, contentDescription = "重试") }
                        IconButton(onClick = { onRemove(comic) }) { Icon(Icons.Outlined.Close, contentDescription = "移出队列") }
                    }
                },
            )
        }
        item {
            Text(
                "请检查网络或切换分流",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
