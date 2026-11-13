package app.picacgmd.paging

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Requests the next page while the end of a lazy list is on screen.
 *
 * [endKeys] are the list keys of the last rows of the content as it is now. The end is on screen when the
 * latest layout of the list shows one of them, or when there are no rows at all. A layout that still shows
 * earlier content shows none of the new keys, and neither does a list that is not displayed, so a page is
 * only requested for an end the user is looking at.
 *
 * One request is made per applied [page] while [canLoad] holds. A page that added no rows therefore
 * continues with the next one, and a page that failed, for which [canLoad] is false, waits for the user.
 */
@Composable
internal fun LoadMoreEffect(listState: LazyListState, page: Int, canLoad: Boolean, endKeys: List<Any>, onLoadMore: () -> Unit) {
    LoadMoreEffect(page, canLoad, endKeys, { keys -> listState.layoutInfo.visibleItemsInfo.any { it.key in keys } }, onLoadMore)
}

/** The same for a grid. */
@Composable
internal fun LoadMoreEffect(gridState: LazyGridState, page: Int, canLoad: Boolean, endKeys: List<Any>, onLoadMore: () -> Unit) {
    LoadMoreEffect(page, canLoad, endKeys, { keys -> gridState.layoutInfo.visibleItemsInfo.any { it.key in keys } }, onLoadMore)
}

@Composable
private fun LoadMoreEffect(
    page: Int,
    canLoad: Boolean,
    endKeys: List<Any>,
    showsAny: (keys: List<Any>) -> Boolean,
    onLoadMore: () -> Unit,
) {
    val currentPage by rememberUpdatedState(page)
    val currentCanLoad by rememberUpdatedState(canLoad)
    val currentEndKeys by rememberUpdatedState(endKeys)
    val currentShowsAny by rememberUpdatedState(showsAny)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(Unit) {
        requestPagesWhile(
            pageToContinue = { pageToContinue(currentPage, currentCanLoad, currentEndKeys, currentShowsAny) },
            onLoadMore = { currentOnLoadMore() },
        )
    }
}

/** The applied page after which the next one is wanted now, or null. */
internal fun pageToContinue(page: Int, canLoad: Boolean, endKeys: List<Any>, showsAny: (keys: List<Any>) -> Boolean): Int? =
    if (canLoad && (endKeys.isEmpty() || showsAny(endKeys))) page else null

/** Calls [onLoadMore] each time [pageToContinue], which may read snapshot state, turns to a page. Runs until cancelled. */
internal suspend fun requestPagesWhile(pageToContinue: () -> Int?, onLoadMore: () -> Unit) {
    snapshotFlow(pageToContinue).distinctUntilChanged().collect { page -> if (page != null) onLoadMore() }
}
