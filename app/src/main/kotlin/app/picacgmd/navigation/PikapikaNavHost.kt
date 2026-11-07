package app.picacgmd.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
import app.picacgmd.feature.account.LoginScreen
import app.picacgmd.feature.games.GameCommentsScreen
import app.picacgmd.feature.games.GameDetailScreen
import app.picacgmd.feature.games.GamesScreen
import app.picacgmd.ui.PlaceholderLink
import app.picacgmd.ui.PlaceholderScreen

/** Sample id used by placeholder links until real lists exist. */
private const val SampleComicId = "sample-0"

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

    NavHost(
        navController = navController,
        startDestination = DiscoverGraph,
        modifier = modifier,
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
        sharedDestinations(navController, externalId, externalExit)
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
            PlaceholderScreen(
                title = stringResource(R.string.nav_discover),
                links = listOf(
                    PlaceholderLink("搜索") { navController.navigate(SearchRoute()) },
                    PlaceholderLink("排行榜") { navController.navigate(RankingsRoute) },
                    PlaceholderLink("随机") { navController.navigate(RandomRoute) },
                    PlaceholderLink("游戏专区") { navController.navigate(GamesRoute) },
                    PlaceholderLink("全部分类") { navController.navigate(CategoriesRoute) },
                    PlaceholderLink("推荐") { navController.navigate(CollectionsRoute) },
                    PlaceholderLink("我的") { navController.navigateToTopLevel(TopLevelDestination.Me) },
                ),
            )
        }
    }
    navigation<BookshelfGraph>(startDestination = BookshelfRoute) {
        composable<BookshelfRoute> {
            PlaceholderScreen(
                title = stringResource(R.string.nav_bookshelf),
                links = listOf(comicDetailLink(navController)),
            )
        }
    }
    navigation<DownloadsGraph>(startDestination = DownloadsRoute) {
        composable<DownloadsRoute> {
            PlaceholderScreen(
                title = stringResource(R.string.nav_downloads),
                links = listOf(comicDetailLink(navController)),
            )
        }
    }
    navigation<MeGraph>(startDestination = MeRoute) {
        composable<MeRoute> {
            PlaceholderScreen(
                title = stringResource(R.string.nav_me),
                onBack = if (compact.value) navController.backAction() else null,
                links = listOf(
                    PlaceholderLink(stringResource(R.string.placeholder_link_settings)) {
                        navController.navigate(SettingsRoute)
                    },
                    PlaceholderLink(stringResource(R.string.placeholder_link_login)) {
                        navController.navigate(LoginRoute)
                    },
                ),
            )
        }
    }
}

private fun NavGraphBuilder.sharedDestinations(navController: NavHostController, externalComicId: State<String?>, onExternalComicExit: State<() -> Unit>) {
    composable<SettingsRoute> {
        PlaceholderScreen(stringResource(R.string.title_settings), onBack = navController.backAction())
    }
    composable<SearchRoute> { entry ->
        val keyword = entry.toRoute<SearchRoute>().keyword
        PlaceholderScreen(
            title = if (keyword.isNullOrBlank()) {
                stringResource(R.string.title_search)
            } else {
                stringResource(R.string.title_search_keyword, keyword)
            },
            onBack = navController.backAction(),
            links = listOf(comicDetailLink(navController)),
        )
    }
    composable<ComicListRoute> { entry ->
        val route = entry.toRoute<ComicListRoute>()
        PlaceholderScreen(
            title = route.category ?: route.tag ?: route.author ?: stringResource(R.string.title_comic_list),
            onBack = navController.backAction(),
            links = listOf(comicDetailLink(navController)),
        )
    }
    composable<ComicDetailRoute> { entry ->
        val comicId = entry.toRoute<ComicDetailRoute>().comicId
        PlaceholderScreen(
            title = stringResource(R.string.title_comic_detail),
            onBack = if (comicId == externalComicId.value) ({ onExternalComicExit.value() }) else navController.backAction(),
            links = listOf(
                PlaceholderLink(stringResource(R.string.placeholder_link_reader)) {
                    navController.navigate(ReaderRoute(comicId))
                },
            ),
        )
    }
    composable<ReaderRoute> {
        PlaceholderScreen(stringResource(R.string.title_reader), onBack = navController.backAction())
    }
    composable<RankingsRoute> {
        PlaceholderScreen(
            title = stringResource(R.string.title_rankings),
            onBack = navController.backAction(),
            links = listOf(comicDetailLink(navController)),
        )
    }
    composable<RandomRoute> {
        PlaceholderScreen(
            title = stringResource(R.string.title_random),
            onBack = navController.backAction(),
            links = listOf(comicDetailLink(navController)),
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
        PlaceholderScreen(
            title = stringResource(R.string.title_collections),
            onBack = navController.backAction(),
            links = listOf(comicDetailLink(navController)),
        )
    }
    composable<CategoriesRoute> {
        PlaceholderScreen(stringResource(R.string.title_categories), onBack = navController.backAction())
    }
    composable<LoginRoute> {
        LoginScreen(onBack = navController.backAction())
    }
}

@Composable
private fun comicDetailLink(navController: NavHostController): PlaceholderLink =
    PlaceholderLink(stringResource(R.string.placeholder_link_detail)) {
        navController.navigate(ComicDetailRoute(SampleComicId))
    }
