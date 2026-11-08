package app.picacgmd.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import app.picacgmd.core.designsystem.component.HomeChannel
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.feature.account.LoginScreen
import app.picacgmd.feature.bookshelf.BookshelfScreen
import app.picacgmd.feature.categories.CategoriesScreen
import app.picacgmd.feature.comments.CommentsScreen
import app.picacgmd.feature.comics.ComicListScreen
import app.picacgmd.feature.detail.ComicDetailScreen
import app.picacgmd.feature.discover.DiscoverScreen
import app.picacgmd.feature.downloads.DownloadSettingsScreen
import app.picacgmd.feature.downloads.DownloadsScreen
import app.picacgmd.feature.games.GameCommentsScreen
import app.picacgmd.feature.games.GameDetailScreen
import app.picacgmd.feature.games.GamesScreen
import app.picacgmd.feature.me.MeScreen
import app.picacgmd.feature.me.MyCommentsScreen
import app.picacgmd.feature.rankings.CollectionScreen
import app.picacgmd.feature.rankings.CollectionsScreen
import app.picacgmd.feature.rankings.RandomScreen
import app.picacgmd.feature.rankings.RankingsScreen
import app.picacgmd.feature.search.SearchScreen
import app.picacgmd.feature.settings.SettingsHost
import app.picacgmd.reader.DownloadedReaderDestination
import app.picacgmd.reader.OnlineReaderDestination
import app.picacgmd.ui.DownloadActions
import kotlinx.coroutines.launch

/**
 * App navigation graph. Motion follows board 01b: tabs crossfade, forward navigation slides in a
 * little from the end edge, and back (including the predictive back gesture) shrinks the leaving
 * page toward 90 % while the previous page comes back in.
 *
 * @param compactNavigation true when the phone navigation bar is in use: 「我的」 is then reached
 * from the discover avatar and gets a back button.
 */
