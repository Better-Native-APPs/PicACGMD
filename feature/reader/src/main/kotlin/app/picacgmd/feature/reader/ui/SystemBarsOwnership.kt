package app.picacgmd.feature.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.picacgmd.feature.reader.SystemBarsMode

internal data class SystemBarsAppearance(
    val mode: SystemBarsMode,
    val lightStatusBars: Boolean,
    val lightNavigationBars: Boolean,
    val behavior: Int,
)

internal data class SystemBarsVisibility(val shownTypes: Int, val hiddenTypes: Int)

internal fun SystemBarsMode.visibility(): SystemBarsVisibility = when (this) {
    SystemBarsMode.Visible -> SystemBarsVisibility(WindowInsetsCompat.Type.systemBars(), 0)
    SystemBarsMode.HiddenStatus -> SystemBarsVisibility(
        WindowInsetsCompat.Type.navigationBars(), WindowInsetsCompat.Type.statusBars(),
    )
    SystemBarsMode.HiddenNavigation -> SystemBarsVisibility(
        WindowInsetsCompat.Type.statusBars(), WindowInsetsCompat.Type.navigationBars(),
    )
    SystemBarsMode.HiddenAll -> SystemBarsVisibility(0, WindowInsetsCompat.Type.systemBars())
}

/** A visible app baseline with one resumed reader taking temporary ownership of the window. */
internal class SystemBarsOwnership(
    initialAppAppearance: SystemBarsAppearance,
    private val apply: (SystemBarsAppearance) -> Unit,
) {
    private var appAppearance = initialAppAppearance.copy(mode = SystemBarsMode.Visible)
    private var readerOwner: Any? = null
    private var readerAppearance: SystemBarsAppearance? = null
    var currentAppearance by mutableStateOf(appAppearance)
        private set

    fun updateAppAppearance(lightBackground: Boolean) {
        appAppearance = appAppearance.copy(
            lightStatusBars = lightBackground,
            lightNavigationBars = lightBackground,
        )
        applyCurrent()
    }

    fun applyReader(owner: Any, mode: SystemBarsMode, lightBackground: Boolean) {
        readerOwner = owner
        readerAppearance = SystemBarsAppearance(
            mode = mode,
            lightStatusBars = lightBackground,
            lightNavigationBars = lightBackground,
            behavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
        )
        applyCurrent()
    }

    fun releaseReader(owner: Any) {
        if (readerOwner !== owner) return
        readerOwner = null
        readerAppearance = null
        applyCurrent()
    }

    fun restoreApp() {
        readerOwner = null
        readerAppearance = null
        applyCurrent()
    }

    private fun applyCurrent() {
        val appearance = readerAppearance ?: appAppearance
        currentAppearance = appearance
        apply(appearance)
    }
}
