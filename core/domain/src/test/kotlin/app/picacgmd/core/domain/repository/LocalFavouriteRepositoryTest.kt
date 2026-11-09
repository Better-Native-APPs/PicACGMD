package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.PicaImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        repository.setFolder(comic, defaultId)
        val saved = repository.entries.first().single()
        assertEquals("https://s2.picacomic.com/static/cover.jpg", saved.coverUrl)
        assertEquals(100L, saved.addedAt)
        assertEquals(comic.toSimple(), saved.toSimple())
        assertEquals(defaultId, repository.folderId(comic.id).first())
        assertEquals(100L, repository.folders.first().single().lastSelectedAt)
    }

    @Test
    fun replacingFolderSelectionKeepsOneMembershipAndRefreshesTheSnapshot() = runTest {
        val other = repository.createFolder("其他")
        repository.setFolder(comic, defaultId)
        timestamp = 200
        repository.setFolder(comic.copy(title = "新标题"), other.folderId)
        val moved = repository.entries.first().single()
        assertEquals(other.folderId, moved.folderId)
        assertEquals(100L, moved.addedAt)
        assertEquals("新标题", moved.title)
        assertEquals(other.folderId, repository.folderId(comic.id).first())
        assertEquals(200L, repository.folders.first().single { it.folderId == other.folderId }.lastSelectedAt)
        timestamp = 300
        repository.setFolder(comic, other.folderId)
        assertEquals(other.folderId, repository.entries.first().single().folderId)
        assertEquals(100L, repository.entries.first().single().addedAt)
        repository.setFolder(comic, null)
        assertTrue(repository.entries.first().isEmpty())
        assertNull(repository.folderId(comic.id).first())
    }

    @Test
    fun scopedRemovalAndUndoKeepOneMembership() = runTest {
        val other = repository.createFolder("其他")
        repository.setFolder(comic, other.folderId)
        val saved = repository.entries.first()
        assertEquals(0, repository.remove(listOf(comic.id), defaultId))
        assertEquals(saved, repository.entries.first())
        assertEquals(1, repository.remove(listOf(comic.id, comic.id), other.folderId))
        assertNull(repository.folderId(comic.id).first())
        repository.setFolder(comic, defaultId)
        repository.restore(saved)
        assertEquals(saved.single(), repository.entries.first().single())
        assertEquals(other.folderId, repository.folderId(comic.id).first())
        assertEquals(1, repository.remove(listOf(comic.id)))
        assertTrue(repository.entries.first().isEmpty())
    }

    @Test
    fun movingSavedComicPreservesItsSnapshotAndOriginalAddedTime() = runTest {
        val other = repository.createFolder("其他")
        repository.setFolder(comic, defaultId)
        val saved = repository.entries.first().single()
        timestamp = 200
        repository.move(comic.id, other.folderId)
        assertEquals(saved.copy(folderId = other.folderId), repository.entries.first().single())
        assertEquals(200L, repository.folders.first().single { it.folderId == other.folderId }.lastSelectedAt)
        repository.move(comic.id, defaultId)
        assertEquals(saved, repository.entries.first().single())
        assertFailsWith<IllegalArgumentException> { repository.move("missing", defaultId) }
        assertEquals(saved, repository.entries.first().single())
    }

    @Test
    fun deletingFolderMovesComicsAndKeepsTheirOriginalAddedTime() = runTest {
        val other = repository.createFolder("其他")
        repository.setFolder(comic, defaultId)
        timestamp = 200
        repository.setFolder(comic.copy(id = "second"), other.folderId)
        timestamp = 300
        repository.setFolder(comic.copy(id = "third"), other.folderId)
        repository.deleteFolder(other.folderId, defaultId)
        assertFalse(dao.folders.value.containsKey(other.folderId))
        val remaining = repository.entries.first().associateBy { it.comicId }
        assertEquals(setOf(comic.id, "second", "third"), remaining.keys)
        assertEquals(100L, remaining.getValue(comic.id).addedAt)
        assertEquals(200L, remaining.getValue("second").addedAt)
        assertEquals(300L, remaining.getValue("third").addedAt)
        assertTrue(remaining.values.all { it.folderId == defaultId })
    }

    @Test
    fun invalidDestinationAndDefaultDeletionLeaveTheLibraryIntact() = runTest {
        repository.setFolder(comic, defaultId)
        val before = repository.entries.first()
        assertFailsWith<IllegalArgumentException> { repository.setFolder(comic, "missing") }
        assertFailsWith<IllegalArgumentException> { repository.move(comic.id, "missing") }
        assertFailsWith<IllegalArgumentException> { repository.deleteFolder(defaultId, "missing") }
        assertFailsWith<IllegalArgumentException> { repository.deleteFolder("missing", defaultId) }
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
