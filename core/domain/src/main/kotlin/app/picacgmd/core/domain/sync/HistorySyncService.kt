package app.picacgmd.core.domain.sync

import app.picacgmd.core.data.settings.WebDavSettings
import app.picacgmd.core.network.webdav.WebDavAccount
import app.picacgmd.core.network.webdav.WebDavConnection
import app.picacgmd.core.network.webdav.WebDavEndpoint
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException
import app.picacgmd.core.network.webdav.WebDavResult
import app.picacgmd.core.network.webdav.WebDavTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class HistorySyncPhase { CONNECTING, DOWNLOADING, MERGING, WRITING_LOCAL, UPLOADING, RETRYING }

sealed interface HistorySyncActivity {
    data object Idle : HistorySyncActivity
    data object Testing : HistorySyncActivity
    data class Syncing(val direction: HistorySyncDirection, val phase: HistorySyncPhase, val automatic: Boolean) : HistorySyncActivity
}

sealed interface HistorySyncOutcome {
    data class Success(val counts: HistorySyncCounts) : HistorySyncOutcome
    data class Failure(
        val error: WebDavError,
        val httpStatus: Int? = null,
        val counts: HistorySyncCounts = HistorySyncCounts(),
        val localChanged: Boolean = false,
        val remoteWritten: Boolean = false,
    ) : HistorySyncOutcome
    data object Skipped : HistorySyncOutcome
}

data class HistorySyncStatus(
    val config: WebDavSettings = WebDavSettings(),
    val hasPassword: Boolean = false,
    val ready: Boolean = false,
    val configurationError: WebDavError? = null,
    val activity: HistorySyncActivity = HistorySyncActivity.Idle,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastResult: HistorySyncOutcome? = null,
)

interface HistorySyncActions {
    val state: StateFlow<HistorySyncStatus>
    suspend fun saveAccount(address: String, username: String, folder: String, password: String? = null): WebDavResult<Unit>
    suspend fun setAutoSync(enabled: Boolean): WebDavResult<Unit>
    suspend fun setWifiOnly(enabled: Boolean): WebDavResult<Unit>
    suspend fun testConnection(): WebDavResult<WebDavConnection>
    suspend fun synchronize(direction: HistorySyncDirection = HistorySyncDirection.MERGE, automatic: Boolean = false): HistorySyncOutcome
    fun requestAutomaticSync()
}

