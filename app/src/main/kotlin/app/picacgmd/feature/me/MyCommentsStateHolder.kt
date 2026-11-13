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
)

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
                ).data
                state = MyCommentsState(
                    items = data.items,
                    page = data.page,
                    pages = data.pages,
                    loading = false,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                state = state.copy(loading = false, error = exception.message ?: "加载失败")
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
}
