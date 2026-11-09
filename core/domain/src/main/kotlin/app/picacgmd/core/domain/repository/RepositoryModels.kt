package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.CategoryShieldMode
import app.picacgmd.core.network.Category
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Page
import app.picacgmd.core.network.UserProfile

sealed interface RepositoryState<out T> {
    data object Idle : RepositoryState<Nothing>
    data object Loading : RepositoryState<Nothing>
    data class Ready<T>(val data: T) : RepositoryState<T>
    data class Failed(val message: String) : RepositoryState<Nothing>
}

data class FilteredComics(val items: List<ComicSimple>, val hiddenCount: Int) {
    val total: Int get() = items.size + hiddenCount
}

data class FilteredPage<T>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val pages: Int,
    val limit: Int,
    val hiddenCount: Int,
) {
    val hasNextPage: Boolean get() = page < pages
}

enum class HomeChannelKind { RANKINGS, RANDOM, GAMES, CATEGORY }

data class HomeChannel(val kind: HomeChannelKind, val title: String, val category: Category? = null)

data class HomeModel(
    val channels: List<HomeChannel>,
    val categories: List<Category>,
)

/** Server spelling is retained in requests; only the approved default's two spellings are aliases. */
internal fun categoryKey(name: String): String = when (val trimmed = name.trim()) {
    "大湿推荐", "大濕推薦", "大濕推荐", "大湿推薦" -> "大湿推荐"
    else -> trimmed
}

/** Marks of AI-made comics in Simplified and Traditional forms; also the 「AI作画」 group of [BlockPresets]. */
internal val aiComicMarkers = listOf(
    "AI作画", "AI作畫", "AI创作", "AI創作", "AI生成", "AI画作", "AI畫作", "AI绘画", "AI繪畫", "AI艺术", "AI藝術",
)

fun isAiComic(comic: ComicSimple): Boolean =
    (sequenceOf(comic.title) + comic.categories.asSequence() + comic.tags.asSequence()).any { text ->
        aiComicMarkers.any { marker -> text.contains(marker, ignoreCase = true) }
    }

fun isComicHidden(comic: ComicSimple, settings: AppSettings, viewedIds: Set<String> = emptySet()): Boolean =
    HideRules(settings).hides(comic, viewedIds)

fun filterComics(
    comics: List<ComicSimple>,
    settings: AppSettings,
    viewedIds: Set<String> = emptySet(),
): FilteredComics {
    val rules = HideRules(settings)
    val visible = comics.filterNot { rules.hides(it, viewedIds) }
    return FilteredComics(visible, comics.size - visible.size)
}

/** The hiding preferences, prepared once per list instead of once per comic. */
private class HideRules(private val settings: AppSettings) {
    private val shielded = settings.shieldedCategories.map(::categoryKey).toSet()
    private val keywords = settings.hiddenKeywords.map(String::trim).filter(String::isNotEmpty)

    fun hides(comic: ComicSimple, viewedIds: Set<String>): Boolean {
        val matchesCategory = comic.categories.any { categoryKey(it) in shielded }
        val categoryHidden = when (settings.categoryShieldMode) {
            CategoryShieldMode.BLACKLIST -> matchesCategory
            CategoryShieldMode.WHITELIST -> !matchesCategory
        }
        return categoryHidden || hiddenByKeyword(comic) || (settings.hideViewedComics && comic.id in viewedIds) ||
            (settings.hideAiComics && isAiComic(comic))
    }

    /** Keywords look at the title, the author, every category and every tag. */
    private fun hiddenByKeyword(comic: ComicSimple): Boolean {
        if (keywords.isEmpty()) return false
        val texts = sequenceOf(comic.title, comic.author) + comic.categories.asSequence() + comic.tags.asSequence()
        return texts.any { text -> keywords.any { keyword -> keywordMatches(text, keyword) } }
    }
}

/**
 * Whether the hidden [keyword] occurs in [text], ignoring case. A keyword made only of ASCII letters and
 * digits has to stand as a whole word, with no ASCII letter or digit right before or after it: 「BL」
 * finds 「BL」, 「[BL]」 and 「BL漫画」 but not 「Blue Archive」, and 「NTR」 does not find 「ENTRY」. Every
 * other keyword (Chinese, Japanese, or one with spaces or symbols) matches anywhere in the text.
 *
 * The boundary is checked by hand because `\b` differs between platforms: the JVM treats only ASCII as
 * word characters, while Android's ICU engine also counts Chinese ones, so 「NTR向」 would not match there.
 */
internal fun keywordMatches(text: String, keyword: String): Boolean {
    if (keyword.isEmpty()) return false
    if (!keyword.all(::isAsciiLetterOrDigit)) return text.contains(keyword, ignoreCase = true)
    var from = 0
    while (true) {
        val start = text.indexOf(keyword, from, ignoreCase = true)
        if (start < 0) return false
        val end = start + keyword.length
        val wordStart = start == 0 || !isAsciiLetterOrDigit(text[start - 1])
        val wordEnd = end == text.length || !isAsciiLetterOrDigit(text[end])
        if (wordStart && wordEnd) return true
        from = start + 1
    }
}

private fun isAsciiLetterOrDigit(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

internal fun Page<ComicSimple>.filtered(result: FilteredComics): FilteredPage<ComicSimple> =
    FilteredPage(result.items, total, page, pages, limit, result.hiddenCount)

fun ComicInfo.toSimple(): ComicSimple = ComicSimple(
    id = id, title = title, author = author, pagesCount = pagesCount, epsCount = epsCount,
    finished = finished, categories = categories, thumb = thumb, likesCount = likesCount, tags = tags,
)

/**
 * The uploader ([ComicInfo.creator], `_creator`) as the detail page shows it. Null when the server sent
 * none, or one without an id or a name: their comics are listed by the id, and the row shows the name.
 */
fun ComicInfo.uploader(): UserProfile? = creator?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }

fun buildHomeModel(
    categories: List<Category>,
    settings: AppSettings,
): HomeModel {
    val serverCategories = categories.filterNot { it.isWeb }
    val hidden = settings.hiddenCategories.map(::categoryKey).toSet()
    val order = settings.categoryOrder.map(::categoryKey).withIndex().associate { it.value to it.index }
    val visible = serverCategories.filterNot { categoryKey(it.title) in hidden }
        .sortedBy { order[categoryKey(it.title)] ?: Int.MAX_VALUE }
    val pins = settings.pinnedHomeCategories.mapNotNull { name ->
        serverCategories.firstOrNull { it.title == name }
            ?: serverCategories.firstOrNull { categoryKey(it.title) == categoryKey(name) }
    }.distinctBy { categoryKey(it.title) }
    // Hidden grid categories and pinned shortcuts are independent preferences (SPEC §0).
    val fixed = listOf(
        HomeChannel(HomeChannelKind.RANKINGS, "排行榜"),
        HomeChannel(HomeChannelKind.RANDOM, "随机"),
        HomeChannel(HomeChannelKind.GAMES, "游戏专区"),
    )
    return HomeModel(
        channels = fixed + pins.map { HomeChannel(HomeChannelKind.CATEGORY, it.title, it) },
        categories = visible,
    )
}