@Composable
fun PikapikaNavHost(
    navController: NavHostController,
    compactNavigation: Boolean,
    modifier: Modifier = Modifier,
    externalComicId: String? = null,
    onExternalComicExit: () -> Unit = {},
) {
    val motion = MaterialTheme.motionScheme
    val slideSpec = motion.defaultSpatialSpec<IntOffset>()
    val scaleSpec = motion.defaultSpatialSpec<Float>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()
    val fastFadeSpec = motion.fastEffectsSpec<Float>()
    // Read through State so the graph builder lambda stays the same across window size changes.
    val compact = rememberUpdatedState(compactNavigation)
    val externalId = rememberUpdatedState(externalComicId)
    val externalExit = rememberUpdatedState(onExternalComicExit)
    val applicationContext = LocalContext.current.applicationContext
    val downloadManager = remember(applicationContext) {
        (applicationContext as DownloadManagerOwner).downloadManager
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val downloads = remember(downloadManager, scope, snackbar) { DownloadActions(downloadManager, scope, snackbar) }
    val onReaderMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    Box(modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = DiscoverGraph,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                if (isTabSwitch()) {
                    fadeIn(fadeSpec)
                } else {
                    slideInHorizontally(slideSpec) { it / 8 } + fadeIn(fadeSpec)
                }
            },
            exitTransition = {
                if (isTabSwitch()) {
                    fadeOut(fastFadeSpec)
                } else {
                    slideOutHorizontally(slideSpec) { -it / 16 } + fadeOut(fastFadeSpec)
                }
            },
            popEnterTransition = {
                if (isTabSwitch()) {
                    fadeIn(fadeSpec)
                } else {
                    slideInHorizontally(slideSpec) { -it / 16 } + fadeIn(fadeSpec)
                }
            },
            popExitTransition = {
                if (isTabSwitch()) {
                    fadeOut(fastFadeSpec)
                } else {
                    scaleOut(scaleSpec, targetScale = 0.9f) + fadeOut(fadeSpec)
                }
            },
            sizeTransform = null,
        ) {
            topLevelGraphs(navController, compact)
            sharedDestinations(navController, externalId, externalExit, downloads, onReaderMessage)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean {
    val from = initialState.destination.topLevelDestination() ?: return false
    val to = targetState.destination.topLevelDestination() ?: return false
    return from != to
}

@Composable
private fun NavHostController.backAction(): () -> Unit = dropUnlessResumed { popBackStack() }

private fun NavGraphBuilder.topLevelGraphs(navController: NavHostController, compact: State<Boolean>) {
    navigation<DiscoverGraph>(startDestination = DiscoverRoute) {
        composable<DiscoverRoute> {
            DiscoverScreen(
                showProfileEntry = compact.value,
                onSearchClick = { navController.navigate(SearchRoute()) },
                onProfileClick = {
                    if (compact.value) navController.navigate(MeGraph)
                    else navController.navigateToTopLevel(TopLevelDestination.Me)
                },
                onChannelClick = { channel ->
                    when (channel) {
                        HomeChannel.Rankings -> navController.navigate(RankingsRoute)
                        HomeChannel.Random -> navController.navigate(RandomRoute)
                        HomeChannel.Games -> navController.navigate(GamesRoute)
                    }
                },
                onOpenCategory = { navController.navigate(ComicListRoute(category = it)) },
                onOpenCollections = { navController.navigate(CollectionsRoute) },
                onOpenCategories = { navController.navigate(CategoriesRoute) },
                onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
            )
        }
    }
    navigation<BookshelfGraph>(startDestination = BookshelfRoute) {
        composable<BookshelfRoute> {
            BookshelfScreen(
                onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
                onContinueReading = { id, ep, page -> navController.navigate(ReaderRoute(id, ep, page)) },
            )
        }
    }
    navigation<DownloadsGraph>(startDestination = DownloadsRoute) {
        composable<DownloadsRoute> {
            DownloadsScreen(
                onReadDownloaded = { navController.navigate(DownloadedReaderRoute(it)) },
                onOpenSettings = { navController.navigate(DownloadSettingsRoute) },
                onBrowse = { navController.navigateToTopLevel(TopLevelDestination.Discover) },
                onOpenDetail = { navController.navigate(ComicDetailRoute(it)) },
            )
        }
    }
    navigation<MeGraph>(startDestination = MeRoute) {
        composable<MeRoute> {
            MeScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenLogin = { navController.navigate(LoginRoute) },
                onOpenMyComments = { navController.navigate(MyCommentsRoute) },
                onBack = if (compact.value) navController.backAction() else null,
            )
        }
    }
}

private fun NavGraphBuilder.sharedDestinations(
    navController: NavHostController,
    externalComicId: State<String?>,
    onExternalComicExit: State<() -> Unit>,
    downloads: DownloadActions,
    onReaderMessage: (String) -> Unit,
) {
    composable<SettingsRoute> {
        SettingsHost(
            onBack = navController.backAction(),
            onOpenDownloads = { navController.navigateToTopLevel(TopLevelDestination.Downloads) },
        )
    }
    composable<DownloadSettingsRoute> {
        DownloadSettingsScreen(onBack = navController.backAction())
    }
    composable<MyCommentsRoute> {
        MyCommentsScreen(
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
        )
    }
    composable<SearchRoute> { entry ->
        SearchScreen(
            initialKeyword = entry.toRoute<SearchRoute>().keyword,
            onBack = navController.backAction(),
            onComicClick = { navController.navigate(ComicDetailRoute(it)) },
            onBatchDownload = downloads::enqueueBatch,
        )
    }
    composable<ComicListRoute> { entry ->
        val route = entry.toRoute<ComicListRoute>()
        ComicListScreen(
            category = route.category,
            tag = route.tag,
            author = route.author,
            chineseTeam = route.chineseTeam,
            creatorId = route.creatorId,
            title = route.title,
            onBack = navController.backAction(),
            onComicClick = { navController.navigate(ComicDetailRoute(it)) },
            onBatchDownload = downloads::enqueueBatch,
        )
    }
    composable<ComicDetailRoute> { entry ->
        val comicId = entry.toRoute<ComicDetailRoute>().comicId
        ComicDetailScreen(
            comicId = comicId,
            onBack = if (comicId == externalComicId.value) ({ onExternalComicExit.value() }) else navController.backAction(),
            onRead = { id, epOrder, page -> navController.navigate(ReaderRoute(id, epOrder, page)) },
            onDownloadChapters = downloads::enqueueChapters,
            onCategoryClick = { navController.navigate(ComicListRoute(category = it)) },
            onTagClick = { navController.navigate(ComicListRoute(tag = it)) },
            onAuthorClick = { navController.navigate(ComicListRoute(author = it)) },
            onTeamClick = { navController.navigate(ComicListRoute(chineseTeam = it)) },
            onComicClick = { navController.navigate(ComicDetailRoute(it)) },
            onOpenComments = { navController.navigate(ComicCommentsRoute(it)) },
        )
    }
    composable<ComicCommentsRoute> { entry ->
        CommentsScreen(
            comicId = entry.toRoute<ComicCommentsRoute>().comicId,
            onBack = navController.backAction(),
        )
    }
    composable<ReaderRoute> { entry ->
        val route = entry.toRoute<ReaderRoute>()
        OnlineReaderDestination(
            comicId = route.comicId,
            episodeOrder = route.episodeOrder,
            page = route.page,
            onBack = navController.backAction(),
            onMessage = onReaderMessage,
            onOpenComments = { navController.navigate(ComicCommentsRoute(route.comicId)) },
            onOpenRelated = { navController.navigate(ComicDetailRoute(route.comicId)) },
            onOpenAllSettings = { navController.navigate(SettingsRoute) },
        )
    }
    composable<DownloadedReaderRoute> { entry ->
        val comicId = entry.toRoute<DownloadedReaderRoute>().comicId
        DownloadedReaderDestination(
            comicId = comicId,
            onBack = navController.backAction(),
            onMessage = onReaderMessage,
            onOpenComments = { navController.navigate(ComicCommentsRoute(comicId)) },
            onOpenRelated = { navController.navigate(ComicDetailRoute(comicId)) },
            onOpenAllSettings = { navController.navigate(SettingsRoute) },
        )
    }
    composable<RankingsRoute> {
        RankingsScreen(
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
            onOpenKnight = { id, name -> navController.navigate(ComicListRoute(creatorId = id, title = name)) },
        )
    }
    composable<RandomRoute> {
        RandomScreen(
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
        )
    }
    composable<GamesRoute> {
        GamesScreen(onBack = navController.backAction(), onGameClick = { navController.navigate(GameDetailRoute(it)) })
    }
    composable<GameDetailRoute> { entry ->
        val route = entry.toRoute<GameDetailRoute>()
        GameDetailScreen(
            gameId = route.gameId,
            onBack = navController.backAction(),
            onComments = { title -> navController.navigate(GameCommentsRoute(route.gameId, title)) },
        )
    }
    composable<GameCommentsRoute> { entry ->
        val route = entry.toRoute<GameCommentsRoute>()
        GameCommentsScreen(
            gameId = route.gameId,
            title = route.title,
            onBack = navController.backAction(),
            onReplies = { navController.navigate(GameRepliesRoute(route.gameId, it)) },
        )
    }
    composable<GameRepliesRoute> { entry ->
        val route = entry.toRoute<GameRepliesRoute>()
        GameCommentsScreen(
            gameId = route.gameId, title = "评论回复", parentCommentId = route.commentId,
            onBack = navController.backAction(),
        )
    }
    composable<CollectionsRoute> {
        CollectionsScreen(
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
            onOpenCollection = { index, title -> navController.navigate(CollectionRoute(index, title)) },
        )
    }
    composable<CollectionRoute> { entry ->
        val route = entry.toRoute<CollectionRoute>()
        CollectionScreen(
            index = route.index,
            title = route.title,
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
        )
    }
    composable<CategoriesRoute> {
        CategoriesScreen(onBack = navController.backAction())
    }
    composable<LoginRoute> {
        LoginScreen(onBack = navController.backAction())
    }
}
