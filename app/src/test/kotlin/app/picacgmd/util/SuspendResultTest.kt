package app.picacgmd.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SuspendResultTest {
    @Test
    fun returnsSuccessFromASuspendingBlock() = runBlocking {
        val result = suspendRunCatching {
            yield()
            42
        }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun wrapsAnOrdinaryExceptionAsFailure() = runBlocking {
        val failure = IllegalStateException("ordinary failure")

        val result = suspendRunCatching<Unit> { throw failure }

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun wrapsAnErrorSubclassAsFailure() = runBlocking {
        val failure = AssertionError("error failure")

        val result = suspendRunCatching<Unit> { throw failure }

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun rethrowsCancellationThrownByTheBlock() = runBlocking {
        val cancellation = CancellationException("block cancellation")

        val thrown = assertFailsWith<CancellationException> {
            suspendRunCatching<Unit> { throw cancellation }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun rethrowsCancellationWhenTheCallingCoroutineIsCancelled() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var ranAfterCall = false
        var caught: CancellationException? = null
        val job = launch {
            try {
                suspendRunCatching<Unit> {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                ranAfterCall = true
            } catch (cancellation: CancellationException) {
                caught = cancellation
                throw cancellation
            }
        }

        entered.await()
        job.cancel(CancellationException("calling coroutine cancellation"))
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull(caught)
        assertFalse(ranAfterCall)
    }
}
