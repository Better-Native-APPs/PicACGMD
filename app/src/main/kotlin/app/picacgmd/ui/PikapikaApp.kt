package app.picacgmd.ui

import android.annotation.SuppressLint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.picacgmd.R
import app.picacgmd.feature.bookshelf.rememberBookshelfUnseenCount
import app.picacgmd.navigation.PikapikaNavHost
import app.picacgmd.navigation.ComicDetailRoute
import app.picacgmd.navigation.ExternalNavigation
import app.picacgmd.navigation.SearchRoute
import app.picacgmd.navigation.TopLevelDestination
import app.picacgmd.navigation.currentTopLevelDestination
import app.picacgmd.navigation.isFullscreen
import app.picacgmd.navigation.isTopLevelStart
import app.picacgmd.navigation.navigateToTopLevel

/** Navigation chrome per window width (board 31). */
private enum class NavigationLayout {
    /** < 600dp: ShortNavigationBar with 发现 / 书架 / 下载. */
    Bar,

    /** 600–1199dp: collapsed WideNavigationRail, 「我的」 joins the items. */
    RailCollapsed,

    /** ≥ 1200dp: expanded WideNavigationRail with the 「搜索」 extended FAB. */
    RailExpanded,
}

/**
 * Window width decides the chrome. `currentWindowAdaptiveInfo()` only reports the 600 / 840 size
 * classes, so the ≥ 1200dp step of board 31 reads the window size directly.
 */
@Composable
private fun currentNavigationLayout(): NavigationLayout {
    val width = LocalWindowInfo.current.containerDpSize.width
    return when {
        width < 600.dp -> NavigationLayout.Bar
        width < 1200.dp -> NavigationLayout.RailCollapsed
        else -> NavigationLayout.RailExpanded
    }
}

/**
 * App shell: adaptive navigation (ShortNavigationBar on phones, WideNavigationRail from 600dp)
 * around the navigation graph. Phones show the bar only on the tab start screens; rails stay
 * visible everywhere except full-screen pages such as the reader.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PikapikaApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    externalNavigation: ExternalNavigation? = null,
    onExternalNavigationConsumed: () -> Unit = {},
    onExternalComicExit: () -> Unit = {},
) {
    val layout = currentNavigationLayout()
    val usesRail = layout != NavigationLayout.Bar
    // The whole stack decides the current tab (see navigateToTopLevel); no public equivalent exists.
    @SuppressLint("RestrictedApi")
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val currentDestination = backStack.lastOrNull()?.destination
    val currentTab = backStack.currentTopLevelDestination()
    var externalComicId by rememberSaveable { mutableStateOf<String?>(null) }

    val showNavigation = currentDestination == null || when {
        currentDestination.isFullscreen() -> false
        usesRail -> true
        else -> currentDestination.isTopLevelStart() && currentTab?.inBottomBar == true
    }
    val suiteState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(showNavigation) {
        if (showNavigation) suiteState.show() else suiteState.hide()
    }

    val suiteType = when (layout) {
        NavigationLayout.Bar -> NavigationSuiteType.ShortNavigationBarCompact
        NavigationLayout.RailCollapsed -> NavigationSuiteType.WideNavigationRailCollapsed
        NavigationLayout.RailExpanded -> NavigationSuiteType.WideNavigationRailExpanded
    }
    val destinations = TopLevelDestination.entries.filter { usesRail || it.inBottomBar }
    val bookshelfUnseenCount = rememberBookshelfUnseenCount()

    NavigationSuiteScaffold(
        navigationItems = {
            destinations.forEach { destination ->
                val selected = destination == currentTab
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (destination == TopLevelDestination.Bookshelf && bookshelfUnseenCount > 0) {
                                    Badge { Text(bookshelfUnseenCount.toString()) }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = null,
                            )
                        }
                    },
                    label = { Text(stringResource(destination.label)) },
                    navigationSuiteType = suiteType,
                )
            }
        },
        modifier = modifier,
        navigationSuiteType = suiteType,
        state = suiteState,
        primaryActionContent = {
            if (usesRail) {
                val expanded = layout == NavigationLayout.RailExpanded
                val searchLabel = stringResource(R.string.nav_search)
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(SearchRoute()) },
                    expanded = expanded,
                    icon = { Icon(Icons.Outlined.Search, contentDescription = if (expanded) null else searchLabel) },
                    text = { Text(searchLabel) },
                )
            }
        },
    ) {
        PikapikaNavHost(
            navController = navController, compactNavigation = !usesRail,
            externalComicId = externalComicId, onExternalComicExit = onExternalComicExit,
        )
    }
    LaunchedEffect(externalNavigation) {
        externalNavigation?.let { target ->
            if (target.downloads) navController.navigateToTopLevel(TopLevelDestination.Downloads)
            target.comicId?.let {
                externalComicId = it
                navController.navigate(ComicDetailRoute(it)) { launchSingleTop = true }
            }
            onExternalNavigationConsumed()
        }
    }
    // Let NavHost own the gesture and its preview. Finish the external-comic activity only after
    // its entry is actually popped, not when a cancelled gesture merely previews the previous page.
    LaunchedEffect(navController, externalComicId) {
        val comicId = externalComicId ?: return@LaunchedEffect
        var opened = false
        navController.currentBackStackEntryFlow.collect {
            val present = runCatching { navController.getBackStackEntry(ComicDetailRoute(comicId)) }.isSuccess
            if (present) opened = true
            else if (opened) {
                externalComicId = null
                onExternalComicExit()
            }
        }
    }
}
