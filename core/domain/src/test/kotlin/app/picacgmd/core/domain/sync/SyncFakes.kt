package app.picacgmd.core.domain.sync

import app.picacgmd.core.data.settings.WebDavSettings
import app.picacgmd.core.network.webdav.WebDavAccount
import app.picacgmd.core.network.webdav.WebDavConnection
import app.picacgmd.core.network.webdav.WebDavRemoteFile
import app.picacgmd.core.network.webdav.WebDavResult
import app.picacgmd.core.network.webdav.WebDavRevision
import app.picacgmd.core.network.webdav.WebDavTransport
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

internal fun syncRecord(id: String, updatedAt: Long = 1): HistorySyncRecord =
    HistorySyncRecord(id, "Title $id", createdAt = 1, updatedAt = updatedAt)

internal fun syncDocument(vararg records: HistorySyncRecord, tombstones: List<HistorySyncTombstone> = emptyList()): HistorySyncDocument =
    HistorySyncDocument(1, records.toList(), tombstones)

internal class SyncFixture(scope: CoroutineScope, local: HistorySyncDocument = HistorySyncDocument.EMPTY,
    remote: HistorySyncDocument = HistorySyncDocument.EMPTY, autoSync: Boolean = false,
    workDispatcher: CoroutineDispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
    codec: HistorySyncCodec = HistorySyncFormat) {
    val preferences = FakeWebDavPreferences(WebDavSettings("https://localhost/dav/", "test-reader", autoSync = autoSync))
    val passwords = FakeWebDavPasswords(WebDavPassword(HistorySyncService.accountKey(preferences.settings.value)!!, "private-webdav-password"))
    val store = FakeHistorySyncStore(local)
    val transport = FakeWebDavTransport(remote)
    var wifi = true
    var wifiChecks = 0
    var time = 100L
    val service = HistorySyncService(preferences, passwords, store, transport, scope,
        wifiAvailable = { wifiChecks++; wifi }, now = { time }, workDispatcher = workDispatcher, codec = codec)
}

internal class FakeWebDavPreferences(config: WebDavSettings) : WebDavPreferences {
    override val settings = MutableStateFlow(config)
    var beforeSave: suspend () -> Unit = {}
    var beforeLastSyncSave: suspend () -> Unit = {}
    override suspend fun saveAccount(address: String, username: String, folder: String) {
        beforeSave()
        val previous = settings.value
        settings.value = previous.copy(address = address, username = username, folder = folder,
            lastSyncedAt = previous.lastSyncedAt.takeIf { previous.address == address && previous.username == username })
    }
    override suspend fun setAutoSync(enabled: Boolean) { settings.value = settings.value.copy(autoSync = enabled) }
    override suspend fun setWifiOnly(enabled: Boolean) { settings.value = settings.value.copy(wifiOnly = enabled) }
    override suspend fun setLastSyncedAt(timestamp: Long) {
        beforeLastSyncSave()
        settings.value = settings.value.copy(lastSyncedAt = timestamp)
    }
}

internal class FakeWebDavPasswords(var value: WebDavPassword?) : WebDavPasswordStore {
    var saves = 0
    override suspend fun read(): WebDavPassword? = value
    override suspend fun save(password: WebDavPassword) { saves++; value = password }
    override suspend fun clear() { value = null }
}

internal class FakeHistorySyncStore(var document: HistorySyncDocument) : HistorySyncStore {
    var beforeApply: suspend () -> Unit = {}
    var beforeCommit: suspend () -> Unit = {}
    var beforePrune: suspend () -> Unit = {}
    var snapshots = 0
    var commits = 0
    override suspend fun snapshot(): HistorySyncDocument { snapshots++; return document }
    override suspend fun apply(remote: HistorySyncDocument, direction: HistorySyncDirection, now: Long): HistorySyncDocument {
        HistorySyncFormat.validateIncomingEventTimes(remote, now)
        beforeApply()
        val merged = when (direction) {
            HistorySyncDirection.MERGE -> HistoryMerge.merge(document, remote)
            HistorySyncDirection.LOCAL_TO_REMOTE -> HistoryMerge.overwrite(document, remote, now)
            HistorySyncDirection.REMOTE_TO_LOCAL -> HistoryMerge.overwrite(remote, document, now)
        }
        beforeCommit()
        currentCoroutineContext().ensureActive()
        document = merged
        commits++
        return merged
    }
    override suspend fun pruneTombstones(before: Long) {
        beforePrune()
        document = document.copy(tombstones = document.tombstones.filter { it.deletedAt >= before })
    }
}

internal class FakeWebDavTransport(var document: HistorySyncDocument) : WebDavTransport {
    var connections = 0
    var gets = 0
    var puts = 0
    var missing = false
    var etag = "\"one\""
    var contentOverride: ByteArray? = null
    var connectionFailure: WebDavResult.Failure? = null
    val putFailures = ArrayDeque<WebDavResult.Failure>()
    val revisions = mutableListOf<WebDavRevision>()
    var beforeGet: suspend () -> Unit = {}
    var beforePut: suspend (Int) -> Unit = {}
    override suspend fun testConnection(account: WebDavAccount): WebDavResult<WebDavConnection> {
        connections++
        return connectionFailure ?: WebDavResult.Success(WebDavConnection(false))
    }
    override suspend fun getHistory(account: WebDavAccount): WebDavResult<WebDavRemoteFile> {
        gets++
        beforeGet()
        return WebDavResult.Success(if (missing) WebDavRemoteFile(null, WebDavRevision.Missing) else
            WebDavRemoteFile(contentOverride ?: HistorySyncFormat.encode(document), WebDavRevision.Existing(etag)))
    }
    override suspend fun putHistory(account: WebDavAccount, content: ByteArray, revision: WebDavRevision): WebDavResult<Unit> {
        puts++
        revisions += revision
        beforePut(puts)
        putFailures.removeFirstOrNull()?.let { return it }
        document = HistorySyncFormat.decode(content)
        missing = false
        return WebDavResult.Success(Unit)
    }
}
