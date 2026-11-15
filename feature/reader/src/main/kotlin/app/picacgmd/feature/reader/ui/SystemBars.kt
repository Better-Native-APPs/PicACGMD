package app.picacgmd.feature.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.picacgmd.feature.reader.SystemBarsMode

private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}

private val LocalSystemBarsOwnership = staticCompositionLocalOf<SystemBarsOwnership?> { null }

/** Shows both system bars outside the reader and keeps their icons aligned with the app theme. */
@Composable
fun AppSystemBars(lightBackground: Boolean, content: @Composable () -> Unit) {
    val ownership = rememberSystemBarsOwnership()
    DisposableEffect(ownership) {
        onDispose { ownership?.restoreApp() }
    }
    SideEffect { ownership?.updateAppAppearance(lightBackground) }
    CompositionLocalProvider(LocalSystemBarsOwnership provides ownership, content = content)
}

/** A resumed reader owns the bars; stopping or leaving it restores the visible app baseline. */
@Composable
fun ReaderSystemBars(mode: SystemBarsMode, lightBackground: Boolean) {
    val ownership = LocalSystemBarsOwnership.current ?: rememberSystemBarsOwnership() ?: return
    val owner = remember(ownership) { Any() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    DisposableEffect(ownership, owner, resumed) {
        onDispose { ownership.releaseReader(owner) }
    }
    SideEffect {
        if (resumed) ownership.applyReader(owner, mode, lightBackground)
    }
}

@Composable
private fun rememberSystemBarsOwnership(): SystemBarsOwnership? {
    val view = LocalView.current
    if (view.isInEditMode) return null
    return remember(view) {
        view.context.findWindow()?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            val initial = SystemBarsAppearance(
                mode = SystemBarsMode.Visible,
                lightStatusBars = controller.isAppearanceLightStatusBars,
                lightNavigationBars = controller.isAppearanceLightNavigationBars,
                behavior = controller.systemBarsBehavior,
            )
            SystemBarsOwnership(initial) { appearance ->
                controller.systemBarsBehavior = appearance.behavior
                controller.isAppearanceLightStatusBars = appearance.lightStatusBars
                controller.isAppearanceLightNavigationBars = appearance.lightNavigationBars
                when (appearance.mode) {
                    SystemBarsMode.Visible -> controller.show(WindowInsetsCompat.Type.systemBars())
                    SystemBarsMode.HiddenStatus -> {
                        controller.show(WindowInsetsCompat.Type.navigationBars())
                        controller.hide(WindowInsetsCompat.Type.statusBars())
                    }
                    SystemBarsMode.HiddenNavigation -> {
                        controller.show(WindowInsetsCompat.Type.statusBars())
                        controller.hide(WindowInsetsCompat.Type.navigationBars())
                    }
                    SystemBarsMode.HiddenAll -> controller.hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
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
