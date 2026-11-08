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
    fun hiddenKeywordsAlsoMatchCategoriesAndTags() {
        val samples = listOf(
            ComicSimple(id = "tag", title = "夏日", tags = listOf("校园", "纯爱")),
            ComicSimple(id = "category", title = "花园", categories = listOf("耽美花園")),
            ComicSimple(id = "plain", title = "冬日", categories = listOf("日常"), tags = listOf("搞笑")),
        )
        val result = filterComics(samples, AppSettings(hiddenKeywords = listOf("纯爱", "耽美")))
        assertEquals(listOf("plain"), result.items.map { it.id })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun asciiKeywordsOnlyMatchWholeWords() {
        // On its own, in brackets, next to Chinese, in another case, in any field: hidden.
        listOf(
            ComicSimple(tags = listOf("BL")),
            ComicSimple(title = "[BL] 夏日"),
            ComicSimple(title = "BL漫画合集"),
            ComicSimple(categories = listOf("耽美BL")),
            ComicSimple(tags = listOf("bl")),
            ComicSimple(author = "studio BL"),
        ).forEach { assertTrue(hiddenBy("BL", it), it.toString()) }
        // Inside a longer word: visible.
        listOf(
            ComicSimple(title = "Blue Archive 同人"),
            ComicSimple(title = "BLEACH"),
            ComicSimple(tags = listOf("Table")),
        ).forEach { assertFalse(hiddenBy("BL", it), it.toString()) }
        assertTrue(hiddenBy("NTR", ComicSimple(categories = listOf("NTR"))))
        assertTrue(hiddenBy("ntr", ComicSimple(tags = listOf("NTR向"))))
        assertFalse(hiddenBy("NTR", ComicSimple(title = "ENTRY")))
        assertFalse(hiddenBy("NTR", ComicSimple(title = "Mind Control")))
        // Digits belong to the word as well.
        assertTrue(hiddenBy("C102", ComicSimple(title = "(C102) 新刊")))
        assertFalse(hiddenBy("C102", ComicSimple(title = "(C1024) 新刊")))
    }

    @Test
    fun asciiKeywordLooksPastAnEmbeddedHitForAWholeOne() {
        // The first 「BL」 is the start of 「Blue」, the second stands alone.
        assertTrue(hiddenBy("BL", ComicSimple(title = "Blue BL")))
        assertFalse(hiddenBy("BL", ComicSimple(title = "Blue Blossom")))
    }

    @Test
    fun otherKeywordsStillMatchInsideWords() {
        assertTrue(hiddenBy("耽美", ComicSimple(categories = listOf("耽美花園"))))
        assertTrue(hiddenBy("寝取", ComicSimple(tags = listOf("寝取られ"))))
        // Mixed with Chinese: a plain substring, still ignoring case.
        assertTrue(hiddenBy("ai作画", ComicSimple(title = "AI作画合集")))
        // A space or a symbol makes it a phrase, which may sit inside a longer word.
        assertTrue(hiddenBy("Blue Archive", ComicSimple(title = "blue archives")))
        assertTrue(hiddenBy("C-1", ComicSimple(title = "AC-12")))
    }

    private fun hiddenBy(keyword: String, comic: ComicSimple): Boolean =
        isComicHidden(comic, AppSettings(hiddenKeywords = listOf(keyword)))

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
        val markers = listOf(
            "AI作画", "AI作畫", "AI创作", "AI創作", "AI生成", "AI画作", "AI畫作", "AI绘画", "AI繪畫", "AI艺术", "AI藝術",
        )
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
