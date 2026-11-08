package app.picacgmd.feature.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.picacgmd.feature.reader.logic.clampPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** What the reader shows for the open chapter. */
internal sealed interface ChapterContent {
    data object Loading : ChapterContent

    data class Failed(val error: Throwable) : ChapterContent

    data object NoChapters : ChapterContent

    data class Empty(val chapter: ReaderChapter) : ChapterContent

    data class Ready(
        val chapterIndex: Int,
        val chapter: ReaderChapter,
        val pages: List<ReaderPage>,
        val startPage: Int,
        /** Changes every time a chapter is (re)opened, to reset the page views. */
        val generation: Int,
    ) : ChapterContent
}

/** Chapter list, open chapter and reading position; loads through [source]. */
@Stable
internal class ReaderSession(
    private val source: ReaderSource,
    private val scope: CoroutineScope,
    startChapter: Int,
    startPage: Int,
) {
    var chapters: List<ReaderChapter> by mutableStateOf(emptyList())
        private set
    var chapterIndex by mutableIntStateOf(startChapter.coerceAtLeast(0))
        private set

    /** Zero-based page the reader is on inside the open chapter. */
    var page by mutableIntStateOf(startPage.coerceAtLeast(0))
        private set

    /** Last page on screen: [page] itself, or the second page of a two-page spread. */
    var pageEnd by mutableIntStateOf(startPage.coerceAtLeast(0))
        private set
    var content: ChapterContent by mutableStateOf(ChapterContent.Loading)
        private set

    /** Pages of the chapter after the open one, once they have been fetched ahead of time. */
    var nextChapterPages: List<ReaderPage>? by mutableStateOf(null)
        private set

    private var finishedChapters: Set<String> by mutableStateOf(emptySet())
    private var job: Job? = null
    private var generation = 0
    private var prefetch: Prefetch? = null

    private class Prefetch(val chapterId: String, val pages: Deferred<List<ReaderPage>?>)

    val previousChapter: ReaderChapter? get() = if (chapterIndex > 0) chapters.getOrNull(chapterIndex - 1) else null
    val nextChapter: ReaderChapter? get() = chapters.getOrNull(chapterIndex + 1)

    /** Read before, or finished since the reader opened. */
    fun isRead(chapter: ReaderChapter): Boolean = chapter.isRead || chapter.id in finishedChapters

    /**
     * Opens chapter [index] at [startPage]; the default reopens the current position. A chapter whose
     * pages were fetched ahead appears at once, without passing through [ChapterContent.Loading].
     */
    fun open(index: Int = chapterIndex, startPage: Int = page) {
        job?.cancel()
        (content as? ChapterContent.Ready)?.let { ready ->
            if (maxOf(page, pageEnd) >= ready.pages.lastIndex) finishedChapters = finishedChapters + ready.chapter.id
        }
        chapterIndex = index.coerceAtLeast(0)
        page = startPage.coerceAtLeast(0)
        pageEnd = page
        nextChapterPages = null
        content = ChapterContent.Loading
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            content = try {
                if (chapters.isEmpty()) chapters = source.loadChapters()
                if (chapters.isEmpty()) {
                    ChapterContent.NoChapters
                } else {
                    val resolved = chapterIndex.coerceIn(0, chapters.size - 1)
                    chapterIndex = resolved
                    val chapter = chapters[resolved]
                    val pages = takePrefetched(chapter) ?: source.loadPages(chapter)
                    if (pages.isEmpty()) {
                        ChapterContent.Empty(chapter)
                    } else {
                        page = clampPage(page, pages.size)
                        pageEnd = page
                        ChapterContent.Ready(resolved, chapter, pages, page, ++generation)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ChapterContent.Failed(error)
            }
        }
    }

    fun openNext() {
        if (nextChapter != null) open(chapterIndex + 1, 0)
    }

    fun openPrevious() {
        if (previousChapter != null) open(chapterIndex - 1, 0)
    }

    /** 重载整页: asks the source for the chapter's pages again, staying on the current page. */
    fun reload() {
        prefetch?.pages?.cancel()
        prefetch = null
        open()
    }

    /** Fetches the next chapter's page list ahead of time, to show its size and to open it at once. */
    fun prefetchNext() {
        val next = nextChapter ?: return
        if (prefetch?.chapterId == next.id) return
        prefetch?.pages?.cancel()
        val pages = scope.async {
            try {
                source.loadPages(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Opening the chapter asks again and reports the failure properly.
                null
            }
        }
        val started = Prefetch(next.id, pages)
        prefetch = started
        scope.launch {
            val result = pages.await()
            if (prefetch === started) {
                if (result == null) prefetch = null else nextChapterPages = result
            }
        }
    }

    /** Pages fetched ahead for [chapter], if any; any other fetch still running is no longer wanted. */
    private suspend fun takePrefetched(chapter: ReaderChapter): List<ReaderPage>? {
        val current = prefetch ?: return null
        prefetch = null
        if (current.chapterId != chapter.id) {
            current.pages.cancel()
            return null
        }
        return current.pages.await()
    }

    /** The page on screen is [index]; a two-page spread also shows the pages up to [lastVisible]. */
    fun onPageChanged(index: Int, lastVisible: Int = index) {
        val ready = content as? ChapterContent.Ready ?: return
        page = clampPage(index, ready.pages.size)
        pageEnd = clampPage(maxOf(index, lastVisible), ready.pages.size)
    }

    /** Current position, or null while nothing is open. */
    fun progress(): ReaderProgress? {
        val ready = content as? ChapterContent.Ready ?: return null
        return ReaderProgress(
            chapterIndex = ready.chapterIndex,
            chapter = ready.chapter,
            pageIndex = clampPage(page, ready.pages.size),
            pageCount = ready.pages.size,
        )
    }
}
