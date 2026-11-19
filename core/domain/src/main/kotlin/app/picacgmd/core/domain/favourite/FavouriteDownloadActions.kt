package app.picacgmd.core.domain.favourite

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.network.ComicInfo
import kotlinx.coroutines.flow.first

/** Automatic additions use the public download facade, without changing manual-download policies. */
class FavouriteDownloadActions(
    context: Context,
    private val settings: SettingsRepository,
    private val downloads: DownloadManager,
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Null means automation was disabled; a returned notice describes the outcome. */
    suspend fun onAdded(comic: ComicInfo): String? {
        val preferences = settings.settings.first()
        if (!preferences.autoDownloadOnFavourite) return null
        if (!comic.allowDownload) return "此漫画不允许下载，已跳过自动下载"
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        if (preferences.autoDownloadWifiOnly && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true) {
            return "未连接不计流量的网络，已跳过自动下载"
        }
        // No chapter subset means all chapters, including chapter metadata recovery in the engine.
        downloads.enqueue(comic)
        return "已加入全部章节下载队列"
    }
}
