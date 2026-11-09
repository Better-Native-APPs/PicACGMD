package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.AppSettings
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
import app.picacgmd.core.network.PicaRouteTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

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
                apiRoute = settings.apiRouteAddress?.let(ApiRoute::Address) ?: ApiRoute.None,
                proxyUrl = settings.proxyUrl.trim().takeIf(String::isNotEmpty),
            ),
            imageServer = settings.toImageServer(),
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
    private val scope: CoroutineScope,
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
     * One independently measured API response with a five-second ceiling. HTTP errors (including
     * an expired login's 401) still prove reachability; no response body or session is needed.
     * Probes connect directly (not through a proxy, which would bypass route DNS) and never update
     * the selected route, session, or retained transports. Values are milliseconds; failures are null.
     * Main-safe: the probe and its socket cleanup run on [Dispatchers.IO].
     */
    suspend fun measureApiRoute(route: ApiRoute): Long? = withRouteProbeDeadline {
        PicaRouteTransport().measureApiRoute(route)
    }

    fun imageUrl(image: PicaImage): String = PicaImageUrls.build(image, current.configuration.imageServer)
    fun imageUrl(fileServer: String, path: String): String =
        PicaImageUrls.build(fileServer, path, current.configuration.imageServer)

    private fun update(configuration: NetworkConfiguration): NetworkClients = synchronized(lock) {
        if (mutableClients.value.configuration != configuration) {
            val previous = mutableClients.value
            mutableClients.value = build(configuration)
            // Closing pooled TLS connections writes to the socket, and update() can run on the main thread.
            scope.launch(Dispatchers.IO) {
                previous.apiClient.connectionPool.evictAll()
                previous.imageClient.connectionPool.evictAll()
            }
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

    fun close() {
        listOf(mutableClients.value.apiClient, mutableClients.value.imageClient).distinct().forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        imageLoadProgress.clear()
    }
}
