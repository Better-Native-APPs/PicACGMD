package app.picacgmd.paging

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * Tells which rows a lazy list was last measured with.
 *
 * The layout a lazy list publishes is one step behind its content: once new rows are composed, the latest layout
 * still describes the rows before them until the list is measured again. Both ends of that step are marked here.
 * [rows] is the first statement of the list's content, which the list runs whenever it rebuilds its rows, and
 * [measured] sits on the list, where it runs every time the list is measured. A scroll that keeps the same rows
 * on screen is not measured: the layout it publishes is a moved copy of the last measured one, with the same rows.
 *
 * Rows are named by the object they are built from, and two such objects are the same rows only when they are
 * the same instance.
 */
@Stable
internal class RowsWatch {
    private var built: Any? = Unset

    /** What the rows that the latest layout of the list describes were built from. */
    var measuredRows: Any? by mutableStateOf(Unset, referentialEqualityPolicy())
        private set

    /** Call first inside the list's content, with what the rows that follow are built from. */
    fun rows(source: Any?) {
        built = source
    }

    /** Goes on the list itself. It changes nothing about how the list is laid out. */
    val measured: Modifier = Modifier.layout { measurable, constraints ->
        // The list reads its rows and publishes its layout inside this call.
        val placeable = measurable.measure(constraints)
        measuredRows = built
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    private object Unset
}

/**
 * Asks for the next page while the end of a lazy list is on screen.
 *
 * The end is on screen when the last row the list shows is one of its last [endZone] rows, whatever those are:
 * headers, pinned rows and footers count like any other. That is read only from a layout that [watch] knows to
 * be measured with [rows], the object the list's rows are built from, which is another object whenever they may
 * be other rows. The layout of earlier rows therefore never answers for new ones. Where the screen shows no list
 * at all ([listShown] is false) there is nothing to scroll and only another page can bring rows, so the end
 * counts as on screen.
 *
 * One request is made for each [revision] of the paged state while [canLoad] holds, and [onLoadMore] is handed
 * the revision it is made for. The holder turns the request down when its state has moved on, because the rows
 * that were scrolled to are then no longer the rows there are. Every load ends in a new revision, also one that
 * failed or brought back the same rows. A request that was turned down is therefore made again for the rows that
 * followed, a page that added no rows continues with the next one, and a page that failed, for which [canLoad]
 * is false, waits for the user.
 */
@Composable
internal fun LoadMoreEffect(
    listState: LazyListState,
    watch: RowsWatch,
    rows: Any?,
    revision: Int,
    canLoad: Boolean,
    endZone: Int,
    onLoadMore: (revision: Int) -> Unit,
    listShown: Boolean = true,
) {
    LoadMoreEffect(watch, rows, revision, canLoad, listShown, onLoadMore) {
        val layout = listState.layoutInfo
        endShown(layout.visibleItemsInfo.lastOrNull()?.index, layout.totalItemsCount, endZone)
    }
}

/** The same for a grid. */
@Composable
internal fun LoadMoreEffect(
    gridState: LazyGridState,
    watch: RowsWatch,
    rows: Any?,
    revision: Int,
    canLoad: Boolean,
    endZone: Int,
    onLoadMore: (revision: Int) -> Unit,
    listShown: Boolean = true,
) {
    LoadMoreEffect(watch, rows, revision, canLoad, listShown, onLoadMore) {
        val layout = gridState.layoutInfo
        endShown(layout.visibleItemsInfo.lastOrNull()?.index, layout.totalItemsCount, endZone)
    }
}

@Composable
private fun LoadMoreEffect(
    watch: RowsWatch,
    rows: Any?,
    revision: Int,
    canLoad: Boolean,
    listShown: Boolean,
    onLoadMore: (revision: Int) -> Unit,
    endShown: () -> Boolean,
) {
    // Compared by identity with what the list was measured with, so an equal copy must not stand in for it.
    val currentRows = remember { mutableStateOf(rows, referentialEqualityPolicy()) }.apply { value = rows }
    val currentRevision by rememberUpdatedState(revision)
    val currentCanLoad by rememberUpdatedState(canLoad)
    val currentListShown by rememberUpdatedState(listShown)
    val currentEndShown by rememberUpdatedState(endShown)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(watch) {
        requestPagesWhile(
            revisionToContinue = {
                revisionToContinue(
                    revision = currentRevision,
                    canLoad = currentCanLoad,
                    listShown = currentListShown,
                    measured = { watch.measuredRows === currentRows.value },
                    endShown = currentEndShown,
                )
            },
            onLoadMore = { revision -> currentOnLoadMore(revision) },
        )
    }
}

/**
 * Whether the last row a lazy list shows is one of its last [endZone] rows. Index and count come from one
 * layout of the list, so every row it has counts, and a row that appears twice is only itself.
 */
internal fun endShown(lastShownIndex: Int?, rowCount: Int, endZone: Int): Boolean = (lastShownIndex ?: 0) >= rowCount - endZone

/**
 * The revision for which the next page is wanted now, or null. [measured] says whether the layout that
 * [endShown] reads belongs to the rows of now; [endShown] is not asked before it does.
 */
internal fun revisionToContinue(
    revision: Int,
    canLoad: Boolean,
    listShown: Boolean,
    measured: () -> Boolean,
    endShown: () -> Boolean,
): Int? = when {
    !canLoad -> null
    !listShown -> revision
    measured() && endShown() -> revision
    else -> null
}

/**
 * Calls [onLoadMore] each time [revisionToContinue], which may read snapshot state, turns to another revision.
 * Runs until cancelled.
 */
internal suspend fun requestPagesWhile(revisionToContinue: () -> Int?, onLoadMore: (revision: Int) -> Unit) {
    // A snapshot flow leaves out a value equal to the one before it, so staying on a revision asks once.
    snapshotFlow(revisionToContinue).collect { revision -> if (revision != null) onLoadMore(revision) }
}
