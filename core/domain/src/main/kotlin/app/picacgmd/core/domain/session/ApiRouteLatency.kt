package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.network.ApiRoute
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal const val API_ROUTE_PROBE_TIMEOUT_MILLIS = 5_000L

internal val builtInApiRoutes: List<ApiRoute>
    get() = listOf(ApiRoute.None, ApiRoute.Split1, ApiRoute.Split2, ApiRoute.Split3)

fun RoutingMode.toApiRoute(customIp: String = ""): ApiRoute = when (this) {
    RoutingMode.NONE -> ApiRoute.None
    RoutingMode.ROUTE_1 -> ApiRoute.Split1
    RoutingMode.ROUTE_2 -> ApiRoute.Split2
    RoutingMode.ROUTE_3 -> ApiRoute.Split3
    RoutingMode.CUSTOM -> ApiRoute.Custom(customIp.trim())
}

fun ApiRoute.toRoutingMode(): RoutingMode = when (this) {
    ApiRoute.None -> RoutingMode.NONE
    ApiRoute.Split1 -> RoutingMode.ROUTE_1
    ApiRoute.Split2 -> RoutingMode.ROUTE_2
    ApiRoute.Split3 -> RoutingMode.ROUTE_3
    is ApiRoute.Custom -> RoutingMode.CUSTOM
}

fun formatApiRouteLatency(latencyMillis: Long?): String =
    latencyMillis?.let { "$it 毫秒" } ?: "超时或不可用"

/** Each route gets its own deadline; caller cancellation is never converted into a failed probe. */
internal suspend fun measureBuiltinApiRoutes(
    probe: suspend (ApiRoute) -> Boolean,
    nowNanos: () -> Long = System::nanoTime,
): Map<ApiRoute, Long?> = coroutineScope {
    builtInApiRoutes.map { route ->
        async {
            val startedAt = nowNanos()
            val succeeded = try {
                withTimeoutOrNull(API_ROUTE_PROBE_TIMEOUT_MILLIS) { probe(route) } == true
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                false
            }
            val elapsedNanos = nowNanos() - startedAt
            val latency = if (succeeded && elapsedNanos <= TimeUnit.MILLISECONDS.toNanos(API_ROUTE_PROBE_TIMEOUT_MILLIS)) {
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
            } else {
                null
            }
            route to latency
        }
    }.awaitAll().toMap()
}
