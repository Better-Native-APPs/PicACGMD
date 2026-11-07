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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.StripPaging
import app.picacgmd.feature.reader.logic.PagingAxis
import app.picacgmd.feature.reader.logic.StripItem
import app.picacgmd.feature.reader.logic.TapAction
import app.picacgmd.feature.reader.logic.clampPage
import app.picacgmd.feature.reader.logic.decodeEdge
import app.picacgmd.feature.reader.logic.doubleTapAction
import app.picacgmd.feature.reader.logic.singleTapAction
import app.picacgmd.feature.reader.logic.stepTarget
import app.picacgmd.feature.reader.logic.stripCurrentPage
import app.picacgmd.feature.reader.logic.stripDecodeHeight
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
 */
@Composable
internal fun StripReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    config: ReaderConfig,
    retry: PageRetryState,
    onPageChange: (Int) -> Unit,
    onTapAction: (TapAction) -> Unit,
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
    val latestConfig by rememberUpdatedState(config)
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val latestOnTapAction by rememberUpdatedState(onTapAction)

    SideEffect {
        zoom.minScale = 1f
        zoom.maxScale = config.maxZoom
        zoom.gestureSpeed = config.gestureSpeed
    }

    val currentPage by remember(listState, zoom) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
            stripCurrentPage(
                visible = info.visibleItemsInfo.map { StripItem(it.index, it.offset, it.size) },
                viewportSize = viewport,
                atStart = !listState.canScrollBackward,
                atEnd = !listState.canScrollForward,
                probeFraction = (0.5f - zoom.offsetY / (zoom.scale * viewport)).coerceIn(0f, 1f),
            )
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { currentPage }.collect { latestOnPageChange(it) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(zoom) {
                detectStripZoomGestures(zoom) { velocityY ->
                    zoom.launchUntilNextGesture {
                        listState.scroll { with(fling) { performFling(-velocityY / zoom.scale) } }
                    }
                }
            }
            .pointerInput(zoom) {
                fun run(action: TapAction, position: Offset) {
                    val settings = latestConfig
                    when (action) {
                        TapAction.ToggleZoom -> zoom.toggle(
                            focusX = position.x - size.width / 2f,
                            focusY = position.y - size.height / 2f,
                            doubleTapScale = settings.doubleTapZoom,
                            animated = !settings.reduceMotion,
                        )
                        TapAction.NextPage, TapAction.PreviousPage -> scope.launch {
                            val forward = action == TapAction.NextPage
                            when (settings.stripPaging) {
                                StripPaging.ByScreen -> {
                                    val distance = stripStepDistance(size.height, settings.scrollFraction, zoom.scale)
                                    val delta = if (forward) distance else -distance
                                    if (settings.noPageAnimation) listState.scrollBy(delta) else listState.animateScrollBy(delta)
                                }
                                StripPaging.ByImage -> {
                                    val target = stepTarget(currentPage, if (forward) 1 else -1, pages.size)
                                    if (target != null) {
                                        if (settings.noPageAnimation) listState.scrollToItem(target) else listState.animateScrollToItem(target)
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
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { zoom.applyTo(this) },
        ) {
            items(count = pages.size, key = { it }) { index ->
                val page = pages[index]
                StripPage(
                    page = page,
                    pageNumber = index + 1,
                    width = pageWidth,
                    requestSize = requestSize,
                    placeholderAspect = aspects[index] ?: page.knownAspect() ?: DEFAULT_PAGE_ASPECT,
                    onAspect = { aspect -> if (aspects[index] != aspect) aspects[index] = aspect },
                    retry = retry,
                    reduceMotion = config.reduceMotion,
                )
            }
        }
    }
}

@Composable
private fun StripPage(
    page: ReaderPage,
    pageNumber: Int,
    width: Dp,
    requestSize: CoilSize,
    placeholderAspect: Float,
    onAspect: (Float) -> Unit,
    retry: PageRetryState,
    reduceMotion: Boolean,
) {
    val context = LocalPlatformContext.current
    val request = remember(page.image, requestSize) { pageRequest(context, page.image, requestSize) }
    val painter = rememberAsyncImagePainter(model = request)
    val state by painter.state.collectAsState()
    when (val current = state) {
        is AsyncImagePainter.State.Success -> {
            val intrinsic = current.painter.intrinsicSize
            val aspect = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                intrinsic.height / intrinsic.width
            } else {
                placeholderAspect
            }
            SideEffect { onAspect(aspect) }
            Image(
                painter = current.painter,
                contentDescription = stringResource(R.string.reader_page_number, pageNumber),
                contentScale = ContentScale.FillWidth,
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
