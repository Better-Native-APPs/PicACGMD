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
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class RouteSource { SYSTEM, SERVER, BUILT_IN, CUSTOM, SAVED }

data class RouteOption<out T>(val value: T, val label: String, val address: String?, val source: RouteSource)

data class ApiRouteState(
    val options: List<RouteOption<ApiRoute>>,
    val selected: RouteOption<ApiRoute>,
    val customIp: String,
    val lastSyncedAtMillis: Long?,
)

data class ImageRouteState(
    val options: List<RouteOption<ImageServer>>,
    val selected: RouteOption<ImageServer>,
    val customHost: String,
)

/** One UI-facing API for both route pickers. All writes propagate persistence failures to the UI. */
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

    suspend fun setCustomApiRoute(ip: String) = settingsRepository.setCustomApiRoute(IpLiteral.normalize(ip))

    suspend fun syncApiRoutes() {
        val proxy = settingsRepository.settings.first().proxyUrl.trim().takeIf(String::isNotEmpty)
        val addresses = try {
            fetchAddresses(proxy).mapNotNull(IpLiteral::normalizeOrNull).distinct()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalStateException("分流同步失败：${exception.localizedMessage ?: "请检查网络后重试"}", exception)
        }
        require(addresses.isNotEmpty()) { "服务器未返回有效的分流地址，请稍后重试" }
        settingsRepository.setSyncedApiAddresses(addresses, nowMillis())
    }

    /** Reset the catalogue, not the selected address or remembered custom route. */
    suspend fun resetApiRoutes() = settingsRepository.resetApiRoutes()

    suspend fun measureApiRoute(route: ApiRoute): Long? = networkConfigProvider.measureApiRoute(route)

    suspend fun selectImageRoute(server: ImageServer) {
        when (server) {
            ImageServer.Original -> settingsRepository.setImageRouting(RoutingMode.NONE)
            ImageServer.Split1 -> settingsRepository.setImageRouting(RoutingMode.ROUTE_1)
            ImageServer.Split2 -> settingsRepository.setImageRouting(RoutingMode.ROUTE_2)
            ImageServer.Split3 -> settingsRepository.setImageRouting(RoutingMode.ROUTE_3)
            is ImageServer.Custom -> setCustomImageHost(server.baseUrl)
        }
    }

    suspend fun setCustomImageHost(host: String) = settingsRepository.setImageRouting(RoutingMode.CUSTOM, normalizeImageHost(host))

    suspend fun measureImageRoute(server: ImageServer): Long? = withRouteProbeDeadline {
        PicaRouteTransport().measureImageHost(imageRouteHost(server))
    }
}

fun apiRouteState(settings: AppSettings): ApiRouteState {
    val synced = settings.syncedApiAddresses.mapNotNull(IpLiteral::normalizeOrNull).distinct()
    val custom = settings.customApiIp.takeIf(String::isNotBlank)?.let(IpLiteral::normalize)
    val selected = settings.apiRouteAddress?.let(IpLiteral::normalize)
    val addresses = (synced + defaultApiAddresses + listOfNotNull(selected).filter { it != custom }).distinct()
    val options: List<RouteOption<ApiRoute>> = listOf(RouteOption(ApiRoute.None, "不分流", null, RouteSource.SYSTEM)) +
        addresses.mapIndexed { index, address ->
            RouteOption(ApiRoute.Address(address), "分流 ${index + 1}", address, when (address) {
                in synced -> RouteSource.SERVER
                in defaultApiAddresses -> RouteSource.BUILT_IN
                else -> RouteSource.SAVED
            })
        } + listOfNotNull(custom?.let { RouteOption(ApiRoute.Address(it), "自定义", it, RouteSource.CUSTOM) })
    return ApiRouteState(options, options.last { it.address == selected }, settings.customApiIp, settings.apiRoutesSyncedAt)
}

fun imageRouteState(settings: AppSettings): ImageRouteState {
    val servers = listOf(ImageServer.Original, ImageServer.Split1, ImageServer.Split2, ImageServer.Split3) +
        listOfNotNull(settings.customImageHost.takeIf(String::isNotBlank)?.let { ImageServer.Custom(normalizeImageHost(it)) })
    val options = servers.mapIndexed { index, server ->
        RouteOption(server, when (server) {
            ImageServer.Original -> "不分流"
            is ImageServer.Custom -> "自定义"
            else -> "分流 $index"
        }, imageRouteHost(server), when (server) {
            ImageServer.Original -> RouteSource.SYSTEM
            is ImageServer.Custom -> RouteSource.CUSTOM
            else -> RouteSource.BUILT_IN
        })
    }
    val selected = settings.toImageServer()
    return ImageRouteState(options, options.first { it.value == selected }, settings.customImageHost)
}

fun AppSettings.toImageServer(): ImageServer = when (imageRoutingMode) {
    RoutingMode.NONE -> ImageServer.Original
    RoutingMode.ROUTE_1 -> ImageServer.Split1
    RoutingMode.ROUTE_2 -> ImageServer.Split2
    RoutingMode.ROUTE_3 -> ImageServer.Split3
    RoutingMode.CUSTOM -> if (customImageHost.isBlank()) ImageServer.Original else ImageServer.Custom(normalizeImageHost(customImageHost))
}

internal fun imageRouteHost(server: ImageServer): String = when (server) {
    // The original fileServer documented in docs/picacg-protocol.md.
    ImageServer.Original -> "https://storage1.picacomic.com"
    ImageServer.Split1 -> "https://storage.wikawika.xyz"
    ImageServer.Split2 -> "https://s2.picacomic.com"
    ImageServer.Split3 -> "https://s3.picacomic.com"
    is ImageServer.Custom -> normalizeImageHost(server.baseUrl)
}

private fun normalizeImageHost(host: String): String {
    val value = host.trim()
    require(value.isNotEmpty()) { "请填写图片服务器地址" }
    val url = try {
        (if ("://" in value) value else "https://$value").toHttpUrl()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("图片服务器地址无效")
    }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "图片分流地址不能包含账号、查询或片段"
    }
    return url.toString().trimEnd('/')
}
