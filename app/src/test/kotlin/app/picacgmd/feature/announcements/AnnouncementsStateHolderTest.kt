package app.picacgmd.feature.announcements

import app.picacgmd.core.domain.repository.AnnouncementRepository
import app.picacgmd.testing.TestPicaServer
import app.picacgmd.testing.line
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

internal class AnnouncementsStateHolderTest {
    private val pica = TestPicaServer()

    @AfterTest
    fun tearDown() = pica.close()

    @Test
    fun firstLoadAppendAndRefreshKeepTheirPendingState() = runTest {
        val fixture = fixture()
        val holder = fixture.holder
        holder.assertState()
        assertEquals(0, pica.requestCount)

        pica.enqueue(pageJson(1, 3, "a1", "a2"), delayMillis = 150)
        val first = fixture.load(1)
        holder.assertState()
        expectPage(1)
        holder.assertState()
        first.join()
        holder.assertState(listOf("a1", "a2"), page = 1, pages = 3, loading = false)

        pica.enqueue(pageJson(2, 3, "a3"), delayMillis = 150)
        val append = fixture.load(holder.state.page + 1)
        holder.assertState(listOf("a1", "a2"), page = 1, pages = 3)
        expectPage(2)
        holder.assertState(listOf("a1", "a2"), page = 1, pages = 3)
        append.join()
        holder.assertState(listOf("a1", "a2", "a3"), page = 2, pages = 3, loading = false)

        pica.enqueue(pageJson(1, 2, "fresh"), delayMillis = 150)
        val refresh = fixture.refresh()
        holder.assertState(listOf("a1", "a2", "a3"), page = 2, pages = 3, refreshing = true)
        expectPage(1)
        holder.assertState(listOf("a1", "a2", "a3"), page = 2, pages = 3, refreshing = true)
        refresh.join()
        holder.assertState(listOf("fresh"), page = 1, pages = 2, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun firstLoadFailureRetriesPageOneWithoutWritingLastSeen() = runTest {
        val writes = mutableListOf<String>()
        val fixture = fixture { writes += it }
        pica.enqueueFailure()
        val failed = fixture.load(1)
        fixture.holder.assertState()
        expectPage(1)
        failed.join()
        fixture.holder.assertState(loading = false, error = "failure")
        assertEquals(emptyList(), writes)

        pica.enqueue(pageJson(1, 2, "retry"), delayMillis = 150)
        val retry = fixture.retryFirstLoad()
        fixture.holder.assertState()
        expectPage(1)
        retry.join()
        fixture.holder.assertState(listOf("retry"), page = 1, pages = 2, loading = false)
        assertEquals(listOf("retry"), writes)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun scrollingAsksForTheNextPageOnlyWhenIdleWithoutAnErrorAndBeforeTheLastPage() = runTest {
        val fixture = fixture()
        val holder = fixture.holder
        assertFalse(holder.state.canLoadNext)
        holder.loadNext()                                       // nothing applied yet
        assertEquals(0, pica.requestCount)

        pica.enqueue(pageJson(1, 3, "a1"), delayMillis = 150)
        val first = fixture.load(1)
        holder.loadNext()                                       // busy
        expectPage(1)
        first.join()
        assertTrue(holder.state.canLoadNext)

        pica.enqueueFailure()
        val failed = fixture.loadNext()
        assertFalse(holder.state.canLoadNext)
        expectPage(2)
        failed.join()
        holder.assertState(listOf("a1"), page = 1, pages = 3, loading = false, error = "failure")
        assertFalse(holder.state.canLoadNext)
        holder.loadNext()                                       // a failed page waits for the retry
        assertEquals(2, pica.requestCount)

        pica.enqueue(pageJson(2, 3, "a2"))
        val retry = fixture.retryTail()
        expectPage(2)
        retry.join()
        pica.enqueue(pageJson(3, 3, "a3"))
        val last = fixture.loadNext()
        expectPage(3)
        last.join()
        holder.assertState(listOf("a1", "a2", "a3"), page = 3, pages = 3, loading = false)
        assertFalse(holder.state.canLoadNext)
        holder.loadNext()                                       // the last page is applied
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun appendFailureKeepsRowsAndTailRetryUsesCurrentPagePlusOne() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "a1"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()

        pica.enqueueFailure()
        val failed = fixture.load(2)
        fixture.holder.assertState(listOf("a1"), page = 1, pages = 3)
        expectPage(2)
        failed.join()
        fixture.holder.assertState(listOf("a1"), page = 1, pages = 3, loading = false, error = "failure")

        pica.enqueue(pageJson(2, 3, "a2"), delayMillis = 150)
        val retry = fixture.retryTail()
        fixture.holder.assertState(listOf("a1"), page = 1, pages = 3)
        expectPage(2)
        retry.join()
        fixture.holder.assertState(listOf("a1", "a2"), page = 2, pages = 3, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun refreshFailureWithRowsRetriesTheNextPageEvenAtTheServerEnd() = runTest {
        val writes = mutableListOf<String>()
        val fixture = fixture { writes += it }
        pica.enqueue(pageJson(1, 2, "a1"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        pica.enqueue(pageJson(2, 2, "a2"))
        val append = fixture.load(2)
        expectPage(2)
        append.join()

        pica.enqueueFailure()
        val failed = fixture.refresh()
        fixture.holder.assertState(listOf("a1", "a2"), page = 2, pages = 2, refreshing = true)
        expectPage(1)
        failed.join()
        fixture.holder.assertState(listOf("a1", "a2"), page = 2, pages = 2, loading = false, error = "failure")
        assertEquals(listOf("a1"), writes)

        pica.enqueue(pageJson(3, 3, "a3"), delayMillis = 150)
        val retry = fixture.retryTail()
        fixture.holder.assertState(listOf("a1", "a2"), page = 2, pages = 2)
        expectPage(3)
        retry.join()
        fixture.holder.assertState(listOf("a1", "a2", "a3"), page = 3, pages = 3, loading = false)
        assertEquals(listOf("a1"), writes)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun refreshFailureWithoutRowsStillUsesTheFirstLoadRetry() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        fixture.holder.assertState(page = 1, pages = 3, loading = false)

        pica.enqueueFailure()
        val failed = fixture.refresh()
        fixture.holder.assertState(page = 1, pages = 3, refreshing = true)
        expectPage(1)
        failed.join()
        fixture.holder.assertState(page = 1, pages = 3, loading = false, error = "failure")

        pica.enqueue(pageJson(1, 3, "retry"))
        val retry = fixture.retryFirstLoad()
        fixture.holder.assertState(page = 1, pages = 3)
        expectPage(1)
        retry.join()
        fixture.holder.assertState(listOf("retry"), page = 1, pages = 3, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun pageOneDuplicatesSurviveUntilAppendAndReturnOnRefresh() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "same", "same"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        fixture.holder.assertState(listOf("same", "same"), page = 1, pages = 3, loading = false)
        val published = fixture.holder.state.items

        pica.enqueue(pageJson(2, 3, "new", "new"))
        val append = fixture.load(2)
        expectPage(2)
        append.join()
        fixture.holder.assertState(listOf("same", "new"), page = 2, pages = 3, loading = false)
        assertEquals(listOf("same", "same"), published.map { it.id })

        pica.enqueue(pageJson(1, 3, "same", "same"))
        val refresh = fixture.refresh()
        expectPage(1)
        refresh.join()
        fixture.holder.assertState(listOf("same", "same"), page = 1, pages = 3, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun theSameIdOnTwoPagesIsOnlyOneAnnouncement() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "same"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        pica.enqueue(pageJson(2, 3, "same"))
        val append = fixture.load(2)
        expectPage(2)
        append.join()
        fixture.holder.assertState(listOf("same"), page = 2, pages = 3, loading = false)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun emptyShortAndFullyDuplicatePagesOnlyUseServerEndMarkers() = runTest {
        val fixture = fixture()
        val responses = listOf(
            pageJson(1, 5),
            pageJson(2, 5, "same"),
            pageJson(3, 5, "same"),
            pageJson(4, 5),
            pageJson(5, 5),
        )
        responses.forEachIndexed { index, response ->
            pica.enqueue(response)
            val page = index + 1
            val load = fixture.load(page)
            expectPage(page)
            load.join()
            fixture.holder.assertState(
                if (page == 1) emptyList() else listOf("same"),
                page = page,
                pages = 5,
                loading = false,
            )
            assertEquals(page < 5, fixture.holder.state.page in 1 until fixture.holder.state.pages)
        }
        assertEquals(5, pica.requestCount)
    }

    @Test
    fun pageCountsAreCoercedAndReturnedPageDoesNotChooseMergeMode() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(4, -2, "old"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        fixture.holder.assertState(listOf("old"), page = 4, loading = false)

        pica.enqueue(pageJson(2, 0, "replacement"))
        val refresh = fixture.refresh()
        expectPage(1)
        refresh.join()
        fixture.holder.assertState(listOf("replacement"), page = 2, loading = false)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun overlappingRefreshesCommitInCompletionOrderAndDoNotStayBusy() = runTest {
        val writes = mutableListOf<String>()
        val fixture = fixture { writes += it }
        pica.enqueue(pageJson(1, 2, "older-response"), delayMillis = 1000)
        val older = fixture.refresh()
        expectPage(1)
        fixture.holder.assertState(refreshing = true)

        pica.enqueue(pageJson(1, 4, "newer-response"))
        val newer = fixture.refresh()
        fixture.holder.assertState(refreshing = true)
        expectPage(1)
        newer.join()
        assertTrue(older.isActive)
        fixture.holder.assertState(listOf("newer-response"), page = 1, pages = 4, loading = false)
        assertEquals(listOf("newer-response"), writes)

        older.join()
        fixture.holder.assertState(listOf("older-response"), page = 1, pages = 2, loading = false)
        assertEquals(listOf("newer-response", "older-response"), writes)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun delayedAppendMergesIntoTheStatePublishedByAnOverlappingRefresh() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "before"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()

        pica.enqueue(pageJson(2, 3, "tail"), delayMillis = 1000)
        val append = fixture.load(2)
        expectPage(2)
        fixture.holder.assertState(listOf("before"), page = 1, pages = 3)

        pica.enqueue(pageJson(1, 4, "fresh"))
        val refresh = fixture.refresh()
        fixture.holder.assertState(listOf("before"), page = 1, pages = 3, refreshing = true)
        expectPage(1)
        refresh.join()
        assertTrue(append.isActive)
        fixture.holder.assertState(listOf("fresh"), page = 1, pages = 4, loading = false)

        append.join()
        fixture.holder.assertState(listOf("fresh", "tail"), page = 2, pages = 3, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun lastSeenIsWrittenAfterPageOneCommitButNotAfterAppendOrEmptyPageOne() = runTest {
        val writes = mutableListOf<String>()
        val statesAtWrite = mutableListOf<AnnouncementsState>()
        val refreshingAtWrite = mutableListOf<Boolean>()
        lateinit var holder: AnnouncementsStateHolder
        val fixture = fixture {
            writes += it
            statesAtWrite += holder.state
            refreshingAtWrite += holder.refreshing
        }
        holder = fixture.holder

        pica.enqueue(pageJson(1, 3, "newest", "older"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        pica.enqueue(pageJson(2, 3, "tail"))
        val append = fixture.load(2)
        expectPage(2)
        append.join()
        pica.enqueue(pageJson(1, 3))
        val emptyRefresh = fixture.refresh()
        expectPage(1)
        emptyRefresh.join()
        holder.assertState(page = 1, pages = 3, loading = false)
        pica.enqueue(pageJson(1, 3, "next-newest"))
        val refresh = fixture.refresh()
        expectPage(1)
        refresh.join()

        assertEquals(listOf("newest", "next-newest"), writes)
        assertEquals(listOf(listOf("newest", "older"), listOf("next-newest")), statesAtWrite.map { state -> state.items.map { it.id } })
        assertTrue(statesAtWrite.all { !it.loading && it.error == null && it.page == 1 && it.pages == 3 })
        assertEquals(listOf(false, true), refreshingAtWrite)
        holder.assertState(listOf("next-newest"), page = 1, pages = 3, loading = false)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun theLastSeenWriteFinishesBeforeTheRefreshIndicatorIsCleared() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture {
            entered.complete(Unit)
            release.await()
        }
        pica.enqueue(pageJson(1, 2, "newest"))
        val refresh = fixture.refresh()
        expectPage(1)
        entered.await()
        assertTrue(refresh.isActive)
        fixture.holder.assertState(listOf("newest"), page = 1, pages = 2, loading = false, refreshing = true)

        release.complete(Unit)
        refresh.join()
        fixture.holder.assertState(listOf("newest"), page = 1, pages = 2, loading = false)
        assertEquals(1, pica.requestCount)
    }

    @Test
    fun lastSeenWriteFailureIsIgnoredWithoutChangingTheVisibleState() = runTest {
        val writes = mutableListOf<String>()
        val fixture = fixture {
            writes += it
            throw AssertionError("last-seen write failed")
        }
        pica.enqueue(pageJson(1, 2, "newest"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        assertFalse(first.isCancelled)
        fixture.holder.assertState(listOf("newest"), page = 1, pages = 2, loading = false)

        pica.enqueue(pageJson(1, 3, "refreshed"))
        val refresh = fixture.refresh()
        expectPage(1)
        refresh.join()
        assertFalse(refresh.isCancelled)
        fixture.holder.assertState(listOf("refreshed"), page = 1, pages = 3, loading = false)
        assertEquals(listOf("newest", "refreshed"), writes)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun lastSeenWriteCancellationIsRethrownWithoutBecomingALoadError() = runTest {
        val cancellation = CancellationException("last-seen write cancelled")
        val fixture = fixture { throw cancellation }
        pica.enqueue(pageJson(1, 2, "newest"))
        val refresh = fixture.refresh()
        var completion: Throwable? = null
        refresh.invokeOnCompletion { completion = it }
        expectPage(1)
        refresh.join()

        assertTrue(refresh.isCancelled)
        assertSame(cancellation, completion)
        fixture.holder.assertState(listOf("newest"), page = 1, pages = 2, loading = false)
        assertEquals(1, pica.requestCount)
    }

    @Test
    fun cancelledAppendCannotCommitAndANewHolderRequestsPageOne() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "old"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        pica.enqueue(pageJson(2, 3, "cancelled"), delayMillis = 300)
        val append = fixture.load(2)
        expectPage(2)
        val pending = fixture.holder.state
        fixture.holder.assertState(listOf("old"), page = 1, pages = 3)

        fixture.scope.cancel()
        append.join()
        assertTrue(append.isCancelled)
        assertEquals(pending, fixture.holder.state)

        val reentered = fixture(repository = fixture.repository)
        reentered.holder.assertState()
        pica.enqueue(pageJson(1, 2, "reentered"))
        val newFirst = reentered.load(1)
        expectPage(1)
        newFirst.join()
        reentered.holder.assertState(listOf("reentered"), page = 1, pages = 2, loading = false)
        withContext(Dispatchers.IO) { delay(350) }
        assertEquals(pending, fixture.holder.state)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun cancellingRefreshKeepsItsPendingPageStateAndClearsTheIndicator() = runTest {
        val writes = mutableListOf<String>()
        val fixture = fixture { writes += it }
        pica.enqueue(pageJson(1, 3, "old"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        pica.enqueue(pageJson(1, 2, "cancelled"), delayMillis = 300)
        val refresh = fixture.refresh()
        expectPage(1)
        fixture.holder.assertState(listOf("old"), page = 1, pages = 3, refreshing = true)
        val pending = fixture.holder.state

        fixture.scope.cancel()
        refresh.join()
        assertTrue(refresh.isCancelled)
        assertEquals(pending, fixture.holder.state)
        assertFalse(fixture.holder.refreshing)
        assertEquals(listOf("old"), writes)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun anAppendWithTheSamePageNumberStopsScrollingUntilRefresh() = runTest {
        val fixture = fixture()
        val holder = fixture.holder
        val firstPage = pageJson(1, 3, "a", "a")
        val repeatedPage = pageJson(1, 3, "a", "b", "b")
        pica.enqueue(firstPage)
        val firstLoad = fixture.load(1)
        expectPage(1)
        firstLoad.join()
        val first = holder.state
        assertEquals(1, first.revision)

        pica.enqueue(repeatedPage)
        val append = fixture.loadNext(first.revision)
        expectPage(2)
        append.join()
        holder.assertState(listOf("a", "b"), page = 1, pages = 3, loading = false)
        val stalled = holder.state
        assertEquals(2, stalled.revision)
        assertTrue(stalled.canLoadNext)
        holder.loadNext(stalled.revision)
        testScheduler.runCurrent()
        assertNull(withContext(Dispatchers.IO) { pica.pollRequest(50) })
        assertEquals(2, pica.requestCount)
        assertEquals(stalled, holder.state)

        pica.enqueue(firstPage)
        val refresh = fixture.refresh()
        expectPage(1)
        refresh.join()
        val refreshed = first.copy(revision = 3)
        assertEquals(refreshed, holder.state)
        pica.enqueueFailure()
        val failedAppend = fixture.loadNext(refreshed.revision)
        expectPage(2)
        failedAppend.join()
        val failed = refreshed.copy(error = "failure", revision = 4)
        assertEquals(failed, holder.state)
        assertFalse(failed.canLoadNext)
        holder.loadNext(failed.revision)
        testScheduler.runCurrent()
        assertNull(withContext(Dispatchers.IO) { pica.pollRequest(50) })
        assertEquals(4, pica.requestCount)
        assertEquals(failed, holder.state)

        pica.enqueue(repeatedPage)
        val retry = fixture.retryTail()
        expectPage(2)
        retry.join()
        val retried = stalled.copy(revision = 5)
        assertEquals(retried, holder.state)
        assertTrue(retried.canLoadNext)
        holder.loadNext(retried.revision)
        testScheduler.runCurrent()
        assertNull(withContext(Dispatchers.IO) { pica.pollRequest(50) })
        assertEquals(5, pica.requestCount)
        assertEquals(retried, holder.state)
    }

    @Test
    fun twoAnswersForTheSameNextPageLeaveTheFollowingPageRequestable() = runTest {
        for (lateAnswerFirst in listOf(false, true)) {
            val fixture = fixture()
            val holder = fixture.holder
            pica.enqueue(pageJson(1, 3, "a"))
            val first = fixture.load(1)
            expectPage(1)
            first.join()

            // A next page is on its way when a refresh comes back, and scrolling then asks for the next page again.
            pica.enqueue(pageJson(2, 3, "b"), delayMillis = if (lateAnswerFirst) 600 else 1500)
            val pending = fixture.loadNext()
            expectPage(2)
            pica.enqueue(pageJson(1, 3, "a"))
            val refresh = fixture.refresh()
            expectPage(1)
            refresh.join()
            assertTrue(pending.isActive)
            pica.enqueue(pageJson(2, 3, "b"), delayMillis = if (lateAnswerFirst) 1500 else 0)
            val again = fixture.loadNext()
            expectPage(2)
            pending.join()
            again.join()
            holder.assertState(listOf("a", "b"), page = 2, pages = 3, loading = false)
            assertTrue(holder.state.canLoadNext)

            pica.enqueue(pageJson(3, 3, "c"))
            val last = fixture.loadNext()
            expectPage(3)
            last.join()
            holder.assertState(listOf("a", "b", "c"), page = 3, pages = 3, loading = false)
            fixture.scope.cancel()
        }
        assertEquals(10, pica.requestCount)
    }

    @Test
    fun everyFinishedLoadChangesTheRevisionExactlyOnce() = runTest {
        val fixture = fixture()
        val holder = fixture.holder
        assertEquals(0, holder.state.revision)

        pica.enqueue(pageJson(1, 4, "a"))
        val first = fixture.load(1)
        assertEquals(0, holder.state.revision)
        expectPage(1)
        first.join()
        assertEquals(1, holder.state.revision)

        pica.enqueue(pageJson(2, 4, "b"))
        val append = fixture.loadNext(1)
        assertEquals(1, holder.state.revision)
        expectPage(2)
        append.join()
        assertEquals(2, holder.state.revision)

        pica.enqueueFailure()
        val refresh = fixture.refresh()
        assertEquals(2, holder.state.revision)
        expectPage(1)
        refresh.join()
        assertEquals(3, holder.state.revision)

        pica.enqueueFailure()
        val failedAppend = fixture.retryTail()
        assertEquals(3, holder.state.revision)
        expectPage(3)
        failedAppend.join()
        assertEquals(4, holder.state.revision)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun aRefreshWithEqualRowsAndPageStillChangesTheState() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "a"))
        val firstLoad = fixture.load(1)
        expectPage(1)
        firstLoad.join()
        val first = fixture.holder.state
        assertEquals(1, first.revision)

        pica.enqueue(pageJson(1, 3, "a"))
        val refresh = fixture.refresh()
        assertEquals(first.copy(loading = true), fixture.holder.state)
        expectPage(1)
        refresh.join()

        assertNotEquals(first, fixture.holder.state)
        assertEquals(first.copy(revision = 2), fixture.holder.state)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun requestsForAnEarlierRevisionDoNotLoadOrChangeState() = runTest {
        val fixture = fixture()
        val holder = fixture.holder
        pica.enqueue(pageJson(1, 3, "a"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        val earlierRevision = holder.state.revision
        pica.enqueue(pageJson(1, 3, "a"))
        val refresh = fixture.refresh()
        expectPage(1)
        refresh.join()
        val current = holder.state
        assertEquals(2, current.revision)
        assertTrue(current.canLoadNext)

        holder.loadNext(earlierRevision)
        testScheduler.runCurrent()
        assertNull(withContext(Dispatchers.IO) { pica.pollRequest(50) })
        assertEquals(2, pica.requestCount)
        assertEquals(current, holder.state)

        pica.enqueue(pageJson(2, 3, "b"))
        val append = fixture.loadNext(current.revision)
        assertEquals(current.copy(loading = true), holder.state)
        expectPage(2)
        append.join()
        assertEquals(3, holder.state.revision)
        assertEquals(listOf("a", "b"), holder.state.items.map { it.id })
        assertEquals(3, pica.requestCount)
    }

    private fun TestScope.fixture(
        repository: AnnouncementRepository = AnnouncementRepository(pica.session(backgroundScope)),
        writer: suspend (String) -> Unit = {},
    ): Fixture {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        return Fixture(scope, repository, AnnouncementsStateHolder(scope, repository, writer))
    }

    private class Fixture(
        val scope: CoroutineScope,
        val repository: AnnouncementRepository,
        val holder: AnnouncementsStateHolder,
    ) {
        fun load(page: Int): Job = start { holder.load(page) }
        fun refresh(): Job = start(holder::refresh)
        fun retryFirstLoad(): Job = start(holder::retryFirstLoad)
        fun retryTail(): Job = start(holder::retryTail)
        fun loadNext(revision: Int = holder.state.revision): Job = start { holder.loadNext(revision) }

        private fun start(action: () -> Unit): Job {
            action()
            return scope.coroutineContext.job.children.last()
        }
    }

    private suspend fun expectPage(page: Int) {
        val request = withContext(Dispatchers.IO) { pica.takeRequest() }
        assertEquals("GET /announcements?page=$page", request.line())
        assertEquals("token", request.headers["authorization"])
    }

    private fun AnnouncementsStateHolder.assertState(
        ids: List<String> = emptyList(),
        page: Int = 0,
        pages: Int = 1,
        loading: Boolean = true,
        error: String? = null,
        refreshing: Boolean = false,
    ) {
        assertEquals(ids, state.items.map { it.id })
        assertEquals(page, state.page)
        assertEquals(pages, state.pages)
        assertEquals(loading, state.loading)
        assertEquals(error, state.error)
        assertEquals(refreshing, this.refreshing)
    }

    private fun pageJson(page: Int, pages: Int, vararg ids: String): String {
        val docs = ids.joinToString(",") { """{"_id":"$it","title":"$it","content":"body-$it"}""" }
        return """{"announcements":{"docs":[$docs],"page":$page,"pages":$pages,"total":50,"limit":10}}"""
    }
}
