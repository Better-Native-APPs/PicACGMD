package app.picacgmd.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.picacgmd.core.data.db.ApiCache
import app.picacgmd.core.data.db.ApiCacheDao
import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadCounts
import app.picacgmd.core.data.db.DownloadDao
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadPicture
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.db.ReadChapter
import app.picacgmd.core.data.db.SearchHistory
import app.picacgmd.core.data.db.SearchHistoryDao
import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.SubscriptionDao
import app.picacgmd.core.data.db.TagFavourite
import app.picacgmd.core.data.db.TagFavouriteDao
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.data.db.ViewHistoryDao
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.download.DownloadFileStore
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.repository.ComicRepository
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.domain.repository.HistoryRepository
import app.picacgmd.core.domain.repository.SubscriptionRepository
import app.picacgmd.core.domain.repository.TagFavouriteRepository
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.PicaApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import sun.misc.Unsafe

// Only the supplied fields are usable: Android assembly, network watching and work scheduling are not exercised.
private fun <T : Any> constructorFreeInstance(type: Class<T>): T {
    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    return checkNotNull(type.cast(unsafe.allocateInstance(type)))
}

private fun Any.setFixtureField(name: String, value: Any) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

internal class TestAppEnvironment(
    pica: TestPicaServer,
    scope: CoroutineScope,
    defaults: AppSettings = AppSettings(autoPunchIn = false),
    beforeApi: (suspend (Int) -> Unit)? = null,
) {
    val preferences = MemoryPreferencesStore()
    val settings = SettingsRepository(preferences, defaults)
    val historyDao = MemoryHistoryDao()
    val subscriptionDao = MemoryShelfSubscriptionDao()
    val downloadDao = ReadOnlyDownloadDao()
    val cacheDao = MemoryCatalogueDao()
    val searchHistoryDao = MemorySearchDao()
    private val session = if (beforeApi == null) pica.session(scope) else {
        lateinit var session: SessionManager
        val api = PicaApi(tokenProvider = { session.token }, baseUrl = pica.server.url("/").toString())
        val calls = AtomicInteger()
        session = SessionManager(
            pica.preferences, MemoryCredentialStore(), { beforeApi(calls.incrementAndGet()); api }, scope,
        )
        session
    }
    val comics = ComicRepository(session, settings.settings, cacheDao, searchHistoryDao, historyDao)
    val history = HistoryRepository(historyDao, settings.settings, now = { 9_000L })
    val subscriptions = SubscriptionRepository(subscriptionDao, comics, settings.settings, now = { 9_000L })
    val downloads = constructorFreeInstance(DownloadManager::class.java).apply {
        setFixtureField("dao", downloadDao)
    }
    val container = constructorFreeInstance(AppContainer::class.java).apply {
        setFixtureField("settingsRepository", settings)
        setFixtureField("comicRepository", comics)
        setFixtureField("commentRepository", CommentRepository(session))
        setFixtureField("historyRepository", history)
        setFixtureField("subscriptionRepository", subscriptions)
        setFixtureField("tagFavouriteRepository", TagFavouriteRepository(MemoryTagDao()))
    }

    fun useFiles(files: DownloadFileStore) { downloads.setFixtureField("files", files) }
}

internal class MemoryPreferencesStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}

internal class MemoryHistoryDao : ViewHistoryDao {
    val entries = MutableStateFlow<Map<String, ViewHistory>>(emptyMap())
    val upserts = CopyOnWriteArrayList<ViewHistory>()
    val writes = Channel<ViewHistory>(Channel.UNLIMITED)
    var beforeGet: suspend () -> Unit = {}
    var beforeUpsert: suspend () -> Unit = {}
    override fun observeAll() = entries.map { it.values.toList() }
    override fun observePage(limit: Int, offset: Int) = observeAll().map { it.drop(offset).take(limit) }
    override fun observe(comicId: String) = entries.map { it[comicId] }
    override fun observeViewedIds(comicIds: List<String>) = entries.map { rows -> comicIds.filter { it in rows } }
    override fun observeReadChapters(comicIds: List<String>) = entries.map { rows ->
        comicIds.mapNotNull { rows[it]?.let { row -> ReadChapter(row.comicId, row.lastEpOrder) } }
    }
    override suspend fun get(comicId: String): ViewHistory? { beforeGet(); return entries.value[comicId] }
    override suspend fun upsert(history: ViewHistory) {
        beforeUpsert()
        upserts += history
        entries.value += history.comicId to history
        writes.send(history)
    }
    override suspend fun updateProgress(comicId: String, epOrder: Int, epTitle: String, pictureIndex: Int, pageCount: Int, updatedAt: Long): Int {
        val previous = get(comicId) ?: return 0
        upsert(previous.copy(lastEpOrder = epOrder, lastEpTitle = epTitle, lastPictureIndex = pictureIndex,
            lastChapterPages = pageCount, updatedAt = updatedAt))
        return 1
    }
    override suspend fun setPinned(comicId: String, pinned: Boolean): Int {
        val previous = get(comicId) ?: return 0
        upsert(previous.copy(pinned = pinned))
        return 1
    }
    override suspend fun delete(comicIds: List<String>): Int {
        val count = comicIds.distinct().count { it in entries.value }
        entries.value -= comicIds.toSet()
        return count
    }
    override suspend fun clear() { entries.value = emptyMap() }
}

