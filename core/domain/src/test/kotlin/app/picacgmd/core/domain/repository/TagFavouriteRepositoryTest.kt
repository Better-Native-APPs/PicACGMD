package app.picacgmd.core.domain.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class TagFavouriteRepositoryTest {
    private val dao = FakeTagFavouriteDao()
    private var timestamp = 100L
    private val repository = TagFavouriteRepository(dao) { timestamp }

    @Test
    fun trimmedTagsAreUniqueAndAppendingKeepsSavedOrder() = runTest {
        repository.add(" 标签一 ")
        timestamp = 200
        repository.add("标签二")
        repository.add("标签一")
        val tags = repository.favourites.first()
        assertEquals(listOf("标签一", "标签二"), tags.map { it.tag })
        assertEquals(listOf(0, 1), tags.map { it.sortOrder })
        assertEquals(100L, tags.first().createdAt)
        assertTrue(repository.isFavourite(" 标签一 ").first())
    }

    @Test
    fun successiveTogglesUseTheStoredValue() = runTest {
        assertTrue(repository.toggle(" 标签 "))
        assertTrue(repository.isFavourite("标签").first())
        assertFalse(repository.toggle("标签"))
        assertFalse(repository.isFavourite("标签").first())
        repository.add("其他")
        timestamp = 300
        assertTrue(repository.toggle("标签"))
        assertEquals(listOf("其他", "标签"), repository.favourites.first().map { it.tag })
        assertEquals(300L, repository.favourites.first().last().createdAt)
    }

    @Test
    fun removalCountsUniqueSavedTagsAndClearPreservesNoEntries() = runTest {
        repository.add("一")
        repository.add("二")
        assertEquals(1, repository.remove(listOf(" 一 ", "一", "不存在")))
        assertEquals(listOf("二"), repository.favourites.first().map { it.tag })
        repository.clear()
        assertTrue(repository.favourites.first().isEmpty())
    }

    @Test
    fun reorderRequiresTheCompleteUniqueSelectionAndPreservesCreatedTimes() = runTest {
        repository.add("一")
        timestamp = 200
        repository.add("二")
        val before = repository.favourites.first()
        assertFailsWith<IllegalArgumentException> { repository.reorder(listOf("一")) }
        assertFailsWith<IllegalArgumentException> { repository.reorder(listOf("一", "一")) }
        assertEquals(before, repository.favourites.first())
        repository.reorder(listOf("二", "一"))
        assertEquals(listOf("二", "一"), repository.favourites.first().map { it.tag })
        assertEquals(listOf(200L, 100L), repository.favourites.first().map { it.createdAt })
    }

    @Test
    fun blankAddAndToggleDoNotWrite() = runTest {
        assertFailsWith<IllegalArgumentException> { repository.add("  ") }
        assertFailsWith<IllegalArgumentException> { repository.toggle("  ") }
        assertTrue(repository.favourites.first().isEmpty())
    }
}
