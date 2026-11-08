package app.picacgmd.core.domain.session

import app.picacgmd.core.network.ROUTE_PROBE_TIMEOUT_MILLIS
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class RouteLatencyGrade { GOOD, OK, SLOW, FAILED }

fun routeLatencyGrade(latencyMillis: Long?): RouteLatencyGrade = when {
    latencyMillis == null -> RouteLatencyGrade.FAILED
    latencyMillis <= 200 -> RouteLatencyGrade.GOOD
    latencyMillis <= 500 -> RouteLatencyGrade.OK
    else -> RouteLatencyGrade.SLOW
}

fun formatRouteLatency(latencyMillis: Long?): String = latencyMillis?.let { "$it ms" } ?: "失败"

/** One row, one deadline. Caller cancellation is never converted into a failed probe. */
internal suspend fun measureRouteLatency(
    nowNanos: () -> Long = System::nanoTime,
    probe: suspend () -> Boolean,
): Long? {
    return withRouteProbeDeadline {
        val startedAt = nowNanos()
        if (probe()) {
            val elapsed = nowNanos() - startedAt
            elapsed.takeIf { it <= TimeUnit.MILLISECONDS.toNanos(ROUTE_PROBE_TIMEOUT_MILLIS) }
                ?.let(TimeUnit.NANOSECONDS::toMillis)
        } else {
            null
        }
    }
}

internal suspend fun withRouteProbeDeadline(probe: suspend () -> Long?): Long? =
    try {
        withTimeoutOrNull(ROUTE_PROBE_TIMEOUT_MILLIS) { probe() }
            ?.takeIf { it in 0..ROUTE_PROBE_TIMEOUT_MILLIS }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }
