package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus

enum class DownloadChapterPhase { Complete, Downloading, Waiting, Failed, Paused, Deleting }

fun DownloadEp.chapterPhase(): DownloadChapterPhase = when (status) {
    DownloadStatus.DONE -> DownloadChapterPhase.Complete
    DownloadStatus.RUNNING -> DownloadChapterPhase.Downloading
    DownloadStatus.QUEUED -> DownloadChapterPhase.Waiting
    DownloadStatus.FAILED -> DownloadChapterPhase.Failed
    DownloadStatus.PAUSED -> DownloadChapterPhase.Paused
    DownloadStatus.DELETING -> DownloadChapterPhase.Deleting
}

fun DownloadEp.downloadFraction(): Float = if (selectedPictureCount <= 0) 0f else
    (completedPictureCount.toFloat() / selectedPictureCount).coerceIn(0f, 1f)
