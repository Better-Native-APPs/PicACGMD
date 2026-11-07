package app.picacgmd.feature.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.logic.preloadOrder
import coil3.size.Size as CoilSize

/** What Coil loads for a page. */
internal val PageImage.coilModel: Any
    get() = when (this) {
        is PageImage.Remote -> url
        is PageImage.Local -> file
    }

/**
 * The one request shape used for a page, both on screen and when preloading, so that a preloaded
 * bitmap is found in the memory cache. Pages are decoded no larger than [size] (the viewport width
 * for strips, the viewport for paged modes): this keeps memory bounded however tall a strip gets.
 */
internal fun pageRequest(context: PlatformContext, image: PageImage, size: CoilSize): ImageRequest =
    ImageRequest.Builder(context)
        .data(image.coilModel)
        .size(size)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .crossfade(false)
        .build()

/**
 * Keeps the pages around [current] loading in the background: the current page, [ahead] pages after
 * it and [behind] pages before it. Requests that fall out of the window are cancelled.
 */
@Composable
internal fun PreloadPages(
    pages: List<ReaderPage>,
    current: Int,
    ahead: Int,
    behind: Int,
    size: CoilSize?,
) {
    if (size == null || pages.isEmpty()) return
    val context = LocalPlatformContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val inflight = remember(pages, size) { HashMap<Int, Disposable>() }
    DisposableEffect(inflight) {
        onDispose {
            inflight.values.forEach(Disposable::dispose)
            inflight.clear()
        }
    }
    LaunchedEffect(inflight, current, ahead, behind) {
        val wanted = preloadOrder(current, pages.size, ahead, behind)
        val stale = inflight.keys.filter { it !in wanted }
        for (index in stale) inflight.remove(index)?.dispose()
        for (index in wanted) {
            if (index !in inflight) inflight[index] = loader.enqueue(pageRequest(context, pages[index].image, size))
        }
    }
}
