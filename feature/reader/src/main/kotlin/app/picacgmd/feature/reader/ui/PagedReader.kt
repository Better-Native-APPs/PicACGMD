package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderDirection
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.TwoPageArrangement
import app.picacgmd.feature.reader.logic.HalfAnchor
import app.picacgmd.feature.reader.logic.PageSlots
import app.picacgmd.feature.reader.logic.PagingAxis
import app.picacgmd.feature.reader.logic.SpreadSides
import app.picacgmd.feature.reader.logic.TapAction
import app.picacgmd.feature.reader.logic.decodeEdge
import app.picacgmd.feature.reader.logic.doubleTapAction
import app.picacgmd.feature.reader.logic.effectiveDirection
import app.picacgmd.feature.reader.logic.fitSize
import app.picacgmd.feature.reader.logic.forwardSwipeFor
import app.picacgmd.feature.reader.logic.rememberReaderMemoryBudget
import app.picacgmd.feature.reader.logic.screenToContent
import app.picacgmd.feature.reader.logic.singleTapAction
import app.picacgmd.feature.reader.logic.spreadAnchor
import app.picacgmd.feature.reader.logic.spreadPageAt
import app.picacgmd.feature.reader.logic.spreadSides
import app.picacgmd.feature.reader.logic.stepTarget
import app.picacgmd.feature.reader.logic.tapZoneAt
import kotlinx.coroutines.launch
import coil3.size.Size as CoilSize

/** Pages kept composed on each side of the current one; they load through their own painters. */
private const val PAGES_BEYOND_VIEWPORT = 1

/** The fold between the two pages of a spread. */
private val SpreadGap = 2.dp

/**
 * 翻页 and 双页: one screen at a time. Left to right and right to left use a horizontal pager
 * (reversed for right to left), top to bottom a vertical one. The settled screen zooms with two
 * fingers or a double tap; dragging a zoomed screen against its edge hands the gesture back to the
 * pager.
 *
 * With [spreads] every screen holds two pages side by side, placed by
 * [ReaderConfig.twoPageArrangement] and in reading order (the first page on the right when reading
 * right to left). The cover can have a screen of its own ([ReaderConfig.twoPageCoverAlone]); a lone
 * page (the cover, or an odd last page) is centred on the whole screen.
 *
 * [endContent], when there is a next chapter, is one more screen after the last; going on from
 * there leaves the chapter through [ReaderPageHost.onPastEnd].
 */
