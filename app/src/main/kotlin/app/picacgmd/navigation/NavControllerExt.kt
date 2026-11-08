package app.picacgmd.navigation

import android.annotation.SuppressLint
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/** The tab that owns the top of [entries]: shared destinations belong to the tab below them. */
fun List<NavBackStackEntry>.currentTopLevelDestination(): TopLevelDestination? =
    asReversed().firstNotNullOfOrNull { it.destination.topLevelDestination() }

/**
 * Switches tabs: pops back to the app start destination while saving the current tab's stack, then
 * restores the target tab's saved stack. Re-selecting the current tab returns to its start screen.
 *
 * Reads the full back stack because shared destinations sit above their tab's entries; Navigation
 * exposes the whole stack only through the library-group `currentBackStack`.
 */
@SuppressLint("RestrictedApi")
fun NavController.navigateToTopLevel(destination: TopLevelDestination) {
    if (destination == currentBackStack.value.currentTopLevelDestination()) {
        popBackStack(destination.startRoute, inclusive = false)
        return
    }
    navigate(destination.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