internal class MemoryShelfSubscriptionDao : SubscriptionDao() {
    val entries = MutableStateFlow<Map<String, Subscription>>(emptyMap())
    val upserts = CopyOnWriteArrayList<Subscription>()
    var beforeUpsert: suspend () -> Unit = {}
    override fun observeAll() = entries.map { it.values.toList() }
    override fun observe(comicId: String) = entries.map { it[comicId] }
    override fun observeUnseenCount() = entries.map { it.values.count(Subscription::unseen) }
    override suspend fun get(comicId: String) = entries.value[comicId]
    override suspend fun upsert(subscription: Subscription) {
        beforeUpsert()
        upserts += subscription
        entries.value += subscription.comicId to subscription
    }
    override suspend fun markSeen(comicId: String): Int {
        val previous = get(comicId) ?: return 0
        upsert(previous.copy(unseen = false, newEpCount = 0))
        return 1
    }
    override suspend fun delete(comicIds: List<String>): Int {
        val count = comicIds.distinct().count { it in entries.value }
        entries.value -= comicIds.toSet()
        return count
    }
    override suspend fun clear() { entries.value = emptyMap() }
}

internal class MemoryCatalogueDao : ApiCacheDao {
    private val entries = MutableStateFlow<Map<String, ApiCache>>(emptyMap())
    override fun observe(key: String) = entries.map { it[key] }
    override suspend fun getFresh(key: String, now: Long) = entries.value[key]?.takeUnless { it.isExpired(now) }
    override suspend fun upsert(cache: ApiCache) { entries.value += cache.key to cache }
    override suspend fun delete(key: String): Int {
        val existed = key in entries.value
        entries.value -= key
        return if (existed) 1 else 0
    }
    override suspend fun deletePrefix(prefix: String): Int = deleteMatching { it.key.startsWith(prefix) }
    override suspend fun deleteExpired(now: Long): Int = deleteMatching { it.isExpired(now) }
    override suspend fun clear() { entries.value = emptyMap() }
    private fun deleteMatching(predicate: (ApiCache) -> Boolean): Int {
        val keys = entries.value.values.filter(predicate).map(ApiCache::key)
        entries.value -= keys.toSet()
        return keys.size
    }
}

internal class MemorySearchDao : SearchHistoryDao {
    private val entries = MutableStateFlow<List<SearchHistory>>(emptyList())
    override fun observeAll() = entries
    override suspend fun upsert(history: SearchHistory) { entries.value = entries.value.filterNot { it.query == history.query } + history }
    override suspend fun delete(queries: List<String>): Int {
        val previous = entries.value
        entries.value = previous.filterNot { it.query in queries }
        return previous.size - entries.value.size
    }
    override suspend fun clear() { entries.value = emptyList() }
}

private class MemoryTagDao : TagFavouriteDao {
    override fun observeAll(): Flow<List<TagFavourite>> = flowOf(emptyList())
    override suspend fun upsert(favourite: TagFavourite) = error("Unexpected tag write")
    override suspend fun delete(tags: List<String>): Int = error("Unexpected tag deletion")
    override suspend fun clear() = error("Unexpected tag deletion")
}

