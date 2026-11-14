package app.picacgmd.paging

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.picacgmd.testing.ComposeHostRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RowsWatchTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    @Test
    fun aListRecordsItsRowsAfterTheFirstLayout() {
        val fixture = host(Layout.LIST)
        composeRule.runOnIdle {
            assertSame(fixture.rows, fixture.watch.measuredRows)
            assertEquals(20, fixture.totalItemsCount)
            assertEquals(listOf(0, 1, 2), fixture.visibleIndices)
        }
    }

    @Test
    fun newListRowsKeepThePreviousMeasurementUntilLayout() {
        verifyRowsReplacement(Layout.LIST)
    }

    @Test
    fun aSmallListScrollPublishesAMovedLayoutWithoutMeasuring() {
        verifySmallScroll(Layout.LIST)
    }

    @Test
    fun aLargeListScrollMeasuresTheSameRowsAgain() {
        verifyLargeScroll(Layout.LIST)
    }

    @Test
    fun aListOutsideCompositionKeepsItsLastMeasuredRowsUntilShownAgain() {
        val fixture = host(Layout.LIST)
        val original = fixture.rows
        val replacement = keys(8)
        composeRule.runOnIdle {
            assertSame(original, fixture.watch.measuredRows)
            fixture.shown = false
        }
        composeRule.runOnIdle {
            fixture.rows = replacement
        }
        composeRule.runOnIdle {
            assertSame(original, fixture.watch.measuredRows)
            assertEquals(20, fixture.totalItemsCount)
            fixture.shown = true
        }
        composeRule.runOnIdle {
            assertSame(replacement, fixture.watch.measuredRows)
            assertEquals(8, fixture.totalItemsCount)
        }
    }

    @Test
    fun reorderedListKeysDoNotAskForAnotherPageUntilTheNewEndIsScrolledIntoView() {
        val original = ('a'..'t').map(Char::toString)
        val holder = FakeHolder(original)
        val fixture = hostHolder(holder)
        val reordered = listOf("a") + ('d'..'t').map(Char::toString) + listOf("b", "c")
        composeRule.runOnIdle {
            assertEquals(listOf(0, 1, 2), fixture.visibleIndices)
            assertEquals(emptyList(), holder.requests)
            holder.finishRefresh(reordered)
        }
        composeRule.runOnIdle {
            assertEquals(2, holder.revision)
            assertSame(reordered, fixture.watch.measuredRows)
            assertEquals(listOf(0, 1, 2), fixture.visibleIndices)
            assertEquals(listOf("a", "d", "e"), fixture.listState.layoutInfo.visibleItemsInfo.map { it.key })
            assertEquals(emptyList(), holder.requests)
            assertTrue(fixture.compositions.all { it.requests.isEmpty() })
            runBlocking { fixture.listState.scrollToItem(19) }
        }
        composeRule.runOnIdle {
            assertEquals(listOf(2), holder.requests)
            assertEquals(listOf(2), holder.accepted)
        }
    }

    @Test
    fun aLongReplacementDoesNotUseTheEndOfTheEarlierShortPage() {
        val original = keys(2)
        val holder = FakeHolder(original).apply { canLoad = false }
        val fixture = hostHolder(holder)
        val replacement = keys(20)
        composeRule.runOnIdle {
            assertSame(original, fixture.watch.measuredRows)
            assertEquals(2, fixture.totalItemsCount)
            assertEquals(listOf(0, 1), fixture.visibleIndices)
            assertEquals(emptyList(), holder.requests)
            holder.finishRefresh(replacement, canLoad = true)
        }
        composeRule.runOnIdle {
            assertEquals(2, holder.revision)
            assertTrue(holder.canLoad)
            val composed = fixture.compositions.first { it.rows === replacement }
            assertSame(original, composed.measuredRows)
            assertEquals(2, composed.totalItemsCount)
            assertSame(replacement, fixture.watch.measuredRows)
            assertEquals(20, fixture.totalItemsCount)
            assertEquals(listOf(0, 1, 2), fixture.visibleIndices)
            assertEquals(emptyList(), holder.requests)
            assertTrue(fixture.compositions.all { it.requests.isEmpty() })
            runBlocking { fixture.listState.scrollToItem(19) }
        }
        composeRule.runOnIdle {
            assertEquals(listOf(2), holder.requests)
            assertEquals(listOf(2), holder.accepted)
        }
    }

    @Test
    fun shortListPagesChainUntilAnAppendMovesTheEndOffScreen() {
        val holder = FakeHolder(keys(2), appends = listOf(keys(2, start = 2), keys(20, start = 4)))
        val fixture = hostHolder(holder)
        composeRule.runOnIdle {
            assertEquals(listOf(1, 2), holder.requests)
            assertEquals(listOf(1, 2), holder.accepted)
            assertEquals(3, holder.revision)
            assertEquals(24, holder.rows.size)
            assertSame(holder.rows, fixture.watch.measuredRows)
            assertEquals(24, fixture.totalItemsCount)
            assertEquals(listOf(0, 1, 2), fixture.visibleIndices)
        }
    }

    @Test
    fun aRefreshBetweenFramesRejectsTheEarlierRequestAndContinuesWithTheCurrentRevision() {
        val original = keys(2)
        val holder = FakeHolder(original, appends = listOf(keys(20, start = 2)))
        var refreshFinished = false
        val fixture = hostHolder(holder, beforeLoadMore = {
            if (!refreshFinished) {
                // The refresh finishes after these rows were measured but before their request reaches the holder.
                holder.finishRefresh(original.toList())
                refreshFinished = true
            }
        })
        composeRule.runOnIdle {
            assertTrue(refreshFinished)
            assertEquals(listOf(1, 2), holder.requests)
            assertEquals(listOf(1), holder.rejected)
            assertEquals(listOf(2), holder.accepted)
            assertEquals(3, holder.revision)
            assertEquals(22, holder.rows.size)
            assertSame(holder.rows, fixture.watch.measuredRows)
            val refreshed = fixture.compositions.first { it.revision == 2 }
            assertEquals(original, refreshed.rows)
            assertNotSame(original, refreshed.rows)
        }
    }

    @Test
    fun aGridRecordsItsRowsAfterTheFirstLayout() {
        val fixture = host(Layout.GRID)
        composeRule.runOnIdle {
            assertSame(fixture.rows, fixture.watch.measuredRows)
            assertEquals(20, fixture.totalItemsCount)
            assertEquals((0..5).toList(), fixture.visibleIndices)
        }
    }

    @Test
    fun newGridRowsKeepThePreviousMeasurementUntilLayout() {
        verifyRowsReplacement(Layout.GRID)
    }

    @Test
    fun aSmallGridScrollPublishesAMovedLayoutWithoutMeasuring() {
        verifySmallScroll(Layout.GRID)
    }

    @Test
    fun aLargeGridScrollMeasuresTheSameRowsAgain() {
        verifyLargeScroll(Layout.GRID)
    }

    @Test
    fun addingAGridFooterChangesTheRowsObjectBeforeItIsMeasured() {
        val fixture = host(Layout.GRID, footerInRows = true)
        val list = fixture.rows
        lateinit var previous: Any
        composeRule.runOnIdle {
            previous = requireNotNull(fixture.watch.measuredRows)
            assertEquals(20, fixture.totalItemsCount)
            fixture.compositions.clear()
            fixture.footer = true
        }
        composeRule.runOnIdle {
            assertSame(list, fixture.rows)
            val composed = fixture.compositions.first { it.rows !== previous }
            assertSame(previous, composed.measuredRows)
            assertEquals(20, composed.totalItemsCount)
            assertNotSame(previous, composed.rows)
            assertSame(composed.rows, fixture.watch.measuredRows)
            assertEquals(21, fixture.totalItemsCount)
        }
    }

    private fun verifyRowsReplacement(layout: Layout) {
        val fixture = host(layout)
        val original = fixture.rows
        val replacement = keys(8)
        composeRule.runOnIdle {
            assertSame(original, fixture.watch.measuredRows)
            assertEquals(20, fixture.totalItemsCount)
            fixture.compositions.clear()
            fixture.rows = replacement
        }
        composeRule.runOnIdle {
            val composed = fixture.compositions.first { it.rows === replacement }
            assertSame(original, composed.measuredRows)
            assertEquals(20, composed.totalItemsCount)
            assertSame(replacement, fixture.watch.measuredRows)
            assertEquals(8, fixture.totalItemsCount)
        }
    }

    private fun verifySmallScroll(layout: Layout) {
        val fixture = host(layout)
        var before = 0
        var visibleBefore = emptyList<Int>()
        composeRule.runOnIdle {
            before = fixture.measureCount
            visibleBefore = fixture.visibleIndices
            assertEquals(if (layout == Layout.LIST) listOf(0, 1, 2) else (0..5).toList(), visibleBefore)
            fixture.scrollBy(10)
        }
        composeRule.runOnIdle {
            assertEquals(0, fixture.firstVisibleItemIndex)
            assertEquals(with(fixture.density) { 10.dp.roundToPx() }, fixture.firstVisibleItemScrollOffset)
            assertEquals(before, fixture.measureCount)
            assertSame(fixture.rows, fixture.watch.measuredRows)
            assertEquals(visibleBefore, fixture.visibleIndices)
            println("RowsWatch $layout measures for a 10 dp scroll: $before -> ${fixture.measureCount}")
        }
    }

    private fun verifyLargeScroll(layout: Layout) {
        val fixture = host(layout)
        var before = 0
        var visibleBefore = emptyList<Int>()
        composeRule.runOnIdle {
            before = fixture.measureCount
            visibleBefore = fixture.visibleIndices
            fixture.scrollBy(250)
        }
        composeRule.runOnIdle {
            assertTrue(fixture.measureCount > before)
            assertSame(fixture.rows, fixture.watch.measuredRows)
            assertNotEquals(visibleBefore, fixture.visibleIndices)
            assertEquals(if (layout == Layout.LIST) listOf(2, 3, 4) else (4..9).toList(), fixture.visibleIndices)
            println("RowsWatch $layout measures for a 250 dp scroll: $before -> ${fixture.measureCount}")
        }
    }

    private fun host(layout: Layout, footerInRows: Boolean = false): LazyFixture {
        val fixture = LazyFixture(layout, keys(20))
        composeRule.setContent {
            fixture.density = LocalDensity.current
            val list = fixture.rows
            val footer = fixture.footer
            val rows = if (footerInRows) remember(list, footer) { Any() } else list
            if (layout == Layout.LIST) {
                fixture.listState = rememberLazyListState()
                if (fixture.shown) {
                    LazyColumn(state = fixture.listState, modifier = fixture.modifier) {
                        fixture.watch.rows(rows)
                        items(list, key = { it }) { Cell() }
                    }
                }
            } else {
                fixture.gridState = rememberLazyGridState()
                if (fixture.shown) {
                    LazyVerticalGrid(columns = GridCells.Fixed(2), state = fixture.gridState, modifier = fixture.modifier) {
                        fixture.watch.rows(rows)
                        items(list, key = { it }) { Cell() }
                        if (footer) item(key = "footer") { Cell() }
                    }
                }
            }
            SideEffect {
                fixture.compositions += CompositionRecord(rows, fixture.watch.measuredRows, fixture.totalItemsCount)
            }
        }
        return fixture
    }

    private fun hostHolder(holder: FakeHolder, beforeLoadMore: (Int) -> Unit = {}): LazyFixture {
        val fixture = LazyFixture(Layout.LIST, holder.rows)
        composeRule.setContent {
            fixture.listState = rememberLazyListState()
            fixture.density = LocalDensity.current
            val rows = holder.rows
            val revision = holder.revision
            LazyColumn(state = fixture.listState, modifier = fixture.modifier) {
                fixture.watch.rows(rows)
                items(rows, key = { it }) { Cell() }
            }
            LoadMoreEffect(
                listState = fixture.listState,
                watch = fixture.watch,
                rows = rows,
                revision = revision,
                canLoad = holder.canLoad,
                endZone = 2,
                onLoadMore = { requested -> beforeLoadMore(requested); holder.loadNext(requested) },
            )
            SideEffect {
                fixture.compositions += CompositionRecord(rows, fixture.watch.measuredRows, fixture.totalItemsCount,
                    revision = revision, requests = holder.requests.toList())
            }
        }
        return fixture
    }

    private enum class Layout { LIST, GRID }

    private class LazyFixture(val layout: Layout, rows: List<String>) {
        val watch = RowsWatch()
        var rows by mutableStateOf(rows, referentialEqualityPolicy())
        var shown by mutableStateOf(true)
        var footer by mutableStateOf(false)
        var measureCount = 0
        val compositions = mutableListOf<CompositionRecord>()
        lateinit var listState: LazyListState
        lateinit var gridState: LazyGridState
        lateinit var density: Density

        val modifier: Modifier get() = Modifier
            .requiredSize(width = 200.dp, height = 250.dp)
            .then(watch.measured)
            .layout { measurable, constraints ->
                measureCount++
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }

        val totalItemsCount: Int get() =
            if (layout == Layout.LIST) listState.layoutInfo.totalItemsCount else gridState.layoutInfo.totalItemsCount
        val visibleIndices: List<Int> get() =
            if (layout == Layout.LIST) listState.layoutInfo.visibleItemsInfo.map { it.index }
            else gridState.layoutInfo.visibleItemsInfo.map { it.index }
        val firstVisibleItemIndex: Int get() =
            if (layout == Layout.LIST) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
        val firstVisibleItemScrollOffset: Int get() =
            if (layout == Layout.LIST) listState.firstVisibleItemScrollOffset else gridState.firstVisibleItemScrollOffset

        fun scrollBy(dp: Int) {
            val pixels = with(density) { dp.dp.toPx() }
            if (layout == Layout.LIST) listState.dispatchRawDelta(pixels) else gridState.dispatchRawDelta(pixels)
        }
    }

    private data class CompositionRecord(
        val rows: Any,
        val measuredRows: Any?,
        val totalItemsCount: Int,
        val revision: Int? = null,
        val requests: List<Int> = emptyList(),
    )

    private class FakeHolder(rows: List<String>, appends: List<List<String>> = emptyList()) {
        var revision by mutableStateOf(1)
        var canLoad by mutableStateOf(true)
        var rows by mutableStateOf(rows, referentialEqualityPolicy())
        val requests = mutableListOf<Int>()
        val accepted = mutableListOf<Int>()
        val rejected = mutableListOf<Int>()
        private val appends = ArrayDeque(appends)

        fun loadNext(revision: Int) {
            requests += revision
            if (revision != this.revision || !canLoad) {
                rejected += revision
                return
            }
            accepted += revision
            val added = appends.removeFirstOrNull() ?: return
            rows = rows + added
            this.revision++
        }

        fun finishRefresh(rows: List<String>, canLoad: Boolean = this.canLoad) {
            this.rows = rows
            this.canLoad = canLoad
            revision++
        }
    }

    private companion object {
        fun keys(count: Int, start: Int = 0): List<String> = List(count) { "r${start + it}" }

        @Composable
        fun Cell() {
            Box(Modifier.fillMaxWidth().requiredHeight(100.dp))
        }
    }
}
