package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadChapterStateTest {
    private val chapter = DownloadEp("ep", "comic", 1, "第一话")

    @Test
    fun everyPersistedStateHasADistinctChapterPhase() {
        val phases = mapOf(
            DownloadStatus.DONE to DownloadChapterPhase.Complete,
            DownloadStatus.RUNNING to DownloadChapterPhase.Downloading,
            DownloadStatus.QUEUED to DownloadChapterPhase.Waiting,
            DownloadStatus.FAILED to DownloadChapterPhase.Failed,
            DownloadStatus.PAUSED to DownloadChapterPhase.Paused,
            DownloadStatus.DELETING to DownloadChapterPhase.Deleting,
        )
        assertEquals(DownloadStatus.entries.toSet(), phases.keys)
        phases.forEach { (status, phase) -> assertEquals(phase, chapter.copy(status = status).chapterPhase()) }
    }

    @Test
    fun progressHandlesUnresolvedChaptersAndClampsPersistedCounts() {
        assertEquals(0f, chapter.downloadFraction())
        assertEquals(0.25f, chapter.copy(selectedPictureCount = 4, completedPictureCount = 1).downloadFraction())
        assertEquals(1f, chapter.copy(selectedPictureCount = 4, completedPictureCount = 5).downloadFraction())
        assertEquals(0f, chapter.copy(selectedPictureCount = 4, completedPictureCount = -1).downloadFraction())
    }
}
