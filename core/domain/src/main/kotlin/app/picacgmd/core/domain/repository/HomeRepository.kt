package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.Category
import app.picacgmd.core.network.ComicCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private data class Catalogue(val categories: List<Category>, val collections: List<ComicCollection>)
    private val catalogue = MutableStateFlow<RepositoryState<Catalogue>>(RepositoryState.Idle)
    private val refreshMutex = Mutex()

    val state: StateFlow<RepositoryState<HomeModel>> = combine(catalogue, settings) { snapshot, preferences ->
        when (snapshot) {
            RepositoryState.Idle -> RepositoryState.Idle
            RepositoryState.Loading -> RepositoryState.Loading
            is RepositoryState.Failed -> snapshot
            is RepositoryState.Ready -> RepositoryState.Ready(
                buildHomeModel(
                    snapshot.data.categories, comics.filterCollections(snapshot.data.collections, preferences), preferences,
                ),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, RepositoryState.Idle)

    suspend fun refresh(forceRefresh: Boolean = false): HomeModel = refreshMutex.withLock {
        val previous = catalogue.value
        catalogue.value = RepositoryState.Loading
        try {
            val result = coroutineScope {
                val categories = async { comics.categories(forceRefresh) }
                val collections = async { comics.rawCollections(forceRefresh) }
                Catalogue(categories.await(), collections.await())
            }
            val preferences = settings.first()
            val model = buildHomeModel(
                result.categories, comics.filterCollections(result.collections, preferences), preferences,
            )
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
