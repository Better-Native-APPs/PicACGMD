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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.navigation.NavDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.HomeChannel
import app.picacgmd.core.designsystem.component.BackPreviewFrame
import app.picacgmd.core.designsystem.component.BackPreviewState
import app.picacgmd.core.designsystem.component.predictiveBackExit
import app.picacgmd.core.designsystem.component.rememberBackPreviewState
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.feature.bookshelf.BookshelfScreen
import app.picacgmd.feature.categories.CategoriesScreen
import app.picacgmd.feature.comments.CommentsScreen
import app.picacgmd.feature.comics.ComicListScreen
import app.picacgmd.feature.detail.ComicDetailScreen
import app.picacgmd.feature.discover.DiscoverScreen
import app.picacgmd.feature.downloads.DownloadSettingsScreen
import app.picacgmd.feature.downloads.DownloadDetailScreen
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
 * little from the end edge, and back shrinks the leaving page toward 90 % over the dimmed previous
 * page, which is already in place underneath. With the back gesture the page follows the finger:
 * how far it has shrunk, rounded and leaned is how far the finger has dragged.
 *
 * @param compactNavigation true when the phone navigation bar is in use: 「我的」 is then reached
 * from the discover avatar and gets a back button. It is an ordinary second-level page there, in
 * how it opens, in how it leaves and in what back does on it (see [isTabSwitch]).
 */
