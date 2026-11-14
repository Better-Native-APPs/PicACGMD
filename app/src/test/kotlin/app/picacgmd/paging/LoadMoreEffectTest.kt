package app.picacgmd.paging

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
internal class LoadMoreEffectTest {
    @Test
    fun theEndZoneStartsAtTheLastRowsOfTheWholeList() {
        assertFalse(endShown(lastShownIndex = 15, rowCount = 20, endZone = 4))
        assertTrue(endShown(lastShownIndex = 16, rowCount = 20, endZone = 4))
        assertTrue(endShown(lastShownIndex = 19, rowCount = 20, endZone = 4))
    }

    @Test
    fun pinnedRowsHeadersAndTheFooterCountWithoutRegularComments() {
        // The five rows are the pinned header, two pinned comments, the regular header and the footer.
        assertFalse(endShown(lastShownIndex = 0, rowCount = 5, endZone = 4))
        assertTrue(endShown(lastShownIndex = 1, rowCount = 5, endZone = 4))
    }

    @Test
    fun fewerRowsThanTheEndZoneCountAsShownFromTheFirstRow() {
        assertTrue(endShown(lastShownIndex = 0, rowCount = 3, endZone = 4))
        assertTrue(endShown(lastShownIndex = 2, rowCount = 3, endZone = 4))
    }

    @Test
    fun noShownRowCountsOnlyWhenTheEndZoneReachesTheFirstRow() {
        assertTrue(endShown(lastShownIndex = null, rowCount = 3, endZone = 4))
        assertFalse(endShown(lastShownIndex = null, rowCount = 5, endZone = 4))
    }

    @Test
    fun theCurrentRevisionContinuesOnlyWhenItsEndCanBeUsed() {
        data class Case(val canLoad: Boolean, val listShown: Boolean, val measured: Boolean, val endShown: Boolean, val wanted: Int?)
        val cases = listOf(
            Case(false, false, false, false, null),
            Case(false, true, true, true, null),
            Case(true, false, false, false, 7),
            Case(true, false, true, true, 7),
            Case(true, true, false, false, null),
            Case(true, true, false, true, null),
            Case(true, true, true, false, null),
            Case(true, true, true, true, 7),
        )
        cases.forEach { case ->
            assertEquals(
                case.wanted,
                revisionToContinue(7, case.canLoad, case.listShown, { case.measured }, { case.endShown }),
                case.toString(),
            )
        }
    }

    @Test
    fun loadingThatIsNotAllowedDoesNotAskWhetherTheListIsMeasuredOrAtItsEnd() {
        for (listShown in listOf(false, true)) {
            assertNull(
                revisionToContinue(7, canLoad = false, listShown = listShown,
                    measured = { fail("a disabled load must not ask about measurement") },
                    endShown = { fail("a disabled load must not ask about the end") }),
            )
        }
    }

    @Test
    fun aScreenWithoutAListDoesNotAskForMeasurementOrLayout() {
        assertEquals(
            7,
            revisionToContinue(7, canLoad = true, listShown = false,
                measured = { fail("an absent list has no measurement") },
                endShown = { fail("an absent list has no layout") }),
        )
    }

    @Test
    fun anUnmeasuredListDoesNotAskWhetherTheEndIsShown() {
        assertNull(
            revisionToContinue(7, canLoad = true, listShown = true,
                measured = { false }, endShown = { fail("an earlier layout must not answer for the current rows") }),
        )
    }

    @Test
    fun pagesThatLeaveTheEndOnScreenFollowOneAnotherWithOneRequestPerRevision() = runTest {
        val list = FakeList(rows = listOf("a", "b"), lastShownIndex = 1)
        val requests = list.watch(this)
        assertEquals(listOf(1), requests())
        assertEquals(listOf(1), requests())

        list.canLoad = false
        assertEquals(listOf(1), requests())
        list.apply { revision = 2; rows = rows + listOf("c", "d"); canLoad = true }
        assertEquals(listOf(1), requests())
        list.measure(lastShownIndex = 3)
        assertEquals(listOf(1, 2), requests())

        list.canLoad = false
        assertEquals(listOf(1, 2), requests())
        list.apply { revision = 3; rows = rows + listOf("e", "f"); canLoad = true }
        assertEquals(listOf(1, 2), requests())
        list.measure(lastShownIndex = 5)
        assertEquals(listOf(1, 2, 3), requests())
        assertEquals(listOf(1, 2, 3), requests())

        list.apply { revision = 4; canLoad = false }
        assertEquals(listOf(1, 2, 3), requests())
    }

    @Test
    fun aLongPageWaitsUntilItsEndIsScrolledIntoView() = runTest {
        val list = FakeList(rows = ('a'..'f').map(Char::toString), lastShownIndex = 1)
        val requests = list.watch(this)
        assertEquals(emptyList(), requests())

        list.lastShownIndex = 3
        assertEquals(emptyList(), requests())
        list.lastShownIndex = 4
        assertEquals(listOf(1), requests())
        list.lastShownIndex = 5
        assertEquals(listOf(1), requests())
        list.lastShownIndex = 4
        assertEquals(listOf(1), requests())

        list.canLoad = false
        assertEquals(listOf(1), requests())
        list.apply { revision = 2; rows = rows + ('g'..'l').map(Char::toString); canLoad = true }
        assertEquals(listOf(1), requests())
        list.measure(lastShownIndex = 7)
        assertEquals(listOf(1), requests())
        list.lastShownIndex = 10
        assertEquals(listOf(1, 2), requests())
        list.lastShownIndex = 11
        assertEquals(listOf(1, 2), requests())
    }

