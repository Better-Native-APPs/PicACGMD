package app.picacgmd.feature.comics

import app.picacgmd.core.domain.repository.FilteredPage
import app.picacgmd.core.network.ComicSimple
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ComicPagerCancellationTest {
    @Test
    fun cancelledReplacementReturningNormallyCannotPublish() = runTest {
        verifyCancelledRequest(append = false, fail = false)
    }

    @Test
    fun cancelledReplacementThrowingAnOrdinaryExceptionCannotPublishAnError() = runTest {
        verifyCancelledRequest(append = false, fail = true)
    }

    @Test
    fun cancelledAppendReturningNormallyCannotPublish() = runTest {
        verifyCancelledRequest(append = true, fail = false)
    }

    @Test
    fun cancelledAppendThrowingAnOrdinaryExceptionCannotPublishAnError() = runTest {
        verifyCancelledRequest(append = true, fail = true)
    }

    private fun TestScope.verifyCancelledRequest(append: Boolean, fail: Boolean) {
        val source = CancellationIgnoringSource()
        val pager = ComicPager(this, source::fetch)
        if (append) {
            pager.reload()
            runCurrent()
            source.requests.last().response.complete(comicPage(1, comic("old"), pages = 5))
            runCurrent()
            pager.loadNext()
        } else {
            pager.jumpTo(2)
        }
        runCurrent()
        val cancelled = source.requests.last()
        pager.jumpTo(4)
        runCurrent()
        assertTrue(cancelled.job.isCancelled)
        source.requests.last().response.complete(comicPage(4, comic("current"), pages = 5))
        runCurrent()
        val current = pager.state.value

        if (fail) {
            cancelled.response.completeExceptionally(IllegalStateException("cancelled failure"))
        } else {
            cancelled.response.complete(comicPage(2, comic("stale"), pages = 5))
        }
        runCurrent()

        assertEquals(if (append) listOf(1, 2, 4) else listOf(2, 4), source.requests.map { it.page })
        assertEquals(current, pager.state.value)
        assertEquals(listOf(comic("current")), pager.state.value.comics)
        assertNull(pager.state.value.error)
        assertNull(pager.state.value.appendError)
    }
}

private class CancellationIgnoringSource {
    val requests = mutableListOf<CancellationIgnoringRequest>()

    suspend fun fetch(page: Int): FilteredPage<ComicSimple> {
        val request = CancellationIgnoringRequest(page, currentCoroutineContext().job)
        requests += request
        return withContext(NonCancellable) { request.response.await() }
    }
}

private class CancellationIgnoringRequest(val page: Int, val job: Job) {
    val response = CompletableDeferred<FilteredPage<ComicSimple>>()
}
