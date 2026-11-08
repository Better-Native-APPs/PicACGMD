package app.picacgmd.feature.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import coil3.compose.AsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.StripPaging
import app.picacgmd.feature.reader.logic.ForwardSwipe
import app.picacgmd.feature.reader.logic.PagingAxis
import app.picacgmd.feature.reader.logic.StripItem
import app.picacgmd.feature.reader.logic.TapAction
import app.picacgmd.feature.reader.logic.clampPage
import app.picacgmd.feature.reader.logic.decodeEdge
import app.picacgmd.feature.reader.logic.doubleTapAction
import app.picacgmd.feature.reader.logic.panToPagePoint
import app.picacgmd.feature.reader.logic.screenToList
import app.picacgmd.feature.reader.logic.singleTapAction
import app.picacgmd.feature.reader.logic.stripCurrentPage
import app.picacgmd.feature.reader.logic.stripDecodeHeight
import app.picacgmd.feature.reader.logic.stripItemAt
import app.picacgmd.feature.reader.logic.stripListHeight
import app.picacgmd.feature.reader.logic.stripStepDistance
import app.picacgmd.feature.reader.logic.tapZoneAt
import app.picacgmd.feature.reader.logic.visiblePageFraction
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import coil3.size.Size as CoilSize

/** Height / width used for a page whose size is not known yet (a typical manga page). */
internal const val DEFAULT_PAGE_ASPECT: Float = 1.42f

internal fun ReaderPage.knownAspect(): Float? =
    if (width > 0 && height > 0) height.toFloat() / width else null

/** How far past the strip's edges a 自由缩放 drag may go with 锁定拖动边界 off: half the screen. */
private const val FREE_PAN_SLACK = 0.5f

/** The minimap takes the page badge's corner while zoomed in (board 12: right 12, bottom 40). */
private val MinimapBottomPadding = 40.dp

/**
 * 条漫: every page at full width, no gaps. Pages are decoded at the viewport width and only the
 * visible ones stay composed, so memory stays flat however long the chapter is. Two fingers zoom
 * the whole strip (up to [ReaderConfig.maxZoom]); a double tap zooms to
 * [ReaderConfig.doubleTapZoom] and back.
 *
 * 自由缩放 ([free]) is the same strip with free zoom: it also zooms out, down to
 * [ReaderConfig.minZoom] (the strip narrows and more pages fit on screen), a drag may take the strip
 * past its edges unless 锁定拖动边界 ([ReaderConfig.lockDragBounds]) is on, and while zoomed in a
 * minimap shows where the screen is on the current page.
 *
 * [endContent], when there is a next chapter, follows the last page; dragging on past the end leaves
 * the chapter through [ReaderPageHost.onPastEnd].
 */
@Composable
internal fun StripReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    config: ReaderConfig,
    host: ReaderPageHost,
    upcomingPages: List<ReaderPage>?,
    endContent: ChapterEndSlot?,
    modifier: Modifier = Modifier,
    free: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = clampPage(initialPage, pages.size))
    val zoom = remember(listState) {
        ZoomState(scope).also { state ->
            state.listScroller = { pixels -> listState.dispatchRawDelta(pixels) }
        }
    }
    val fling = ScrollableDefaults.flingBehavior()
    val aspects = remember(pages) { mutableStateMapOf<Int, Float>() }
    val pull = rememberChapterPull(ForwardSwipe.Up, host.onPastEnd)
    val colorFilter = rememberPageColorFilter(config.filter)
    val haptics = LocalHapticFeedback.current
    val latestConfig by rememberUpdatedState(config)
    val hasEndItem by rememberUpdatedState(endContent != null)
    val pageCount = pages.size

    SideEffect {
        zoom.minScale = if (free) min(config.minZoom, 1f) else 1f
        zoom.maxScale = config.maxZoom
        zoom.gestureSpeed = config.gestureSpeed
        zoom.listGrowsWhenZoomedOut = free
        val slack = if (free && !config.lockDragBounds) FREE_PAN_SLACK else 0f
        if (zoom.panSlack != slack) {
            zoom.panSlack = slack
            zoom.clampToBounds()
        }
    }

    val currentPage by remember(listState, zoom, pageCount) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
            val visible = info.visibleItemsInfo.filter { it.index < pageCount }
            if (visible.isEmpty() && info.visibleItemsInfo.isNotEmpty()) {
                pageCount - 1
            } else {
                stripCurrentPage(
                    visible = visible.map { StripItem(it.index, it.offset, it.size) },
                    viewportSize = viewport,
                    atStart = !listState.canScrollBackward,
                    atEnd = !listState.canScrollForward,
                    probeFraction = (0.5f - zoom.offsetY / (zoom.scale * viewport)).coerceIn(0f, 1f),
                )
            }
        }
    }
    val visiblePages by remember(listState, pageCount) {
        derivedStateOf {
            val indices = listState.layoutInfo.visibleItemsInfo.map { it.index }.filter { it < pageCount }
            if (indices.isEmpty()) IntRange.EMPTY else indices.first()..indices.last()
        }
    }
    val endTakesOver by remember(listState, pageCount) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            info.visibleItemsInfo.lastOrNull()?.let { it.index >= pageCount && it.offset < viewport / 2 } ?: false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { currentPage }.collect { host.onPageChange(it, it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { endTakesOver }.collect { host.onChapterEndVisible(it) }
    }
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.isZoomedIn }.collect { host.onZoomedInChange(it) }
    }
    DisposableEffect(host) {
        onDispose {
            host.onChapterEndVisible(false)
            host.onZoomedInChange(false)
        }
    }

    val navigator = remember(listState, zoom, pageCount) {
        object : PageNavigator {
            override suspend fun step(forward: Boolean, leaveAtEnd: Boolean): Boolean {
                val settings = latestConfig
                val instant = settings.noPageAnimation || settings.reduceMotion
                return when (settings.stripPaging) {
                    StripPaging.ByScreen -> when {
                        forward && !listState.canScrollForward -> leaveAtEnd.also { leaving -> if (leaving) host.onPastEnd() }
                        !forward && !listState.canScrollBackward -> false
                        else -> {
                            // A share of the screen, whatever the zoom: the list moves less when zoomed in.
                            val screen = zoom.containerHeight.roundToInt()
                            val distance = stripStepDistance(screen, settings.scrollFraction, zoom.scale)
                            val delta = if (forward) distance else -distance
                            if (instant) listState.scrollBy(delta) else listState.animateScrollBy(delta)
                            true
                        }
                    }
                    StripPaging.ByImage -> {
                        val target = currentPage + if (forward) 1 else -1
                        val endVisible = listState.layoutInfo.visibleItemsInfo.any { it.index >= pageCount }
                        when {
                            target < 0 -> false
                            target >= pageCount && (!hasEndItem || endVisible) ->
                                leaveAtEnd.also { leaving -> if (leaving) host.onPastEnd() }
                            instant -> {
                                listState.scrollToItem(target)
                                true
                            }
                            else -> {
                                listState.animateScrollToItem(target)
                                true
                            }
                        }
                    }
                }
            }

            override suspend fun jumpTo(page: Int) {
                listState.scrollToItem(clampPage(page, pageCount))
            }

            override fun autoScrollRemaining(): Float {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull { it.index < pageCount } ?: return 0f
                if (last.index < pageCount - 1) return Float.MAX_VALUE
                return (last.offset + last.size - info.viewportEndOffset + info.afterContentPadding).toFloat()
            }

            override fun autoScrollBy(pixels: Float) {
                listState.dispatchRawDelta(pixels)
            }
        }
    }
    RegisterNavigator(host.navigator, navigator)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        SideEffect { zoom.setGeometry(widthPx.toFloat(), heightPx.toFloat()) }
        val requestSize = remember(widthPx) { CoilSize(decodeEdge(widthPx), stripDecodeHeight(decodeEdge(widthPx))) }
        val pageWidth = maxWidth

        PreloadPages(
            pages = pages,
            current = currentPage,
            ahead = config.preloadAhead,
            behind = ReaderConfig.PRELOAD_BEHIND,
            size = requestSize,
            skip = visiblePages,
            reloads = host.reloads,
        )
        if (upcomingPages != null) {
            PreloadPages(
                pages = upcomingPages,
                current = 0,
                ahead = config.preloadAhead,
                behind = 0,
                size = requestSize,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pull)
                .pointerInput(zoom) {
                    detectStripZoomGestures(zoom) { velocityY ->
                        zoom.launchUntilNextGesture {
                            listState.scroll { with(fling) { performFling(-velocityY / zoom.scale) } }
                        }
                    }
                }
                .pointerInput(zoom, navigator) {
                    fun run(action: TapAction, position: Offset) {
                        when (action) {
                            TapAction.ToggleZoom -> {
                                val settings = latestConfig
                                zoom.toggle(
                                    focusX = position.x - size.width / 2f,
                                    focusY = position.y - size.height / 2f,
                                    doubleTapScale = settings.doubleTapZoom,
                                    animated = !settings.reduceMotion,
                                )
                            }
                            TapAction.NextPage -> scope.launch { navigator.step(forward = true) }
                            TapAction.PreviousPage -> scope.launch { navigator.step(forward = false) }
                            TapAction.ToggleControls, TapAction.None -> host.onTapAction(action)
                        }
                    }
                    detectTapGestures(
                        onDoubleTap = { position -> run(doubleTapAction(latestConfig.tapMode), position) },
                        onLongPress = { position ->
                            val info = listState.layoutInfo
                            val listHeight = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                            val y = screenToList(position.y, size.height.toFloat(), listHeight, zoom.scale, zoom.offsetY)
                            val items = info.visibleItemsInfo.map { StripItem(it.index, it.offset, it.size) }
                            val item = stripItemAt(items, y)
                            if (item != null && item < pageCount) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                host.onLongPressPage(item)
                            }
                        },
                        onTap = { position ->
                            val zone = tapZoneAt(
                                x = position.x,
                                y = position.y,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                                axis = PagingAxis.Vertical,
                                rightToLeft = false,
                                alwaysRightIsNext = false,
                            )
                            run(singleTapAction(latestConfig.tapMode, zone), position)
                        },
                    )
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (free) Modifier.growWhenZoomedOut(zoom) else Modifier)
                    .graphicsLayer { zoom.applyTo(this) },
                overscrollEffect = null,
            ) {
                items(count = pages.size, key = { it }) { index ->
                    val page = pages[index]
                    key(host.reloads.nonce(index)) {
                        StripPage(
                            page = page,
                            index = index,
                            width = pageWidth,
                            requestSize = requestSize,
                            placeholderAspect = aspects[index] ?: page.knownAspect() ?: DEFAULT_PAGE_ASPECT,
                            onAspect = { aspect -> if (aspects[index] != aspect) aspects[index] = aspect },
                            host = host,
                            colorFilter = colorFilter,
                            reduceMotion = config.reduceMotion,
                        )
                    }
                }
                if (endContent != null) {
                    item(key = "chapter-end") { endContent(pull, Modifier.fillMaxWidth()) }
                }
            }
        }

        if (free) {
            FreeZoomOverlays(
                pages = pages,
                currentPage = currentPage,
                listState = listState,
                zoom = zoom,
                aspects = aspects,
                requestSize = requestSize,
                colorFilter = colorFilter,
                controlsShown = host.controlsShown,
            )
        }
    }
}

