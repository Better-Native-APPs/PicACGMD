package app.picacgmd.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.logic.AutoScrollSpeed
import app.picacgmd.feature.reader.logic.PagingAxis
import app.picacgmd.feature.reader.logic.TapAction
import app.picacgmd.feature.reader.logic.VolumeAction
import app.picacgmd.feature.reader.logic.VolumeKey
import app.picacgmd.feature.reader.logic.VolumeKeyInterpreter
import app.picacgmd.feature.reader.logic.autoScrollDelta
import app.picacgmd.feature.reader.logic.effectiveDirection
import app.picacgmd.feature.reader.logic.forwardSwipeFor
import app.picacgmd.feature.reader.media.PageLabel
import app.picacgmd.feature.reader.media.savePageToGallery
import app.picacgmd.feature.reader.media.sharePage
import app.picacgmd.feature.reader.ui.AllReadDialog
import app.picacgmd.feature.reader.ui.ChapterEndBlock
import app.picacgmd.feature.reader.ui.ChapterEndInfo
import app.picacgmd.feature.reader.ui.ChapterEndSlot
import app.picacgmd.feature.reader.ui.ChapterHint
import app.picacgmd.feature.reader.ui.ChapterSheet
import app.picacgmd.feature.reader.ui.DirectionHint
import app.picacgmd.feature.reader.ui.FilterHint
import app.picacgmd.feature.reader.ui.PageActionsSheet
import app.picacgmd.feature.reader.ui.PageBadge
import app.picacgmd.feature.reader.ui.PageNavigatorHolder
import app.picacgmd.feature.reader.ui.PagePreviewDialog
import app.picacgmd.feature.reader.ui.PageReloadState
import app.picacgmd.feature.reader.ui.PageRetryState
import app.picacgmd.feature.reader.ui.PagedReader
import app.picacgmd.feature.reader.ui.ReaderControlActions
import app.picacgmd.feature.reader.ui.ReaderControls
import app.picacgmd.feature.reader.ui.ReaderKeepScreenOn
import app.picacgmd.feature.reader.ui.ReaderPageHost
import app.picacgmd.feature.reader.ui.ReaderSettingsSheet
import app.picacgmd.feature.reader.ui.ReaderSystemBars
import app.picacgmd.feature.reader.ui.ReaderTheme
import app.picacgmd.feature.reader.ui.StripReader
import app.picacgmd.feature.reader.ui.TapOverlayMemory
import app.picacgmd.feature.reader.ui.TapZoneOverlay
import app.picacgmd.feature.reader.ui.TopCapsuleGap
import app.picacgmd.feature.reader.ui.TopCapsuleHeight
import app.picacgmd.feature.reader.ui.palette
import app.picacgmd.feature.reader.ui.readerChromeInsets
import app.picacgmd.feature.reader.ui.rememberPageColorFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private const val PROGRESS_SAVE_DELAY_MS = 600L

/** A jump with the slider is not reading forward, so it does not hide the controls. */
private const val SEEK_PAGE_CHANGE_WINDOW_MS = 600L

/** Which sheet is open over the reader. */
private sealed interface OpenSheet {
    data object Settings : OpenSheet

    data object Chapters : OpenSheet

    data class PageActions(val page: Int) : OpenSheet
}

private data class PreviewTarget(val image: PageImage, val label: PageLabel)

