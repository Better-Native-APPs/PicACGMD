package app.picacgmd.core.domain.di

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.image.createImageLoader
import app.picacgmd.core.domain.cache.CacheMaintenance
import app.picacgmd.core.domain.favourite.FavouriteDownloadActions
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.core.domain.isLowRamDevice
import app.picacgmd.core.domain.repository.AnnouncementRepository
import app.picacgmd.core.domain.repository.ComicRepository
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.domain.repository.HistoryRepository
import app.picacgmd.core.domain.repository.HomeRepository
import app.picacgmd.core.domain.repository.SubscriptionCheckReport
import app.picacgmd.core.domain.repository.SubscriptionRepository
import app.picacgmd.core.domain.repository.TagFavouriteRepository
import app.picacgmd.core.domain.repository.UserRepository
import app.picacgmd.core.domain.session.CredentialStore
import app.picacgmd.core.domain.session.KeystoreCredentialStore
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.RoutingRepository
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.domain.sync.HistorySyncService
import app.picacgmd.core.domain.sync.KeystoreWebDavPasswordStore
import app.picacgmd.core.domain.sync.RoomHistorySyncStore
import app.picacgmd.core.domain.sync.SettingsWebDavPreferences
import app.picacgmd.core.network.webdav.OkHttpWebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Retain one container on the Application; no Activity context or DI framework is needed. */
class AppContainer(context: Context, syncWifiAvailable: () -> Boolean = { false }) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lowRamDevice = isLowRamDevice(applicationContext)
    val defaultDownloadConcurrency: Int = AppSettings.defaultsForDevice(lowRamDevice).downloadThreads

    val database: PicaDatabase = PicaDatabase.create(applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository.create(
        applicationContext, AppSettings.defaultsForDevice(lowRamDevice),
    )
    val credentialStore: CredentialStore = KeystoreCredentialStore(applicationContext)
    val networkConfigProvider: NetworkConfigProvider = NetworkConfigProvider(settingsRepository.settings, scope) {
        sessionManager.token
    }
    val sessionManager: SessionManager = SessionManager(
        settingsRepository, credentialStore, { networkConfigProvider.api() }, scope,
    )
    val routingRepository: RoutingRepository = RoutingRepository(settingsRepository, networkConfigProvider)
    val comicRepository: ComicRepository = ComicRepository(
        sessionManager, settingsRepository.settings, database.apiCacheDao(), database.searchHistoryDao(), database.viewHistoryDao(),
    )
    val homeRepository: HomeRepository = HomeRepository(comicRepository, settingsRepository.settings, scope)
    val commentRepository: CommentRepository = CommentRepository(sessionManager)
    val gameRepository: GameRepository = GameRepository(sessionManager)
    val announcementRepository: AnnouncementRepository = AnnouncementRepository(sessionManager)
    val userRepository: UserRepository = UserRepository(sessionManager, scope)
    val historyRepository: HistoryRepository = HistoryRepository(database.viewHistoryDao(), settingsRepository.settings)
    private val webDavClient = OkHttpWebDavClient()
    val webDavSync = HistorySyncService(SettingsWebDavPreferences(settingsRepository), KeystoreWebDavPasswordStore(applicationContext),
        RoomHistorySyncStore(database), webDavClient, scope, syncWifiAvailable)
    val subscriptionRepository: SubscriptionRepository = SubscriptionRepository(
        database.subscriptionDao(), comicRepository, settingsRepository.settings,
    )
    val tagFavouriteRepository: TagFavouriteRepository = TagFavouriteRepository(database.tagFavouriteDao())
    val imageLoader: ImageLoader = createImageLoader(applicationContext, networkConfigProvider, lowRamDevice = lowRamDevice)
    val cacheMaintenance = CacheMaintenance(applicationContext, imageLoader, database.apiCacheDao())
    val favouriteDownloads by lazy {
        FavouriteDownloadActions(applicationContext, settingsRepository, (applicationContext as DownloadManagerOwner).downloadManager)
    }

    // Download wiring belongs to its parallel lane; these shared dependencies are ready for it.

    suspend fun onAppOpen(): SubscriptionCheckReport {
        try {
            cacheMaintenance.cleanIfDue(settingsRepository.settings.first().autoCleanCacheInterval)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (exception: Exception) {
            Log.w("CacheMaintenance", "Automatic cache cleanup failed", exception)
        }
        networkConfigProvider.awaitReady()
        sessionManager.onAppOpen()
        // Restored subscriptions must not trigger authenticated requests while the user is signed out.
        if (sessionManager.state.value !is SessionState.SignedIn) return SubscriptionCheckReport(0, 0, emptyMap())
        return subscriptionRepository.checkOnAppOpen()
    }

    override fun close() {
        scope.cancel()
        imageLoader.shutdown()
        networkConfigProvider.close()
        webDavClient.close()
        database.close()
    }
}
