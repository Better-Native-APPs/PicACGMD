package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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
import app.picacgmd.feature.reader.logic.screenToContent
import app.picacgmd.feature.reader.logic.singleTapAction
import app.picacgmd.feature.reader.logic.stripCurrentPage
import app.picacgmd.feature.reader.logic.stripDecodeHeight
import app.picacgmd.feature.reader.logic.stripItemAt
import app.picacgmd.feature.reader.logic.stripStepDistance
import app.picacgmd.feature.reader.logic.tapZoneAt
import kotlinx.coroutines.launch
import coil3.size.Size as CoilSize

/** Height / width used for a page whose size is not known yet (a typical manga page). */
internal const val DEFAULT_PAGE_ASPECT: Float = 1.42f

internal fun ReaderPage.knownAspect(): Float? =
    if (width > 0 && height > 0) height.toFloat() / width else null

/**
 * 条漫: every page at full width, no gaps. Pages are decoded at the viewport width and only the
 * visible ones stay composed, so memory stays flat however long the chapter is. Two fingers zoom
 * the whole strip (up to [ReaderConfig.maxZoom]); a double tap zooms to
 * [ReaderConfig.doubleTapZoom] and back.
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
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = clampPage(initialPage, pages.size))
    val zoom = remember(listState) {
        ZoomState(scope).also { state ->
            state.verticalScroller = { dy -> -listState.dispatchRawDelta(-dy / state.scale) * state.scale }
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
        zoom.minScale = 1f
        zoom.maxScale = config.maxZoom
        zoom.gestureSpeed = config.gestureSpeed
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
        snapshotFlow { currentPage }.collect { host.onPageChange(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { endTakesOver }.collect { host.onChapterEndVisible(it) }
    }
    DisposableEffect(host) {
        onDispose { host.onChapterEndVisible(false) }
    }

    val navigator = remember(listState, zoom, pageCount) {
        object : PageNavigator {
            override suspend fun step(forward: Boolean) {
                val settings = latestConfig
                val instant = settings.noPageAnimation || settings.reduceMotion
                when (settings.stripPaging) {
                    StripPaging.ByScreen -> {
                        if (forward && !listState.canScrollForward) {
                            host.onPastEnd()
                        } else {
                            val info = listState.layoutInfo
                            val viewport = info.viewportEndOffset - info.viewportStartOffset
                            val distance = stripStepDistance(viewport, settings.scrollFraction, zoom.scale)
                            val delta = if (forward) distance else -distance
                            if (instant) listState.scrollBy(delta) else listState.animateScrollBy(delta)
                        }
                    }
                    StripPaging.ByImage -> {
                        val target = currentPage + if (forward) 1 else -1
                        val endVisible = listState.layoutInfo.visibleItemsInfo.any { it.index >= pageCount }
                        when {
                            target < 0 -> Unit
                            target >= pageCount && (!hasEndItem || endVisible) -> host.onPastEnd()
                            instant -> listState.scrollToItem(target)
                            else -> listState.animateScrollToItem(target)
                        }
                    }
                }
            }

            override suspend fun jumpTo(page: Int) {
                listState.scrollToItem(clampPage(page, pageCount))
            }
        }
    }
    RegisterNavigator(host.navigator, navigator)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
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
                        val y = screenToContent(position.y, size.height / 2f, zoom.scale, zoom.offsetY)
                        val items = listState.layoutInfo.visibleItemsInfo.map { StripItem(it.index, it.offset, it.size) }
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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
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
            modifier = Modifier
                .fillMaxWidth()
                .height(width * placeholderAspect)
                .clipToBounds(),
        )
    }
}
