package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadPicture
import app.picacgmd.core.data.db.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import sun.misc.Unsafe

@OptIn(ExperimentalCoroutinesApi::class)
internal class DownloadManagerTest {
    @Test
    fun getComicReturnsTheExactDaoSnapshotAndNullForAMissingComic() = runTest {
        val dao = FakeDownloadDao()
        val manager = facade(dao)
        val snapshot = DownloadComic("comic", "Title", "Author", "server", "thumb",
            "[\"Category\"]", "[\"Tag\"]", "Description", status = DownloadStatus.DELETING,
            selectedEpCount = 5, completedEpCount = 2, selectedPictureCount = 30, completedPictureCount = 9,
            downloadedBytes = 123L, folder = "folder", filePath = "legacy", thumbFilePath = "cover",
            thumbFormat = "jpeg", storageRoot = "content://downloads/tree", error = "pending removal",
            createdAt = 11L, updatedAt = 22L, completedAt = 33L)
        dao.upsertComic(snapshot)

        assertEquals(snapshot, manager.getComic("comic"))
        assertNull(manager.getComic("missing"))
    }

    @Test
    fun observePicturesKeepsDaoOrderingFieldsStatusesAndLiveUpdates() = runTest {
        val dao = FakeDownloadDao()
        val manager = facade(dao)
        val first = DownloadPicture("z", "comic", "ep", 3, 1, "server", "page",
            originalName = "original", status = DownloadStatus.DONE, downloadedBytes = 12L,
            fileSizeBytes = 13L, format = "png", width = 640, height = 960,
            filePath = "content://downloads/document/first", attemptCount = 2, error = "stored",
            createdAt = 11L, updatedAt = 22L, completedAt = 33L)
        val last = first.copy(pictureId = "a", pictureIndex = 3, status = DownloadStatus.FAILED, completedAt = null)
        dao.upsertPictures(listOf(last, first, first.copy(pictureId = "other", epId = "other")))
        val emissions = mutableListOf<List<DownloadPicture>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { manager.observePictures("ep").take(2).toList(emissions) }

        val changed = last.copy(status = DownloadStatus.DONE, error = null)
        dao.upsertPictures(listOf(changed))
        job.join()

        assertEquals(listOf(listOf(first, last), listOf(first, changed)), emissions)
        assertEquals(emptyList(), manager.observePictures("missing").first())
    }

    @Test
    fun chapterFacadeKeepsTheDaoOrderAndDoesNotFilterStatuses() = runTest {
        val dao = FakeDownloadDao()
        val manager = facade(dao)
        val first = DownloadEp("z", "comic", 1, "First", status = DownloadStatus.DONE, createdAt = 10L)
        val last = first.copy(epId = "a", epOrder = 5, title = "Last", status = DownloadStatus.FAILED)
        dao.upsertEps(listOf(last, first, first.copy(epId = "other", comicId = "other")))

        assertEquals(listOf(first, last), manager.observeChapters("comic").first())
    }

    // Exercise only DAO delegation; the Context-dependent engine, notifications and scheduling stay unconstructed.
    private fun facade(dao: FakeDownloadDao): DownloadManager {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return (unsafe.allocateInstance(DownloadManager::class.java) as DownloadManager).apply {
            DownloadManager::class.java.getDeclaredField("dao").apply { isAccessible = true }.set(this, dao)
        }
    }
}