/**
 * The comic reader.
 *
 * @param source chapters, pages and progress storage supplied by the app.
 * @param config reading preferences; clamped with [ReaderConfig.normalized] before use.
 * @param startChapter index into [ReaderSource.loadChapters] to open first.
 * @param startPage zero-based page inside [startChapter] to open at.
 * @param onConfigChange called with the new preferences when the reader changes them; the app
 *   persists them and passes the result back as [config].
 * @param onBack leave the reader; also what 结束阅读 and 返回详情 do.
 * @param onOpenComments the comic's comments (评论 / 看评论); the buttons are hidden when null.
 * @param onOpenRelated other comics readers also viewed (也在看); the button is hidden when null.
 * @param onOpenAllSettings the app's reader settings page (全部设置); the links are hidden when null.
 * @param routes 分流 / 图片分流 rows in the settings sheet; also what 「换分流」 on a failed page
 *   opens. Hidden when null (downloaded comics).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderScreen(
    source: ReaderSource,
    config: ReaderConfig,
    startChapter: Int,
    startPage: Int,
    onConfigChange: (ReaderConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenComments: (() -> Unit)? = null,
    onOpenRelated: (() -> Unit)? = null,
    onOpenAllSettings: (() -> Unit)? = null,
    routes: ReaderRouteControls? = null,
) {
    val settings = remember(config) { config.normalized() }
    val latestSettings by rememberUpdatedState(settings)
    val palette = settings.background.palette()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val direction = effectiveDirection(settings.mode, settings.direction)
    val chromeInsets = readerChromeInsets()

    // Survives rotation (two-page reading is landscape) and process death.
    var savedChapter by rememberSaveable { mutableIntStateOf(startChapter) }
    var savedPage by rememberSaveable { mutableIntStateOf(startPage) }
    val session = remember(source) { ReaderSession(source, scope, savedChapter, savedPage) }
    val latestRoutes by rememberUpdatedState(routes)
    // Whether routes are offered depends only on the kind of source, so this is stable per source.
    val retry = remember(source) {
        lateinit var state: PageRetryState
        state = PageRetryState(canSwitchServer = source.canSwitchImageServer || routes != null) {
            val picker = latestRoutes
            if (picker != null) {
                picker.onOpenImageRoutes()
            } else {
                scope.launch { if (source.switchImageServer()) state.retryAllFailed() }
            }
        }
        state
    }
    val reloads = remember(session, session.chapterIndex) { PageReloadState() }
    val navigator = remember { PageNavigatorHolder() }
    val volumeKeys = remember { VolumeKeyInterpreter() }
    val keyFocus = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val overlayMemory = remember(context) { TapOverlayMemory(context) }

    var controlsVisible by rememberSaveable { mutableStateOf(!settings.autoFullscreen) }
    var openSheet by remember { mutableStateOf<OpenSheet?>(null) }
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }
    var allReadVisible by remember { mutableStateOf(false) }
    var chapterEndVisible by remember { mutableStateOf(false) }
    var zoneOverlayVisible by remember { mutableStateOf(false) }
    var lastSeekMillis by remember { mutableLongStateOf(0L) }
    // Only read inside effects, so a touch does not recompose the screen.
    var touching by remember { mutableStateOf(false) }
    // Changes only when the zoom crosses 1×, not on every step of a pinch.
    var zoomedIn by remember { mutableStateOf(false) }

    val content = session.content
    val ready = content as? ChapterContent.Ready
    // A chapter that could not be shown has no pages to tap on, so the controls stay. A chapter on its
    // way does not count: moving between chapters while reading should not flash the controls.
    val controlsShown = controlsVisible || (ready == null && content != ChapterContent.Loading)

    val latestControlsShown by rememberUpdatedState(controlsShown)
    val goPastEnd = remember(session) {
        { if (session.nextChapter != null) session.openNext() else allReadVisible = true }
    }
    val host = remember(session, retry, reloads) {
        ReaderPageHost(
            retry = retry,
            reloads = reloads,
            navigator = navigator,
            onPageChange = session::onPageChanged,
            onTapAction = { action ->
                if (action == TapAction.ToggleControls) controlsVisible = !controlsVisible
            },
            onLongPressPage = { page -> openSheet = OpenSheet.PageActions(page) },
            onPastEnd = goPastEnd,
            onChapterEndVisible = { chapterEndVisible = it },
            loadProgress = source::imageLoadProgress,
            onZoomedInChange = { zoomedIn = it },
            controlsShown = { latestControlsShown },
        )
    }

    LaunchedEffect(session, retry) {
        // After an image-server switch, pages not loaded yet (and failed ones) load from the new address.
        source.pageAddressChanges?.collect {
            session.refreshAddresses()
            retry.retryAllFailed()
        }
    }
    LaunchedEffect(session) {
        session.open()
        var previousChapter = -1
        var previousPage = -1
        snapshotFlow { session.chapterIndex to session.page }.collect { (chapter, page) ->
            savedChapter = chapter
            savedPage = page
            val movedForward = chapter == previousChapter && page > previousPage
            val seeking = System.currentTimeMillis() - lastSeekMillis < SEEK_PAGE_CHANGE_WINDOW_MS
            if (movedForward && !seeking && latestSettings.autoFullscreenOnForward) controlsVisible = false
            previousChapter = chapter
            previousPage = page
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
    // 画质 applies at once: the source is asked for the chapter's pages again and hands out addresses
    // for the new quality. The position stays; a next chapter fetched ahead at the old quality is dropped.
    var appliedQuality by remember(session) { mutableStateOf(settings.quality) }
    LaunchedEffect(session, settings.quality) {
        if (settings.quality != appliedQuality) {
            appliedQuality = settings.quality
            session.reload()
        }
    }
    // The first time a tap mode is used, once there is something on screen and no sheet over it.
    val readyForOverlay = ready != null && openSheet == null
    LaunchedEffect(settings.tapMode, readyForOverlay) {
        if (readyForOverlay && !overlayMemory.isSeen(settings.tapMode)) {
            overlayMemory.markSeen(settings.tapMode)
            zoneOverlayVisible = true
        }
    }
    // Key events only reach Compose through the focused node, so hold focus for as long as volume keys
    // page, and take it again when a sheet or another app gives the window back.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, settings.volumeKeyPaging) {
        if (windowFocused && settings.volumeKeyPaging) keyFocus.requestFocus()
    }

    // 自动翻页: waits while the controls, a sheet, a dialog, the zone overlay or the chapter-end card are
    // up, while a finger is on the screen and while the page is zoomed in; turning a page by hand starts
    // the wait over. At the end of the chapter it stops, and it never leaves the chapter by itself.
    // It also waits while the reader is not in the foreground.
    val autoTurnHeld by rememberUpdatedState(
        controlsShown || openSheet != null || preview != null || allReadVisible || zoneOverlayVisible || chapterEndVisible,
    )
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(settings.autoTurn, settings.autoTurnSeconds, settings.mode, ready?.generation) {
        if (!settings.autoTurn || settings.mode.isContinuous || ready == null) return@LaunchedEffect
        val wait = settings.autoTurnSeconds * 1_000L
        snapshotFlow {
            val resumed = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)
            if (autoTurnHeld || touching || zoomedIn || !resumed) null else session.page
        }.collectLatest { page ->
            if (page == null) return@collectLatest
            while (true) {
                delay(wait)
                // The turn runs in the screen's scope: the page it reaches restarts this wait, and
                // that must not cut its own animation short half-way between two pages.
                val turn = scope.async { navigator.active?.step(forward = true, leaveAtEnd = false) ?: false }
                if (!turn.await()) awaitCancellation()
            }
        }
    }

    // 自动滚动 (the strips' 自动翻页): a steady scroll, held by everything that holds 自动翻页 except that a
    // finger only holds it while 手指触摸时暂停 is on. At the end of the last page it stops, or, with
    // 自动翻章, opens the next chapter with a short cue and carries on there.
    val autoScrollPixelsPerSecond = with(LocalDensity.current) {
        AutoScrollSpeed.dpPerSecond(settings.autoScrollSpeed).dp.toPx()
    }
    val nextChapterCue = stringResource(R.string.reader_auto_next_chapter_cue)
    LaunchedEffect(settings.autoTurn, settings.mode, autoScrollPixelsPerSecond, ready?.generation) {
        if (!settings.autoTurn || !settings.mode.isContinuous || ready == null) return@LaunchedEffect
        snapshotFlow {
            val resumed = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)
            val fingerHolds = touching && latestSettings.autoScrollPauseOnTouch
            !autoTurnHeld && !fingerHolds && !zoomedIn && resumed
        }.collectLatest { running ->
            if (!running) return@collectLatest
            var lastFrame = withFrameNanos { it }
            while (true) {
                val frame = withFrameNanos { it }
                val active = navigator.active ?: break
                val remaining = active.autoScrollRemaining()
                val delta = autoScrollDelta(autoScrollPixelsPerSecond, frame - lastFrame, remaining)
                lastFrame = frame
                if (delta > 0f) {
                    active.autoScrollBy(delta)
                    continue
                }
                if (remaining > 0f) continue
                val next = session.nextChapter
                if (latestSettings.autoNextChapter && next != null) {
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(nextChapterCue.format(next.title))
                    }
                    session.openNext()
                }
                awaitCancellation()
            }
        }
    }

    ReaderSystemBars(
        hidden = settings.fullscreen && !controlsShown,
        lightBackground = palette.background.luminance() > 0.5f,
    )
    ReaderKeepScreenOn(settings.keepScreenOn)

    fun onKey(event: KeyEvent): Boolean {
        val key = when (event.key) {
            Key.VolumeDown -> VolumeKey.Down
            Key.VolumeUp -> VolumeKey.Up
            else -> return false
        }
        if (!settings.volumeKeyPaging || ready == null) return false
        val native = event.nativeKeyEvent
        // The matching release and held-key repeats are swallowed too, or the volume would still change.
        if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) {
            when (volumeKeys.onPress(key, native.eventTime, settings.volumeKeyChapterJump)) {
                VolumeAction.NextPage -> scope.launch { navigator.active?.step(forward = true) }
                VolumeAction.PreviousPage -> scope.launch { navigator.active?.step(forward = false) }
                VolumeAction.NextChapter -> goPastEnd()
                VolumeAction.PreviousChapter -> session.openPrevious()
            }
        }
        return true
    }

    val colorFilter = rememberPageColorFilter(settings.filter)
    val savedMessage = stringResource(R.string.reader_saved)
    val saveFailedMessage = stringResource(R.string.reader_save_failed)
    val shareFailedMessage = stringResource(R.string.reader_share_failed)

    ReaderTheme(settings.background, settings.reduceMotion) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(palette.background)
                .pointerInput(Unit) {
                    // Watches without taking anything: a finger anywhere holds 自动翻页.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touching = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touching = false
                        }
                    }
                }
                .onPreviewKeyEvent(::onKey)
                .focusRequester(keyFocus)
                .focusable(enabled = settings.volumeKeyPaging),
        ) {
            when (content) {
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
                    ChapterFadeIn(enabled = !settings.reduceMotion) {
                        ChapterPages(
                            content = content,
                            settings = settings,
                            session = session,
                            host = host,
                            source = source,
                            onBack = onBack,
                            onOpenComments = onOpenComments,
                        )
                    }
                }
            }

            // In 自由缩放 the minimap takes the badge's corner while zoomed in.
            val minimapShown = settings.mode == ReaderMode.FreeZoom && zoomedIn
            if (ready != null && !controlsShown && !chapterEndVisible && !minimapShown) {
                CurrentPageBadge(
                    page = { session.page },
                    lastPage = { session.pageEnd },
                    pageCount = ready.pages.size,
                    onClick = if (settings.tapMode == TapMode.Controller) ({ controlsVisible = true }) else null,
                )
            }

            // Changes made in a sheet are announced once it has closed and the hint can be seen.
            val hintsHeld = openSheet != null
            FilterHint(
                filter = settings.filter,
                held = hintsHeld,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(chromeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(start = 12.dp, top = hintTopPadding(controlsShown, 12.dp)),
            )
            ChapterHint(
                chapterTitle = ready?.chapter?.title,
                held = hintsHeld,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(chromeInsets.only(WindowInsetsSides.Top))
                    .padding(top = hintTopPadding(controlsShown, 16.dp)),
            )
            DirectionHint(
                direction = direction,
                held = hintsHeld,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(chromeInsets.only(WindowInsetsSides.Bottom))
                    .padding(bottom = if (controlsShown) 180.dp else 120.dp),
            )

            ReaderControls(
                visible = controlsShown,
                chapterTitle = ready?.chapter?.title ?: session.chapters.getOrNull(session.chapterIndex)?.title.orEmpty(),
                comicTitle = source.comicTitle,
                page = { session.page },
                pageCount = ready?.pages?.size ?: 0,
                sliderPosition = settings.sliderPosition,
                rightToLeft = direction == ReaderDirection.RightToLeft,
                hasPreviousChapter = session.previousChapter != null,
                hasNextChapter = session.nextChapter != null,
                reduceMotion = settings.reduceMotion,
                actions = ReaderControlActions(
                    onBack = onBack,
                    onOpenChapters = { if (session.chapters.isNotEmpty()) openSheet = OpenSheet.Chapters },
                    onOpenSettings = { openSheet = OpenSheet.Settings },
                    onFullscreen = {
                        controlsVisible = false
                        if (!settings.fullscreen) onConfigChange(settings.copy(fullscreen = true))
                    },
                    onPreviousChapter = session::openPrevious,
                    onNextChapter = session::openNext,
                    onSeek = { page ->
                        lastSeekMillis = System.currentTimeMillis()
                        scope.launch { navigator.active?.jumpTo(page) }
                    },
                    onReloadChapter = {
                        reloads.reloadAll()
                        session.reload()
                    },
                    onPreviewZones = { zoneOverlayVisible = true },
                    onOpenComments = onOpenComments,
                    onOpenAllSettings = onOpenAllSettings,
                ),
            )

            TapZoneOverlay(
                visible = zoneOverlayVisible,
                tapMode = settings.tapMode,
                axis = if (direction == ReaderDirection.TopToBottom) PagingAxis.Vertical else PagingAxis.Horizontal,
                rightToLeft = direction == ReaderDirection.RightToLeft,
                alwaysRightIsNext = settings.alwaysRightIsNext,
                stripLabels = settings.mode == ReaderMode.Strip || settings.mode == ReaderMode.FreeZoom,
                onDismiss = { zoneOverlayVisible = false },
            )

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(chromeInsets.only(WindowInsetsSides.Bottom))
                    .padding(bottom = if (controlsShown) 180.dp else 16.dp),
            )

            when (val sheet = openSheet) {
                null -> Unit
                OpenSheet.Settings -> ReaderSettingsSheet(
                    config = settings,
                    onConfigChange = onConfigChange,
                    onDismiss = { openSheet = null },
                    onOpenAllSettings = onOpenAllSettings,
                    onPreviewZones = { zoneOverlayVisible = true },
                    routes = routes,
                )
                OpenSheet.Chapters -> ChapterSheet(
                    chapters = session.chapters,
                    currentIndex = session.chapterIndex,
                    isRead = session::isRead,
                    onSelect = { index -> session.open(index, 0) },
                    onDismiss = { openSheet = null },
                )
                is OpenSheet.PageActions -> {
                    val page = ready?.pages?.getOrNull(sheet.page)
                    if (page != null) {
                        val label = PageLabel(source.comicTitle, ready.chapter.title, sheet.page + 1)
                        PageActionsSheet(
                            image = page.image,
                            pageNumber = label.pageNumber,
                            colorFilter = colorFilter,
                            onDismiss = { openSheet = null },
                            onSave = {
                                scope.launch {
                                    val saved = savePageToGallery(context, page.image, label)
                                    snackbar.showSnackbar(if (saved) savedMessage else saveFailedMessage)
                                }
                            },
                            onReload = { reloads.reload(sheet.page) },
                            onShare = {
                                scope.launch {
                                    if (!sharePage(context, page.image, label)) snackbar.showSnackbar(shareFailedMessage)
                                }
                            },
                            onPreview = { preview = PreviewTarget(page.image, label) },
                            onReloadAll = {
                                reloads.reloadAll()
                                session.reload()
                            },
                        )
                    }
                }
            }

            preview?.let { target ->
                PagePreviewDialog(
                    image = target.image,
                    label = target.label,
                    colorFilter = colorFilter,
                    reduceMotion = settings.reduceMotion,
                    onDismiss = { preview = null },
                )
            }

            if (allReadVisible) {
                AllReadDialog(
                    reduceMotion = settings.reduceMotion,
                    onDismiss = { allReadVisible = false },
                    onBackToDetails = onBack,
                    onComments = onOpenComments?.let { open ->
                        {
                            allReadVisible = false
                            open()
                        }
                    },
                    onRelated = onOpenRelated?.let { open ->
                        {
                            allReadVisible = false
                            open()
                        }
                    },
                )
            }
        }
    }
}

/** Below the top capsule while it is on screen; otherwise [resting] below the top of the screen. */
private fun hintTopPadding(controlsShown: Boolean, resting: Dp): Dp =
    if (controlsShown) TopCapsuleGap + TopCapsuleHeight + 8.dp else resting

