package app.picacgmd.feature.comics

import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.domain.repository.FilteredPage
import app.picacgmd.core.network.ComicSimple
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ComicPagerTest {
    @Test
    fun initialStateAndAutomaticLoadingDoNotFetchBeforeTheFirstPage() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)

        assertEquals(ComicPageState(), pager.state.value)
        assertEquals(PaginationMode.INFINITE, pager.mode)
        assertFalse(pager.state.value.hasNextPage)
        assertFalse(pager.state.value.isEmpty)

        pager.loadNext()
        runCurrent()

        assertEquals(emptyList(), source.requestedPages)
    }

    @Test
    fun reloadRequestsPageOneAndReplacesContentAfterKeepingItWhileLoading() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.jumpTo(3)
        runCurrent()
        source.respond(comicPage(3, comic("old"), pages = 5, hidden = 2, total = 50))
        runCurrent()
        val old = pager.state.value

        pager.reload()
        assertEquals(old.copy(loading = true), pager.state.value)
        runCurrent()
        assertEquals(listOf(3, 1), source.requestedPages)
        source.respond(comicPage(1, comic("new"), pages = 4, hidden = 1, total = 40))
        runCurrent()

        assertEquals(
            ComicPageState(
                entries = listOf(ComicEntry.Hidden(1, 1), ComicEntry.Comic(comic("new"), 1)),
                page = 1, pages = 4, total = 40, hiddenCount = 1, loading = false,
                revision = 2,
            ),
            pager.state.value,
        )
        assertEquals(listOf(comic("old")), old.comics)
    }

    @Test
    fun jumpUsesTheRequestedPageButEntriesAndMetadataUseTheServerPage() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)

        pager.jumpTo(4)
        runCurrent()
        source.respond(comicPage(5, comic("server"), pages = 7, total = 70))
        runCurrent()

        assertEquals(listOf(4), source.requestedPages)
        assertEquals(5, pager.state.value.page)
        assertEquals(listOf(ComicEntry.Comic(comic("server"), 5)), pager.state.value.entries)

        pager.jumpTo(0)
        runCurrent()
        source.respond(comicPage(1, comic("first"), pages = 2))
        runCurrent()

        assertEquals(listOf(4, 1), source.requestedPages)
        assertEquals(listOf(comic("first")), pager.state.value.comics)
    }

    @Test
    fun jumpCancelsAnInFlightReplacementAndItsResponseNeverPublishes() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.jumpTo(2)
        runCurrent()
        val cancelled = source.requests.single()

        pager.jumpTo(4)
        runCurrent()
        assertTrue(cancelled.cancelled)
        source.respond(comicPage(4, comic("current"), pages = 6))
        runCurrent()
        val current = pager.state.value
        cancelled.response.complete(comicPage(2, comic("stale"), pages = 6))
        runCurrent()

        assertEquals(listOf(2, 4), source.requestedPages)
        assertEquals(current, pager.state.value)
        assertEquals(listOf(comic("current")), pager.state.value.comics)
        assertNull(pager.state.value.error)
    }

    @Test
    fun jumpCancelsAnInFlightAppendAndResetsItsPresentationFlags() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 5))
        runCurrent()
        val old = pager.state.value
        pager.loadNext()
        runCurrent()
        val cancelled = source.requests.last()

        pager.jumpTo(4)
        assertEquals(old.copy(loading = true), pager.state.value)
        runCurrent()
        assertTrue(cancelled.cancelled)
        cancelled.response.complete(comicPage(2, comic("stale"), pages = 5))
        runCurrent()
        assertEquals(old.copy(loading = true), pager.state.value)
        source.respond(comicPage(4, comic("replacement"), pages = 5))
        runCurrent()

        assertEquals(listOf(1, 2, 4), source.requestedPages)
        assertEquals(listOf(comic("replacement")), pager.state.value.comics)
        assertFalse(pager.state.value.appending)
        assertNull(pager.state.value.appendError)
    }

    @Test
    fun reloadFailureKeepsAllPreviouslyLoadedContentAndMetadata() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.jumpTo(3)
        runCurrent()
        source.respond(comicPage(3, comic("old"), pages = 8, hidden = 2, total = 80))
        runCurrent()
        val old = pager.state.value

        pager.reload()
        runCurrent()
        assertEquals(old.copy(loading = true), pager.state.value)
        source.fail(IllegalStateException("refresh failed"))
        runCurrent()

        assertEquals(old.copy(error = "refresh failed", revision = 2), pager.state.value)
        assertEquals(listOf(3, 1), source.requestedPages)
        assertNull(pager.state.value.appendError)
    }

    @Test
    fun failedJumpKeepsTheCurrentPageAndUsesTheReadableExceptionFallback() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 8))
        runCurrent()
        val old = pager.state.value

        pager.jumpTo(7)
        runCurrent()
        source.fail(IllegalStateException(" "))
        runCurrent()

        assertEquals(old.copy(error = "IllegalStateException", revision = 2), pager.state.value)
        assertEquals(listOf(1, 7), source.requestedPages)
    }

    @Test
    fun replaceAndAppendLoadingAndErrorsHaveSeparateStateTraces() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        val states = mutableListOf<ComicPageState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pager.state.toList(states) }
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 3))
        runCurrent()
        val ready = pager.state.value

        pager.loadNext()
        runCurrent()
        source.fail(IllegalArgumentException("append failed"))
        runCurrent()
        pager.reload()
        runCurrent()
        source.fail(IllegalStateException("replace failed"))
        runCurrent()

        assertEquals(
            listOf(
                ComicPageState(),
                ready,
                ready.copy(appending = true),
                ready.copy(appendError = "append failed", revision = 2),
                ready.copy(loading = true, revision = 2),
                ready.copy(error = "replace failed", revision = 3),
            ),
            states,
        )
        assertEquals(listOf(1, 2, 1), source.requestedPages)
    }

    @Test
    fun automaticAppendIsBlockedWhileLoadingAppendingOrAtTheServerEnd() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        pager.loadNext()
        runCurrent()
        assertEquals(listOf(1), source.requestedPages)
        source.respond(comicPage(1, comic("first"), pages = 2))
        runCurrent()

        pager.loadNext()
        pager.loadNext()
        runCurrent()
        assertEquals(listOf(1, 2), source.requestedPages)
        assertTrue(pager.state.value.appending)
        source.respond(comicPage(2, comic("last"), pages = 2))
        runCurrent()
        pager.loadNext()
        runCurrent()

        assertFalse(pager.state.value.hasNextPage)
        assertEquals(listOf(1, 2), source.requestedPages)
    }

    @Test
    fun switchingModesDoesNotReloadCancelOrChangeContentAndOnlyInfiniteAutoAppends() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("first"), pages = 4))
        runCurrent()
        val first = pager.state.value

        pager.mode = PaginationMode.BUTTONS
        assertEquals(first.copy(revision = 2), pager.state.value)
        pager.loadNext()
        runCurrent()
        assertEquals(listOf(1), source.requestedPages)

        pager.mode = PaginationMode.INFINITE
        pager.loadNext()
        runCurrent()
        pager.mode = PaginationMode.BUTTONS
        assertTrue(pager.state.value.appending)
        assertFalse(source.requests.last().cancelled)
        source.respond(comicPage(2, comic("second"), pages = 4))
        runCurrent()
        val second = pager.state.value
        pager.loadNext()
        runCurrent()
        assertEquals(second, pager.state.value)
        assertEquals(listOf(1, 2), source.requestedPages)

        pager.mode = PaginationMode.INFINITE
        assertEquals(second.copy(revision = 6), pager.state.value)
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(3, comic("third"), pages = 4))
        runCurrent()

        assertEquals(listOf(1, 2, 3), source.requestedPages)
        assertEquals(listOf(comic("first"), comic("second"), comic("third")), pager.state.value.comics)
    }

    @Test
    fun appendFailureStopsAutomaticRequestsAndRetryUsesCurrentPagePlusOne() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 4, hidden = 2))
        runCurrent()
        val old = pager.state.value
        pager.loadNext()
        runCurrent()
        assertEquals(old.copy(appending = true), pager.state.value)
        source.fail(IllegalStateException("append failed"))
        runCurrent()
        assertEquals(old.copy(appendError = "append failed", revision = 2), pager.state.value)

        repeat(3) { pager.loadNext() }
        runCurrent()
        assertEquals(listOf(1, 2), source.requestedPages)
        pager.retryAppend()
        assertEquals(old.copy(appending = true, revision = 2), pager.state.value)
        runCurrent()
        assertEquals(listOf(1, 2, 2), source.requestedPages)
        source.respond(comicPage(2, comic("new"), pages = 4, hidden = 1))
        runCurrent()

        assertEquals(listOf(comic("old"), comic("new")), pager.state.value.comics)
        assertEquals(3, pager.state.value.hiddenCount)
        assertNull(pager.state.value.appendError)
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(3, pages = 4))
        runCurrent()
        assertEquals(listOf(1, 2, 2, 3), source.requestedPages)
    }

    @Test
    fun replacementKeepsDuplicateIdsIncludingIdsFromTheReplacedPage() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("same", "old"), comic("discarded"), pages = 3))
        runCurrent()
        val incoming = listOf(comic("same", "first"), comic("same", "second"))

        pager.jumpTo(2)
        runCurrent()
        source.respond(comicPage(2, *incoming.toTypedArray(), pages = 3))
        runCurrent()

        assertEquals(incoming, pager.state.value.comics)
        assertEquals(incoming.map { ComicEntry.Comic(it, 2) }, pager.state.value.entries)
        assertEquals(listOf("c-same-2", "c-same-2"), pager.state.value.entries.map { it.key })
    }

    @Test
    fun appendRejectsOnlyOldIdsAndKeepsDuplicatesWithinBothOldAndIncomingPages() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        val old = listOf(comic("a"), comic("b", "old first"), comic("b", "old second"))
        val added = listOf(comic("c", "new first"), comic("c", "new second"))
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, *old.toTypedArray(), pages = 4))
        runCurrent()

        pager.loadNext()
        runCurrent()
        source.respond(comicPage(2, comic("b", "ignored"), *added.toTypedArray(), comic("a", "ignored"), pages = 4))
        runCurrent()
        assertEquals(old + added, pager.state.value.comics)
        assertEquals(old.map { ComicEntry.Comic(it, 1) } + added.map { ComicEntry.Comic(it, 2) }, pager.state.value.entries)

        pager.loadNext()
        runCurrent()
        source.respond(comicPage(3, comic("c", "now old"), comic("d"), pages = 4))
        runCurrent()

        assertEquals(old + added + comic("d"), pager.state.value.comics)
        assertEquals(listOf(1, 2, 3), source.requestedPages)
    }

    @Test
    fun allHiddenPagesKeepTheirSlotsAndDoNotEndAutomaticPaging() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, pages = 3, hidden = 4))
        runCurrent()

        assertTrue(pager.state.value.isEmpty)
        assertTrue(pager.state.value.hasNextPage)
        assertEquals(listOf(ComicEntry.Hidden(1, 4)), pager.state.value.entries)
        assertEquals(emptyList(), pager.state.value.comics)
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(2, pages = 3, hidden = 3))
        runCurrent()
        assertTrue(pager.state.value.isEmpty)
        assertTrue(pager.state.value.hasNextPage)
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(3, comic("visible"), pages = 3))
        runCurrent()

        assertEquals(listOf(ComicEntry.Hidden(1, 4), ComicEntry.Hidden(2, 3), ComicEntry.Comic(comic("visible"), 3)), pager.state.value.entries)
        assertEquals(7, pager.state.value.hiddenCount)
        assertFalse(pager.state.value.isEmpty)
        assertFalse(pager.state.value.hasNextPage)
        assertEquals(listOf(1, 2, 3), source.requestedPages)
    }

    @Test
    fun emptyNonLastPagesAdvanceAndTheServerAloneDecidesTheEnd() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, pages = 3, total = 100))
        runCurrent()

        assertTrue(pager.state.value.isEmpty)
        assertTrue(pager.state.value.hasNextPage)
        assertEquals(emptyList(), pager.state.value.entries)
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(2, pages = 3, total = 100))
        runCurrent()
        assertEquals(2, pager.state.value.page)
        assertTrue(pager.state.value.hasNextPage)
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(3, comic("last"), pages = 3, total = 100))
        runCurrent()
        pager.loadNext()
        runCurrent()

        assertEquals(100, pager.state.value.total)
        assertFalse(pager.state.value.hasNextPage)
        assertEquals(listOf(1, 2, 3), source.requestedPages)
    }

    @Test
    fun anAppendWithOnlyAlreadyLoadedIdsStillAdvancesTheServerPage() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("same"), pages = 3))
        runCurrent()
        val entries = pager.state.value.entries

        pager.loadNext()
        runCurrent()
        source.respond(comicPage(2, comic("same", "ignored"), pages = 3))
        runCurrent()
        assertEquals(entries, pager.state.value.entries)
        assertEquals(2, pager.state.value.page)
        assertTrue(pager.state.value.hasNextPage)
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(3, comic("same", "ignored again"), pages = 3))
        runCurrent()
        pager.loadNext()
        runCurrent()

        assertEquals(entries, pager.state.value.entries)
        assertFalse(pager.state.value.hasNextPage)
        assertEquals(listOf(1, 2, 3), source.requestedPages)
    }

    @Test
    fun hiddenRowsKeepOrderKeysServerPageNumbersAndAdditiveCountsEvenForRepeatedPages() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.jumpTo(3)
        runCurrent()
        source.respond(comicPage(3, comic("a"), comic("b"), pages = 6, hidden = 2, total = 60))
        runCurrent()
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(4, comic("a", "ignored"), comic("c"), pages = 6, hidden = 3, total = 61))
        runCurrent()
        pager.loadNext()
        runCurrent()
        source.respond(comicPage(4, pages = 7, hidden = 1, total = 70))
        runCurrent()

        assertEquals(
            listOf(
                ComicEntry.Hidden(3, 2), ComicEntry.Comic(comic("a"), 3), ComicEntry.Comic(comic("b"), 3),
                ComicEntry.Hidden(4, 3), ComicEntry.Comic(comic("c"), 4), ComicEntry.Hidden(4, 1),
            ),
            pager.state.value.entries,
        )
        assertEquals(listOf("h-3", "c-a-3", "c-b-3", "h-4", "c-c-4", "h-4"), pager.state.value.entries.map { it.key })
        assertEquals(6, pager.state.value.hiddenCount)
        assertEquals(4, pager.state.value.page)
        assertEquals(7, pager.state.value.pages)
        assertEquals(70, pager.state.value.total)
        assertEquals(listOf(3, 4, 5), source.requestedPages)
    }

    @Test
    fun manualAppendRetryInButtonModeKeepsAnExistingReplaceError() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.mode = PaginationMode.BUTTONS
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 2))
        runCurrent()
        pager.jumpTo(7)
        runCurrent()
        source.fail(IllegalStateException("jump failed"))
        runCurrent()
        val failed = pager.state.value

        pager.retryAppend()
        assertEquals(failed.copy(appending = true), pager.state.value)
        runCurrent()
        source.respond(comicPage(2, comic("new"), pages = 2))
        runCurrent()

        assertEquals(listOf(1, 7, 2), source.requestedPages)
        assertEquals(listOf(comic("old"), comic("new")), pager.state.value.comics)
        assertEquals("jump failed", pager.state.value.error)
        assertNull(pager.state.value.appendError)
        assertFalse(pager.state.value.appending)
    }

    @Test
    fun cancellationIsNeverAnErrorAndDoesNotResetLoadingOrAppendingByItself() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.fail(CancellationException("replace cancelled"))
        runCurrent()

        assertEquals(ComicPageState(), pager.state.value)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 2))
        runCurrent()
        val old = pager.state.value
        pager.loadNext()
        runCurrent()
        source.fail(CancellationException("append cancelled"))
        runCurrent()

        assertEquals(old.copy(appending = true), pager.state.value)
        assertNull(pager.state.value.error)
        assertNull(pager.state.value.appendError)
        assertEquals(listOf(1, 1, 2), source.requestedPages)
    }

    @Test
    fun aCancelledAppendCanBeRequestedAgainAfterAFailedReload() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("a"), pages = 3))
        runCurrent()
        val first = pager.state.value
        assertEquals(1, first.revision)

        pager.loadNext(first.revision)
        runCurrent()
        val cancelled = source.requests.last()
        assertEquals(2, cancelled.page)
        assertEquals(first.copy(appending = true), pager.state.value)
        pager.reload()
        runCurrent()
        assertTrue(cancelled.cancelled)
        assertEquals(first.copy(loading = true), pager.state.value)
        source.fail(IllegalStateException("refresh failed"))
        runCurrent()
        val failed = first.copy(error = "refresh failed", revision = 2)
        assertEquals(failed, pager.state.value)
        assertTrue(failed.canLoadNext)
        assertEquals(listOf(1, 2, 1), source.requestedPages)

        pager.loadNext(failed.revision)
        runCurrent()
        assertEquals(listOf(1, 2, 1, 2), source.requestedPages)
        assertEquals(failed.copy(appending = true), pager.state.value)
        source.respond(comicPage(2, comic("b"), pages = 3))
        runCurrent()
        assertEquals(
            failed.copy(entries = first.entries + ComicEntry.Comic(comic("b"), 2), page = 2, revision = 3),
            pager.state.value,
        )
    }

    @Test
    fun anAppendWithTheSamePageNumberStopsScrollingUntilReload() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        val old = listOf(comic("a", "first old"), comic("a", "second old"))
        val added = listOf(comic("b", "first new"), comic("b", "second new"))
        val firstPage = comicPage(1, *old.toTypedArray(), pages = 3, hidden = 2)
        val repeatedPage = comicPage(1, comic("a", "ignored"), *added.toTypedArray(), pages = 3, hidden = 3)
        pager.reload()
        runCurrent()
        source.respond(firstPage)
        runCurrent()
        val first = pager.state.value
        assertEquals(1, first.revision)

        pager.loadNext(first.revision)
        runCurrent()
        source.respond(repeatedPage)
        runCurrent()
        val stalled = first.copy(
            entries = first.entries + ComicEntry.Hidden(1, 3) + added.map { ComicEntry.Comic(it, 1) },
            hiddenCount = 5,
            revision = 2,
        )
        assertEquals(stalled, pager.state.value)
        assertTrue(stalled.canLoadNext)
        pager.loadNext(stalled.revision)
        runCurrent()
        assertEquals(listOf(1, 2), source.requestedPages)
        assertEquals(stalled, pager.state.value)

        pager.reload()
        runCurrent()
        source.respond(firstPage)
        runCurrent()
        val reloaded = first.copy(revision = 3)
        assertEquals(reloaded, pager.state.value)
        pager.loadNext(reloaded.revision)
        runCurrent()
        assertEquals(listOf(1, 2, 1, 2), source.requestedPages)
        source.fail(IllegalStateException("append failed"))
        runCurrent()
        val failed = reloaded.copy(appendError = "append failed", revision = 4)
        assertEquals(failed, pager.state.value)
        assertFalse(failed.canLoadNext)
        pager.loadNext(failed.revision)
        runCurrent()
        assertEquals(listOf(1, 2, 1, 2), source.requestedPages)
        assertEquals(failed, pager.state.value)

        pager.retryAppend()
        runCurrent()
        assertEquals(listOf(1, 2, 1, 2, 2), source.requestedPages)
        source.respond(repeatedPage)
        runCurrent()
        val retried = stalled.copy(revision = 5)
        assertEquals(retried, pager.state.value)
        assertTrue(retried.canLoadNext)
        pager.loadNext(retried.revision)
        runCurrent()
        assertEquals(listOf(1, 2, 1, 2, 2), source.requestedPages)
        assertEquals(retried, pager.state.value)
    }

    @Test
    fun everyFinishedLoadChangesTheRevisionExactlyOnce() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        assertEquals(0, pager.state.value.revision)

        pager.reload()
        runCurrent()
        assertEquals(0, pager.state.value.revision)
        source.respond(comicPage(1, comic("a"), pages = 4))
        runCurrent()
        assertEquals(1, pager.state.value.revision)

        pager.loadNext(1)
        runCurrent()
        assertEquals(1, pager.state.value.revision)
        source.respond(comicPage(2, comic("b"), pages = 4))
        runCurrent()
        assertEquals(2, pager.state.value.revision)

        pager.reload()
        runCurrent()
        assertEquals(2, pager.state.value.revision)
        source.fail(IllegalStateException("refresh failed"))
        runCurrent()
        assertEquals(3, pager.state.value.revision)

        pager.loadNext(3)
        runCurrent()
        assertEquals(3, pager.state.value.revision)
        source.fail(IllegalStateException("append failed"))
        runCurrent()
        assertEquals(4, pager.state.value.revision)
        assertEquals(listOf(1, 2, 1, 3), source.requestedPages)
    }

    @Test
    fun aRefreshWithEqualRowsAndPageStillChangesTheState() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        val page = comicPage(1, comic("a"), pages = 3)
        pager.reload()
        runCurrent()
        source.respond(page)
        runCurrent()
        val first = pager.state.value
        assertEquals(1, first.revision)

        pager.reload()
        runCurrent()
        assertEquals(first.copy(loading = true), pager.state.value)
        source.respond(page.copy(items = page.items.toList()))
        runCurrent()

        assertNotEquals(first, pager.state.value)
        assertEquals(first.copy(revision = 2), pager.state.value)
        assertEquals(listOf(1, 1), source.requestedPages)
    }

    @Test
    fun requestsForAnEarlierRevisionDoNotLoadOrChangeState() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("a"), pages = 3))
        runCurrent()
        val earlierRevision = pager.state.value.revision
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("a"), pages = 3))
        runCurrent()
        val current = pager.state.value
        assertEquals(2, current.revision)
        assertTrue(current.canLoadNext)

        pager.loadNext(earlierRevision)
        runCurrent()
        assertEquals(listOf(1, 1), source.requestedPages)
        assertEquals(current, pager.state.value)

        pager.loadNext(current.revision)
        runCurrent()
        assertEquals(listOf(1, 1, 2), source.requestedPages)
        assertEquals(current.copy(appending = true), pager.state.value)
        source.respond(comicPage(2, comic("b"), pages = 3))
        runCurrent()
        assertEquals(3, pager.state.value.revision)
        assertEquals(listOf(comic("a"), comic("b")), pager.state.value.comics)
    }

    @Test
    fun onlyADifferentPagingModeChangesTheRevision() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        val initial = pager.state.value

        pager.mode = PaginationMode.INFINITE
        assertEquals(initial, pager.state.value)
        pager.mode = PaginationMode.BUTTONS
        assertEquals(initial.copy(revision = 1), pager.state.value)
        pager.mode = PaginationMode.BUTTONS
        assertEquals(initial.copy(revision = 1), pager.state.value)
        pager.mode = PaginationMode.INFINITE
        assertEquals(initial.copy(revision = 2), pager.state.value)
        runCurrent()
        assertEquals(emptyList(), source.requestedPages)
    }

    @Test
    fun stateEqualityHashCodeAndCopyRemainConsistentWithEntriesAndProjection() {
        val entries = listOf(ComicEntry.Hidden(2, 3), ComicEntry.Comic(comic("a"), 2), ComicEntry.Comic(comic("a", "duplicate"), 2))
        val state = ComicPageState(entries = entries, page = 2, pages = 4, hiddenCount = 3, loading = false)
        val equal = ComicPageState(entries = entries.toList(), page = 2, pages = 4, hiddenCount = 3, loading = false)

        assertEquals(listOf(comic("a"), comic("a", "duplicate")), state.comics)
        assertEquals(equal, state)
        assertEquals(equal.hashCode(), state.hashCode())
        assertEquals(state, state.copy())
        assertEquals(state.comics, state.copy(loading = true).comics)

        val changed = state.copy(entries = listOf(ComicEntry.Comic(comic("b"), 4)))
        assertEquals(listOf(comic("b")), changed.comics)
        assertEquals(listOf(comic("a"), comic("a", "duplicate")), state.comics)
        assertTrue(state != changed)
        assertEquals(emptyList(), state.copy(entries = emptyList()).comics)
    }

    @Test
    fun previouslyPublishedEntriesAndComicsDoNotChangeAfterAppend() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 2, hidden = 2))
        runCurrent()
        val old = pager.state.value
        val entries = old.entries
        val comics = old.comics

        pager.loadNext()
        runCurrent()
        source.respond(comicPage(2, comic("new"), pages = 2, hidden = 1))
        runCurrent()

        assertEquals(listOf(ComicEntry.Hidden(1, 2), ComicEntry.Comic(comic("old"), 1)), entries)
        assertEquals(listOf(comic("old")), comics)
        assertEquals(entries, old.entries)
        assertEquals(comics, old.comics)
        assertEquals(listOf(comic("old"), comic("new")), pager.state.value.comics)
    }

    @Test
    fun retryAppendStartsAnotherBusyRequestAndBothResponsesMergeInCompletionOrder() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 5, hidden = 2))
        runCurrent()
        pager.loadNext()
        runCurrent()
        val earlier = source.requests.last()

        pager.retryAppend()
        runCurrent()
        val later = source.requests.last()
        assertEquals(listOf(1, 2, 2), source.requestedPages)
        assertFalse(earlier.cancelled)
        assertFalse(later.cancelled)
        later.response.complete(comicPage(2, comic("c"), pages = 6, hidden = 3, total = 60))
        runCurrent()
        assertFalse(pager.state.value.appending)
        earlier.response.complete(comicPage(2, comic("c", "now old"), comic("b"), pages = 5, hidden = 1, total = 50))
        runCurrent()

        assertEquals(listOf(comic("old"), comic("c"), comic("b")), pager.state.value.comics)
        assertEquals(listOf("h-1", "c-old-1", "h-2", "c-c-2", "h-2", "c-b-2"), pager.state.value.entries.map { it.key })
        assertEquals(6, pager.state.value.hiddenCount)
        assertEquals(5, pager.state.value.pages)
        assertEquals(50, pager.state.value.total)
        assertFalse(earlier.cancelled)
    }

    @Test
    fun jumpCancelsOnlyTheLastStoredJobAndAnEarlierAppendCanPublishAfterReplacement() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.reload()
        runCurrent()
        source.respond(comicPage(1, comic("old"), pages = 5))
        runCurrent()
        pager.loadNext()
        runCurrent()
        val earlier = source.requests.last()
        pager.retryAppend()
        runCurrent()
        val latest = source.requests.last()

        pager.jumpTo(4)
        runCurrent()
        assertFalse(earlier.cancelled)
        assertTrue(latest.cancelled)
        source.respond(comicPage(4, comic("replacement"), pages = 8))
        runCurrent()
        earlier.response.complete(comicPage(2, comic("earlier"), pages = 5, hidden = 1))
        runCurrent()
        val completed = pager.state.value
        latest.response.complete(comicPage(2, comic("cancelled"), pages = 5))
        runCurrent()

        assertEquals(listOf(1, 2, 2, 4), source.requestedPages)
        assertEquals(completed, pager.state.value)
        assertEquals(
            listOf(ComicEntry.Comic(comic("replacement"), 4), ComicEntry.Hidden(2, 1), ComicEntry.Comic(comic("earlier"), 2)),
            pager.state.value.entries,
        )
        assertEquals(2, pager.state.value.page)
        assertEquals(5, pager.state.value.pages)
    }

    @Test
    fun retryAppendDuringReplacementIgnoresLoadingAndDoesNotCancelTheReplacement() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.jumpTo(3)
        runCurrent()
        source.respond(comicPage(3, comic("old"), pages = 8))
        runCurrent()
        val old = pager.state.value
        pager.jumpTo(6)
        runCurrent()
        val replacement = source.requests.last()

        pager.retryAppend()
        assertEquals(old.copy(loading = true, appending = true), pager.state.value)
        runCurrent()
        assertEquals(listOf(3, 6, 4), source.requestedPages)
        assertFalse(replacement.cancelled)
        replacement.response.complete(comicPage(6, comic("replacement"), pages = 8))
        runCurrent()
        assertFalse(pager.state.value.loading)
        assertFalse(pager.state.value.appending)
        source.respond(comicPage(4, comic("append"), pages = 8))
        runCurrent()

        assertEquals(listOf(ComicEntry.Comic(comic("replacement"), 6), ComicEntry.Comic(comic("append"), 4)), pager.state.value.entries)
        assertEquals(4, pager.state.value.page)
    }
}

internal class ComicPageSource {
    val requests = mutableListOf<ComicPageRequest>()
    val requestedPages: List<Int> get() = requests.map { it.page }

    suspend fun fetch(page: Int): FilteredPage<ComicSimple> {
        val request = ComicPageRequest(page)
        requests += request
        return try {
            request.response.await()
        } catch (cancelled: CancellationException) {
            request.cancelled = true
            throw cancelled
        }
    }

    fun respond(page: FilteredPage<ComicSimple>) {
        requests.last().response.complete(page)
    }

    fun fail(error: Throwable) {
        requests.last().response.completeExceptionally(error)
    }
}

internal class ComicPageRequest(val page: Int) {
    val response = CompletableDeferred<FilteredPage<ComicSimple>>()
    var cancelled = false
}

internal fun comic(id: String, title: String = id): ComicSimple = ComicSimple(id = id, title = title)

internal fun comicPage(
    page: Int,
    vararg comics: ComicSimple,
    pages: Int = 5,
    hidden: Int = 0,
    total: Int = 50,
): FilteredPage<ComicSimple> = FilteredPage(
    items = comics.toList(), total = total, page = page, pages = pages, limit = 20, hiddenCount = hidden,
)
