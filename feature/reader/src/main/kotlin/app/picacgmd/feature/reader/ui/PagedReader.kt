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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
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
import app.picacgmd.feature.reader.logic.fitSize
import app.picacgmd.feature.reader.logic.singleTapAction
import app.picacgmd.feature.reader.logic.stepTarget
import app.picacgmd.feature.reader.logic.tapZoneAt
import kotlinx.coroutines.launch
import coil3.size.Size as CoilSize

/**
 * 翻页: one page per screen. Left to right and right to left use a horizontal pager (reversed for
 * right to left), top to bottom a vertical one. The settled page zooms with two fingers or a
 * double tap; dragging a zoomed page against its edge hands the gesture back to the pager.
 */
@Composable
internal fun PagedReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    config: ReaderConfig,
    retry: PageRetryState,
    onPageChange: (Int) -> Unit,
    onTapAction: (TapAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = clampPage(initialPage, pages.size)) { pages.size }
    val zoom = remember(pagerState) { ZoomState(scope) }
    val vertical = config.direction == ReaderDirection.TopToBottom
    val rightToLeft = config.direction == ReaderDirection.RightToLeft
    val latestConfig by rememberUpdatedState(config)
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val latestOnTapAction by rememberUpdatedState(onTapAction)

    SideEffect {
        zoom.minScale = config.minZoom
        zoom.maxScale = config.maxZoom
        zoom.gestureSpeed = config.gestureSpeed
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { zoom.reset() }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { latestOnPageChange(it) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pagerState, zoom) {
                fun run(action: TapAction, position: Offset) {
                    val settings = latestConfig
                    when (action) {
                        TapAction.ToggleZoom -> zoom.toggle(
                            focusX = position.x - size.width / 2f,
                            focusY = position.y - size.height / 2f,
                            doubleTapScale = settings.doubleTapZoom,
                            animated = !settings.reduceMotion,
                        )
                        TapAction.NextPage, TapAction.PreviousPage -> {
                            val delta = if (action == TapAction.NextPage) 1 else -1
                            val target = stepTarget(pagerState.currentPage, delta, pagerState.pageCount)
                            if (target != null) {
                                scope.launch {
                                    if (settings.noPageAnimation) {
                                        pagerState.scrollToPage(target)
                                    } else {
                                        pagerState.animateScrollToPage(target)
                                    }
                                }
                            }
                        }
                        TapAction.ToggleControls, TapAction.None -> latestOnTapAction(action)
                    }
                }
                detectTapGestures(
                    onDoubleTap = { position -> run(doubleTapAction(latestConfig.tapMode), position) },
                    onTap = { position ->
                        val settings = latestConfig
                        val zone = tapZoneAt(
                            x = position.x,
                            y = position.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            axis = if (settings.direction == ReaderDirection.TopToBottom) PagingAxis.Vertical else PagingAxis.Horizontal,
                            rightToLeft = settings.direction == ReaderDirection.RightToLeft,
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

        PreloadPages(
            pages = pages,
            current = pagerState.currentPage,
            ahead = config.preloadAhead,
            behind = ReaderConfig.PRELOAD_BEHIND,
            size = requestSize,
        )

        val pageContent: @Composable PagerScope.(Int) -> Unit = { index ->
            PagedPage(
                page = pages[index],
                index = index,
                pagerState = pagerState,
                zoom = zoom,
                horizontalPaging = !vertical,
                requestSize = requestSize,
                containerWidth = widthPx.toFloat(),
                containerHeight = heightPx.toFloat(),
                retry = retry,
                reduceMotion = config.reduceMotion,
            )
        }
        if (vertical) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { it },
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                reverseLayout = rightToLeft,
                key = { it },
                pageContent = pageContent,
            )
        }
    }
}

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
    retry: PageRetryState,
    reduceMotion: Boolean,
) {
    val context = LocalPlatformContext.current
    val request = remember(page.image, requestSize) { pageRequest(context, page.image, requestSize) }
    val painter = rememberAsyncImagePainter(model = request)
    val state by painter.state.collectAsState()
    val settled = pagerState.settledPage == index

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