/** The new chapter fades in; together with the chapter's name, that is the whole transition. */
@Composable
private fun ChapterFadeIn(enabled: Boolean, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(if (enabled) 0f else 1f) }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(Unit) { alpha.animateTo(1f, spec) }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
        content()
    }
}

@Composable
private fun ChapterPages(
    content: ChapterContent.Ready,
    settings: ReaderConfig,
    session: ReaderSession,
    host: ReaderPageHost,
    source: ReaderSource,
    onBack: () -> Unit,
    onOpenComments: (() -> Unit)?,
) {
    // A newly shown mode starts where the previous one was, without recomposing on every page.
    val startAt = remember(settings.mode) { Snapshot.withoutReadObservation { session.page } }
    val lastPage = content.pages.lastIndex
    LaunchedEffect(session, lastPage, settings.preloadAhead) {
        snapshotFlow { session.page >= lastPage - settings.preloadAhead }
            .filter { nearTheEnd -> nearTheEnd }
            .collect { session.prefetchNext() }
    }
    val upcomingPages = session.nextChapterPages
    val next = session.nextChapter
    val endContent: ChapterEndSlot? = next?.let { chapter ->
        val slot: ChapterEndSlot = { pull, slotModifier ->
            ChapterEndBlock(
                info = ChapterEndInfo(
                    finishedTitle = content.chapter.title,
                    next = chapter,
                    nextPageCount = upcomingPages?.size,
                    cover = source.comicCover ?: upcomingPages?.firstOrNull()?.image,
                    commentCount = source.commentCount,
                ),
                pull = pull,
                swipe = forwardSwipeFor(effectiveDirection(settings.mode, settings.direction)),
                onNext = session::openNext,
                onComments = onOpenComments,
                onFinish = onBack,
                modifier = slotModifier,
            )
        }
        slot
    }
    when (settings.mode) {
        ReaderMode.Strip, ReaderMode.FreeZoom -> key(settings.mode) {
            StripReader(
                pages = content.pages,
                initialPage = startAt,
                config = settings,
                host = host,
                upcomingPages = upcomingPages,
                endContent = endContent,
                free = settings.mode == ReaderMode.FreeZoom,
            )
        }
        ReaderMode.Paged -> PagedReader(
            pages = content.pages,
            initialPage = startAt,
            config = settings,
            host = host,
            upcomingPages = upcomingPages,
            endContent = endContent,
        )
        // Two pages side by side on a landscape screen; on a portrait one they would be too small,
        // so it reads a page at a time there. Turning the phone keeps the page.
        ReaderMode.DoublePage -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val spreads = maxWidth > maxHeight
            key(spreads, settings.twoPageCoverAlone) {
                val start = remember { Snapshot.withoutReadObservation { session.page } }
                PagedReader(
                    pages = content.pages,
                    initialPage = start,
                    config = settings,
                    host = host,
                    upcomingPages = upcomingPages,
                    endContent = endContent,
                    spreads = spreads,
                )
            }
        }
    }
}

/**
 * The page badge at the bottom right. Under 控制器 it is also the way to bring the controls back, so it
 * gets a finger-sized touch area without growing on screen.
 */
@Composable
private fun BoxScope.CurrentPageBadge(page: () -> Int, lastPage: () -> Int, pageCount: Int, onClick: (() -> Unit)?) {
    val label = stringResource(R.string.reader_show_controls)
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = 4.dp, bottom = 17.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 15.dp),
    ) {
        val first = page()
        PageBadge(page = first + 1, pageCount = pageCount, lastPage = maxOf(first, lastPage()) + 1)
    }
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