/**
 * Below 1× the strip is laid out taller than the screen ([stripListHeight]) and centred on it, so
 * that the zoomed-out layer still covers the screen and the list composes the extra pages it shows.
 */
private fun Modifier.growWhenZoomedOut(zoom: ZoomState): Modifier = layout { measurable, constraints ->
    val height = constraints.maxHeight
    val listHeight = ceil(stripListHeight(height.toFloat(), zoom.scale)).toInt().coerceAtLeast(height)
    val placeable = measurable.measure(constraints.copy(minHeight = listHeight, maxHeight = listHeight))
    layout(constraints.maxWidth, height) {
        placeable.place(0, (height - listHeight) / 2)
    }
}

/** 自由缩放's zoom level pill and minimap, over the strip. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FreeZoomOverlays(
    pages: List<ReaderPage>,
    currentPage: Int,
    listState: LazyListState,
    zoom: ZoomState,
    aspects: Map<Int, Float>,
    requestSize: CoilSize,
    colorFilter: ColorFilter?,
    controlsShown: () -> Boolean,
) {
    if (pages.isEmpty()) return
    ZoomHint(
        percent = { (zoom.scale * 100f).roundToInt() },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(readerChromeInsets().only(WindowInsetsSides.Top))
            .padding(top = 16.dp),
    )

    // While a finger is on the minimap it stays on the page it started on; otherwise moving the
    // view across a page boundary would swap the page under the finger.
    var heldPage by remember { mutableStateOf<Int?>(null) }
    val shownPage = (heldPage ?: currentPage).coerceIn(0, pages.lastIndex)
    val zoomedIn by remember(zoom) { derivedStateOf { zoom.isZoomedIn } }

    fun itemOf(index: Int): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    fun listHeight(): Float {
        val info = listState.layoutInfo
        return (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    }

    // The bottom toolbar would cover it while the controls are up.
    AnimatedVisibility(
        visible = zoomedIn && !controlsShown(),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = 12.dp, bottom = MinimapBottomPadding),
        enter = readerFadeIn(),
        exit = readerFadeOut(),
    ) {
        PageMinimap(
            image = pages[shownPage].image,
            aspect = aspects[shownPage] ?: pages[shownPage].knownAspect() ?: DEFAULT_PAGE_ASPECT,
            requestSize = requestSize,
            colorFilter = colorFilter,
            visibleFraction = {
                itemOf(shownPage)?.let { item ->
                    visiblePageFraction(
                        screenWidth = zoom.containerWidth,
                        screenHeight = zoom.containerHeight,
                        listHeight = listHeight(),
                        scale = zoom.scale,
                        offsetX = zoom.offsetX,
                        offsetY = zoom.offsetY,
                        pageTop = item.offset.toFloat(),
                        pageHeight = item.size.toFloat(),
                    )
                }
            },
            onDragStart = {
                zoom.gestureStarted()
                heldPage = shownPage
            },
            onDrag = { u, v ->
                val item = itemOf(heldPage ?: shownPage)
                if (item != null) {
                    val (dx, dy) = panToPagePoint(
                        u = u,
                        v = v,
                        screenWidth = zoom.containerWidth,
                        screenHeight = zoom.containerHeight,
                        listHeight = listHeight(),
                        scale = zoom.scale,
                        offsetX = zoom.offsetX,
                        offsetY = zoom.offsetY,
                        pageTop = item.offset.toFloat(),
                        pageHeight = item.size.toFloat(),
                    )
                    zoom.panBy(dx, dy)
                }
            },
            onDragEnd = { heldPage = null },
        )
    }
}

@Composable
private fun StripPage(
    page: ReaderPage,
    index: Int,
    width: Dp,
    requestSize: CoilSize,
    placeholderAspect: Float,
    onAspect: (Float) -> Unit,
    host: ReaderPageHost,
    colorFilter: ColorFilter?,
    reduceMotion: Boolean,
) {
    val painter = rememberPagePainter(page.image, index, requestSize, host.reloads)
    val state by painter.state.collectAsState()
    val pageNumber = index + 1
    val retry = host.retry
    when (val current = state) {
        is AsyncImagePainter.State.Success -> {
            val intrinsic = current.painter.intrinsicSize
            val aspect = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                intrinsic.height / intrinsic.width
            } else {
                placeholderAspect
            }
            SideEffect {
                onAspect(aspect)
                host.reloads.markLoaded(index)
            }
            Image(
                painter = current.painter,
                contentDescription = stringResource(R.string.reader_page_number, pageNumber),
                contentScale = ContentScale.FillWidth,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(width * aspect),
            )
        }
        is AsyncImagePainter.State.Error -> {
            val failedAt = remember { retry.epoch }
            LaunchedEffect(retry.epoch) { if (retry.epoch != failedAt) painter.restart() }
            PageFailureBlock(
                pageNumber = pageNumber,
                failure = pageFailureOf(current.result.throwable, page.image),
                onRetry = painter::restart,
                onSwitchServer = if (retry.canSwitchServer && page.image is PageImage.Remote) retry::switchServer else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(max(width * placeholderAspect, PageBlockMinHeight)),
            )
        }
        else -> PageLoadingBlock(
            pageNumber = pageNumber,
            reduceMotion = reduceMotion,
            progress = rememberLoadProgress(page.image, host),
            modifier = Modifier
                .fillMaxWidth()
                .height(width * placeholderAspect)
                .clipToBounds(),
        )
    }
}
