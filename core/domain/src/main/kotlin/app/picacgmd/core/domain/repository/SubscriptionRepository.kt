package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.Subscription
import app.picacgmd.core.data.db.SubscriptionDao
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.ComicInfo
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val APP_OPEN_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1_000

data class SubscriptionCheckReport(val checkedCount: Int, val updatedCount: Int, val failures: Map<String, String>)

sealed interface SubscriptionCheckState {
    data object Idle : SubscriptionCheckState
    data class Checking(val completed: Int, val total: Int) : SubscriptionCheckState
    data class Done(val report: SubscriptionCheckReport) : SubscriptionCheckState
    data class Failed(val message: String) : SubscriptionCheckState
}

/** No timer, worker, or notification: checks are explicit app-open/pull-to-refresh actions (B5). */
class SubscriptionRepository(
    private val dao: SubscriptionDao,
    private val comics: ComicRepository,
    settings: Flow<AppSettings>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val checkMutex = Mutex()
    private val mutableCheckState = MutableStateFlow<SubscriptionCheckState>(SubscriptionCheckState.Idle)
    val checkState: StateFlow<SubscriptionCheckState> = mutableCheckState.asStateFlow()
    val subscriptions: Flow<List<Subscription>> = dao.observeAll()
    val unseenCount: Flow<Int> = combine(dao.observeUnseenCount(), settings) { count, preferences ->
        if (preferences.showSubscriptions) count else 0
    }

    fun observe(comicId: String): Flow<Subscription?> = dao.observe(comicId)

    suspend fun subscribe(comic: ComicInfo) {
        val previous = dao.get(comic.id)
        val timestamp = now()
        val subscription = previous ?: Subscription(
            comicId = comic.id, title = comic.title, lastKnownEpsCount = comic.epsCount,
            lastKnownUpdatedAt = parseComicUpdatedAt(comic.updatedAt), createdAt = timestamp,
        )
        dao.upsert(
            subscription.copy(
                title = comic.title, author = comic.author,
                thumbFileServer = comic.thumb?.fileServer.orEmpty(), thumbPath = comic.thumb?.path.orEmpty(),
                updatedAt = timestamp,
            ),
        )
    }

    suspend fun unsubscribe(comicIds: List<String>): Int = dao.delete(comicIds)
    suspend fun markSeen(comicId: String): Int = dao.markSeen(comicId)
    suspend fun clear() = dao.clear()
    suspend fun checkOnAppOpen(): SubscriptionCheckReport = check(staleOnly = true)
    suspend fun refresh(): SubscriptionCheckReport = check(staleOnly = false)

    private suspend fun check(staleOnly: Boolean): SubscriptionCheckReport = checkMutex.withLock {
        try {
            val subscribed = dao.observeAll().first().let { entries ->
                if (staleOnly) {
                    val cutoff = now() - APP_OPEN_CHECK_INTERVAL_MILLIS
                    entries.filter { entry -> entry.lastCheckedAt?.let { it < cutoff } ?: true }
                } else entries
            }
            var updated = 0
            var checked = 0
            val failures = linkedMapOf<String, String>()
            mutableCheckState.value = SubscriptionCheckState.Checking(0, subscribed.size)
            subscribed.forEachIndexed { index, entry ->
                try {
                    val latest = comics.comicInfo(entry.comicId)
                    val checkedAt = now()
                    val updateTime = parseComicUpdatedAt(latest.updatedAt)
                    val previousUpdateTime = entry.lastKnownUpdatedAt
                    val changed = latest.epsCount > entry.lastKnownEpsCount ||
                        (updateTime != null && previousUpdateTime != null && updateTime > previousUpdateTime)
                    if (dao.applyUpdate(entry.comicId, latest.epsCount, updateTime, checkedAt)) {
                        checked++
                        if (changed) updated++
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    failures[entry.comicId] = exception.message ?: "更新检查失败"
                }
                mutableCheckState.value = SubscriptionCheckState.Checking(index + 1, subscribed.size)
            }
            SubscriptionCheckReport(checked, updated, failures).also {
                mutableCheckState.value = SubscriptionCheckState.Done(it)
            }
        } catch (exception: CancellationException) {
            mutableCheckState.value = SubscriptionCheckState.Idle
            throw exception
        } catch (exception: Exception) {
            mutableCheckState.value = SubscriptionCheckState.Failed(exception.message ?: "更新检查失败")
            throw exception
        }
    }
}

internal fun parseComicUpdatedAt(value: String?): Long? {
    if (value == null) return null
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
