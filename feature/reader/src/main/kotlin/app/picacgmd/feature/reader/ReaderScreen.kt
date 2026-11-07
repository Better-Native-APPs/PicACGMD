package app.picacgmd.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.logic.TapAction
import app.picacgmd.feature.reader.ui.PageBadge
import app.picacgmd.feature.reader.ui.PageRetryState
import app.picacgmd.feature.reader.ui.PagedReader
import app.picacgmd.feature.reader.ui.ReaderTheme
import app.picacgmd.feature.reader.ui.StripReader
import app.picacgmd.feature.reader.ui.palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private const val PROGRESS_SAVE_DELAY_MS = 600L

/**
 * The comic reader.
 *
 * @param source chapters, pages and progress storage supplied by the app.
 * @param config reading preferences; clamped with [ReaderConfig.normalized] before use.
 * @param startChapter index into [ReaderSource.loadChapters] to open first.
 * @param startPage zero-based page inside [startChapter] to open at.
 * @param onConfigChange called with the new preferences when the reader changes them; the app
 *   persists them and passes the result back as [config].
 * @param onBack leave the reader.
 */
@Composable
fun ReaderScreen(
    source: ReaderSource,
    config: ReaderConfig,
    startChapter: Int,
    startPage: Int,
    onConfigChange: (ReaderConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = remember(config) { config.normalized() }
    val palette = settings.background.palette()
    val scope = rememberCoroutineScope()

    // Survives rotation (two-page reading is landscape) and process death.
    var savedChapter by rememberSaveable { mutableIntStateOf(startChapter) }
    var savedPage by rememberSaveable { mutableIntStateOf(startPage) }
    val session = remember(source) { ReaderSession(source, scope, savedChapter, savedPage) }
    val retry = remember(source) {
        lateinit var state: PageRetryState
        state = PageRetryState(canSwitchServer = source.canSwitchImageServer) {
            scope.launch { if (source.switchImageServer()) state.retryAllFailed() }
        }
        state
    }

    // Shown and hidden by taps; the control layer itself is drawn by a later slice.
    var controlsVisible by rememberSaveable { mutableStateOf(!settings.autoFullscreen) }

    LaunchedEffect(session) {
        session.open()
        snapshotFlow { session.chapterIndex to session.page }.collect { (chapter, page) ->
            savedChapter = chapter
            savedPage = page
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { session.progress() }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { progress ->
                delay(PROGRESS_SAVE_DELAY_MS)
                source.saveProgress(progress)
            }
    }
    DisposableEffect(session) {
        onDispose { session.progress()?.let(source::saveProgress) }
    }
    KeepScreenOn(settings.keepScreenOn)

    ReaderTheme(settings.background) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(palette.background),
        ) {
            when (val content = session.content) {
                ChapterContent.Loading -> ChapterLoading(reduceMotion = settings.reduceMotion)
                is ChapterContent.Failed -> ChapterMessage(
                    title = stringResource(R.string.reader_chapter_failed),
                    detail = stringResource(R.string.reader_chapter_failed_detail),
                    color = palette.onBackground,
                    actionLabel = stringResource(R.string.reader_retry),
                    onAction = { session.open() },
                )
                ChapterContent.NoChapters -> ChapterMessage(
                    title = stringResource(R.string.reader_no_chapters),
                    detail = null,
                    color = palette.onBackground,
                    actionLabel = stringResource(R.string.reader_back),
                    onAction = onBack,
                )
                is ChapterContent.Empty -> ChapterMessage(
                    title = stringResource(R.string.reader_chapter_empty),
                    detail = content.chapter.title,
                    color = palette.onBackground,
                    actionLabel = stringResource(R.string.reader_back),
                    onAction = onBack,
                )
                is ChapterContent.Ready -> key(content.generation) {
                    ChapterPages(
                        content = content,
                        settings = settings,
                        session = session,
                        retry = retry,
                        onTapAction = { action ->
                            if (action == TapAction.ToggleControls) controlsVisible = !controlsVisible
                        },
                    )
                    CurrentPageBadge(
                        page = { session.page },
                        pageCount = content.pages.size,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 12.dp, bottom = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterPages(
    content: ChapterContent.Ready,
    settings: ReaderConfig,
    session: ReaderSession,
    retry: PageRetryState,
    onTapAction: (TapAction) -> Unit,
) {
    // A newly shown mode starts where the previous one was, without recomposing on every page.
    val startAt = remember(settings.mode) { Snapshot.withoutReadObservation { session.page } }
    when (settings.mode) {
        // 自由缩放 currently reads as a zoomable strip.
        ReaderMode.Strip, ReaderMode.FreeZoom -> StripReader(
            pages = content.pages,
            initialPage = startAt,
            config = settings,
            retry = retry,
            onPageChange = session::onPageChanged,
            onTapAction = onTapAction,
        )
        // 双页 currently reads one page at a time.
        ReaderMode.Paged, ReaderMode.DoublePage -> PagedReader(
            pages = content.pages,
            initialPage = startAt,
            config = settings,
            retry = retry,
            onPageChange = session::onPageChanged,
            onTapAction = onTapAction,
        )
    }
}

@Composable
private fun CurrentPageBadge(page: () -> Int, pageCount: Int, modifier: Modifier = Modifier) {
    PageBadge(page = page() + 1, pageCount = pageCount, modifier = modifier)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChapterLoading(reduceMotion: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (reduceMotion) {
            LoadingIndicator(progress = { 0f }, modifier = Modifier.size(64.dp))
        } else {
            LoadingIndicator(modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
private fun ChapterMessage(
    title: String,
    detail: String?,
    color: Color,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                textAlign = TextAlign.Center,
            )
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Keeps the display awake while the reader is on screen. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val before = view.keepScreenOn
        if (enabled) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}
