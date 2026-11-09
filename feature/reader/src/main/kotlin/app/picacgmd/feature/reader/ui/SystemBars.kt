package app.picacgmd.feature.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.picacgmd.feature.reader.SystemBarsMode

private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}

/**
 * Controls the system bars while the reader is on screen and gives them back as they were when it
 * leaves. Hidden bars come back for a moment with a swipe from the edge.
 *
 * @param mode app-wide system bar visibility.
 * @param lightBackground the chrome behind the bars is light (纸 / 白), so their icons must be dark.
 */
@Composable
fun ReaderSystemBars(mode: SystemBarsMode, lightBackground: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val controller = remember(view) {
        view.context.findWindow()?.let { WindowCompat.getInsetsController(it, view) }
    } ?: return

    DisposableEffect(controller) {
        val behavior = controller.systemBarsBehavior
        val lightStatusBars = controller.isAppearanceLightStatusBars
        val lightNavigationBars = controller.isAppearanceLightNavigationBars
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.systemBarsBehavior = behavior
            controller.isAppearanceLightStatusBars = lightStatusBars
            controller.isAppearanceLightNavigationBars = lightNavigationBars
        }
    }
    DisposableEffect(controller, mode) {
        when (mode) {
            SystemBarsMode.Visible -> controller.show(WindowInsetsCompat.Type.systemBars())
            SystemBarsMode.HiddenNavigation -> {
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            }
            SystemBarsMode.HiddenAll -> controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(controller, lightBackground) {
        controller.isAppearanceLightStatusBars = lightBackground
        controller.isAppearanceLightNavigationBars = lightBackground
        onDispose { }
    }
}

/** Keeps the display awake while the reader is on screen. */
@Composable
internal fun ReaderKeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val before = view.keepScreenOn
        if (enabled) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}
