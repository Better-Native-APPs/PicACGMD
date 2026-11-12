package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.SubscriptionDao
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.session.MemoryCredentialStore
import app.picacgmd.core.domain.session.MemorySessionPreferences
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.PicaClientFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dao: MemorySubscriptionDao

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = PicaClientFactory.apiClient()
        dao = MemorySubscriptionDao()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun appOpenChecksOnlyNeverCheckedAndStrictlyOlderThanSixHours() = runTest {
        val cutoff = NOW - SIX_HOURS
        val skipped = listOf(
            Subscription("boundary", "Boundary", lastCheckedAt = cutoff),
            Subscription("fresh", "Fresh", lastCheckedAt = NOW - 1, unseen = true, newEpCount = 3),
            Subscription("future", "Future", lastCheckedAt = NOW + 1),
        )
        listOf(
            Subscription("never", "Never"),
            Subscription("old", "Old", lastKnownEpsCount = 1, lastCheckedAt = cutoff - 1),
        ).plus(skipped).forEach { dao.upsert(it) }
        enqueueComic("never")
        enqueueComic("old")
        val repository = repository(backgroundScope)
        val states = mutableListOf<SubscriptionCheckState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.checkState.toList(states) }

        val report = repository.checkOnAppOpen()

        assertEquals(SubscriptionCheckReport(2, 2, emptyMap()), report)
        assertEquals(2, server.requestCount)
        assertEquals(NOW, dao.get("never")?.lastCheckedAt)
        assertEquals(NOW, dao.get("old")?.lastCheckedAt)
        skipped.forEach { assertEquals(it, dao.get(it.comicId)) }
        assertEquals(listOf(0, 1, 2), states.filterIsInstance<SubscriptionCheckState.Checking>().map { it.completed })
        assertTrue(states.filterIsInstance<SubscriptionCheckState.Checking>().all { it.total == 2 })
        assertEquals(SubscriptionCheckState.Done(report), repository.checkState.value)
    }

    @Test
    fun manualRefreshChecksRecentlyCheckedEntriesToo() = runTest {
        dao.upsert(Subscription("fresh", "Fresh", lastCheckedAt = NOW))
        dao.upsert(Subscription("boundary", "Boundary", lastCheckedAt = NOW - SIX_HOURS))
        enqueueComic("fresh")
        enqueueComic("boundary")
        val repository = repository(backgroundScope)

        val report = repository.refresh()

        assertEquals(2, report.checkedCount)
        assertEquals(2, server.requestCount)
        assertEquals(NOW, dao.get("boundary")?.lastCheckedAt)
        assertEquals(SubscriptionCheckState.Done(report), repository.checkState.value)
    }

    @Test
    fun anotherAppOpenSkipsSuccessfulChecksAndStillPublishesDone() = runTest {
        dao.upsert(Subscription("comic", "Comic"))
        enqueueComic("comic")
        val repository = repository(backgroundScope)
        assertEquals(1, repository.checkOnAppOpen().checkedCount)

        val report = repository.checkOnAppOpen()

        assertEquals(SubscriptionCheckReport(0, 0, emptyMap()), report)
        assertEquals(1, server.requestCount)
        assertEquals(SubscriptionCheckState.Done(report), repository.checkState.value)
    }

    @Test
    fun failedChecksRemainStaleAndKeepPerEntryFailureReporting() = runTest {
        dao.upsert(Subscription("comic", "Comic"))
        server.enqueue(MockResponse.Builder().code(500).body("unavailable").build())
        val repository = repository(backgroundScope)
        val failed = repository.checkOnAppOpen()
        assertEquals(0, failed.checkedCount)
        assertEquals(setOf("comic"), failed.failures.keys)
        assertNull(dao.get("comic")?.lastCheckedAt)
        assertEquals(SubscriptionCheckState.Done(failed), repository.checkState.value)

        enqueueComic("comic")
        assertEquals(1, repository.checkOnAppOpen().checkedCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun appOpenAndRefreshStopAfterThreeTransportFailuresAndReportSkippedEntries() = runTest {
        val entries = (1..5).map { Subscription("comic-$it", "Comic $it", lastCheckedAt = NOW - SIX_HOURS - 1) }
        entries.forEach { dao.upsert(it) }
        val lookups = AtomicInteger()
        client = client.newBuilder().addInterceptor {
            lookups.incrementAndGet()
            throw IOException("offline")
        }.build()
        val repository = repository(backgroundScope)
        val states = mutableListOf<SubscriptionCheckState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.checkState.toList(states) }

        repeat(2) { attempt ->
            states.clear()
            val report = if (attempt == 0) repository.checkOnAppOpen() else repository.refresh()

            assertEquals((attempt + 1) * 3, lookups.get())
            assertEquals(0, report.checkedCount)
            assertEquals(0, report.updatedCount)
            val lastFailure = report.failures.getValue(entries[2].comicId)
            assertEquals(entries.associate { it.comicId to lastFailure }, report.failures)
            entries.forEach { assertEquals(it, dao.get(it.comicId)) }
            assertEquals(entries.size, states.filterIsInstance<SubscriptionCheckState.Checking>().last().completed)
            assertTrue(states.filterIsInstance<SubscriptionCheckState.Checking>().all { it.total == entries.size })
            assertEquals(SubscriptionCheckState.Done(report), repository.checkState.value)
        }
    }

    @Test
    fun serverErrorResetsTheConsecutiveTransportFailureCounter() = runTest {
        val entries = (1..7).map { Subscription("comic-$it", "Comic $it") }
        entries.forEach { dao.upsert(it) }
        val lookups = AtomicInteger()
        client = client.newBuilder().addInterceptor { chain ->
            if (lookups.incrementAndGet() == 3) chain.proceed(chain.request()) else throw IOException("offline")
        }.build()
        server.enqueue(MockResponse.Builder().code(500).body("unavailable").build())
        val repository = repository(backgroundScope)

        val report = repository.refresh()

        assertEquals(6, lookups.get())
        assertEquals(1, server.requestCount)
        assertEquals(entries.map { it.comicId }.toSet(), report.failures.keys)
        assertTrue(report.failures.getValue("comic-3").contains("500"))
        assertEquals(report.failures.getValue("comic-6"), report.failures.getValue("comic-7"))
        entries.forEach { assertNull(dao.get(it.comicId)?.lastCheckedAt) }
        assertEquals(SubscriptionCheckState.Done(report), repository.checkState.value)
    }

    @Test
    fun successfulChecksResetTheCounterAndKeepUpdatingSubscriptions() = runTest {
        val entries = (1..6).map { Subscription("comic-$it", "Comic $it") }
        entries.forEach { dao.upsert(it) }
        val lookups = AtomicInteger()
        client = client.newBuilder().addInterceptor { chain ->
            if (lookups.incrementAndGet() % 3 == 0) chain.proceed(chain.request()) else throw IOException("offline")
        }.build()
        enqueueComic("comic-3")
        enqueueComic("comic-6")
        val repository = repository(backgroundScope)

        val report = repository.refresh()

        assertEquals(6, lookups.get())
        assertEquals(2, server.requestCount)
        assertEquals(2, report.checkedCount)
        assertEquals(2, report.updatedCount)
        assertEquals(setOf("comic-1", "comic-2", "comic-4", "comic-5"), report.failures.keys)
        listOf("comic-3", "comic-6").forEach { assertEquals(NOW, dao.get(it)?.lastCheckedAt) }
        report.failures.keys.forEach { assertNull(dao.get(it)?.lastCheckedAt) }
        assertEquals(SubscriptionCheckState.Done(report), repository.checkState.value)
    }

    private fun repository(scope: CoroutineScope): SubscriptionRepository {
        val preferences = MemorySessionPreferences(AppSettings(authToken = "token", autoPunchIn = false))
        lateinit var session: SessionManager
        val api = PicaApi(tokenProvider = { session.token }, baseUrl = server.url("/").toString(), client = client)
        session = SessionManager(preferences, MemoryCredentialStore(), { api }, scope)
        val comics = ComicRepository(session, preferences.settings, FakeApiCacheDao(), FakeSearchHistoryDao(), FakeViewHistoryDao())
        return SubscriptionRepository(dao, comics, preferences.settings, now = { NOW })
    }

    private fun enqueueComic(id: String) {
        server.enqueue(MockResponse.Builder().body("""{"code":200,"data":{"comic":{"_id":"$id","title":"Comic","epsCount":2}}}""").build())
    }

    companion object {
        private const val SIX_HOURS = 6L * 60 * 60 * 1_000
        private const val NOW = 48L * 60 * 60 * 1_000
    }
}

private class MemorySubscriptionDao : SubscriptionDao() {
    private val entries = MutableStateFlow<Map<String, Subscription>>(emptyMap())
    override fun observeAll() = entries.map { it.values.toList() }
    override fun observe(comicId: String) = entries.map { it[comicId] }
    override fun observeUnseenCount() = entries.map { it.values.count(Subscription::unseen) }
    override suspend fun get(comicId: String) = entries.value[comicId]
    override suspend fun upsert(subscription: Subscription) { entries.value += subscription.comicId to subscription }
    override suspend fun markSeen(comicId: String): Int {
        val current = get(comicId) ?: return 0
        upsert(current.copy(unseen = false, newEpCount = 0))
        return 1
    }
    override suspend fun delete(comicIds: List<String>): Int {
        val count = comicIds.distinct().count { it in entries.value }
        entries.value -= comicIds.toSet()
        return count
    }
    override suspend fun clear() { entries.value = emptyMap() }
}
