package app.picacgmd.feature.bookshelf

import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.TestAppEnvironment
import app.picacgmd.testing.TestPicaServer
import app.picacgmd.testing.awaitRealTime
import app.picacgmd.testing.awaitValue
import app.picacgmd.testing.line
import app.picacgmd.testing.respondWith
import app.picacgmd.testing.successResponse
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.coroutines.cancellation.CancellationException

internal class BookshelfViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pica = TestPicaServer()

    @AfterTest fun closeServer() = pica.close()

    @Test
    fun historyUndoPreservesSnapshotFieldsAndAdvancesPastTheDeletionInGivenOrder() = runTest {
        val environment = TestAppEnvironment(pica, backgroundScope, AppSettings(historyEnabled = false))
        val first = ViewHistory("b", "Original", "Author", "server", "cover", "[\"Category\"]",
            lastEpOrder = 8, lastEpTitle = "Chapter eight", lastPictureIndex = 12, lastChapterPages = 30,
            pinned = true, createdAt = 123L, updatedAt = 456L)
        val items = listOf(first, first.copy(comicId = "a", pinned = false, createdAt = 111L),
            first.copy(lastPictureIndex = 13, updatedAt = 457L))
        environment.historyDao.entries.value = items.associateBy { it.comicId }
        environment.history.delete(listOf("b", "a"))
        val viewModel = BookshelfViewModel(environment.container)
        try {
            viewModel.restoreHistory(items)
            val expected = listOf(items[0].copy(modifiedAt = 9_001L), items[1].copy(modifiedAt = 9_001L), items[2].copy(modifiedAt = 9_002L))
            assertEquals(expected, environment.historyDao.upserts.toList())
            assertEquals(expected.associateBy { it.comicId }, environment.historyDao.entries.value)
            assertTrue(environment.historyDao.tombstones.value.isEmpty())
            assertEquals(emptyList(), viewModel.messages.value)
            assertEquals(0, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun subscriptionUndoPreservesUnreadCountsAndEveryTimestampInGivenOrder() = runTest {
        val environment = TestAppEnvironment(pica, backgroundScope)
        val first = Subscription("b", "Original", "Author", "server", "cover", lastKnownEpsCount = 18,
            lastKnownUpdatedAt = 99L, unseen = true, newEpCount = 4, lastCheckedAt = 567L,
            createdAt = 123L, updatedAt = 456L)
        val items = listOf(first, first.copy(comicId = "a", unseen = false, lastKnownUpdatedAt = null,
            lastCheckedAt = null, createdAt = 111L), first.copy(newEpCount = 5, updatedAt = 457L))
        environment.subscriptionDao.entries.value = items.associateBy { it.comicId }
        environment.subscriptions.unsubscribe(listOf("b", "a"))
        val viewModel = BookshelfViewModel(environment.container)
        try {
            viewModel.restoreSubscriptions(items)
            assertEquals(items, environment.subscriptionDao.upserts.toList())
            assertEquals(items.associateBy { it.comicId }, environment.subscriptionDao.entries.value)
            assertEquals(emptyList(), viewModel.messages.value)
            assertEquals(0, pica.requestCount)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun removalIsSequentialAndAnOrdinaryDownloadDeletionFailureStaysBestEffort() = runTest {
        val firstToggles = AtomicInteger()
        pica.respondWith { request ->
            successResponse(when (request.url.encodedPath) {
                "/comics/a/favourite" -> if (firstToggles.incrementAndGet() == 1) """{"action":"favourite"}""" else """{"action":"unfavourite"}"""
                "/comics/b/favourite" -> """{"action":"unfavourite"}"""
                "/users/favourite" -> """{"comics":{"docs":[],"page":1,"pages":1}}"""
                else -> error("Unexpected request ${request.line()}")
            })
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = BookshelfViewModel(environment.container)
        val deletionStarted = CompletableDeferred<Unit>()
        val deletionRelease = CompletableDeferred<Unit>()
        val deletions = CopyOnWriteArrayList<String>()
        try {
            viewModel.unfavourite(listOf("a", "b"), deleteDownloads = true) { id ->
                deletions += id
                if (id == "a") {
                    deletionStarted.complete(Unit)
                    deletionRelease.await()
                    throw IOException("download deletion failed")
                }
            }
            testScheduler.runCurrent()
            awaitRealTime { deletionStarted.await() }
            assertEquals(listOf("POST /comics/a/favourite", "POST /comics/a/favourite"),
                List(2) { pica.takeRequest().line() })
            assertEquals(BatchProgress("正在取消收藏", 0, 2), viewModel.batch.value)
            assertEquals(emptyList(), viewModel.messages.value)
            assertEquals(listOf("a"), deletions.toList())
            assertEquals(2, pica.requestCount)

            deletionRelease.complete(Unit)
            assertEquals(listOf("已取消收藏 2 本"), viewModel.messages.awaitValue { it.isNotEmpty() })
            assertEquals(listOf("POST /comics/b/favourite", "GET /users/favourite?s=dd&page=1"),
                List(2) { pica.takeRequest().line() })
            viewModel.onlinePager.state.awaitValue { !it.loading && it.page == 1 }
            assertEquals(listOf("a", "b"), deletions.toList())
            assertNull(viewModel.batch.value)
            assertEquals(4, pica.requestCount)
        } finally {
            deletionRelease.complete(Unit)
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun cancelledDownloadDeletionStopsTheBatchWithoutASuccessMessageOrReload() = runTest {
        pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/comics/a/favourite", "/comics/b/favourite" -> successResponse("""{"action":"unfavourite"}""")
                "/users/favourite" -> successResponse("""{"comics":{"docs":[],"page":1,"pages":1}}""")
                else -> error("Unexpected request ${request.line()}")
            }
        }
        val environment = TestAppEnvironment(pica, backgroundScope)
        val viewModel = BookshelfViewModel(environment.container)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deletions = CopyOnWriteArrayList<String>()
        try {
            viewModel.unfavourite(listOf("a", "b"), deleteDownloads = true) { id ->
                deletions += id
                started.complete(Unit)
                release.await()
                throw CancellationException("download deletion cancelled")
            }
            testScheduler.runCurrent()
            awaitRealTime { started.await() }
            val batch = assertNotNull(viewModel.viewModelScope.coroutineContext[Job]).children.last()
            release.complete(Unit)
            awaitRealTime { batch.join() }

            assertTrue(batch.isCancelled)
            assertEquals(listOf("a"), deletions.toList())
            assertEquals(emptyList(), viewModel.messages.value)
            assertEquals(BatchProgress("正在取消收藏", 0, 2), viewModel.batch.value)
            assertEquals("POST /comics/a/favourite", pica.takeRequest().line())
            assertEquals(1, pica.requestCount)
        } finally {
            release.complete(Unit)
            viewModel.viewModelScope.cancel()
        }
    }
}
