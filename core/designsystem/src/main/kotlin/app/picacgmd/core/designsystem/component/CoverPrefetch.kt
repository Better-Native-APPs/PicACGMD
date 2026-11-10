package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * How far past the viewport a list of comics keeps its rows ready (2026-11-10). Rows inside the
 * window are composed ahead of the scroll, in idle time between frames, so their covers are already
 * loading before they come into view — through the very request the cover on screen makes, so the
 * two can never disagree about address or cache key.
 *
 * About three rows of a cover grid, six of a details list: enough that a normal scroll meets loaded
 * covers, small enough that a slow image server is not asked for a whole page at once.
 */
@OptIn(ExperimentalFoundationApi::class)
private val CoverCacheWindow = LazyLayoutCacheWindow(ahead = 640.dp)

/** State for a grid of comic covers that loads the covers just past the viewport ahead of time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCoverGridState(): LazyGridState = rememberLazyGridState(cacheWindow = CoverCacheWindow)

/** State for a list of comic rows that loads the covers just past the viewport ahead of time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCoverListState(): LazyListState = rememberLazyListState(cacheWindow = CoverCacheWindow)
