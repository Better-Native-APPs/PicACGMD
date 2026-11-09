package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadEp

/** A null chapter selection means all chapters, resolved after the request is persisted. */
data class DownloadRequest(val comic: DownloadComic, val eps: List<DownloadEp>? = null)

data class DownloadNetworkState(val connected: Boolean, val wifi: Boolean) {
    companion object {
        val OFFLINE: DownloadNetworkState = DownloadNetworkState(connected = false, wifi = false)
    }
}

/** Progress for the current engine session, not the entire previously downloaded library. */
data class DownloadProgress(
    val activeComics: Int = 0,
    val completedComics: Int = 0,
    val failedComics: Int = 0,
    val totalPictures: Int = 0,
    val completedPictures: Int = 0,
    val failedPictures: Int = 0,
    val downloadedBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val currentComicTitle: String? = null,
    val currentEpTitle: String? = null,
    val waitingForWifi: Boolean = false,
    val configuredThreads: Int = 4,
    val effectiveThreads: Int = 4,
    /** False while any selected chapter's page manifest is still unknown (including failed fetches). */
    val pictureTotalsKnown: Boolean = false,
) {
    val remainingPictures: Int get() = (totalPictures - completedPictures).coerceAtLeast(0)
}

/** The application owns the manager so WorkManager can reconstruct it after process death. */
interface DownloadManagerOwner {
    val downloadManager: DownloadManager
}
