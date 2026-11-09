package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.PicaImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class LocalFavouriteRepositoryTest {
    private val dao = FakeLocalFavouriteDao()
    private var timestamp = 100L
    private var folderNumber = 0
    private val repository = LocalFavouriteRepository(dao, { timestamp }, { "folder-${++folderNumber}" })
    private val comic = ComicInfo(
        id = "comic",
        title = "测试漫画",
        author = "作者",
        thumb = PicaImage("https://s2.picacomic.com", "cover.jpg"),
        categories = listOf("漫画", "中文"),
        pagesCount = 120,
        epsCount = 6,
        finished = true,
    )
    private val defaultId = LocalFavouriteFolder.DEFAULT_ID

    @Test
    fun firstAccessCreatesOneDefaultFolderWithoutOverwritingItsName() = runTest {
        assertEquals("默认收藏夹", repository.folders.first().single().name)
        repository.renameFolder(defaultId, "我的收藏")
        timestamp = 200
        assertEquals("我的收藏", repository.folders.first().single().name)
        assertEquals(100L, repository.folders.first().single().createdAt)
    }

    @Test
    fun snapshotsAreCompleteAndReadableWithoutANetworkOrSession() = runTest {
        repository.setFolders(comic, setOf(defaultId))
        val saved = repository.entries.first().single()
        assertEquals("https://s2.picacomic.com/static/cover.jpg", saved.coverUrl)
        assertEquals(100L, saved.addedAt)
        assertEquals(comic.toSimple(), saved.toSimple())
        assertEquals(setOf(defaultId), repository.folderIds(comic.id).first())
        assertEquals(100L, repository.folders.first().single().lastSelectedAt)
    }

    @Test
    fun replacingFolderSelectionPreservesExistingAddedTimesAndRefreshesTheSnapshot() = runTest {
        val other = repository.createFolder("其他")
        repository.setFolders(comic, setOf(defaultId))
        timestamp = 200
        repository.setFolders(comic.copy(title = "新标题"), setOf(defaultId, other.folderId))
        val entries = repository.entries.first().associateBy { it.folderId }
        assertEquals(100L, entries.getValue(defaultId).addedAt)
        assertEquals(200L, entries.getValue(other.folderId).addedAt)
        assertTrue(entries.values.all { it.title == "新标题" })
        timestamp = 300
        repository.setFolders(comic, setOf(other.folderId))
        assertEquals(other.folderId, repository.entries.first().single().folderId)
        assertEquals(200L, repository.entries.first().single().addedAt)
        repository.setFolders(comic, emptySet())
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun removingOneMembershipAndUndoDoNotRemoveTheOtherFolder() = runTest {
        val other = repository.createFolder("其他")
        repository.setFolders(comic, setOf(defaultId, other.folderId))
        val saved = repository.entries.first().filter { it.folderId == other.folderId }
        assertEquals(1, repository.remove(listOf(comic.id, comic.id), other.folderId))
        assertEquals(setOf(defaultId), repository.folderIds(comic.id).first())
        repository.restore(saved)
        assertEquals(setOf(defaultId, other.folderId), repository.folderIds(comic.id).first())
        assertEquals(2, repository.remove(listOf(comic.id)))
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun deletingFolderMovesComicsAndKeepsExistingDestinationAddedTime() = runTest {
        val other = repository.createFolder("其他")
        repository.setFolders(comic, setOf(defaultId))
        timestamp = 200
        repository.setFolders(comic, setOf(defaultId, other.folderId))
        repository.setFolders(comic.copy(id = "second"), setOf(other.folderId))
        repository.deleteFolder(other.folderId, defaultId)
        assertFalse(dao.folders.value.containsKey(other.folderId))
        val remaining = repository.entries.first().associateBy { it.comicId }
        assertEquals(setOf(comic.id, "second"), remaining.keys)
        assertEquals(100L, remaining.getValue(comic.id).addedAt)
        assertEquals(200L, remaining.getValue("second").addedAt)
        assertTrue(remaining.values.all { it.folderId == defaultId })
    }

    @Test
    fun invalidDestinationAndDefaultDeletionLeaveTheLibraryIntact() = runTest {
        repository.setFolders(comic, setOf(defaultId))
        val before = repository.entries.first()
        assertFailsWith<IllegalArgumentException> { repository.setFolders(comic, setOf("missing")) }
        assertFailsWith<IllegalArgumentException> { repository.deleteFolder(defaultId, "missing") }
        assertEquals(before, repository.entries.first())
        assertFailsWith<IllegalArgumentException> { repository.createFolder("  ") }
        assertFailsWith<IllegalArgumentException> { repository.renameFolder(defaultId, "  ") }
    }

    @Test
    fun foldersAreUnlimitedAndKeepCreationOrder() = runTest {
        repeat(12) { repository.createFolder(" 文件夹 $it ") }
        val folders = repository.folders.first()
        assertEquals(13, folders.size)
        assertEquals((0..12).toList(), folders.map { it.sortOrder })
        assertEquals("文件夹 11", folders.last().name)
        repository.renameFolder(folders.last().folderId, " 重命名 ")
        assertEquals("重命名", repository.folders.first().last().name)
    }
}