@Composable
internal fun PagedReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    config: ReaderConfig,
    host: ReaderPageHost,
    upcomingPages: List<ReaderPage>?,
    endContent: ChapterEndSlot?,
    modifier: Modifier = Modifier,
    spreads: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val memoryBudget = rememberReaderMemoryBudget()
    val pageCount = pages.size
    val slots = PageSlots(pageCount, spreads, config.twoPageCoverAlone)
    val hasEndPage = endContent != null
    val pagerState = rememberPagerState(initialPage = slots.slotOf(initialPage)) {
        slots.count + if (hasEndPage) 1 else 0
    }
    val zoom = remember(pagerState) { ZoomState(scope) }
    val direction = effectiveDirection(config.mode, config.direction)
    val vertical = direction == ReaderDirection.TopToBottom
    val rightToLeft = direction == ReaderDirection.RightToLeft
    val pull = rememberChapterPull(forwardSwipeFor(direction), host.onPastEnd)
    val colorFilter = rememberPageColorFilter(config.filter)
    val haptics = LocalHapticFeedback.current
    val latestConfig by rememberUpdatedState(config)
    val latestSlots by rememberUpdatedState(slots)
    val latestRightToLeft by rememberUpdatedState(rightToLeft)

    SideEffect {
        // Pages open fitted to the screen (1×); a 最小缩放 above that would make the first pinch jump.
        zoom.minScale = minOf(config.minZoom, 1f)
        zoom.maxScale = config.maxZoom
        zoom.gestureSpeed = config.gestureSpeed
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { zoom.reset() }
    }
    LaunchedEffect(pagerState, slots) {
        snapshotFlow { pagerState.currentPage }.collect { slot ->
            val shown = slots.pagesOf(slot)
            // The chapter-end screen counts as the last page.
            if (shown.isEmpty()) host.onPageChange(pageCount - 1, pageCount - 1) else host.onPageChange(shown.first, shown.last)
        }
    }
    LaunchedEffect(pagerState, slots) {
        snapshotFlow { pagerState.currentPage >= slots.count }.collect { host.onChapterEndVisible(it) }
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

    val navigator = remember(pagerState, slots) {
        object : PageNavigator {
            override suspend fun step(forward: Boolean, leaveAtEnd: Boolean): Boolean {
                val settings = latestConfig
                val instant = settings.noPageAnimation || settings.reduceMotion
                val target = stepTarget(pagerState.targetPage, if (forward) 1 else -1, pagerState.pageCount)
                return when {
                    target == null -> (forward && leaveAtEnd).also { leaving -> if (leaving) host.onPastEnd() }
                    instant -> {
                        pagerState.scrollToPage(target)
                        true
                    }
                    else -> {
                        pagerState.animateScrollToPage(target)
                        true
                    }
                }
            }

            override suspend fun jumpTo(page: Int) {
                pagerState.scrollToPage(slots.slotOf(page))
            }
        }
    }
    RegisterNavigator(host.navigator, navigator)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(pull)
            .pointerInput(pagerState, zoom, navigator) {
                fun run(action: TapAction, position: Offset) {
                    when (action) {
                        TapAction.ToggleZoom -> if (pagerState.settledPage < latestSlots.count) {
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
                        val shown = latestSlots.pagesOf(pagerState.settledPage)
                        if (!shown.isEmpty()) {
                            val page = if (shown.first == shown.last) {
                                shown.first
                            } else {
                                val contentX = screenToContent(position.x, size.width / 2f, zoom.scale, zoom.offsetX)
                                spreadPageAt(contentX, size.width.toFloat(), spreadSides(shown.first, shown.last, latestRightToLeft))
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            host.onLongPressPage(page)
                        }
                    },
                    onTap = { position ->
                        val settings = latestConfig
                        val tapDirection = effectiveDirection(settings.mode, settings.direction)
                        val zone = tapZoneAt(
                            x = position.x,
                            y = position.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            axis = if (tapDirection == ReaderDirection.TopToBottom) PagingAxis.Vertical else PagingAxis.Horizontal,
                            rightToLeft = tapDirection == ReaderDirection.RightToLeft,
                            alwaysRightIsNext = settings.alwaysRightIsNext,
                        )
                        run(singleTapAction(settings.tapMode, zone), position)
                    },
                )
            },
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val gapPx = with(LocalDensity.current) { SpreadGap.roundToPx() }
        val requestSize = remember(widthPx, heightPx) { CoilSize(decodeEdge(widthPx), decodeEdge(heightPx)) }
        val halfSize = remember(widthPx, heightPx, gapPx) {
            CoilSize(decodeEdge((widthPx - gapPx) / 2), decodeEdge(heightPx))
        }
        val preloadSize = if (spreads) halfSize else requestSize
        val currentSlot = pagerState.currentPage
        val anchorPage = slots.pagesOf(currentSlot).firstOrNull() ?: (pageCount - 1)
        val preloadWindow = memoryBudget.preloadWindow(
            ahead = config.preloadAhead * slots.perSlot + (slots.perSlot - 1),
            behind = ReaderConfig.PRELOAD_BEHIND * slots.perSlot,
            pagesPerSlot = slots.perSlot,
        )

        PreloadPages(
            pages = pages,
            current = anchorPage,
            // Counted in pages from the first page of the current screen.
            ahead = preloadWindow.ahead,
            behind = preloadWindow.behind,
            size = preloadSize,
            skip = slots.pagesAround(currentSlot, PAGES_BEYOND_VIEWPORT),
            reloads = host.reloads,
            pagesPerSlot = slots.perSlot,
        )
        if (upcomingPages != null && memoryBudget.preloadNextChapterImages) {
            PreloadPages(
                pages = upcomingPages,
                current = 0,
                ahead = config.preloadAhead * slots.perSlot,
                behind = 0,
                size = preloadSize,
                pagesPerSlot = slots.perSlot,
                nextChapter = true,
            )
        }

        val pageContent: @Composable PagerScope.(Int) -> Unit = { slot ->
            val shown = slots.pagesOf(slot)
            when {
                shown.isEmpty() -> ChapterEndPage { inner -> endContent?.invoke(pull, inner) }
                shown.first == shown.last -> key(host.reloads.nonce(shown.first)) {
                    PagedPage(
                        page = pages[shown.first],
                        index = shown.first,
                        slot = slot,
                        pagerState = pagerState,
                        zoom = zoom,
                        horizontalPaging = !vertical,
                        requestSize = requestSize,
                        containerWidth = widthPx.toFloat(),
                        containerHeight = heightPx.toFloat(),
                        host = host,
                        colorFilter = colorFilter,
                        reduceMotion = config.reduceMotion,
                    )
                }
                else -> SpreadPage(
                    pages = pages,
                    sides = spreadSides(shown.first, shown.last, rightToLeft),
                    slot = slot,
                    arrangement = config.twoPageArrangement,
                    pagerState = pagerState,
                    zoom = zoom,
                    requestSize = halfSize,
                    containerWidth = widthPx.toFloat(),
                    containerHeight = heightPx.toFloat(),
                    host = host,
                    colorFilter = colorFilter,
                    reduceMotion = config.reduceMotion,
                )
            }
        }
        if (vertical) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = PAGES_BEYOND_VIEWPORT,
                key = { it },
                overscrollEffect = null,
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = PAGES_BEYOND_VIEWPORT,
                reverseLayout = rightToLeft,
                key = { it },
                overscrollEffect = null,
                pageContent = pageContent,
            )
        }
    }
}

