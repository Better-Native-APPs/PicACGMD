package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.session.MemoryCredentialStore
import app.picacgmd.core.domain.session.MemorySessionPreferences
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.ComicSort
import app.picacgmd.core.network.PicaApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class ComicRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var preferences: MemorySessionPreferences
    private lateinit var searches: FakeSearchHistoryDao
    private lateinit var history: FakeViewHistoryDao

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        preferences = MemorySessionPreferences(AppSettings(authToken = "token", autoPunchIn = false))
        searches = FakeSearchHistoryDao()
        history = FakeViewHistoryDao()
    }

    @AfterTest
    fun tearDown() { server.close() }

    @Test
    fun searchRecordsHistoryAndRetainsRemotePagingMetadataAfterFiltering() = runTest {
        preferences.settings.value = preferences.settings.value.copy(hiddenKeywords = listOf("hidden"))
        enqueue("""{"comics":{"docs":[{"_id":"1","title":"visible"},{"_id":"2","title":"Hidden story"}],"page":12,"pages":20,"total":128,"limit":40}}""")
        val result = repository(backgroundScope).search("  query  ", page = 12)
        assertEquals(listOf("1"), result.items.map { it.id })
        assertEquals(1, result.hiddenCount)
        assertEquals(128, result.total)
        assertEquals(12, result.page)
        assertEquals(20, result.pages)
        assertEquals(40, result.limit)
        assertEquals("query", searches.entries.value.single().query)
    }

    @Test
    fun homeOnlyFetchesCategoriesAndReappliesPreferencesWithoutAnotherRequest() = runTest {
        val home = HomeRepository(repository(backgroundScope), preferences.settings, backgroundScope)
        enqueue("""{"categories":[{"_id":"1","title":"日常"},{"_id":"2","title":"冒险"}]}""")
        assertEquals(listOf("全分类", "日常", "冒险"), home.refresh().categories.map { it.title })
        assertEquals("/categories", server.takeRequest().url.encodedPath)
        preferences.settings.value = preferences.settings.value.copy(
            hiddenCategories = listOf("冒险"), pinnedHomeCategories = listOf("冒险"),
        )
        val updated = home.state.filterIsInstance<RepositoryState.Ready<HomeModel>>()
            .first { it.data.categories.map { category -> category.title } == listOf("全分类", "日常") }.data
        assertEquals("冒险", updated.channels.last().title)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun allComicsRequestsOmitFiltersAndReuseEverySort() = runTest {
        val repository = repository(backgroundScope)
        ComicSort.entries.forEach { sort ->
            enqueue("""{"comics":{"docs":[{"_id":"comic","title":"漫画"}],"page":3,"pages":5,"total":84,"limit":20}}""")
            val result = repository.comics(sort = sort, page = 3)
            val request = server.takeRequest()
            assertEquals("/comics", request.url.encodedPath)
            assertEquals(setOf("s", "page"), request.url.queryParameterNames)
            assertEquals(sort.value, request.url.queryParameter("s"))
            assertEquals("3", request.url.queryParameter("page"))
            assertEquals(listOf("comic"), result.items.map { it.id })
            assertEquals(84, result.total)
            assertEquals(3, result.page)
        }
    }

    @Test
    fun hideViewedUsesTheHistoryDao() = runTest {
        preferences.settings.value = preferences.settings.value.copy(hideViewedComics = true)
        history.upsert(ViewHistory("read", "读过"))
        enqueue("""{"comics":[{"_id":"read","title":"读过"},{"_id":"new","title":"未读"}]}""")
        val result = repository(backgroundScope).random()
        assertEquals(listOf("new"), result.items.map { it.id })
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun aiFilterUsesOptionalTagsAndRetainsRemotePageMetadata() = runTest {
        preferences.settings.value = preferences.settings.value.copy(hideAiComics = true)
        enqueue("""{"comics":{"docs":[{"_id":"title","title":"AI作画"},
            {"_id":"category","categories":["AI藝術"]},{"_id":"tag","tags":["ai生成"]},
            {"_id":"visible","title":"日常"}],"page":2,"pages":5,"total":84,"limit":20}}""")
        val result = repository(backgroundScope).comics(page = 2)
        assertEquals(listOf("visible"), result.items.map { it.id })
        assertEquals(3, result.hiddenCount)
        assertEquals(84, result.total)
        assertEquals(2, result.page)
        assertEquals(5, result.pages)
        assertEquals(20, result.limit)
    }

    private fun repository(scope: CoroutineScope): ComicRepository {
        lateinit var session: SessionManager
        val api = PicaApi(tokenProvider = { session.token }, baseUrl = server.url("/").toString())
        session = SessionManager(preferences, MemoryCredentialStore(), { api }, scope)
        return ComicRepository(session, preferences.settings, FakeApiCacheDao(), searches, history)
    }

    private fun enqueue(data: String) { server.enqueue(MockResponse.Builder().body("""{"code":200,"data":$data}""").build()) }
}
