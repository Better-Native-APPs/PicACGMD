package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.CategoryShieldMode
import app.picacgmd.core.network.ComicSimple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComicFilterTest {
    private val comics = listOf(
        ComicSimple(id = "1", title = "Summer Story", author = "Alice", categories = listOf("日常", "彩色")),
        ComicSimple(id = "2", title = "冬日", author = "Bob", categories = listOf("冒险")),
        ComicSimple(id = "3", title = "短篇集", author = "summer studio", categories = listOf("日常")),
    )

    @Test
    fun defaultSettingsHideNothing() {
        assertEquals(FilteredComics(comics, 0), filterComics(comics, AppSettings()))
    }

    @Test
    fun blacklistHidesAnyMatchingCategory() {
        val result = filterComics(comics, AppSettings(shieldedCategories = listOf("彩色", "冒险")))
        assertEquals(listOf("3"), result.items.map { it.id })
        assertEquals(2, result.hiddenCount)
        assertEquals(3, result.total)
    }

    @Test
    fun whitelistKeepsAnyAllowedCategory() {
        val result = filterComics(
            comics, AppSettings(categoryShieldMode = CategoryShieldMode.WHITELIST, shieldedCategories = listOf("彩色")),
        )
        assertEquals(listOf("1"), result.items.map { it.id })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun emptyWhitelistAllowsNothing() {
        val result = filterComics(comics, AppSettings(categoryShieldMode = CategoryShieldMode.WHITELIST))
        assertTrue(result.items.isEmpty())
        assertEquals(3, result.hiddenCount)
    }

    @Test
    fun hiddenKeywordsMatchTitleAndAuthorIgnoringCase() {
        val result = filterComics(comics, AppSettings(hiddenKeywords = listOf(" SUMMER ")))
        assertEquals(listOf("2"), result.items.map { it.id })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun overlappingReasonsCountEachComicOnlyOnce() {
        val result = filterComics(
            comics,
            AppSettings(shieldedCategories = listOf("彩色"), hiddenKeywords = listOf("summer"), hideViewedComics = true),
            viewedIds = setOf("1", "2"),
        )
        assertTrue(result.items.isEmpty())
        assertEquals(3, result.hiddenCount)
    }

    @Test
    fun blankKeywordsAreIgnoredAndViewedToggleIsRespected() {
        assertFalse(isComicHidden(comics.first(), AppSettings(hiddenKeywords = listOf("", "  ")), setOf("1")))
        assertTrue(isComicHidden(comics.first(), AppSettings(hideViewedComics = true), setOf("1")))
    }

    @Test
    fun defaultCategoryAliasAlsoWorksForShielding() {
        val comic = ComicSimple(categories = listOf("大湿推荐"))
        assertTrue(isComicHidden(comic, AppSettings(shieldedCategories = listOf("大濕推薦"))))
    }
}
