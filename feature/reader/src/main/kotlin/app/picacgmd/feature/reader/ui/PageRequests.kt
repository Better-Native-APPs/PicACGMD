package app.picacgmd.feature.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.logic.preloadOrder
import app.picacgmd.feature.reader.logic.preloadsToCancel
import app.picacgmd.feature.reader.logic.rememberReaderMemoryBudget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import coil3.size.Size as CoilSize

/** What Coil loads for a page. */
internal val PageImage.coilModel: Any
    get() = when (this) {
        is PageImage.Remote -> url
        is PageImage.Local -> file
        is PageImage.Document -> uri
    }

/**
 * The one request shape used for a page, both on screen and when preloading, so that a preloaded
 * bitmap is found in the memory cache. Pages are decoded no larger than [size] (the viewport width
 * for strips, the viewport for paged modes): this keeps memory bounded however tall a strip gets.
 *
 * With [fresh] the request goes around Coil's caches and replaces what they hold.
 */
internal fun pageRequest(
    context: PlatformContext,
    image: PageImage,
    size: CoilSize,
    fresh: Boolean = false,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(image.coilModel)
        .size(size)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .crossfade(false)
        .apply {
            (image as? PageImage.Remote)?.cacheKey?.let { diskCacheKey(it) }
            if (fresh) {
                memoryCachePolicy(CachePolicy.WRITE_ONLY)
                diskCachePolicy(CachePolicy.WRITE_ONLY)
            }
        }
        .build()

/** The painter of page [index]; wrap it in `key(reloads.nonce(index))` so reloading starts over. */
@Composable
internal fun rememberPagePainter(
    image: PageImage,
    index: Int,
    size: CoilSize,
    reloads: PageReloadState,
): AsyncImagePainter {
    val context = LocalPlatformContext.current
    // Keyed on the image's identity, not its address: when the address changes (another image
    // server), a page already on screen stays as it is, while one still loading or one that failed
    // starts over from the new address.
    val identity = (image as? PageImage.Remote)?.stableKey ?: image
    var request by remember(identity, size) { mutableStateOf(pageRequest(context, image, size, fresh = reloads.needsFresh(index))) }
    val painter = rememberAsyncImagePainter(model = request)
    LaunchedEffect(image) {
        if (request.data != image.coilModel && painter.state.value !is AsyncImagePainter.State.Success) {
            request = pageRequest(context, image, size, fresh = reloads.needsFresh(index))
        }
    }
    return painter
}

/**
 * Keeps the pages around [current] loading in the background: the current page, [ahead] pages after
 * it and [behind] pages before it. Requests that fall out of the window are cancelled.
 *
 * Pages in [skip] are on screen (or about to be) and load through their own painters, so no
 * background load is started for them. One that was already running when its page came on screen is
 * left to finish (see [preloadsToCancel]): the image loader lets the page's own request wait for the
 * same download instead of starting a second one, so what was fetched ahead is never thrown away.
 * [loadProgress] is told about every page fetched ahead, so that such a page can show how far its
 * download already is the moment it appears.
 *
 * Pages waiting for a reload are skipped as well: the caches still hold what the reader wants
 * replaced. Low-RAM requests are capped to one slot ahead and one page behind; [nextChapter] image
 * requests are disabled. [pagesPerSlot] keeps the cap aligned with two-page spreads.
 */
@Composable
internal fun PreloadPages(
    pages: List<ReaderPage>,
    current: Int,
    ahead: Int,
    behind: Int,
    size: CoilSize?,
    skip: IntRange = IntRange.EMPTY,
    reloads: PageReloadState? = null,
    pagesPerSlot: Int = 1,
    nextChapter: Boolean = false,
    loadProgress: ((url: String) -> StateFlow<Float?>?)? = null,
) {
    val memoryBudget = rememberReaderMemoryBudget()
    if (nextChapter && !memoryBudget.preloadNextChapterImages) return
    if (size == null || pages.isEmpty()) return
    val window = memoryBudget.preloadWindow(ahead, behind, pagesPerSlot)
    val context = LocalPlatformContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val scope = rememberCoroutineScope()
    // Plain jobs rather than Coil's Disposables: a finished Disposable keeps its result, and with it the
    // bitmap, reachable for as long as the page stays in the window. A finished job keeps nothing, so
    // the memory cache alone decides what stays decoded.
    val inflight = remember(pages, size) { HashMap<Int, Job>() }
    DisposableEffect(inflight) {
        onDispose {
            inflight.values.forEach(Job::cancel)
            inflight.clear()
        }
    }
    LaunchedEffect(inflight, current, window, skip) {
        val wanted = preloadOrder(current, pages.size, window.ahead, window.behind)
            .filter { it !in skip && reloads?.needsFresh(it) != true }
        for (index in preloadsToCancel(inflight.keys, wanted, onScreen = skip)) inflight.remove(index)?.cancel()
        for (index in wanted) {
            if (index !in inflight) {
                val image = pages[index].image
                // Registered before the download starts, or its progress could not be followed later.
                if (image is PageImage.Remote) loadProgress?.invoke(image.url)
                val request = pageRequest(context, image, size)
                inflight[index] = scope.launch { loader.execute(request) }
            }
        }
    }
}
