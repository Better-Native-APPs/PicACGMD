package app.picacgmd.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import app.picacgmd.R
import kotlin.reflect.KClass

/**
 * Top-level navigation items. Phones show the first three in the ShortNavigationBar and reach
 * 「我的」 from the discover avatar; rails (≥ 600dp) list all four (boards 01b, 31).
 */
enum class TopLevelDestination(
    val graph: Any,
    val startRoute: KClass<*>,
    @param:StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val inBottomBar: Boolean,
) {
    Discover(
        graph = DiscoverGraph,
        startRoute = DiscoverRoute::class,
        label = R.string.nav_discover,
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
        inBottomBar = true,
    ),
    Bookshelf(
        graph = BookshelfGraph,
        startRoute = BookshelfRoute::class,
        label = R.string.nav_bookshelf,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark,
        inBottomBar = true,
    ),
    Downloads(
        graph = DownloadsGraph,
        startRoute = DownloadsRoute::class,
        label = R.string.nav_downloads,
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Outlined.Download,
        inBottomBar = true,
    ),
    Me(
        graph = MeGraph,
        startRoute = MeRoute::class,
        label = R.string.nav_me,
        selectedIcon = Icons.Filled.AccountCircle,
        unselectedIcon = Icons.Outlined.AccountCircle,
        inBottomBar = false,
    ),
}

/** The tab whose graph contains this destination, or null for shared root-level destinations. */
fun NavDestination?.topLevelDestination(): TopLevelDestination? =
    this?.hierarchy?.firstNotNullOfOrNull { node ->
        TopLevelDestination.entries.firstOrNull { node.hasRoute(it.graph::class) }
    }

/** True for the start screen of a tab (where the phone navigation bar is shown). */
fun NavDestination?.isTopLevelStart(): Boolean =
    this != null && TopLevelDestination.entries.any { hasRoute(it.startRoute) }

/** Destinations that take the whole window, without navigation bar or rail. */
fun NavDestination?.isFullscreen(): Boolean =
    this != null && (hasRoute(ReaderRoute::class) || hasRoute(DownloadedReaderRoute::class) || hasRoute(LoginRoute::class))
