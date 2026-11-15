package app.picacgmd.feature.comics

import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.domain.repository.FilteredPage
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.paging.Dedupe
import app.picacgmd.paging.PageAccumulator
import app.picacgmd.paging.PageData
import app.picacgmd.paging.PageWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One row of a comic result list: a comic, or the slot left by comics hidden by shield rules. */
sealed interface ComicEntry {
    val key: String

    data class Comic(val comic: ComicSimple, val page: Int) : ComicEntry {
        override val key: String get() = "c-${comic.id}-$page"
    }

    data class Hidden(val page: Int, val count: Int) : ComicEntry {
        override val key: String get() = "h-$page"
    }
}

data class ComicPageState(
    val entries: List<ComicEntry> = emptyList(),
    /** Last loaded page (infinite) or the page on screen (buttons). 0 before the first load. */
    val page: Int = 0,
    val pages: Int = 0,
    val total: Int = 0,
    val hiddenCount: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val appending: Boolean = false,
    val appendError: String? = null,
    /**
     * Goes up whenever a load ends, whatever came of it, and when the paging mode changes. A request made by
     * scrolling names the revision it saw and is made once for each.
     */
    val revision: Int = 0,
) {
    // Keep this outside the constructor so equality, hashCode, and copy ignore the cached value.
    val comics: List<ComicSimple> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        entries.mapNotNull { (it as? ComicEntry.Comic)?.comic }
    }
    val hasNextPage: Boolean get() = page < pages
    /** Scrolling may ask for the next page: nothing is in flight, and a failed page waits for a retry. */
    val canLoadNext: Boolean get() = !loading && !appending && hasNextPage && appendError == null
    val isEmpty: Boolean get() = !loading && error == null && entries.none { it is ComicEntry.Comic }
}

/**
 * Pages through a filtered comic source. Infinite mode appends; button mode replaces the page on screen.
 * Comics removed by shield rules leave a [ComicEntry.Hidden] slot so the list explains the gap (board 09).
 */
class ComicPager(
    private val scope: CoroutineScope,
    private val fetch: suspend (page: Int) -> FilteredPage<ComicSimple>,
) {
    private val mutableState = MutableStateFlow(ComicPageState())
    val state: StateFlow<ComicPageState> = mutableState.asStateFlow()
    /** What scrolling may ask for depends on the mode, so a change of it is a new revision. */
    var mode: PaginationMode = PaginationMode.INFINITE
        set(value) {
            if (field == value) return
            field = value
            mutableState.update { it.copy(revision = it.revision + 1) }
        }
    private var job: Job? = null
    private val accumulator = PageAccumulator<ComicEntry, String, Int>(
        initial = PageData(items = emptyList(), page = 0, pages = 0, total = 0, meta = 0),
        keyOf = { (it as? ComicEntry.Comic)?.comic?.id },
        replaceDedupe = Dedupe.NONE,
        appendDedupe = Dedupe.AGAINST_OLD,
        appendMeta = { old, added -> old + added },
    )

    fun reload() = jumpTo(1)

    fun jumpTo(page: Int) {
        job?.cancel()
        mutableState.update { it.copy(loading = true, error = null, appendError = null, appending = false) }
        job = scope.launch {
            try {
                val result = fetch(page.coerceAtLeast(1))
                ensureActive()
                publishPage(result, PageWrite.REPLACE)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ensureActive()
                mutableState.update { it.copy(loading = false, error = error.readable(), revision = it.revision + 1) }
            }
        }
    }

    /**
     * The next page, as scrolling asks for it. [revision] is the one the list on screen was at: a request made
     * for an earlier one is turned down, because the rows that were scrolled to are no longer the rows there are.
     * Nor does scrolling go on where the next page was answered with an earlier one, see
     * [PageAccumulator.nextPageStalled].
     */
    fun loadNext(revision: Int = mutableState.value.revision) {
        val current = mutableState.value
        if (mode != PaginationMode.INFINITE || revision != current.revision || !current.canLoadNext) return
        if (accumulator.nextPageStalled) return
        appendNext()
    }

    fun retryAppend() {
        mutableState.update { it.copy(appendError = null) }
        appendNext()
    }

    private fun appendNext() {
        val next = mutableState.value.page + 1
        mutableState.update { it.copy(appending = true, appendError = null) }
        job = scope.launch {
            try {
                val result = fetch(next)
                ensureActive()
                publishPage(result, PageWrite.APPEND, requested = next)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ensureActive()
                mutableState.update { it.copy(appending = false, appendError = error.readable(), revision = it.revision + 1) }
            }
        }
    }

    // Keep merge and publication in one non-suspending step after the cancellation check.
    private fun publishPage(result: FilteredPage<ComicSimple>, write: PageWrite, requested: Int = result.page) {
        val data = accumulator.commit(
            PageData(result.toEntries(), result.page, result.pages, result.total, result.hiddenCount),
            write,
            requested,
        ).data
        when (write) {
            PageWrite.REPLACE -> mutableState.value = ComicPageState(
                entries = data.items,
                page = data.page,
                pages = data.pages,
                total = data.total,
                hiddenCount = data.meta,
                loading = false,
                revision = mutableState.value.revision + 1,
            )
            PageWrite.APPEND -> mutableState.update {
                it.copy(
                    entries = data.items,
                    page = data.page,
                    pages = data.pages,
                    total = data.total,
                    hiddenCount = data.meta,
                    appending = false,
                    revision = it.revision + 1,
                )
            }
        }
    }
}

private fun FilteredPage<ComicSimple>.toEntries(): List<ComicEntry> = buildList {
    if (hiddenCount > 0) add(ComicEntry.Hidden(page, hiddenCount))
    items.forEach { add(ComicEntry.Comic(it, page)) }
}

internal fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
