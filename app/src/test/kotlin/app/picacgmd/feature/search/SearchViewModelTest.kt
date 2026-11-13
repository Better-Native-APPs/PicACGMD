package app.picacgmd.feature.search

import androidx.lifecycle.viewModelScope
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
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.coroutines.cancellation.CancellationException

internal class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pica = TestPicaServer()

    @AfterTest fun closeServer() = pica.close()

    @Test
    fun bothSuggestionRequestsArePrefetchedEvenWhenOpenedWithAKeyword() = runTest {
        val hotGate = ResponseGate(successResponse("""{"keywords":["hot","next"]}"""))
        val categoryGate = ResponseGate(successResponse("""{"categories":[{"title":"Second"},{"title":"First"}]}"""))
        pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/keywords" -> hotGate.awaitResponse()
                "/categories" -> categoryGate.awaitResponse()
                "/comics/advanced-search" -> successResponse("""{"comics":{"docs":[],"page":1,"pages":1}}""")
                else -> error("Unexpected request ${request.line()}")
            }
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = SearchViewModel(environment.container, "  query  ")
        try {
            testScheduler.runCurrent()
            assertEquals(setOf("GET /keywords", "GET /categories", "POST /comics/advanced-search?page=1"),
                List(3) { pica.takeRequest().line() }.toSet())
            assertEquals("query", viewModel.keyword.value)
            assertNull(viewModel.hotKeywords.value)
            assertEquals(emptyList(), viewModel.categories.value)

            hotGate.open()
            categoryGate.open()
            assertEquals(listOf("hot", "next"), viewModel.hotKeywords.awaitValue { it != null })
            assertEquals(listOf("Second", "First"), viewModel.categories.awaitValue { it.isNotEmpty() })
            viewModel.clearKeyword()
            assertNull(viewModel.keyword.value)
            assertEquals(listOf("hot", "next"), viewModel.hotKeywords.value)
            assertEquals(3, pica.requestCount)
        } finally {
            hotGate.open()
            categoryGate.open()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun suggestionFailuresStillFallBackToEmptyLists() = runTest {
        val hotGate = ResponseGate(failureResponse())
        val categoryGate = ResponseGate(failureResponse())
        pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/keywords" -> hotGate.awaitResponse()
                "/categories" -> categoryGate.awaitResponse()
                else -> error("Unexpected request ${request.line()}")
            }
        }
        val categoryLoad = CompletableDeferred<Job>()
        val environment = TestAppEnvironment(pica, backgroundScope, beforeApi = { call ->
            if (call == 2) categoryLoad.complete(assertNotNull(currentCoroutineContext()[Job]))
        })
        val viewModel = SearchViewModel(environment.container, null)
        try {
            testScheduler.runCurrent()
            assertEquals(setOf("GET /keywords", "GET /categories"), List(2) { pica.takeRequest().line() }.toSet())
            assertNull(viewModel.hotKeywords.value)
            assertEquals(emptyList(), viewModel.categories.value)
            hotGate.open()
            categoryGate.open()
            assertEquals(emptyList(), viewModel.hotKeywords.awaitValue { it != null })
            val load = awaitRealTime { categoryLoad.await() }
            awaitRealTime { load.join() }
            assertFalse(load.isCancelled)
            assertEquals(emptyList(), viewModel.categories.value)
            assertEquals(2, pica.requestCount)
        } finally {
            hotGate.open()
            categoryGate.open()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun oneSuggestionFailureDoesNotDiscardTheOtherSuccessfulSuggestion() = runTest {
        pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/keywords" -> failureResponse()
                "/categories" -> successResponse("""{"categories":[{"title":"Category"}]}""")
                else -> error("Unexpected request ${request.line()}")
            }
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = SearchViewModel(environment.container, null)
        try {
            testScheduler.runCurrent()
            assertEquals(emptyList(), viewModel.hotKeywords.awaitValue { it != null })
            assertEquals(listOf("Category"), viewModel.categories.awaitValue { it.isNotEmpty() })
            assertEquals(2, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test fun cancelledHotKeywordsStayPendingInsteadOfShowingTheFailureFallback() = cancelledSuggestion(1)
    @Test fun cancelledCategoriesKeepTheInitialListAndCancelTheirLoad() = cancelledSuggestion(2)

    private fun cancelledSuggestion(cancelledCall: Int) = runTest {
        pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/keywords" -> successResponse("""{"keywords":["hot"]}""")
                "/categories" -> successResponse("""{"categories":[{"title":"Category"}]}""")
                else -> error("Unexpected request ${request.line()}")
            }
        }
        val started = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val environment = TestAppEnvironment(pica, backgroundScope, beforeApi = { call ->
            if (call == cancelledCall) {
                started.complete(assertNotNull(currentCoroutineContext()[Job]))
                release.await()
                throw CancellationException("suggestion cancelled")
            }
        })
        val viewModel = SearchViewModel(environment.container, null)
        try {
            testScheduler.runCurrent()
            val suggestion = awaitRealTime { started.await() }
            if (cancelledCall == 1) viewModel.categories.awaitValue { it.isNotEmpty() }
            else viewModel.hotKeywords.awaitValue { it != null }
            release.complete(Unit)
            awaitRealTime { suggestion.join() }

            assertTrue(suggestion.isCancelled)
            if (cancelledCall == 1) {
                assertNull(viewModel.hotKeywords.value)
                assertEquals(listOf("Category"), viewModel.categories.value)
            } else {
                assertEquals(listOf("hot"), viewModel.hotKeywords.value)
                assertEquals(emptyList(), viewModel.categories.value)
            }
            assertEquals(1, pica.requestCount)
        } finally {
            release.complete(Unit)
            viewModel.viewModelScope.cancel()
        }
    }
}
