package app.picacgmd.reader

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadPicture
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.domain.download.DownloadFileStore
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.ReaderProgress
import app.picacgmd.testing.TestAppEnvironment
import app.picacgmd.testing.TestPicaServer
import app.picacgmd.testing.awaitRealTime
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

internal class DownloadedReaderSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pica = TestPicaServer()
    private val comic = DownloadComic("comic", "Downloaded", author = "Author",
        categoriesJson = "[\"Category\"]", thumbFileServer = "https://images.example.com", thumbPath = "cover",
        createdAt = 10L)

    @AfterTest fun closeServer() = pica.close()

    @Test
    fun completedChaptersKeepDatabaseOrderAndReadMarksAndAreReadOnlyOnce() = runTest {
        val environment = TestAppEnvironment(pica, backgroundScope)
        val files = DownloadFileStore(temporary.newFolder("downloads"))
        environment.downloadDao.chapters.value = listOf(
            chapter("a", 5, DownloadStatus.DONE),
            chapter("failed", 2, DownloadStatus.FAILED),
            chapter("z", 1, DownloadStatus.DONE),
            chapter("running", 3, DownloadStatus.RUNNING),
            chapter("paused", 4, DownloadStatus.PAUSED),
            chapter("queued", 6, DownloadStatus.QUEUED),
            chapter("deleting", 7, DownloadStatus.DELETING),
        )
        environment.historyDao.entries.value = mapOf("comic" to ViewHistory("comic", "Old",
            lastEpOrder = 3, lastPictureIndex = 2, createdAt = 1L))
        val source = source(environment, files)

        val chapters = source.loadChapters()
        assertEquals(listOf(ReaderChapter("z", "Chapter 1", isRead = true, isDownloaded = true),
            ReaderChapter("a", "Chapter 5", isDownloaded = true)), chapters)
        assertEquals(0, source.indexOfEpisode(1))
        assertEquals(1, source.indexOfEpisode(5))
        assertEquals(-1, source.indexOfEpisode(2))

        environment.downloadDao.chapters.value = emptyList()
        assertSame(chapters, source.loadChapters())
        assertEquals(1, environment.downloadDao.chapterReads.get())
        assertEquals(0, pica.requestCount)
    }

    @Test
    fun picturesUseOneSnapshotKeepTheirOrderAndStayLocal() = runTest {
        val environment = TestAppEnvironment(pica, backgroundScope)
        val root = temporary.newFolder("downloads")
        val files = DownloadFileStore(root)
        File(root, comic.thumbFilePath).apply { parentFile!!.mkdirs(); writeText("cover") }
        val fileUri = File(root, "pinned-page").toURI().toString()
        environment.downloadDao.pictures.value = listOf(
            picture("last", 5, DownloadStatus.DONE, "content://downloads/document/page"),
            picture("failed", 2, DownloadStatus.FAILED),
            picture("first", 1, DownloadStatus.DONE).copy(width = 640, height = 960),
            picture("pinned", 3, DownloadStatus.DONE, fileUri).copy(height = 800),
            picture("queued", 4, DownloadStatus.QUEUED),
        )
        val source = source(environment, files)

        assertEquals(PageImage.Local(File(root, comic.thumbFilePath)), source.comicCover)
        val pages = source.loadPages(ReaderChapter("ep", "Chapter"))
        assertEquals(listOf(
            ReaderPage(PageImage.Local(File(root, "comic/1/1")), 640, 960),
            ReaderPage(PageImage.Local(File(root, "pinned-page")), 0, 800),
            ReaderPage(PageImage.Document("content://downloads/document/page")),
        ), pages)
        assertEquals(1, environment.downloadDao.pictureReads.get())
        assertEquals(0, environment.downloadDao.activePictureReads.get())
        environment.downloadDao.pictures.value = emptyList()
        assertEquals(3, pages.size)
        assertEquals(emptyList(), source.loadPages(ReaderChapter("ep", "Chapter")))
        assertEquals(2, environment.downloadDao.pictureReads.get())
        assertEquals(0, pica.requestCount)
        assertNull(pica.pollRequest(100))
    }

    @Test
    fun savedProgressUsesTheEpisodeOrderAndOneBasedPictureIndexWithoutNetwork() = runTest {
        val environment = TestAppEnvironment(pica, backgroundScope)
        val files = DownloadFileStore(temporary.newFolder("downloads"))
        val previous = ViewHistory("comic", "Old", pinned = true, createdAt = 12L, updatedAt = 13L)
        environment.historyDao.entries.value = mapOf("comic" to previous)
        environment.downloadDao.chapters.value = listOf(chapter("ep", 5, DownloadStatus.DONE))
        val source = source(environment, files)
        val chapter = source.loadChapters().single()

        source.saveProgress(ReaderProgress(0, chapter, pageIndex = 3, pageCount = 19))
        val written = awaitRealTime { environment.historyDao.writes.receive() }

        assertEquals(previous.copy(title = comic.title, author = comic.author, thumbFileServer = comic.thumbFileServer,
            thumbPath = comic.thumbPath, categoriesJson = comic.categoriesJson, lastEpOrder = 5,
            lastEpTitle = "Chapter 5", lastPictureIndex = 4, lastChapterPages = 19, updatedAt = 9_000L), written)
        assertEquals(0, pica.requestCount)
    }

    private fun source(environment: TestAppEnvironment, files: DownloadFileStore): DownloadedReaderSource {
        environment.useFiles(files)
        return DownloadedReaderSource(comic, environment.downloads, files, environment.history,
            onProgressError = { throw AssertionError("Unexpected progress failure", it) })
    }

    private fun chapter(id: String, order: Int, status: DownloadStatus) = DownloadEp(id, "comic", order,
        "Chapter $order", status = status, createdAt = 10L)

    private fun picture(id: String, index: Int, status: DownloadStatus, filePath: String = "comic/1/$index") =
        DownloadPicture(id, "comic", "ep", 1, index, "https://images.example.com", "page-$index",
            status = status, filePath = filePath, createdAt = 10L)
}
