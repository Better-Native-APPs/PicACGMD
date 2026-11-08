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
import kotlinx.coroutines.flow.StateFlow

/** What the slider, the tap zones, the volume keys and 自动翻页 ask of the visible reading mode. */
internal interface PageNavigator {
    /**
     * One page, spread or screen forward or back. Going forward past the end calls
     * [ReaderPageHost.onPastEnd], unless [leaveAtEnd] is false (自动翻页 never leaves a chapter).
     * Returns false when there was nowhere to go and nothing happened.
     */
    suspend fun step(forward: Boolean, leaveAtEnd: Boolean = true): Boolean

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
    /** The page on screen changed; a spread reports its first and last page. */
    val onPageChange: (page: Int, lastVisible: Int) -> Unit,
    val onTapAction: (TapAction) -> Unit,
    val onLongPressPage: (Int) -> Unit,
    /** The reader tried to go forward past the end of the chapter. */
    val onPastEnd: () -> Unit,
    /** The chapter-end card took over the screen, or left it. */
    val onChapterEndVisible: (Boolean) -> Unit,
    /** Download progress of a remote page; see [app.picacgmd.feature.reader.ReaderSource.imageLoadProgress]. */
    val loadProgress: (url: String) -> StateFlow<Float?>? = { null },
    /**
     * The page or strip went above 1×, or came back. Zooming out (自由缩放 below 1×) does not count:
     * the whole width is still on screen and reading goes on as usual.
     */
    val onZoomedInChange: (Boolean) -> Unit = {},
    /** The controls are on screen; overlays of a mode (the minimap) make way for them. */
    val controlsShown: () -> Boolean = { false },
)

/** The card shown after the last page; each reading mode decides where it goes. */
internal typealias ChapterEndSlot = @Composable (pull: ChapterPullState, modifier: Modifier) -> Unit

/** The colour filter for [filter], or null for 正常. */
@Composable
internal fun rememberPageColorFilter(filter: ReaderFilter): ColorFilter? = remember(filter) {
    filter.colorMatrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
}
