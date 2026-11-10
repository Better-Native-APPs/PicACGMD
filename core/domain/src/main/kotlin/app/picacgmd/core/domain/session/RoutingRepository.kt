package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.data.settings.defaultApiAddresses
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.core.network.ImageServer
import app.picacgmd.core.network.IpLiteral
import app.picacgmd.core.network.PicaRouteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** SAVED: the selected address is no longer in the synced or built-in list; it stays until another route is chosen. */
enum class RouteSource { SYSTEM, SERVER, BUILT_IN, SAVED }

data class RouteOption<out T>(val value: T, val label: String, val address: String?, val source: RouteSource)

/** Missing and failed samples never win; ties retain the catalogue order. */
fun <T> fastestReachableRoute(options: List<RouteOption<T>>, latencies: Map<T, Long?>): RouteOption<T>? =
    options.filter { latencies[it.value] != null }.minByOrNull { latencies.getValue(it.value)!! }

data class ApiRouteState(
    val options: List<RouteOption<ApiRoute>>,
    val selected: RouteOption<ApiRoute>,
    val lastSyncedAtMillis: Long?,
)

data class ImageRouteState(
    val options: List<RouteOption<ImageServer>>,
    val selected: RouteOption<ImageServer>,
)

/** One UI-facing API for both route lists. All writes propagate persistence failures to the UI. */
class RoutingRepository(
    private val settingsRepository: SettingsRepository,
    private val networkConfigProvider: NetworkConfigProvider,
    private val fetchAddresses: suspend (String?) -> List<String> = PicaRouteTransport()::fetchApiAddresses,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val apiRoutes: Flow<ApiRouteState> = settingsRepository.settings.map(::apiRouteState).distinctUntilChanged()
    val imageRoutes: Flow<ImageRouteState> = settingsRepository.settings.map(::imageRouteState).distinctUntilChanged()

    suspend fun selectApiRoute(route: ApiRoute) {
        settingsRepository.setApiRouteAddress(when (route) {
            ApiRoute.None -> null
            is ApiRoute.Address -> IpLiteral.normalize(route.ip)
        })
    }

    suspend fun syncApiRoutes() {
        val proxy = settingsRepository.settings.first().proxyUrl.trim().takeIf(String::isNotEmpty)
        val addresses = try {
            fetchAddresses(proxy).mapNotNull(IpLiteral::normalizeOrNull).distinct()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalStateException(exception.localizedMessage?.let { "分流同步失败：$it" } ?: "分流同步失败", exception)
        }
        require(addresses.isNotEmpty()) { "未获取到分流地址" }
        settingsRepository.setSyncedApiAddresses(addresses, nowMillis())
    }

    suspend fun measureApiRoute(route: ApiRoute): Long? = networkConfigProvider.measureApiRoute(route)

    /** Recover an unreachable selection after a picker measurement, without replaying API mutations. */
    suspend fun fallbackApiRoute(latencies: Map<ApiRoute, Long?>): RouteOption<ApiRoute>? {
        val state = apiRoutes.first()
        return fallbackRoute(state.options, state.selected, latencies, ::selectApiRoute)
    }

    suspend fun selectImageRoute(server: ImageServer) {
        settingsRepository.setImageRouting(when (server) {
            ImageServer.Original -> RoutingMode.NONE
            ImageServer.Split1 -> RoutingMode.ROUTE_1
            ImageServer.Split2 -> RoutingMode.ROUTE_2
            ImageServer.Split3 -> RoutingMode.ROUTE_3
        })
    }

    suspend fun measureImageRoute(server: ImageServer): Long? = withRouteProbeDeadline {
        PicaRouteTransport().measureImageHost(imageRouteHost(server))
    }

    suspend fun fallbackImageRoute(latencies: Map<ImageServer, Long?>): RouteOption<ImageServer>? {
        val state = imageRoutes.first()
        return fallbackRoute(state.options, state.selected, latencies, ::selectImageRoute)
    }
}

private suspend fun <T> fallbackRoute(
    options: List<RouteOption<T>>,
    selected: RouteOption<T>,
    latencies: Map<T, Long?>,
    select: suspend (T) -> Unit,
): RouteOption<T>? {
    if (!latencies.containsKey(selected.value) || latencies[selected.value] != null) return null
    val fastest = fastestReachableRoute(options, latencies) ?: return null
    select(fastest.value)
    return fastest
}

fun apiRouteState(settings: AppSettings): ApiRouteState {
    val synced = settings.syncedApiAddresses.mapNotNull(IpLiteral::normalizeOrNull).distinct()
    val selected = settings.apiRouteAddress?.let(IpLiteral::normalize)
    val addresses = (synced + defaultApiAddresses + listOfNotNull(selected)).distinct()
    val options: List<RouteOption<ApiRoute>> = listOf(RouteOption(ApiRoute.None, "不分流", null, RouteSource.SYSTEM)) +
        addresses.mapIndexed { index, address ->
            RouteOption(ApiRoute.Address(address), "分流 ${index + 1}", address, when (address) {
                in synced -> RouteSource.SERVER
                in defaultApiAddresses -> RouteSource.BUILT_IN
                else -> RouteSource.SAVED
            })
        }
    return ApiRouteState(options, options.first { it.address == selected }, settings.apiRoutesSyncedAt)
}

fun imageRouteState(settings: AppSettings): ImageRouteState {
    val servers = listOf(ImageServer.Original, ImageServer.Split1, ImageServer.Split2, ImageServer.Split3)
    val options = servers.mapIndexed { index, server ->
        RouteOption(
            server,
            if (server == ImageServer.Original) "不分流" else "分流 $index",
            imageRouteHost(server),
            if (server == ImageServer.Original) RouteSource.SYSTEM else RouteSource.BUILT_IN,
        )
    }
    val selected = settings.toImageServer()
    return ImageRouteState(options, options.first { it.value == selected })
}

fun AppSettings.toImageServer(): ImageServer = when (imageRoutingMode) {
    RoutingMode.NONE -> ImageServer.Original
    RoutingMode.ROUTE_1 -> ImageServer.Split1
    RoutingMode.ROUTE_2 -> ImageServer.Split2
    RoutingMode.ROUTE_3 -> ImageServer.Split3
}

internal fun imageRouteHost(server: ImageServer): String = when (server) {
    // The original fileServer documented in docs/picacg-protocol.md.
    ImageServer.Original -> "https://storage1.picacomic.com"
    ImageServer.Split1 -> "https://storage.wikawika.xyz"
    ImageServer.Split2 -> "https://s2.picacomic.com"
    ImageServer.Split3 -> "https://s3.picacomic.com"
}
