package app.picacgmd.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.HomeChannel
import app.picacgmd.core.designsystem.component.BackPreviewFrame
import app.picacgmd.core.designsystem.component.predictiveBackExit
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
import app.picacgmd.feature.announcements.AnnouncementsScreen
import app.picacgmd.feature.me.MeScreen
import app.picacgmd.feature.me.MyCommentsScreen
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
                    EnterTransition.None
                }
            },
            popExitTransition = {
                if (isTabSwitch()) {
                    fadeOut(fastFadeSpec)
                } else {
                    predictiveBackExit()
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
private fun NavHostController.backAction(): () -> Unit = dropUnlessResumed {
    // A destination opened as the start of a deep link may have no previous entry.
    if (!popBackStack()) navigate(DiscoverGraph) { launchSingleTop = true }
}

/** Every destination, including the reader, clips its own predictive back preview. */
private inline fun <reified T : Any> NavGraphBuilder.page(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        BackPreviewFrame(roundCorners = !entry.destination.isTopLevelStart()) { content(entry) }
    }
}

private fun NavGraphBuilder.topLevelGraphs(navController: NavHostController, compact: State<Boolean>) {
    navigation<DiscoverGraph>(startDestination = DiscoverRoute) {
        page<DiscoverRoute> {
            val allComicsTitle = stringResource(R.string.title_categories)
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
                onOpenAllComics = { navController.navigate(ComicListRoute(title = allComicsTitle)) },
                onOpenCategories = { navController.navigate(CategoriesRoute) },
            )
        }
    }
    navigation<BookshelfGraph>(startDestination = BookshelfRoute) {
        page<BookshelfRoute> {
            BookshelfScreen(
                onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
                onContinueReading = { id, ep, page -> navController.navigate(ReaderRoute(id, ep, page)) },
            )
        }
    }
    navigation<DownloadsGraph>(startDestination = DownloadsRoute) {
        page<DownloadsRoute> {
            DownloadsScreen(
                onReadDownloaded = { navController.navigate(DownloadedReaderRoute(it)) },
                onOpenSettings = { navController.navigate(DownloadSettingsRoute) },
                onBrowse = { navController.navigateToTopLevel(TopLevelDestination.Discover) },
                onOpenDetail = { navController.navigate(ComicDetailRoute(it)) },
            )
        }
    }
    navigation<MeGraph>(startDestination = MeRoute) {
        page<MeRoute> {
            val messageBoardTitle = stringResource(R.string.comments_message_board)
            MeScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenLogin = { navController.navigate(LoginRoute) },
                onOpenMyComments = { navController.navigate(MyCommentsRoute) },
                onOpenAnnouncements = { navController.navigate(AnnouncementsRoute) },
                onOpenMessageBoard = { navController.navigate(ComicCommentsRoute(MESSAGE_BOARD_COMIC_ID, messageBoardTitle)) },
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
    page<SettingsRoute> {
        SettingsHost(
            onBack = navController.backAction(),
            onOpenDownloads = { navController.navigateToTopLevel(TopLevelDestination.Downloads) },
        )
    }
    page<DownloadSettingsRoute> {
        DownloadSettingsScreen(onBack = navController.backAction())
    }
    page<AnnouncementsRoute> {
        AnnouncementsScreen(onBack = navController.backAction())
    }
    page<MyCommentsRoute> {
        MyCommentsScreen(
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
        )
    }
    page<SearchRoute> { entry ->
        SearchScreen(
            initialKeyword = entry.toRoute<SearchRoute>().keyword,
            onBack = navController.backAction(),
            onComicClick = { navController.navigate(ComicDetailRoute(it)) },
            onBatchDownload = downloads::enqueueBatch,
        )
    }
    page<ComicListRoute> { entry ->
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
    page<ComicDetailRoute> { entry ->
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
            onCreatorClick = { id, name -> navController.navigate(ComicListRoute(creatorId = id, title = name)) },
            onComicClick = { navController.navigate(ComicDetailRoute(it)) },
            onOpenComments = { navController.navigate(ComicCommentsRoute(it)) },
        )
    }
    page<ComicCommentsRoute> { entry ->
        val route = entry.toRoute<ComicCommentsRoute>()
        CommentsScreen(
            comicId = route.comicId,
            title = route.title,
            onBack = navController.backAction(),
        )
    }
    page<ReaderRoute> { entry ->
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
    page<DownloadedReaderRoute> { entry ->
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
    page<RankingsRoute> {
        RankingsScreen(
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
            onOpenKnight = { id, name -> navController.navigate(ComicListRoute(creatorId = id, title = name)) },
        )
    }
    page<RandomRoute> {
        RandomScreen(
            onBack = navController.backAction(),
            onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
        )
    }
    page<GamesRoute> {
        GamesScreen(onBack = navController.backAction(), onGameClick = { navController.navigate(GameDetailRoute(it)) })
    }
    page<GameDetailRoute> { entry ->
        val route = entry.toRoute<GameDetailRoute>()
        GameDetailScreen(
            gameId = route.gameId,
            onBack = navController.backAction(),
            onComments = { title -> navController.navigate(GameCommentsRoute(route.gameId, title)) },
        )
    }
    page<GameCommentsRoute> { entry ->
        val route = entry.toRoute<GameCommentsRoute>()
        GameCommentsScreen(
            gameId = route.gameId,
            title = route.title,
            onBack = navController.backAction(),
            onReplies = { navController.navigate(GameRepliesRoute(route.gameId, it)) },
        )
    }
    page<GameRepliesRoute> { entry ->
        val route = entry.toRoute<GameRepliesRoute>()
        GameCommentsScreen(
            gameId = route.gameId, title = "评论回复", parentCommentId = route.commentId,
            onBack = navController.backAction(),
        )
    }
    page<CategoriesRoute> {
        val allComicsTitle = stringResource(R.string.title_categories)
        CategoriesScreen(
            onBack = navController.backAction(),
            onOpenAllComics = { navController.navigate(ComicListRoute(title = allComicsTitle)) },
        )
    }
    page<LoginRoute> {
        LoginScreen(onBack = navController.backAction())
    }
}

/** The official 「嗶咔留言板」 is the comment section of this special comic. */
private const val MESSAGE_BOARD_COMIC_ID = "5822a6e3ad7ede654696e482"
