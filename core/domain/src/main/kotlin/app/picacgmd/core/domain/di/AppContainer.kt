package app.picacgmd.core.domain.di

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import app.picacgmd.core.data.db.PikapikaDatabase
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
import app.picacgmd.core.domain.repository.LocalFavouriteRepository
import app.picacgmd.core.domain.repository.SubscriptionCheckReport
import app.picacgmd.core.domain.repository.SubscriptionRepository
import app.picacgmd.core.domain.repository.TagFavouriteRepository
import app.picacgmd.core.domain.repository.UserRepository
import app.picacgmd.core.domain.session.CredentialStore
import app.picacgmd.core.domain.session.KeystoreCredentialStore
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.RoutingRepository
import app.picacgmd.core.domain.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Retain one container on the Application; no Activity context or DI framework is needed. */
class AppContainer(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lowRamDevice = isLowRamDevice(applicationContext)
    val defaultDownloadConcurrency: Int = AppSettings.defaultsForDevice(lowRamDevice).downloadThreads

    val database: PikapikaDatabase = PikapikaDatabase.create(applicationContext)
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
    val subscriptionRepository: SubscriptionRepository = SubscriptionRepository(
        database.subscriptionDao(), comicRepository, settingsRepository.settings,
    )
    val tagFavouriteRepository: TagFavouriteRepository = TagFavouriteRepository(database.tagFavouriteDao())
    val localFavouriteRepository: LocalFavouriteRepository = LocalFavouriteRepository(database.localFavouriteDao())
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
        return subscriptionRepository.checkOnAppOpen()
    }

    override fun close() {
        scope.cancel()
        imageLoader.shutdown()
        networkConfigProvider.close()
        database.close()
    }
}