/** One page filling a screen of the pager ([slot]); zooms while it is the settled screen. */
@Composable
private fun PagedPage(
    page: ReaderPage,
    index: Int,
    slot: Int,
    pagerState: PagerState,
    zoom: ZoomState,
    horizontalPaging: Boolean,
    requestSize: CoilSize,
    containerWidth: Float,
    containerHeight: Float,
    host: ReaderPageHost,
    colorFilter: ColorFilter?,
    reduceMotion: Boolean,
) {
    val painter = rememberPagePainter(page.image, index, requestSize, host.reloads)
    val state by painter.state.collectAsState()
    val settled = pagerState.settledPage == slot
    val retry = host.retry

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(zoom, slot, horizontalPaging) {
                detectPageZoomGestures(zoom, horizontalPaging) { pagerState.settledPage == slot }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is AsyncImagePainter.State.Success -> {
                val intrinsic = current.painter.intrinsicSize
                SideEffect {
                    host.reloads.markLoaded(index)
                    if (settled) {
                        val (fitWidth, fitHeight) = if (intrinsic.isSpecified) {
                            fitSize(intrinsic.width, intrinsic.height, containerWidth, containerHeight)
                        } else {
                            containerWidth to containerHeight
                        }
                        zoom.setGeometry(containerWidth, containerHeight, fitWidth, fitHeight)
                    }
                }
                Image(
                    painter = current.painter,
                    contentDescription = stringResource(R.string.reader_page_number, index + 1),
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { if (settled) zoom.applyTo(this) },
                )
            }
            is AsyncImagePainter.State.Error -> {
                SideEffect { if (settled) zoom.setGeometry(containerWidth, containerHeight) }
                val failedAt = remember { retry.epoch }
                LaunchedEffect(retry.epoch) { if (retry.epoch != failedAt) painter.restart() }
                PageFailureBlock(
                    pageNumber = index + 1,
                    failure = pageFailureOf(current.result.throwable, page.image),
                    onRetry = painter::restart,
                    onSwitchServer = if (retry.canSwitchServer && page.image is PageImage.Remote) retry::switchServer else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                SideEffect { if (settled) zoom.setGeometry(containerWidth, containerHeight) }
                PageLoadingBlock(
                    pageNumber = index + 1,
                    reduceMotion = reduceMotion,
                    progress = rememberLoadProgress(page.image, host),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Two pages side by side on one screen of the pager ([slot]). The spread zooms and pans as one
 * picture; each half loads, fails and retries on its own.
 */
@Composable
private fun SpreadPage(
    pages: List<ReaderPage>,
    sides: SpreadSides,
    slot: Int,
    arrangement: TwoPageArrangement,
    pagerState: PagerState,
    zoom: ZoomState,
    requestSize: CoilSize,
    containerWidth: Float,
    containerHeight: Float,
    host: ReaderPageHost,
    colorFilter: ColorFilter?,
    reduceMotion: Boolean,
) {
    val settled = pagerState.settledPage == slot
    SideEffect { if (settled) zoom.setGeometry(containerWidth, containerHeight) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(zoom, slot) {
                detectPageZoomGestures(zoom, horizontalPaging = true) { pagerState.settledPage == slot }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { if (settled) zoom.applyTo(this) },
            // Absolute: the halves are left and right whatever the layout direction.
            horizontalArrangement = Arrangement.Absolute.spacedBy(SpreadGap),
        ) {
            SpreadHalf(
                page = pages[sides.left],
                index = sides.left,
                anchor = spreadAnchor(arrangement, leftHalf = true),
                requestSize = requestSize,
                host = host,
                colorFilter = colorFilter,
                reduceMotion = reduceMotion,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            SpreadHalf(
                page = pages[sides.right],
                index = sides.right,
                anchor = spreadAnchor(arrangement, leftHalf = false),
                requestSize = requestSize,
                host = host,
                colorFilter = colorFilter,
                reduceMotion = reduceMotion,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SpreadHalf(
    page: ReaderPage,
    index: Int,
    anchor: HalfAnchor,
    requestSize: CoilSize,
    host: ReaderPageHost,
    colorFilter: ColorFilter?,
    reduceMotion: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        key(host.reloads.nonce(index)) {
            val painter = rememberPagePainter(page.image, index, requestSize, host.reloads)
            val state by painter.state.collectAsState()
            val retry = host.retry
            when (val current = state) {
                is AsyncImagePainter.State.Success -> {
                    SideEffect { host.reloads.markLoaded(index) }
                    Image(
                        painter = current.painter,
                        contentDescription = stringResource(R.string.reader_page_number, index + 1),
                        alignment = anchor.alignment(),
                        contentScale = ContentScale.Fit,
                        colorFilter = colorFilter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is AsyncImagePainter.State.Error -> {
                    val failedAt = remember { retry.epoch }
                    LaunchedEffect(retry.epoch) { if (retry.epoch != failedAt) painter.restart() }
                    PageFailureBlock(
                        pageNumber = index + 1,
                        failure = pageFailureOf(current.result.throwable, page.image),
                        onRetry = painter::restart,
                        onSwitchServer = if (retry.canSwitchServer && page.image is PageImage.Remote) retry::switchServer else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> PageLoadingBlock(
                    pageNumber = index + 1,
                    reduceMotion = reduceMotion,
                    progress = rememberLoadProgress(page.image, host),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Absolute, not start/end: the halves of a spread are left and right whatever the UI language. */
private fun HalfAnchor.alignment(): Alignment = when (this) {
    HalfAnchor.Left -> AbsoluteAlignment.CenterLeft
    HalfAnchor.Center -> Alignment.Center
    HalfAnchor.Right -> AbsoluteAlignment.CenterRight
}
