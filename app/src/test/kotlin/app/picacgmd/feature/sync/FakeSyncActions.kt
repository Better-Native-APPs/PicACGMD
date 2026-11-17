package app.picacgmd.feature.sync

import app.picacgmd.core.data.settings.WebDavSettings
import app.picacgmd.core.domain.sync.HistorySyncActions
import app.picacgmd.core.domain.sync.HistorySyncActivity
import app.picacgmd.core.domain.sync.HistorySyncCounts
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncOutcome
import app.picacgmd.core.domain.sync.HistorySyncPhase
import app.picacgmd.core.domain.sync.HistorySyncStatus
import app.picacgmd.core.network.webdav.WebDavConnection
import app.picacgmd.core.network.webdav.WebDavResult
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeSyncActions(config: WebDavSettings = WebDavSettings("https://localhost/dav/", "test-reader")) : HistorySyncActions {
    override val state = MutableStateFlow(HistorySyncStatus(config = config,
        hasPassword = true, ready = true, lastSuccessAt = config.lastSyncedAt))
    var savedPassword: String? = null
    var beforeSave: suspend () -> Unit = {}
    var saveResult: WebDavResult<Unit> = WebDavResult.Success(Unit)
    var switchResult: WebDavResult<Unit> = WebDavResult.Success(Unit)
    var connectionResult: WebDavResult<WebDavConnection> = WebDavResult.Success(WebDavConnection(false))
    var syncResult: HistorySyncOutcome = HistorySyncOutcome.Success(HistorySyncCounts(2, 3, 4))
    var beforeTest: suspend () -> Unit = {}
    var beforeSync: suspend () -> Unit = {}
    var connections = 0
    var automaticRequests = 0
    val directions = mutableListOf<HistorySyncDirection>()
    val automatic = mutableListOf<Boolean>()

    override suspend fun saveAccount(address: String, username: String, folder: String, password: String?): WebDavResult<Unit> {
        savedPassword = password
        beforeSave()
        if (saveResult is WebDavResult.Success) {
            val previous = state.value
            val identityChanged = !sameWebDavAccount(WebDavAccountDraft(previous.config.address, previous.config.username),
                WebDavAccountDraft(address, username))
            state.value = previous.copy(config = previous.config.copy(
                address = address.trim(), username = username.trim(), folder = folder.trim(),
                lastSyncedAt = previous.config.lastSyncedAt.takeUnless { identityChanged }), hasPassword = true,
                lastSuccessAt = previous.lastSuccessAt.takeUnless { identityChanged },
                lastAttemptAt = previous.lastAttemptAt.takeUnless { identityChanged },
                lastResult = previous.lastResult.takeUnless { identityChanged })
        }
        return saveResult
    }
    override suspend fun setAutoSync(enabled: Boolean): WebDavResult<Unit> {
        if (switchResult is WebDavResult.Success) state.value = state.value.copy(config = state.value.config.copy(autoSync = enabled))
        return switchResult
    }
    override suspend fun setWifiOnly(enabled: Boolean): WebDavResult<Unit> {
        if (switchResult is WebDavResult.Success) state.value = state.value.copy(config = state.value.config.copy(wifiOnly = enabled))
        return switchResult
    }
    override suspend fun testConnection(): WebDavResult<WebDavConnection> {
        connections++
        state.value = state.value.copy(activity = HistorySyncActivity.Testing)
        try { beforeTest(); return connectionResult } finally { state.value = state.value.copy(activity = HistorySyncActivity.Idle) }
    }
    override suspend fun synchronize(direction: HistorySyncDirection, automatic: Boolean): HistorySyncOutcome {
        directions += direction
        this.automatic += automatic
        state.value = state.value.copy(activity = HistorySyncActivity.Syncing(direction, HistorySyncPhase.DOWNLOADING, automatic))
        try {
            beforeSync()
            state.value = state.value.copy(config = state.value.config.copy(
                lastSyncedAt = if (syncResult is HistorySyncOutcome.Success) 123 else state.value.config.lastSyncedAt),
                lastResult = syncResult, lastAttemptAt = 123,
                lastSuccessAt = if (syncResult is HistorySyncOutcome.Success) 123 else state.value.lastSuccessAt)
            return syncResult
        } finally { state.value = state.value.copy(activity = HistorySyncActivity.Idle) }
    }
    override fun requestAutomaticSync() { automaticRequests++ }
}
