package app.picacgmd.core.domain.session

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class ApiRouteLatencyTest {
    @Test
    fun latencyFormattingAndGradeBoundaries() {
        assertEquals("0 ms", formatRouteLatency(0))
        assertEquals("123 ms", formatRouteLatency(123))
        assertEquals("失败", formatRouteLatency(null))
        assertEquals(RouteLatencyGrade.GOOD, routeLatencyGrade(200))
        assertEquals(RouteLatencyGrade.OK, routeLatencyGrade(201))
        assertEquals(RouteLatencyGrade.OK, routeLatencyGrade(500))
        assertEquals(RouteLatencyGrade.SLOW, routeLatencyGrade(501))
        assertEquals(RouteLatencyGrade.FAILED, routeLatencyGrade(null))
    }

    @Test
    fun independentRowsReturnBeforeSlowerRowsAndEachHasItsOwnDeadline() = runTest {
        val clock = { testScheduler.currentTime * 1_000_000 }
        val slow = async { measureRouteLatency(clock) { delay(6_000); true } }
        val fast = async { measureRouteLatency(clock) { delay(200); true } }
        assertEquals(200L, fast.await())
        assertFalse(slow.isCompleted)
        assertEquals(200L, testScheduler.currentTime)
        assertNull(slow.await())
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun failuresAndExceptionsMapToNullWithoutCancellingOtherRows() = runTest {
        val clock = { testScheduler.currentTime * 1_000_000 }
        val exception = async { measureRouteLatency(clock) { throw IOException("unreachable") } }
        val failed = async { measureRouteLatency(clock) { false } }
        val success = async { measureRouteLatency(clock) { delay(100); true } }
        assertNull(exception.await())
        assertNull(failed.await())
        assertEquals(100L, success.await())
    }

    @Test
    fun measuredSuccessOverFiveSecondsIsStillRejected() = runTest {
        var nanos = 0L
        assertNull(measureRouteLatency({ nanos }) { nanos += 5_000_000_001L; true })
    }

    @Test
    fun callerCancellationPropagatesRatherThanBecomingNull() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { measureRouteLatency { delay(1_000); true } }
        }
    }
}
