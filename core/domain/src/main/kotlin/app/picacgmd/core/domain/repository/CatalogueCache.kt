package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ApiCache
import app.picacgmd.core.data.db.ApiCacheDao
import app.picacgmd.core.network.Category
import app.picacgmd.core.network.ComicCollection
import app.picacgmd.core.network.PicaJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Cache the unfiltered catalogue, so preference changes never require a network refresh. */
class CatalogueCache(private val dao: ApiCacheDao, private val now: () -> Long = System::currentTimeMillis) {
    private val categoriesMutex = Mutex()
    private val collectionsMutex = Mutex()

    suspend fun categories(forceRefresh: Boolean = false, fetch: suspend () -> List<Category>): List<Category> =
        categoriesMutex.withLock {
            read(ApiCache.CATEGORIES_KEY, ListSerializer(Category.serializer()), forceRefresh, ApiCache.Companion::categories, fetch)
        }

    suspend fun collections(
        forceRefresh: Boolean = false,
        fetch: suspend () -> List<ComicCollection>,
    ): List<ComicCollection> = collectionsMutex.withLock {
        read(ApiCache.COLLECTIONS_KEY, ListSerializer(ComicCollection.serializer()), forceRefresh, ApiCache.Companion::collections, fetch)
    }

    private suspend fun <T> read(
        key: String,
        serializer: KSerializer<T>,
        forceRefresh: Boolean,
        makeCache: (String, Long) -> ApiCache,
        fetch: suspend () -> T,
    ): T {
        if (!forceRefresh) dao.getFresh(key, now())?.let { cached ->
            return PicaJson.decodeFromString(serializer, cached.json)
        }
        val value = fetch()
        dao.upsert(makeCache(PicaJson.encodeToString(serializer, value), now()))
        return value
    }
}
