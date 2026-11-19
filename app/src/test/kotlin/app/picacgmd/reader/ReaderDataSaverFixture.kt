package app.picacgmd.reader

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.ComicRepository
import app.picacgmd.core.domain.repository.HistoryRepository
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.RoutingRepository
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.PicaApi
import app.picacgmd.testing.MemoryCatalogueDao
import app.picacgmd.testing.MemoryCredentialStore
import app.picacgmd.testing.MemoryHistoryDao
import app.picacgmd.testing.MemoryPreferencesStore
import app.picacgmd.testing.MemorySearchDao
import app.picacgmd.testing.MemorySessionPreferences
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo
import sun.misc.Unsafe
import kotlin.test.assertNotNull

internal class ReaderDataSaverFixture(quality: ImageQuality = ImageQuality.HIGH) : AutoCloseable, ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val defaults = AppSettings(imageQuality = quality, readerPreloadAhead = 5, autoPunchIn = false)
    val settings = SettingsRepository(MemoryPreferencesStore(), defaults)
    val requests = CopyOnWriteArrayList<Request>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val data = when (request.url.encodedPath) {
            "/comics/comic" -> """{"comic":{"_id":"comic","title":"测试漫画"}}"""
            "/comics/comic/eps" -> """{"eps":{"docs":[{"_id":"ep-1","title":"第 1 话","order":1},{"_id":"ep-2","title":"第 2 话","order":2}],"page":1,"pages":1,"total":2}}"""
            "/comics/comic/order/1/pages", "/comics/comic/order/2/pages" ->
                """{"pages":{"docs":[],"page":1,"pages":1,"total":0}}"""
            else -> error("Unexpected reader test request: ${request.url.encodedPath}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"code":200,"message":"success","data":$data}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
    val api = PicaApi(baseUrl = "https://reader.test/", client = client)
    val network = NetworkConfigProvider(settings.settings, scope)
    private val historyDao = MemoryHistoryDao()
    private val session = SessionManager(MemorySessionPreferences(defaults), MemoryCredentialStore(), { api }, scope)
    val comics = ComicRepository(session, settings.settings, MemoryCatalogueDao(), MemorySearchDao(), historyDao)
    val history = HistoryRepository(historyDao, settings.settings)
    val container: AppContainer = run {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        (unsafe.allocateInstance(AppContainer::class.java) as AppContainer).apply {
            setField("settingsRepository", settings)
            setField("networkConfigProvider", network)
            setField("comicRepository", comics)
            setField("historyRepository", history)
            setField("routingRepository", RoutingRepository(settings, network))
        }
    }

    fun source(qualityProvider: () -> app.picacgmd.feature.reader.ImageQuality) = OnlineReaderSource(
        comic = ComicInfo(id = "comic", title = "测试漫画"),
        comicRepository = comics,
        historyRepository = history,
        networkConfigProvider = network,
        onProgressError = { throw AssertionError("Unexpected reader progress failure", it) },
        qualityProvider = qualityProvider,
    )

    override fun close() {
        viewModelStore.clear()
        scope.cancel()
        network.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun AppContainer.setField(name: String, value: Any) {
        AppContainer::class.java.getDeclaredField(name).apply { isAccessible = true; set(this@setField, value) }
    }
}

@Suppress("DEPRECATION")
internal fun setReaderTestNetwork(unmetered: Boolean, transport: Int = NetworkCapabilities.TRANSPORT_WIFI) {
    val context = RuntimeEnvironment.getApplication()
    // Robolectric does not grant the app's merged signature permission automatically.
    shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val shadow = shadowOf(manager)
    shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED,
        if (transport == NetworkCapabilities.TRANSPORT_WIFI) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE,
        0, true, true,
    ))
    val capabilities = NetworkCapabilities()
    shadowOf(capabilities).apply {
        addTransportType(transport)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        else removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    shadow.setNetworkCapabilities(assertNotNull(manager.activeNetwork), capabilities)
}
