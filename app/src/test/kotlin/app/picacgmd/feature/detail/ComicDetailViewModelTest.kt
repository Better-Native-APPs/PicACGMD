package app.picacgmd.feature.detail

import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.ResponseGate
import app.picacgmd.testing.TestAppEnvironment
import app.picacgmd.testing.TestPicaServer
import app.picacgmd.testing.awaitRealTime
import app.picacgmd.testing.awaitValue
import app.picacgmd.testing.failureResponse
import app.picacgmd.testing.line
import app.picacgmd.testing.respondWith
import app.picacgmd.testing.successResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
internal class ComicDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pica = TestPicaServer()

    @AfterTest fun closeServer() = pica.close()

    @Test
    fun chapterFailureStillAllowsParallelRecommendationsAndCommentPreview() = runTest {
        val comicGate = ResponseGate(successResponse(COMIC))
        val chapterGate = ResponseGate(failureResponse())
        val recommendationGate = ResponseGate(successResponse(RECOMMENDATIONS))
        val commentGate = ResponseGate(successResponse(COMMENTS))
        pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/comics/comic" -> comicGate.awaitResponse()
                "/comics/comic/eps" -> chapterGate.awaitResponse()
                "/comics/comic/recommendation" -> recommendationGate.awaitResponse()
                "/comics/comic/comments" -> commentGate.awaitResponse()
                else -> error("Unexpected request ${request.line()}")
            }
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        val states = mutableListOf<DetailState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.toList(states) }
        try {
            testScheduler.runCurrent()
            assertEquals("GET /comics/comic", pica.takeRequest().line())
            assertEquals(DetailState(), viewModel.state.value)
            assertEquals(1, pica.requestCount)
            assertTrue(environment.historyDao.upserts.isEmpty())

            comicGate.open()
            viewModel.state.awaitValue { it.comic != null }
            val parallelRequests = List(3) { pica.takeRequest().line() }.toSet()
            assertEquals(setOf("GET /comics/comic/eps?page=1", "GET /comics/comic/recommendation",
                "GET /comics/comic/comments?page=1"), parallelRequests)
            assertFalse(viewModel.state.value.loading)
            assertNull(viewModel.state.value.eps)
            assertNull(viewModel.state.value.epsError)
            assertEquals(emptyList(), viewModel.state.value.recommendations)
            assertFalse(viewModel.state.value.commentsLoaded)
            assertEquals(1, environment.historyDao.upserts.size)

            chapterGate.open()
            viewModel.state.awaitValue { it.epsError != null }
            assertEquals(emptyList(), viewModel.state.value.recommendations)
            assertFalse(viewModel.state.value.commentsLoaded)
            recommendationGate.open()
            viewModel.state.awaitValue { it.recommendations.isNotEmpty() }
            assertFalse(viewModel.state.value.commentsLoaded)
            commentGate.open()
            val ready = viewModel.state.awaitValue { it.commentsLoaded }
            assertNull(ready.error)
            assertNull(ready.eps)
            assertEquals("failure", ready.epsError)
            assertEquals(listOf("recommend"), ready.recommendations.map { it.id })
            assertEquals(listOf("top", "second", "third"), ready.comments.map { it.id })
            assertEquals("Pinned", ready.comments.first().content)
            assertEquals(listOf(
                listOf(true, false, false, false, false),
                listOf(false, true, false, false, false),
                listOf(false, true, true, false, false),
                listOf(false, true, true, true, false),
                listOf(false, true, true, true, true),
            ), states.map { listOf(it.loading, it.comic != null, it.epsError != null,
                it.recommendations.isNotEmpty(), it.commentsLoaded) })
            assertEquals(4, pica.requestCount)
        } finally {
            comicGate.open()
            chapterGate.open()
            recommendationGate.open()
            commentGate.open()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun previewFailureKeepsSuccessfulChaptersAndRecommendations() = runTest {
        pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/comics/comic/comments" -> failureResponse()
                else -> standardResponse(request.url.encodedPath)
            }
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            val ready = viewModel.state.awaitValue { it.commentsLoaded }
            assertEquals(listOf("first", "last"), ready.eps?.map { it.id })
            assertNull(ready.epsError)
            assertNull(ready.error)
            assertEquals(listOf("recommend"), ready.recommendations.map { it.id })
            assertEquals(emptyList(), ready.comments)
            assertEquals(setOf("GET /comics/comic", "GET /comics/comic/eps?page=1",
                "GET /comics/comic/recommendation", "GET /comics/comic/comments?page=1"),
                List(4) { pica.takeRequest().line() }.toSet())
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun recommendationFailureKeepsChaptersAndCommentPreview() = runTest {
        pica.respondWith { request ->
            if (request.url.encodedPath == "/comics/comic/recommendation") failureResponse()
            else standardResponse(request.url.encodedPath)
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            val ready = viewModel.state.awaitValue { it.commentsLoaded }
            assertEquals(listOf("first", "last"), ready.eps?.map { it.id })
            assertEquals(emptyList(), ready.recommendations)
            assertEquals(listOf("top", "second", "third"), ready.comments.map { it.id })
            assertNull(ready.error)
            assertEquals(4, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun chapterRetryKeepsOldContentAndDoesNotReloadTheOtherSections() = runTest {
        var retry = false
        val retryGate = ResponseGate(failureResponse())
        pica.respondWith { request ->
            if (retry && request.url.encodedPath == "/comics/comic/eps") retryGate.awaitResponse()
            else standardResponse(request.url.encodedPath)
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            val ready = viewModel.state.awaitValue { it.commentsLoaded }
            repeat(4) { pica.takeRequest() }
            retry = true

            viewModel.retryEps()
            assertEquals("GET /comics/comic/eps?page=1", pica.takeRequest().line())
            assertEquals(ready, viewModel.state.value)
            retryGate.open()
            val failed = viewModel.state.awaitValue { it.epsError != null }
            assertEquals(ready.eps, failed.eps)
            assertEquals(ready.recommendations, failed.recommendations)
            assertEquals(ready.comments, failed.comments)
            assertFalse(failed.loading)
            assertNull(failed.error)
            assertEquals(5, pica.requestCount)
        } finally {
            retryGate.open()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun likeStaysOptimisticAndBusyUntilTheServerAnswer() = runTest {
        val answer = ResponseGate(successResponse("""{"action":"unlike"}"""))
        pica.respondWith { request ->
            if (request.url.encodedPath == "/comics/comic/like") answer.awaitResponse()
            else standardResponse(request.url.encodedPath)
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            viewModel.state.awaitValue { it.commentsLoaded }
            repeat(4) { pica.takeRequest() }

            viewModel.toggleLike()
            viewModel.toggleLike()
            val request = pica.takeRequest()
            assertEquals("POST /comics/comic/like", request.line())
            assertEquals(0, request.body?.size ?: 0)
            assertTrue(viewModel.state.value.liking)
            assertTrue(assertNotNull(viewModel.state.value.comic).isLiked)
            assertEquals(8, viewModel.state.value.comic?.likesCount)
            assertEquals(5, pica.requestCount)

            answer.open()
            val settled = viewModel.state.awaitValue { !it.liking }
            assertFalse(assertNotNull(settled.comic).isLiked)
            assertEquals(8, settled.comic.likesCount)
        } finally {
            answer.open()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun favouriteStaysOptimisticAndBusyUntilTheServerAnswer() = runTest {
        val answer = ResponseGate(successResponse("""{"action":"unfavourite"}"""))
        pica.respondWith { request ->
            if (request.url.encodedPath == "/comics/comic/favourite") answer.awaitResponse()
            else standardResponse(request.url.encodedPath)
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            viewModel.state.awaitValue { it.commentsLoaded }
            repeat(4) { pica.takeRequest() }

            viewModel.toggleFavourite()
            viewModel.toggleFavourite()
            val request = pica.takeRequest()
            assertEquals("POST /comics/comic/favourite", request.line())
            assertEquals(0, request.body?.size ?: 0)
            assertTrue(viewModel.state.value.favouriting)
            assertTrue(assertNotNull(viewModel.state.value.comic).isFavourite)
            assertEquals(5, pica.requestCount)

            answer.open()
            val settled = viewModel.state.awaitValue { !it.favouriting }
            assertFalse(assertNotNull(settled.comic).isFavourite)
        } finally {
            answer.open()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun liveDownloadMarksOnlyContainDoneChapterIds() = runTest {
        pica.respondWith { standardResponse(it.url.encodedPath) }
        val environment = TestAppEnvironment(pica, backgroundScope)
        environment.downloadDao.chapters.value = listOf(
            DownloadEp("done", "comic", 1, "Done", status = DownloadStatus.DONE, createdAt = 1L),
            DownloadEp("pending", "comic", 2, "Pending", status = DownloadStatus.QUEUED, createdAt = 1L),
        )
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        val library = mutableListOf<LibraryState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.library.toList(library) }
        try {
            testScheduler.runCurrent()
            viewModel.state.awaitValue { it.commentsLoaded }
            viewModel.library.awaitValue { it.downloadedEpIds == setOf("done") }
            environment.downloadDao.chapters.value = environment.downloadDao.chapters.value.map {
                if (it.epId == "pending") it.copy(status = DownloadStatus.FAILED, error = "offline") else it
            }
            testScheduler.runCurrent()
            assertEquals(listOf(emptySet(), setOf("done")), library.map { it.downloadedEpIds })
            environment.downloadDao.chapters.value = environment.downloadDao.chapters.value.map { it.copy(status = DownloadStatus.DONE) }
            viewModel.library.awaitValue { it.downloadedEpIds == setOf("done", "pending") }
            assertEquals(listOf(emptySet(), setOf("done"), setOf("done", "pending")), library.map { it.downloadedEpIds })
            assertEquals(4, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun cancellationDuringHistoryRecordingStopsTheRemainingLoadsWithoutAnError() = runTest {
        pica.respondWith { standardResponse(it.url.encodedPath) }
        val loadStarted = CompletableDeferred<Job>()
        val environment = TestAppEnvironment(pica, backgroundScope, beforeApi = { call ->
            if (call == 1) loadStarted.complete(assertNotNull(currentCoroutineContext()[Job]))
        })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        environment.historyDao.beforeUpsert = {
            started.complete(Unit)
            release.await()
            throw CancellationException("history cancelled")
        }
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            val load = awaitRealTime { loadStarted.await() }
            awaitRealTime { started.await() }
            release.complete(Unit)
            awaitRealTime { load.join() }

            assertTrue(load.isCancelled)
            assertNotNull(viewModel.state.value.comic)
            assertNull(viewModel.state.value.error)
            assertNull(viewModel.state.value.epsError)
            assertFalse(viewModel.state.value.commentsLoaded)
            assertTrue(environment.historyDao.upserts.isEmpty())
            assertEquals(1, pica.requestCount)
        } finally {
            release.complete(Unit)
            viewModel.viewModelScope.cancel()
        }
    }

    @Test fun cancelledChapterLoadLeavesNoBusinessError() = cancelledParallelLoad(2)
    @Test fun cancelledRecommendationLoadLeavesNoBusinessError() = cancelledParallelLoad(3)
    @Test fun cancelledPreviewLoadDoesNotMarkCommentsLoadedOrSetAnError() = cancelledParallelLoad(4)

    private fun cancelledParallelLoad(cancelledCall: Int) = runTest {
        pica.respondWith { standardResponse(it.url.encodedPath) }
        val loadStarted = CompletableDeferred<Job>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val environment = TestAppEnvironment(pica, backgroundScope, beforeApi = { call ->
            if (call == 1) loadStarted.complete(assertNotNull(currentCoroutineContext()[Job]))
            if (call == cancelledCall) {
                started.complete(Unit)
                release.await()
                throw CancellationException("parallel load cancelled")
            }
        })
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            val load = awaitRealTime { loadStarted.await() }
            awaitRealTime { started.await() }
            release.complete(Unit)
            awaitRealTime { load.join() }

            assertTrue(load.isCancelled)
            assertNotNull(viewModel.state.value.comic)
            assertNull(viewModel.state.value.error)
            assertNull(viewModel.state.value.epsError)
            assertFalse(viewModel.state.value.commentsLoaded)
            if (cancelledCall > 2) assertEquals(listOf("first", "last"), viewModel.state.value.eps?.map { it.id })
            if (cancelledCall > 3) assertEquals(listOf("recommend"), viewModel.state.value.recommendations.map { it.id })
        } finally {
            release.complete(Unit)
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun cancelledChapterRetryKeepsOldChaptersAndNoError() = runTest {
        pica.respondWith { standardResponse(it.url.encodedPath) }
        val environment = TestAppEnvironment(pica, backgroundScope, beforeApi = { call ->
            if (call == 5) throw CancellationException("retry cancelled")
        })
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        try {
            testScheduler.runCurrent()
            val ready = viewModel.state.awaitValue { it.commentsLoaded }
            viewModel.retryEps()
            testScheduler.runCurrent()
            assertEquals(ready, viewModel.state.value)
            assertEquals(4, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test fun cancelledLikeDoesNotRollbackOrEmitAnErrorMessage() = cancelledOptimisticAction(like = true)
    @Test fun cancelledFavouriteDoesNotRollbackOrEmitAnErrorMessage() = cancelledOptimisticAction(like = false)

    private fun cancelledOptimisticAction(like: Boolean) = runTest {
        pica.respondWith { standardResponse(it.url.encodedPath) }
        val environment = TestAppEnvironment(pica, backgroundScope, beforeApi = { call ->
            if (call == 5) throw CancellationException("action cancelled")
        })
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        try {
            testScheduler.runCurrent()
            viewModel.state.awaitValue { it.commentsLoaded }
            if (like) viewModel.toggleLike() else viewModel.toggleFavourite()
            testScheduler.runCurrent()

            val state = viewModel.state.value
            if (like) {
                assertTrue(state.liking)
                assertTrue(assertNotNull(state.comic).isLiked)
                assertEquals(8, state.comic.likesCount)
            } else {
                assertTrue(state.favouriting)
                assertTrue(assertNotNull(state.comic).isFavourite)
            }
            assertEquals(emptyList(), messages)
            assertNull(state.error)
            assertEquals(4, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun cancelledSubscriptionChangeEmitsNoMessageAndWritesNoSnapshot() = runTest {
        pica.respondWith { standardResponse(it.url.encodedPath) }
        val environment = TestAppEnvironment(pica, backgroundScope)
        environment.subscriptionDao.beforeUpsert = { throw CancellationException("subscription cancelled") }
        val viewModel = ComicDetailViewModel(environment.container, "comic", environment.downloads)
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        try {
            testScheduler.runCurrent()
            viewModel.state.awaitValue { it.commentsLoaded }
            viewModel.toggleSubscribe()
            testScheduler.runCurrent()

            assertEquals(emptyList(), messages)
            assertTrue(environment.subscriptionDao.upserts.isEmpty())
            assertEquals(4, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun standardResponse(path: String) = successResponse(when (path) {
        "/comics/comic" -> COMIC
        "/comics/comic/eps" -> CHAPTERS
        "/comics/comic/recommendation" -> RECOMMENDATIONS
        "/comics/comic/comments" -> COMMENTS
        else -> error("Unexpected request $path")
    })

    private companion object {
        const val COMIC = """{"comic":{"_id":"comic","title":"Comic","likesCount":7}}"""
        const val CHAPTERS = """{"eps":{"docs":[{"_id":"last","title":"Last","order":5},{"_id":"first","title":"First","order":1},{"_id":"first","title":"Duplicate","order":2}],"page":1,"pages":1}}"""
        const val RECOMMENDATIONS = """{"comics":[{"_id":"recommend","title":"Recommendation"}]}"""
        const val COMMENTS = """{"topComments":[{"_id":"top","content":"Pinned"}],"comments":{"docs":[{"_id":"top","content":"Duplicate"},{"_id":"second"},{"_id":"third"},{"_id":"fourth"}],"page":1,"pages":1}}"""
    }
}
