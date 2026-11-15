package app.picacgmd.core.domain.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadWifiWatchTest {
    @Test
    fun normalCompletionCancelsTheWatchAndReportsCompletion() = runTest {
        val completed = runWithWifiWaitWatch(MutableStateFlow(true), GRACE_MILLIS) {
            delay(GRACE_MILLIS / 2)
        }

        assertTrue(completed)
        assertEquals(GRACE_MILLIS / 2, testScheduler.currentTime)
    }

    @Test
    fun shortWaitKeepsRunningAndTheNextWaitGetsANewGracePeriod() = runTest {
        val waiting = MutableStateFlow(false)
        var stopped = false
        val run = async {
            runWithWifiWaitWatch(waiting, GRACE_MILLIS) {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        runCurrent()
        waiting.value = true
        runCurrent()
        advanceTimeBy(GRACE_MILLIS - 1)
        runCurrent()
        assertFalse(run.isCompleted)
        waiting.value = false
        runCurrent()
        advanceTimeBy(GRACE_MILLIS * 2)
        runCurrent()
        assertFalse(run.isCompleted)
        assertFalse(stopped)

        waiting.value = true
        runCurrent()
        advanceTimeBy(GRACE_MILLIS - 1)
        runCurrent()
        assertFalse(run.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(run.await())
        assertTrue(stopped)
    }

    @Test
    fun continuousWaitCancelsTheBlockAtTheGraceDeadline() = runTest {
        var stopped = false
        val run = async {
            runWithWifiWaitWatch(MutableStateFlow(true), GRACE_MILLIS) {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        runCurrent()
        advanceTimeBy(GRACE_MILLIS - 1)
        runCurrent()
        assertFalse(run.isCompleted)
        assertFalse(stopped)
        advanceTimeBy(1)
        runCurrent()

        assertFalse(run.await())
        assertTrue(stopped)
        assertEquals(GRACE_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun outerCancellationPropagatesInsteadOfReportingAHandover() = runTest {
        var stopped = false
        val run = async {
            runWithWifiWaitWatch(MutableStateFlow(true), GRACE_MILLIS) {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        runCurrent()
        advanceTimeBy(GRACE_MILLIS - 1)
        run.cancel()

        assertFailsWith<CancellationException> { run.await() }
        assertTrue(stopped)
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
    }
}
