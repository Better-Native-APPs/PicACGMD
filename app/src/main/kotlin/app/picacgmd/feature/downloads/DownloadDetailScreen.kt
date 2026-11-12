package app.picacgmd.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppFilledTonalButton
import app.picacgmd.core.designsystem.component.AppIconButton
import app.picacgmd.core.designsystem.component.AppOutlinedButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.component.BackButton
import app.picacgmd.core.designsystem.component.ComicCover
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.component.StatusPane
import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.designsystem.window.securePopupProperties
import app.picacgmd.core.domain.download.DownloadChapterPhase
import app.picacgmd.core.domain.download.chapterPhase
import app.picacgmd.core.domain.download.downloadFraction
import app.picacgmd.core.network.PicaJson
import app.picacgmd.feature.settings.formatBytes
import app.picacgmd.feature.settings.rememberAppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private data class DownloadDetailSnapshot(val comic: DownloadComic?, val chapters: List<DownloadEp>)

/** Local snapshots and live chapter states; only explicit online actions leave this destination. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadDetailScreen(
    comicId: String,
    onBack: () -> Unit,
    onReadDownloaded: (comicId: String, episodeOrder: Int?) -> Unit,
    onAddChapters: (comicId: String) -> Unit,
    onOpenComments: (comicId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = rememberDownloadManager()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings = rememberAppSettings()
    DownloadStorageNotice(manager, snackbar)
    val snapshot by remember(manager, comicId) {
        combine(manager.observeComic(comicId), manager.observeChapters(comicId), ::DownloadDetailSnapshot)
    }.collectAsStateWithLifecycle(initialValue = null)
    val comic = snapshot?.comic?.takeUnless { it.status == DownloadStatus.DELETING }
    val chapters = snapshot?.chapters.orEmpty()
    var menu by remember { mutableStateOf(false) }
    var deletingComic by remember { mutableStateOf(false) }
    var deletingChapter by remember { mutableStateOf<DownloadEp?>(null) }

    fun act(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                snackbar.showSnackbar("$failure：${exception.message ?: "请重试"}")
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("下载详情") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (comic != null) Box {
                        AppIconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = securePopupProperties(focusable = true)) {
                            DropdownMenuItem(text = { Text("删除整本") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
                                menu = false
                                deletingComic = true
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            snapshot == null -> LoadingState(Modifier.padding(padding).fillMaxSize())
            comic == null -> StatusPane(
                title = "未找到下载",
                description = "这本漫画的下载已被删除",
                icon = Icons.Outlined.Download,
                modifier = Modifier.padding(padding).fillMaxSize(),
                actions = { AppButton(onClick = onBack) { Text("返回") } },
            )
            else -> LazyColumn(
                modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                item(key = "snapshot") {
                    DownloadSnapshotHeader(
                        comic = comic,
                        onRead = { onReadDownloaded(comicId, null) },
                        canRead = chapters.any { it.status == DownloadStatus.DONE },
                        onAddChapters = { onAddChapters(comicId) },
                        onOpenComments = if (settings?.showCommentsInDownloads == true) ({ onOpenComments(comicId) }) else null,
                    )
                }
                item(key = "chapter-header") {
                    Text("章节 · ${chapters.size}", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    if (chapters.isEmpty()) Text("尚无章节，可点「补充章节」选择下载", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
                items(chapters, key = { it.epId }) { chapter ->
                    DownloadChapterRow(
                        chapter = chapter,
                        onRead = { onReadDownloaded(comicId, chapter.epOrder) },
                        onRetry = { act("重试失败") { manager.retryChapter(chapter.epId) } },
                        onDelete = { deletingChapter = chapter },
                    )
                }
            }
        }
    }

    if (deletingComic && comic != null) DownloadDeleteDialog(
        title = "删除「${comic.title}」？",
        description = "移出下载队列并删除这本漫画的全部已下载文件",
        onDismiss = { deletingComic = false },
        onConfirm = {
            deletingComic = false
            act("删除失败") { manager.delete(comicId); onBack() }
        },
    )
    deletingChapter?.let { chapter ->
        DownloadDeleteDialog(
            title = "删除「${chapter.title}」？",
            description = "只删除这一话的下载，其他章节不受影响",
            onDismiss = { deletingChapter = null },
            onConfirm = {
                deletingChapter = null
                act("删除失败") { manager.deleteChapter(chapter.epId) }
            },
        )
    }
}

@Composable
private fun DownloadSnapshotHeader(
    comic: DownloadComic,
    onRead: () -> Unit,
    canRead: Boolean,
    onAddChapters: () -> Unit,
    onOpenComments: (() -> Unit)?,
) {
    val context = LocalContext.current
    val cover = rememberCover(comic)
    val categories = remember(comic.categoriesJson) { PicaJson.decodeFromString<List<String>>(comic.categoriesJson) }
    val tags = remember(comic.tagsJson) { PicaJson.decodeFromString<List<String>>(comic.tagsJson) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ComicCover(coverUrl = cover, contentDescription = null, modifier = Modifier.width(96.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(comic.title, style = MaterialTheme.typography.headlineSmall)
                if (comic.author.isNotBlank()) Text(comic.author, style = MaterialTheme.typography.bodyMedium)
                Text("已下载 ${comic.completedEpCount}/${comic.selectedEpCount} 话 · ${context.formatBytes(comic.downloadedBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (comic.folder.isNotBlank()) Text("文件夹 · ${comic.folder}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(onClick = onRead, enabled = canRead) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("继续阅读")
            }
            AppOutlinedButton(onClick = onAddChapters) { Text("补充章节") }
            if (onOpenComments != null) AppFilledTonalButton(onClick = onOpenComments) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("评论")
            }
        }
        if (categories.isNotEmpty()) Text(categories.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag -> AssistChip(onClick = {}, label = { Text(tag) }, enabled = false) }
        }
        if (comic.description.isNotBlank()) Text(comic.description, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadChapterRow(chapter: DownloadEp, onRead: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    val phase = chapter.chapterPhase()
    val label = when (phase) {
        DownloadChapterPhase.Complete -> "完成"
        DownloadChapterPhase.Downloading -> "下载中"
        DownloadChapterPhase.Waiting -> "等待"
        DownloadChapterPhase.Failed -> "失败"
        DownloadChapterPhase.Paused -> "暂停"
        DownloadChapterPhase.Deleting -> "删除中"
    }
    ListItem(
        onClick = onRead,
        enabled = phase == DownloadChapterPhase.Complete,
        content = { Text(chapter.title) },
        supportingContent = {
            Column {
                Text(if (chapter.selectedPictureCount > 0) "$label · ${chapter.completedPictureCount}/${chapter.selectedPictureCount} 张" else label,
                    color = if (phase == DownloadChapterPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (phase == DownloadChapterPhase.Failed) chapter.error?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (phase == DownloadChapterPhase.Downloading || phase == DownloadChapterPhase.Paused) {
                    Spacer(Modifier.height(8.dp))
                    if (phase == DownloadChapterPhase.Downloading) LinearWavyProgressIndicator(
                        progress = { chapter.downloadFraction() }, modifier = Modifier.fillMaxWidth(), waveSpeed = 0.dp,
                    ) else LinearProgressIndicator(progress = { chapter.downloadFraction() }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        trailingContent = {
            Row {
                if (phase == DownloadChapterPhase.Failed) AppTextButton(onClick = onRetry) { Text("重试") }
                AppIconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "删除${chapter.title}") }
            }
        },
    )
}

@Composable
private fun DownloadDeleteDialog(title: String, description: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            AppButton(onClick = onConfirm, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
            )) { Text("删除") }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
