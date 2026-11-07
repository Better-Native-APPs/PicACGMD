package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ApiCacheDao
import app.picacgmd.core.data.db.SearchHistory
import app.picacgmd.core.data.db.SearchHistoryDao
import app.picacgmd.core.data.db.ViewHistoryDao
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.Category
import app.picacgmd.core.network.ComicCollection
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.ComicSort
import app.picacgmd.core.network.Ep
import app.picacgmd.core.network.ImageQuality
import app.picacgmd.core.network.Knight
import app.picacgmd.core.network.LeaderboardType
import app.picacgmd.core.network.Page
import app.picacgmd.core.network.PicturePage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class ComicRepository(
    private val session: SessionManager,
    private val settings: Flow<AppSettings>,
    cacheDao: ApiCacheDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val viewHistoryDao: ViewHistoryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val catalogueCache = CatalogueCache(cacheDao, now)
    val searchHistory: Flow<List<SearchHistory>> = searchHistoryDao.observeAll()

    suspend fun categories(forceRefresh: Boolean = false): List<Category> =
        catalogueCache.categories(forceRefresh) { session.withApi { it.categories() } }

    suspend fun collections(forceRefresh: Boolean = false): List<CollectionGroup> =
        filterCollections(rawCollections(forceRefresh), settings.first())

    internal suspend fun rawCollections(forceRefresh: Boolean = false): List<ComicCollection> =
        catalogueCache.collections(forceRefresh) { session.withApi { it.collections() } }

    internal suspend fun filterCollections(
        collections: List<ComicCollection>,
        preferences: AppSettings,
    ): List<CollectionGroup> = collections.map { group ->
        val filtered = filter(group.comics, preferences)
        CollectionGroup(group.title, filtered.items, filtered.hiddenCount)
    }

    suspend fun comics(
        category: String? = null,
        tag: String? = null,
        author: String? = null,
        creatorId: String? = null,
        chineseTeam: String? = null,
        sort: ComicSort = ComicSort.NEWEST,
        page: Int = 1,
    ): FilteredPage<ComicSimple> {
        require(page >= 1) { "页码必须从 1 开始" }
        val result = session.withApi { it.comics(category, tag, author, creatorId, chineseTeam, sort, page) }
        return result.filtered(filter(result.docs))
    }

    suspend fun search(
        keyword: String,
        sort: ComicSort = ComicSort.NEWEST,
        page: Int = 1,
        categories: List<String> = emptyList(),
    ): FilteredPage<ComicSimple> {
        require(keyword.isNotBlank()) { "搜索关键词不能为空" }
        require(page >= 1) { "页码必须从 1 开始" }
        val query = keyword.trim()
        searchHistoryDao.upsert(SearchHistory(query, now()))
        val result = session.withApi { it.searchComics(query, sort, page, categories) }
        return result.filtered(filter(result.docs))
    }

    suspend fun deleteSearchHistory(queries: List<String>): Int = searchHistoryDao.delete(queries)
    suspend fun clearSearchHistory() = searchHistoryDao.clear()

    suspend fun comicInfo(comicId: String): ComicInfo = session.withApi { it.comicInfo(comicId) }

    suspend fun eps(comicId: String, page: Int = 1): Page<Ep> {
        require(page >= 1) { "页码必须从 1 开始" }
        return session.withApi { it.comicEpPage(comicId, page) }
    }

    suspend fun allEps(comicId: String): List<Ep> {
        val episodes = mutableListOf<Ep>()
        var page = 1
        do {
            val result = eps(comicId, page)
            episodes += result.docs
            page++
        } while (page <= result.pages)
        return episodes.distinctBy(Ep::id).sortedBy(Ep::order)
    }

    suspend fun pictures(
        comicId: String,
        epOrder: Int,
        page: Int = 1,
        quality: ImageQuality? = null,
    ): PicturePage {
        require(epOrder >= 1 && page >= 1) { "章节序号和页码必须从 1 开始" }
        val selectedQuality = quality ?: ImageQuality.valueOf(settings.first().imageQuality.name)
        return session.withApi { it.comicPicturePageWithQuality(comicId, epOrder, page, selectedQuality) }
    }

    suspend fun recommendations(comicId: String): FilteredComics =
        filter(session.withApi { it.recommendation(comicId) })

    suspend fun toggleLike(comicId: String): Boolean = session.withApi { it.switchLike(comicId) } == "like"
    suspend fun toggleFavourite(comicId: String): Boolean = session.withApi { it.switchFavourite(comicId) } == "favourite"

    suspend fun favourites(sort: ComicSort = ComicSort.NEWEST, page: Int = 1): FilteredPage<ComicSimple> {
        require(page >= 1) { "页码必须从 1 开始" }
        val result = session.withApi { it.favouriteComics(sort, page) }
        return result.filtered(filter(result.docs))
    }

    suspend fun rankings(type: LeaderboardType = LeaderboardType.H24): FilteredComics =
        filter(session.withApi { it.leaderboard(type) })

    suspend fun knights(): List<Knight> = session.withApi { it.leaderboardOfKnight() }
    suspend fun random(): FilteredComics = filter(session.withApi { it.randomComics() })
    suspend fun hotKeywords(): List<String> = session.withApi { it.hotKeywords() }

    suspend fun isHidden(comic: ComicInfo): Boolean = filter(listOf(comic.toSimple())).hiddenCount > 0

    suspend fun filter(comics: List<ComicSimple>, preferences: AppSettings? = null): FilteredComics {
        val snapshot = preferences ?: settings.first()
        val viewed = if (snapshot.hideViewedComics && comics.isNotEmpty())
            viewHistoryDao.observeViewedIds(comics.map(ComicSimple::id)).first().toSet() else emptySet()
        return filterComics(comics, snapshot, viewed)
    }
}
