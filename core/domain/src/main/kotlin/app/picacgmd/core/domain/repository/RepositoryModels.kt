package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.CategoryShieldMode
import app.picacgmd.core.network.Category
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Page

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

data class CollectionGroup(val title: String, val comics: List<ComicSimple>, val hiddenCount: Int = 0)

enum class HomeChannelKind { RANKINGS, RANDOM, GAMES, CATEGORY }

data class HomeChannel(val kind: HomeChannelKind, val title: String, val category: Category? = null)

sealed interface HomeCategoryEntry {
    data object Recommendations : HomeCategoryEntry
    data class ServerCategory(val category: Category) : HomeCategoryEntry
}

data class HomeModel(
    val channels: List<HomeChannel>,
    val categories: List<Category>,
    val collections: List<CollectionGroup>,
    val carousel: List<ComicSimple>,
    val carouselHiddenCount: Int,
) {
    val showCarousel: Boolean get() = carousel.isNotEmpty()
    val categoryEntries: List<HomeCategoryEntry> get() =
        listOf(HomeCategoryEntry.Recommendations) + categories.map(HomeCategoryEntry::ServerCategory)
}

/** Server spelling is retained in requests; only the approved default's two spellings are aliases. */
internal fun categoryKey(name: String): String = when (val trimmed = name.trim()) {
    "大湿推荐", "大濕推薦", "大濕推荐", "大湿推薦" -> "大湿推荐"
    else -> trimmed
}

fun isComicHidden(comic: ComicSimple, settings: AppSettings, viewedIds: Set<String> = emptySet()): Boolean {
    val shielded = settings.shieldedCategories.map(::categoryKey).toSet()
    val matchesCategory = comic.categories.any { categoryKey(it) in shielded }
    val categoryHidden = when (settings.categoryShieldMode) {
        CategoryShieldMode.BLACKLIST -> matchesCategory
        CategoryShieldMode.WHITELIST -> !matchesCategory
    }
    val keywordHidden = settings.hiddenKeywords.any { word ->
        val keyword = word.trim()
        keyword.isNotEmpty() &&
            (comic.title.contains(keyword, ignoreCase = true) || comic.author.contains(keyword, ignoreCase = true))
    }
    return categoryHidden || keywordHidden || (settings.hideViewedComics && comic.id in viewedIds)
}

fun filterComics(
    comics: List<ComicSimple>,
    settings: AppSettings,
    viewedIds: Set<String> = emptySet(),
): FilteredComics {
    val visible = comics.filterNot { isComicHidden(it, settings, viewedIds) }
    return FilteredComics(visible, comics.size - visible.size)
}

internal fun Page<ComicSimple>.filtered(result: FilteredComics): FilteredPage<ComicSimple> =
    FilteredPage(result.items, total, page, pages, limit, result.hiddenCount)

fun ComicInfo.toSimple(): ComicSimple = ComicSimple(
    id = id, title = title, author = author, pagesCount = pagesCount, epsCount = epsCount,
    finished = finished, categories = categories, thumb = thumb, likesCount = likesCount,
)

fun buildHomeModel(
    categories: List<Category>,
    collections: List<CollectionGroup>,
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
        collections = collections,
        carousel = collections.firstOrNull()?.comics.orEmpty(),
        carouselHiddenCount = collections.firstOrNull()?.hiddenCount ?: 0,
    )
}
