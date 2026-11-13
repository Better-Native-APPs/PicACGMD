package app.picacgmd.paging

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
internal class LoadMoreEffectTest {
    @Test
    fun aPageIsWantedOnlyWhileLoadingIsAllowedAndAnEndRowIsShown() {
        val shown = setOf<Any>("a", "b")
        val shows = { keys: List<Any> -> keys.any { it in shown } }

        assertEquals(3, pageToContinue(3, canLoad = true, endKeys = listOf("b", "c"), showsAny = shows))
        assertNull(pageToContinue(3, canLoad = true, endKeys = listOf("c", "d"), showsAny = shows))
        assertNull(pageToContinue(3, canLoad = false, endKeys = listOf("b", "c"), showsAny = shows))
        // Without rows there is nothing to scroll: the end counts as shown and the layout is not asked.
        assertEquals(1, pageToContinue(1, canLoad = true, endKeys = emptyList()) { error("the layout must not be asked") })
        assertNull(pageToContinue(1, canLoad = false, endKeys = emptyList()) { error("the layout must not be asked") })
    }

    @Test
    fun pagesThatLeaveTheEndOnScreenFollowOneAnotherWithOneRequestEach() = runTest {
        val list = FakeList(rows = listOf("a", "b"), shown = listOf("a", "b"))
        val requests = list.watch(this)
        assertEquals(1, requests())

        list.canLoad = false                                    // the request is in flight
        assertEquals(1, requests())
        list.apply { page = 2; rows = listOf("a", "b", "c", "d"); canLoad = true }
        assertEquals(1, requests())                             // the layout still shows page 1
        list.shown = listOf("a", "b", "c", "d")
        assertEquals(2, requests())

        list.canLoad = false
        list.apply { page = 3; canLoad = false }                // the last page
        list.shown = listOf("a", "b", "c", "d", "e")
        assertEquals(2, requests())
    }

    @Test
    fun aLongPageWaitsUntilItsEndIsScrolledIntoView() = runTest {
        val list = FakeList(rows = listOf("a", "b", "c", "d", "e", "f"), shown = listOf("a", "b"))
        val requests = list.watch(this)
        assertEquals(0, requests())

        list.shown = listOf("c", "d")
        assertEquals(0, requests())
        list.shown = listOf("d", "e")
        assertEquals(1, requests())
        // Moving around inside the end does not ask again.
        list.shown = listOf("c", "d", "e")
        list.shown = listOf("f")
        assertEquals(1, requests())

        // The new page is applied while the old layout, which ends with the old last row, is still current.
        list.canLoad = false
        list.apply { page = 2; rows = rows + listOf("g", "h", "i", "j", "k", "l"); canLoad = true }
        assertEquals(1, requests())
        list.shown = listOf("f", "g", "h")
        assertEquals(1, requests())
        list.shown = listOf("j", "k")
        assertEquals(2, requests())
    }

    @Test
    fun aPageThatAddsNoRowsContinuesWithTheNextOne() = runTest {
        val list = FakeList(rows = listOf("a", "b", "c"), shown = listOf("b", "c"))
        val requests = list.watch(this)
        assertEquals(1, requests())

        list.canLoad = false
        list.apply { page = 2; canLoad = true }                 // same rows, same layout
        assertEquals(2, requests())
        list.canLoad = false
        list.apply { page = 3; canLoad = true }
        assertEquals(3, requests())
    }

    @Test
    fun aFailedPageAndAListThatIsNotDisplayedWaitUntilLoadingIsAllowedAgain() = runTest {
        val list = FakeList(rows = listOf("a", "b", "c"), shown = listOf("b", "c"))
        val requests = list.watch(this)
        assertEquals(1, requests())

        list.canLoad = false                                    // in flight, then failed: still not allowed
        list.shown = listOf("a", "b")
        list.shown = listOf("b", "c")
        assertEquals(1, requests())

        list.canLoad = true                                     // retried or refreshed, back on the same page
        assertEquals(2, requests())
    }

    @Test
    fun contentWithoutRowsContinuesWithoutALayout() = runTest {
        val list = FakeList(rows = emptyList(), shown = listOf("left over from an earlier layout"), canLoad = false)
        val requests = list.watch(this)
        assertEquals(0, requests())

        list.canLoad = true
        assertEquals(1, requests())
        list.canLoad = false
        list.apply { page = 2; canLoad = true }
        assertEquals(2, requests())
        list.canLoad = false
        list.apply { page = 3; rows = listOf("a", "b", "c"); canLoad = true }
        assertEquals(2, requests())                             // rows now, and the layout does not show them
    }

    /** What a screen feeds into the effect: the applied page, its rows, and the keys of the latest layout. */
    private class FakeList(rows: List<String>, shown: List<String>, canLoad: Boolean = true) {
        var page by mutableStateOf(1)
        var rows by mutableStateOf(rows)
        var shown by mutableStateOf(shown)
        var canLoad by mutableStateOf(canLoad)

        /** Starts the watcher; the returned function lets pending changes arrive and gives the requests so far. */
        fun watch(scope: TestScope): () -> Int {
            var requests = 0
            scope.backgroundScope.launch {
                requestPagesWhile(
                    pageToContinue = { pageToContinue(page, canLoad, rows.takeLast(2)) { keys -> keys.any { it in shown } } },
                    onLoadMore = { requests++ },
                )
            }
            return {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                requests
            }
        }
    }
}
