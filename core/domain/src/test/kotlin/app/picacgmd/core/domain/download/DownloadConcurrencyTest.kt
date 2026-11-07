package app.picacgmd.core.domain.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadConcurrencyTest {
    @Test
    fun concurrencyNeverExceedsConfiguredLimit() = runTest {
        val limiter = AdaptiveDownloadLimiter(16, nowMillis = { testScheduler.currentTime })
        var active = 0
        var peak = 0
        List(64) {
            launch {
                limiter.withPermit {
                    active++
                    peak = maxOf(peak, active)
                    delay(10)
                    active--
                }
            }
        }.joinAll()
        assertEquals(16, peak)
        assertEquals(0, limiter.state.value.inFlight)
    }

    @Test
    fun liveIncreaseAdmitsWaitingTransfersWithoutRestart() = runTest {
        val limiter = AdaptiveDownloadLimiter(1, nowMillis = { testScheduler.currentTime })
        val release = CompletableDeferred<Unit>()
        var started = 0
        val workers = List(3) { launch { limiter.withPermit { started++; release.await() } } }
        runCurrent()
        assertEquals(1, started)
        limiter.setLimit(3)
        runCurrent()
        assertEquals(3, started)
        assertEquals(3, limiter.state.value.inFlight)
        release.complete(Unit)
        workers.joinAll()
    }

    @Test
    fun liveDecreaseStopsAdmissionsUntilExistingTransfersDrain() = runTest {
        val limiter = AdaptiveDownloadLimiter(4, nowMillis = { testScheduler.currentTime })
        val release = Channel<Unit>(Channel.UNLIMITED)
        var started = 0
        val workers = List(8) { launch { limiter.withPermit { started++; release.receive() } } }
        runCurrent()
        assertEquals(4, started)
        limiter.setLimit(2)
        release.send(Unit)
        release.send(Unit)
        runCurrent()
        assertEquals(4, started)
        assertEquals(2, limiter.state.value.inFlight)
        release.send(Unit)
        runCurrent()
        assertEquals(5, started)
        assertEquals(2, limiter.state.value.inFlight)
        repeat(5) { release.send(Unit) }
        workers.joinAll()
    }

    @Test
    fun http429And503BackOffExponentiallyAndRecoverGradually() = runTest {
        val limiter = AdaptiveDownloadLimiter(16, nowMillis = { testScheduler.currentTime }, jitter = { 0 })
        assertEquals(1_000L, limiter.onHttpFailure(429))
        assertEquals(8, limiter.state.value.effectiveLimit)
        assertEquals(2_000L, limiter.onHttpFailure(503))
        assertEquals(4, limiter.state.value.effectiveLimit)
        assertEquals(4_000L, limiter.onHttpFailure(429))
        assertEquals(2, limiter.state.value.effectiveLimit)
        advanceTimeBy(34_000)
        limiter.onSuccess()
        assertEquals(3, limiter.state.value.effectiveLimit)
        advanceTimeBy(13 * 30_000L)
        limiter.onSuccess()
        assertEquals(16, limiter.state.value.effectiveLimit)
        assertEquals(1_000L, limiter.onHttpFailure(503))
    }

    @Test
    fun retryAfterBlocksAllAdmissionsUntilTheDeadline() = runTest {
        val limiter = AdaptiveDownloadLimiter(4, nowMillis = { testScheduler.currentTime }, jitter = { 0 })
        limiter.onHttpFailure(429, retryAfterMillis = 5_000)
        var admitted = false
        val worker = launch { limiter.withPermit { admitted = true } }
        runCurrent()
        assertFalse(admitted)
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(admitted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(admitted)
        worker.join()
    }
}
