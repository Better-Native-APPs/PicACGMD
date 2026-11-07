package app.picacgmd

import android.app.Application
import android.util.Log
import coil3.SingletonImageLoader
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.download.DownloadManagerOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PikapikaApplication : Application(), DownloadManagerOwner {
    lateinit var appContainer: AppContainer
        private set
    override val downloadManager: DownloadManager by lazy {
        DownloadManager(
            apiProvider = { appContainer.networkConfigProvider.current.api },
            imageClientProvider = { appContainer.networkConfigProvider.current.imageClient },
            dao = appContainer.database.downloadDao(),
            settingsRepository = appContainer.settingsRepository,
            context = this,
            imageUrlProvider = appContainer.networkConfigProvider::imageUrl,
        )
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        SingletonImageLoader.setSafe { appContainer.imageLoader }
        downloadManager.start()
        applicationScope.launch {
            try {
                appContainer.onAppOpen()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Optional sign-in/punch-in/subscription refresh must not crash the application.
                Log.w("Pikapika", "App-open refresh failed", exception)
            }
        }
    }
}
