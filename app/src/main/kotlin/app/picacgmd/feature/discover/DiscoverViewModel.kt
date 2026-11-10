package app.picacgmd.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.designsystem.component.HomeChannel
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.ALL_COMICS_CATEGORY
import app.picacgmd.core.domain.repository.HomeChannelKind
import app.picacgmd.core.domain.repository.HomeModel
import app.picacgmd.core.domain.repository.RepositoryState
import app.picacgmd.core.network.Category
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.bookshelf.safeImageUrl
import app.picacgmd.feature.routing.RoutePickerDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class DiscoverViewModel(private val container: AppContainer) : ViewModel() {
    /** Last good home model; kept while a refresh fails so the page never goes blank. */
    var model by mutableStateOf<HomeModel?>(null)
        private set
    var failure by mutableStateOf<LoadState.Failed?>(null)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    private var job: Job? = null

    init {
        // The repository state re-emits whenever pins / order / hidden settings change.
        viewModelScope.launch {
            container.homeRepository.state.collect { state ->
                if (state is RepositoryState.Ready) model = state.data
            }
        }
        if (container.homeRepository.state.value !is RepositoryState.Ready) load(force = false, pull = false)
    }

    fun retry() = load(force = true, pull = false)

    /** Pull-to-refresh: reload the catalogue and check subscriptions at the same time. */
    fun refresh() = load(force = true, pull = true)

    private fun load(force: Boolean, pull: Boolean) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            isRefreshing = pull
            if (model == null) failure = null
            try {
                coroutineScope {
                    if (pull) {
                        launch {
                            try {
                                container.subscriptionRepository.refresh()
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Exception) {
                                // Subscription check failures surface on the bookshelf, not here.
                            }
                        }
                    }
                    container.homeRepository.refresh(force)
                }
                failure = null
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failure = exception.toFailure()
            } finally {
                isRefreshing = false
            }
        }
    }
}

/**
 * 发现 with live data. Wire into `DiscoverRoute`.
 *
 * @param onOpenCategory category name → `ComicListRoute(category = name)`.
 */
@Composable
fun DiscoverScreen(
    showProfileEntry: Boolean,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onChannelClick: (HomeChannel) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenAllComics: () -> Unit,
    onOpenCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = appViewModel { DiscoverViewModel(it) }
    val container = LocalAppContainer.current
    val model = viewModel.model
    val content = remember(model, container) { model?.toContent(container) }
    var routePickerOpen by remember { mutableStateOf(false) }
    // The avatar entry shows the signed-in user's real picture; the profile is fetched once if
    // nothing (e.g. 我的) has loaded it yet.
    val profile by container.userRepository.currentProfile.collectAsStateWithLifecycle()
    LaunchedEffect(showProfileEntry) {
        if (showProfileEntry && container.userRepository.currentProfile.value == null) {
            try {
                container.userRepository.profile()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep the placeholder; 我的 retries with its own error state.
            }
        }
    }
    val avatarUrl = remember(profile, container) { container.safeImageUrl(profile?.avatar) }
    DiscoverScreen(
        content = content,
        showProfileEntry = showProfileEntry,
        onSearchClick = onSearchClick,
        onProfileClick = onProfileClick,
        onChannelClick = onChannelClick,
        onCategoryClick = onOpenCategory,
        onAllComicsClick = onOpenAllComics,
        onManageCategoriesClick = onOpenCategories,
        onRouteClick = { routePickerOpen = true },
        modifier = modifier,
        failure = viewModel.failure,
        isRefreshing = viewModel.isRefreshing,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        avatarUrl = avatarUrl,
        avatarInitial = profile?.name?.take(1)?.takeIf { it.isNotBlank() },
    )
    if (routePickerOpen) RoutePickerDialog(onDismiss = { routePickerOpen = false })
}

private fun HomeModel.toContent(container: AppContainer) = DiscoverContent(
    pinnedCategories = channels
        .filter { it.kind == HomeChannelKind.CATEGORY }
        .map { it.category?.toHomeCategory(container) ?: HomeCategory(it.title) },
    categories = categories.map { it.toHomeCategory(container) },
)

private fun Category.toHomeCategory(container: AppContainer): HomeCategory =
    if (id == ALL_COMICS_CATEGORY.id) AllComicsHomeCategory else HomeCategory(title, container.imageUrl(thumb))
