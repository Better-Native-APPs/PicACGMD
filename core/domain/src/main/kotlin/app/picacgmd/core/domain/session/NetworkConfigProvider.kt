package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.domain.image.ImageLoadProgressInterceptor
import app.picacgmd.core.domain.image.ImageLoadProgressRegistry
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.core.network.ImageQuality
import app.picacgmd.core.network.ImageServer
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.PicaClientFactory
import app.picacgmd.core.network.PicaHeaderBuilder
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaImageUrls
import app.picacgmd.core.network.PicaNetworkConfig
import app.picacgmd.core.network.PicaResult
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

data class NetworkConfiguration(
    val networkConfig: PicaNetworkConfig,
    val imageServer: ImageServer,
    val imageQuality: ImageQuality,
    val connectTimeoutSeconds: Int,
    val readTimeoutSeconds: Int,
    val timeOffsetHours: Int,
    val useApiClientForImages: Boolean,
) {
    companion object {
        fun from(settings: AppSettings): NetworkConfiguration = NetworkConfiguration(
            networkConfig = PicaNetworkConfig(
                apiRoute = settings.apiRoutingMode.toApiRoute(settings.customApiIp),
                proxyUrl = settings.proxyUrl.trim().takeIf(String::isNotEmpty),
            ),
            imageServer = when (settings.imageRoutingMode) {
                RoutingMode.NONE -> ImageServer.Original
                RoutingMode.ROUTE_1 -> ImageServer.Split1
                RoutingMode.ROUTE_2 -> ImageServer.Split2
                RoutingMode.ROUTE_3 -> ImageServer.Split3
                RoutingMode.CUSTOM -> {
                    val host = settings.customImageHost.trim()
                    val url = (if ("://" in host) host else "https://$host").toHttpUrl()
                    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                        "图片分流地址不能包含账号、查询或片段"
                    }
                    ImageServer.Custom(url.toString())
                }
            },
            imageQuality = ImageQuality.valueOf(settings.imageQuality.name),
            connectTimeoutSeconds = settings.connectTimeoutSeconds,
            readTimeoutSeconds = settings.readTimeoutSeconds,
            timeOffsetHours = settings.timeOffsetHours,
            useApiClientForImages = settings.useApiClientForImages,
        )
    }
}

data class NetworkClients(
    val configuration: NetworkConfiguration,
    val apiClient: OkHttpClient,
    val imageClient: OkHttpClient,
    val api: PicaApi,
)

/** Only network preferences rebuild transports. New requests use the latest persisted configuration. */
class NetworkConfigProvider(
    private val settings: Flow<AppSettings>,
    scope: CoroutineScope,
    private val tokenProvider: () -> String? = { null },
) {
    private val lock = Any()
    val imageLoadProgress = ImageLoadProgressRegistry()
    private val mutableClients = MutableStateFlow(build(NetworkConfiguration.from(AppSettings.DEFAULT)))
    private val mutableError = MutableStateFlow<IllegalArgumentException?>(null)
    val clients: StateFlow<NetworkClients> = mutableClients.asStateFlow()
    val configurationError: StateFlow<IllegalArgumentException?> = mutableError.asStateFlow()
    val current: NetworkClients get() {
        mutableError.value?.let { throw it }
        return mutableClients.value
    }

    init {
        scope.launch {
            settings.map { snapshot ->
                try {
                    Result.success(NetworkConfiguration.from(snapshot))
                } catch (exception: IllegalArgumentException) {
                    Result.failure(exception)
                }
            }.distinctUntilChanged().collect { configuration ->
                try {
                    update(configuration.getOrThrow())
                } catch (exception: IllegalArgumentException) {
                    mutableError.value = exception
                }
            }
        }
    }

    suspend fun api(): PicaApi = update(NetworkConfiguration.from(settings.first())).api

    suspend fun awaitReady() { api() }

    /**
     * One signed keyword request per built-in route, in parallel, with a five-second ceiling.
     * Probes connect directly (not through a proxy, which would bypass route DNS) and never update
     * the selected route, session, or retained transports. Values are milliseconds; failures are null.
     */
    suspend fun measureApiRoutes(): Map<ApiRoute, Long?> {
        val timeOffsetHours = settings.first().timeOffsetHours
        return measureBuiltinApiRoutes(probe = { route -> probeApiRoute(route, timeOffsetHours) })
    }

    fun imageUrl(image: PicaImage): String = PicaImageUrls.build(image, current.configuration.imageServer)
    fun imageUrl(fileServer: String, path: String): String =
        PicaImageUrls.build(fileServer, path, current.configuration.imageServer)

    private fun update(configuration: NetworkConfiguration): NetworkClients = synchronized(lock) {
        if (mutableClients.value.configuration != configuration) {
            val previous = mutableClients.value
            mutableClients.value = build(configuration)
            previous.apiClient.connectionPool.evictAll()
            previous.imageClient.connectionPool.evictAll()
        }
        mutableError.value = null
        mutableClients.value
    }

    private fun build(configuration: NetworkConfiguration): NetworkClients {
        val apiClient = PicaClientFactory.apiClient(configuration.networkConfig).withTimeouts(configuration)
        // API clients contain no authentication interceptors; API headers are added by PicaApi only.
        val imageTransport = if (configuration.useApiClientForImages) apiClient else
            PicaClientFactory.imageClient(configuration.networkConfig).withTimeouts(configuration)
        val progress = ImageLoadProgressInterceptor(imageLoadProgress)
        val imageClient = imageTransport.newBuilder()
            // Image storage hosts redirect; safe because image requests carry no signed headers.
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor(progress)
            .eventListenerFactory(progress.eventListenerFactory())
            .build()
        val api = PicaApi(
            tokenProvider = tokenProvider,
            client = apiClient,
            headerBuilder = PicaHeaderBuilder(
                epochSeconds = { System.currentTimeMillis() / 1_000 + configuration.timeOffsetHours * 3_600L },
            ),
        )
        return NetworkClients(configuration, apiClient, imageClient, api)
    }

    private fun OkHttpClient.withTimeouts(configuration: NetworkConfiguration): OkHttpClient = newBuilder()
        .connectTimeout(configuration.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(configuration.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    private suspend fun probeApiRoute(route: ApiRoute, timeOffsetHours: Int): Boolean {
        val client = PicaClientFactory.apiClient(PicaNetworkConfig(apiRoute = route)).newBuilder()
            .proxy(Proxy.NO_PROXY)
            .callTimeout(API_ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .connectTimeout(API_ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(API_ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(API_ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        try {
            val api = PicaApi(
                tokenProvider = tokenProvider,
                client = client,
                headerBuilder = PicaHeaderBuilder(
                    epochSeconds = { System.currentTimeMillis() / 1_000 + timeOffsetHours * 3_600L },
                ),
            )
            return api.hotKeywords() is PicaResult.Success
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    fun close() {
        listOf(mutableClients.value.apiClient, mutableClients.value.imageClient).distinct().forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        imageLoadProgress.clear()
    }
}