    @Test
    fun reorderedRowsOfTheSameCountWaitForTheirOwnMeasurement() = runTest {
        val original = ('a'..'t').map(Char::toString)
        val list = FakeList(rows = original, lastShownIndex = 2)
        val requests = list.watch(this)
        assertEquals(emptyList(), requests())

        list.apply { rows = listOf("a") + ('d'..'t').map(Char::toString) + listOf("b", "c"); revision = 2 }
        assertEquals(original.size, list.rows.size)
        assertEquals(emptyList(), requests())
        list.lastShownIndex = 18
        assertEquals(emptyList(), requests())
        list.lastShownIndex = 19
        assertEquals(emptyList(), requests())
        list.lastShownIndex = 2
        assertEquals(emptyList(), requests())

        list.measure(lastShownIndex = 2)
        assertEquals(emptyList(), requests())
        list.lastShownIndex = 19
        assertEquals(listOf(2), requests())
    }

    @Test
    fun aRequestTurnedDownWhileBusyIsRepeatedForANewRevisionWithEqualRows() = runTest {
        val original = listOf("a", "b")
        val list = FakeList(rows = original, lastShownIndex = 1)
        var busy = true
        val accepted = mutableListOf<Int>()
        val requests = list.watch(this) { if (!busy) accepted += it }
        assertEquals(listOf(1), requests())
        assertEquals(emptyList(), accepted)
        assertTrue(list.canLoad)

        busy = false
        list.apply { rows = rows.toList(); revision = 2 }
        assertEquals(original, list.rows)
        assertNotSame(original, list.rows)
        assertTrue(list.canLoad)
        assertEquals(listOf(1), requests())
        list.measure(lastShownIndex = 1)
        assertEquals(listOf(1, 2), requests())
        assertEquals(listOf(2), accepted)
        assertEquals(listOf(1, 2), requests())
    }

    @Test
    fun aPageThatAddsNoRowsContinuesWithTheNextOne() = runTest {
        val list = FakeList(rows = listOf("a", "b", "c"), lastShownIndex = 2)
        val requests = list.watch(this)
        assertEquals(listOf(1), requests())

        list.canLoad = false
        assertEquals(listOf(1), requests())
        list.apply { revision = 2; rows = rows.toList(); canLoad = true }
        assertEquals(listOf(1), requests())
        list.measure(lastShownIndex = 2)
        assertEquals(listOf(1, 2), requests())

        list.canLoad = false
        assertEquals(listOf(1, 2), requests())
        list.apply { revision = 3; canLoad = true }
        assertEquals(listOf(1, 2, 3), requests())
    }

    @Test
    fun aFailedPageWaitsUntilLoadingIsAllowedUnderANewRevision() = runTest {
        val list = FakeList(rows = listOf("a", "b", "c"), lastShownIndex = 2)
        val requests = list.watch(this)
        assertEquals(listOf(1), requests())

        list.apply { canLoad = false; revision = 2 }
        assertEquals(listOf(1), requests())
        list.lastShownIndex = 1
        assertEquals(listOf(1), requests())
        list.lastShownIndex = 2
        assertEquals(listOf(1), requests())

        list.apply { revision = 3; canLoad = true }
        assertEquals(listOf(1, 3), requests())
        assertEquals(listOf(1, 3), requests())
    }

    @Test
    fun aScreenWithoutAListContinuesWithoutALayoutUntilAListAppears() = runTest {
        val list = FakeList(rows = emptyList(), lastShownIndex = null, listShown = false)
        list.measuredRows = Any()
        list.rowCount = 20
        val requests = list.watch(this)
        assertEquals(listOf(1), requests())
        assertEquals(listOf(1), requests())

        list.revision = 2
        assertEquals(listOf(1, 2), requests())
        list.apply { rows = listOf("a", "b"); revision = 3; listShown = true }
        assertEquals(listOf(1, 2), requests())
        list.apply { lastShownIndex = 19; rowCount = 20 }
        assertEquals(listOf(1, 2), requests())
        list.measure(lastShownIndex = 1)
        assertEquals(listOf(1, 2, 3), requests())
        assertEquals(listOf(1, 2, 3), requests())
    }

    private class FakeList(
        rows: List<String>,
        lastShownIndex: Int?,
        canLoad: Boolean = true,
        listShown: Boolean = true,
    ) {
        var revision by mutableStateOf(1)
        var canLoad by mutableStateOf(canLoad)
        var listShown by mutableStateOf(listShown)
        var rows by mutableStateOf(rows, referentialEqualityPolicy())
        var measuredRows by mutableStateOf<Any?>(rows, referentialEqualityPolicy())
        var lastShownIndex by mutableStateOf(lastShownIndex)
        var rowCount by mutableStateOf(rows.size)

        fun measure(lastShownIndex: Int?) {
            measuredRows = rows
            this.lastShownIndex = lastShownIndex
            rowCount = rows.size
        }

        fun watch(scope: TestScope, onLoadMore: (Int) -> Unit = {}): () -> List<Int> {
            val requests = mutableListOf<Int>()
            scope.backgroundScope.launch {
                requestPagesWhile(
                    revisionToContinue = {
                        revisionToContinue(revision, canLoad, listShown,
                            measured = { measuredRows === rows },
                            endShown = { endShown(lastShownIndex, rowCount, endZone = 2) })
                    },
                    onLoadMore = { revision -> requests += revision; onLoadMore(revision) },
                )
            }
            return {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                requests.toList()
            }
        }
    }
}
