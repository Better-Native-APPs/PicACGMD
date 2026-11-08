package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.network.ApiRoute
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class ApiRouteLatencyTest {
    @Test
    fun builtInRouteSelectionAndSettingsMappingArePure() {
        assertEquals(listOf(ApiRoute.None, ApiRoute.Split1, ApiRoute.Split2, ApiRoute.Split3), builtInApiRoutes)
        val modes = listOf(RoutingMode.NONE, RoutingMode.ROUTE_1, RoutingMode.ROUTE_2, RoutingMode.ROUTE_3)
        assertEquals(modes, builtInApiRoutes.map(ApiRoute::toRoutingMode))
        assertEquals(builtInApiRoutes, modes.map { it.toApiRoute() })
        assertEquals(ApiRoute.Custom("203.0.113.7"), RoutingMode.CUSTOM.toApiRoute(" 203.0.113.7 "))
        assertEquals(RoutingMode.CUSTOM, ApiRoute.Custom("203.0.113.7").toRoutingMode())
    }

    @Test
    fun latencyFormattingDistinguishesSuccessAndFailure() {
        assertEquals("0 毫秒", formatApiRouteLatency(0))
        assertEquals("123 毫秒", formatApiRouteLatency(123))
        assertEquals("超时或不可用", formatApiRouteLatency(null))
    }

    @Test
    fun probesStartTogetherAndEachHasItsOwnFiveSecondDeadline() = runTest {
        val started = mutableListOf<ApiRoute>()
        val result = measureBuiltinApiRoutes(
            probe = { route ->
                started += route
                delay(when (route) {
                    ApiRoute.None -> 200
                    ApiRoute.Split1 -> 400
                    ApiRoute.Split2 -> 300
                    else -> 6_000
                })
                assertEquals(4, started.size)
                route != ApiRoute.Split2
            },
            nowNanos = { testScheduler.currentTime * 1_000_000 },
        )
        assertEquals(builtInApiRoutes, started)
        assertEquals(mapOf(ApiRoute.None to 200L, ApiRoute.Split1 to 400L, ApiRoute.Split2 to null, ApiRoute.Split3 to null), result)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun anExceptionDoesNotCancelTheOtherProbes() = runTest {
        val result = measureBuiltinApiRoutes(
            probe = { route ->
                if (route == ApiRoute.Split2) throw IOException("unreachable")
                delay(100)
                true
            },
            nowNanos = { testScheduler.currentTime * 1_000_000 },
        )
        assertEquals(100L, result[ApiRoute.None])
        assertEquals(100L, result[ApiRoute.Split1])
        assertNull(result[ApiRoute.Split2])
        assertEquals(100L, result[ApiRoute.Split3])
    }

    @Test
    fun measuredSuccessOverFiveSecondsIsStillRejected() = runTest {
        var nanos = 0L
        val result = measureBuiltinApiRoutes(
            probe = { nanos += 5_000_000_001L; true },
            nowNanos = { nanos },
        )
        assertEquals(builtInApiRoutes.associateWith { null }, result)
    }

    @Test
    fun callerCancellationPropagatesRatherThanBecomingNullLatencies() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) {
                measureBuiltinApiRoutes(probe = { delay(1_000); true })
            }
        }
    }
}
