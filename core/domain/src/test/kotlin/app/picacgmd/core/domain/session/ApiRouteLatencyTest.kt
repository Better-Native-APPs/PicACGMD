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
        val slow = async { withRouteProbeDeadline { delay(6_000); 6_000L } }
        val fast = async { withRouteProbeDeadline { delay(200); 200L } }
        assertEquals(200L, fast.await())
        assertFalse(slow.isCompleted)
        assertEquals(200L, testScheduler.currentTime)
        assertNull(slow.await())
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun failuresAndExceptionsMapToNullWithoutCancellingOtherRows() = runTest {
        val exception = async { withRouteProbeDeadline { throw IOException("unreachable") } }
        val failed = async { withRouteProbeDeadline { null } }
        val success = async { withRouteProbeDeadline { delay(100); 100L } }
        assertNull(exception.await())
        assertNull(failed.await())
        assertEquals(100L, success.await())
    }

    @Test
    fun measuredSuccessOverFiveSecondsIsStillRejected() = runTest {
        assertNull(withRouteProbeDeadline { 5_001L })
        assertNull(withRouteProbeDeadline { -1L })
        assertEquals(5_000L, withRouteProbeDeadline { 5_000L })
    }

    @Test
    fun transportLatencyIsNotReplacedByCleanupOrDispatchTime() = runTest {
        assertEquals(850L, withRouteProbeDeadline { delay(1_000); 850L })
    }

    @Test
    fun callerCancellationPropagatesRatherThanBecomingNull() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { withRouteProbeDeadline { delay(1_000); 1_000L } }
        }
    }
}
