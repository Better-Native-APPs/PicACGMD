package app.picacgmd.feature.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import app.picacgmd.feature.reader.ReaderFilter
import app.picacgmd.feature.reader.logic.TapAction
import app.picacgmd.feature.reader.logic.colorMatrix

/** What the slider, the tap zones and the volume keys ask of the visible reading mode. */
internal interface PageNavigator {
    /** One page or screen forward or back; going forward past the end calls [ReaderPageHost.onPastEnd]. */
    suspend fun step(forward: Boolean)

    /** Straight to [page], without animating. */
    suspend fun jumpTo(page: Int)
}

/** Holds the navigator of whichever reading mode is on screen. */
@Stable
internal class PageNavigatorHolder {
    var active: PageNavigator? = null
}

/** Makes [navigator] the active one for as long as the calling mode is on screen. */
@Composable
internal fun RegisterNavigator(holder: PageNavigatorHolder, navigator: PageNavigator) {
    DisposableEffect(holder, navigator) {
        holder.active = navigator
        onDispose { if (holder.active === navigator) holder.active = null }
    }
}

/** What the reading modes share with the screen around them. */
@Stable
internal class ReaderPageHost(
    val retry: PageRetryState,
    val reloads: PageReloadState,
    val navigator: PageNavigatorHolder,
    val onPageChange: (Int) -> Unit,
    val onTapAction: (TapAction) -> Unit,
    val onLongPressPage: (Int) -> Unit,
    /** The reader tried to go forward past the end of the chapter. */
    val onPastEnd: () -> Unit,
    /** The chapter-end card took over the screen, or left it. */
    val onChapterEndVisible: (Boolean) -> Unit,
)

/** The card shown after the last page; each reading mode decides where it goes. */
internal typealias ChapterEndSlot = @Composable (pull: ChapterPullState, modifier: Modifier) -> Unit

/** The colour filter for [filter], or null for 正常. */
@Composable
internal fun rememberPageColorFilter(filter: ReaderFilter): ColorFilter? = remember(filter) {
    filter.colorMatrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
}
