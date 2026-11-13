package app.picacgmd.feature.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.GameSimple
import app.picacgmd.paging.Dedupe
import app.picacgmd.paging.PageAccumulator
import app.picacgmd.paging.PageData
import app.picacgmd.paging.PageWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class GamesViewModel(private val repository: GameRepository) : ViewModel() {
    var games by mutableStateOf<List<GameSimple>>(emptyList())
        private set
    var page by mutableStateOf(0)
        private set
    var pages by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val accumulator = PageAccumulator<GameSimple, String, Unit>(
        initial = PageData(emptyList(), 0, 0, 0, Unit),
        keyOf = GameSimple::id,
        replaceDedupe = Dedupe.NONE,
        appendDedupe = Dedupe.ALL,
        appendMeta = { _, _ -> },
    )

    init { load(1, append = false) }

    fun load(target: Int, append: Boolean) {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = repository.games(target)
                val data = accumulator.commit(
                    PageData(result.docs, result.page, result.pages, result.total, Unit),
                    if (append) PageWrite.APPEND else PageWrite.REPLACE,
                ).data
                games = data.items
                page = data.page
                pages = data.pages
                total = data.total
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.localizedMessage ?: "加载游戏失败"
            } finally {
                loading = false
            }
        }
    }
}
