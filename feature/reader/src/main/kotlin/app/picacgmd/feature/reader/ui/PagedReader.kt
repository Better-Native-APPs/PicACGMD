package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderDirection
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.logic.PagingAxis
import app.picacgmd.feature.reader.logic.TapAction
import app.picacgmd.feature.reader.logic.clampPage
import app.picacgmd.feature.reader.logic.decodeEdge
import app.picacgmd.feature.reader.logic.doubleTapAction
import app.picacgmd.feature.reader.logic.effectiveDirection
import app.picacgmd.feature.reader.logic.fitSize
import app.picacgmd.feature.reader.logic.forwardSwipeFor
import app.picacgmd.feature.reader.logic.singleTapAction
import app.picacgmd.feature.reader.logic.stepTarget
import app.picacgmd.feature.reader.logic.tapZoneAt
import kotlinx.coroutines.launch
import coil3.size.Size as CoilSize

/**
 * 翻页: one page per screen. Left to right and right to left use a horizontal pager (reversed for
 * right to left), top to bottom a vertical one. The settled page zooms with two fingers or a
 * double tap; dragging a zoomed page against its edge hands the gesture back to the pager.
 *
 * [endContent], when there is a next chapter, is one more page after the last; going on from there
 * leaves the chapter through [ReaderPageHost.onPastEnd].
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
) {
    val scope = rememberCoroutineScope()
    val pageCount = pages.size
    val hasEndPage = endContent != null
    val pagerState = rememberPagerState(initialPage = clampPage(initialPage, pageCount)) {
        pageCount + if (hasEndPage) 1 else 0
    }
    val zoom = remember(pagerState) { ZoomState(scope) }
    val direction = effectiveDirection(config.mode, config.direction)
    val vertical = direction == ReaderDirection.TopToBottom
    val rightToLeft = direction == ReaderDirection.RightToLeft
    val pull = rememberChapterPull(forwardSwipeFor(direction), host.onPastEnd)
    val colorFilter = rememberPageColorFilter(config.filter)
    val haptics = LocalHapticFeedback.current
    val latestConfig by rememberUpdatedState(config)

    SideEffect {
        zoom.minScale = config.minZoom
        zoom.maxScale = config.maxZoom
        zoom.gestureSpeed = config.gestureSpeed
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { zoom.reset() }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { host.onPageChange(it) }
    }
    LaunchedEffect(pagerState, pageCount) {
        snapshotFlow { pagerState.currentPage >= pageCount }.collect { host.onChapterEndVisible(it) }
    }
    DisposableEffect(host) {
        onDispose { host.onChapterEndVisible(false) }
    }

    val navigator = remember(pagerState, pageCount) {
        object : PageNavigator {
            override suspend fun step(forward: Boolean) {
                val settings = latestConfig
                val instant = settings.noPageAnimation || settings.reduceMotion
                val target = stepTarget(pagerState.targetPage, if (forward) 1 else -1, pagerState.pageCount)
                when {
                    target == null -> if (forward) host.onPastEnd()
                    instant -> pagerState.scrollToPage(target)
                    else -> pagerState.animateScrollToPage(target)
                }
            }

            override suspend fun jumpTo(page: Int) {
                pagerState.scrollToPage(clampPage(page, pageCount))
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
                        TapAction.ToggleZoom -> if (pagerState.settledPage < pageCount) {
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
                    onLongPress = {
                        val settled = pagerState.settledPage
                        if (settled < pageCount) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            host.onLongPressPage(settled)
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
        val requestSize = remember(widthPx, heightPx) { CoilSize(decodeEdge(widthPx), decodeEdge(heightPx)) }
        val current = pagerState.currentPage

        PreloadPages(
            pages = pages,
            current = current,
            ahead = config.preloadAhead,
            behind = ReaderConfig.PRELOAD_BEHIND,
            size = requestSize,
            skip = (current - PAGES_BEYOND_VIEWPORT)..(current + PAGES_BEYOND_VIEWPORT),
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

        val pageContent: @Composable PagerScope.(Int) -> Unit = { index ->
            if (index >= pageCount) {
                ChapterEndPage { inner -> endContent?.invoke(pull, inner) }
            } else {
                key(host.reloads.nonce(index)) {
                    PagedPage(
                        page = pages[index],
                        index = index,
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

/** Pages kept composed on each side of the current one; they load through their own painters. */
private const val PAGES_BEYOND_VIEWPORT = 1

@Composable
private fun PagedPage(
    page: ReaderPage,
    index: Int,
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
    val settled = pagerState.settledPage == index
    val retry = host.retry

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(zoom, index, horizontalPaging) {
                detectPageZoomGestures(zoom, horizontalPaging) { pagerState.settledPage == index }
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
                PageLoadingBlock(pageNumber = index + 1, reduceMotion = reduceMotion, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
