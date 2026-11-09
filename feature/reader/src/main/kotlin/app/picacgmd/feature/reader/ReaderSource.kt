package app.picacgmd.feature.reader

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Everything the reader needs from the app. The reader never touches the network, the database
 * or the download store itself: the app supplies one implementation for online comics and one for
 * downloaded comics.
 *
 * Suspend functions are called from the reader's composition scope (main thread); implementations
 * switch dispatchers themselves. Throwing (anything except cancellation) makes the reader show an
 * inline error with 「重试」.
 *
 * Images are loaded through Coil's singleton `ImageLoader`, so the app must register one that can
 * fetch `http(s)` URLs (for example with `coil-network-okhttp`). Local files need nothing extra.
 */
@Stable
interface ReaderSource {
    /** Comic title, shown under the chapter title in the reader chrome. */
    val comicTitle: String

    /** All chapters in reading order; index 0 is the first chapter. */
    suspend fun loadChapters(): List<ReaderChapter>

    /** Pages of [chapter] in reading order. */
    suspend fun loadPages(chapter: ReaderChapter): List<ReaderPage>

    /**
     * Persists the reading position. Called on the main thread when the visible page settles
     * (debounced) and once more when the reader leaves composition. Must not block.
     */
    fun saveProgress(progress: ReaderProgress)

    /** True when failed network pages may offer 「换分流」 (switch image server). */
    val canSwitchImageServer: Boolean get() = false

    /**
     * Switches to the next image server. Return true when a switch happened; the reader then
     * retries every page that failed to load.
     */
    suspend fun switchImageServer(): Boolean = false

    /**
     * Emits when remote page addresses may have changed, for example after the image server was
     * switched. The reader then passes every page it holds through [refreshPage]: pages not yet
     * loaded, and pages that failed, load again from the new address.
     */
    val pageAddressChanges: Flow<Unit>? get() = null

    /** [page] with its current address; the default keeps it unchanged. */
    fun refreshPage(page: ReaderPage): ReaderPage = page

    /** Cover shown on the chapter-end card, or null to show none. */
    val comicCover: PageImage? get() = null

    /** Comment count shown on the chapter-end card (「评论 N」), or null when unknown. */
    val commentCount: Int? get() = null

    /**
     * Download progress of a remote page, keyed by [PageImage.Remote.url] exactly as given.
     * Returns null when the source can't report progress (the default, and for downloaded comics).
     * The flow's value is null while the total size is unknown, otherwise a fraction in 0f..1f.
     */
    fun imageLoadProgress(url: String): StateFlow<Float?>? = null
}

/** One chapter (话) of the comic. */
@Immutable
data class ReaderChapter(
    /** Stable id from the app, for example the episode id. */
    val id: String,
    /** Display title such as 「第 12 话 星屑海」. */
    val title: String,
    /** Read before; dimmed in the chapter list. */
    val isRead: Boolean = false,
    /** Downloaded and readable offline; marked in the chapter list and the chapter-end card. */
    val isDownloaded: Boolean = false,
)

/** One page image of a chapter. */
@Immutable
data class ReaderPage(
    val image: PageImage,
    /** Pixel width when known in advance (downloaded files), otherwise 0. */
    val width: Int = 0,
    /** Pixel height when known in advance (downloaded files), otherwise 0. */
    val height: Int = 0,
)

/** Where a page image comes from. Every case is loaded by Coil. */
@Immutable
sealed interface PageImage {
    /**
     * An `http(s)` image; needs the app's network-capable singleton `ImageLoader`.
     *
     * [cacheKey] names the image independently of the server it is fetched from, so the disk
     * cache survives a server switch; null uses [url].
     */
    @Immutable
    data class Remote(val url: String, val cacheKey: String? = null) : PageImage {
        val stableKey: String get() = cacheKey ?: url
    }

    /** A file on disk, typically a downloaded page. */
    @Immutable
    data class Local(val file: File) : PageImage

    /** A persisted SAF document URI; stays local and never uses the network image loader. */
    @Immutable
    data class Document(val uri: String) : PageImage
}

/** Reading position reported through [ReaderSource.saveProgress]. */
@Immutable
data class ReaderProgress(
    /** Index into [ReaderSource.loadChapters]. */
    val chapterIndex: Int,
    val chapter: ReaderChapter,
    /** Zero-based page index inside the chapter. */
    val pageIndex: Int,
    val pageCount: Int,
)

/**
 * 分流 / 图片分流 for the settings sheet. The app owns the route pickers; the reader only shows the
 * current labels and asks the app to open a picker.
 */
@Immutable
data class ReaderRouteControls(
    /** Current API route, such as 「分流 2」. */
    val apiLabel: String,
    /** Current image route, such as 「不分流」. */
    val imageLabel: String,
    val onOpenApiRoutes: () -> Unit,
    val onOpenImageRoutes: () -> Unit,
)
