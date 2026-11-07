package app.picacgmd.feature.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.picacgmd.feature.reader.logic.clampPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    var content: ChapterContent by mutableStateOf(ChapterContent.Loading)
        private set

    private var job: Job? = null
    private var generation = 0

    /** Opens chapter [index] at [startPage]; the default reopens the current position. */
    fun open(index: Int = chapterIndex, startPage: Int = page) {
        job?.cancel()
        chapterIndex = index.coerceAtLeast(0)
        page = startPage.coerceAtLeast(0)
        content = ChapterContent.Loading
        job = scope.launch {
            content = try {
                if (chapters.isEmpty()) chapters = source.loadChapters()
                if (chapters.isEmpty()) {
                    ChapterContent.NoChapters
                } else {
                    val resolved = chapterIndex.coerceIn(0, chapters.size - 1)
                    chapterIndex = resolved
                    val chapter = chapters[resolved]
                    val pages = source.loadPages(chapter)
                    if (pages.isEmpty()) {
                        ChapterContent.Empty(chapter)
                    } else {
                        page = clampPage(page, pages.size)
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

    fun onPageChanged(index: Int) {
        val ready = content as? ChapterContent.Ready ?: return
        page = clampPage(index, ready.pages.size)
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
