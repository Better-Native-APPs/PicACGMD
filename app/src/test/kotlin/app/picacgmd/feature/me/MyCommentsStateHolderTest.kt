package app.picacgmd.feature.me

import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.testing.TestPicaServer
import app.picacgmd.testing.line
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

internal class MyCommentsStateHolderTest {
    private val pica = TestPicaServer()

    @AfterTest
    fun tearDown() = pica.close()

    @Test
    fun firstLoadAppendAndRefreshKeepTheirPendingState() = runTest {
        val fixture = fixture()
        val holder = fixture.holder
        holder.assertState()
        assertEquals(0, pica.requestCount)

        pica.enqueue(pageJson(1, 3, "c1", "c2"), delayMillis = 150)
        val first = fixture.load(1)
        holder.assertState()
        expectPage(1)
        holder.assertState()
        first.join()
        holder.assertState(listOf("c1", "c2"), page = 1, pages = 3, loading = false)

        pica.enqueue(pageJson(2, 3, "c3"), delayMillis = 150)
        val append = fixture.load(holder.state.page + 1)
        holder.assertState(listOf("c1", "c2"), page = 1, pages = 3)
        expectPage(2)
        holder.assertState(listOf("c1", "c2"), page = 1, pages = 3)
        append.join()
        holder.assertState(listOf("c1", "c2", "c3"), page = 2, pages = 3, loading = false)

        pica.enqueue(pageJson(1, 2, "fresh"), delayMillis = 150)
        val refresh = fixture.refresh()
        holder.assertState(listOf("c1", "c2", "c3"), page = 2, pages = 3, refreshing = true)
        expectPage(1)
        holder.assertState(listOf("c1", "c2", "c3"), page = 2, pages = 3, refreshing = true)
        refresh.join()
        holder.assertState(listOf("fresh"), page = 1, pages = 2, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun firstLoadFailureRetriesPageOne() = runTest {
        val fixture = fixture()
        pica.enqueueFailure()
        val failed = fixture.load(1)
        fixture.holder.assertState()
        expectPage(1)
        failed.join()
        fixture.holder.assertState(loading = false, error = "failure")

        pica.enqueue(pageJson(1, 2, "retry"), delayMillis = 150)
        val retry = fixture.retryFirstLoad()
        fixture.holder.assertState()
        expectPage(1)
        retry.join()
        fixture.holder.assertState(listOf("retry"), page = 1, pages = 2, loading = false)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun appendFailureKeepsRowsAndTailRetryUsesCurrentPagePlusOne() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "c1"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()

        pica.enqueueFailure()
        val failed = fixture.load(2)
        fixture.holder.assertState(listOf("c1"), page = 1, pages = 3)
        expectPage(2)
        failed.join()
        fixture.holder.assertState(listOf("c1"), page = 1, pages = 3, loading = false, error = "failure")

        pica.enqueue(pageJson(2, 3, "c2"), delayMillis = 150)
        val retry = fixture.retryTail()
        fixture.holder.assertState(listOf("c1"), page = 1, pages = 3)
        expectPage(2)
        retry.join()
        fixture.holder.assertState(listOf("c1", "c2"), page = 2, pages = 3, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun refreshFailureWithRowsRetriesTheNextPageEvenAtTheServerEnd() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 2, "c1"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        pica.enqueue(pageJson(2, 2, "c2"))
        val append = fixture.load(2)
        expectPage(2)
        append.join()

        pica.enqueueFailure()
        val failed = fixture.refresh()
        fixture.holder.assertState(listOf("c1", "c2"), page = 2, pages = 2, refreshing = true)
        expectPage(1)
        failed.join()
        fixture.holder.assertState(listOf("c1", "c2"), page = 2, pages = 2, loading = false, error = "failure")

        pica.enqueue(pageJson(3, 3, "c3"), delayMillis = 150)
        val retry = fixture.retryTail()
        fixture.holder.assertState(listOf("c1", "c2"), page = 2, pages = 2)
        expectPage(3)
        retry.join()
        fixture.holder.assertState(listOf("c1", "c2", "c3"), page = 3, pages = 3, loading = false)
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
    fun duplicatesWithinPagesSurviveAppendAndRefresh() = runTest {
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
        fixture.holder.assertState(listOf("same", "same", "new", "new"), page = 2, pages = 3, loading = false)
        assertEquals(listOf("same", "same"), published.map { it.id })

        pica.enqueue(pageJson(1, 3, "fresh", "fresh"))
        val refresh = fixture.refresh()
        expectPage(1)
        refresh.join()
        fixture.holder.assertState(listOf("fresh", "fresh"), page = 1, pages = 3, loading = false)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun theSameIdOnTwoPagesIsTwoComments() = runTest {
        val fixture = fixture()
        pica.enqueue(pageJson(1, 3, "same"))
        val first = fixture.load(1)
        expectPage(1)
        first.join()
        pica.enqueue(pageJson(2, 3, "same"))
        val append = fixture.load(2)
        expectPage(2)
        append.join()
        fixture.holder.assertState(listOf("same", "same"), page = 2, pages = 3, loading = false)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun emptyShortAndRepeatedPagesOnlyUseServerEndMarkers() = runTest {
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
                when (page) {
                    1 -> emptyList()
                    2 -> listOf("same")
                    else -> listOf("same", "same")
                },
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
        val fixture = fixture()
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

        older.join()
        fixture.holder.assertState(listOf("older-response"), page = 1, pages = 2, loading = false)
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
        val fixture = fixture()
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
        assertEquals(2, pica.requestCount)
    }

    private fun TestScope.fixture(
        repository: CommentRepository = CommentRepository(pica.session(backgroundScope)),
    ): Fixture {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        return Fixture(scope, repository, MyCommentsStateHolder(scope, repository))
    }

    private class Fixture(
        val scope: CoroutineScope,
        val repository: CommentRepository,
        val holder: MyCommentsStateHolder,
    ) {
        fun load(page: Int): Job = start { holder.load(page) }
        fun refresh(): Job = start(holder::refresh)
        fun retryFirstLoad(): Job = start(holder::retryFirstLoad)
        fun retryTail(): Job = start(holder::retryTail)

        private fun start(action: () -> Unit): Job {
            action()
            return scope.coroutineContext.job.children.last()
        }
    }

    private suspend fun expectPage(page: Int) {
        val request = withContext(Dispatchers.IO) { pica.takeRequest() }
        assertEquals("GET /users/my-comments?page=$page", request.line())
        assertEquals("token", request.headers["authorization"])
    }

    private fun MyCommentsStateHolder.assertState(
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
        val docs = ids.joinToString(",") { """{"_id":"$it","content":"body-$it","_comic":{"_id":"comic-$it","title":"$it"}}""" }
        return """{"comments":{"docs":[$docs],"page":$page,"pages":$pages,"total":50,"limit":10}}"""
    }
}