@Composable
fun PikapikaNavHost(
    navController: NavHostController,
    compactNavigation: Boolean,
    modifier: Modifier = Modifier,
    externalComicId: String? = null,
    onExternalComicExit: () -> Unit = {},
    rootBackHandler: @Composable () -> Unit = {},
) {
    val motion = MaterialTheme.motionScheme
    val slideSpec = motion.defaultSpatialSpec<IntOffset>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()
    val fastFadeSpec = motion.fastEffectsSpec<Float>()
    // Read through State so the graph builder lambda stays the same across window size changes.
    val compact = rememberUpdatedState(compactNavigation)
    val externalId = rememberUpdatedState(externalComicId)
    val externalExit = rememberUpdatedState(onExternalComicExit)
    val rootBack = rememberUpdatedState(rootBackHandler)
    val applicationContext = LocalContext.current.applicationContext
    val downloadManager = remember(applicationContext) {
        (applicationContext as DownloadManagerOwner).downloadManager
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val downloads = remember(downloadManager, scope, snackbar) { DownloadActions(downloadManager, scope, snackbar) }
    val onReaderMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    // The exit specs below say whether a transition is a pop, so only a popped page and the one it
    // uncovers get the back preview; in every other transition the frames stay idle. This holds
    // for a back gesture too: NavHost picks popExitTransition as soon as the gesture starts and
    // builds the spec in the composition that follows, before it seeks the first frame. Nothing
    // resets the flag when a gesture is cancelled, and nothing has to: the pages are back at rest,
    // and the next transition, whichever it is, builds its specs again.
    val backPreview = rememberBackPreviewState()

    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBackPreview provides backPreview) {
            NavHost(
                navController = navController,
                startDestination = DiscoverGraph,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (isTabSwitch(compact.value)) {
                        fadeIn(fadeSpec)
                    } else {
                        slideInHorizontally(slideSpec) { it / 8 } + fadeIn(fadeSpec)
                    }
                },
                exitTransition = {
                    backPreview.popping = false
                    if (isTabSwitch(compact.value)) {
                        fadeOut(fastFadeSpec)
                    } else {
                        slideOutHorizontally(slideSpec) { -it / 16 } + fadeOut(fastFadeSpec)
                    }
                },
                popEnterTransition = {
                    if (isTabSwitch(compact.value)) {
                        fadeIn(fadeSpec)
                    } else {
                        EnterTransition.None
                    }
                },
                popExitTransition = {
                    backPreview.popping = !isTabSwitch(compact.value)
                    if (isTabSwitch(compact.value)) {
                        fadeOut(fastFadeSpec)
                    } else {
                        // No motion from the host: BackPreviewFrame moves the page, off the same
                        // timeline, so there is nothing here for the gesture to seek twice.
                        predictiveBackExit()
                    }
                },
                sizeTransform = null,
            ) {
                topLevelGraphs(navController, compact, rootBack)
                sharedDestinations(navController, externalId, externalExit, downloads, onReaderMessage)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * Whether this transition goes from one tab to another, which crossfades instead of sliding in or
 * shrinking away.
 *
 * A tab is a top-level destination that the navigation chrome on screen lists. A rail lists all
 * four. The phone bar leaves out 「我的」, which is opened from the discover avatar and closed with
 * a back arrow: with [compact] navigation it counts as a page like any other, so it slides in and
 * gets the back preview. What decides is the layout at the moment the transition starts, not how
 * the page was reached: the back stack looks the same either way.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(compact: Boolean): Boolean {
    val from = initialState.destination.tab(compact) ?: return false
    val to = targetState.destination.tab(compact) ?: return false
    return from != to
}

private fun NavDestination.tab(compact: Boolean): TopLevelDestination? =
    topLevelDestination()?.takeIf { !compact || it.inBottomBar }

@Composable
private fun NavHostController.backAction(): () -> Unit = dropUnlessResumed {
    // A destination opened as the start of a deep link may have no previous entry.
    if (!popBackStack()) navigate(DiscoverGraph) { launchSingleTop = true }
}

private val LocalBackPreview = staticCompositionLocalOf<BackPreviewState> { error("No back preview state") }

/** Every destination, including the reader, carries its own predictive back preview frame. */
private inline fun <reified T : Any> NavGraphBuilder.page(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        BackPreviewFrame(LocalBackPreview.current) { content(entry) }
    }
}

private fun NavGraphBuilder.topLevelGraphs(
    navController: NavHostController,
    compact: State<Boolean>,
    rootBack: State<@Composable () -> Unit>,
) {
    navigation<DiscoverGraph>(startDestination = DiscoverRoute) {
        page<DiscoverRoute> {
            rootBack.value()
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
            rootBack.value()
            BookshelfScreen(
                onOpenComic = { navController.navigate(ComicDetailRoute(it)) },
                onContinueReading = { id, ep, page -> navController.navigate(ReaderRoute(id, ep, page)) },
            )
        }
    }
    navigation<DownloadsGraph>(startDestination = DownloadsRoute) {
        page<DownloadsRoute> {
            rootBack.value()
            DownloadsScreen(
                onReadDownloaded = { navController.navigate(DownloadedReaderRoute(it)) },
                onOpenSettings = { navController.navigate(DownloadSettingsRoute) },
                onBrowse = { navController.navigateToTopLevel(TopLevelDestination.Discover) },
                onOpenDetail = { navController.navigate(DownloadDetailRoute(it)) },
            )
        }
    }
    navigation<MeGraph>(startDestination = MeRoute) {
        page<MeRoute> {
            // Behind the avatar on phones, back returns to the page it was opened from, as the
            // arrow in its app bar does; only where it is a rail tab is it a root page.
            if (!compact.value) rootBack.value()
            val messageBoardTitle = stringResource(R.string.comments_message_board)
            MeScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
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
            onOpenCategories = { navController.navigate(CategoriesRoute) },
        )
    }
    page<DownloadSettingsRoute> {
        DownloadSettingsScreen(onBack = navController.backAction())
    }
    page<DownloadDetailRoute> { entry ->
        val comicId = entry.toRoute<DownloadDetailRoute>().comicId
        DownloadDetailScreen(
            comicId = comicId,
            onBack = navController.backAction(),
            onReadDownloaded = { id, order -> navController.navigate(DownloadedReaderRoute(id, order)) },
            onAddChapters = { navController.navigate(ComicDetailRoute(it)) },
            onOpenComments = { navController.navigate(ComicCommentsRoute(it)) },
        )
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
        val route = entry.toRoute<DownloadedReaderRoute>()
        val comicId = route.comicId
        DownloadedReaderDestination(
            comicId = comicId,
            episodeOrder = route.episodeOrder,
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
}

/** The official 「嗶咔留言板」 is the comment section of this special comic. */
private const val MESSAGE_BOARD_COMIC_ID = "5822a6e3ad7ede654696e482"