/** One application-lifetime operation queue and status stream, shared by manual and automatic callers. */
@OptIn(FlowPreview::class)
class HistorySyncService(
    private val preferences: WebDavPreferences,
    private val passwords: WebDavPasswordStore,
    private val store: HistorySyncStore,
    private val transport: WebDavTransport,
    scope: CoroutineScope,
    private val wifiAvailable: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val codec: HistorySyncCodec = HistorySyncFormat,
) : HistorySyncActions {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(HistorySyncStatus())
    override val state: StateFlow<HistorySyncStatus> = mutableState.asStateFlow()
    private val automaticTriggers = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            try {
                preferences.settings.collect {
                    mutex.withLock {
                        // A queued emission can predate an operation that held the lock; read its committed configuration.
                        val config = preferences.settings.first()
                        val secret = passwords.read()
                        updateConfiguration(config, secret != null && secret.password.isNotEmpty() && secret.accountKey == accountKey(config))
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(ready = true, configurationError = WebDavError.STORAGE) }
            }
        }
        scope.launch {
            automaticTriggers.receiveAsFlow().debounce(AUTOMATIC_DEBOUNCE_MILLIS).collect {
                synchronize(automatic = true)
            }
        }
    }

    override fun requestAutomaticSync() {
        automaticTriggers.trySend(Unit)
    }

    /** Trigger prerequisites can fail before an operation starts; retain a typed, non-blocking result. */
    fun recordAutomaticFailure(error: WebDavError) {
        finish(HistorySyncOutcome.Failure(error))
    }

    override suspend fun saveAccount(address: String, username: String, folder: String, password: String?): WebDavResult<Unit> =
        mutex.withLock {
            result {
                val endpoint = WebDavEndpoint.parse(address, folder).getOrThrow()
                val user = username.trim()
                validateUsername(user)
                val previous = passwords.read()
                val key = accountKey(endpoint, user)
                val value = password?.takeIf { it.isNotEmpty() } ?: previous?.takeIf { it.accountKey == key }?.password
                    ?: throw WebDavException(WebDavError.PASSWORD_REQUIRED)
                withContext(NonCancellable) {
                    passwords.save(WebDavPassword(key, value))
                    try {
                        preferences.saveAccount(endpoint.collection.toString(), user, folder.trim())
                    } catch (exception: Exception) {
                        if (previous == null) passwords.clear() else passwords.save(previous)
                        throw exception
                    }
                }
                val config = preferences.settings.first()
                updateConfiguration(config, hasPassword = true)
            }
        }

    override suspend fun setAutoSync(enabled: Boolean): WebDavResult<Unit> = result { preferences.setAutoSync(enabled) }
    override suspend fun setWifiOnly(enabled: Boolean): WebDavResult<Unit> = result { preferences.setWifiOnly(enabled) }

    override suspend fun testConnection(): WebDavResult<WebDavConnection> = mutex.withLock {
        mutableState.update { it.copy(activity = HistorySyncActivity.Testing) }
        try {
            result { transport.testConnection(account(preferences.settings.first())).getOrThrow() }
        } finally {
            mutableState.update { it.copy(activity = HistorySyncActivity.Idle) }
        }
    }

    override suspend fun synchronize(direction: HistorySyncDirection, automatic: Boolean): HistorySyncOutcome =
        withContext(workDispatcher) { synchronizeLocked(direction, automatic) }

    private suspend fun synchronizeLocked(direction: HistorySyncDirection, automatic: Boolean): HistorySyncOutcome = mutex.withLock {
        var counts = HistorySyncCounts()
        var localChanged = false
        var remoteWritten = false
        try {
            val config = preferences.settings.first()
            if (automatic && (!config.autoSync || config.wifiOnly && !wifiAvailable())) return@withLock HistorySyncOutcome.Skipped
            fun phase(value: HistorySyncPhase) {
                mutableState.update { it.copy(activity = HistorySyncActivity.Syncing(direction, value, automatic)) }
            }
            phase(HistorySyncPhase.CONNECTING)
            val account = account(config)
            transport.testConnection(account).getOrThrow()
            var originalLocal: HistorySyncDocument? = null
            for (attempt in 0..1) {
                phase(HistorySyncPhase.DOWNLOADING)
                val file = transport.getHistory(account).getOrThrow()
                val remote = file.content?.let(codec::decode) ?: HistorySyncDocument.EMPTY
                val receivedAt = now()
                HistorySyncFormat.validateIncomingEventTimes(remote, receivedAt)
                if (originalLocal == null) originalLocal = store.snapshot()
                phase(HistorySyncPhase.MERGING)
                phase(HistorySyncPhase.WRITING_LOCAL)
                val merged = store.apply(remote, direction, receivedAt)
                localChanged = localChanged || merged != originalLocal
                val planned = HistoryMerge.counts(originalLocal, remote, merged)
                val mergedIds = merged.records.map { it.comicId }.toSet()
                counts = planned.copy(uploaded = 0,
                    downloaded = if (direction == HistorySyncDirection.LOCAL_TO_REMOTE) 0 else planned.downloaded,
                    removed = originalLocal.records.count { it.comicId !in mergedIds })
                if (direction == HistorySyncDirection.REMOTE_TO_LOCAL) {
                    return@withLock finishSuccess(counts)
                }
                phase(HistorySyncPhase.UPLOADING)
                val upload = transport.putHistory(account, codec.encode(HistoryMerge.pruneTombstones(merged, now())), file.revision)
                if (upload is WebDavResult.Failure && upload.error == WebDavError.CHANGED_ELSEWHERE && attempt == 0) {
                    phase(HistorySyncPhase.RETRYING)
                    continue
                }
                upload.getOrThrow()
                remoteWritten = true
                counts = counts.copy(uploaded = planned.uploaded, removed = planned.removed)
                // Only a successful remote write permits dropping old local deletion events.
                store.pruneTombstones(HistoryMerge.pruningThreshold(now()))
                return@withLock finishSuccess(counts)
            }
            error("Unreachable sync attempt")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: WebDavException) {
            finish(HistorySyncOutcome.Failure(failure.error, failure.httpStatus, counts, localChanged, remoteWritten))
        } catch (_: Exception) {
            finish(HistorySyncOutcome.Failure(WebDavError.STORAGE, counts = counts, localChanged = localChanged, remoteWritten = remoteWritten))
        } finally {
            mutableState.update { it.copy(activity = HistorySyncActivity.Idle) }
        }
    }

    private fun updateConfiguration(config: WebDavSettings, hasPassword: Boolean) {
        mutableState.update {
            val identityChanged = accountKey(it.config) != accountKey(config)
            it.copy(config = config, hasPassword = hasPassword, ready = true, configurationError = null,
                lastSuccessAt = config.lastSyncedAt,
                lastAttemptAt = if (identityChanged) null else it.lastAttemptAt,
                lastResult = if (identityChanged) null else it.lastResult)
        }
    }

    private suspend fun finishSuccess(counts: HistorySyncCounts): HistorySyncOutcome {
        val time = now()
        preferences.setLastSyncedAt(time)
        updateConfiguration(preferences.settings.first(), hasPassword = true)
        return finish(HistorySyncOutcome.Success(counts), time)
    }

    private fun finish(outcome: HistorySyncOutcome, time: Long = now()): HistorySyncOutcome {
        mutableState.update { it.copy(lastAttemptAt = time, lastSuccessAt = if (outcome is HistorySyncOutcome.Success) time else it.lastSuccessAt,
            lastResult = outcome) }
        return outcome
    }

    private suspend fun account(config: WebDavSettings): WebDavAccount {
        val endpoint = WebDavEndpoint.parse(config.address, config.folder).getOrThrow()
        validateUsername(config.username)
        val saved = passwords.read()?.takeIf { it.accountKey == accountKey(endpoint, config.username) }
            ?: throw WebDavException(WebDavError.PASSWORD_REQUIRED)
        return WebDavAccount(endpoint, config.username, saved.password)
    }

    private suspend fun <T> result(block: suspend () -> T): WebDavResult<T> = try {
        WebDavResult.Success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: WebDavException) {
        WebDavResult.Failure(failure.error, failure.httpStatus)
    } catch (_: Exception) {
        WebDavResult.Failure(WebDavError.STORAGE)
    }

    companion object {
        const val AUTOMATIC_DEBOUNCE_MILLIS = 30_000L

        internal fun accountKey(config: WebDavSettings): String? = when (val endpoint = WebDavEndpoint.parse(config.address, config.folder)) {
            is WebDavResult.Failure -> null
            is WebDavResult.Success -> accountKey(endpoint.value, config.username)
        }

        private fun accountKey(endpoint: WebDavEndpoint, username: String): String = "${endpoint.collection}\n$username"

        private fun validateUsername(username: String) {
            if (username.isBlank() || username.any { it == ':' || it.isISOControl() }) throw WebDavException(WebDavError.INVALID_USERNAME)
        }
    }
}