internal class ReadOnlyDownloadDao : DownloadDao() {
    val comics = MutableStateFlow<Map<String, DownloadComic>>(emptyMap())
    val chapters = MutableStateFlow<List<DownloadEp>>(emptyList())
    val pictures = MutableStateFlow<List<DownloadPicture>>(emptyList())
    val chapterReads = AtomicInteger()
    val pictureReads = AtomicInteger()
    val activePictureReads = AtomicInteger()
    override fun observeComic(comicId: String) = comics.map { it[comicId] }
    override suspend fun getComic(comicId: String) = comics.value[comicId]
    override fun observeEps(comicId: String) = chapters.map { rows -> rows.filter { it.comicId == comicId }.sortedBy { it.epOrder } }
        .onStart { chapterReads.incrementAndGet() }
    override fun observePictures(epId: String) = pictures.map { rows -> rows.filter { it.epId == epId }.sortedBy { it.pictureIndex } }
        .onStart { pictureReads.incrementAndGet(); activePictureReads.incrementAndGet() }
        .onCompletion { activePictureReads.decrementAndGet() }
    override fun observeComics(folder: String?, search: String): Flow<List<DownloadComic>> = error("Unexpected queue read")
    override fun observeFolders(): Flow<List<String>> = error("Unexpected folder read")
    override suspend fun getNextComic(): DownloadComic? = error("Unexpected queue read")
    override fun observeDeletingComics(): Flow<List<DownloadComic>> = error("Unexpected deletion read")
    override suspend fun getDeletingComics(): List<DownloadComic> = error("Unexpected deletion read")
    override suspend fun getEp(epId: String): DownloadEp? = error("Unexpected chapter lookup")
    override suspend fun getNextUnfetchedEp(comicId: String): DownloadEp? = error("Unexpected chapter lookup")
    override suspend fun getPicture(pictureId: String): DownloadPicture? = error("Unexpected picture lookup")
    override fun observeNextPendingPictures(comicId: String, limit: Int): Flow<List<DownloadPicture>> = error("Unexpected queue read")
    override suspend fun nextPendingPictures(comicId: String, limit: Int): List<DownloadPicture> = error("Unexpected queue read")
    override fun observeCounts(comicId: String): Flow<DownloadCounts?> = error("Unexpected counts read")
    override suspend fun getCounts(comicId: String): DownloadCounts? = error("Unexpected counts read")
    override suspend fun upsertComic(comic: DownloadComic) = error("Unexpected download write")
    override suspend fun upsertEps(eps: List<DownloadEp>) = error("Unexpected download write")
    override suspend fun upsertPictures(pictures: List<DownloadPicture>) = error("Unexpected download write")
    override suspend fun claimPicture(pictureId: String, updatedAt: Long): Int = error("Unexpected download write")
    override suspend fun updatePictures(pictures: List<DownloadPicture>): Int = error("Unexpected download write")
    override suspend fun refreshEpProgress(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun refreshComicProgress(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun changeComicStatus(comicId: String, expected: DownloadStatus, next: DownloadStatus, error: String?, updatedAt: Long, completedAt: Long?): Int = error("Unexpected download write")
    override suspend fun markEpPicturesDeleting(epId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun requeueFailedEpPictures(epId: String, updatedAt: Long): Int = error("Unexpected download write")
    override suspend fun deleteEp(epId: String): Int = error("Unexpected download write")
    override suspend fun pauseEps(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun pausePictures(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun requeueEps(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun requeuePictures(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun markEpsDeleting(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun markPicturesDeleting(comicId: String, updatedAt: Long) = error("Unexpected download write")
    override suspend fun requeueFailedPictures(updatedAt: Long): Int = error("Unexpected download write")
    override suspend fun requeueFailedEps(updatedAt: Long) = error("Unexpected download write")
    override suspend fun requeueFailedComics(updatedAt: Long) = error("Unexpected download write")
    override suspend fun recoverRunningPictures(updatedAt: Long): Int = error("Unexpected download write")
    override suspend fun recoverRunningEps(updatedAt: Long) = error("Unexpected download write")
    override suspend fun recoverRunningComics(updatedAt: Long) = error("Unexpected download write")
    override suspend fun moveComics(comicIds: List<String>, folder: String, updatedAt: Long): Int = error("Unexpected download write")
    override suspend fun deleteComic(comicId: String): Int = error("Unexpected download write")
}

internal fun successResponse(data: String): MockResponse = MockResponse.Builder()
    .body("""{"code":200,"message":"success","data":$data}""").build()

internal fun failureResponse(): MockResponse = MockResponse.Builder().code(500)
    .body("""{"code":500,"message":"failure"}""").build()

internal class ResponseGate(private val response: MockResponse) {
    private val release = CountDownLatch(1)
    fun open() = release.countDown()
    fun awaitResponse(): MockResponse {
        check(release.await(5, TimeUnit.SECONDS)) { "response gate was not released" }
        return response
    }
}

internal fun TestPicaServer.respondWith(respond: (RecordedRequest) -> MockResponse) {
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
    }
}

internal suspend fun <T> awaitRealTime(block: suspend () -> T): T = withContext(Dispatchers.Default) {
    withTimeout(5_000) { block() }
}

internal suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T = awaitRealTime { first(predicate) }
