package app.picacgmd.feature.me

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.network.MyComment
import app.picacgmd.paging.Dedupe
import app.picacgmd.paging.PageAccumulator
import app.picacgmd.paging.PageData
import app.picacgmd.paging.PageWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class MyCommentsState(
    val items: List<MyComment> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val loading: Boolean = true,
    val error: String? = null,
    /**
     * Goes up whenever a load ends, whatever came of it. A request made by scrolling names the revision it saw
     * and is made once for each.
     */
    val revision: Int = 0,
) {
    /** Scrolling may ask for the next page: nothing is in flight, and a failed page waits for a retry. */
    val canLoadNext: Boolean get() = !loading && error == null && page in 1 until pages
}

internal class MyCommentsStateHolder(
    private val scope: CoroutineScope,
    private val repository: CommentRepository,
) {
    private val accumulator = PageAccumulator<MyComment, String, Unit>(
        initial = PageData(emptyList(), page = 0, pages = 1, total = 0, meta = Unit),
        keyOf = { it.id },
        replaceDedupe = Dedupe.NONE,
        appendDedupe = Dedupe.NONE,
        appendMeta = { _, _ -> },
    )

    var state by mutableStateOf(MyCommentsState())
        private set
    var refreshing by mutableStateOf(false)
        private set

    fun load(page: Int) {
        state = state.copy(loading = true, error = null)
        scope.launch {
            try {
                val result = repository.myComments(page)
                val data = accumulator.commit(
                    PageData(result.docs, result.page, result.pages.coerceAtLeast(1), result.total, Unit),
                    if (page == 1) PageWrite.REPLACE else PageWrite.APPEND,
                    requested = page,
                ).data
                state = MyCommentsState(
                    items = data.items,
                    page = data.page,
                    pages = data.pages,
                    loading = false,
                    revision = state.revision + 1,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                state = state.copy(loading = false, error = exception.message ?: "加载失败", revision = state.revision + 1)
            } finally {
                refreshing = false
            }
        }
    }

    fun refresh() {
        refreshing = true
        load(1)
    }

    fun retryFirstLoad() = load(1)

    fun retryTail() = load(state.page + 1)

    /**
     * The next page, as scrolling asks for it. [revision] is the one the list on screen was at: a request made
     * for an earlier one is turned down, because the rows that were scrolled to are no longer the rows there are.
     * Nor does scrolling go on where the next page was answered with an earlier one, see
     * [PageAccumulator.nextPageStalled].
     */
    fun loadNext(revision: Int = state.revision) {
        val current = state
        if (revision != current.revision || !current.canLoadNext) return
        if (accumulator.nextPageStalled) return
        load(current.page + 1)
    }
}
