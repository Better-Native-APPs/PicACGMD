package app.picacgmd.feature.comics

import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.domain.repository.FilteredPage
import app.picacgmd.core.network.ComicSimple
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) {
    val comics: List<ComicSimple> get() = entries.mapNotNull { (it as? ComicEntry.Comic)?.comic }
    val hasNextPage: Boolean get() = page < pages
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
    var mode: PaginationMode = PaginationMode.INFINITE
    private var job: Job? = null

    fun reload() = jumpTo(1)

    fun jumpTo(page: Int) {
        job?.cancel()
        mutableState.update { it.copy(loading = true, error = null, appendError = null, appending = false) }
        job = scope.launch {
            try {
                val result = fetch(page.coerceAtLeast(1))
                mutableState.value = ComicPageState(
                    entries = result.toEntries(),
                    page = result.page,
                    pages = result.pages,
                    total = result.total,
                    hiddenCount = result.hiddenCount,
                    loading = false,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = error.readable()) }
            }
        }
    }

    fun loadNext() {
        val current = mutableState.value
        if (mode != PaginationMode.INFINITE || current.loading || current.appending || !current.hasNextPage) return
        if (current.appendError != null) return
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
                mutableState.update { state ->
                    val known = state.entries.mapNotNullTo(HashSet()) { (it as? ComicEntry.Comic)?.comic?.id }
                    state.copy(
                        entries = state.entries + result.toEntries { it.id !in known },
                        page = result.page,
                        pages = result.pages,
                        total = result.total,
                        hiddenCount = state.hiddenCount + result.hiddenCount,
                        appending = false,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableState.update { it.copy(appending = false, appendError = error.readable()) }
            }
        }
    }
}

private fun FilteredPage<ComicSimple>.toEntries(keep: (ComicSimple) -> Boolean = { true }): List<ComicEntry> = buildList {
    if (hiddenCount > 0) add(ComicEntry.Hidden(page, hiddenCount))
    items.filter(keep).forEach { add(ComicEntry.Comic(it, page)) }
}

internal fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
