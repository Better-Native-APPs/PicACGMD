package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ApiCache
import app.picacgmd.core.network.Category
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CatalogueCacheTest {
    @Test
    fun categoriesExpireAtExactlyThreeHours() = runTest {
        var now = 1_000L
        var fetches = 0
        val dao = FakeApiCacheDao()
        val cache = CatalogueCache(dao) { now }
        val fetch: suspend () -> List<Category> = { listOf(Category(title = "v${++fetches}")) }
        assertEquals("v1", cache.categories(fetch = fetch).single().title)
        assertEquals(1_000L + 10_800_000L, dao.entries.value.getValue(ApiCache.CATEGORIES_KEY).expiresAt)
        now += 10_800_000L - 1
        assertEquals("v1", cache.categories(fetch = fetch).single().title)
        now++
        assertEquals("v2", cache.categories(fetch = fetch).single().title)
        assertEquals(2, fetches)
    }

    @Test
    fun categoriesUseThePersistedCacheAcrossInstances() = runTest {
        val dao = FakeApiCacheDao()
        val cache = CatalogueCache(dao) { 2_000L }
        cache.categories { listOf(Category(title = "分类")) }
        val restored = CatalogueCache(dao) { 3_000L }
        assertEquals("分类", restored.categories { error("Must use cache") }.single().title)
        assertEquals(setOf(ApiCache.CATEGORIES_KEY), dao.entries.value.keys)
        assertEquals(10_802_000L, dao.entries.value.getValue(ApiCache.CATEGORIES_KEY).expiresAt)
    }

    @Test
    fun forceRefreshReplacesEvenFreshEntries() = runTest {
        val dao = FakeApiCacheDao()
        val cache = CatalogueCache(dao) { 1_000L }
        cache.categories { listOf(Category(title = "旧")) }
        assertEquals("新", cache.categories(forceRefresh = true) { listOf(Category(title = "新")) }.single().title)
        assertEquals("新", cache.categories { error("Must use cache") }.single().title)
    }

    @Test
    fun failedRefreshDoesNotOverwriteTheLastCache() = runTest {
        val dao = FakeApiCacheDao()
        val cache = CatalogueCache(dao) { 1_000L }
        cache.categories { listOf(Category(title = "缓存")) }
        val before = dao.entries.value
        assertFailsWith<IOException> { cache.categories(forceRefresh = true) { throw IOException("offline") } }
        assertEquals(before, dao.entries.value)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentCacheMissesShareOneFetch() = runTest {
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val cache = CatalogueCache(FakeApiCacheDao()) { 1_000L }
        val fetch: suspend () -> List<Category> = { fetches++; release.await(); emptyList() }
        val first = async { cache.categories(fetch = fetch) }
        val second = async { cache.categories(fetch = fetch) }
        runCurrent()
        assertEquals(1, fetches)
        release.complete(Unit)
        assertEquals(emptyList(), first.await())
        assertEquals(emptyList(), second.await())
        assertEquals(1, fetches)
    }
}
