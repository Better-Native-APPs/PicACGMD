package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HomeRepository(
    private val comics: ComicRepository,
    private val settings: Flow<AppSettings>,
    scope: CoroutineScope,
) {
    private val catalogue = MutableStateFlow<RepositoryState<List<Category>>>(RepositoryState.Idle)
    private val refreshMutex = Mutex()

    val state: StateFlow<RepositoryState<HomeModel>> = combine(catalogue, settings) { snapshot, preferences ->
        when (snapshot) {
            RepositoryState.Idle -> RepositoryState.Idle
            RepositoryState.Loading -> RepositoryState.Loading
            is RepositoryState.Failed -> snapshot
            is RepositoryState.Ready -> RepositoryState.Ready(
                buildHomeModel(snapshot.data, preferences),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, RepositoryState.Idle)

    suspend fun refresh(forceRefresh: Boolean = false): HomeModel = refreshMutex.withLock {
        val previous = catalogue.value
        catalogue.value = RepositoryState.Loading
        try {
            val result = comics.categories(forceRefresh)
            val preferences = settings.first()
            val model = buildHomeModel(result, preferences)
            catalogue.value = RepositoryState.Ready(result)
            model
        } catch (exception: CancellationException) {
            catalogue.value = previous
            throw exception
        } catch (exception: Exception) {
            catalogue.value = RepositoryState.Failed(exception.message ?: "首页加载失败")
            throw exception
        }
    }
}
