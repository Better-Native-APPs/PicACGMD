package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadPicture
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStorageTest {
    private val temporaryRoot = Files.createTempDirectory("pikapika-storage-test-").toFile()
    private val legacy = File(temporaryRoot, "legacy")
    private val internal = File(temporaryRoot, "internal")
    private val staging = File(temporaryRoot, "staging")
    private val provider = FakeDownloadTreeAccess()
    private fun store() = DownloadFileStore({ legacy }, { internal }, { staging }) { TreeDownloadBackend(provider) }

    @AfterTest
    fun tearDown() { temporaryRoot.deleteRecursively() }

    @Test
    fun newInternalDownloadsUseRecordedRootWhileLegacyPathsStayWhereTheyWere() {
        val files = store()
        val legacyPage = File(legacy, "comic/1/1")
        legacyPage.parentFile!!.mkdirs()
        legacyPage.writeText(IMAGE)
        val old = DownloadComic("comic", "旧漫画")
        assertEquals(DownloadedFileLocation.OnDisk(legacyPage), files.readLocation(old, "comic/1/1"))

        val root = files.selectStorageRoot(null).root
        val comic = DownloadComic("new", "新漫画", storageRoot = root)
        val location = publish(files, files.pictureDestination(comic, picture("new", 1, 1)))
        assertEquals(internal.toURI().toString(), root)
        assertEquals(DownloadedFileLocation.OnDisk(File(internal, "new/1/1")), files.readLocation(comic, location))
        assertEquals(IMAGE, legacyPage.readText())
        assertFalse(File(legacy, "new/1/1").exists())
    }

    @Test
    fun treePagesPersistUrisAndEnumerateEachDirectoryOnlyOnce() {
        val files = store()
        val comic = DownloadComic("comic", "漫画", storageRoot = files.selectStorageRoot(FakeDownloadTreeAccess.TREE).root)
        val cover = publish(files, files.coverDestination(comic))
        val locations = (1..32).map { page -> publish(files, files.pictureDestination(comic, picture("comic", 1, page))) }
        assertEquals(FakeDownloadTreeAccess.TREE, comic.storageRoot)
        assertTrue(locations.all { it.startsWith("content://") })
        assertEquals(3, provider.directoryScans.size)
        assertTrue(provider.directoryScans.values.all { it == 1 })
        val scans = provider.directoryScans.toMap()

        // A fresh reader store has no directory cache, but persisted URIs still require no enumeration.
        val reopened = store()
        reopened.requireReadAccess(comic)
        assertEquals(DownloadedFileLocation.Document(cover), reopened.coverLocation(comic.copy(thumbFilePath = cover)))
        locations.forEach {
            assertEquals(DownloadedFileLocation.Document(it), reopened.readLocation(comic, it))
            assertEquals(IMAGE, provider.openRead(provider.documentId(it)).reader().use { input -> input.readText() })
        }
        assertEquals(scans, provider.directoryScans)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertFalse(File(legacy, "comic/1/1").exists())
    }

    @Test
    fun switchingLocationDoesNotRedirectChapterOrComicDeletion() {
        val files = store()
        val comic = DownloadComic("comic", "漫画", storageRoot = FakeDownloadTreeAccess.TREE)
        val cover = publish(files, files.coverDestination(comic))
        val first = publish(files, files.pictureDestination(comic, picture("comic", 1, 1)))
        val second = publish(files, files.pictureDestination(comic, picture("comic", 2, 1)))
        files.selectStorageRoot(null)
        val other = File(internal, "other/1/1")
        other.parentFile!!.mkdirs()
        other.writeText(IMAGE)

        files.deleteChapter(comic, 1)
        assertFalse(provider.exists(provider.documentId(first)))
        assertTrue(provider.exists(provider.documentId(second)))
        assertTrue(provider.exists(provider.documentId(cover)))
        files.deleteComic(comic)
        assertFalse(provider.exists(provider.documentId(second)))
        assertFalse(provider.exists(provider.documentId(cover)))
        assertEquals(IMAGE, other.readText())
    }

    @Test
    fun revokedTreeFallsBackForNewComicsAndExistingReadsExplainMissingPermission() {
        val files = store()
        provider.writable = false
        val selection = files.selectStorageRoot(FakeDownloadTreeAccess.TREE)
        assertEquals(internal.toURI().toString(), selection.root)
        assertEquals(FakeDownloadTreeAccess.TREE, selection.unavailableTree)
        assertNotNull(files.storageNotice.value)
        provider.readable = false
        assertFailsWith<DownloadStorageAccessException> {
            files.requireReadAccess(DownloadComic("old", "旧漫画", storageRoot = FakeDownloadTreeAccess.TREE))
        }
    }

    @Test
    fun failedPublicationDeletesPartialDocumentAndAnUnpublishedCoverIsNotReused() {
        val files = store()
        val comic = DownloadComic("comic", "漫画", storageRoot = FakeDownloadTreeAccess.TREE)
        val destination = files.coverDestination(comic)
        provider.failNextWrite = true
        assertFailsWith<IOException> { publish(files, destination) }
        assertNull(files.coverLocation(comic))
        val location = publish(files, files.coverDestination(comic))
        assertEquals(DownloadedFileLocation.Document(location), files.coverLocation(comic.copy(thumbFilePath = location)))
        assertFalse(files.coverDestination(comic).exists(), "An orphan without a persisted URI must not be treated as complete")
    }

    private fun publish(files: DownloadFileStore, destination: DownloadDestination): String {
        val temporary = files.createTemporaryFile(destination).apply { writeText(IMAGE) }
        val staged = StagedDownload(temporary, destination, temporary.length(), "gif")
        return try { staged.commit() } finally { staged.discard() }
    }

    private fun picture(comic: String, chapter: Int, page: Int) = DownloadPicture(
        "picture-$chapter-$page", comic, "ep-$chapter", chapter, page, "server", "image.gif",
    )

    companion object { private val IMAGE = "GIF89a" + "original bytes".repeat(1024) }
}
