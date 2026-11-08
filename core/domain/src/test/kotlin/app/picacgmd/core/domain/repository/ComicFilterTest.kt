package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.CategoryShieldMode
import app.picacgmd.core.network.ComicInfo
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

    @Test
    fun aiMarkersMatchTitlesCategoriesAndTagsIgnoringCase() {
        val markers = listOf("AI作画", "AI作畫", "AI创作", "AI生成", "AI画作", "AI绘画", "AI艺术", "AI繪畫", "AI藝術")
        val samples = markers.flatMap { marker ->
            listOf(
                ComicSimple(title = "[$marker] 漫画"),
                ComicSimple(categories = listOf("分类 ${marker.replaceFirst("AI", "ai")}")),
                ComicSimple(tags = listOf("标签 ${marker.replaceFirst("AI", "aI")}")),
            )
        }
        samples.forEach { assertTrue(isAiComic(it), it.toString()) }
        val result = filterComics(samples, AppSettings(hideAiComics = true))
        assertTrue(result.items.isEmpty())
        assertEquals(samples.size, result.hiddenCount)
    }

    @Test
    fun aiFilterIsOptInAndDoesNotMatchPlainAiOrAuthors() {
        val samples = listOf(
            ComicSimple(id = "ai", title = "AI作画"),
            ComicSimple(id = "normal", title = "AI 教程", author = "AI藝術", tags = listOf("AI")),
            ComicSimple(id = "empty"),
        )
        assertEquals(FilteredComics(samples, 0), filterComics(samples, AppSettings()))
        val result = filterComics(samples, AppSettings(hideAiComics = true))
        assertEquals(listOf("normal", "empty"), result.items.map { it.id })
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun aiAndOtherFiltersCountOverlappingComicsOnce() {
        val comic = ComicSimple(id = "ai", title = "AI作画", categories = listOf("AI藝術"), tags = listOf("AI生成"))
        val result = filterComics(
            listOf(comic, ComicSimple(id = "visible")),
            AppSettings(
                hideAiComics = true, hideViewedComics = true,
                hiddenKeywords = listOf("AI"), shieldedCategories = listOf("AI藝術"),
            ),
            viewedIds = setOf("ai"),
        )
        assertEquals(listOf("visible"), result.items.map { it.id })
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun detailConversionPreservesTagsForAiFiltering() {
        val simple = ComicInfo(id = "detail", tags = listOf("AI繪畫")).toSimple()
        assertEquals(listOf("AI繪畫"), simple.tags)
        assertTrue(isComicHidden(simple, AppSettings(hideAiComics = true)))
    }
}
